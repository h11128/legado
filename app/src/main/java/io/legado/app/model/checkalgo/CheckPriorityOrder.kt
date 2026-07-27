package io.legado.app.model.checkalgo

/**
 * Order URLs by historical respondTime (fast first). Unknowns sort last.
 */
object CheckPriorityOrder {

    fun orderByPriority(
        urls: List<String>,
        respondTimeByUrl: Map<String, Long>,
    ): List<String> {
        if (urls.size <= 1) return urls
        return urls.mapIndexed { index, url ->
            Triple(url, respondTimeByUrl[url] ?: Long.MAX_VALUE / 2, index)
        }.sortedWith(
            compareBy<Triple<String, Long, Int>> { it.second }.thenBy { it.third }
        ).map { it.first }
    }
}
