package io.legado.app.ui.browser

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.viewModels
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.databinding.ActivityWebViewBinding
import io.legado.app.help.WebCacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.toWebViewRequestConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.Download
import io.legado.app.model.Debug
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ACache
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.help.http.CookieManager as AppCookieManager
import splitties.systemservices.powerManager
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

class WebViewActivity : VMBaseActivity<ActivityWebViewBinding, WebViewModel>() {
    companion object {
        var sessionShowWebLog = false
        private const val AUTO_CLOSE_DELAY_MS = 2_000L
    }

    private lateinit var pooledWebView: PooledWebView
    internal lateinit var currentWebView: WebView
    internal val webBinding get() = binding
    internal val webModel get() = viewModel
    override val binding by viewBinding(ActivityWebViewBinding::inflate)
    override val viewModel by viewModels<WebViewModel>()
    internal var customWebViewCallback: WebChromeClient.CustomViewCallback? = null
    private var webPic: String? = null
    internal var isCloudflareChallenge = false
    internal var isFullScreen = false
    internal var isfullscreen = false
    private var wasScreenOff = false
    internal var needClearHistory = true

    internal val autoCloseRunnable = Runnable {
        if (isFinishing || isDestroyed || !::currentWebView.isInitialized) return@Runnable
        currentWebView.evaluateJavascript("!!window._cf_chl_opt") { cfResult ->
            if (cfResult == "true") {
                isCloudflareChallenge = true
                cancelAutoClose()
            } else {
                confirmAndFinish()
            }
        }
    }

    private val saveImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            viewModel.saveImage(webPic, uri.toString())
        }
    }

    internal fun refresh() {
        currentWebView.reload()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        pooledWebView = WebViewPool.acquire(this)
        currentWebView = pooledWebView.realWebView
        binding.webViewContainer.addView(currentWebView)
        if (!SourceVerificationHelp.attachVerificationUi(
                intent.getStringExtra("verificationResultKey"),
                ::finishVerificationUi,
            )
        ) {
            finish()
            return
        }
        currentWebView.post {
            currentWebView.clearHistory()
        }
        binding.titleBar.title = intent.getStringExtra("title") ?: getString(R.string.loading)
        binding.titleBar.subtitle = intent.getStringExtra("sourceName")
        viewModel.initData(intent) {
            val url = viewModel.baseUrl
            val headerMap = viewModel.headerMap
            val requestConfig = headerMap.toWebViewRequestConfig(AppConfig.userAgent)
            initWebView(url, requestConfig.userAgent)
            val html = viewModel.html
            if (html.isNullOrEmpty()) {
                currentWebView.loadUrl(url, requestConfig.additionalHeaders)
            } else {
                if (viewModel.localHtml) {
                    viewModel.source?.let {
                        val webJsExtensions = WebJsExtensions(it, this, currentWebView)
                        currentWebView.addJavascriptInterface(webJsExtensions, nameJava)
                    }
                    currentWebView.addJavascriptInterface(WebCacheManager, nameCache)
                }
                currentWebView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
            }
        }
        currentWebView.clearHistory()
        setupOnBackPressed()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.web_view, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        handlePrepareOptionsMenu(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        if (handleOptionsItemSelected(item)) return true
        return super.onCompatOptionsItemSelected(item)
    }

    //实现starBrowser调起页面全屏
    internal fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        toggleSystemBar(!isFullScreen)
        if (isFullScreen) {
            supportActionBar?.hide()
        } else {
            supportActionBar?.show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(url: String, userAgent: String) {
        binding.progressBar.fontColor = accentColor
        currentWebView.webChromeClient = CustomWebChromeClient(this)
        // 添加 JavaScript 接口
        currentWebView.addJavascriptInterface(JSInterface(this), nameBasic)
        currentWebView.webViewClient = CustomWebViewClient(this)
        currentWebView.settings.apply {
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = userAgent
        }
        AppCookieManager.applyToWebView(url)
        currentWebView.setOnLongClickListener {
            val hitTestResult = currentWebView.hitTestResult
            if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                hitTestResult.extra?.let { webPic ->
                    selector(
                        arrayListOf(
                            SelectItem(getString(R.string.action_save), "save"),
                            SelectItem(getString(R.string.select_folder), "selectFolder")
                        )
                    ) { _, charSequence, _ ->
                        when (charSequence.value) {
                            "save" -> saveImage(webPic)
                            "selectFolder" -> selectSaveFolder()
                        }
                    }
                    return@setOnLongClickListener true
                }
            }
            return@setOnLongClickListener false
        }
        currentWebView.setDownloadListener { downloadUrl, _, contentDisposition, _, _ ->
            var fileName = URLUtil.guessFileName(downloadUrl, contentDisposition, null)
            fileName = URLDecoder.decode(fileName, "UTF-8")
            currentWebView.longSnackbar(fileName, getString(R.string.action_download)) {
                Download.start(this, downloadUrl, fileName)
            }
        }
    }

    private fun saveImage(webPic: String) {
        this.webPic = webPic
        val path = ACache.get().getAsString(imagePathKey)
        if (path.isNullOrEmpty()) {
            selectSaveFolder()
        } else {
            viewModel.saveImage(webPic, path)
        }
    }

    private fun selectSaveFolder() {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(imagePathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        saveImage.launch {
            otherActions = default
        }
    }

    override fun finish() {
        cancelAutoClose()
        SourceVerificationHelp.checkResult(intent.getStringExtra("verificationResultKey"))
        super.finish()
    }

    private fun finishVerificationUi() {
        val finished = CountDownLatch(1)
        runOnUiThread {
            try {
                finish()
            } finally {
                finished.countDown()
            }
        }
        finished.await(5, SECONDS)
    }

    internal fun close() {
        if (!isCloudflareChallenge) {
            confirmAndFinish()
        }
    }

    /** Same as toolbar checkmark: save verification HTML/cookies then finish. */
    internal fun confirmAndFinish() {
        cancelAutoClose()
        if (isFinishing || isDestroyed) return
        if (viewModel.sourceVerificationEnable) {
            viewModel.saveVerificationResult(currentWebView) {
                finish()
            }
        } else {
            finish()
        }
    }

    /**
     * During App/MCP book-source check, auto-click the checkmark after the page settles.
     * Skipped while Cloudflare challenge UI is active.
     */
    internal fun scheduleAutoCloseIfChecking() {
        if (isFinishing || isDestroyed || !::currentWebView.isInitialized) {
            cancelAutoClose()
            return
        }
        if (!Debug.isChecking || !viewModel.sourceVerificationEnable || isCloudflareChallenge) {
            cancelAutoClose()
            return
        }
        cancelAutoClose()
        currentWebView.postDelayed(autoCloseRunnable, AUTO_CLOSE_DELAY_MS)
    }

    internal fun cancelAutoClose() {
        if (::currentWebView.isInitialized) {
            currentWebView.removeCallbacks(autoCloseRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        if (powerManager.isInteractive) {
            wasScreenOff = false
            currentWebView.onPause()
        } else {
            wasScreenOff = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (!wasScreenOff) {
            currentWebView.onResume()
        }
    }

    override fun onDestroy() {
        cancelAutoClose()
        WebViewPool.release(pooledWebView)
        super.onDestroy()
    }
}
