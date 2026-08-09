package io.legado.app.model.review

/**
 * RFC-004 click invariant: detail APIs must use provider-native para identity.
 */
data class ProviderParaRef(
    val providerParaIndex: Int,
    val paraData: String,
)

/**
 * Full overlay session context required before opening detail (RFC-004 §6.9).
 */
data class ReviewOverlaySession(
    val providerSourceUrl: String,
    val providerBookUrl: String,
    val providerChapterIndex: Int,
    val providerChapterUrl: String,
    val chapterBucket: ProviderParaRef?,
    /** Local body review id → provider ref (P2). Empty when chapter-bucket only. */
    val paraRefs: Map<Int, ProviderParaRef> = emptyMap(),
)
