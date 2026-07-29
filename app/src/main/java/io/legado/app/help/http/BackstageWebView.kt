package io.legado.app.help.http

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AndroidRuntimeException
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CacheManager
import io.legado.app.help.WebCacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewRequestConfig
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.getInjectionString
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.toWebViewRequestConfig
import io.legado.app.model.Debug
import io.legado.app.utils.runOnUI
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.apache.commons.text.StringEscapeUtils
import splitties.init.appCtx
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 后台webView
 */
class BackstageWebView(
    private val url: String? = null,
    private val html: String? = null,
    private val encode: String? = null,
    private val tag: String? = null,
    private val headerMap: HashMap<String, String>? = null,
    private val sourceRegex: String? = null,
    private val overrideUrlRegex: String? = null,
    private val javaScript: String? = null,
    private var delayTime: Long = 0,
    private val cacheFirst: Boolean = false,
    private val timeout: Long? = null,
    private val result: String? = null,
    private val isRule: Boolean = false
) {

    private val mHandler = Handler(Looper.getMainLooper())
    private var callback: Callback? = null
    private var pooledWebView: PooledWebView? = null
    private val settleGeneration = AtomicInteger(0)

    suspend fun getStrResponse(): StrResponse = withTimeout(timeout ?: 60000L) {
        suspendCancellableCoroutine { block ->
            block.invokeOnCancellation {
                runOnUI {
                    destroy()
                }
            }
            callback = object : Callback() {
                override fun onResult(response: StrResponse) {
                    if (!block.isCompleted) {
                        block.resume(response)
                    }
                }

                override fun onError(error: Throwable) {
                    if (!block.isCompleted)
                        block.resumeWithException(error)
                }
            }
            if (javaScript == null && delayTime == 0L) {
                delayTime = if (Debug.isChecking) checkSettleDelayMs() else DEFAULT_DELAY_MS
            }
            runOnUI {
                try {
                    load()
                } catch (error: Throwable) {
                    destroy()
                    block.resumeWithException(error)
                }
            }
        }
    }

    private fun getEncoding(): String {
        return encode ?: "utf-8"
    }

    @Throws(AndroidRuntimeException::class)
    private fun load() {
        val requestConfig = headerMap.toWebViewRequestConfig(AppConfig.userAgent)
        val webView = createWebView(requestConfig)
        try {
            when {
                !html.isNullOrEmpty() -> {
                    if (isRule) {
                        webView.addJavascriptInterface(WebCacheManager, nameCache)
                        tag?.let { key ->
                           appDb.bookSourceDao.getBookSource(key)?.let {
                               webView.addJavascriptInterface(it as BaseSource, nameSource)
                               val webJsExtensions = WebJsExtensions(it, null, webView)
                               webView.addJavascriptInterface(webJsExtensions, nameJava)
                            }
                        }
                    }
                    result?.let {
                        CacheManager.put("webview_result", it)
                    }
                    webView.loadDataWithBaseURL(url, html, "text/html", getEncoding(), url)
                }

                else -> if (requestConfig.additionalHeaders.isEmpty()) {
                    webView.loadUrl(url!!)
                } else {
                    webView.loadUrl(url!!, requestConfig.additionalHeaders)
                }
            }
        } catch (e: Exception) {
            callback?.onError(e)
            destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(requestConfig: WebViewRequestConfig): WebView {
        val pooledWebView = WebViewPool.acquire(appCtx)
        this.pooledWebView = pooledWebView
        val webView = pooledWebView.realWebView
        webView.onResume() //缓存库拿的需要激活
        val settings = webView.settings
        settings.blockNetworkImage = true
        settings.userAgentString = requestConfig.userAgent
        settings.cacheMode = if (cacheFirst) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT
        val htmlClient = if (sourceRegex.isNullOrBlank() && overrideUrlRegex.isNullOrBlank()) {
            HtmlWebViewClient().also { webView.webViewClient = it }
        } else {
            webView.webViewClient = SnifferWebClient()
            null
        }
        val sourceTag = tag?.takeIf { it.isNotBlank() }
        if (htmlClient != null || sourceTag != null) {
            webView.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    htmlClient?.onLoadProgress(newProgress)
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    if (sourceTag == null) return super.onConsoleMessage(consoleMessage)
                    val messageLevel = consoleMessage.messageLevel().name
                    val message = consoleMessage.message()
                    Debug.log(sourceTag, "$messageLevel: $message", true)
                    return true
                }
            }
        }
        return webView
    }

    private fun destroy() {
        mHandler.removeCallbacksAndMessages(null)
        settleGeneration.incrementAndGet()
        pooledWebView?.let { WebViewPool.release(it) }
        pooledWebView = null
    }

    private fun getJs(): String {
        javaScript?.let {
            if (it.isNotEmpty()) {
                return it
            }
        }
        return JS
    }

    private fun setCookie(url: String) {
        tag?.let {
            Coroutine.async(executeContext = IO) {
                val cookie = CookieManager.getInstance().getCookie(url)
                CookieStore.setCookie(it, cookie)
            }
        }
    }

    private inner class HtmlWebViewClient : WebViewClient() {

        private var evalRunnable: EvalJsRunnable? = null
        private var settleRunnable: DomSettleRunnable? = null
        private var isRedirect = false
        @Volatile
        private var pageProgress = 0
        /** Wall-clock settle deadline for the current main-document navigation. */
        @Volatile
        private var settleDeadlineAt = 0L

        fun onLoadProgress(progress: Int) {
            pageProgress = progress
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            pageProgress = 0
            settleDeadlineAt = System.currentTimeMillis() + checkSettleMaxMs()
            settleRunnable?.onNavigationStarted()
            super.onPageStarted(view, url, favicon)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            isRedirect = isRedirect || if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                request.isRedirect
            } else {
                request.url.toString() != view.url
            }
            return super.shouldOverrideUrlLoading(view, request)
        }

        override fun onPageFinished(view: WebView, url: String) {
            setCookie(url)
            // Progress callbacks often stop before finished; treat finished as load complete.
            pageProgress = 100
            result?.let {
                view.evaluateJavascript("window.result = $nameCache.getFromMemory('webview_result')", null)
            }
            val eval = evalRunnable ?: EvalJsRunnable(view, url, getJs()).also {
                evalRunnable = it
            }
            mHandler.removeCallbacks(eval)
            val settle = settleRunnable ?: DomSettleRunnable(view, eval).also {
                settleRunnable = it
            }
            mHandler.removeCallbacks(settle)
            if (useDomSettle()) {
                if (settleDeadlineAt == 0L) {
                    settleDeadlineAt = System.currentTimeMillis() + checkSettleMaxMs()
                }
                settle.reset(view)
                mHandler.postDelayed(settle, 100L + delayTime)
            } else {
                settleGeneration.incrementAndGet()
                mHandler.postDelayed(eval, 100L + delayTime)
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            handler?.proceed()
        }

        private fun useDomSettle(): Boolean = Debug.isChecking

        /**
         * During bulk check: poll HTML length until stable (or deadline), then snapshot.
         * Fixed delay alone returns skeleton HTML for JS-rendered TOC sites.
         * Deadline is set on onPageStarted (wall clock for this navigation).
         */
        private inner class DomSettleRunnable(
            webView: WebView,
            private val eval: EvalJsRunnable,
        ) : Runnable {
            private var mWebView: WeakReference<WebView> = WeakReference(webView)
            private var lastLen = -1
            private var peakLen = 0
            private var stableHits = 0
            private var generation = 0

            fun reset(webView: WebView) {
                mWebView = WeakReference(webView)
                lastLen = -1
                stableHits = 0
                // Keep peakLen across repeated onPageFinished in the same navigation;
                // onPageStarted refreshes settleDeadlineAt and we clear peak there.
                generation = settleGeneration.incrementAndGet()
            }

            fun onNavigationStarted() {
                lastLen = -1
                peakLen = 0
                stableHits = 0
            }

            private fun pastDeadline(): Boolean {
                val deadline = settleDeadlineAt
                return deadline > 0L && System.currentTimeMillis() >= deadline
            }

            override fun run() {
                if (pooledWebView == null || generation != settleGeneration.get()) return
                val view = mWebView.get() ?: return
                if (pastDeadline()) {
                    if (generation == settleGeneration.get()) {
                        mHandler.post(eval)
                    }
                    return
                }
                val genAtStart = generation
                view.evaluateJavascript(HTML_LENGTH_JS) { raw ->
                    if (pooledWebView == null) return@evaluateJavascript
                    if (genAtStart != settleGeneration.get()) return@evaluateJavascript
                    if (pastDeadline()) {
                        mHandler.post(eval)
                        return@evaluateJavascript
                    }
                    val len = raw?.trim()?.removeSurrounding("\"")?.toIntOrNull() ?: -1
                    if (len > peakLen) peakLen = len
                    // Skip tiny static skeletons: need a reasonably large DOM or prior growth.
                    val substantial = peakLen >= MIN_STABLE_HTML_LEN * 2
                    val ready = pageProgress >= 100 &&
                        len >= MIN_STABLE_HTML_LEN &&
                        len == lastLen &&
                        substantial
                    if (ready) {
                        stableHits++
                    } else {
                        stableHits = 0
                        lastLen = len
                    }
                    if (stableHits >= STABLE_HITS_REQUIRED) {
                        mHandler.post(eval)
                    } else {
                        mHandler.postDelayed(this, SETTLE_POLL_MS)
                    }
                }
            }
        }

        private inner class EvalJsRunnable(
            webView: WebView,
            private val url: String,
            mJavaScript: String
        ) : Runnable {
            private var retry = 0
            private val intervals = listOf(200L, 400L, 600L, 800L, 1000L)
            private val mWebView: WeakReference<WebView> = WeakReference(webView)
            private val jsStr = if (isRule) {
                "$getInjectionString\n$mJavaScript"
            } else mJavaScript
            override fun run() {
                if (pooledWebView == null) return
                mWebView.get()?.evaluateJavascript(jsStr) {
                    if (pooledWebView != null) {
                        handleResult(it)
                    }
                }
            }

            private fun handleResult(result: String) = Coroutine.async {
                if (result.isNotEmpty() && result != "null") {
                    val content = StringEscapeUtils.unescapeJson(result)
                        .replace(quoteRegex, "")
                    try {
                        val response = buildStrResponse(content)
                        callback?.onResult(response)
                    } catch (e: Exception) {
                        callback?.onError(e)
                    }
                    mHandler.post {
                        destroy()
                    }
                    return@async
                }
                if (retry > 30) {
                    callback?.onError(NoStackTraceException("js执行超时"))
                    mHandler.post {
                        destroy()
                    }
                    return@async
                }
                val nextDelay = if (retry < intervals.size) {
                    intervals[retry]
                } else {
                    intervals.last()
                }
                retry++
                mHandler.postDelayed(this@EvalJsRunnable, nextDelay)
            }

            private fun buildStrResponse(content: String): StrResponse {
                if (!isRedirect) {
                    return StrResponse(url, content)
                }
                val originUrl = this@BackstageWebView.url ?: url
                val originResponse = Response.Builder()
                    .code(302)
                    .request(Request.Builder().url(originUrl).build())
                    .protocol(Protocol.HTTP_1_1)
                    .message("Found")
                    .build()
                val response = Response.Builder()
                    .code(200)
                    .request(Request.Builder().url(url).build())
                    .protocol(Protocol.HTTP_1_1)
                    .message("OK")
                    .priorResponse(originResponse)
                    .build()
                return StrResponse(response, content)
            }
        }

    }

    private inner class SnifferWebClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            if (shouldOverrideUrlLoading(request.url.toString())) {
                return true
            }
            return super.shouldOverrideUrlLoading(view, request)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            if (shouldOverrideUrlLoading(url)) {
                return true
            }
            return super.shouldOverrideUrlLoading(view, url)
        }

        private fun shouldOverrideUrlLoading(requestUrl: String): Boolean {
            overrideUrlRegex?.let {
                if (requestUrl.matches(it.toRegex())) {
                    try {
                        val response = StrResponse(url!!, requestUrl)
                        callback?.onResult(response)
                    } catch (e: Exception) {
                        callback?.onError(e)
                    }
                    destroy()
                    return true
                }
            }
            return false
        }

        override fun onLoadResource(view: WebView, resUrl: String) {
            sourceRegex?.let {
                if (resUrl.matches(it.toRegex())) {
                    try {
                        val response = StrResponse(url!!, resUrl)
                        callback?.onResult(response)
                    } catch (e: Exception) {
                        callback?.onError(e)
                    }
                    destroy()
                }
            }
        }

        override fun onPageFinished(webView: WebView, url: String) {
            setCookie(url)
            if (!javaScript.isNullOrEmpty()) {
                val runnable = LoadJsRunnable(webView, javaScript)
                mHandler.postDelayed(runnable, 100L + delayTime)
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            handler?.proceed()
        }

        private inner class LoadJsRunnable(
            webView: WebView,
            private val mJavaScript: String?
        ) : Runnable {
            private val mWebView: WeakReference<WebView> = WeakReference(webView)
            override fun run() {
                mWebView.get()?.loadUrl("javascript:${mJavaScript}")
            }
        }

    }

    companion object {
        const val JS = "document.documentElement.outerHTML"
        /** Default settle before evaluating page HTML when no custom JS/delay. */
        const val DEFAULT_DELAY_MS = 900L
        /**
         * Min wait after onPageFinished during bulk check before DOM-stability polls.
         * Override with CacheManager key `checkWebViewDelay` (200–8000ms).
         */
        const val CHECK_DELAY_MS = 1000L
        /**
         * Max wait for HTML length to stabilize during check (DOM settled).
         * Override with CacheManager key `checkWebViewMaxWait` (1000–15000ms).
         * Wall-clock deadline starts at onPageStarted for the current navigation.
         */
        const val CHECK_SETTLE_MAX_MS = 5000L
        private const val CHECK_WEBVIEW_DELAY_KEY = "checkWebViewDelay"
        private const val CHECK_WEBVIEW_MAX_WAIT_KEY = "checkWebViewMaxWait"
        private const val HTML_LENGTH_JS =
            "(function(){try{return (document.documentElement&&document.documentElement.innerHTML||'').length;}catch(e){return -1;}})()"
        private const val SETTLE_POLL_MS = 300L
        private const val STABLE_HITS_REQUIRED = 3
        private const val MIN_STABLE_HTML_LEN = 200
        private val quoteRegex = "^\"|\"$".toRegex()

        fun checkSettleDelayMs(): Long {
            cachedCheckSettleMs?.let { return it }
            val value = (CacheManager.getLong(CHECK_WEBVIEW_DELAY_KEY) ?: CHECK_DELAY_MS)
                .coerceIn(200L, 8000L)
            cachedCheckSettleMs = value
            return value
        }

        fun checkSettleMaxMs(): Long {
            cachedCheckSettleMaxMs?.let { return it }
            val value = (CacheManager.getLong(CHECK_WEBVIEW_MAX_WAIT_KEY) ?: CHECK_SETTLE_MAX_MS)
                .coerceIn(1000L, 15_000L)
            cachedCheckSettleMaxMs = value
            return value
        }

        fun setCheckSettleConfig(minDelayMs: Long? = null, maxWaitMs: Long? = null) {
            if (minDelayMs != null) {
                CacheManager.put(CHECK_WEBVIEW_DELAY_KEY, minDelayMs.coerceIn(200L, 8000L))
            }
            if (maxWaitMs != null) {
                CacheManager.put(CHECK_WEBVIEW_MAX_WAIT_KEY, maxWaitMs.coerceIn(1000L, 15_000L))
            }
            clearSettleCache()
        }

        fun clearSettleCache() {
            cachedCheckSettleMs = null
            cachedCheckSettleMaxMs = null
        }

        @Volatile
        private var cachedCheckSettleMs: Long? = null

        @Volatile
        private var cachedCheckSettleMaxMs: Long? = null
    }

    abstract class Callback {
        abstract fun onResult(response: StrResponse)
        abstract fun onError(error: Throwable)
    }
}
