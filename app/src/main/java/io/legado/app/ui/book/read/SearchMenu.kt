package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.animation.Animation
import android.widget.FrameLayout
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ViewSearchMenuBinding
import io.legado.app.help.*
import io.legado.app.lib.theme.*
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.utils.*
import splitties.views.*

/**
 * 阅读界面菜单
 */
class SearchMenu @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }

    private val callBack: CallBack get() = activity as CallBack
    private val binding = ViewSearchMenuBinding.inflate(LayoutInflater.from(context), this, true)
    private val menuTopIn: Animation = AnimationUtilsSupport.loadAnimation(context, R.anim.anim_readbook_top_in)
    private val menuTopOut: Animation = AnimationUtilsSupport.loadAnimation(context, R.anim.anim_readbook_top_out)
    private val menuBottomIn: Animation = AnimationUtilsSupport.loadAnimation(context, R.anim.anim_readbook_bottom_in)
    private val menuBottomOut: Animation = AnimationUtilsSupport.loadAnimation(context, R.anim.anim_readbook_bottom_out)
    private val bgColor: Int = context.bottomBackground
    private val textColor: Int = context.getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
    private val bottomBackgroundList: ColorStateList =
        Selector.colorBuild().setDefaultColor(bgColor).setPressedColor(ColorUtils.darkenColor(bgColor)).create()
    private var onMenuOutEnd: (() -> Unit)? = null
    private var searchResultList: List<SearchResult> = listOf()
    private var currentSearchResultIndex : Int = -1

    private val hasSearchResult: Boolean
        get() = searchResultList.size > currentSearchResultIndex && currentSearchResultIndex > 0

    init {

        initAnimation()
        initView()
        bindEvent()
        activity?.let { owner ->
            eventObservable<List<SearchResult>>(EventBus.SEARCH_RESULT).observe(owner, {
                searchResultList = it
            })
        }

    }

    private fun initView() = binding.run {
        llSearchBaseInfo.setBackgroundColor(bgColor)
        tvCurrentSearchInfo.setTextColor(bottomBackgroundList)
        llBottomBg.setBackgroundColor(bgColor)
        fabLeft.backgroundTintList = bottomBackgroundList
        fabLeft.setColorFilter(textColor)
        fabRight.backgroundTintList = bottomBackgroundList
        fabRight.setColorFilter(textColor)
        tvMainMenu.setTextColor(textColor)
        tvSearchResults.setTextColor(textColor)
        tvSearchExit.setTextColor(textColor)
        tvSetting.setTextColor(textColor)
        ivMainMenu.setColorFilter(textColor)
        ivSearchResults.setColorFilter(textColor)
        ivSearchExit.setColorFilter(textColor)
        ivSetting.setColorFilter(textColor)
        ivSearchContentBottom.setColorFilter(textColor)
        ivSearchContentTop.setColorFilter(textColor)
    }


    fun runMenuIn() {
        this.visible()
        binding.titleBar.visible()
        binding.llSearchBaseInfo.visible()
        binding.llBottomBg.visible()
        binding.titleBar.startAnimation(menuTopIn)
        binding.llSearchBaseInfo.startAnimation(menuBottomIn)
        binding.llBottomBg.startAnimation(menuBottomIn)
    }

    fun runMenuOut(onMenuOutEnd: (() -> Unit)? = null) {
        this.onMenuOutEnd = onMenuOutEnd
        if (this.isVisible) {
            binding.titleBar.startAnimation(menuTopOut)
            binding.llSearchBaseInfo.startAnimation(menuBottomOut)
            binding.llBottomBg.startAnimation(menuBottomOut)
        }
    }

    fun updateSearchResultIndex(updateIndex: Int){
        if(updateIndex >= 0 && updateIndex < searchResultList.size){
            currentSearchResultIndex = updateIndex
        }
    }

    private fun bindEvent() = binding.run {
        titleBar.toolbar.setOnClickListener {
            ReadBook.book?.let {
                context.startActivity<BookInfoActivity> {
                    putExtra("name", it.name)
                    putExtra("author", it.author)
                }
            }
        }
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                runMenuOut {
                    callBack.openSearchActivity(query)
                }
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                return false
            }
        })

        llSearchResults.setOnClickListener {
            runMenuOut {
                callBack.openSearchActivity(searchView.query.toString())
            }
        }

        //主菜单
        llMainMenu.setOnClickListener {
            runMenuOut {
                callBack.showMenuBar()
            }
        }


        //目录
        llSearchExit.setOnClickListener {
            runMenuOut {
                callBack.exitSearchMenu()
            }
        }

        //设置
        llSetting.setOnClickListener {
            runMenuOut {
                callBack.showSearchSetting()
            }
        }

        fabLeft.setOnClickListener {
            runMenuOut {
                updateSearchResultIndex(currentSearchResultIndex - 1)
                callBack.navigateToSearch(searchResultList[currentSearchResultIndex], currentSearchResultIndex)
            }
        }

        fabRight.setOnClickListener {
            runMenuOut {
                updateSearchResultIndex(currentSearchResultIndex + 1)
                callBack.navigateToSearch(searchResultList[currentSearchResultIndex], currentSearchResultIndex)
            }
        }
    }

    private fun initAnimation() {
        //显示菜单
        menuTopIn.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {
                callBack.upSystemUiVisibility()
                binding.fabLeft.visible(hasSearchResult)
                binding.fabRight.visible(hasSearchResult)
            }

            @SuppressLint("RtlHardcoded")
            override fun onAnimationEnd(animation: Animation) {
                val navigationBarHeight = if (ReadBookConfig.hideNavigationBar) {
                    activity?.navigationBarHeight ?: 0
                } else {
                    0
                }
                binding.run {
                    vwMenuBg.setOnClickListener { runMenuOut() }
                    root.padding = 0
                    when (activity?.navigationBarGravity) {
                        Gravity.BOTTOM -> root.bottomPadding = navigationBarHeight
                        Gravity.LEFT   -> root.leftPadding = navigationBarHeight
                        Gravity.RIGHT  -> root.rightPadding = navigationBarHeight
                    }
                }
                callBack.upSystemUiVisibility()
            }

            override fun onAnimationRepeat(animation: Animation) = Unit
        })

        //隐藏菜单
        menuTopOut.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {
                binding.vwMenuBg.setOnClickListener(null)
            }

            override fun onAnimationEnd(animation: Animation) {
                this@SearchMenu.invisible()
                binding.titleBar.invisible()
                binding.llSearchBaseInfo.invisible()
                binding.llBottomBg.invisible()
                binding.fabRight.invisible()
                binding.fabLeft.invisible()
                onMenuOutEnd?.invoke()
                callBack.upSystemUiVisibility()
            }

            override fun onAnimationRepeat(animation: Animation) = Unit
        })
    }

    interface CallBack {
        var isShowingSearchResult: Boolean
        fun openSearchActivity(searchWord: String?)
        fun showSearchSetting()
        fun upSystemUiVisibility()
        fun exitSearchMenu()
        fun showMenuBar()
        fun navigateToSearch(searchResult: SearchResult, searchResultIndex: Int)
    }

}
