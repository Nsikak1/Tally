package ng.name.nsikak.libaccession.data.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Turns a photo of handwriting into versions the text recogniser can read more reliably. */
object ImagePreprocess {
    data class Variant(val name: String, val bitmap: Bitmap)

    private const val MIN_HEIGHT_PX = 160
    private const val MAX_LONG_SIDE_PX = 2200

    /** Cuts [rect] (image pixels) out of [src] with a little padding; null if it is too small. */
    fun crop(src: Bitmap, rect: Rect, padFraction: Float = 0.06f): Bitmap? {
        val padX = (rect.width() * padFraction).roundToInt()
        val padY = (rect.height() * padFraction).roundToInt()
        val left = (rect.left - padX).coerceIn(0, src.width - 1)
        val top = (rect.top - padY).coerceIn(0, src.height - 1)
        val right = (rect.right + padX).coerceIn(left + 1, src.width)
        val bottom = (rect.bottom + padY).coerceIn(top + 1, src.height)
        if (right - left < 8 || bottom - top < 8) return null
        return Bitmap.createBitmap(src, left, top, right - left, bottom - top)
    }

    /**
     * Three readings of the same crop, each upscaled and given a white margin:
     *  - contrast: grayscale with the histogram stretched (1st..99th percentile)
     *  - binary:   black/white by Otsu's threshold
     *  - plain:    just upscaled colour
     * Light-on-dark writing is inverted first.
     */
    fun variants(crop: Bitmap): List<Variant> {
        val up = upscale(crop)
        val w = up.width
        val h = up.height
        val n = w * h

        val pixels = IntArray(n)
        up.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(n) { i ->
            val p = pixels[i]
            (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)).toInt()
        }

        val stretched = stretch(gray)
        if (stretched.average() < 110.0) {
            for (i in stretched.indices) stretched[i] = 255 - stretched[i]
        }

        val contrast = fromGray(stretched, w, h)
        val threshold = otsu(stretched)
        val binary = fromGray(IntArray(n) { if (stretched[it] > threshold) 255 else 0 }, w, h)

        return listOf(
            Variant("contrast", withMargin(contrast)),
            Variant("binary", withMargin(binary)),
            Variant("plain", withMargin(up)),
        )
    }

    /** Grayscale with the contrast stretched (and inverted if the writing is light on dark). Same size as [src]. */
    fun contrastOnly(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h) { i ->
            val p = pixels[i]
            (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)).toInt()
        }
        val stretched = stretch(gray)
        if (stretched.average() < 110.0) {
            for (i in stretched.indices) stretched[i] = 255 - stretched[i]
        }
        return fromGray(stretched, w, h)
    }

    private fun upscale(src: Bitmap): Bitmap {
        var factor = max(1f, MIN_HEIGHT_PX.toFloat() / src.height)
        factor = min(factor, MAX_LONG_SIDE_PX.toFloat() / max(src.width, src.height))
        if (factor <= 1.02f) return src
        return Bitmap.createScaledBitmap(
            src,
            (src.width * factor).roundToInt(),
            (src.height * factor).roundToInt(),
            true,
        )
    }

    /** Linear contrast stretch between the 1st and 99th percentile. */
    private fun stretch(gray: IntArray): IntArray {
        val hist = IntArray(256)
        for (v in gray) hist[v.coerceIn(0, 255)]++
        val n = gray.size
        var acc = 0
        var lo = 0
        var hi = 255
        var loSet = false
        for (v in 0..255) {
            acc += hist[v]
            if (!loSet && acc >= n * 0.01) { lo = v; loSet = true }
            if (acc >= n * 0.99) { hi = v; break }
        }
        if (hi - lo < 16) { lo = gray.min(); hi = gray.max() }
        if (hi <= lo) hi = lo + 1
        val range = (hi - lo).toFloat()
        return IntArray(n) { (((gray[it] - lo) / range) * 255f).roundToInt().coerceIn(0, 255) }
    }

    /** Otsu's method: the threshold that best separates ink from paper. */
    private fun otsu(gray: IntArray): Int {
        val hist = IntArray(256)
        for (v in gray) hist[v]++
        val total = gray.size.toDouble()
        var sumAll = 0.0
        for (t in 0..255) sumAll += t * hist[t].toDouble()
        var sumB = 0.0
        var wB = 0.0
        var best = 0.0
        var threshold = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = t }
        }
        return threshold
    }

    private fun fromGray(gray: IntArray, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h) { Color.rgb(gray[it], gray[it], gray[it]) }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Text detectors read edge-to-edge text badly; a white border helps. */
    private fun withMargin(src: Bitmap): Bitmap {
        val pad = max(16, src.height / 6)
        val out = Bitmap.createBitmap(src.width + 2 * pad, src.height + 2 * pad, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, pad.toFloat(), pad.toFloat(), null)
        return out
    }
}
