package io.legado.app.web.mcp

import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.configureCheckHttpLimits
import io.legado.app.help.http.restoreDefaultHttpLimits
import io.legado.app.model.BookSourceCheckRunner
import io.legado.app.model.CheckDnsGuard
import io.legado.app.model.CheckSource
import io.legado.app.model.CheckSourceResultWriter
import io.legado.app.model.Debug
import io.legado.app.model.checkalgo.CheckAimdLimiter
import io.legado.app.model.checkalgo.CheckAlgoRuntime
import io.legado.app.model.checkalgo.CheckHostTokenBucket
import io.legado.app.model.checkalgo.CheckPriorityOrder
import io.legado.app.model.checkalgo.CheckWorkStealingScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * In-process multi-thread book-source check for MCP.
 * Uses AIMD concurrency, host token buckets, work-stealing, priority order, bloom dedup.
 */
object McpSourceCheckJob {

    private const val MAX_STORED_RESULTS = 3_000
    private const val CHECK_MAX_REQUESTS_PER_HOST = 8
    private const val MAX_CHECK_POOL_THREADS = 128

    data class ResultItem(
        val url: String,
        val name: String,
        val success: Boolean,
        val message: String,
        val group: String,
        val respondTime: Long,
        val durationMs: Long,
    )

    data class CheckFlags(
        val checkDomain: Boolean,
        val checkSearch: Boolean,
        val checkDiscovery: Boolean,
        val checkInfo: Boolean,
        val checkCategory: Boolean,
        val checkContent: Boolean,
        val wSourceComment: Boolean,
    )

    data class Snapshot(
        val running: Boolean,
        val total: Int,
        val finished: Int,
        val success: Int,
        val failed: Int,
        val keyword: String,
        val threadCount: Int,
        val startedAt: Long?,
        val finishedAt: Long?,
        val lastProgressAt: Long? = null,
        val error: String?,
        val results: List<ResultItem>,
        val resultOffset: Int,
        val resultTotal: Int,
        val aimdConcurrency: Int = 0,
        val checkFlags: CheckFlags? = null,
        val hostThrottled: Map<String, Double>? = null,
        val hostEwmaLow: Map<String, Double>? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var job: Job? = null
    private var dispatcher = Executors.newFixedThreadPool(1).asCoroutineDispatcher()

    @Volatile private var running = false
    @Volatile private var total = 0
    @Volatile private var keyword = CheckSource.keyword
    @Volatile private var threadCount = 1
    @Volatile private var startedAt: Long? = null
    @Volatile private var finishedAt: Long? = null
    @Volatile private var lastProgressAt: Long? = null
    @Volatile private var error: String? = null
    @Volatile private var aimdConcurrency: Int = 0
    @Volatile private var activeFlags: CheckFlags? = null
    @Volatile private var activeSettings: CheckSource.Settings? = null
    @Volatile private var activeTokens: CheckHostTokenBucket? = null

    private val finished = AtomicInteger(0)
    private val success = AtomicInteger(0)
    private val failed = AtomicInteger(0)
    private val resultsLock = Any()
    private val results = ArrayList<ResultItem>(256)

    fun isActive(): Boolean = running || job?.isActive == true

    fun lastProgressAtMs(): Long = lastProgressAt ?: startedAt ?: 0L

    /**
     * Non-suspending cancel for AlarmManager watchdog.
     * Does NOT clear running / Debug.isChecking / channel-guard mirrors —
     * those stay busy until the job [finally] block runs, so deferred MCP
     * restarts cannot race a still-working pool.
     */
    fun requestStopFromWatchdog(): String {
        val active = job
        if (!running && active?.isActive != true) {
            return "no check running"
        }
        active?.cancel()
        return "check cancel requested (${finished.get()}/$total)"
    }

    suspend fun start(
        urls: List<String>?,
        enabledOnly: Boolean,
        keywordOverride: String?,
        threadCountOverride: Int?,
        timeoutMsOverride: Long?,
        checkDomainOverride: Boolean? = null,
        checkSearchOverride: Boolean? = null,
        checkDiscoveryOverride: Boolean? = null,
        checkInfoOverride: Boolean? = null,
        checkCategoryOverride: Boolean? = null,
        checkContentOverride: Boolean? = null,
        wSourceCommentOverride: Boolean? = null,
    ): String = mutex.withLock {
        if (running || job?.isActive == true) {
            return "已有 MCP 批量校验在进行中"
        }
        if (Debug.callback != null || Debug.isChecking) {
            return "调试/校验通道占用中，请稍后重试"
        }
        if (!Debug.tryStartChecking()) {
            return "调试/校验通道占用中，请稍后重试"
        }
        val rawUrls = resolveUrls(urls, enabledOnly)
        val pendingUrls = rawUrls.filterNot { CheckAlgoRuntime.bloom.mightContain(it) }
            .ifEmpty { rawUrls }
        val respondTimes = pendingUrls.associateWith { url ->
            appDb.bookSourceDao.getBookSource(url)?.respondTime ?: Long.MAX_VALUE / 2
        }
        val sourceUrls = CheckPriorityOrder.orderByPriority(pendingUrls, respondTimes)
        if (sourceUrls.isEmpty()) {
            Debug.finishChecking()
            return "没有可校验的书源"
        }
        keyword = keywordOverride?.takeIf { it.isNotBlank() } ?: CheckSource.keyword
        threadCount = (threadCountOverride ?: AppConfig.threadCount)
            .coerceIn(1, min(AppConst.MAX_THREAD, MAX_CHECK_POOL_THREADS))
        val timeoutMs = (timeoutMsOverride ?: CheckSource.timeout).coerceIn(5_000L, 600_000L)
        val jobSettings = CheckSource.Settings.merge(
            checkDomain = checkDomainOverride,
            checkSearch = checkSearchOverride,
            checkDiscovery = checkDiscoveryOverride,
            checkInfo = checkInfoOverride,
            checkCategory = checkCategoryOverride,
            checkContent = checkContentOverride,
            wSourceComment = wSourceCommentOverride,
        )
        activeSettings = jobSettings
        activeFlags = jobSettings.toCheckFlags()
        try {
            resetCounters(sourceUrls.size)
            CheckDnsGuard.clear()
            CheckAlgoRuntime.resetEwma()
            val aimd = CheckAimdLimiter(
                maxConcurrency = threadCount,
                minConcurrency = 1,
                initial = (threadCount / 2).coerceAtLeast(1),
            )
            val tokens = CheckHostTokenBucket(maxTokensPerHost = 4, refillPerSecond = 4.0)
            activeTokens = tokens
            val inFlight = AtomicInteger(0)
            aimdConcurrency = aimd.current()
            runCatching { dispatcher.close() }
            val myDispatcher = Executors.newFixedThreadPool(threadCount).asCoroutineDispatcher()
            dispatcher = myDispatcher
            configureCheckHttpLimits(
                maxRequests = (threadCount * 2).coerceAtMost(256),
                maxRequestsPerHost = CHECK_MAX_REQUESTS_PER_HOST,
            )
            running = true
            val now = System.currentTimeMillis()
            startedAt = now
            lastProgressAt = now
            finishedAt = null
            error = null
            McpChannelGuard.noteCheckStarted(sourceUrls.size)
            job = scope.launch(myDispatcher) {
                try {
                    val scheduler = CheckWorkStealingScheduler<String>()
                    for (url in sourceUrls) {
                        scheduler.offer(CheckAlgoRuntime.hostOf(url), url)
                    }
                    scheduler.run(workers = threadCount) { host, url ->
                        CheckAlgoRuntime.acquireAimdSlot(aimd, inFlight)
                        aimdConcurrency = aimd.current()
                        try {
                            tokens.acquire(host)
                            val source = appDb.bookSourceDao.getBookSource(url)
                            if (source == null || (enabledOnly && !source.enabled)) {
                                markSkipped(url, source?.bookSourceName.orEmpty())
                                return@run
                            }
                            val outcome = checkOne(source, timeoutMs, aimd)
                            if (outcome.success) {
                                CheckAlgoRuntime.bloom.put(url)
                            }
                            CheckAlgoRuntime.ewma.onResult(host, outcome.success)
                        } finally {
                            CheckAlgoRuntime.releaseAimdSlot(inFlight)
                            aimdConcurrency = aimd.current()
                        }
                    }
                } catch (t: Throwable) {
                    error = t.localizedMessage ?: t.toString()
                } finally {
                    activeSettings = null
                    activeFlags = null
                    activeTokens = null
                    CheckSourceResultWriter.flush()
                    running = false
                    finishedAt = System.currentTimeMillis()
                    lastProgressAt = finishedAt
                    Debug.finishChecking()
                    restoreDefaultHttpLimits()
                    runCatching { myDispatcher.close() }
                    McpChannelGuard.noteCheckFinished(finished.get(), total)
                }
            }
        } catch (t: Throwable) {
            activeSettings = null
            activeFlags = null
            activeTokens = null
            Debug.finishChecking()
            throw t
        }
        val flags = activeFlags ?: captureFlags()
        return "已开始校验 ${sourceUrls.size} 个书源（线程=$threadCount, keyword=$keyword, " +
            "domain=${flags.checkDomain}, search=${flags.checkSearch}, " +
            "discovery=${flags.checkDiscovery}, info=${flags.checkInfo}, " +
            "category=${flags.checkCategory}, content=${flags.checkContent}, " +
            "wComment=${flags.wSourceComment}, AIMD）"
    }

    suspend fun stop(): String = mutex.withLock {
        if (!running && job?.isActive != true) {
            return "当前没有进行中的 MCP 批量校验"
        }
        val active = job
        job = null
        active?.cancelAndJoin()
        running = false
        finishedAt = System.currentTimeMillis()
        CheckSourceResultWriter.flush()
        // finishChecking / restore / dispatcher close happen in job finally when join returns;
        // if job was already null/dead, still clear gates.
        if (Debug.isChecking) Debug.finishChecking()
        restoreDefaultHttpLimits()
        return "已停止 MCP 批量校验（完成 ${finished.get()}/$total）"
    }

    fun snapshot(resultOffset: Int = 0, resultLimit: Int = 50): Snapshot {
        val offset = resultOffset.coerceAtLeast(0)
        val limit = resultLimit.coerceIn(1, 500)
        val page: List<ResultItem>
        val resultTotal: Int
        synchronized(resultsLock) {
            resultTotal = results.size
            page = if (offset >= resultTotal) {
                emptyList()
            } else {
                ArrayList(results.subList(offset, min(offset + limit, resultTotal)))
            }
        }
        return Snapshot(
            running = running,
            total = total,
            finished = finished.get(),
            success = success.get(),
            failed = failed.get(),
            keyword = keyword,
            threadCount = threadCount,
            startedAt = startedAt,
            finishedAt = finishedAt,
            lastProgressAt = lastProgressAt,
            error = error,
            results = page,
            resultOffset = offset,
            resultTotal = resultTotal,
            aimdConcurrency = aimdConcurrency,
            checkFlags = activeFlags,
            hostThrottled = if (running) {
                activeTokens?.hostsWithLowTokens()?.takeIf { it.isNotEmpty() }
            } else {
                null
            },
            hostEwmaLow = if (running) {
                CheckAlgoRuntime.ewma.hostsBelowRate().takeIf { it.isNotEmpty() }
            } else {
                null
            },
        )
    }

    fun antiBlockSnapshot(): Pair<Map<String, Double>?, Map<String, Double>?> {
        if (!running) return null to null
        val throttled = activeTokens?.hostsWithLowTokens()?.takeIf { it.isNotEmpty() }
        val ewmaLow = CheckAlgoRuntime.ewma.hostsBelowRate().takeIf { it.isNotEmpty() }
        return throttled to ewmaLow
    }

    private fun CheckSource.Settings.toCheckFlags() = CheckFlags(
        checkDomain = checkDomain,
        checkSearch = checkSearch,
        checkDiscovery = checkDiscovery,
        checkInfo = checkInfo,
        checkCategory = checkCategory,
        checkContent = checkContent,
        wSourceComment = wSourceComment,
    )

    private fun captureFlags(): CheckFlags = CheckSource.Settings.fromGlobals().toCheckFlags()

    private fun markSkipped(url: String, name: String) {
        addResult(
            ResultItem(
                url = url,
                name = name.ifEmpty { url },
                success = false,
                message = "跳过",
                group = "",
                respondTime = 0L,
                durationMs = 0L,
            )
        )
        finished.incrementAndGet()
        failed.incrementAndGet()
        lastProgressAt = System.currentTimeMillis()
        McpChannelGuard.noteCheckProgress(finished.get(), total)
    }

    private suspend fun checkOne(
        source: BookSource,
        timeoutMs: Long,
        aimd: CheckAimdLimiter,
    ): BookSourceCheckRunner.Outcome {
        val begin = System.currentTimeMillis()
        val settings = activeSettings ?: CheckSource.Settings.fromGlobals()
        val outcome = BookSourceCheckRunner.checkSource(
            source = source,
            timeoutMs = timeoutMs,
            keyword = keyword,
            emptyTocMessage = appCtx.getString(R.string.chapter_list_empty),
            settings = settings,
        )
        val duration = System.currentTimeMillis() - begin
        when {
            outcome.success -> {
                aimd.onSuccess()
                if (duration > timeoutMs / 2) aimd.onSlow(duration, timeoutMs / 2)
            }
            outcome.message.contains("超时") -> aimd.onTimeout()
            // Soft fail: do not shrink AIMD (avoids collapsing to 1 under mixed failures).
        }
        CheckSourceResultWriter.enqueueAndMaybeFlush(source)
        addResult(
            ResultItem(
                url = source.bookSourceUrl,
                name = source.bookSourceName,
                success = outcome.success,
                message = outcome.message,
                group = source.bookSourceGroup.orEmpty(),
                respondTime = source.respondTime,
                durationMs = duration,
            )
        )
        Debug.clearSourceCheckState(source.bookSourceUrl)
        finished.incrementAndGet()
        if (outcome.success) success.incrementAndGet() else failed.incrementAndGet()
        lastProgressAt = System.currentTimeMillis()
        McpChannelGuard.noteCheckProgress(finished.get(), total)
        return outcome
    }

    private fun resolveUrls(urls: List<String>?, enabledOnly: Boolean): List<String> {
        if (!urls.isNullOrEmpty()) return urls.distinct()
        return if (enabledOnly) {
            appDb.bookSourceDao.allEnabledUrls()
        } else {
            appDb.bookSourceDao.allUrls()
        }
    }

    private fun addResult(item: ResultItem) {
        synchronized(resultsLock) {
            results.add(item)
            while (results.size > MAX_STORED_RESULTS) {
                results.removeAt(0)
            }
        }
    }

    private fun resetCounters(size: Int) {
        total = size
        finished.set(0)
        success.set(0)
        failed.set(0)
        synchronized(resultsLock) { results.clear() }
    }
}
