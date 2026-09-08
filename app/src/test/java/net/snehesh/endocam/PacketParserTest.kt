package net.snehesh.endocam

import net.snehesh.endocam.ButtonDetector.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketParserTest {
    // JPEG start and end markers, written as ISO-8859-1 strings so payloads stay readable.
    private val soi = "\u00FF\u00D8"
    private val eoi = "\u00FF\u00D9"

    private fun pkt(fid: Int, cam: Int, flags: Int, payload: String, cid: Int = 7, magic: Int = 0xBBAA): ByteArray {
        val len = 7 + payload.length
        val hdr = byteArrayOf(
            magic.toByte(), (magic shr 8).toByte(), cid.toByte(), len.toByte(), (len shr 8).toByte(),
            fid.toByte(), cam.toByte(), flags.toByte(), 0, 0, 0, 0,
        )
        return hdr + payload.toByteArray(Charsets.ISO_8859_1)
    }

    private class Sink {
        val frames = mutableListOf<String>()
        val parser = PacketParser { frames += String(it, Charsets.ISO_8859_1) }
        fun feed(vararg pkts: ByteArray) = pkts.forEach { parser.feed(it, it.size) }
    }

    @Test
    fun assemblesFrameAcrossChunks() {
        val s = Sink()
        s.feed(pkt(1, 0, 0, soi + "AB"), pkt(1, 0, 0, "CD" + eoi), pkt(2, 0, 0, soi + "EF" + eoi))
        assertEquals(listOf(soi + "ABCD" + eoi), s.frames)
        s.parser.flush()
        assertEquals(listOf(soi + "ABCD" + eoi, soi + "EF" + eoi), s.frames)
    }

    @Test
    fun dropsFrameMissingStartOrEnd() {
        val s = Sink()
        s.feed(pkt(1, 0, 0, "AB" + eoi), pkt(2, 0, 0, soi + "CD"), pkt(3, 0, 0, soi + "EF" + eoi))
        s.parser.flush()
        assertEquals(listOf(soi + "EF" + eoi), s.frames)
        assertEquals(2, s.parser.dropped)
    }

    @Test
    fun rejectsBadPackets() {
        val s = Sink()
        s.feed(pkt(1, 0, 0, "AB", magic = 0x1234))
        s.feed(pkt(1, 0, 0, "AB", cid = 5))
        val short = pkt(1, 0, 0, "AB")
        s.parser.feed(short, short.size - 1)
        s.feed(pkt(1, 0, 1, "AB"))   // has_g set on first chunk
        s.feed(pkt(1, 2, 0, "AB"))   // cam_num >= 2
        s.parser.flush()
        assertTrue(s.frames.isEmpty())
    }

    @Test
    fun dropsMismatchedChunkWithoutEndingFrame() {
        val s = Sink()
        s.feed(pkt(1, 0, 0, soi + "AB"), pkt(1, 1, 0, "XX"), pkt(1, 0, 0, "CD" + eoi), pkt(2, 0, 0, soi + "E"))
        assertEquals(listOf(soi + "ABCD" + eoi), s.frames)
    }

    @Test
    fun buttonFlagFollowsPackets() {
        val s = Sink()
        s.feed(pkt(1, 0, 2, "A"))
        assertTrue(s.parser.buttonDown)
        s.feed(pkt(1, 0, 0, "B"))
        assertFalse(s.parser.buttonDown)
    }

    /** Feeds `pressed` packets every 10 ms over [from, to], collects events with their time. */
    private fun drive(d: ButtonDetector, from: Long, to: Long, pressed: Boolean, out: MutableList<Pair<Long, Event>>) {
        var t = from
        while (t <= to) {
            d.update(pressed, t)?.let { out += t to it }
            t += 10
        }
    }

    @Test
    fun singlePressIsPhotoAfterDoubleWindow() {
        val d = ButtonDetector()
        val ev = mutableListOf<Pair<Long, Event>>()
        drive(d, 0, 100, true, ev)
        drive(d, 110, 2000, false, ev)
        // release seen at 190 (100 + 80 ms debounce), photo 400 ms later
        assertEquals(listOf(590L to Event.PHOTO), ev)
    }

    @Test
    fun doublePressTogglesRecording() {
        val d = ButtonDetector()
        val ev = mutableListOf<Pair<Long, Event>>()
        drive(d, 0, 100, true, ev)
        drive(d, 110, 290, false, ev)
        drive(d, 300, 400, true, ev)
        drive(d, 410, 2000, false, ev)
        assertEquals(listOf(490L to Event.RECORD_TOGGLE), ev)
    }

    @Test
    fun holdIsIgnored() {
        val d = ButtonDetector()
        val ev = mutableListOf<Pair<Long, Event>>()
        drive(d, 0, 700, true, ev)
        drive(d, 710, 2000, false, ev)
        assertTrue(ev.isEmpty())
    }

    @Test
    fun oneDroppedPacketDoesNotEndPress() {
        val d = ButtonDetector()
        val ev = mutableListOf<Pair<Long, Event>>()
        drive(d, 0, 50, true, ev)
        assertNull(d.update(false, 60))
        drive(d, 70, 100, true, ev)
        drive(d, 110, 2000, false, ev)
        assertEquals(listOf(590L to Event.PHOTO), ev)
    }
}
