package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * RFC-004 §6.8 — content book ↔ review provider binding.
 */
@Entity(
    tableName = "book_review_bindings",
    indices = [
        Index(value = ["contentBookUrl"], unique = true),
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
    var updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val MODE_AUTO = "auto"
        const val MODE_MANUAL = "manual"
    }
}
