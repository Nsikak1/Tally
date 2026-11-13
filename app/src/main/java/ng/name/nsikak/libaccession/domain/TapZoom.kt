package ng.name.nsikak.libaccession.domain

import kotlin.math.abs

/** [zoom] is the ratio to set; ([x], [y]) is where the tapped spot ends up on screen after zooming. */
data class ZoomPlan(val zoom: Float, val x: Float, val y: Float)

/**
 * Double-tap zoom. Camera zoom is always centred, so a spot far from the centre would slide off screen
 * if we zoomed all the way. The zoom is therefore limited so the tapped spot stays inside the view
 * (with [margin] to spare). A second double-tap, when already zoomed in, goes back to the minimum.
 */
object TapZoom {
    fun plan(
        current: Float,
        min: Float,
        max: Float,
        wanted: Float,
        tapX: Float,
        tapY: Float,
        viewW: Float,
        viewH: Float,
        margin: Float = 0.12f,
    ): ZoomPlan {
        if (viewW <= 0f || viewH <= 0f || current <= 0f) return ZoomPlan(current, tapX, tapY)

        val target = wanted.coerceIn(min, max)
        val zoomedIn = current > min * 1.05f

        val newZoom = if (zoomedIn && current >= target * 0.9f) {
            min
        } else {
            val dx = abs(tapX - viewW / 2f)
            val dy = abs(tapY - viewH / 2f)
            val limitX = if (dx < 1f) Float.MAX_VALUE else (1f - margin) * (viewW / 2f) / dx
            val limitY = if (dy < 1f) Float.MAX_VALUE else (1f - margin) * (viewH / 2f) / dy
            val capped = minOf(target, current * minOf(limitX, limitY), max)
            if (capped < current * 1.1f) current else capped
        }

        val f = newZoom / current
        return ZoomPlan(
            zoom = newZoom,
            x = viewW / 2f + (tapX - viewW / 2f) * f,
            y = viewH / 2f + (tapY - viewH / 2f) * f,
        )
    }
}
