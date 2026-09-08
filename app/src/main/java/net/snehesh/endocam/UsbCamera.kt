package net.snehesh.endocam

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Port of UsbSupercamera from supercamera_core.cpp on android.hardware.usb.
 * Everything runs on one reader thread so a failed init never blocks the UI.
 */
class UsbCamera(
    private val onFrame: (ByteArray) -> Unit,
    private val onButton: (ButtonDetector.Event) -> Unit,
    private val onError: (String) -> Unit,
) {
    val latestJpeg = AtomicReference<ByteArray?>()

    @Volatile private var running = false
    private var worker: Thread? = null

    /** [initCmd] goes to EP 0x02, [startCmd] to EP 0x01, in that order, before reading. */
    fun start(um: UsbManager, dev: UsbDevice, initCmd: ByteArray = DEFAULT_INIT, startCmd: ByteArray = DEFAULT_START) {
        stop()
        running = true
        worker = thread(name = "usb-reader") { loop(um, dev, initCmd, startCmd) }
    }

    fun stop() {
        running = false
        // The reader returns from bulkTransfer within TIMEOUT_MS; wait for it so the next start
        // never opens the device while this connection is still claimed.
        worker?.join(3000)
        worker = null
    }

    private fun loop(um: UsbManager, dev: UsbDevice, initCmd: ByteArray, startCmd: ByteArray) {
        val conn = um.openDevice(dev) ?: return fail("openDevice failed")
        var if0: UsbInterface? = null
        var if1: UsbInterface? = null
        var idleIf: UsbInterface? = null
        try {
            val ifs = (0 until dev.interfaceCount).map(dev::getInterface)
            val i0 = ifs.firstOrNull { it.id == 0 } ?: return fail("interface 0 missing")
            // Interface 1 alt 0 has no endpoints: the camera idles there after a plug-in.
            // Alt 1 owns EP 0x81 IN and 0x01 OUT and is the streaming state.
            val idle = ifs.firstOrNull { it.id == 1 && it.alternateSetting == 0 }
            idleIf = idle
            val i1 = ifs.firstOrNull { it.id == 1 && it.alternateSetting == 1 }
                ?: return fail("interface 1 alt 1 missing")
            check(conn.claimInterface(i0, true)) { "claimInterface 0 failed" }
            if0 = i0
            check(conn.claimInterface(i1, true)) { "claimInterface 1 failed" }
            if1 = i1
            val ep2Out = endpoint(i0, 0x02) ?: return fail("EP 0x02 missing")
            val ep1In = endpoint(i1, 0x81) ?: return fail("EP 0x81 missing")
            val ep1Out = endpoint(i1, 0x01) ?: return fail("EP 0x01 missing")

            // Same sequence as UsbSupercamera in supercamera_core.cpp, preceded by a drop to alt 0.
            // After the app closes and reopens the camera is still streaming in alt 1; alt 0 stops it,
            // so the start commands land on an idle device like they do after a fresh plug-in.
            fun arm() {
                idle?.let { conn.setInterface(it) }
                check(conn.setInterface(i1)) { "setInterface 1 alt 1 failed" }
                val haltIn = conn.controlTransfer(0x02, 0x01, 0x00, 0x81, null, 0, TIMEOUT_MS)
                check(haltIn >= 0) { "clear halt EP 0x81 failed: $haltIn" }
                conn.controlTransfer(0x02, 0x01, 0x00, 0x01, null, 0, TIMEOUT_MS)
                write(conn, ep2Out, initCmd)
                write(conn, ep1Out, startCmd)
                Log.i(TAG, "armed")
            }
            arm()

            var frames = 0
            var lastFrameAt = SystemClock.elapsedRealtime()
            val parser = PacketParser { jpeg ->
                frames++
                lastFrameAt = SystemClock.elapsedRealtime()
                latestJpeg.set(jpeg)
                onFrame(jpeg)
            }
            val detector = ButtonDetector()
            val buf = ByteArray(1024)
            var attempts = 1
            var nextLog = SystemClock.elapsedRealtime() + 5000
            while (running) {
                // Returns bytes read, or -1 for both timeout and error. Detach arrives by broadcast.
                val n = conn.bulkTransfer(ep1In, buf, buf.size, TIMEOUT_MS)
                val now = SystemClock.elapsedRealtime()
                if (n > 0) {
                    parser.feed(buf, n)
                    detector.update(parser.buttonDown, now)?.let(onButton)
                }
                if (now - lastFrameAt > NO_FRAME_MS) {
                    // Watchdog: the device answered the init but never sent a whole frame. Re-arm.
                    if (attempts >= MAX_ATTEMPTS) return fail("No video from the camera. Unplug it and plug it back in.")
                    attempts++
                    Log.w(TAG, "no frames for ${NO_FRAME_MS} ms, re-arming (attempt $attempts)")
                    onError("Restarting camera ($attempts of $MAX_ATTEMPTS)")
                    arm()
                    lastFrameAt = now
                }
                if (now >= nextLog) {
                    Log.i(TAG, "frames=$frames dropped=${parser.dropped} (last 5 s: ${frames / 5} fps)")
                    frames = 0
                    nextLog = now + 5000
                }
            }
            parser.flush()
        } catch (e: Exception) {
            fail(e.message ?: e.toString())
        } finally {
            // Leave the camera idle so the next opener, this app or the desktop tool, finds it like a fresh plug-in.
            idleIf?.let { runCatching { conn.setInterface(it) } }
            if1?.let { conn.releaseInterface(it) }
            if0?.let { conn.releaseInterface(it) }
            conn.close()
        }
    }

    private fun write(conn: UsbDeviceConnection, ep: UsbEndpoint, data: ByteArray) {
        val n = conn.bulkTransfer(ep, data, data.size, TIMEOUT_MS)
        check(n == data.size) { "write to EP 0x%02x returned %d".format(ep.address, n) }
        Log.i(TAG, "wrote $n bytes to EP 0x%02x".format(ep.address))
    }

    private fun endpoint(intf: UsbInterface, address: Int): UsbEndpoint? =
        (0 until intf.endpointCount).map(intf::getEndpoint).firstOrNull { it.address == address }

    private fun fail(msg: String) {
        Log.e(TAG, msg)
        onError(msg)
    }

    companion object {
        const val TAG = "EndoCam"
        private const val TIMEOUT_MS = 1000
        private const val NO_FRAME_MS = 3000L
        private const val MAX_ATTEMPTS = 3
        private val IDS = setOf(0x2ce3 to 0x3828, 0x0329 to 0x2022)
        val DEFAULT_INIT = bytes(0xFF, 0x55, 0xFF, 0x55, 0xEE, 0x10)
        val DEFAULT_START = bytes(0xBB, 0xAA, 0x05, 0x00, 0x00)

        fun find(um: UsbManager): UsbDevice? =
            um.deviceList.values.firstOrNull { (it.vendorId to it.productId) in IDS }

        private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    }
}
