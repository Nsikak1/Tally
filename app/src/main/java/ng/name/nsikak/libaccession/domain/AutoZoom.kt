package ng.name.nsikak.libaccession.domain

/**
 * Our own auto-zoom, independent of ML Kit's zoom suggestions: when the biggest barcode in view
 * is small, zoom in until it fills a comfortable part of the screen. Never zooms OUT by itself.
 */
class AutoZoom(
    private val comfortableFraction: Float = 0.22f,
    private val targetFraction: Float = 0.38f,
    private val cooldownMs: Long = 700L,
    private val maxAutoRatio: Float = 4f,
) {
    private var lastApplyMs = 0L

    /** Returns the zoom ratio to apply now, or null to leave the zoom alone. */
    fun suggest(
        nowMs: Long,
        current: Float,
        min: Float,
        max: Float,
        boxWidth: Float,
        viewWidth: Float,
    ): Float? {
        if (viewWidth <= 0f || boxWidth <= 0f) return null
        if (nowMs - lastApplyMs < cooldownMs) return null

        val fraction = boxWidth / viewWidth
        if (fraction >= comfortableFraction) return null

        val upper = maxOf(min, minOf(max, maxAutoRatio))
        val wanted = (current * targetFraction / fraction).coerceIn(min, upper)
        if (wanted < current * 1.15f) return null

        lastApplyMs = nowMs
        // Go most of the way, so a slightly wrong estimate doesn't push the code out of frame.
        return current + (wanted - current) * 0.7f
    }
}
