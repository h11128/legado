package io.legado.app.model.review

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookReviewBinding
import io.legado.app.help.book.BookAuthorIdentity
import io.legado.app.help.config.AppConfig

/**
 * Persist / migrate review provider bindings (RFC-004 §6.8 / §12).
 */
object ReviewOverlayBindings {

    fun list(contentBookUrl: String): List<BookReviewBinding> =
        appDb.bookReviewBindingDao.listByContentBookUrl(contentBookUrl)

    /** Enabled + sorted + merge-pref capped set used by the read path. */
    fun listEnabled(contentBookUrl: String): List<BookReviewBinding> =
        ReviewOverlayMerge.selectBindings(
            list(contentBookUrl),
            mergeEnabled = AppConfig.reviewOverlayMergeEnabled,
            mergeMax = AppConfig.reviewOverlayMergeMax,
        )

    /** Compat: first row for this content book (any role), or null. */
    fun get(contentBookUrl: String): BookReviewBinding? =
        list(contentBookUrl).firstOrNull()

    fun clear(contentBookUrl: String) {
        appDb.bookReviewBindingDao.deleteByContentBookUrl(contentBookUrl)
    }

    fun remove(contentBookUrl: String, providerSourceUrl: String) {
        appDb.bookReviewBindingDao.deleteByContentAndProvider(contentBookUrl, providerSourceUrl)
    }

    fun upsert(binding: BookReviewBinding) {
        binding.updatedAt = System.currentTimeMillis()
        val existing = appDb.bookReviewBindingDao.getByContentAndProvider(
            binding.contentBookUrl,
            binding.providerSourceUrl,
        )
        if (existing != null) {
            binding.id = existing.id
            if (binding.sortOrder == 0 && existing.sortOrder != 0) {
                binding.sortOrder = existing.sortOrder
            }
            appDb.bookReviewBindingDao.update(binding)
        } else {
            binding.id = 0
            if (binding.sortOrder == 0) {
                val maxOrder = list(binding.contentBookUrl).maxOfOrNull { it.sortOrder } ?: -1
                binding.sortOrder = maxOrder + 1
            }
            appDb.bookReviewBindingDao.insert(binding)
        }
    }

    fun bindManual(
        contentBook: Book,
        providerSourceUrl: String,
        providerBookUrl: String,
        providerName: String,
        providerAuthor: String,
    ) {
        bind(
            contentBook = contentBook,
            providerSourceUrl = providerSourceUrl,
            providerBookUrl = providerBookUrl,
            providerName = providerName,
            providerAuthor = providerAuthor,
            bindMode = BookReviewBinding.MODE_MANUAL,
        )
    }

    fun bindAuto(
        contentBook: Book,
        providerSourceUrl: String,
        providerBookUrl: String,
        providerName: String,
        providerAuthor: String,
    ) {
        bind(
            contentBook = contentBook,
            providerSourceUrl = providerSourceUrl,
            providerBookUrl = providerBookUrl,
            providerName = providerName,
            providerAuthor = providerAuthor,
            bindMode = BookReviewBinding.MODE_AUTO,
        )
    }

    fun setEnabled(contentBookUrl: String, providerSourceUrl: String, enabled: Boolean) {
        val row = appDb.bookReviewBindingDao.getByContentAndProvider(contentBookUrl, providerSourceUrl)
            ?: return
        row.enabled = enabled
        row.updatedAt = System.currentTimeMillis()
        appDb.bookReviewBindingDao.update(row)
    }

    fun setParagraphPrimary(contentBookUrl: String, providerSourceUrl: String) {
        val rows = list(contentBookUrl)
        for (row in rows) {
            val want = if (row.providerSourceUrl == providerSourceUrl) {
                BookReviewBinding.ROLE_PARAGRAPH_PRIMARY
            } else {
                BookReviewBinding.ROLE_CHAPTER
            }
            if (row.role != want) {
                row.role = want
                row.updatedAt = System.currentTimeMillis()
                appDb.bookReviewBindingDao.update(row)
            }
        }
    }

    private fun bind(
        contentBook: Book,
        providerSourceUrl: String,
        providerBookUrl: String,
        providerName: String,
        providerAuthor: String,
        bindMode: String,
    ) {
        val existing = appDb.bookReviewBindingDao.getByContentAndProvider(
            contentBook.bookUrl,
            providerSourceUrl,
        )
        upsert(
            BookReviewBinding(
                contentBookUrl = contentBook.bookUrl,
                contentName = contentBook.name,
                contentAuthor = contentBook.author,
                contentOrigin = contentBook.origin,
                providerSourceUrl = providerSourceUrl,
                providerBookUrl = providerBookUrl,
                providerName = providerName,
                providerAuthor = providerAuthor,
                bindMode = bindMode,
                enabled = true,
                sortOrder = existing?.sortOrder ?: 0,
                role = existing?.role ?: BookReviewBinding.ROLE_CHAPTER,
            )
        )
    }

    /**
     * Migrate all bindings after content 换源 (RFC-004 §6.8 / §12).
     * Exact previousBookUrl only.
     *
     * @return true if any row was rewritten
     */
    fun migrateOnChangeSource(previousBookUrl: String?, newBook: Book): Boolean {
        if (previousBookUrl.isNullOrBlank()) return false
        if (previousBookUrl == newBook.bookUrl) {
            val rows = list(newBook.bookUrl)
            if (rows.isEmpty()) return false
            for (row in rows) {
                row.contentName = newBook.name
                row.contentAuthor = newBook.author
                row.contentOrigin = newBook.origin
                row.updatedAt = System.currentTimeMillis()
                appDb.bookReviewBindingDao.update(row)
            }
            return true
        }
        val byUrl = list(previousBookUrl)
        if (byUrl.isEmpty()) return false
        var any = false
        for (row in byUrl) {
            if (rewriteToNewBook(row, newBook)) any = true
        }
        return any
    }

    fun providerStillMatches(contentBook: Book, binding: BookReviewBinding): Boolean {
        return BookAuthorIdentity.sameBook(
            contentBook.name,
            contentBook.author,
            binding.providerName,
            binding.providerAuthor,
            listOf(contentBook.author, binding.providerAuthor, binding.contentAuthor),
        )
    }

    private fun rewriteToNewBook(row: BookReviewBinding, newBook: Book): Boolean {
        if (!providerStillMatches(newBook, row)) {
            appDb.bookReviewBindingDao.deleteById(row.id)
            return false
        }
        val clash = appDb.bookReviewBindingDao.getByContentAndProvider(
            newBook.bookUrl,
            row.providerSourceUrl,
        )
        if (clash != null && clash.id != row.id) {
            appDb.bookReviewBindingDao.deleteById(row.id)
            return false
        }
        row.contentBookUrl = newBook.bookUrl
        row.contentName = newBook.name
        row.contentAuthor = newBook.author
        row.contentOrigin = newBook.origin
        row.updatedAt = System.currentTimeMillis()
        appDb.bookReviewBindingDao.update(row)
        return true
    }
}
