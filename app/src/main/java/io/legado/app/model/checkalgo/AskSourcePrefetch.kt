package io.legado.app.model.checkalgo

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.toBookSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Chunked prefetch for ask-path Flow producers (§6.7 companion).
 *
 * Splits [BookSourcePart] lists into [PREFETCH_CHUNK_SIZE] chunks so each
 * [toBookSource] call stays a single Room query while streaming.
 */
object AskSourcePrefetch {

    const val PREFETCH_CHUNK_SIZE = 150

    /**
     * Parse URL host; empty string on failure / relative / non-http labels.
     * Callers skip host-bucket acquire when empty (alias bookSourceUrl etc.).
     */
    fun hostOf(url: String): String {
        return url.toHttpUrlOrNull()?.host.orEmpty()
    }

    /** Split parts into chunks of [PREFETCH_CHUNK_SIZE] preserving order. */
    fun chunkParts(parts: List<BookSourcePart>): List<List<BookSourcePart>> {
        return parts.chunked(PREFETCH_CHUNK_SIZE)
    }

    /**
     * Resolve parts via existing [toBookSource] (one Room query per chunk when
     * chunk size ≤ [io.legado.app.data.entities.BOOK_SOURCE_QUERY_CHUNK_SIZE])
     * and emit sources in ask order.
     */
    fun emitSources(parts: List<BookSourcePart>): Flow<BookSource> = flow {
        for (chunk in chunkParts(parts)) {
            for (source in chunk.toBookSource()) {
                emit(source)
            }
        }
    }
}
