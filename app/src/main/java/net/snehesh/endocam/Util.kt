package net.snehesh.endocam

/** Pure Kotlin helpers with no Android imports so they run in JVM unit tests. */

/** "BB AA 05 00 00" or "0xBB,0xAA" -> bytes. Null when the text is not valid hex bytes. */
fun parseHex(text: String): ByteArray? = runCatching {
    text.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        .map { it.removePrefix("0x").removePrefix("0X").toInt(16).also { v -> require(v in 0..255) }.toByte() }
        .toByteArray()
}.getOrNull()?.takeIf { it.isNotEmpty() }

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

/**
 * Recursive temporal filter: px = (1 - weight) * px + weight * prev, and prev takes the result.
 * ARGB_8888 ints, alpha forced opaque. Returns the array to use as prev for the next frame.
 */
fun blendPrevious(px: IntArray, prev: IntArray?, weight: Float): IntArray {
    if (prev == null || prev.size != px.size) return px.copyOf()
    val a = (weight * 256).toInt()
    val b = 256 - a
    for (i in px.indices) {
        val c = px[i]
        val o = prev[i]
        val r = (((c shr 16) and 0xFF) * b + ((o shr 16) and 0xFF) * a) shr 8
        val g = (((c shr 8) and 0xFF) * b + ((o shr 8) and 0xFF) * a) shr 8
        val bl = ((c and 0xFF) * b + (o and 0xFF) * a) shr 8
        val v = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        px[i] = v
        prev[i] = v
    }
    return prev
}

/**
 * Unsharp mask with a 3x3 box blur: out = p + amount * (p - mean3x3). Edges are left as they are.
 * [scratch] must be at least px.size and is used as the read copy.
 */
// ponytail: CPU loop, about 10-20 ms per 640x480 frame; move to an AGSL RuntimeShader if it lags.
fun unsharp(px: IntArray, w: Int, h: Int, amount: Float, scratch: IntArray) {
    System.arraycopy(px, 0, scratch, 0, px.size)
    val k = (amount * 256).toInt()
    for (y in 1 until h - 1) {
        var i = y * w + 1
        for (x in 1 until w - 1) {
            var r = 0
            var g = 0
            var b = 0
            var j = i - w - 1
            repeat(3) {
                val p0 = scratch[j]
                val p1 = scratch[j + 1]
                val p2 = scratch[j + 2]
                r += ((p0 shr 16) and 0xFF) + ((p1 shr 16) and 0xFF) + ((p2 shr 16) and 0xFF)
                g += ((p0 shr 8) and 0xFF) + ((p1 shr 8) and 0xFF) + ((p2 shr 8) and 0xFF)
                b += (p0 and 0xFF) + (p1 and 0xFF) + (p2 and 0xFF)
                j += w
            }
            val p = scratch[i]
            val pr = (p shr 16) and 0xFF
            val pg = (p shr 8) and 0xFF
            val pb = p and 0xFF
            // 2304 = 9 taps * 256 fixed point
            val nr = clamp255(pr + (k * (9 * pr - r)) / 2304)
            val ng = clamp255(pg + (k * (9 * pg - g)) / 2304)
            val nb = clamp255(pb + (k * (9 * pb - b)) / 2304)
            px[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            i++
        }
    }
}

private fun clamp255(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
