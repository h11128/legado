package io.legado.app.model.webBook

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.toBookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.releaseHtmlData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.RespondTimeUpdater
import io.legado.app.model.checkalgo.AskSourcePrefetch
import io.legado.app.model.checkalgo.AskTimeout
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.mapParallelSafe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

class SearchModel(private val scope: CoroutineScope, private val callBack: CallBack) {
    val threadCount = AppConfig.threadCount
    private var searchPool: ExecutorCoroutineDispatcher? = null
    private var mSearchId = 0L
    private var searchPage = 1
    private var searchKey: String = ""
    private var bookSourceParts = emptyList<BookSourcePart>()
    private var searchBooks = arrayListOf<SearchBook>()
    /** Raw per-source hits for RFC-003 rebuild (never absorb these in place). */
    private var rawSearchHits = arrayListOf<SearchBook>()
    private val pageOwner = SearchPageOwner()
    private var workingState = MutableStateFlow(true)
    private var activeProgress = AtomicReference<SearchProgressReporter?>()
    /** URLs already noted for the current [mSearchId] (once per source per run). */
    private val notedRespondTimeUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** First page of a new searchId must heal+load parts on the pool thread. */
    private var reloadPartsOnStart = false
    /** Scope string captured at search() — avoids pool reading a later UI edit. */
    private var pendingScopeSnapshot: String? = null

    private fun initSearchPool() {
        searchPool?.close()
        searchPool = Executors
            .newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    }

    fun search(searchId: Long, key: String) {
        synchronized(pageOwner) {
            if (searchId == mSearchId && pageOwner.isRunning()) return
            if (searchId != mSearchId) {
                if (key.isEmpty()) {
                    return
                }
                searchKey = key
                if (mSearchId != 0L) {
                    close()
                }
                searchBooks.clear()
                rawSearchHits.clear()
                bookSourceParts = emptyList()
                mSearchId = searchId
                searchPage = 1
                notedRespondTimeUrls.clear()
                reloadPartsOnStart = true
                pendingScopeSnapshot = callBack.getSearchScope().toString()
                initSearchPool()
            } else {
                searchPage++
                reloadPartsOnStart = false
            }
            startSearch()
        }
    }

    private fun startSearch() {
        val precision = appCtx.getPrefBoolean(PreferKey.precisionSearch)
        var hasMore = false
        val key = searchKey
        val page = searchPage
        val needReloadParts = reloadPartsOnStart
        val scopeSnapshot = pendingScopeSnapshot
        reloadPartsOnStart = false
        pendingScopeSnapshot = null
        activeProgress.getAndSet(null)?.cancel()
        val job = scope.launch(searchPool!!, start = CoroutineStart.LAZY) {
            if (needReloadParts) {
                SourceHelp.ensureRespondTimeHealed()
                val parts = SearchScope(scopeSnapshot.orEmpty()).getBookSourceParts()
                if (parts.isEmpty()) {
                    pageOwner.complete(currentCoroutineContext()[Job]) {
                        callBack.onSearchCancel(NoStackTraceException("启用书源为空"))
                    }
                    return@launch
                }
                bookSourceParts = parts
            }
            val sourceParts = bookSourceParts
            if (sourceParts.isEmpty()) {
                pageOwner.complete(currentCoroutineContext()[Job]) {
                    callBack.onSearchCancel(NoStackTraceException("启用书源为空"))
                }
                return@launch
            }
            val progress = SearchProgressReporter(sourceParts.size, callBack::onSearchProgress)
            activeProgress.getAndSet(progress)?.cancel()
            flow {
                for (chunk in AskSourcePrefetch.chunkParts(sourceParts)) {
                    val resolved = chunk.toBookSource()
                    val byUrl = resolved.associateBy { it.bookSourceUrl }
                    for (part in chunk) {
                        val source = byUrl[part.bookSourceUrl]
                        if (source == null) {
                            if (currentCoroutineContext().isActive) {
                                progress.completeOne()
                            }
                        } else {
                            emit(source)
                        }
                        workingState.first { it }
                    }
                }
            }.onStart {
                progress.start(callBack::onSearchStart)
            }.mapParallelSafe(threadCount) {
                try {
                    val startTime = System.currentTimeMillis()
                    withTimeout(AskTimeout.SEARCH_MS) {
                        val items = WebBook.searchBookAwait(
                            it, key, page,
                            filter = { name, author, kind ->
                                !precision || name.contains(key) ||
                                        author.contains(key) ||
                                        kind?.contains(key) == true
                            })
                        if (items.isNotEmpty() && notedRespondTimeUrls.add(it.bookSourceUrl)) {
                            RespondTimeUpdater.noteSuccess(
                                it.bookSourceUrl,
                                System.currentTimeMillis() - startTime,
                                it.respondTime,
                            )
                        }
                        items
                    }
                } finally {
                    if (currentCoroutineContext().isActive) {
                        progress.completeOne()
                    }
                }
            }.onEach { items ->
                for (book in items) {
                    book.releaseHtmlData()
                }
                hasMore = hasMore || items.isNotEmpty()
                appDb.searchBookDao.insert(*items.toTypedArray())
                mergeItems(items, precision, key)
                currentCoroutineContext().ensureActive()
                callBack.onSearchSuccess(searchBooks)
            }.onCompletion { error ->
                withContext(NonCancellable) {
                    RespondTimeUpdater.flush()
                    runCatching {
                        rebuildDisplay(precision, key)
                        callBack.onSearchSuccess(searchBooks)
                    }
                }
                val context = currentCoroutineContext()
                pageOwner.complete(context[Job]) {
                    when {
                        error == null -> progress.finish {
                            callBack.onSearchFinish(searchBooks.isEmpty(), hasMore)
                        }
                        context.isActive -> progress.finish {
                            callBack.onSearchCancel()
                        }
                        else -> progress.cancel()
                    }
                    activeProgress.compareAndSet(progress, null)
                }
            }.catch {
                AppLog.put("书源搜索出错\n${it.localizedMessage}", it)
            }.collect()
        }
        check(pageOwner.register(job))
        job.start()
    }

    private suspend fun mergeItems(newDataS: List<SearchBook>, precision: Boolean, key: String) {
        if (newDataS.isNotEmpty()) {
            for (book in newDataS) {
                currentCoroutineContext().ensureActive()
                rawSearchHits.add(book.copy())
            }
        }
        rebuildDisplay(precision, key)
    }

    /** RFC-003 §4.6: authoritative list is only rebuild output from raw hits. */
    private suspend fun rebuildDisplay(precision: Boolean, key: String) {
        val merged = SearchBookMerge.rebuildFromRawHits(rawSearchHits)
        val equalData = arrayListOf<SearchBook>()
        val containsData = arrayListOf<SearchBook>()
        val tagsData = arrayListOf<SearchBook>()
        val otherData = arrayListOf<SearchBook>()
        for (book in merged) {
            currentCoroutineContext().ensureActive()
            when {
                book.name == key || book.author == key -> equalData.add(book)
                book.kind?.contains(key) == true -> tagsData.add(book)
                book.name.contains(key) || book.author.contains(key) -> containsData.add(book)
                !precision -> otherData.add(book)
            }
        }
        currentCoroutineContext().ensureActive()
        equalData.sortByDescending { it.origins.size }
        equalData.addAll(tagsData.sortedByDescending { it.origins.size })
        equalData.addAll(containsData.sortedByDescending { it.origins.size })
        if (!precision) {
            equalData.addAll(otherData)
        }
        currentCoroutineContext().ensureActive()
        searchBooks = equalData
    }

    fun pause() {
        workingState.value = false
    }

    fun resume() {
        workingState.value = true
    }

    fun cancelSearch() {
        close()
        callBack.onSearchCancel()
    }

    fun close() {
        synchronized(pageOwner) {
            activeProgress.getAndSet(null)?.cancel()
            pageOwner.cancel()?.cancel()
            searchPool?.close()
            searchPool = null
            reloadPartsOnStart = false
            pendingScopeSnapshot = null
            mSearchId = 0L
        }
    }

    interface CallBack {
        fun getSearchScope(): SearchScope
        fun onSearchStart()
        fun onSearchProgress(searched: Int, total: Int)
        fun onSearchSuccess(searchBooks: List<SearchBook>)
        fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean)
        fun onSearchCancel(exception: Throwable? = null)
    }
}
