package io.legado.app.service.help

import android.content.Context
import android.content.Intent
import android.util.Log
import io.legado.app.App
import io.legado.app.R
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.Debug
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.CheckSourceService
import io.legado.app.utils.HtmlFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.coroutines.CoroutineContext

class DebugSource(val source: BookSource){

    @Synchronized
    fun log(
        sourceUrl: String?,
        msg: String? = "",
        print: Boolean = true,
        isHtml: Boolean = false,
        showTime: Boolean = true,
        state: Int = 1
    ) {
        if (callback == null || !print) return
        var printMsg = msg ?: ""
        if (isHtml) {
            printMsg = HtmlFormatter.format(msg)
        }
        if (showTime) {
            printMsg =
                "${DEBUG_TIME_FORMAT.format(Date(System.currentTimeMillis() - startTime))} $printMsg"
        }
    }

    companion object {
        var keyword = "都市"
        fun observe(callback: (Int, String, BookSource)-> Unit){
            this.callback = callback
        }
        private var startTime: Long = System.currentTimeMillis()
        private val DEBUG_TIME_FORMAT = SimpleDateFormat("[mm:ss.SSS]", Locale.getDefault())
        private var callback: ((Int, String, BookSource)-> Unit)? = null

        fun start(context: Context, sources: List<BookSource>) {
            if (sources.isEmpty()) {
                return
            }
            val selectedIds: ArrayList<String> = arrayListOf()
            sources.map {
                selectedIds.add(it.bookSourceUrl)
            }
            Intent(context, CheckSourceService::class.java).let {
                it.action = IntentAction.start
                it.putExtra("selectIds", selectedIds)
                context.startService(it)
            }
        }

        fun stop(context: Context) {
            Intent(context, CheckSourceService::class.java).let {
                it.action = IntentAction.stop
                context.startService(it)
            }
        }
    }

    fun check(
        scope: CoroutineScope,
        context: CoroutineContext,
        onNext: (sourceUrl: String) -> Unit
    ): Coroutine<*> {
        val webBook = WebBook(source)
        val variableBook = SearchBook(origin = source.bookSourceUrl)
        //Debug.simpleDebug(source, keyword)
        log(source.bookSourceUrl, "校验搜索")
        Log.d("h11128", source.bookSourceUrl.toString())
        return webBook.searchBook(scope, keyword, context = context, page = 1).timeout(60000L)
            .timeout(60000L)
            .onError(Dispatchers.IO) {
                source.addGroup("失效")
                appDb.bookSourceDao.update(source)
                log(source.bookSourceUrl, "校验失败")
            }.onSuccess(Dispatchers.IO) {
                source.removeGroup("失效")
                appDb.bookSourceDao.update(source)
                log(source.bookSourceUrl, "校验成功")
            }.onFinally(Dispatchers.IO) {
                onNext(source.bookSourceUrl)
            }
    }
}