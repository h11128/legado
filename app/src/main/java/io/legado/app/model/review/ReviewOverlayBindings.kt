package io.legado.app.model.review

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookReviewBinding
import io.legado.app.help.book.BookAuthorIdentity

/**
 * Persist / migrate review provider bindings (RFC-004 §6.8).
 */
object ReviewOverlayBindings {

    fun get(contentBookUrl: String): BookReviewBinding? =
        appDb.bookReviewBindingDao.getByContentBookUrl(contentBookUrl)

    fun clear(contentBookUrl: String) {
        appDb.bookReviewBindingDao.deleteByContentBookUrl(contentBookUrl)
    }

    fun upsert(binding: BookReviewBinding) {
        binding.updatedAt = System.currentTimeMillis()
        val existing = appDb.bookReviewBindingDao.getByContentBookUrl(binding.contentBookUrl)
        if (existing != null) {
            binding.id = existing.id
            appDb.bookReviewBindingDao.update(binding)
        } else {
            binding.id = 0
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

    private fun bind(
        contentBook: Book,
        providerSourceUrl: String,
        providerBookUrl: String,
        providerName: String,
        providerAuthor: String,
        bindMode: String,
    ) {
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
            )
        )
    }

    /**
     * Migrate binding after content 换源.
     * P1: exact previousBookUrl only (RFC-004 §6.8). Name/author fallback deferred —
     * ambiguous same-name shelves can steal bindings.
     *
     * @return true if a row was rewritten
     */
    fun migrateOnChangeSource(previousBookUrl: String?, newBook: Book): Boolean {
        if (previousBookUrl.isNullOrBlank()) return false
        if (previousBookUrl == newBook.bookUrl) {
            val same = appDb.bookReviewBindingDao.getByContentBookUrl(newBook.bookUrl) ?: return false
            same.contentName = newBook.name
            same.contentAuthor = newBook.author
            same.contentOrigin = newBook.origin
            same.updatedAt = System.currentTimeMillis()
            appDb.bookReviewBindingDao.update(same)
            return true
        }
        val byUrl = appDb.bookReviewBindingDao.getByContentBookUrl(previousBookUrl) ?: return false
        return rewriteToNewBook(byUrl, newBook)
    }

    /**
     * Pure decision helper for tests: whether provider book identity still matches.
     */
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
        val clash = appDb.bookReviewBindingDao.getByContentBookUrl(newBook.bookUrl)
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
