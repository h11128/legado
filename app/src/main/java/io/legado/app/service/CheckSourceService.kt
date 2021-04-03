package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jeremyliao.liveeventbus.core.Observable
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.AppConfig
import io.legado.app.help.IntentHelp
import io.legado.app.help.coroutine.CompositeCoroutine
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.help.CheckSource
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.book.source.manage.CheckSourceState
import io.legado.app.utils.eventObservable
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.min

class CheckSourceService : BaseService() {
    private var threadCount = AppConfig.threadCount
    private var searchCoroutine = Executors.newFixedThreadPool(threadCount).asCoroutineDispatcher()
    private var tasks = CompositeCoroutine()
    private val allIds = ArrayList<String>()
    private val checkedIds = ArrayList<String>()
    private var processIndex = 0
    private var notificationMsg = ""
    lateinit var statusObservable: Observable<Pair<String, String>>
    private var stateMap: ConcurrentHashMap<String, CheckSourceState> = ConcurrentHashMap()
    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdReadAloud)
                .setSmallIcon(R.drawable.ic_network_check)
                .setOngoing(true)
                .setContentTitle(getString(R.string.check_book_source))
                .setContentIntent(IntentHelp.activityPendingIntent<BookSourceActivity>(this,
                                                                                       "activity"))
                .addAction(R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                IntentHelp.servicePendingIntent<CheckSourceService>(this, IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onCreate() {
        super.onCreate()
        notificationMsg = getString(R.string.start)
        upNotification()
        statusObservable = eventObservable(EventBus.CHECK_SOURCE_PROGRESS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> intent.getStringArrayListExtra("selectIds")?.let {
                check(it)
            }
            else -> stopSelf()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        tasks.clear()
        searchCoroutine.close()
        postEvent(EventBus.CHECK_SOURCE_DONE, 0)
    }

    private fun check(ids: List<String>) {
        if (allIds.isNotEmpty()) {
            toastOnUi("已有书源在校验,等完成后再试")
            return
        }
        tasks.clear()
        allIds.clear()
        checkedIds.clear()
        allIds.addAll(ids)
        processIndex = 0
        threadCount = min(allIds.size, threadCount)
        notificationMsg = getString(R.string.progress_show, "", 0, allIds.size)
        upNotification()
        for (i in 0 until threadCount) {
            check()
        }
    }

    /**
     * 检测
     */
    private fun check() {
        val index = processIndex
        synchronized(this) {
            processIndex++
        }
        execute {
            if (index < allIds.size) {
                val sourceUrl = allIds[index]
                appDb.bookSourceDao.getBookSource(sourceUrl)?.let { source ->
                    check(source)
                } ?: onNext(sourceUrl, "")
            }
        }
    }

    fun check(source: BookSource) {
        val job = Coroutine.async(this, searchCoroutine) {
            var currentState = CheckSourceState.CheckContent
            stateMap[source.bookSourceUrl] = currentState
            statusObservable.post(Pair(source.bookSourceUrl, currentState.toString()))
            val webBook = WebBook(source)
            var books = webBook.searchBookAwait(this, CheckSource.keyword)
            if (books.isEmpty()) {
                val exs = source.getExploreKinds()
                if (exs.isEmpty()) {
                    throw Exception("搜索内容为空并且没有发现")
                }
                var url: String? = null
                for (ex in exs) {
                    url = ex.url
                    if (!url.isNullOrBlank()) {
                        break
                    }
                }
                currentState = CheckSourceState.CheckExplore
                stateMap[source.bookSourceUrl] = currentState
                statusObservable.post(Pair(source.bookSourceUrl, currentState.toString()))

                books = webBook.exploreBookAwait(this, url!!)
            }
            if (books.isEmpty()) {

                throw Exception("发现内容为空")
            }
            currentState = CheckSourceState.CheckInfo
            stateMap[source.bookSourceUrl] = currentState
            statusObservable.post(Pair(source.bookSourceUrl, currentState.toString()))

            val book = webBook.getBookInfoAwait(this, books.first().toBook())
            currentState = CheckSourceState.CheckChapterList
            stateMap[source.bookSourceUrl] = currentState
            statusObservable.post(Pair(source.bookSourceUrl, currentState.toString()))

            val toc = webBook.getChapterListAwait(this, book)
            currentState = CheckSourceState.CheckContent
            stateMap[source.bookSourceUrl] = currentState
            statusObservable.post(Pair(source.bookSourceUrl, currentState.toString()))

            val content = webBook.getContentAwait(this, book, toc.first())
            if (content.isBlank()) {
                currentState = CheckSourceState.CheckContent
                stateMap[source.bookSourceUrl] = currentState
                statusObservable.post(Pair(source.bookSourceUrl, currentState.toString()))
                throw Exception("正文内容为空")
            }
        }.timeout(180000L)
            .onError {
                source.addGroup("失效")
                source.bookSourceComment = "error:${it.localizedMessage}\n${source.bookSourceComment}"
                val currentState = CheckSourceState.values()[stateMap[source.bookSourceUrl]!!.ordinal + 5]
                stateMap[source.bookSourceUrl] = currentState
                statusObservable.post(Pair(source.bookSourceUrl, currentState.toString()))
                appDb.bookSourceDao.update(source)
            }.onSuccess {
                source.removeGroup("失效")
                val currentState = CheckSourceState.values()[stateMap[source.bookSourceUrl]!!.ordinal + 10]
                stateMap[source.bookSourceUrl] = currentState
                statusObservable.post(Pair(source.bookSourceUrl, currentState.toString()))
                appDb.bookSourceDao.update(source)
            }.onFinally {

                onNext(source.bookSourceUrl, source.bookSourceName)
            }
        statusObservable.post(Pair(source.bookSourceUrl, job.getTime().toString()))

    }

    private fun onNext(sourceUrl: String, sourceName: String) {
        synchronized(this) {
            check()
            checkedIds.add(sourceUrl)
            notificationMsg =
                getString(R.string.progress_show, sourceName, checkedIds.size, allIds.size)
            upNotification()
            if (processIndex >= allIds.size + threadCount - 1) {
                stopSelf()
            }
        }
    }

    /**
     * 更新通知
     */
    private fun upNotification() {
        notificationBuilder.setContentText(notificationMsg)
        notificationBuilder.setProgress(allIds.size, checkedIds.size, false)
        postEvent(EventBus.CHECK_SOURCE, notificationMsg)
        startForeground(112202, notificationBuilder.build())
    }

}