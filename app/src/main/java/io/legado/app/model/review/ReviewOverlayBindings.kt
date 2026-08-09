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
            // Auto path must not revive a user-disabled row; manual bind may re-enable.
            if (!existing.enabled && binding.bindMode == BookReviewBinding.MODE_AUTO) {
                binding.enabled = false
            }
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

    /**
     * Silent multi-bind for auto-discovery.
     * Skips providers that already have a row (enabled or disabled — never re-enables).
     * Inserts until total row count reaches [mergeMax].
     *
     * @return number of newly inserted rows
     */
    fun bindAutoAll(
        contentBook: Book,
        proposals: List<ReviewOverlayAutoBind.Proposal>,
        mergeMax: Int = AppConfig.reviewOverlayMergeMax,
    ): Int {
        if (proposals.isEmpty()) return 0
        val existing = list(contentBook.bookUrl)
        val toBind = selectNewAutoProposals(
            proposals = proposals,
            existingProviderUrls = existing.map { it.providerSourceUrl }.toSet(),
            existingCount = existing.size,
            mergeMax = mergeMax,
        )
        for (p in toBind) {
            bindAuto(
                contentBook = contentBook,
                providerSourceUrl = p.source.bookSourceUrl,
                providerBookUrl = p.providerBookUrl,
                providerName = p.providerName,
                providerAuthor = p.providerAuthor,
            )
        }
        return toBind.size
    }

    /** Pure filter for [bindAutoAll] (unit-testable). */
    internal fun selectNewAutoProposals(
        proposals: List<ReviewOverlayAutoBind.Proposal>,
        existingProviderUrls: Set<String>,
        existingCount: Int,
        mergeMax: Int,
    ): List<ReviewOverlayAutoBind.Proposal> {
        var slots = (mergeMax.coerceAtLeast(1) - existingCount.coerceAtLeast(0)).coerceAtLeast(0)
        if (slots == 0 || proposals.isEmpty()) return emptyList()
        val seen = existingProviderUrls.toHashSet()
        val out = ArrayList<ReviewOverlayAutoBind.Proposal>(slots)
        for (p in proposals) {
            if (slots <= 0) break
            val url = p.source.bookSourceUrl
            if (url in seen) continue
            seen.add(url)
            out.add(p)
            slots--
        }
        return out
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

    /** Swap [sortOrder] with the neighbor above/below (stable list order). */
    fun moveSortOrder(contentBookUrl: String, providerSourceUrl: String, delta: Int): Boolean {
        if (delta == 0) return false
        val rows = list(contentBookUrl).toMutableList()
        val i = rows.indexOfFirst { it.providerSourceUrl == providerSourceUrl }
        if (i < 0) return false
        val j = i + delta
        if (j !in rows.indices) return false
        val a = rows[i]
        val b = rows[j]
        val tmp = a.sortOrder
        a.sortOrder = b.sortOrder
        b.sortOrder = tmp
        // If equal orders, assign contiguous ranks.
        if (a.sortOrder == b.sortOrder) {
            rows[i] = a
            rows[j] = b
            rows.forEachIndexed { idx, row ->
                row.sortOrder = idx
                row.updatedAt = System.currentTimeMillis()
                appDb.bookReviewBindingDao.update(row)
            }
            return true
        }
        a.updatedAt = System.currentTimeMillis()
        b.updatedAt = System.currentTimeMillis()
        appDb.bookReviewBindingDao.update(a)
        appDb.bookReviewBindingDao.update(b)
        return true
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
