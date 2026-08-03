package io.legado.app.model.checkalgo

/**
 * Order URLs by historical respondTime (fast first).
 * Uses [RespondTimeRank] so failure encodings never sort ahead of successes.
 * Missing map keys sort last (same as before).
 */
object CheckPriorityOrder {

    fun orderByPriority(
        urls: List<String>,
        respondTimeByUrl: Map<String, Long>,
    ): List<String> {
        if (urls.size <= 1) return urls
        return urls.mapIndexed { index, url ->
            Entry(url, respondTimeByUrl[url], index)
        }.sortedWith(
            compareBy<Entry> { entry ->
                entry.respondTime?.let { RespondTimeRank.classify(it) }
                    ?: (RespondTimeRank.FAILURE + 1)
            }.thenBy { it.respondTime ?: Long.MAX_VALUE / 2 }
                .thenBy { it.index }
        ).map { it.url }
    }

    private data class Entry(
        val url: String,
        val respondTime: Long?,
        val index: Int,
    )
}
