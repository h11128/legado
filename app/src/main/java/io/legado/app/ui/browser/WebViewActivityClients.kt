package io.legado.app.ui.browser

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.view.size
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.CookieStore
import io.legado.app.help.webView.WebJsExtensions.Companion.basicJs
import io.legado.app.help.webView.WebViewPool
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startActivity
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.visible
import java.lang.ref.WeakReference

@Suppress("unused")
internal class JSInterface(activity: WebViewActivity) {
    private val activityRef: WeakReference<WebViewActivity> = WeakReference(activity)

    @JavascriptInterface
    fun lockOrientation(orientation: String) {
        val ctx = activityRef.get()
        if (ctx != null && ctx.isfullscreen && !ctx.isFinishing && !ctx.isDestroyed) {
            ctx.runOnUiThread {
                ctx.requestedOrientation = when (orientation) {
                    "portrait", "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    "any", "unspecified" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    @JavascriptInterface
    fun onCloseRequested() {
        val ctx = activityRef.get()
        if (ctx != null && !ctx.isFinishing && !ctx.isDestroyed) {
            ctx.runOnUiThread {
                ctx.close()
            }
        }
    }
}

internal class CustomWebChromeClient(private val activity: WebViewActivity) : WebChromeClient() {
    override fun getDefaultVideoPoster(): Bitmap {
        return super.getDefaultVideoPoster() ?: createBitmap(100, 100)
    }

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        activity.webBinding.progressBar.setDurProgress(newProgress)
        activity.webBinding.progressBar.gone(newProgress == 100)
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        activity.isfullscreen = true
        activity.webBinding.llView.invisible()
        activity.webBinding.customWebView.addView(view)
        activity.customWebViewCallback = callback
        activity.keepScreenOn(true)
        activity.toggleSystemBar(false)
    }

    override fun onHideCustomView() {
        activity.isfullscreen = false
        activity.webBinding.customWebView.removeAllViews()
        activity.webBinding.llView.visible()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity.keepScreenOn(false)
        activity.toggleSystemBar(true)
    }

    override fun onCloseWindow(window: WebView?) {
        activity.close()
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        activity.webModel.source?.let { source ->
            if (WebViewActivity.sessionShowWebLog) {
                val messageLevel = consoleMessage.messageLevel().name
                val message = consoleMessage.message()
                AppLog.put(
                    "${source.getTag()}${messageLevel}: $message",
                    NoStackTraceException(
                        "\n${message}\n- Line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}"
                    ),
                )
                return true
            }
        }
        return false
    }
}

internal class CustomWebViewClient(private val activity: WebViewActivity) : WebViewClient() {
    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?,
    ): Boolean {
        request?.let {
            return shouldOverrideUrlLoading(it.url)
        }
        return true
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        url?.let {
            return shouldOverrideUrlLoading(it.toUri())
        }
        return true
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (activity.needClearHistory) {
            activity.needClearHistory = false
            activity.currentWebView.clearHistory()
        }
        super.onPageStarted(view, url, favicon)
        activity.cancelAutoClose()
        activity.currentWebView.evaluateJavascript(basicJs, null)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        val cookieManager = CookieManager.getInstance()
        url?.let {
            CookieStore.setCookie(it, cookieManager.getCookie(it))
        }
        if (view == null) return
        val title = view.title
        if (!title.isNullOrBlank() && title != url && title != view.url) {
            activity.webBinding.titleBar.title = title
        } else {
            activity.webBinding.titleBar.title = activity.intent.getStringExtra("title")
        }
        // Cloudflare check is async; only auto-close after we know it is not a challenge.
        view.evaluateJavascript("!!window._cf_chl_opt") { cfResult ->
            if (activity.isFinishing || activity.isDestroyed) return@evaluateJavascript
            if (cfResult == "true") {
                activity.isCloudflareChallenge = true
                activity.cancelAutoClose()
            } else if (activity.isCloudflareChallenge && activity.webModel.sourceVerificationEnable) {
                // Challenge cleared — same as existing auto-finish path.
                activity.confirmAndFinish()
            } else {
                activity.scheduleAutoCloseIfChecking()
            }
        }
    }

    private fun shouldOverrideUrlLoading(url: Uri): Boolean {
        return when (url.scheme) {
            "http", "https" -> false
            "legado", "yuedu" -> {
                activity.startActivity<OnLineImportActivity> {
                    data = url
                }
                true
            }
            else -> {
                activity.webBinding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                    activity.openUrl(url)
                }
                true
            }
        }
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: SslError?,
    ) {
        handler?.proceed()
    }
}

internal fun WebViewActivity.setupOnBackPressed() {
    onBackPressedDispatcher.addCallback(this) {
        if (webBinding.customWebView.size > 0) {
            customWebViewCallback?.onCustomViewHidden()
            return@addCallback
        }
        if (isFullScreen) {
            toggleFullScreen()
            return@addCallback
        }
        if (currentWebView.canGoBack()) {
            val list = currentWebView.copyBackForwardList()
            val size = list.size
            if (size == 1) {
                finish()
                return@addCallback
            }
            val currentIndex = list.currentIndex
            val currentItem = list.currentItem
            val currentUrl = currentItem?.originalUrl ?: WebViewPool.BLANK_HTML
            val currentTitle = currentItem?.title
            var steps = 1
            for (i in currentIndex - 1 downTo 0) {
                val item = list.getItemAtIndex(i)
                val itemUrl = item.originalUrl
                if (itemUrl == WebViewPool.BLANK_HTML) {
                    finish()
                    return@addCallback
                }
                if (itemUrl != currentUrl || currentTitle != item.title) {
                    break
                }
                if (currentUrl == WebViewPool.DATA_HTML) {
                    break
                }
                steps++
            }
            if (steps == size) {
                finish()
                return@addCallback
            }
            currentWebView.goBackOrForward(-steps)
            return@addCallback
        }
        finish()
    }
}

internal fun WebViewActivity.handlePrepareOptionsMenu(menu: Menu) {
    if (webModel.sourceOrigin.isNotEmpty()) {
        menu.findItem(R.id.menu_disable_source)?.isVisible = true
        menu.findItem(R.id.menu_delete_source)?.isVisible = true
    }
    menu.findItem(R.id.menu_show_web_log)?.isChecked = WebViewActivity.sessionShowWebLog
}

/** @return true if the item was handled */
internal fun WebViewActivity.handleOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
        R.id.menu_web_refresh -> refresh()
        R.id.menu_open_in_browser -> openUrl(webModel.baseUrl)
        R.id.menu_copy_url -> sendToClip(webModel.baseUrl)
        R.id.menu_ok -> confirmAndFinish()
        R.id.menu_full_screen -> toggleFullScreen()
        R.id.menu_show_web_log -> {
            WebViewActivity.sessionShowWebLog = !WebViewActivity.sessionShowWebLog
            item.isChecked = WebViewActivity.sessionShowWebLog
        }
        R.id.menu_disable_source -> {
            webModel.disableSource {
                finish()
            }
        }
        R.id.menu_delete_source -> {
            alert(R.string.draw) {
                setMessage(getString(R.string.sure_del) + "\n" + webModel.sourceName)
                noButton()
                yesButton {
                    webModel.deleteSource {
                        finish()
                    }
                }
            }
        }
        else -> return false
    }
    return true
}
