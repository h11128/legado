package io.legado.app.ui.book.manga

import android.app.Application
import android.content.Intent
import android.net.Uri
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.book.removeType
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ReadManga
import io.legado.app.model.checkalgo.AutoChangeSource
import io.legado.app.model.checkalgo.formatAutoChangeProgressCurrent
import io.legado.app.model.checkalgo.formatAutoChangeProgressMetrics
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.ACache
import io.legado.app.utils.FileDoc
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import splitties.init.appCtx

class ReadMangaViewModel(application: Application) : BaseViewModel(application) {

    private var changeSourceCoroutine: Coroutine<*>? = null

    /**
     * 初始化
     */
    fun initData(intent: Intent, success: (() -> Unit)? = null) {
        execute {
            ReadManga.inBookshelf = intent.getBooleanExtra("inBookshelf", true)
            ReadManga.chapterChanged = intent.getBooleanExtra("chapterChanged", false)
            val bookUrl = intent.getStringExtra("bookUrl")
            val book = when {
                bookUrl.isNullOrEmpty() -> appDb.bookDao.lastReadBook
                else -> appDb.bookDao.getBook(bookUrl)
            } ?: ReadManga.book
            when {
                book != null -> initManga(book)
                else -> {
                    ReadManga.loadFail(context.getString(R.string.no_book), false)
                    AppLog.put("未找到漫画书籍\nbookUrl:$bookUrl")
                }
            }
        }.onSuccess {
            success?.invoke()
        }.onError {
            val msg = "初始化数据失败\n${it.localizedMessage}"
            AppLog.put(msg, it)
        }.onFinally {
            ReadManga.saveRead()
        }
    }

    private suspend fun initManga(book: Book) {
        val isSameBook = ReadManga.book?.bookUrl == book.bookUrl
        if (isSameBook) {
            ReadManga.upData(book)
        } else {
            ReadManga.autoChangeAttemptedFor = null
            ReadManga.resetData(book)
        }
        if (!book.isLocal && book.tocUrl.isEmpty() && !loadBookInfo(book)) {
            tryAutoChangeSource(book, AutoChangeSource.Trigger.INFO_FAIL)
            return
        }

        if (book.isLocal && !checkLocalBookFileExist(book)) {
            return
        }

        if ((ReadManga.chapterSize == 0 || book.isLocalModified()) && !loadChapterListAwait(book)) {
            tryAutoChangeSource(book, AutoChangeSource.Trigger.TOC_FAIL)
            return
        }

        //开始加载内容
        if (!isSameBook) {
            ReadManga.loadContent()
        } else {
            ReadManga.loadOrUpContent()
        }

        if (ReadManga.chapterChanged) {
            // 有章节跳转不同步阅读进度
            ReadManga.chapterChanged = false
        } else if (ReadManga.inBookshelf) {
            if (AppConfig.syncBookProgressPlus) {
                ReadManga.syncProgress(
                    { progress -> ReadManga.mCallback?.sureNewProgress(progress) })
            } else {
                syncBookProgress(book)
            }
        }

        //自动换源
        if (!book.isLocal && ReadManga.bookSource == null) {
            tryAutoChangeSource(book, AutoChangeSource.Trigger.MISSING_SOURCE)
            return
        }
    }

    private suspend fun loadChapterListAwait(book: Book): Boolean {
        val bookSource = ReadManga.bookSource ?: return true
        val oldBook = book.copy()
        WebBook.getChapterListAwait(bookSource, book, true).onSuccess { cList ->
            if (oldBook.bookUrl == book.bookUrl) {
                appDb.bookDao.update(book)
            } else {
                appDb.bookDao.replace(oldBook, book)
                BookHelp.updateCacheFolder(oldBook, book)
            }
            appDb.bookChapterDao.delByBook(oldBook.bookUrl)
            appDb.bookChapterDao.insert(*cList.toTypedArray())
            ReadManga.onChapterListUpdated(book)
            return true
        }.onFailure {
            currentCoroutineContext().ensureActive()
            //加载章节出错
            ReadManga.mCallback?.loadFail(appCtx.getString(R.string.error_load_toc))
            return false
        }
        return true
    }

    /**
     * 加载详情页
     */
    private suspend fun loadBookInfo(book: Book): Boolean {
        val source = ReadManga.bookSource ?: return true
        try {
            WebBook.getBookInfoAwait(source, book, canReName = false)
            return true
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            ReadManga.mCallback?.loadFail("详情页出错: ${e.localizedMessage}")
            return false
        }
    }

    /**
     * 自动换源（缺源 / 详情失败 / 目录失败）
     */
    private fun tryAutoChangeSource(book: Book, trigger: AutoChangeSource.Trigger) {
        if (book.isLocal || !AppConfig.autoChangeSource) return
        if (ReadManga.autoChangeAttemptedFor == book.bookUrl) return
        ReadManga.autoChangeAttemptedFor = book.bookUrl
        AutoChangeSource.logTrigger(trigger, book.origin, book.bookUrl)
        val excludeOrigin = book.origin.takeIf { it.isNotBlank() }
        execute {
            val (newBook, toc, _) = AutoChangeSource.findFirst(
                name = book.name,
                author = book.author,
                excludeOrigin = excludeOrigin,
                onStart = {
                    ReadManga.showLoading()
                },
                onProgress = { progress ->
                    val metrics = context.formatAutoChangeProgressMetrics(progress)
                    val current = context.formatAutoChangeProgressCurrent(progress)
                    ReadManga.upLoadingMessage("$metrics\n$current")
                },
            )
            ReadManga.autoChangeAttemptedFor = newBook.bookUrl
            changeTo(newBook, toc)
        }.onError {
            AppLog.put("自动换源失败\n${it.localizedMessage}", it)
            context.toastOnUi("自动换源失败\n${it.localizedMessage}")
            ReadManga.loadFail(
                context.getString(R.string.source_auto_changing) + "\n${it.localizedMessage}"
            )
        }.onCancel {
            ReadManga.loadFail(
                context.getString(R.string.source_auto_changing)
            )
        }
    }

    /**
     * 同步进度
     */
    fun syncBookProgress(
        book: Book,
        alertSync: ((progress: BookProgress) -> Unit)? = null
    ) {
        if (!AppConfig.syncBookProgress) return
        execute {
            AppWebDav.getBookProgress(book)
        }.onError {
            AppLog.put("拉取阅读进度失败《${book.name}》\n${it.localizedMessage}", it)
        }.onSuccess { progress ->
            progress ?: return@onSuccess
            if (progress.durChapterIndex == book.durChapterIndex && progress.durChapterPos == book.durChapterPos) {
                return@onSuccess
            }
            if (progress.durChapterIndex < book.durChapterIndex ||
                (progress.durChapterIndex == book.durChapterIndex
                        && progress.durChapterPos < book.durChapterPos)
            ) {
                alertSync?.invoke(progress)
            } else if (progress.durChapterIndex < book.simulatedTotalChapterNum()) {
                ReadManga.setProgress(progress)
                AppLog.put("自动同步阅读进度成功《${book.name}》 ${progress.durChapterTitle}")
                context.toastOnUi("已同步最新漫画阅读进度")
            }
        }
    }

    /**
     * 换源
     */
    fun changeTo(book: Book, toc: List<BookChapter>) {
        changeSourceCoroutine?.cancel()
        changeSourceCoroutine = execute {
            //换源中
            ReadManga.book?.migrateTo(book, toc)
            book.removeType(BookType.updateError)
            ReadManga.book?.delete()
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(*toc.toTypedArray())
            ReadManga.resetData(book)
            ReadManga.loadContent()
        }.onError {
            AppLog.put("换源失败\n$it", it, true)
        }.onFinally {
            postEvent(EventBus.SOURCE_CHANGED, book.bookUrl)
        }
    }

    private fun checkLocalBookFileExist(book: Book): Boolean {
        try {
            LocalBook.getBookInputStream(book)
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    fun openChapter(index: Int, durChapterPos: Int = 0) {
        if (index < ReadManga.chapterSize) {
            ReadManga.showLoading()
            ReadManga.durChapterIndex = index
            ReadManga.durChapterPos = durChapterPos
            ReadManga.saveRead()
            ReadManga.loadContent()
        }
    }

    fun removeFromBookshelf(success: (() -> Unit)?) {
        val book = ReadManga.book
        Coroutine.async {
            book?.delete()
        }.onSuccess {
            success?.invoke()
        }
    }

    override fun onCleared() {
        super.onCleared()
        changeSourceCoroutine?.cancel()
    }

    fun refreshContentDur(book: Book) {
        execute {
            appDb.bookChapterDao.getChapter(book.bookUrl, ReadManga.durChapterIndex)
                ?.let { chapter ->
                    BookHelp.delContent(book, chapter)
                    openChapter(ReadManga.durChapterIndex, ReadManga.durChapterPos)
            }
        }
    }

    fun saveImage(src: String?, uri: Uri) {
        src ?: return
        val book = ReadManga.book ?: return
        execute {
            BookHelp.saveImage(ReadManga.bookSource, book, src)
            val image = BookHelp.getImage(book, src)
            if (!image.isFile) throw NoStackTraceException("图片下载失败")
            try {
                FileDoc.fromDir(uri).createFileIfNotExist(image.name).writeFile(image)
            } catch (error: Exception) {
                ACache.get().remove(AppConst.imagePathKey)
                throw error
            }
        }.onError {
            AppLog.put("保存图片出错\n${it.localizedMessage}", it)
            context.toastOnUi("保存图片出错\n${it.localizedMessage}")
        }
    }
}
