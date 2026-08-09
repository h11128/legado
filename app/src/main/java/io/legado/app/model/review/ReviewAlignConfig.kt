package io.legado.app.model.review

/**
 * RFC-004 thresholds. Chapter align uses a discrete accept set matching
 * [io.legado.app.model.checkalgo.ChangeChapterVerify.AlignResult.quality] scores.
 */
object ReviewAlignConfig {

    /** Exact-in-window, exact-out-of-window, chapter-num-in-window. */
    val CHAPTER_ALIGN_ACCEPT = setOf(1.0, 0.95, 0.7)

    const val PARA_MAP_MIN = 0.55
    const val MAP_COVERAGE_MIN = 0.5
    const val PARA_MAP_MAX = 400
    const val AUTO_DISCOVERY_CAP = 20

    fun acceptsChapterAlign(score: Double?): Boolean =
        score != null && score in CHAPTER_ALIGN_ACCEPT
}
