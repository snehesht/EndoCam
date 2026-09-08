package net.snehesh.endocam

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/**
 * JPEG frames in (as Bitmap), H.264 MP4 out.
 * Surface-input encoder: each bitmap is drawn on the encoder surface, the encoder stamps the
 * presentation time at post time, so the camera's variable frame rate is kept as-is.
 */
class Mp4Recorder(path: String, width: Int = 640, height: Int = 480) {
    private val codec: MediaCodec
    private val surface: Surface
    private val muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private val dst = Rect(0, 0, width, height)
    private var track = -1
    private var muxerStarted = false
    private val thread = HandlerThread("mp4-encoder").apply { start() }
    private val handler: Handler

    @Volatile private var running = true

    init {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
        muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        handler = Handler(thread.looper) { msg ->
            encode(msg.obj as Bitmap)
            true
        }
    }

    /** Called from the USB reader thread. */
    fun feed(bmp: Bitmap) {
        // ponytail: drops a frame when the encoder is still busy with the previous one.
        if (!running || handler.hasMessages(0)) return
        handler.sendMessage(handler.obtainMessage(0, bmp))
    }

    /** Finalises the file. Returns true if any sample was written. */
    fun stop(): Boolean {
        running = false
        handler.post {
            runCatching {
                codec.signalEndOfInputStream()
                drain(eos = true)
            }
            runCatching { codec.stop() }
            codec.release()
            surface.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
        thread.quitSafely()
        thread.join()
        return muxerStarted
    }

    private fun encode(bmp: Bitmap) {
        if (!running) return
        val canvas = surface.lockCanvas(null)
        canvas.drawBitmap(bmp, null, dst, null)
        surface.unlockCanvasAndPost(canvas)
        drain(eos = false)
    }

    private fun drain(eos: Boolean) {
        var tries = 0
        while (true) {
            val i = codec.dequeueOutputBuffer(info, if (eos) 10_000L else 0L)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos || ++tries > 50) return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                i >= 0 -> {
                    val buf = codec.getOutputBuffer(i)!!
                    // Codec config (SPS/PPS) is already in outputFormat, the muxer has it.
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(i, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }
}
