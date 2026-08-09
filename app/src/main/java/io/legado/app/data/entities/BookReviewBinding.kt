package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * RFC-004 §6.8 / §12 — content book ↔ review provider binding (multi-row).
 */
@Entity(
    tableName = "book_review_bindings",
    indices = [
        Index(value = ["contentBookUrl", "providerSourceUrl"], unique = true),
        Index(value = ["contentName", "contentAuthor"]),
        Index(value = ["providerSourceUrl"]),
    ],
)
data class BookReviewBinding(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0,
    var contentBookUrl: String = "",
    var contentName: String = "",
    var contentAuthor: String = "",
    var contentOrigin: String = "",
    var providerSourceUrl: String = "",
    var providerBookUrl: String = "",
    var providerName: String = "",
    var providerAuthor: String = "",
    /** `auto` or `manual` */
    var bindMode: String = MODE_MANUAL,
    var enabled: Boolean = true,
    var sortOrder: Int = 0,
    /** [ROLE_CHAPTER] or [ROLE_PARAGRAPH_PRIMARY] */
    var role: String = ROLE_CHAPTER,
    var updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val MODE_AUTO = "auto"
        const val MODE_MANUAL = "manual"
        const val ROLE_CHAPTER = "chapter"
        const val ROLE_PARAGRAPH_PRIMARY = "paragraph_primary"
    }
}
