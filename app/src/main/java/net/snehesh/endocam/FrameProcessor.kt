package net.snehesh.endocam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint

data class Enhance(
    val enabled: Boolean = true,
    val brightness: Float = 0f,        // -0.5..0.5
    val contrast: Float = 1.15f,       // 0.5..2
    val saturation: Float = 1.1f,      // 0..2
    val sharpen: Float = 0.4f,         // 0..1
    val denoise: Boolean = true,
    val denoiseStrength: Float = 0.4f, // 0..0.8, weight of the previous frame
    val applyToFiles: Boolean = true,  // also for saved photos and videos, else display only
    val pinchZoom: Boolean = true,     // two-finger zoom, 1x to 4x, digital
    val tapLevel: Boolean = true,      // tap sets the point that is pulled to mid brightness
    val mirror: Boolean = true,        // flip left-right, like a mirror
    val flipVertical: Boolean = false, // flip top-bottom
)

/** Mirror and/or vertical flip. Returns [src] untouched when both are off. */
fun flip(src: Bitmap, mirror: Boolean, vertical: Boolean): Bitmap {
    if (!mirror && !vertical) return src
    val m = Matrix().apply { preScale(if (mirror) -1f else 1f, if (vertical) -1f else 1f) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
}

/** Digital zoom: crop 1/zoom of the frame around (cx, cy) in 0..1 and scale back to full size. */
fun zoomCrop(src: Bitmap, zoom: Float, cx: Float, cy: Float): Bitmap {
    if (zoom <= 1.01f) return src
    val w = src.width
    val h = src.height
    val cw = (w / zoom).toInt().coerceAtLeast(16)
    val ch = (h / zoom).toInt().coerceAtLeast(16)
    val x = (cx * w - cw / 2f).toInt().coerceIn(0, w - cw)
    val y = (cy * h - ch / 2f).toInt().coerceIn(0, h - ch)
    val crop = Bitmap.createBitmap(src, x, y, cw, ch)
    return Bitmap.createScaledBitmap(crop, w, h, true)
}

/** Colour, temporal denoise, then unsharp mask. Call from one thread only. */
class FrameProcessor {
    @Volatile var settings = Enhance()

    /** Level point in frame coordinates 0..1, or null. The area around it is pulled to mid brightness. */
    @Volatile var levelPoint: Pair<Float, Float>? = null

    private var prev: IntArray? = null
    private var px = IntArray(0)
    private var scratch = IntArray(0)
    private var gain = 1f
    private val window = IntArray(4 * LEVEL_RADIUS * LEVEL_RADIUS)

    fun process(src: Bitmap): Bitmap {
        val s = settings
        val g = levelGain(src)
        if (!s.enabled && g == 1f) {
            prev = null
            return src
        }
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cm = if (s.enabled) colorMatrix(s) else ColorMatrix()
        if (g != 1f) cm.postConcat(ColorMatrix().apply { setScale(g, g, g, 1f) })
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        if (!s.enabled || (s.sharpen <= 0f && !s.denoise)) {
            prev = null
            return out
        }
        if (px.size != w * h) {
            px = IntArray(w * h)
            scratch = IntArray(w * h)
            prev = null
        }
        out.getPixels(px, 0, w, 0, 0, w, h)
        if (s.denoise) prev = blendPrevious(px, prev, s.denoiseStrength) else prev = null
        if (s.sharpen > 0f) unsharp(px, w, h, s.sharpen, scratch)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Mean brightness in a window around the level point, turned into a smoothed gain. 1 when off. */
    private fun levelGain(src: Bitmap): Float {
        val p = levelPoint ?: run {
            gain = 1f
            return 1f
        }
        val r = LEVEL_RADIUS
        val x0 = (p.first * src.width).toInt().coerceIn(r, src.width - r - 1)
        val y0 = (p.second * src.height).toInt().coerceIn(r, src.height - r - 1)
        src.getPixels(window, 0, 2 * r, x0 - r, y0 - r, 2 * r, 2 * r)
        var sum = 0L
        for (c in window) sum += (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        val mean = sum / window.size.toFloat() / 255f
        val target = (0.5f / mean.coerceAtLeast(0.02f)).coerceIn(0.4f, 3f)
        gain = gain * 0.8f + target * 0.2f
        return gain
    }

    private fun colorMatrix(s: Enhance): ColorMatrix {
        val c = s.contrast
        val t = 128f * (1f - c) + s.brightness * 255f
        val m = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        m.postConcat(ColorMatrix().apply { setSaturation(s.saturation) })
        return m
    }

    private companion object {
        const val LEVEL_RADIUS = 24
    }
}
