package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index

/**
 * Persisted chapter-level change-source probe (TTL ~1 day, same as searchBooks).
 * Does not store full TOC — only status/score for a chapterKey.
 */
@Entity(
    tableName = "change_source_chapter_probe",
    primaryKeys = ["name", "author", "origin", "chapterKey"],
    indices = [
        Index(value = ["time"]),
        Index(value = ["name", "author", "chapterKey"]),
    ],
)
data class ChangeSourceChapterProbe(
    val name: String,
    val author: String,
    val origin: String,
    val chapterKey: String,
    /** [STATUS_OK], [STATUS_TOC_OK], [STATUS_NO_CHAPTER], [STATUS_CONTENT_FAIL] */
    val status: String,
    val score: Double = 0.0,
    val time: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_TOC_OK = "toc_ok"
        const val STATUS_NO_CHAPTER = "no_chapter"
        const val STATUS_CONTENT_FAIL = "content_fail"
    }
}
