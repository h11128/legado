package io.legado.app.model

import android.content.Context
import io.legado.app.R
import io.legado.app.constant.IntentAction
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.CacheManager
import io.legado.app.help.IntentData
import io.legado.app.help.http.BackstageWebView
import io.legado.app.service.CheckSourceService
import io.legado.app.utils.startService
import splitties.init.appCtx

object CheckSource {
    var keyword = "我的"

    /** Job-local check toggles; defaults mirror [CheckSource] globals. */
    data class Settings(
        val checkDomain: Boolean,
        val checkSearch: Boolean,
        val checkDiscovery: Boolean,
        val checkInfo: Boolean,
        val checkCategory: Boolean,
        val checkContent: Boolean,
        val wSourceComment: Boolean,
    ) {
        companion object {
            fun fromGlobals(): Settings = Settings(
                checkDomain = CheckSource.checkDomain,
                checkSearch = CheckSource.checkSearch,
                checkDiscovery = CheckSource.checkDiscovery,
                checkInfo = CheckSource.checkInfo,
                checkCategory = CheckSource.checkCategory,
                checkContent = CheckSource.checkContent,
                wSourceComment = CheckSource.wSourceComment,
            )

            fun merge(
                base: Settings = fromGlobals(),
                checkDomain: Boolean? = null,
                checkSearch: Boolean? = null,
                checkDiscovery: Boolean? = null,
                checkInfo: Boolean? = null,
                checkCategory: Boolean? = null,
                checkContent: Boolean? = null,
                wSourceComment: Boolean? = null,
            ): Settings = Settings(
                checkDomain = checkDomain ?: base.checkDomain,
                checkSearch = checkSearch ?: base.checkSearch,
                checkDiscovery = checkDiscovery ?: base.checkDiscovery,
                checkInfo = checkInfo ?: base.checkInfo,
                checkCategory = checkCategory ?: base.checkCategory,
                checkContent = checkContent ?: base.checkContent,
                wSourceComment = wSourceComment ?: base.wSourceComment,
            )
        }
    }

    //校验设置
    var timeout = CacheManager.getLong("checkSourceTimeout") ?: 180000L
    var wSourceComment = CacheManager.get("wSourceComment")?.toBoolean() ?: true
    var checkDomain = CacheManager.get("checkDomain")?.toBoolean() ?: false
    var checkSearch = CacheManager.get("checkSearch")?.toBoolean() ?: true
    var checkDiscovery = CacheManager.get("checkDiscovery")?.toBoolean() ?: true
    var checkInfo = CacheManager.get("checkInfo")?.toBoolean() ?: true
    var checkCategory = CacheManager.get("checkCategory")?.toBoolean() ?: true
    var checkContent = CacheManager.get("checkContent")?.toBoolean() ?: true
    /** Max WebView DOM-settle wait during check (ms). */
    var webViewSettleMaxMs: Long
        get() = BackstageWebView.checkSettleMaxMs()
        set(value) {
            BackstageWebView.setCheckSettleConfig(maxWaitMs = value)
        }
    val summary get() = upSummary()

    fun start(context: Context, sources: List<BookSourcePart>) {
        val selectedIds = sources.map {
            it.bookSourceUrl
        }
        IntentData.put("checkSourceSelectedIds", selectedIds)
        context.startService<CheckSourceService> {
            action = IntentAction.start
        }
    }

    fun stop(context: Context) {
        context.startService<CheckSourceService> {
            action = IntentAction.stop
        }
    }

    fun resume(context: Context) {
        context.startService<CheckSourceService> {
            action = IntentAction.resume
        }
    }

    fun putConfig() {
        CacheManager.put("checkSourceTimeout", timeout)
        CacheManager.put("wSourceComment", wSourceComment)
        CacheManager.put("checkDomain", checkDomain)
        CacheManager.put("checkSearch", checkSearch)
        CacheManager.put("checkDiscovery", checkDiscovery)
        CacheManager.put("checkInfo", checkInfo)
        CacheManager.put("checkCategory", checkCategory)
        CacheManager.put("checkContent", checkContent)
        // webViewSettleMaxMs writes via BackstageWebView.setCheckSettleConfig
    }

    private fun upSummary(): String {
        var checkItem = ""
        if (checkDomain) checkItem = "$checkItem ${appCtx.getString(R.string.domain)}"
        if (checkSearch) checkItem = "$checkItem ${appCtx.getString(R.string.search)}"
        if (checkDiscovery) checkItem = "$checkItem ${appCtx.getString(R.string.discovery)}"
        if (checkInfo) checkItem = "$checkItem ${appCtx.getString(R.string.source_tab_info)}"
        if (checkCategory) checkItem = "$checkItem ${appCtx.getString(R.string.chapter_list)}"
        if (checkContent) checkItem = "$checkItem ${appCtx.getString(R.string.main_body)}"
        return appCtx.getString(
            R.string.check_source_config_summary,
            (timeout / 1000).toString(),
            checkItem
        )
    }
}