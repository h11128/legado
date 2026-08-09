package io.legado.app.ui.book.read

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemReviewCommentBinding
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.ReviewRuleParser
import io.legado.app.model.jsSource.JsSourceReview
import io.legado.app.model.review.ProviderParaRef
import io.legado.app.model.review.ReviewOverlaySessionStore
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import io.legado.app.utils.windowSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import splitties.systemservices.windowManager
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * RFC-004 §12: merged chapter-bucket detail.
 * Per-provider sections (so small sources are not drowned), page-1 + in-dialog load-more,
 * honest “sum / not deduped” subtitle. Row click still opens [ReviewDetailDialog].
 */
class ReviewMergeDetailDialog() : BaseDialogFragment(R.layout.dialog_recycler_view) {

    constructor(totalCount: Int, sourceCount: Int) : this() {
        arguments = Bundle().apply {
            putInt(ARG_TOTAL_COUNT, totalCount)
            putInt(ARG_SOURCE_COUNT, sourceCount)
        }
    }

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy {
        MergeAdapter(requireContext(), ::onRowClick)
    }
    private var totalCount: Int = 0
    private var sourceCount: Int = 0
    private val providerStates = LinkedHashMap<String, ProviderState>()
    private var loadingMoreKey: String? = null

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.16f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
        }
        val windowHeight = requireContext().windowManager.windowSize.heightPixels
        if (windowHeight > 0) {
            setLayout(1f, (windowHeight * 0.62f).roundToInt())
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        totalCount = arguments?.getInt(ARG_TOTAL_COUNT) ?: 0
        sourceCount = arguments?.getInt(ARG_SOURCE_COUNT) ?: 0
        binding.root.setBackgroundResource(R.drawable.bg_dialog_round_top)
        binding.dragHandle.visible()
        binding.toolBar.setBackgroundResource(R.drawable.bg_review_toolbar)
        binding.toolBar.updateLayoutParams<ViewGroup.LayoutParams> {
            height = 48.dpToPx()
        }
        binding.toolBar.minimumHeight = 0
        binding.toolBar.title = getString(R.string.review_merge_title, sourceCount.coerceAtLeast(1))
        binding.toolBar.subtitle = if (totalCount > 0) {
            getString(R.string.review_merge_subtitle_sum, totalCount)
        } else {
            null
        }
        binding.toolBar.setNavigationIcon(R.drawable.ic_baseline_close)
        binding.toolBar.navigationIcon?.setTint(getCompatColor(R.color.secondaryText))
        binding.toolBar.setNavigationOnClickListener { dismiss() }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        loadMergedPage1()
    }

    private fun onRowClick(row: MergeRow) {
        when (row) {
            is MergeRow.LoadMore -> loadMore(row.providerSourceKey)
            is MergeRow.Comment -> openProviderDetail(row)
            else -> Unit
        }
    }

    private fun openProviderDetail(row: MergeRow.Comment) {
        if (row.paraData.isBlank()) return
        val providerSource = appDb.bookSourceDao.getBookSource(row.providerSourceKey)
        if (providerSource == null) {
            toastOnUi(R.string.review_empty)
            return
        }
        val ruleHash = if (providerSource.isJsSource()) {
            providerSource.mainJs.hashCode()
        } else {
            providerSource.ruleReview?.hashCode() ?: run {
                toastOnUi(R.string.review_rule_missing)
                return
            }
        }
        showDialogFragment(
            ReviewDetailDialog(
                paragraphNum = row.paragraphNum,
                totalCount = row.providerBucketCount.coerceAtLeast(1),
                chapterIndex = row.chapterIndex,
                paragraphData = row.paraData,
                bookUrl = row.bookUrl,
                sourceKey = row.providerSourceKey,
                ruleHash = ruleHash,
            )
        )
    }

    private fun loadMergedPage1() {
        val merge = ReviewOverlaySessionStore.getMerge()
        val providers = merge?.providersWithBucket().orEmpty()
        if (providers.isEmpty()) {
            toastOnUi(R.string.review_empty)
            dismiss()
            return
        }
        binding.rotateLoading.visible()
        binding.tvMsg.gone()
        Coroutine.async(lifecycleScope, IO) {
            coroutineScope {
                providers.map { session ->
                    async { loadProviderPage(session, page = 1, nextPageUrl = null) }
                }.awaitAll()
            }
        }.onSuccess(Main) { blocks ->
            binding.rotateLoading.gone()
            providerStates.clear()
            for (block in blocks) {
                if (block == null) continue
                providerStates[block.providerSourceKey] = ProviderState(
                    session = block.session,
                    sourceLabel = block.sourceLabel,
                    items = block.items.toMutableList(),
                    error = block.error,
                    page = 1,
                    nextPageUrl = block.nextPageUrl,
                    hasMore = block.hasMore,
                    bookUrl = block.bookUrl,
                    chapterIndex = block.chapterIndex,
                    paragraphNum = block.paragraphNum,
                    paraData = block.paraData,
                    providerBucketCount = block.providerBucketCount,
                )
            }
            renderRows()
        }.onError {
            binding.rotateLoading.gone()
            AppLog.put("ReviewOverlay merge detail failed\n${it.localizedMessage}", it)
            toastOnUi(it.localizedMessage ?: getString(R.string.review_empty))
        }
    }

    private fun loadMore(providerKey: String) {
        val state = providerStates[providerKey] ?: return
        if (!state.hasMore || loadingMoreKey != null) return
        loadingMoreKey = providerKey
        renderRows()
        val nextPage = state.page + 1
        Coroutine.async(lifecycleScope, IO) {
            loadProviderPage(state.session, page = nextPage, nextPageUrl = state.nextPageUrl)
        }.onSuccess(Main) { block ->
            loadingMoreKey = null
            if (block == null || block.error) {
                state.hasMore = false
                renderRows()
                return@onSuccess
            }
            if (block.items.isEmpty()) {
                state.hasMore = false
                state.nextPageUrl = null
            } else {
                state.items.addAll(block.items)
                state.page = nextPage
                state.nextPageUrl = block.nextPageUrl
                state.hasMore = block.hasMore
            }
            renderRows()
        }.onError {
            loadingMoreKey = null
            state.hasMore = false
            renderRows()
            AppLog.put("ReviewOverlay merge load-more failed\n${it.localizedMessage}", it)
        }
    }

    private fun renderRows() {
        val rows = ArrayList<MergeRow>()
        for ((_, state) in providerStates) {
            rows.add(
                MergeRow.Header(
                    sourceLabel = state.sourceLabel,
                    bucketCount = state.providerBucketCount.coerceAtLeast(state.items.size),
                    providerSourceKey = state.session.providerSourceKey,
                )
            )
            if (state.error && state.items.isEmpty()) {
                rows.add(
                    MergeRow.Error(
                        sourceLabel = state.sourceLabel,
                        message = getString(R.string.review_merge_provider_fail),
                        providerSourceKey = state.session.providerSourceKey,
                    )
                )
                continue
            }
            for (item in state.items) {
                rows.add(
                    MergeRow.Comment(
                        sourceLabel = state.sourceLabel,
                        name = item.name.orEmpty().ifBlank { state.sourceLabel },
                        content = item.content.orEmpty(),
                        providerSourceKey = state.session.providerSourceKey,
                        bookUrl = state.bookUrl,
                        chapterIndex = state.chapterIndex,
                        paragraphNum = state.paragraphNum,
                        paraData = state.paraData,
                        providerBucketCount = state.providerBucketCount,
                    )
                )
            }
            if (state.hasMore) {
                rows.add(
                    MergeRow.LoadMore(
                        sourceLabel = state.sourceLabel,
                        providerSourceKey = state.session.providerSourceKey,
                        loading = loadingMoreKey == state.session.providerSourceKey,
                    )
                )
            }
        }
        if (rows.isEmpty()) {
            binding.tvMsg.visible()
            binding.tvMsg.text = getString(R.string.review_empty)
        } else {
            binding.tvMsg.gone()
            adapter.submit(rows)
        }
    }

    private suspend fun loadProviderPage(
        session: ReviewOverlaySessionStore.Active,
        page: Int,
        nextPageUrl: String?,
    ): ProviderBlock? {
        val ref: ProviderParaRef = session.chapterBucket ?: return null
        val source = appDb.bookSourceDao.getBookSource(session.providerSourceKey)
            ?: return ProviderBlock(
                session = session,
                sourceLabel = sessionLabel(session),
                items = emptyList(),
                error = true,
                nextPageUrl = null,
                hasMore = false,
                providerSourceKey = session.providerSourceKey,
                bookUrl = session.providerBook.bookUrl,
                chapterIndex = session.providerChapterIndex,
                paragraphNum = ref.providerParaIndex,
                paraData = ref.paraData,
                providerBucketCount = 0,
            )
        val label = source.bookSourceName.ifBlank { source.bookSourceUrl }.take(12)
        val bucketCount = session.chapterBucketCount.coerceAtLeast(0)
        return try {
            val book = resolveBook(session.providerBook.bookUrl) ?: session.providerBook
            val chapter = resolveChapter(session.providerBook.bookUrl, session.providerChapterIndex)
                ?: session.providerChapter
            val pageResult = if (source.isJsSource()) {
                JsSourceReview.getReviewDetailAwait(
                    source = source,
                    book = book,
                    chapter = chapter,
                    paragraphIndex = ref.providerParaIndex,
                    paragraphData = ref.paraData,
                    page = page,
                )
            } else {
                fetchNativeDetail(source, book, chapter, ref, page, nextPageUrl)
            }
            val items = pageResult?.items.orEmpty()
            val next = pageResult?.nextPageUrl
            ProviderBlock(
                session = session,
                sourceLabel = label,
                items = items,
                error = false,
                nextPageUrl = next,
                hasMore = !next.isNullOrBlank(),
                providerSourceKey = session.providerSourceKey,
                bookUrl = book.bookUrl,
                chapterIndex = session.providerChapterIndex,
                paragraphNum = ref.providerParaIndex,
                paraData = ref.paraData,
                providerBucketCount = if (items.isEmpty() && page == 1) {
                    bucketCount
                } else {
                    bucketCount.coerceAtLeast(items.size)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.put(
                "ReviewOverlay merge skip=${session.providerSourceKey} detail\n${e.localizedMessage}",
                e,
            )
            ProviderBlock(
                session = session,
                sourceLabel = label,
                items = emptyList(),
                error = true,
                nextPageUrl = null,
                hasMore = false,
                providerSourceKey = session.providerSourceKey,
                bookUrl = session.providerBook.bookUrl,
                chapterIndex = session.providerChapterIndex,
                paragraphNum = ref.providerParaIndex,
                paraData = ref.paraData,
                providerBucketCount = bucketCount,
            )
        }
    }

    private suspend fun fetchNativeDetail(
        source: BookSource,
        book: Book,
        chapter: BookChapter,
        ref: ProviderParaRef,
        page: Int,
        nextPageUrl: String?,
    ): ReviewRuleParser.DetailPage? {
        val rule = source.ruleReview ?: return null
        val firstPageUrlRule = rule.reviewDetailUrl?.takeIf { it.isNotBlank() } ?: return null
        if (rule.detailListRule.isNullOrBlank() || rule.detailContentRule.isNullOrBlank()) {
            return null
        }
        val nextPageUrlRule = rule.reviewDetailNextPageUrl?.takeIf { it.isNotBlank() }
        if (page > 1 && nextPageUrl.isNullOrBlank() && nextPageUrlRule == null) return null
        val detailUrlRule = when {
            page > 1 && !nextPageUrl.isNullOrBlank() -> nextPageUrl
            page > 1 -> nextPageUrlRule ?: firstPageUrlRule
            else -> firstPageUrlRule
        }
        val paraIndex = ref.providerParaIndex.toString()
        val analyzeUrl = AnalyzeUrl(
            detailUrlRule,
            page = page,
            extraParams = mapOf(
                "paraIndex" to paraIndex,
                "paraData" to ref.paraData,
                "page" to page.toString(),
            ),
            baseUrl = chapter.url,
            source = source,
            ruleData = book,
            chapter = chapter,
            coroutineContext = coroutineContext,
        )
        val body = analyzeUrl.getStrResponseAwait(useWebView = false).body ?: return null
        return ReviewRuleParser.parseDetailPage(
            body = body,
            rule = rule,
            nextPageRule = nextPageUrlRule,
            baseUrl = analyzeUrl.url,
            source = source,
            book = book,
            chapter = chapter,
            context = coroutineContext,
            paraIndex = paraIndex,
            paraData = ref.paraData,
            page = page.toString(),
        )
    }

    private fun resolveBook(bookUrl: String): Book? =
        ReviewOverlaySessionStore.providerBookFor(bookUrl)

    private fun resolveChapter(bookUrl: String, chapterIndex: Int): BookChapter? =
        ReviewOverlaySessionStore.providerChapter(bookUrl, chapterIndex)

    private fun sessionLabel(session: ReviewOverlaySessionStore.Active): String {
        val source = appDb.bookSourceDao.getBookSource(session.providerSourceKey)
        return source?.bookSourceName?.ifBlank { source.bookSourceUrl }?.take(12)
            ?: session.providerSourceKey.take(12)
    }

    private data class ProviderState(
        val session: ReviewOverlaySessionStore.Active,
        val sourceLabel: String,
        val items: MutableList<ReviewRuleParser.DetailItem>,
        var error: Boolean,
        var page: Int,
        var nextPageUrl: String?,
        var hasMore: Boolean,
        val bookUrl: String,
        val chapterIndex: Int,
        val paragraphNum: Int,
        val paraData: String,
        val providerBucketCount: Int,
    )

    private data class ProviderBlock(
        val session: ReviewOverlaySessionStore.Active,
        val sourceLabel: String,
        val items: List<ReviewRuleParser.DetailItem>,
        val error: Boolean,
        val nextPageUrl: String?,
        val hasMore: Boolean,
        val providerSourceKey: String,
        val bookUrl: String,
        val chapterIndex: Int,
        val paragraphNum: Int,
        val paraData: String,
        val providerBucketCount: Int,
    )

    private sealed class MergeRow {
        data class Header(
            val sourceLabel: String,
            val bucketCount: Int,
            val providerSourceKey: String,
        ) : MergeRow()

        data class Comment(
            val sourceLabel: String,
            val name: String,
            val content: String,
            val providerSourceKey: String,
            val bookUrl: String,
            val chapterIndex: Int,
            val paragraphNum: Int,
            val paraData: String,
            val providerBucketCount: Int,
        ) : MergeRow()

        data class LoadMore(
            val sourceLabel: String,
            val providerSourceKey: String,
            val loading: Boolean,
        ) : MergeRow()

        data class Error(
            val sourceLabel: String,
            val message: String,
            val providerSourceKey: String,
        ) : MergeRow()
    }

    private class MergeAdapter(
        private val context: Context,
        private val onRowClick: (MergeRow) -> Unit,
    ) : RecyclerView.Adapter<MergeAdapter.VH>() {

        private val items = ArrayList<MergeRow>()

        fun submit(rows: List<MergeRow>) {
            items.clear()
            items.addAll(rows)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is MergeRow.Header -> VT_HEADER
            is MergeRow.LoadMore -> VT_MORE
            is MergeRow.Error -> VT_ERROR
            is MergeRow.Comment -> VT_COMMENT
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemReviewCommentBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.binding.ivAvatar.gone()
            holder.binding.ivMedia.gone()
            holder.binding.tvAudio.gone()
            holder.binding.tvTime.gone()
            holder.binding.llLikeArea.gone()
            holder.binding.llBadges.removeAllViews()
            when (item) {
                is MergeRow.Header -> {
                    holder.binding.tvName.text = context.getString(
                        R.string.review_merge_section,
                        item.sourceLabel,
                        item.bucketCount,
                    )
                    holder.binding.tvContent.gone()
                    holder.binding.llBadges.gone()
                    holder.itemView.isClickable = false
                    holder.itemView.setOnClickListener(null)
                    holder.binding.tvName.setTextColor(context.getCompatColor(R.color.primaryText))
                }
                is MergeRow.Error -> {
                    holder.binding.tvName.text = item.sourceLabel
                    holder.binding.tvContent.visible()
                    holder.binding.tvContent.text = item.message
                    holder.binding.llBadges.gone()
                    holder.itemView.isClickable = false
                    holder.itemView.setOnClickListener(null)
                }
                is MergeRow.LoadMore -> {
                    holder.binding.tvName.text = if (item.loading) {
                        "…"
                    } else {
                        context.getString(R.string.review_merge_load_more, item.sourceLabel)
                    }
                    holder.binding.tvContent.gone()
                    holder.binding.llBadges.gone()
                    holder.itemView.isClickable = !item.loading
                    holder.itemView.setOnClickListener {
                        if (!item.loading) onRowClick(item)
                    }
                    holder.binding.tvName.setTextColor(context.getCompatColor(R.color.accent))
                }
                is MergeRow.Comment -> {
                    holder.binding.tvName.text = item.name
                    holder.binding.tvContent.visible()
                    holder.binding.tvContent.text = item.content
                    holder.binding.llBadges.visible()
                    val badge = TextView(context).apply {
                        text = item.sourceLabel
                        setBackgroundResource(R.drawable.bg_review_badge_chip)
                        setTextColor(context.getCompatColor(R.color.secondaryText))
                        textSize = 11f
                        setPadding(8.dpToPx(), 2.dpToPx(), 8.dpToPx(), 2.dpToPx())
                    }
                    holder.binding.llBadges.addView(badge)
                    holder.itemView.isClickable = item.paraData.isNotBlank()
                    holder.itemView.setOnClickListener {
                        if (item.paraData.isNotBlank()) onRowClick(item)
                    }
                    holder.binding.tvName.setTextColor(context.getCompatColor(R.color.primaryText))
                }
            }
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemReviewCommentBinding) : RecyclerView.ViewHolder(binding.root)

        companion object {
            private const val VT_HEADER = 1
            private const val VT_COMMENT = 2
            private const val VT_MORE = 3
            private const val VT_ERROR = 4
        }
    }

    companion object {
        private const val ARG_TOTAL_COUNT = "totalCount"
        private const val ARG_SOURCE_COUNT = "sourceCount"
    }
}
