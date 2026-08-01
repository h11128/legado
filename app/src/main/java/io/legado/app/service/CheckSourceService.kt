package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.IntentData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.configureCheckHttpLimits
import io.legado.app.help.http.restoreDefaultHttpLimits
import io.legado.app.model.BookSourceCheckRunner
import io.legado.app.model.CheckDnsGuard
import io.legado.app.model.CheckSourceResultWriter
import io.legado.app.model.Debug
import io.legado.app.model.checkalgo.CheckAimdLimiter
import io.legado.app.model.checkalgo.CheckAlgoRuntime
import io.legado.app.model.checkalgo.CheckHostTokenBucket
import io.legado.app.model.checkalgo.CheckPriorityOrder
import io.legado.app.model.checkalgo.CheckWorkStealingScheduler
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** Kept for unit tests / callers that import the old top-level helper. */
internal fun parseCheckSourceEndpoint(domain: String): Pair<String, Int>? =
    BookSourceCheckRunner.parseEndpoint(domain)

/**
 * 校验书源
 */
class CheckSourceService : BaseService() {
    private var threadCount = AppConfig.threadCount
    private var searchCoroutine =
        Executors.newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    private var notificationMsg = appCtx.getString(R.string.service_starting)
    private var checkJob: Job? = null
    private var originSize = 0
    private val finishCount = AtomicInteger(0)
    @Volatile
    private var activeTokens: CheckHostTokenBucket? = null

    companion object {
        /** Above this, bulk check often trips WAF / mass false 搜索失效. */
        const val THREAD_COUNT_BAN_WARN = 16
    }

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdReadAloud)
            .setSmallIcon(R.drawable.ic_network_check)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.check_book_source))
            .setContentIntent(activityPendingIntent<BookSourceActivity>("activity"))
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<CheckSourceService>(IntentAction.stop),
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> IntentData.get<List<String>>("checkSourceSelectedIds")?.let {
                check(it)
            }
            IntentAction.resume -> upNotification()
            IntentAction.stop -> stopSelf()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        Debug.finishChecking()
        restoreDefaultHttpLimits()
        searchCoroutine.close()
        postEvent(EventBus.CHECK_SOURCE_DONE, 0)
        notificationManager.cancel(NotificationId.CheckSourceService)
    }

    private fun check(ids: List<String>) {
        if (checkJob?.isActive == true) {
            toastOnUi("已有书源在校验,等完成后再试")
            return
        }
        threadCount = AppConfig.threadCount.coerceIn(1, min(AppConst.MAX_THREAD, 128))
        if (!Debug.tryStartChecking()) {
            toastOnUi("调试/校验通道占用中，请稍后重试")
            return
        }
        if (threadCount > THREAD_COUNT_BAN_WARN) {
            toastOnUi(getString(R.string.thread_count_ban_warn, THREAD_COUNT_BAN_WARN))
        }
        CheckDnsGuard.clear()
        CheckAlgoRuntime.resetEwma()
        val pending = ids.filterNot { CheckAlgoRuntime.bloom.mightContain(it) }.ifEmpty { ids }
        val respondTimes = pending.associateWith { url ->
            appDb.bookSourceDao.getBookSource(url)?.respondTime ?: Long.MAX_VALUE / 2
        }
        val ordered = CheckPriorityOrder.orderByPriority(pending, respondTimes)
        originSize = ordered.size
        finishCount.set(0)
        notificationMsg = getString(R.string.progress_show, "", 0, originSize)
        upNotification()
        configureCheckHttpLimits(
            maxRequests = (threadCount * 2).coerceAtMost(256),
            maxRequestsPerHost = 8,
        )
        searchCoroutine.close()
        searchCoroutine =
            Executors.newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD).coerceAtMost(128))
                .asCoroutineDispatcher()
        val aimd = CheckAimdLimiter(
            maxConcurrency = threadCount,
            minConcurrency = 1,
            initial = (threadCount / 2).coerceAtLeast(1),
        )
        val tokens = CheckHostTokenBucket(
            maxTokensPerHost = CheckHostTokenBucket.DEFAULT_MAX_TOKENS,
            refillPerSecond = CheckHostTokenBucket.DEFAULT_REFILL_PER_SECOND,
        )
        activeTokens = tokens
        val inFlight = AtomicInteger(0)
        checkJob = lifecycleScope.launch(searchCoroutine) {
            try {
                val scheduler = CheckWorkStealingScheduler<String>()
                for (url in ordered) {
                    scheduler.offer(CheckAlgoRuntime.hostOf(url), url)
                }
                scheduler.run(workers = threadCount) { host, url ->
                    CheckAlgoRuntime.acquireAimdSlot(aimd, inFlight)
                    try {
                        val source = appDb.bookSourceDao.getBookSource(url)
                        if (source == null) {
                            finishCount.incrementAndGet()
                            upNotification()
                            return@run
                        }
                        tokens.acquire(host, source.concurrentRate)
                        checkSource(source, aimd)
                        // Only bloom successful checks so failures remain retriable.
                    } finally {
                        CheckAlgoRuntime.releaseAimdSlot(inFlight)
                    }
                }
            } finally {
                activeTokens = null
                CheckSourceResultWriter.flush()
                restoreDefaultHttpLimits()
                Debug.finishChecking()
                stopSelf()
            }
        }
    }

    private suspend fun checkSource(source: BookSource, aimd: CheckAimdLimiter) {
        val begin = System.currentTimeMillis()
        val outcome = BookSourceCheckRunner.checkSource(
            source = source,
            emptyTocMessage = getString(R.string.chapter_list_empty),
        )
        val duration = System.currentTimeMillis() - begin
        when {
            outcome.success -> {
                aimd.onSuccess()
                if (duration > 15_000L) aimd.onSlow(duration, 15_000L)
                CheckAlgoRuntime.bloom.put(source.bookSourceUrl)
            }
            outcome.message.contains("超时") -> aimd.onTimeout()
        }
        CheckAlgoRuntime.ewma.onResult(CheckAlgoRuntime.hostOf(source.bookSourceUrl), outcome.success)
        val done = finishCount.incrementAndGet()
        notificationMsg = formatProgressMessage(source.bookSourceName, done)
        upNotification()
        CheckSourceResultWriter.enqueueAndMaybeFlush(source)
        Debug.clearSourceCheckState(source.bookSourceUrl)
    }

    private fun formatProgressMessage(name: String, done: Int): String {
        val throttledHosts = activeTokens?.hostsWithLowTokens()?.keys.orEmpty()
        val ewmaHosts = CheckAlgoRuntime.ewma.hostsBelowRate().keys
        val pressure = (throttledHosts + ewmaHosts).size
        return if (pressure > 0) {
            getString(R.string.progress_show_throttled, name, done, originSize, pressure)
        } else {
            getString(R.string.progress_show, name, done, originSize)
        }
    }

    private fun upNotification() {
        notificationBuilder.setContentText(notificationMsg)
        notificationBuilder.setProgress(originSize, finishCount.get(), false)
        postEvent(EventBus.CHECK_SOURCE, notificationMsg)
        notificationManager.notify(NotificationId.CheckSourceService, notificationBuilder.build())
    }

    override fun startForegroundNotification() {
        notificationBuilder.setContentText(notificationMsg)
        notificationBuilder.setProgress(originSize, finishCount.get(), false)
        postEvent(EventBus.CHECK_SOURCE, notificationMsg)
        startForeground(NotificationId.CheckSourceService, notificationBuilder.build())
    }
}
