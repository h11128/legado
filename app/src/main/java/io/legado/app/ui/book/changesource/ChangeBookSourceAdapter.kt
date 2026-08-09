package io.legado.app.ui.book.changesource

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemChangeSourceBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.checkalgo.ChangeBookSourceQuality
import io.legado.app.ui.widget.popupActionMenu
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.visible
import splitties.init.appCtx
import splitties.views.onLongClick


class ChangeBookSourceAdapter(
    context: Context,
    val viewModel: ChangeBookSourceViewModel,
    val callBack: CallBack
) : DiffRecyclerAdapter<SearchBook, ItemChangeSourceBinding>(context) {

    override val diffItemCallback = object : DiffUtil.ItemCallback<SearchBook>() {
        override fun areItemsTheSame(oldItem: SearchBook, newItem: SearchBook): Boolean {
            return oldItem.bookUrl == newItem.bookUrl
        }

        override fun areContentsTheSame(oldItem: SearchBook, newItem: SearchBook): Boolean {
            return oldItem.originName == newItem.originName
                    && oldItem.getDisplayLastChapterTitle() == newItem.getDisplayLastChapterTitle()
                    && oldItem.tocChapterCount == newItem.tocChapterCount
                    && oldItem.chapterWordCountText == newItem.chapterWordCountText
                    && oldItem.respondTime == newItem.respondTime
                    && oldItem.smartScore == newItem.smartScore
                    && oldItem.qualityTags == newItem.qualityTags
                    && oldItem.qualityVerdict == newItem.qualityVerdict
        }

    }

    override fun getViewBinding(parent: ViewGroup): ItemChangeSourceBinding {
        return ItemChangeSourceBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemChangeSourceBinding,
        item: SearchBook,
        payloads: MutableList<Any>
    ) {
        binding.apply {
            if (payloads.isEmpty()) {
                tvOrigin.text = item.originName
                tvAuthor.text = item.author
                bindCatalogLine(this, item)
                bindMetricAndTags(this, item)
                bindSmartScore(this, item)
                if (callBack.oldBookUrl == item.bookUrl) {
                    ivChecked.visible()
                } else {
                    ivChecked.invisible()
                }
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "name" -> tvOrigin.text = item.originName
                            "latest" -> bindCatalogLine(this@apply, item)
                            "upCurSource" -> if (callBack.oldBookUrl == item.bookUrl) {
                                ivChecked.visible()
                            } else {
                                ivChecked.invisible()
                            }
                        }
                    }
                }
                bindCatalogLine(this, item)
                bindMetricAndTags(this, item)
                bindSmartScore(this, item)
            }
            val score = callBack.getBookScore(item)
            if (score > 0) {
                binding.ivBad.gone()
                binding.ivGood.visible()
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_A200)
                )
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_100)
                )
            } else if (score < 0) {
                binding.ivGood.gone()
                binding.ivBad.visible()
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_100)
                )
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_A200)
                )
            } else {
                binding.ivGood.visible()
                binding.ivBad.visible()
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_100)
                )
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_100)
                )
            }
        }
    }

    private fun bindCatalogLine(binding: ItemChangeSourceBinding, item: SearchBook) {
        binding.tvLast.text = ChangeBookSourceQuality.catalogLine(
            context.getString(R.string.lasted_show, item.getDisplayLastChapterTitle()),
        )
    }

    private fun bindMetricAndTags(binding: ItemChangeSourceBinding, item: SearchBook) {
        val text = ChangeBookSourceQuality.composeProbeEvidence(
            tocChapterCount = item.tocChapterCount,
            chapterWordCount = item.chapterWordCount,
            respondTimeMs = item.respondTime,
            chapterWordCountText = item.chapterWordCountText,
            probeChapterOrdinal = item.probeChapterOrdinal,
            probeChapterTitle = item.probeChapterTitle,
        )
        // TOC total `[N]` stays visible even when load-word-count is off.
        val showProbe = !text.isNullOrBlank() &&
            (AppConfig.changeSourceLoadWordCount || item.tocChapterCount > 0)
        if (showProbe) {
            binding.tvCurrentChapterWordCount.text = if (AppConfig.changeSourceLoadWordCount) {
                text
            } else {
                ChangeBookSourceQuality.totalChapterBracket(item.tocChapterCount) ?: text
            }
            binding.tvCurrentChapterWordCount.visible()
        } else {
            binding.tvCurrentChapterWordCount.gone()
        }
        val tags = item.qualityTags.joinToString(" · ")
        if (AppConfig.changeSourceLoadWordCount && showProbe && tags.isNotEmpty()) {
            binding.tvQualityTags.text = tags
            binding.tvQualityTags.visible()
        } else {
            binding.tvQualityTags.gone()
        }
    }

    private fun bindSmartScore(binding: ItemChangeSourceBinding, item: SearchBook) {
        if (!AppConfig.changeSourceLoadWordCount) {
            binding.tvSmartScore.gone()
            return
        }
        binding.tvSmartScore.visible()
        binding.tvSmartScore.text = if (item.smartScore >= 0) {
            item.smartScore.toString()
        } else {
            "—"
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemChangeSourceBinding) {
        binding.ivGood.setOnClickListener {
            if (binding.ivBad.isVisible) {
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_A200)
                )
                binding.ivBad.gone()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, 1)
                }
            } else {
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_100)
                )
                binding.ivBad.visible()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, 0)
                }
            }
        }
        binding.ivBad.setOnClickListener {
            if (binding.ivGood.isVisible) {
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_A200)
                )
                binding.ivGood.gone()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, -1)
                }
            } else {
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_100)
                )
                binding.ivGood.visible()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, 0)
                }
            }
        }
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                if (it.bookUrl != callBack.oldBookUrl) {
                    callBack.changeTo(it)
                }
            }
        }
        holder.itemView.onLongClick {
            showMenu(holder.itemView, getItem(holder.layoutPosition))
        }
    }

    private fun showMenu(view: View, searchBook: SearchBook?) {
        searchBook ?: return
        popupActionMenu(context) {
            item(context.getString(R.string.to_top), "topSource")
            item(context.getString(R.string.to_bottom), "bottomSource")
            item(context.getString(R.string.edit_source), "editSource")
            item(context.getString(R.string.disable_source), "disableSource")
            item(context.getString(R.string.delete_source), "deleteSource")
            danger("deleteSource")
        }.show(view) { action ->
            when (action) {
                "topSource" -> callBack.topSource(searchBook)
                "bottomSource" -> callBack.bottomSource(searchBook)
                "editSource" -> callBack.editSource(searchBook)
                "disableSource" -> callBack.disableSource(searchBook)
                "deleteSource" -> context.alert(R.string.draw) {
                    setMessage(context.getString(R.string.sure_del) + "\n" + searchBook.originName)
                    noButton()
                    yesButton {
                        callBack.deleteSource(searchBook)
                        updateItems(0, itemCount, listOf<Int>())
                    }
                }
            }
        }
    }

    interface CallBack {
        val oldBookUrl: String?
        fun changeTo(searchBook: SearchBook)
        fun topSource(searchBook: SearchBook)
        fun bottomSource(searchBook: SearchBook)
        fun editSource(searchBook: SearchBook)
        fun disableSource(searchBook: SearchBook)
        fun deleteSource(searchBook: SearchBook)
        fun setBookScore(searchBook: SearchBook, score: Int)
        fun getBookScore(searchBook: SearchBook): Int
    }
}
