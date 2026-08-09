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
 * RFC-004 §12: merged chapter-bucket detail (page-1 preview per provider, source badges).
 * Row click opens [ReviewDetailDialog] for that provider (A19 + full paging there).
 * In-dialog per-provider load-more remains a follow-up (§12.4.3).
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
        MergeAdapter(requireContext()) { row -> openProviderDetail(row) }
    }
    private var totalCount: Int = 0
    private var sourceCount: Int = 0

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
            height = 42.dpToPx()
        }
        binding.toolBar.minimumHeight = 0
        binding.toolBar.title = getString(R.string.review_merge_title, sourceCount.coerceAtLeast(1))
        binding.toolBar.subtitle = null
        if (totalCount > 0) {
            val countView = TextView(requireContext()).apply {
                text = getString(R.string.review_total_count, totalCount)
                setTextColor(getCompatColor(R.color.secondaryText))
                textSize = 14f
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
            }
            val lp = androidx.appcompat.widget.Toolbar.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { marginEnd = 8.dpToPx() }
            binding.toolBar.addView(countView, lp)
        }
        binding.toolBar.setNavigationIcon(R.drawable.ic_baseline_close)
        binding.toolBar.navigationIcon?.setTint(getCompatColor(R.color.secondaryText))
        binding.toolBar.setNavigationOnClickListener { dismiss() }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        loadMergedPage1()
    }

    private fun openProviderDetail(row: MergeRow) {
        if (row.isError || row.paraData.isBlank()) return
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
                    async {
                        loadProviderPage1(session)
                    }
                }.awaitAll()
            }
        }.onSuccess(Main) { blocks ->
            binding.rotateLoading.gone()
            val rows = ArrayList<MergeRow>()
            for (block in blocks) {
                if (block == null) continue
                if (block.error) {
                    rows.add(
                        MergeRow(
                            sourceLabel = block.sourceLabel,
                            name = block.sourceLabel,
                            content = getString(R.string.review_merge_provider_fail),
                            isError = true,
                            providerSourceKey = block.providerSourceKey,
                            bookUrl = block.bookUrl,
                            chapterIndex = block.chapterIndex,
                            paragraphNum = -1,
                            paraData = "",
                            providerBucketCount = 0,
                        )
                    )
                    continue
                }
                for (item in block.items) {
                    rows.add(
                        MergeRow(
                            sourceLabel = block.sourceLabel,
                            name = item.name.orEmpty().ifBlank { block.sourceLabel },
                            content = item.content.orEmpty(),
                            isError = false,
                            providerSourceKey = block.providerSourceKey,
                            bookUrl = block.bookUrl,
                            chapterIndex = block.chapterIndex,
                            paragraphNum = block.paragraphNum,
                            paraData = block.paraData,
                            providerBucketCount = block.providerBucketCount,
                        )
                    )
                }
            }
            if (rows.isEmpty()) {
                binding.tvMsg.visible()
                binding.tvMsg.text = getString(R.string.review_empty)
            } else {
                adapter.submit(rows)
            }
        }.onError {
            binding.rotateLoading.gone()
            AppLog.put("ReviewOverlay merge detail failed\n${it.localizedMessage}", it)
            toastOnUi(it.localizedMessage ?: getString(R.string.review_empty))
        }
    }

    private suspend fun loadProviderPage1(
        session: ReviewOverlaySessionStore.Active,
    ): ProviderBlock? {
        val ref: ProviderParaRef = session.chapterBucket ?: return null
        val source = appDb.bookSourceDao.getBookSource(session.providerSourceKey)
            ?: return ProviderBlock(
                sourceLabel = sessionLabel(session),
                items = emptyList(),
                error = true,
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
            val items = if (source.isJsSource()) {
                JsSourceReview.getReviewDetailAwait(
                    source = source,
                    book = book,
                    chapter = chapter,
                    paragraphIndex = ref.providerParaIndex,
                    paragraphData = ref.paraData,
                    page = 1,
                )?.items.orEmpty()
            } else {
                fetchNativeDetail(source, book, chapter, ref)
            }
            ProviderBlock(
                sourceLabel = label,
                items = items,
                error = false,
                providerSourceKey = session.providerSourceKey,
                bookUrl = book.bookUrl,
                chapterIndex = session.providerChapterIndex,
                paragraphNum = ref.providerParaIndex,
                paraData = ref.paraData,
                providerBucketCount = bucketCount.coerceAtLeast(items.size).coerceAtLeast(1),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.put(
                "ReviewOverlay merge skip=${session.providerSourceKey} detail\n${e.localizedMessage}",
                e,
            )
            ProviderBlock(
                sourceLabel = label,
                items = emptyList(),
                error = true,
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
    ): List<ReviewRuleParser.DetailItem> {
        val rule = source.ruleReview ?: return emptyList()
        val detailUrl = rule.reviewDetailUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (rule.detailListRule.isNullOrBlank() || rule.detailContentRule.isNullOrBlank()) {
            return emptyList()
        }
        val paraIndex = ref.providerParaIndex.toString()
        val analyzeUrl = AnalyzeUrl(
            detailUrl,
            page = 1,
            extraParams = mapOf(
                "paraIndex" to paraIndex,
                "paraData" to ref.paraData,
                "page" to "1",
            ),
            baseUrl = chapter.url,
            source = source,
            ruleData = book,
            chapter = chapter,
            coroutineContext = coroutineContext,
        )
        val body = analyzeUrl.getStrResponseAwait(useWebView = false).body ?: return emptyList()
        return ReviewRuleParser.parseDetailPage(
            body = body,
            rule = rule,
            nextPageRule = rule.reviewDetailNextPageUrl,
            baseUrl = analyzeUrl.url,
            source = source,
            book = book,
            chapter = chapter,
            context = coroutineContext,
            paraIndex = paraIndex,
            paraData = ref.paraData,
            page = "1",
        ).items
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

    private data class ProviderBlock(
        val sourceLabel: String,
        val items: List<ReviewRuleParser.DetailItem>,
        val error: Boolean,
        val providerSourceKey: String,
        val bookUrl: String,
        val chapterIndex: Int,
        val paragraphNum: Int,
        val paraData: String,
        val providerBucketCount: Int,
    )

    private data class MergeRow(
        val sourceLabel: String,
        val name: String,
        val content: String,
        val isError: Boolean,
        val providerSourceKey: String,
        val bookUrl: String,
        val chapterIndex: Int,
        val paragraphNum: Int,
        val paraData: String,
        val providerBucketCount: Int,
    )

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
            holder.binding.tvName.text = item.name
            holder.binding.tvContent.text = item.content
            holder.binding.llBadges.removeAllViews()
            if (item.sourceLabel.isNotBlank()) {
                holder.binding.llBadges.visible()
                val badge = TextView(context).apply {
                    text = item.sourceLabel
                    setBackgroundResource(R.drawable.bg_review_badge_chip)
                    setTextColor(context.getCompatColor(R.color.secondaryText))
                    textSize = 11f
                    setPadding(8.dpToPx(), 2.dpToPx(), 8.dpToPx(), 2.dpToPx())
                }
                holder.binding.llBadges.addView(badge)
            } else {
                holder.binding.llBadges.gone()
            }
            holder.itemView.setOnClickListener {
                if (!item.isError && item.paraData.isNotBlank()) {
                    onRowClick(item)
                }
            }
            holder.itemView.isClickable = !item.isError && item.paraData.isNotBlank()
        }

        override fun getItemCount(): Int = items.size

        class VH(val binding: ItemReviewCommentBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        private const val ARG_TOTAL_COUNT = "totalCount"
        private const val ARG_SOURCE_COUNT = "sourceCount"
    }
}
