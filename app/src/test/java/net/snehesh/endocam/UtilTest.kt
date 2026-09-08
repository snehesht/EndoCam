package net.snehesh.endocam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UtilTest {
    @Test
    fun parsesHexInSeveralSpellings() {
        assertArrayEquals(byteArrayOf(0xBB.toByte(), 0xAA.toByte(), 5, 0, 0), parseHex("BB AA 05 00 00"))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x55), parseHex(" 0xff, 0x55 "))
        assertNull(parseHex(""))
        assertNull(parseHex("GG"))
        assertNull(parseHex("100"))
        assertEquals("BB AA 05 00 00", parseHex("bb aa 5 0 0")!!.toHex())
    }

    @Test
    fun blendMixesWithPreviousAndUpdatesIt() {
        val px = intArrayOf(0xFF000000.toInt() or 0x646464)          // 100,100,100
        val prev = intArrayOf(0xFF000000.toInt() or 0xC8C8C8)        // 200,200,200
        val next = blendPrevious(px, prev, 0.5f)
        assertEquals(0xFF000000.toInt() or 0x969696, px[0])           // 150,150,150
        assertEquals(px[0], next[0])
    }

    @Test
    fun blendWithoutPreviousReturnsCopy() {
        val px = intArrayOf(0xFF102030.toInt())
        val prev = blendPrevious(px, null, 0.5f)
        assertArrayEquals(px, prev)
        assert(prev !== px)
    }

    @Test
    fun unsharpLeavesFlatAreasAndLiftsEdges() {
        val w = 5
        val h = 5
        val flat = IntArray(w * h) { 0xFF808080.toInt() }
        val scratch = IntArray(w * h)
        val copy = flat.copyOf()
        unsharp(flat, w, h, 1f, scratch)
        assertArrayEquals(copy, flat)

        // Bright centre pixel on a dark field gets brighter, its neighbours darker.
        val img = IntArray(w * h) { 0xFF404040.toInt() }
        img[12] = 0xFFC0C0C0.toInt()
        unsharp(img, w, h, 1f, scratch)
        assertEquals(0xFF, img[12] and 0xFF)              // clamped up
        assert((img[11] and 0xFF) < 0x40)                 // neighbour pulled down
    }
}
