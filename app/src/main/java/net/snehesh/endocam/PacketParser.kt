package net.snehesh.endocam

import java.io.ByteArrayOutputStream

/**
 * Port of UPPCameraParser::handle_upp_frame from supercamera_core.cpp.
 * One packet in, JPEG frames out. Pure Kotlin, no Android imports.
 */
class PacketParser(private val onFrame: (ByteArray) -> Unit) {
    private val out = ByteArrayOutputStream()
    private var fid = 0
    private var cam = 0
    private var hasG = 0
    private var other = 0

    /** True while the hardware button is held, updated per valid packet. */
    var buttonDown = false
        private set

    /** Frames thrown away because they did not start with SOI or end with EOI (lost packets). */
    var dropped = 0
        private set

    fun feed(buf: ByteArray, n: Int) {
        if (n < USB_HDR) return
        val magic = u8(buf, 0) or (u8(buf, 1) shl 8)
        if (magic != MAGIC) return
        val cid = u8(buf, 2)
        if (cid != 7 && cid != 11) return
        val len = u8(buf, 3) or (u8(buf, 4) shl 8)
        if (USB_HDR + len > n) return
        if (len < CAM_HDR) return

        val pFid = u8(buf, 5)
        val pCam = u8(buf, 6)
        val flags = u8(buf, 7)
        val pHasG = flags and 1
        val pOther = flags shr 2
        buttonDown = (flags shr 1) and 1 == 1

        if (out.size() > 0 && fid != pFid) emit()
        if (out.size() == 0) {
            fid = pFid
            cam = pCam
            hasG = pHasG
            other = pOther
            if (!(cam < 2 && hasG == 0 && other == 0)) return
        } else if (fid != pFid || cam != pCam || hasG != pHasG || other != pOther) {
            return
        }
        out.write(buf, USB_HDR + CAM_HDR, len - CAM_HDR)
    }

    fun flush() = emit()

    private fun emit() {
        val n = out.size()
        if (n == 0) return
        val jpeg = out.toByteArray()
        out.reset()
        // A frame that lost a packet at either end decodes to a half grey image and flickers on screen.
        val whole = n >= 4 &&
            jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte() &&
            jpeg[n - 2] == 0xFF.toByte() && jpeg[n - 1] == 0xD9.toByte()
        if (whole) onFrame(jpeg) else dropped++
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private companion object {
        const val MAGIC = 0xBBAA
        const val USB_HDR = 5
        const val CAM_HDR = 7
    }
}

/**
 * Turns the per-packet button flag into events.
 * Short press = PHOTO. Two presses within [doubleMs] = RECORD_TOGGLE.
 * A hold of [holdMs] or more is ignored: the camera firmware switches lenses on a hold.
 */
class ButtonDetector(
    private val doubleMs: Long = 400,
    private val holdMs: Long = 600,
    private val releaseMs: Long = 80,
) {
    enum class Event { PHOTO, RECORD_TOGGLE }

    private var down = false
    private var downAt = 0L
    private var lastPressedAt = 0L
    private var lastReleaseAt = Long.MIN_VALUE / 2
    private var pendingPhotoAt = -1L
    private var doubleCandidate = false

    // ponytail: ticks only on packet arrival; the stream never idles so timers are not needed.
    fun update(pressed: Boolean, now: Long): Event? {
        if (pressed) {
            if (!down) {
                down = true
                downAt = now
                doubleCandidate = now - lastReleaseAt <= doubleMs
                if (doubleCandidate) pendingPhotoAt = -1
            }
            lastPressedAt = now
            return null
        }
        if (down && now - lastPressedAt > releaseMs) {
            down = false
            if (lastPressedAt - downAt >= holdMs) return null
            if (doubleCandidate) {
                doubleCandidate = false
                lastReleaseAt = Long.MIN_VALUE / 2
                return Event.RECORD_TOGGLE
            }
            lastReleaseAt = now
            pendingPhotoAt = now
            return null
        }
        if (pendingPhotoAt >= 0 && now - pendingPhotoAt >= doubleMs) {
            pendingPhotoAt = -1
            return Event.PHOTO
        }
        return null
    }
}
