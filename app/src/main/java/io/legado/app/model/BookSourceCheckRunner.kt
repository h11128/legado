package io.legado.app.model

import com.script.ScriptException
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.checkalgo.CheckAlgoRuntime
import io.legado.app.model.checkalgo.CheckHedgedProbe
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.selectCheckSourceChapter
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.htmlunit.corejs.javascript.WrappedException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Shared book-source check used by CheckSourceService and MCP.
 */
object BookSourceCheckRunner {

    data class Outcome(
        val success: Boolean,
        val message: String,
    )

    fun parseEndpoint(domain: String): Pair<String, Int>? {
        val rawUrl = domain.substringBefore('#')
        val uri = kotlin.runCatching { URI(rawUrl) }.getOrNull() ?: return null
        if (uri.rawAuthority.isNullOrBlank()) return null
        val url = rawUrl.toHttpUrlOrNull() ?: return null
        return url.host to url.port
    }

    suspend fun checkSource(
        source: BookSource,
        timeoutMs: Long = CheckSource.timeout,
        keyword: String = CheckSource.keyword,
        emptyTocMessage: String = "目录为空",
        checkMode: CheckMode = CheckMode.Default,
    ): Outcome {
        val startTime = System.currentTimeMillis()
        return withContext(checkMode) {
            kotlin.runCatching {
                withTimeout(timeoutMs) {
                    doCheckSource(source, keyword, emptyTocMessage, checkMode)
                }
            }.fold(
                onSuccess = {
                    Debug.updateFinalMessage(source.bookSourceUrl, "校验成功")
                    Outcome(true, "校验成功")
                },
                onFailure = { error ->
                    currentCoroutineContext().ensureActive()
                    when (error) {
                        is TimeoutCancellationException -> source.addGroup("校验超时")
                        is ScriptException, is WrappedException -> source.addGroup("js失效")
                        !is NoStackTraceException -> source.addGroup("网站失效")
                    }
                    if (CheckSource.wSourceComment) {
                        source.addErrorComment(error)
                    }
                    val msg = error.localizedMessage ?: error.toString()
                    Debug.updateFinalMessage(source.bookSourceUrl, "校验失败:$msg")
                    Outcome(false, "校验失败:$msg")
                },
            )
        }.also {
            source.respondTime = System.currentTimeMillis() - startTime
        }
    }

    private suspend fun doCheckSource(
        source: BookSource,
        keyword: String,
        emptyTocMessage: String,
        checkMode: CheckMode,
    ) {
        Debug.startChecking(source)
        source.removeInvalidGroups()
        if (CheckSource.wSourceComment) source.removeErrorComment()
        ensureDomain(source)
        var searchDeepOk = false
        if (CheckSource.checkSearch) {
            searchDeepOk = runSearch(source, keyword, emptyTocMessage)
        }
        val skipDiscovery =
            checkMode.skipDiscoveryIfSearchOk && searchDeepOk && CheckSource.checkSearch
        if (CheckSource.checkDiscovery && !source.exploreUrl.isNullOrBlank() && !skipDiscovery) {
            runDiscovery(source, emptyTocMessage)
        } else if (skipDiscovery) {
            source.removeGroup("发现规则为空")
            source.removeGroup("发现失效")
        }
        val finalCheckMessage = source.getInvalidGroupNames()
        if (finalCheckMessage.isNotBlank()) {
            throw NoStackTraceException(finalCheckMessage)
        }
    }

    private suspend fun ensureDomain(source: BookSource) {
        if (!CheckSource.checkDomain) return
        val endpoint = parseEndpoint(source.bookSourceUrl)
            ?: throw NoStackTraceException("源地址不是http链接")
        val (host, port) = endpoint
        if (CheckDnsGuard.isBlocked(host)) {
            source.addGroup("域名失效")
            throw NoStackTraceException("源地址不可访问(熔断)")
        }
        val reachable = CheckHedgedProbe.hedged(
            primaryDelayMs = 350,
            primary = {
                // Prefer cached/warm DNS then connect.
                val cached = CheckDnsGuard.lookupCached(host)
                if (cached != null) {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(cached.first(), port), 1200)
                        true
                    }
                } else {
                    isDomainReachable(host, port)
                }
            },
            fallback = { isDomainReachable(host, port) },
        )
        if (reachable) {
            source.removeGroup("域名失效")
            warmDns(host)
        } else {
            CheckDnsGuard.markFailed(host)
            source.addGroup("域名失效")
            throw NoStackTraceException("源地址不可访问")
        }
    }

    private suspend fun runSearch(
        source: BookSource,
        keyword: String,
        emptyTocMessage: String,
    ): Boolean {
        val searchWord = source.getCheckKeyword(keyword)
        if (!(source.isJsSource() || !source.searchUrl.isNullOrBlank())) {
            source.addGroup("搜索链接规则为空")
            return false
        }
        source.removeGroup("搜索链接规则为空")
        val host = CheckAlgoRuntime.hostOf(source.bookSourceUrl)
        if (!CheckAlgoRuntime.ewma.shouldDeepCheck(host)) {
            source.addGroup("EWMA跳过")
            return false
        }
        val firstBook = run {
            val searchBooks = WebBook.searchBookAwait(source, searchWord)
            searchBooks.firstOrNull()?.toBook()
        }
        if (firstBook == null) {
            source.addGroup("搜索失效")
            return false
        }
        source.removeGroup("搜索失效")
        return checkBook(firstBook, source, emptyTocMessage, true)
    }

    private suspend fun runDiscovery(source: BookSource, emptyTocMessage: String) {
        val url = source.exploreKinds().firstOrNull { !it.url.isNullOrBlank() }?.url
        if (url.isNullOrBlank()) {
            source.addGroup("发现规则为空")
            return
        }
        source.removeGroup("发现规则为空")
        val firstBook = run {
            val exploreBooks = WebBook.exploreBookAwait(source, url)
            exploreBooks.firstOrNull()?.toBook()
        }
        if (firstBook == null) {
            source.addGroup("发现失效")
            return
        }
        source.removeGroup("发现失效")
        checkBook(firstBook, source, emptyTocMessage, false)
    }

    /** @return true when deep path (info/toc/content as configured) succeeded */
    private suspend fun checkBook(
        book: Book,
        source: BookSource,
        emptyTocMessage: String,
        isSearchBook: Boolean,
    ): Boolean {
        var deepOk = true
        kotlin.runCatching {
            if (!CheckSource.checkInfo) return@runCatching
            if (book.tocUrl.isBlank()) {
                WebBook.getBookInfoAwait(source, book)
            }
            if (!CheckSource.checkCategory || source.bookSourceType == BookSourceType.file) {
                return@runCatching
            }
            val chapterSelection = run {
                val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
                selectCheckSourceChapter(chapters = chapters, emptyMessage = emptyTocMessage)
            }
            if (!CheckSource.checkContent) return@runCatching
            WebBook.getContentAwait(
                bookSource = source,
                book = book,
                bookChapter = chapterSelection.chapter,
                nextChapterUrl = chapterSelection.nextChapterUrl,
                needSave = false,
            )
        }.onFailure {
            deepOk = false
            val bookType = if (isSearchBook) "搜索" else "发现"
            when (it) {
                is ContentEmptyException -> source.addGroup("${bookType}正文失效")
                is TocEmptyException -> source.addGroup("${bookType}目录失效")
                else -> throw it
            }
        }.onSuccess {
            val bookType = if (isSearchBook) "搜索" else "发现"
            source.removeGroup("${bookType}目录失效")
            source.removeGroup("${bookType}正文失效")
        }
        return deepOk
    }

    private fun warmDns(host: String) {
        CheckDnsGuard.lookupCached(host)?.let {
            CheckDnsGuard.markOk(host, it)
            return
        }
        val looked = kotlin.runCatching { InetAddress.getAllByName(host).toList() }.getOrNull()
        CheckDnsGuard.markOk(host, looked)
    }

    private suspend fun isDomainReachable(host: String, port: Int): Boolean {
        return kotlin.runCatching {
            withTimeout(2000) {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 1600)
                    true
                }
            }
        }.getOrDefault(false)
    }
}
