package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookSourcePart

/**
 * Map [BookType] bitmask → [BookSourceType] enum (§6.3 RFC-001).
 * Priority matches BookInfoEditActivity: video > image > audio > webFile > text.
 */
object BookSourceTypeMapper {

    fun bookTypeToSourceType(bookType: Int): Int = when {
        (bookType and BookType.video) != 0 -> BookSourceType.video
        (bookType and BookType.image) != 0 -> BookSourceType.image
        (bookType and BookType.audio) != 0 -> BookSourceType.audio
        (bookType and BookType.webFile) != 0 -> BookSourceType.file
        else -> BookSourceType.default
    }

    /**
     * Keep sources whose [BookSourcePart.bookSourceType] matches the book's type.
     * Logs how many were filtered out.
     * If the filter empties the pool (mis-tagged community sources / odd BookType bits),
     * fall back to the unfiltered list so 换源 still runs.
     */
    fun filterSameType(
        sources: List<BookSourcePart>,
        bookType: Int,
        logTag: String = "换源",
    ): List<BookSourcePart> {
        if (sources.isEmpty()) return sources
        val target = bookTypeToSourceType(bookType)
        val filtered = sources.filter { it.bookSourceType == target }
        val dropped = sources.size - filtered.size
        if (filtered.isEmpty()) {
            runCatching {
                AppLog.put(
                    "${logTag}类型过滤: 目标bookSourceType=$target, 过滤后为空，回退到未过滤列表(${sources.size})"
                )
            }
            return sources
        }
        if (dropped > 0) {
            runCatching {
                AppLog.put("${logTag}类型过滤: 目标bookSourceType=$target, 过滤掉${dropped}个书源")
            }
        }
        return filtered
    }
}
