package net.snehesh.endocam

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

private val RecordRed = Color(0xFFD93025)

class MainActivity : ComponentActivity() {
    private enum class Mode(val label: String) { PHOTO("Photo"), VIDEO("Video") }

    private var frame by mutableStateOf<ImageBitmap?>(null)
    private var status by mutableStateOf("No endoscope connected")
    private var mode by mutableStateOf(Mode.PHOTO)
    private var recording by mutableStateOf(false)
    /** Mode to return to when a recording started by the hardware button stops. */
    private var modeAfterRecording: Mode? = null
    private var showGallery by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var preview by mutableStateOf<File?>(null)
    private var mediaVersion by mutableIntStateOf(0)
    /** Digital zoom 1..4 and its centre in frame coordinates 0..1. */
    private var zoom by mutableFloatStateOf(1f)
    private var zoomCenter by mutableStateOf(Offset(0.5f, 0.5f))
    /** Brightness level point in frame coordinates 0..1, null when not set. */
    private var levelPoint by mutableStateOf<Offset?>(null)
    private var recStartMs = 0L

    private val camera = UsbCamera(::onFrame, ::onButton, ::onError)
    private val prefs by lazy { Prefs(this) }
    private var enhance by mutableStateOf(Enhance())
    private var usb by mutableStateOf(UsbCommands())
    private var usbAtOpen = UsbCommands()

    // Decode and enhance off the USB thread so reads never stall. A frame is dropped when the last one is still busy.
    private val processor = FrameProcessor()
    private val frameThread = HandlerThread("frames").apply { start() }
    private val frameHandler = Handler(frameThread.looper) { msg ->
        handleJpeg(msg.obj as ByteArray)
        true
    }
    /** Latest frame after enhancement, or the raw decode when enhancement is off. */
    private val latestBitmap = AtomicReference<Bitmap?>()

    @Volatile private var recorder: Mp4Recorder? = null
    private var recFile: File? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERM -> {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect()
                    else status = "USB permission denied"
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> disconnect("Disconnected")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enhance = prefs.loadEnhance()
        usb = prefs.loadUsb()
        processor.settings = enhance
        setContent {
            // Camera apps stay dark. Dynamic color follows the user's wallpaper on Android 12+.
            val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(this) else darkColorScheme()
            MaterialTheme(colorScheme = scheme) { Ui() }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(ACTION_PERM)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        connect()
    }

    override fun onStop() {
        disconnect("No endoscope connected")
        unregisterReceiver(receiver)
        super.onStop()
    }

    override fun onDestroy() {
        frameThread.quitSafely()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        connect()
    }

    private fun connect() {
        val um = getSystemService(UsbManager::class.java)
        val dev = intent?.let { IntentCompat.getParcelableExtra(it, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) }
            ?: UsbCamera.find(um)
            ?: run { status = "No endoscope connected"; return }
        if (um.hasPermission(dev)) {
            val init = if (usb.custom) parseHex(usb.init) else UsbCamera.DEFAULT_INIT
            val start = if (usb.custom) parseHex(usb.start) else UsbCamera.DEFAULT_START
            if (init == null || start == null) {
                status = "Invalid USB command in Settings"
                return
            }
            status = "Starting camera"
            camera.start(um, dev, init, start)
        } else {
            status = "Waiting for USB permission"
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_PERM).setPackage(packageName), PendingIntent.FLAG_MUTABLE,
            )
            um.requestPermission(dev, pi)
        }
    }

    private fun disconnect(msg: String) {
        stopRecording()
        camera.stop()
        intent?.removeExtra(UsbManager.EXTRA_DEVICE)
        frame = null
        resetView()
        status = msg
    }

    // USB reader thread.
    private fun onFrame(jpeg: ByteArray) {
        if (frameHandler.hasMessages(0)) return
        frameHandler.obtainMessage(0, jpeg).sendToTarget()
    }

    // Frame thread.
    private fun handleJpeg(jpeg: ByteArray) {
        val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        val z = zoom
        val c = zoomCenter
        val e = enhance
        val processed = processor.process(raw)
        val shown = flip(zoomCrop(processed, z, c.x, c.y), e.mirror, e.flipVertical)
        frame = shown.asImageBitmap()
        val forFiles = if (e.applyToFiles) shown else flip(zoomCrop(raw, z, c.x, c.y), e.mirror, e.flipVertical)
        latestBitmap.set(forFiles)
        recorder?.feed(forFiles)
    }

    // USB reader thread. Single press = photo, also while recording (the recording goes on).
    // Double press = start recording, or stop it. Only that or the on-screen shutter stops a recording.
    private fun onButton(ev: ButtonDetector.Event) {
        runOnUiThread {
            when {
                ev == ButtonDetector.Event.PHOTO -> takePhoto()
                recording -> stopRecording()
                else -> {
                    modeAfterRecording = mode
                    toggleRecording()
                }
            }
        }
    }

    // USB reader thread.
    private fun onError(msg: String) {
        frame = null
        status = msg
    }

    private fun resetView() {
        zoom = 1f
        zoomCenter = Offset(0.5f, 0.5f)
        levelPoint = null
        processor.levelPoint = null
    }

    /** Scale from frame pixels to view pixels for the letterboxed feed. */
    private fun fitScale(view: IntSize): Float {
        val f = frame ?: return 1f
        return minOf(view.width / f.width.toFloat(), view.height / f.height.toFloat())
    }

    /** View pixel -> 0..1 within the shown (zoomed) image, or null outside it. */
    private fun viewToShown(p: Offset, view: IntSize): Offset? {
        val f = frame ?: return null
        val s = fitScale(view)
        val nx = (p.x - (view.width - f.width * s) / 2f) / s / f.width
        val ny = (p.y - (view.height - f.height * s) / 2f) / s / f.height
        return if (nx in 0f..1f && ny in 0f..1f) Offset(nx, ny) else null
    }

    /** Tap sets or clears the brightness level point. Coordinates are stored for the full frame. */
    private fun onFeedTap(tap: Offset, view: IntSize) {
        if (!enhance.tapLevel) return
        val n = viewToShown(tap, view)?.let { unflip(it) } ?: return
        val p = Offset(zoomCenter.x + (n.x - 0.5f) / zoom, zoomCenter.y + (n.y - 0.5f) / zoom)
        val cur = levelPoint
        levelPoint = if (cur != null && (cur - p).getDistance() < 0.08f) null else p
        processor.levelPoint = levelPoint?.let { it.x to it.y }
    }

    /** Shown image coordinates -> frame orientation, undoing mirror and vertical flip. Its own inverse. */
    private fun unflip(n: Offset) = Offset(
        if (enhance.mirror) 1f - n.x else n.x,
        if (enhance.flipVertical) 1f - n.y else n.y,
    )

    /** Two-finger pinch changes zoom, drag moves the crop. */
    private fun onPinch(pan: Offset, factor: Float, view: IntSize) {
        if (!enhance.pinchZoom) return
        val f = frame ?: return
        val newZoom = (zoom * factor).coerceIn(1f, 4f)
        val s = fitScale(view) * newZoom
        val half = 0.5f / newZoom
        zoom = newZoom
        // Content follows the finger, so a mirrored axis moves the crop the other way.
        val sx = if (enhance.mirror) 1f else -1f
        val sy = if (enhance.flipVertical) 1f else -1f
        zoomCenter = Offset(
            (zoomCenter.x + sx * pan.x / s / f.width).coerceIn(half, 1f - half),
            (zoomCenter.y + sy * pan.y / s / f.height).coerceIn(half, 1f - half),
        )
    }

    private fun takePhoto() {
        val jpeg = camera.latestJpeg.get() ?: return
        // Zoomed or enhanced frames must be re-encoded; otherwise the raw JPEG is written as is.
        val processed = enhance.applyToFiles && (enhance.enabled || levelPoint != null)
        val enhanced = if (processed || zoom > 1f || enhance.mirror || enhance.flipVertical) latestBitmap.get() else null
        val file = File(mediaDir(Environment.DIRECTORY_PICTURES), "IMG_${stamp()}.jpg")
        thread {
            // Raw frames are written byte for byte. Enhanced frames must be re-encoded.
            if (enhanced == null) file.writeBytes(jpeg)
            else FileOutputStream(file).use { enhanced.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            mediaVersion++
            runOnUiThread { Toast.makeText(this, "Saved ${file.name}", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun applyEnhance(e: Enhance) {
        enhance = e
        processor.settings = e
        prefs.save(e)
    }

    private fun openSettings() {
        usbAtOpen = usb
        showSettings = true
    }

    private fun closeSettings() {
        showSettings = false
        prefs.save(usb)
        if (usb != usbAtOpen) {
            camera.stop()
            connect()
        }
    }

    private fun toggleRecording() {
        if (recorder != null) {
            stopRecording()
            return
        }
        val file = File(mediaDir(Environment.DIRECTORY_MOVIES), "VID_${stamp()}.mp4")
        try {
            recorder = Mp4Recorder(file.path)
            recFile = file
            recStartMs = SystemClock.elapsedRealtime()
            recording = true
            mode = Mode.VIDEO
        } catch (e: Exception) {
            Log.e(UsbCamera.TAG, "recorder start failed", e)
            file.delete()
            status = "Recording failed: ${e.message}"
        }
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        recording = false
        modeAfterRecording?.let { mode = it }
        modeAfterRecording = null
        val hasData = r.stop()
        val file = recFile ?: return
        recFile = null
        if (hasData) Toast.makeText(this, "Saved ${file.name}", Toast.LENGTH_SHORT).show() else file.delete()
        mediaVersion++
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    @Composable
    private fun Ui() {
        val p = preview
        when {
            p != null -> {
                BackHandler { preview = null }
                PreviewScreen(p, onShare = { share(p) }, onBack = { preview = null })
            }
            showSettings -> {
                BackHandler { closeSettings() }
                SettingsScreen(enhance, ::applyEnhance, usb, { usb = it }, ::closeSettings)
            }
            showGallery -> {
                BackHandler { showGallery = false }
                // ponytail: directory listing on the main thread; fine for a few hundred files.
                val files = remember(mediaVersion) { mediaFiles() }
                GalleryScreen(files, onOpen = { preview = it }, onBack = { showGallery = false })
            }
            else -> CameraUi()
        }
    }

    @Composable
    private fun CameraUi() {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val f = frame
            if (f != null) {
                Image(
                    f, "Live camera feed",
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { tap -> onFeedTap(tap, size) } }
                        .pointerInput(Unit) { detectTransformGestures { _, pan, gestureZoom, _ -> onPinch(pan, gestureZoom, size) } }
                        .drawWithContent {
                            drawContent()
                            val lp = levelPoint ?: return@drawWithContent
                            // Level point back to view pixels through the current zoom.
                            val n = unflip(Offset(0.5f + (lp.x - zoomCenter.x) * zoom, 0.5f + (lp.y - zoomCenter.y) * zoom))
                            val nx = n.x
                            val ny = n.y
                            if (nx !in 0f..1f || ny !in 0f..1f) return@drawWithContent
                            val s = minOf(size.width / f.width, size.height / f.height)
                            val cx = (size.width - f.width * s) / 2f + nx * f.width * s
                            val cy = (size.height - f.height * s) / 2f + ny * f.height * s
                            val side = 56.dp.toPx()
                            drawRect(Color.White, Offset(cx - side / 2, cy - side / 2), Size(side, side), style = Stroke(3.dp.toPx()))
                        },
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    status,
                    Modifier.align(Alignment.Center).padding(32.dp),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            // Controls stay clear of the status and navigation bars; the feed stays edge-to-edge.
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (recording) RecBadge(Modifier.align(Alignment.TopCenter).padding(top = 16.dp))
                if (zoom > 1.01f) {
                    Surface(
                        Modifier.align(Alignment.TopStart).padding(12.dp).clickable(role = Role.Button) { zoom = 1f; zoomCenter = Offset(0.5f, 0.5f) },
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.5f),
                    ) {
                        Text(
                            "%.1f× · tap to reset".format(zoom),
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                IconButton(onClick = ::openSettings, Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Icon(Icons.Filled.Settings, "Settings", tint = Color.White)
                }
                // Bottom in portrait, right edge in landscape.
                Column(
                    Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    ModeSwitch()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        GalleryButton()
                        Shutter()
                        Spacer(Modifier.size(48.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun RecBadge(modifier: Modifier) {
        var elapsed by remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) {
            while (true) {
                elapsed = (SystemClock.elapsedRealtime() - recStartMs) / 1000
                delay(1000)
            }
        }
        Surface(modifier, shape = CircleShape, color = Color.Black.copy(alpha = 0.5f)) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(10.dp).background(RecordRed, CircleShape))
                Text(
                    "%02d:%02d".format(elapsed / 60, elapsed % 60),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ModeSwitch() {
        SingleChoiceSegmentedButtonRow {
            Mode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = m == mode,
                    onClick = { mode = m },
                    shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size),
                    enabled = !recording,
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = Color.White,
                        activeContentColor = Color.Black,
                        inactiveContainerColor = Color.Black.copy(alpha = 0.4f),
                        inactiveContentColor = Color.White,
                    ),
                ) { Text(m.label) }
            }
        }
    }

    @Composable
    private fun GalleryButton() {
        val latest = remember(mediaVersion) { mediaFiles().firstOrNull() }
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .border(2.dp, Color.White, CircleShape)
                .background(Color.DarkGray)
                .semantics { contentDescription = "Open gallery" }
                .clickable(role = Role.Button) { showGallery = true },
        ) {
            latest?.let { Thumb(it, Modifier.fillMaxSize()) }
        }
    }

    @Composable
    private fun Shutter() {
        val video = mode == Mode.VIDEO
        val label = when {
            recording -> "Stop recording"
            video -> "Start recording"
            else -> "Take photo"
        }
        // White ring, dark gap, filled centre. Red centre in video mode, square while recording.
        Box(
            Modifier
                .size(80.dp)
                .background(Color.White, CircleShape)
                .padding(4.dp)
                .background(Color.Black, CircleShape)
                .padding(if (recording) 14.dp else 3.dp)
                .background(if (video) RecordRed else Color.White, if (recording) RoundedCornerShape(6.dp) else CircleShape)
                .semantics { contentDescription = label }
                .clickable(role = Role.Button) {
                    modeAfterRecording = null
                    if (video) toggleRecording() else takePhoto()
                },
        )
    }

    private companion object {
        const val ACTION_PERM = "net.snehesh.endocam.USB_PERMISSION"
    }
}
