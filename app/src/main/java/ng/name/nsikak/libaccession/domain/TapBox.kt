package ng.name.nsikak.libaccession.domain

import kotlin.math.max
import kotlin.math.min

data class BoxF(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Text mode double-tap: where to put the reading box on the photo.
 * The preview shows the photo scaled to FILL the view and centre-cropped, so a view point maps to a
 * photo point by undoing that scale and crop. The box is a wide, short rectangle (a handwritten label)
 * centred on the tap and kept inside the photo.
 */
object TapBox {
    fun imageBox(
        tapX: Float,
        tapY: Float,
        viewW: Float,
        viewH: Float,
        imgW: Float,
        imgH: Float,
        widthFraction: Float = 0.5f,
        heightFraction: Float = 0.14f,
    ): BoxF {
        val s = max(viewW / imgW, viewH / imgH)
        val offsetX = (imgW * s - viewW) / 2f
        val offsetY = (imgH * s - viewH) / 2f
        val cx = (tapX + offsetX) / s
        val cy = (tapY + offsetY) / s

        val boxW = min(viewW * widthFraction / s, imgW)
        val boxH = min(min(viewW, viewH) * heightFraction / s, imgH)

        val left = (cx - boxW / 2f).coerceIn(0f, imgW - boxW)
        val top = (cy - boxH / 2f).coerceIn(0f, imgH - boxH)
        return BoxF(left, top, left + boxW, top + boxH)
    }
}
