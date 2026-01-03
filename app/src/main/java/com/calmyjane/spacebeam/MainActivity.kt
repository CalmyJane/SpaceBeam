package com.calmyjane.spacebeam


import android.Manifest

import android.animation.ValueAnimator

import android.content.ContentValues

import android.content.Context

import android.content.Intent

import android.content.pm.PackageManager
import org.json.JSONObject
import android.graphics.*

import android.graphics.drawable.BitmapDrawable

import android.graphics.drawable.GradientDrawable

import android.graphics.drawable.ShapeDrawable

import android.graphics.drawable.shapes.PathShape

import android.media.*

import android.opengl.*

import android.os.*

import android.provider.MediaStore

import android.view.*

import android.widget.*

import androidx.appcompat.app.AppCompatActivity

import androidx.camera.core.CameraSelector

import androidx.camera.core.Preview

import androidx.camera.core.SurfaceRequest

import androidx.camera.lifecycle.ProcessCameraProvider

import androidx.core.app.ActivityCompat

import androidx.core.content.ContextCompat

import java.io.File

import java.nio.ByteBuffer

import java.nio.ByteOrder

import java.nio.FloatBuffer

import javax.microedition.khronos.opengles.GL10

import kotlin.math.*

import android.view.Surface

import android.view.SurfaceHolder

import android.view.SurfaceView

import android.view.WindowManager

import android.app.Presentation

import android.hardware.display.DisplayManager

import android.os.Bundle

import android.view.Display

import androidx.media3.common.MediaItem

import androidx.media3.exoplayer.ExoPlayer

import androidx.media3.exoplayer.rtsp.RtspMediaSource

import androidx.media3.common.VideoSize

import androidx.media3.common.Player

import android.util.Log

import android.animation.LayoutTransition

import android.widget.LinearLayout

import android.view.Gravity

import android.graphics.Color

import android.graphics.Typeface


// Use type aliases to distinguish between the incompatible EGL classes

import javax.microedition.khronos.egl.EGLConfig as GL10EGLConfig

import android.opengl.EGLConfig as EGL14EGLConfig


/**

 * Manages external displays (HDMI, Miracast/QuickShare).

 * When connected, it opens a clean SurfaceView on the external screen

 * and passes the Surface to the renderer.

 */

class ExternalDisplayHelper(

    private val context: Context,

    private val renderer: MainActivity.KaleidoscopeRenderer

) {

    private var presentation: CleanFeedPresentation? = null

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager


    private val displayListener = object : DisplayManager.DisplayListener {

        override fun onDisplayAdded(displayId: Int) = updatePresentation()

        override fun onDisplayChanged(displayId: Int) = updatePresentation()

        override fun onDisplayRemoved(displayId: Int) = updatePresentation()

    }


    fun start() {

        displayManager.registerDisplayListener(displayListener, null)

        updatePresentation()

    }


    fun stop() {

        displayManager.unregisterDisplayListener(displayListener)

        presentation?.dismiss()

        presentation = null

    }


    private fun updatePresentation() {

// Look for secondary displays (HDMI, Wireless Display)

        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)



        if (displays.isNotEmpty()) {

            val externalDisplay = displays[0]

// If we are already showing on this display, do nothing

            if (presentation?.display?.displayId == externalDisplay.displayId) return


// Dismiss old one if display changed

            presentation?.dismiss()


// Create new Presentation

            presentation = CleanFeedPresentation(context, externalDisplay, renderer).apply {

                try {
                    show()
                } catch (e: WindowManager.InvalidDisplayException) {
                    dismiss()
                }

            }

        } else {

// No external display, clean up

            presentation?.dismiss()

            presentation = null

            renderer.removeExternalSurface()

        }

    }


    /**

     * Inner class representing the Window on the secondary screen.

     * It contains ONLY the SurfaceView (no UI buttons).

     */

    private class CleanFeedPresentation(

        ctx: Context,

        display: Display,

        val renderer: MainActivity.KaleidoscopeRenderer

    ) : Presentation(ctx, display) {


        override fun onCreate(savedInstanceState: Bundle?) {

            super.onCreate(savedInstanceState)

            val surfaceView = SurfaceView(context)

            setContentView(surfaceView)



            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {

                override fun surfaceCreated(holder: SurfaceHolder) {

                    renderer.setExternalSurface(holder.surface, display.width, display.height)

                }


                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {

                    renderer.setExternalSurface(holder.surface, width, height)

                }


                override fun surfaceDestroyed(holder: SurfaceHolder) {

                    renderer.removeExternalSurface()

                }

            })

        }

    }

}


class PropertyControl(

    private val context: Context,

    val id: String,

    val label: String,

    val min: Int = 0,

    val max: Int = 1000,

    val defaultValue: Int = 0,

    val hasModulation: Boolean = false,

    val modMode: ModMode = ModMode.MIRROR,

    private val onValueChanged: ((Int) -> Unit)? = null

) {

    enum class ModMode { WRAP, MIRROR }


// This is the Integer value for the UI (SeekBar)

    var value: Int = defaultValue
        private set


// NEW: This is the high-precision value for the Renderer

    var preciseValue: Float = defaultValue.toFloat()
        private set


    var modRate: Int = 0
        private set

    var modDepth: Int = 0
        private set


// Modulation floating point counterparts for smooth transitions

    var preciseModRate: Float = 0f

    var preciseModDepth: Float = 0f


    var lfoPhase: Double = 0.0

    var lfoDrift: Double = 0.0


    private var mainSeekBar: SeekBar? = null

    private var rateSeekBar: SeekBar? = null

    private var depthSeekBar: SeekBar? = null


    data class Snapshot(val value: Int, val rate: Int, val depth: Int)


    fun getSnapshot(): Snapshot = Snapshot(value, modRate, modDepth)


    fun restore(snapshot: Snapshot) {

// When restoring immediately (no animation), snap both values

        setProgress(snapshot.value)

        if (hasModulation) {

            setModRate(snapshot.rate)

            setModDepth(snapshot.depth)

        }

    }


// --- NEW: ANIMATION METHODS ---


// Called by the Animator: Updates the float for smooth GL rendering

// Only updates the UI if the integer changes (Performance Optimization)

    fun setAnimatedValue(v: Float) {

        preciseValue = v.coerceIn(min.toFloat(), max.toFloat())

        val intVal = preciseValue.toInt()



        if (intVal != value) {

            value = intVal

            mainSeekBar?.progress = value

            onValueChanged?.invoke(value)

        }

    }


    fun setAnimatedModRate(v: Float) {

        preciseModRate = v.coerceIn(0f, 1000f)

        val intVal = preciseModRate.toInt()

        if (intVal != modRate) {

            modRate = intVal

            rateSeekBar?.progress = modRate

        }

    }


    fun setAnimatedModDepth(v: Float) {

        preciseModDepth = v.coerceIn(0f, 1000f)

        val intVal = preciseModDepth.toInt()

        if (intVal != modDepth) {

            modDepth = intVal

            depthSeekBar?.progress = modDepth

        }

    }


// --- STANDARD UI METHODS ---


    fun setProgress(v: Int) {

        value = v.coerceIn(min, max)

        preciseValue = value.toFloat() // Sync float to int

        mainSeekBar?.progress = value

        onValueChanged?.invoke(value)

    }


    fun setModRate(v: Int) {

        modRate = v.coerceIn(0, 1000)

        preciseModRate = modRate.toFloat() // Sync float to int

        rateSeekBar?.progress = modRate

    }


    fun setModDepth(v: Int) {

        modDepth = v.coerceIn(0, 1000)

        preciseModDepth = modDepth.toFloat() // Sync float to int

        depthSeekBar?.progress = modDepth

    }


    fun reset() {

        setProgress(defaultValue)

        if (hasModulation) {

            setModRate(0)

            setModDepth(0)

        }

    }


// --- RENDERER ACCESS ---


// CRITICAL FIX: Use preciseValue instead of value for calculation

    fun getNormalized(): Float = preciseValue / max.toFloat()


    fun getMapped(outMin: Float, outMax: Float): Float {

        return outMin + (getNormalized() * (outMax - outMin))

    }


// CRITICAL FIX: Use preciseMod... values

    fun getModRateNormalized(): Float = (preciseModRate / 1000f + 0.05f).pow(3f)

    fun getModDepthNormalized(): Float = (preciseModDepth / 1000f).pow(3f)


// ... attachTo and createSeekBar methods remain unchanged ...

    fun attachTo(parent: ViewGroup) {

        val container = LinearLayout(context).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(0, 10, 0, 10)

        }


        val labelView = TextView(context).apply {

            text = label

            setTextColor(Color.WHITE)

            textSize = 10f

            setTypeface(null, Typeface.BOLD)

            alpha = 0.9f

            setOnClickListener { reset() }

        }

        container.addView(labelView)



        mainSeekBar = createSeekBar(max, value) { p ->

            setProgress(p) // Calls the updated logic

        }

        mainSeekBar?.layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60)

        container.addView(mainSeekBar)



        if (hasModulation) {

            val modContainer = LinearLayout(context).apply {

                orientation = LinearLayout.VERTICAL

                setPadding(20, 0, 0, 0)

            }

            modContainer.addView(
                createSubRow(
                    "SPEED",
                    modRate
                ) { p -> setModRate(p) }.also { rateSeekBar = it.second }.first
            )

            modContainer.addView(
                createSubRow(
                    "DEPTH",
                    modDepth
                ) { p -> setModDepth(p) }.also { depthSeekBar = it.second }.first
            )

            container.addView(modContainer)

        }

        parent.addView(container)

    }


    private fun createSubRow(
        label: String,
        startVal: Int,
        onChange: (Int) -> Unit
    ): Pair<LinearLayout, SeekBar> {

        val row = LinearLayout(context).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER_VERTICAL

            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 50)

        }

        val lbl = TextView(context).apply {

            text = label

            setTextColor(Color.LTGRAY)

            textSize = 8f

            minWidth = 100

        }

        val sb = createSeekBar(1000, startVal, onChange).apply {

            thumb = GradientDrawable().apply { setColor(Color.LTGRAY); setSize(12, 22) }

            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        }

        row.addView(lbl)

        row.addView(sb)

        return Pair(row, sb)

    }


    private fun createSeekBar(maxVal: Int, startVal: Int, listener: (Int) -> Unit): SeekBar {

        return SeekBar(context).apply {

            max = maxVal

            progress = startVal

            thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(16, 32) }

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    if (f) listener(p)
                } // Only trigger on user touch to avoid loops

                override fun onStartTrackingTouch(s: SeekBar?) {}

                override fun onStopTrackingTouch(s: SeekBar?) {}

            })

        }

    }

}


// --- MAIN ACTIVITY ---


class MainActivity : AppCompatActivity() {


    private lateinit var glView: GLSurfaceView

    private lateinit var renderer: KaleidoscopeRenderer

    private var currentSelector = CameraSelector.DEFAULT_FRONT_CAMERA

    private lateinit var overlayHUD: FrameLayout


    private lateinit var displayHelper: ExternalDisplayHelper


// Added axisSb as a class property for access in globalReset and presets

    private lateinit var axisSb: SeekBar


    private val controls = mutableListOf<PropertyControl>()

    val controlsMap = mutableMapOf<String, PropertyControl>()


    private val presetButtons = mutableMapOf<Int, Button>()

    private var currentAnimator: ValueAnimator? = null

    private var activePreset: Int = -1


    private lateinit var flipXBtn: ImageButton

    private lateinit var flipYBtn: ImageButton

    private lateinit var rot180Btn: ImageButton

    private lateinit var readabilityBtn: ImageButton

    private lateinit var resetBtn: ImageButton

    private lateinit var galleryBtn: ImageButton

    private lateinit var photoBtn: ImageButton

    private lateinit var recordBtn: ImageButton

    private lateinit var parameterToggleBtn: Button

    private lateinit var parameterToggleContainer: FrameLayout

    private lateinit var flashOverlay: View

    private lateinit var leftHUDContainer: LinearLayout


    private var axisLocked = true

    private lateinit var lockBtn: Button

    private lateinit var saveConfirmBtn: Button


    private var lastFingerDist = 0f

    private var lastFingerAngle = 0f

    private var lastFingerFocusX = 0f

    private var lastFingerFocusY = 0f


    private var exoPlayer: ExoPlayer? = null

    private var isRtspMode = false

    private var lastRtspUrl: String =
        "rtsp://wowzaec2demo.streamlock.net/vod/mp4:BigBuckBunny_115k.mp4" // Example URL


    private data class Preset(

        val controlSnapshots: Map<String, PropertyControl.Snapshot>,

        val flipX: Float,

        val flipY: Float,

        val rot180: Boolean,

        val axis: Int

    )

    private val presets = mutableMapOf<Int, Preset>()

    private var pendingSaveIndex: Int? = null


    private var transitionMs: Long = 1000L

    private var isHudVisible = true

    private var isMenuExpanded = true

    private var isRecording = false

    private var recordingSeconds = 0

    private val handler = Handler(Looper.getMainLooper())

    private var recordTicker: Runnable? = null

    private var readabilityLevel = 2


    private lateinit var parameterPanel: ScrollView

    private lateinit var cameraSettingsPanel: LinearLayout

    private lateinit var presetPanel: LinearLayout

    private lateinit var recordControls: LinearLayout


    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        hideSystemUI()



        renderer = KaleidoscopeRenderer(this)

        glView = GLSurfaceView(this).apply {

            setEGLContextClientVersion(2)

            setEGLConfigChooser(8, 8, 8, 8, 0, 0)

            setRenderer(renderer)

            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        }



        glView.setOnTouchListener { _, event ->

            if (saveConfirmBtn.visibility == View.VISIBLE) {

                saveConfirmBtn.visibility = View.GONE

                pendingSaveIndex = null

            }

            handleInteraction(event)

            true

        }



        setContentView(glView)

        setupOverlayHUD()

        initDefaultPresets()



        glView.post {

            globalReset()

            applyPreset(1)

            applyReadabilityStyle()

        }

        displayHelper = ExternalDisplayHelper(this, renderer)

        displayHelper.start()

        checkAndRequestPermissions()

    }


    private fun checkAndRequestPermissions() {

        val permissions =
            mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)



        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {

            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)

            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)

        } else {

            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)

            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)

        }


        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {

            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 10)

        } else {

            startCamera()

        }

    }


    fun startCamera() {

// 1. Stop RTSP if running

        stopRtsp()

        isRtspMode = false


// 2. Start CameraX

        val cpFuture = ProcessCameraProvider.getInstance(this)

        cpFuture.addListener({

            val provider = cpFuture.get()

            val preview = Preview.Builder()

                .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)

                .build()


// Connect camera to renderer

            preview.setSurfaceProvider { req -> renderer.provideSurface(req) }



            try {

                provider.unbindAll()

                provider.bindToLifecycle(this, currentSelector, preview)

            } catch (e: Exception) {

                android.util.Log.e("Camera", "Bind failed", e)

            }

        }, ContextCompat.getMainExecutor(this))

    }


    private fun startRtsp(url: String) {

// 1. Unbind CameraX

        val cpFuture = ProcessCameraProvider.getInstance(this)

        cpFuture.addListener({

            try {

                cpFuture.get().unbindAll()

            } catch (e: Exception) {
            }

        }, ContextCompat.getMainExecutor(this))


// 2. Setup ExoPlayer

        if (exoPlayer == null) {

            exoPlayer = ExoPlayer.Builder(this).build()

// --- CHANGE: MUTE AUDIO ---

            exoPlayer?.volume = 0f

        }


// 3. Configure Renderer and Player

        glView.queueEvent {

            val surface = renderer.getPlayerSurface()



            runOnUiThread {

                if (surface != null) {

                    exoPlayer?.setVideoSurface(surface)


// Force RTSP over TCP

                    val rtspSource = RtspMediaSource.Factory()

                        .setForceUseRtpTcp(true)

                        .setTimeoutMs(5000)

                        .createMediaSource(MediaItem.fromUri(url))



                    exoPlayer?.setMediaSource(rtspSource)


// Handle Resolution Changes

                    exoPlayer?.addListener(object : Player.Listener {

                        override fun onVideoSizeChanged(videoSize: VideoSize) {

                            super.onVideoSizeChanged(videoSize)

                            if (videoSize.width > 0 && videoSize.height > 0) {

                                renderer.updateTextureSize(videoSize.width, videoSize.height)

                            }

                        }


                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {

                            Toast.makeText(
                                this@MainActivity,
                                "Stream Error: ${error.message}",
                                Toast.LENGTH_LONG
                            ).show()

                        }

                    })



                    exoPlayer?.prepare()

                    exoPlayer?.play()



                    isRtspMode = true

                    lastRtspUrl = url

                    Toast.makeText(this, "Connecting (TCP)...", Toast.LENGTH_SHORT).show()

                } else {

                    Toast.makeText(this, "Renderer not ready", Toast.LENGTH_SHORT).show()

                }

            }

        }

    }


    private fun stopRtsp() {

        exoPlayer?.stop()

        exoPlayer?.clearVideoSurface()

// We do NOT release the player here if we want to reuse it quickly,

// but for memory safety you could release.

    }


    override fun onDestroy() {

        super.onDestroy()

        exoPlayer?.release()

        exoPlayer = null

        displayHelper.stop() // Existing

    }


    private fun handleInteraction(event: MotionEvent) {

        if (event.pointerCount >= 2) {

            val p1x = event.getX(0);
            val p1y = event.getY(0)

            val p2x = event.getX(1);
            val p2y = event.getY(1)

            val focusX = (p1x + p2x) / 2f;
            val focusY = (p1y + p2y) / 2f

            val dist = hypot(p1x - p2x, p1y - p2y)

            val angle =
                Math.toDegrees(atan2((p1y - p2y).toDouble(), (p1x - p2x).toDouble())).toFloat()



            if (event.actionMasked == MotionEvent.ACTION_MOVE) {

                val dx = (focusX - lastFingerFocusX) / glView.width.toFloat() * 2.0f

                val dy = (focusY - lastFingerFocusY) / glView.height.toFloat() * 2.0f



                controlsMap["M_TX"]?.let {
                    it.setProgress(
                        (it.value - (dx * 500).toInt()).coerceIn(
                            0,
                            1000
                        )
                    )
                }

                controlsMap["M_TY"]?.let {
                    it.setProgress(
                        (it.value + (dy * 500).toInt()).coerceIn(
                            0,
                            1000
                        )
                    )
                }


                val scaleFactor = dist / lastFingerDist

                if (scaleFactor > 0) {

                    controlsMap["M_ZOOM"]?.let {
                        it.setProgress(
                            (it.value - (log2(scaleFactor) * 300).toInt()).coerceIn(
                                0,
                                1000
                            )
                        )
                    }

                }


                val dAngle = angle - lastFingerAngle

                controlsMap["M_ANGLE"]?.let {

                    it.setProgress((it.value - (dAngle * (1000f / 360f)).toInt() + 1000) % 1000)

                }

            }

            lastFingerDist = dist; lastFingerAngle = angle; lastFingerFocusX =
                focusX; lastFingerFocusY = focusY

        } else if (event.action == MotionEvent.ACTION_UP) {

            if (event.eventTime - event.downTime < 200) toggleHud()

        }

    }


    private fun textToIcon(t: String, size: Float = 60f, color: Int = Color.WHITE): BitmapDrawable {

        val b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
        val c = Canvas(b);
        val p = Paint().apply {
            this.color = color; textSize = size; textAlign = Paint.Align.CENTER; isFakeBoldText =
            true; isAntiAlias = true
        }

        c.drawText(t, 80f, 80f + (size / 3f), p); return BitmapDrawable(resources, b)

    }

    private fun createGalleryDrawable(): BitmapDrawable {

        val b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
        val c = Canvas(b);
        val p = Paint().apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 8f; isAntiAlias =
            true; strokeJoin = Paint.Join.ROUND
        }

        c.drawRoundRect(RectF(50f, 30f, 130f, 110f), 12f, 12f, p)

        p.style = Paint.Style.FILL; p.color = Color.BLACK;
        val front = RectF(30f, 50f, 110f, 130f); c.drawRoundRect(front, 12f, 12f, p)

        p.style = Paint.Style.STROKE; p.color = Color.WHITE; c.drawRoundRect(front, 12f, 12f, p)

        val path = Path().apply {
            moveTo(38f, 115f); lineTo(60f, 85f); lineTo(80f, 105f); lineTo(
            95f,
            75f
        ); lineTo(105f, 115f)
        }; c.drawPath(path, p)

        c.drawCircle(90f, 70f, 8f, p); return BitmapDrawable(resources, b)

    }

    private fun createClockDrawable(): BitmapDrawable {

        val b = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        val c = Canvas(b);
        val p = Paint().apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true
        }

        c.drawCircle(50f, 55f, 35f, p); c.drawLine(50f, 55f, 50f, 35f, p); c.drawLine(
            50f,
            55f,
            65f,
            55f,
            p
        )

        c.drawLine(40f, 15f, 60f, 15f, p); c.drawLine(50f, 15f, 50f, 20f, p); return BitmapDrawable(
            resources,
            b
        )

    }

    private fun createLogoDrawable(): ShapeDrawable {

        val p = Path().apply {
            moveTo(46f, 131f); lineTo(46f, 162f); lineTo(159f, 162f); lineTo(
            159f,
            144f
        ); lineTo(64f, 144f); lineTo(64f, 131f); close()
        }

        return ShapeDrawable(PathShape(p, 200f, 200f)).apply {
            paint.color = Color.WHITE; paint.isAntiAlias = true
        }

    }

    private fun createLockDrawable(locked: Boolean): BitmapDrawable {

        val b = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        val c = Canvas(b);
        val p = Paint().apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 8f; isAntiAlias = true
        }

        val shackle = RectF(30f, 20f, 70f, 60f); if (locked) c.drawArc(
            shackle,
            180f,
            180f,
            false,
            p
        ) else c.drawArc(shackle, 160f, 180f, false, p)

        p.style = Paint.Style.FILL; c.drawRoundRect(
            RectF(25f, 50f, 75f, 85f),
            8f,
            8f,
            p
        ); return BitmapDrawable(resources, b)

    }


    private fun setupOverlayHUD() {

        overlayHUD = FrameLayout(this).apply { layoutParams = FrameLayout.LayoutParams(-1, -1) }

        flashOverlay = View(this).apply {
            setBackgroundColor(Color.WHITE); alpha = 0f; layoutParams =
            FrameLayout.LayoutParams(-1, -1)
        }

        val logoView = ImageView(this).apply {
            setImageDrawable(createLogoDrawable()); alpha = 0.4f; layoutParams =
            FrameLayout.LayoutParams(180, 180)
                .apply { gravity = Gravity.TOP or Gravity.START; topMargin = 40; leftMargin = 40 }
        }



        leftHUDContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutParams =
            FrameLayout.LayoutParams(-2, -1).apply { gravity = Gravity.START }
        }



        parameterPanel = ScrollView(this).apply {

            layoutParams = LinearLayout.LayoutParams(850, -1); layoutDirection =
            View.LAYOUT_DIRECTION_RTL; isVerticalScrollBarEnabled = true; scrollBarStyle =
            View.SCROLLBARS_INSIDE_OVERLAY

        }


// Main list container

        val menuLayout = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(40, 40, 20, 240)

            layoutDirection = View.LAYOUT_DIRECTION_LTR

// Enable smooth animations when groups expand/collapse

            layoutTransition =
                LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }

        }

        parameterPanel.addView(menuLayout)



        parameterToggleContainer = FrameLayout(this).apply {

            layoutParams = LinearLayout.LayoutParams(140, -1)

            parameterToggleBtn = Button(this@MainActivity).apply {

                text =
                    "<"; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha =
                0.8f; textSize = 32f; layoutParams = FrameLayout.LayoutParams(
                -1,
                400,
                Gravity.CENTER_VERTICAL
            ); setOnClickListener { toggleMenu() }

            }

            addView(parameterToggleBtn)

        }

        leftHUDContainer.addView(parameterPanel); leftHUDContainer.addView(parameterToggleContainer)


// --- NEW COLLAPSIBLE LOGIC ---


        var currentGroupContent: LinearLayout? = null


// Helper to create a collapsible section

        fun createGroup(title: String, startOpen: Boolean = false) {

            val groupContainer = LinearLayout(this).apply {

                orientation = LinearLayout.VERTICAL

                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 15 }

                layoutTransition = LayoutTransition() // Smooth animation inside the group

            }


// The Header Bar

            val header = LinearLayout(this).apply {

                orientation = LinearLayout.HORIZONTAL

                gravity = Gravity.CENTER_VERTICAL

                setPadding(15, 20, 15, 20)

                background = GradientDrawable().apply {

                    setColor(Color.parseColor("#22FFFFFF")) // Slight glass background

                    cornerRadius = 12f

                }

            }


            val arrow = TextView(this).apply {

                text = "▶" // Start pointing right (closed)

                textSize = 10f

                setTextColor(Color.LTGRAY)

                layoutParams = LinearLayout.LayoutParams(60, -2)

                rotation = if (startOpen) 90f else 0f // Rotate down if open

            }


            val label = TextView(this).apply {

                text = title

                textSize = 10f

                setTypeface(null, Typeface.BOLD)

                setTextColor(Color.WHITE)

                letterSpacing = 0.1f

            }



            header.addView(arrow)

            header.addView(label)


// The Content Area

            val content = LinearLayout(this).apply {

                orientation = LinearLayout.VERTICAL

                visibility = if (startOpen) View.VISIBLE else View.GONE

                setPadding(10, 10, 10, 10)

            }


// Toggle Logic

            header.setOnClickListener {

                val isVisible = content.visibility == View.VISIBLE

                if (isVisible) {

                    content.visibility = View.GONE

                    arrow.animate().rotation(0f).setDuration(200).start()

                } else {

                    content.visibility = View.VISIBLE

                    arrow.animate().rotation(90f).setDuration(200).start()

                }

            }



            groupContainer.addView(header)

            groupContainer.addView(content)

            menuLayout.addView(groupContainer)


// Update reference for subsequent addControl calls

            currentGroupContent = content

        }


// --- 1. GEOMETRY GROUP (Includes Axis) ---

        createGroup("GEOMETRY", startOpen = true)


        val axisContainer =
            LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 10) }

        val axisCtrl =
            PropertyControl(this, "AXIS", "COUNT", min = 0, max = 15, defaultValue = 1).apply {

// Manually updated via SeekBar below

            }

        controls.add(axisCtrl); controlsMap["AXIS"] = axisCtrl



        axisSb = SeekBar(this).apply {

            max = 15; progress = 1; layoutParams = LinearLayout.LayoutParams(0, 65, 1f); thumb =
            GradientDrawable().apply { setColor(Color.WHITE); setSize(16, 32) }

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {

                    renderer.axisCount = (p + 1).toFloat()

                    axisCtrl.setProgress(p)

                }

                override fun onStartTrackingTouch(s: SeekBar?) {};
                override fun onStopTrackingTouch(s: SeekBar?) {}

            })

        }



        lockBtn = Button(this).apply {

            background = createLockDrawable(axisLocked); layoutParams =
            LinearLayout.LayoutParams(80, 80).apply { leftMargin = 20 }

            setOnClickListener {
                axisLocked = !axisLocked; background = createLockDrawable(axisLocked); alpha =
                if (axisLocked) 1.0f else 0.4f
            }

            alpha = if (axisLocked) 1.0f else 0.4f

        }

        axisContainer.addView(TextView(this).apply {
            text = "COUNT"; setTextColor(Color.WHITE); textSize = 8f; minWidth = 100; alpha = 0.8f
        }); axisContainer.addView(axisSb); axisContainer.addView(lockBtn)


// Add Axis to the first group

        currentGroupContent?.addView(axisContainer)


// Wrapper to add to the current collapsible group

        fun addControl(c: PropertyControl) {

            controls.add(c)

            controlsMap[c.id] = c

// Attach to the currently active collapsible content view

            currentGroupContent?.let { c.attachTo(it) } ?: c.attachTo(menuLayout)

        }


// --- DYNAMIC CONTROLS ---


        createGroup("3D")

        addControl(
            PropertyControl(
                this,
                "3D_MIX",
                "MIX 3D",
                defaultValue = 0,
                hasModulation = true
            )
        )

        addControl(
            PropertyControl(
                this,
                "S_SHAPE",
                "ROOM SHAPE",
                defaultValue = 0,
                hasModulation = true
            )
        )

        addControl(
            PropertyControl(
                this,
                "S_SPEED",
                "FLIGHT SPEED",
                defaultValue = 500,
                hasModulation = true
            )
        )

        addControl(
            PropertyControl(
                this,
                "S_FOV",
                "FOV DEPTH",
                defaultValue = 500,
                hasModulation = true
            )
        )



        createGroup("MORPHING")

        addControl(
            PropertyControl(
                this,
                "CURVE",
                "CURVE",
                defaultValue = 500,
                hasModulation = true,
                modMode = PropertyControl.ModMode.MIRROR
            )
        )

        addControl(
            PropertyControl(
                this,
                "TWIST",
                "VORTEX",
                defaultValue = 500,
                hasModulation = true,
                modMode = PropertyControl.ModMode.MIRROR
            )
        )

        addControl(
            PropertyControl(
                this,
                "FLUX",
                "BIO-FLUX",
                defaultValue = 0,
                hasModulation = true
            )
        )



        createGroup("MASTER TRANSFORM")

        addControl(
            PropertyControl(
                this,
                "M_ANGLE",
                "ANGLE",
                defaultValue = 0,
                hasModulation = true,
                modMode = PropertyControl.ModMode.WRAP
            )
        )

        addControl(PropertyControl(this, "M_ROT", "ROTATION", defaultValue = 500))

        addControl(
            PropertyControl(
                this,
                "M_ZOOM",
                "ZOOM",
                defaultValue = 160,
                hasModulation = true,
                modMode = PropertyControl.ModMode.MIRROR
            )
        )

        addControl(
            PropertyControl(
                this,
                "M_TX",
                "TRANSLATE X",
                defaultValue = 500,
                hasModulation = true,
                modMode = PropertyControl.ModMode.MIRROR
            )
        )

        addControl(
            PropertyControl(
                this,
                "M_TY",
                "TRANSLATE Y",
                defaultValue = 500,
                hasModulation = true,
                modMode = PropertyControl.ModMode.MIRROR
            )
        )

        addControl(
            PropertyControl(
                this,
                "M_TILTX",
                "TILT X",
                defaultValue = 500,
                hasModulation = true
            )
        )

        addControl(
            PropertyControl(
                this,
                "M_TILTY",
                "TILT Y",
                defaultValue = 500,
                hasModulation = true
            )
        )

        addControl(
            PropertyControl(
                this,
                "M_RGB",
                "RGB SHIFT",
                defaultValue = 0,
                hasModulation = true,
                modMode = PropertyControl.ModMode.MIRROR
            )
        )



        createGroup("CAMERA TRANSFORM")
        val orientationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 20)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120)
        }

        // Helper: Zeichnet die Icons live
        fun createCustomIcon(type: Int): BitmapDrawable {
            val size = 100
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val paint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                isAntiAlias = true
            }

            val center = size / 2f

            when (type) {
                0 -> { // FLIP X (Horizontal)
                    paint.strokeWidth = 9f // Etwas feiner als vorher
                    val range = 20f
                    c.drawLine(center - range, center, center + range, center, paint)
                    val p = Path().apply {
                        moveTo(center - 8, center - 12); lineTo(center - range - 4, center); lineTo(center - 8, center + 12)
                        moveTo(center + 8, center - 12); lineTo(center + range + 4, center); lineTo(center + 8, center + 12)
                    }
                    c.drawPath(p, paint)
                }
                1 -> { // FLIP Y (Vertical)
                    paint.strokeWidth = 9f // Etwas feiner als vorher
                    val range = 20f
                    c.drawLine(center, center - range, center, center + range, paint)
                    val p = Path().apply {
                        moveTo(center - 12, center - 8); lineTo(center, center - range - 4); lineTo(center + 12, center - 8)
                        moveTo(center - 12, center + 8); lineTo(center, center + range + 4); lineTo(center + 12, center + 8)
                    }
                    c.drawPath(p, paint)
                }
                2 -> { // ROTATE (Standard Sync/Cycle Icon Style)
                    paint.strokeWidth = 9f // Gleiche Dicke wie Flip

                    val r = 24f // Radius
                    val box = RectF(center - r, center - r, center + r, center + r)

                    // Wir zeichnen zwei saubere Halbkreise mit Lücken
                    // Bogen 1 (Oben)
                    c.drawArc(box, 180f + 20f, 140f, false, paint)
                    // Bogen 2 (Unten)
                    c.drawArc(box, 0f + 20f, 140f, false, paint)

                    // Pfeilspitzen (Exakt positioniert am Ende der Bögen)
                    val p = Path()

                    // Pfeil oben rechts (Ende von Bogen 1)
                    // Spitze zeigt nach rechts/unten
                    val endX1 = center + (r * cos(Math.toRadians(340.0))).toFloat()
                    val endY1 = center + (r * sin(Math.toRadians(340.0))).toFloat()
                    p.moveTo(endX1 - 5f, endY1 - 15f); p.lineTo(endX1, endY1); p.lineTo(endX1 - 18f, endY1 - 2f)

                    // Pfeil unten links (Ende von Bogen 2)
                    // Spitze zeigt nach links/oben
                    val endX2 = center + (r * cos(Math.toRadians(160.0))).toFloat()
                    val endY2 = center + (r * sin(Math.toRadians(160.0))).toFloat()
                    p.moveTo(endX2 + 5f, endY2 + 15f); p.lineTo(endX2, endY2); p.lineTo(endX2 + 18f, endY2 + 2f)

                    paint.style = Paint.Style.STROKE
                    c.drawPath(p, paint)
                }
            }
            return BitmapDrawable(resources, b)
        }

        fun createParamBtn(icon: BitmapDrawable, action: () -> Unit): ImageButton {
            return ImageButton(this).apply {
                setImageDrawable(icon)
                // Quadratischer Hintergrund (Header-Stil)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#22FFFFFF"))
                    cornerRadius = 12f
                }
                // Margins für Abstand
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                lp.setMargins(6, 0, 6, 0)
                layoutParams = lp

                setOnClickListener { action(); updateSidebarVisuals() }
            }
        }

        flipXBtn = createParamBtn(createCustomIcon(0)) {
            renderer.flipX = if (renderer.flipX == 1f) -1f else 1f
        }

        flipYBtn = createParamBtn(createCustomIcon(1)) {
            renderer.flipY = if (renderer.flipY == 1f) -1f else 1f
        }

        rot180Btn = createParamBtn(createCustomIcon(2)) {
            renderer.rot180 = !renderer.rot180
        }

        orientationRow.addView(flipXBtn)
        orientationRow.addView(flipYBtn)
        orientationRow.addView(rot180Btn)

        // Add the row to the collapsible content
        currentGroupContent?.addView(orientationRow)
        addControl(
            PropertyControl(
                this,
                "C_ANGLE",
                "ANGLE",
                defaultValue = 0,
                hasModulation = true,
                modMode = PropertyControl.ModMode.WRAP
            )
        )

        addControl(PropertyControl(this, "C_ROT", "ROTATION", defaultValue = 500))

        addControl(PropertyControl(this, "WARP", "WARP DISTORT", defaultValue = 0))

        addControl(
            PropertyControl(
                this,
                "C_ZOOM",
                "ZOOM",
                defaultValue = 300,
                hasModulation = true,
                modMode = PropertyControl.ModMode.MIRROR
            )
        )

        addControl(
            PropertyControl(
                this,
                "C_TX",
                "TRANSLATE X",
                defaultValue = 500,
                hasModulation = true,
                modMode = PropertyControl.ModMode.MIRROR
            )
        )

        addControl(
            PropertyControl(
                this,
                "C_TY",
                "TRANSLATE Y",
                defaultValue = 500,
                hasModulation = true,
                modMode = PropertyControl.ModMode.MIRROR
            )
        )

        addControl(
            PropertyControl(
                this,
                "C_TILTX",
                "TILT X",
                defaultValue = 500,
                hasModulation = true
            )
        )

        addControl(
            PropertyControl(
                this,
                "C_TILTY",
                "TILT Y",
                defaultValue = 500,
                hasModulation = true
            )
        )

        addControl(
            PropertyControl(
                this,
                "RGB",
                "RGB SHIFT",
                defaultValue = 0,
                hasModulation = true,
                modMode = PropertyControl.ModMode.MIRROR
            )
        )



        createGroup("COLOR GRADING")

        addControl(
            PropertyControl(
                this,
                "HUE",
                "HUE",
                defaultValue = 0,
                hasModulation = true,
                modMode = PropertyControl.ModMode.WRAP
            )
        )

        addControl(
            PropertyControl(
                this,
                "NEG",
                "NEGATIVE",
                defaultValue = 0,
                hasModulation = true,
                modMode = PropertyControl.ModMode.MIRROR
            )
        )

        addControl(
            PropertyControl(
                this,
                "GLOW",
                "GLOW",
                defaultValue = 0,
                hasModulation = true,
                modMode = PropertyControl.ModMode.MIRROR
            )
        )

        addControl(PropertyControl(this, "CONTRAST", "CONTRAST", defaultValue = 500))

        addControl(PropertyControl(this, "VIBRANCE", "VIBRANCE", defaultValue = 500))


// --- UI BUTTONS & LAYOUTS ---


        cameraSettingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(
            10,
            20,
            10,
            20
        )
        }

        fun createSideBtn(action: () -> Unit) = ImageButton(this).apply {
            setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha =
            0.3f; layoutParams = LinearLayout.LayoutParams(
            100,
            100
        ); setOnClickListener { action(); updateSidebarVisuals() }
        }

        cameraSettingsPanel.addView(createSideBtn {
            currentSelector =
                if (currentSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA; startCamera()
        }.apply { setImageResource(android.R.drawable.ic_menu_camera) })



        cameraSettingsPanel.addView(createSideBtn {
            showRtspDialog()
        }.apply {
            setImageResource(android.R.drawable.ic_menu_compass)
        })



        recordControls = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(
            20,
            10,
            20,
            10
        )

            translationX = resources.displayMetrics.widthPixels * 0.2f

        }

        photoBtn = ImageButton(this).apply {
            setImageDrawable(
                textToIcon(
                    "[ ]",
                    50f
                )
            ); setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE); alpha =
            0.8f; scaleX = 1.5f; scaleY = 1.5f; layoutParams = LinearLayout.LayoutParams(
            150,
            150
        ); setOnClickListener { renderer.capturePhoto(); triggerFlashPulse() }
        }

        recordBtn = ImageButton(this).apply {
            setImageDrawable(
                textToIcon(
                    "REC",
                    40f
                )
            ); setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE); alpha =
            0.5f; layoutParams = LinearLayout.LayoutParams(150, 150)
            .apply { leftMargin = 40 }; setOnClickListener { toggleRecording() }
        }

        recordControls.addView(photoBtn); recordControls.addView(recordBtn)



        presetPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(
            15,
            10,
            15,
            30
        )
        }

        val transContainer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(
            10,
            0,
            10,
            10
        )
        }

        val clockIcon = ImageView(this).apply {
            setImageDrawable(createClockDrawable()); alpha = 0.5f; layoutParams =
            LinearLayout.LayoutParams(45, 45).apply { rightMargin = 10 }
        }

        val timeLabel = TextView(this).apply {
            text = "1.0s"; setTextColor(Color.WHITE); textSize = 9f; setPadding(4, 0, 8, 0)
        }

        val transSeekBar = SeekBar(this).apply {

            max = 1000; progress = 333; layoutParams = LinearLayout.LayoutParams(500, 45); thumb =
            GradientDrawable().apply { setColor(Color.WHITE); setSize(12, 24) }

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    transitionMs = ((p / 1000f).pow(3.0f) * 30000).toLong(); timeLabel.text =
                        "%.1fs".format(transitionMs / 1000f)
                };
                override fun onStartTrackingTouch(s: SeekBar?) {};
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })

        }

        transContainer.addView(clockIcon); transContainer.addView(timeLabel); transContainer.addView(
            transSeekBar
        )

        val presetRow = FrameLayout(this);
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }

        fun createPresetBtn(idx: Int) = Button(this).apply {
            text =
                idx.toString(); setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha =
            0.8f; textSize = 16f; layoutParams = LinearLayout.LayoutParams(80, 140); setPadding(
            0,
            0,
            0,
            20
        ); setOnClickListener { applyPreset(idx) }; setOnLongClickListener {
            pendingSaveIndex = idx; saveConfirmBtn.visibility = View.VISIBLE; saveConfirmBtn.text =
            "SAVE $idx?"; true
        }
        }

        (8 downTo 1).forEach {
            val b = createPresetBtn(it); presetButtons[it] = b; btnRow.addView(b)
        }

        saveConfirmBtn = Button(this).apply {
            visibility = View.GONE; setTextColor(Color.BLACK); textSize = 12f; setTypeface(
            null,
            Typeface.BOLD
        ); background =
            GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8f }; layoutParams =
            FrameLayout.LayoutParams(
                250,
                100,
                Gravity.CENTER
            ); setOnClickListener {
            pendingSaveIndex?.let { savePreset(it) }; visibility = View.GONE
        }
        }

        presetRow.addView(btnRow); presetRow.addView(saveConfirmBtn); presetPanel.addView(
            transContainer
        ); presetPanel.addView(presetRow)



        galleryBtn = ImageButton(this).apply {
            setImageDrawable(createGalleryDrawable()); setColorFilter(Color.WHITE); alpha =
            0.4f; layoutParams = FrameLayout.LayoutParams(100, 100).apply {
            gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 250; rightMargin = 45
        }; setOnClickListener { openGallery() }
        }

        readabilityBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_view); setColorFilter(Color.WHITE); alpha =
            0.4f; layoutParams = FrameLayout.LayoutParams(120, 120).apply {
            gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 140; rightMargin = 35
        }; setOnClickListener { toggleReadability() }
        }

        resetBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel); setColorFilter(Color.WHITE); alpha =
            0.4f; layoutParams = FrameLayout.LayoutParams(120, 120).apply {
            gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 30; rightMargin = 35
        }; setOnClickListener { globalReset() }
        }



        overlayHUD.addView(flashOverlay); overlayHUD.addView(logoView); overlayHUD.addView(
            leftHUDContainer
        ); overlayHUD.addView(
            recordControls,
            FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = 30
            }); overlayHUD.addView(
            cameraSettingsPanel,
            FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.TOP or Gravity.END; topMargin = 40; rightMargin = 40
            }); overlayHUD.addView(
            presetPanel,
            FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 15; rightMargin = 180
            }); overlayHUD.addView(galleryBtn); overlayHUD.addView(readabilityBtn); overlayHUD.addView(
            resetBtn
        )

        addContentView(overlayHUD, ViewGroup.LayoutParams(-1, -1)); updateSidebarVisuals()

    }

    private fun showRtspDialog() {
        // 1. History laden (Max 20 Einträge)
        val prefs = getSharedPreferences("SpaceBeam_RTSP", Context.MODE_PRIVATE)
        val historyKey = "RTSP_HISTORY"

        // Wir laden das Set. Achtung: Sets sind unsortiert.
        val rawSet = prefs.getStringSet(historyKey, null)
        val historyList = rawSet?.toMutableList() ?: mutableListOf()

        // Fallback
        if (historyList.isEmpty()) {
            historyList.add("rtsp://wowzaec2demo.streamlock.net/vod/mp4:BigBuckBunny_115k.mp4")
        }

        // Container für Input + Button
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 20, 40, 0)
        }

        // 2. Das Textfeld
        val input = AutoCompleteTextView(this).apply {
            setText(lastRtspUrl)
            setTextColor(Color.BLACK)
            textSize = 16f
            setPadding(20, 30, 20, 30)
            threshold = 1 // Zeige Vorschläge ab 1 Zeichen (oder via Button)

            // WICHTIG: Verhindert, dass die Tastatur den ganzen Screen einnimmt (Landscape Fix)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI

            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_URI

            // Layout Gewichtung: Nimm den meisten Platz
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

            val adapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, historyList)
            setAdapter(adapter)
        }

        // 3. Der "Dropdown" Pfeil Button
        val arrowBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.arrow_down_float)
            setBackgroundColor(Color.LTGRAY) // Leichtes Grau als Hintergrund für Button
            alpha = 0.7f
            scaleType = ImageView.ScaleType.CENTER_INSIDE

            layoutParams = LinearLayout.LayoutParams(120, 100).apply {
                leftMargin = 10
            }

            // Klick: Tastatur verstecken (falls offen) und Liste zeigen
            setOnClickListener {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(input.windowToken, 0)
                input.showDropDown()
            }
        }

        row.addView(input)
        row.addView(arrowBtn)

        // Dialog erstellen
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Enter RTSP/Video URL")
            .setView(row) // Wir nutzen jetzt 'row' statt nur 'container'
            .setPositiveButton("Load", null) // Listener wird unten überschrieben
            .setNegativeButton("Cancel", null)
            .create()

        fun performLoad() {
            val url = input.text.toString().trim()
            if (url.isNotEmpty()) {
                // --- HISTORY LOGIC (Limit 20) ---
                // 1. Wenn URL schon drin ist, entfernen (damit sie nach oben/neu kommt, je nach Sortierung)
                if (historyList.contains(url)) {
                    historyList.remove(url)
                }
                // 2. Neue URL hinzufügen
                historyList.add(0, url) // Vorne anfügen (für Adapter-Logik im RAM)

                // 3. Auf 20 beschränken
                while (historyList.size > 20) {
                    historyList.removeAt(historyList.lastIndex)
                }

                // 4. Speichern (Als Set konvertieren für SharedPreferences)
                prefs.edit().putStringSet(historyKey, historyList.toHashSet()).apply()

                startRtsp(url)
                dialog.dismiss()
            }
        }

        // Keyboard "Done" Handler
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                performLoad()
                true
            } else false
        }

        // Item Click Handler (Wenn man einen Vorschlag auswählt)
        input.setOnItemClickListener { _, _, _, _ ->
            // Optional: Direkt laden, wenn man draufklickt?
            // Oder nur Text setzen (Standardverhalten). Lassen wir Standard.
        }

        dialog.show()

        // Button Logik überschreiben
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            performLoad()
        }
    }

    private fun applyReadabilityStyle() {

        val getBg = { alpha: Int ->
            GradientDrawable().apply {
                setColor(
                    Color.argb(
                        alpha,
                        5,
                        5,
                        5
                    )
                ); setStroke(3, Color.argb(180, 40, 40, 40)); cornerRadius = 45f; shape =
                GradientDrawable.RECTANGLE
            }
        }

        val panels = listOf(leftHUDContainer, cameraSettingsPanel, presetPanel, recordControls)

        val utils = listOf(galleryBtn, readabilityBtn, resetBtn)

        panels.forEach {
            it.background = null; it.setPadding(30, 30, 30, 30); it.clipToOutline = true
        }



        when (readabilityLevel) {

            1 -> {

                panels.forEach { it.background = getBg(210) }

                utils.forEach { it.background = getBg(230).apply { shape = GradientDrawable.OVAL } }

                applyRecursiveGlow(overlayHUD, false)

            }

            2 -> {

                panels.forEach { it.background = getBg(160) }

                utils.forEach { it.background = getBg(200).apply { shape = GradientDrawable.OVAL } }

                applyRecursiveGlow(overlayHUD, true)

            }

            else -> {

                panels.forEach { it.setPadding(0, 0, 0, 0); it.background = null }

                applyRecursiveGlow(overlayHUD, false)

            }

        }

    }


    private fun applyRecursiveGlow(view: View, enabled: Boolean) {

        if (view is TextView) {

            if (enabled) view.setShadowLayer(50f, 0f, 0f, Color.BLACK) else view.setShadowLayer(
                0f,
                0f,
                0f,
                Color.TRANSPARENT
            )

        } else if (view is ImageButton || view is Button) {

            view.elevation = if (enabled) 50f else 0f

        }

        if (view is ViewGroup) (0 until view.childCount).forEach {
            applyRecursiveGlow(
                view.getChildAt(
                    it
                ), enabled
            )
        }

    }


    private fun toggleReadability() {
        readabilityLevel = (readabilityLevel + 1) % 3; applyReadabilityStyle()
    }

    private fun openGallery() {

        try {

            val intent =
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

            startActivity(intent)

        } catch (e: Exception) {

            val fallback = Intent(Intent.ACTION_VIEW).apply {
                type = "image/*"; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                startActivity(fallback)
            } catch (ex: Exception) {
                Toast.makeText(this, "Gallery app not found", Toast.LENGTH_SHORT).show()
            }

        }

    }


    private fun toggleRecording() {

        if (!isRecording) {

            val fileName = "SB_${System.currentTimeMillis()}.mp4"

            val tempFile = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), fileName)

            renderer.startRecording(tempFile); isRecording = true; recordingSeconds =
                0; recordBtn.alpha = 1.0f

            recordTicker = object : Runnable {

                override fun run() {

                    recordingSeconds++;
                    val m = recordingSeconds / 60;
                    val s = recordingSeconds % 60

                    recordBtn.setImageDrawable(
                        textToIcon(
                            "%d:%02d".format(m, s),
                            38f,
                            Color.RED
                        )
                    ); handler.postDelayed(this, 1000)

                }

            }; handler.post(recordTicker!!)

        } else {

            renderer.stopRecording { savedFile ->

                isRecording = false; recordTicker?.let { handler.removeCallbacks(it) }

                runOnUiThread {
                    recordBtn.setImageDrawable(
                        textToIcon(
                            "REC",
                            40f
                        )
                    ); recordBtn.alpha =
                    0.5f; if (savedFile != null && savedFile.exists()) saveVideoToGallery(savedFile)
                }

            }

        }

    }


    private fun saveVideoToGallery(file: File) {

        if (file.length() == 0L) return

        val values = ContentValues().apply {

            put(
                MediaStore.Video.Media.DISPLAY_NAME,
                file.name
            ); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/SpaceBeam"
                ); put(MediaStore.Video.Media.IS_PENDING, 1)
            }

        }

        try {

            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)

            uri?.let {

                contentResolver.openOutputStream(it)
                    ?.use { out -> file.inputStream().use { it.copyTo(out) } }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear(); values.put(
                        MediaStore.Video.Media.IS_PENDING,
                        0
                    ); contentResolver.update(it, values, null, null)
                }

                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(file.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )

                file.delete(); Toast.makeText(this, "Video Saved", Toast.LENGTH_SHORT).show()

            }

        } catch (e: Exception) {
        }

    }

    private fun globalReset() {
        // 1. Kill any ongoing preset animations immediately
        currentAnimator?.cancel()

        // 2. Reset the Renderer's continuous accumulators (Rotation & Flight)
        // These are "physics" variables the sliders don't own directly
        renderer.mRotAccum = 0.0
        renderer.cRotAccum = 0.0
        renderer.lRotAccum = 0.0
        renderer.scrollAccum = 0.0f
        renderer.resetPhases() // Reset LFO/Modulation positions

        // 3. Reset Camera Flipping & Rotation
        renderer.flipX = 1f
        renderer.flipY = -1f
        renderer.rot180 = false

        // 4. Reset UI State
        activePreset = -1
        updatePresetHighlights()

        // 5. AUTOMATED RESET: Loop through all sliders and restore their defaultValues
        // This handles Zoom, Warp, Colors, 3D Mix, etc. automatically
        controls.forEach { it.reset() }

        // 6. Specific Override: The AXIS slider
        // We force this to 2 segments (Mirror mode) as the baseline
        renderer.axisCount = 2.0f
        axisSb.progress = 1 // Index 1 = Count 2
        controlsMap["AXIS"]?.setProgress(1)

        // 7. Update sidebar button highlights (Alpha states)
        updateSidebarVisuals()
    }


    private fun updatePresetHighlights() {
        presetButtons.forEach { (idx, btn) ->
            btn.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT); if (idx == activePreset) setStroke(
                4,
                Color.WHITE
            ); cornerRadius = 12f
            }
        }
    }

    private fun triggerFlashPulse() {
        flashOverlay.alpha = 0.6f; flashOverlay.animate().alpha(0f).setDuration(400)
            .start(); photoBtn.animate().scaleX(1.8f).scaleY(1.8f).setDuration(100)
            .withEndAction { photoBtn.animate().scaleX(1.5f).scaleY(1.5f).setDuration(200).start() }
            .start()
    }

    private fun updateSidebarVisuals() {
        flipXBtn.alpha = if (renderer.flipX < 0f) 1.0f else 0.3f; flipYBtn.alpha =
            if (renderer.flipY > 0f) 1.0f else 0.3f; rot180Btn.alpha =
            if (renderer.rot180) 1.0f else 0.3f
    }

    private fun addHeader(m: LinearLayout, t: String) = m.addView(TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 8f; alpha = 0.3f; setPadding(0, 45, 0, 5)
    })

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
    }

    private fun applyPreset(idx: Int) {
        val p = presets[idx] ?: return
        activePreset = idx
        updatePresetHighlights()
        currentAnimator?.cancel()

        if (!axisLocked) {
            renderer.axisCount = p.axis.toFloat()
            axisSb.progress = p.axis - 1
            controlsMap["AXIS"]?.setProgress(p.axis - 1)
        }

        // --- SNAPSHOT START VALUES ---
        val startValues = controls.associate { it.id to it.preciseValue }
        val startRates = controls.associate { it.id to it.preciseModRate }
        val startDepths = controls.associate { it.id to it.preciseModDepth }

        // --- INTELLIGENTER ROTATION RESET ---
        // 1. Startwerte holen
        val startMRot = renderer.mRotAccum
        val startCRot = renderer.cRotAccum

        // 2. Ziel berechnen: Das nächste Vielfache von 360 Grad
        // Wir runden den aktuellen Wert geteilt durch 360.
        // Beispiel: Stehen wir bei 370°, runden wir auf 1 -> Ziel 360°. Weg: -10°.
        // Beispiel: Stehen wir bei 350°, runden wir auf 1 -> Ziel 360°. Weg: +10°.
        val targetMRot = round(startMRot / 360.0) * 360.0
        val targetCRot = round(startCRot / 360.0) * 360.0

        currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = transitionMs
            // Decelerate sieht bei Rotation natürlicher aus (bremst sanft ab)
            interpolator = android.view.animation.DecelerateInterpolator()

            addUpdateListener { anim ->
                val t = anim.animatedValue as Float

                // 1. Slider Werte animieren
                controls.forEach { control ->
                    if (control.id == "AXIS") return@forEach
                    val target = p.controlSnapshots[control.id]
                    if (target != null) {
                        val sVal = startValues[control.id] ?: 0f
                        val newVal = sVal + (target.value - sVal) * t
                        control.setAnimatedValue(newVal)

                        if (control.hasModulation) {
                            val sRate = startRates[control.id] ?: 0f
                            val newRate = sRate + (target.rate - sRate) * t
                            control.setAnimatedModRate(newRate)

                            val sDepth = startDepths[control.id] ?: 0f
                            val newDepth = sDepth + (target.depth - sDepth) * t
                            control.setAnimatedModDepth(newDepth)
                        }
                    }
                }

                // 2. Rotation sanft zur nächsten geraden Position drehen
                // Lerp Formel: start + (ziel - start) * t
                renderer.mRotAccum = startMRot + (targetMRot - startMRot) * t.toDouble()
                renderer.cRotAccum = startCRot + (targetCRot - startCRot) * t.toDouble()

                // LFO Rotation setzen wir hart auf 0 zurück (Fade out wäre komplexer)
                renderer.lRotAccum = renderer.lRotAccum * (1.0 - t.toDouble())

                // 3. Flags setzen
                renderer.flipX = p.flipX
                renderer.flipY = p.flipY
                renderer.rot180 = p.rot180
                updateSidebarVisuals()
            }
            start()
        }
    }


    private fun savePreset(idx: Int) {
        // 1. Capture current state
        val snapshots = controls.filter { it.id != "AXIS" }.associate { it.id to it.getSnapshot() }
        val axisVal = controlsMap["AXIS"]?.value ?: 0

        val newPreset = Preset(
            snapshots,
            renderer.flipX, renderer.flipY, renderer.rot180,
            axisVal + 1
        )

        // 2. Update Memory
        presets[idx] = newPreset
        activePreset = idx
        updatePresetHighlights()

        // 3. SERIALIZE TO JSON & SAVE TO DISK
        try {
            val rootObj = JSONObject()
            rootObj.put("axis", newPreset.axis)
            rootObj.put("flipX", newPreset.flipX.toDouble())
            rootObj.put("flipY", newPreset.flipY.toDouble())
            rootObj.put("rot180", newPreset.rot180)

            val controlsObj = JSONObject()
            newPreset.controlSnapshots.forEach { (key, snap) ->
                val snapObj = JSONObject()
                snapObj.put("v", snap.value)
                snapObj.put("r", snap.rate)
                snapObj.put("d", snap.depth)
                controlsObj.put(key, snapObj)
            }
            rootObj.put("controls", controlsObj)

            val prefs = getSharedPreferences("SpaceBeam_Presets", Context.MODE_PRIVATE)
            prefs.edit().putString("PRESET_$idx", rootObj.toString()).apply()

            Toast.makeText(this, "Preset $idx Saved Permanently", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("PRESET", "Failed to save preset", e)
            Toast.makeText(this, "Save Failed", Toast.LENGTH_SHORT).show()
        }
    }
    private fun logPresetCode(idx: Int, p: Preset) {
        val sb = StringBuilder()
        sb.append("presets[$idx] = p(ax=${p.axis}, mRot=${p.controlSnapshots["M_ROT"]?.value ?: 500}")

        p.controlSnapshots.forEach { (id, snap) ->
            if (id != "M_ROT" && id != "AXIS") {
                if (snap.value != 500 || snap.rate != 0 || snap.depth != 0) {
                    sb.append(", \"$id\", ${snap.value}, ${snap.rate}, ${snap.depth}")
                }
            }
        }
        sb.append(")")
        android.util.Log.d("PRESET_STUDIO", sb.toString())
    }

    private fun initDefaultPresets() {
        // --- PART 1: Hardcoded Defaults (Factory Settings) ---
        fun p(ax: Int = 1, mRot: Int = 500, vararg overrides: Any): Preset {
            val baseSnapshots = controls.associate { it.id to it.getSnapshot() }.toMutableMap()
            baseSnapshots["M_ROT"] = PropertyControl.Snapshot(mRot, 0, 0)
            var i = 0
            while (i < overrides.size) {
                val key = overrides[i] as String
                val value = overrides[i + 1] as Int
                if (i + 3 < overrides.size && overrides[i + 2] is Int && overrides[i + 3] is Int) {
                    val rate = overrides[i + 2] as Int
                    val depth = overrides[i + 3] as Int
                    baseSnapshots[key] = PropertyControl.Snapshot(value, rate, depth)
                    i += 4
                } else {
                    baseSnapshots[key] = PropertyControl.Snapshot(value, 0, 0)
                    i += 2
                }
            }
            return Preset(baseSnapshots, 1f, -1f, false, ax)
        }

        // Load Factory Defaults
        presets[1] = p(ax = 2, mRot = 500, "M_ZOOM", 300, 139, 307, "WARP", 1000, 0, 0)
        presets[2] = p(ax = 2, mRot = 615, "M_ZOOM", 248, 293, 383, "WARP", 1000, 0, 0)
        presets[3] = p(ax = 2, mRot = 673, "M_ZOOM", 268, 293, 559, "M_TILTX", 553, 305, 880, "M_TILTY", 500, 353, 1000, "WARP", 1000, 0, 0)
        presets[4] = p(ax = 2, mRot = 673, "M_ZOOM", 268, 293, 517, "M_TX", 500, 159, 624, "M_TY", 500, 309, 753, "M_TILTX", 553, 305, 880, "M_TILTY", 500, 353, 1000, "WARP", 1000, 0, 0)
        presets[5] = p(ax = 2, mRot = 673, "M_ZOOM", 359, 293, 517, "M_TX", 500, 159, 624, "M_TY", 500, 309, 753, "M_TILTX", 553, 305, 577, "M_TILTY", 500, 353, 854, "C_ROT", 657, 0, 0, "WARP", 0, 0, 0)
        presets[6] = p(ax = 2, mRot = 673, "M_ZOOM", 268, 293, 517, "M_TX", 500, 159, 624, "M_TY", 500, 309, 753, "M_TILTX", 553, 305, 1000, "M_TILTY", 500, 353, 1000, "C_ROT", 657, 0, 0, "WARP", 0, 0, 0, "C_TX", 500, 389, 739, "C_TY", 500, 209, 763, "GLOW", 164, 395, 129)
        presets[7] = p(ax = 2, mRot = 673, "M_ZOOM", 912, 293, 740, "M_TX", 500, 159, 624, "M_TY", 500, 309, 753, "M_TILTX", 553, 305, 1000, "M_TILTY", 500, 353, 1000, "C_ROT", 657, 0, 0, "WARP", 0, 0, 0, "C_TX", 500, 389, 739, "C_TY", 500, 209, 763, "C_TILTX", 500, 287, 677, "C_TILTY", 500, 443, 557, "GLOW", 164, 395, 129)
        presets[8] = p(ax = 2, mRot = 673, "M_ZOOM", 268, 293, 517, "M_TX", 500, 159, 624, "M_TY", 500, 309, 753, "M_TILTX", 553, 305, 1000, "M_TILTY", 500, 353, 1000, "C_ROT", 657, 0, 0, "WARP", 0, 0, 0, "C_TX", 500, 389, 739, "C_TY", 500, 209, 763, "C_TILTX", 500, 287, 677, "C_TILTY", 500, 443, 557, "RGB", 957, 0, 0, "GLOW", 164, 395, 129)

        // --- PART 2: Load Saved User Overrides from Disk ---
        val prefs = getSharedPreferences("SpaceBeam_Presets", Context.MODE_PRIVATE)

        for (i in 1..8) {
            val jsonStr = prefs.getString("PRESET_$i", null)
            if (jsonStr != null) {
                try {
                    val rootObj = JSONObject(jsonStr)
                    val loadedAxis = rootObj.getInt("axis")
                    val loadedFlipX = rootObj.getDouble("flipX").toFloat()
                    val loadedFlipY = rootObj.getDouble("flipY").toFloat()
                    val loadedRot180 = rootObj.getBoolean("rot180")

                    val controlsObj = rootObj.getJSONObject("controls")
                    val loadedSnapshots = mutableMapOf<String, PropertyControl.Snapshot>()

                    // Start with defaults to ensure missing keys don't crash
                    loadedSnapshots.putAll(presets[i]?.controlSnapshots ?: emptyMap())

                    // --- FIX IS HERE: Use strict Iterator while-loop ---
                    val keysIterator = controlsObj.keys()
                    while (keysIterator.hasNext()) {
                        val key = keysIterator.next()
                        val snapObj = controlsObj.getJSONObject(key)
                        loadedSnapshots[key] = PropertyControl.Snapshot(
                            snapObj.getInt("v"),
                            snapObj.optInt("r", 0),
                            snapObj.optInt("d", 0)
                        )
                    }

                    presets[i] = Preset(loadedSnapshots, loadedFlipX, loadedFlipY, loadedRot180, loadedAxis)
                    Log.d("PRESETS", "Successfully loaded user preset $i") // Log success
                } catch (e: Exception) {
                    Log.e("PRESET", "Error loading preset $i", e)
                }
            }
        }
    }


    private fun toggleHud() {
        isHudVisible = !isHudVisible; overlayHUD.visibility =
            if (isHudVisible) View.VISIBLE else View.GONE; if (isHudVisible) hideSystemUI()
    }

    private fun toggleMenu() {
        isMenuExpanded = !isMenuExpanded; parameterPanel.visibility =
            if (isMenuExpanded) View.VISIBLE else View.GONE; parameterToggleBtn.text =
            if (isMenuExpanded) "<" else ">"
    }


    inner class KaleidoscopeRenderer(private val ctx: MainActivity) : GLSurfaceView.Renderer {
        // --- Programs ---
        private var kaleidoProgram = 0
        private var simpleProgram = 0
        var scrollAccum = 0.0f
        // --- Textures & Buffers ---
        private var cameraTexId = -1
        private var surfaceTexture: SurfaceTexture? = null

        // NEW: Surface wrapper for ExoPlayer
        private var playerSurface: Surface? = null

        // TIMESTAMP FIX: This tracks when the current recording session truly began
        private var recordStartTimeNs: Long = 0

        // ... (Keep existing FBO, Buffers, Uniforms variables) ...
        private var fboId = 0
        private var fboTexId = 0
        private var fboWidth = 1920
        private var fboHeight = 1080

        private lateinit var pBuf: FloatBuffer
        private lateinit var tBuf: FloatBuffer
        private var uLocs = mutableMapOf<String, Int>()
        private var simpleULocs = mutableMapOf<String, Int>()
        private var viewWidth = 1
        private var viewHeight = 1
        private var lastTime = System.nanoTime()

        // Geometric properties
        var axisCount = 2.0f
        var flipX = 1.0f
        var flipY = -1.0f
        var rot180 = false
        var mRotAccum = 0.0
        var cRotAccum = 0.0
        var lRotAccum = 0.0

        // Media / Capture vars
        private var captureRequested = false
        private var videoRecorder: VideoRecorder? = null
        private var recordSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
        private var pendingRecordFile: File? = null
        private var onStopCallback: ((File?) -> Unit)? = null
        private var isStopRequested = false
        private var mSavedDisplay = EGL14.EGL_NO_DISPLAY
        private var mSavedContext = EGL14.eglGetCurrentContext()
        private var mEglConfig: EGL14EGLConfig? = null
        private var extSurfaceArgs: Triple<Surface, Int, Int>? = null
        private var extEglSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
        private var extWidth = 0
        private var extHeight = 0
        private var startTime = System.nanoTime()

        fun resetPhases() {
            ctx.controls.forEach { it.lfoPhase = 0.0; it.lfoDrift = 0.0 }
            mRotAccum = 0.0; cRotAccum = 0.0; lRotAccum = 0.0
        }

        fun capturePhoto() { captureRequested = true }

        // Clean stop function (Removed recursive loop)
        fun stopRecording(callback: (File?) -> Unit) {
            onStopCallback = callback
            isStopRequested = true
        }

        fun getPlayerSurface(): Surface? {
            if (surfaceTexture == null) return null
            if (playerSurface == null) {
                playerSurface = Surface(surfaceTexture)
            }
            return playerSurface
        }

        fun startRecording(file: File) {
            pendingRecordFile = file
            // CRITICAL FIX: Reset the time to 0 for every new video
            recordStartTimeNs = 0
        }

        override fun onSurfaceCreated(gl: GL10?, config: GL10EGLConfig?) {
            setupEGL()
            val vSrc = "attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }"
            val fSrc = """#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 v;
uniform samplerExternalOES uTex;

// --- UNIFORMS ---
uniform float uMR, uCR, uCZ, uA, uMZ, uAx, uC, uS, uHue, uSol, uBloom, uRGB, uMRGB, uWarp;
uniform vec2 uMT, uCT, uF, uMTilt, uCTilt;
uniform float uCurve, uTwist, uFlux;
uniform float uSShape, uSFov, uScroll, uMode;

// Helper für Hue Rotation
vec3 hueShift(vec3 color, float hue) {
    const vec3 k = vec3(0.57735, 0.57735, 0.57735);
    float cosAngle = cos(hue);
    return vec3(color * cosAngle + cross(k, color) * sin(hue) + k * dot(k, color) * (1.0 - cosAngle));
}

vec3 sampleCamera(vec2 uv, float rgbShift) {
    vec2 centered = uv - 0.5;
    float z = 1.0 + (centered.x * uCTilt.x) + (centered.y * uCTilt.y);
    centered /= max(z, 0.1);
    centered *= uCZ;
    float aspectFactor = mix(uA, 1.0, uWarp);
    centered.x *= aspectFactor;
    float cr = uCR * 0.01745329; 
    float c = cos(cr); float s = sin(cr);
    centered = vec2(centered.x * c - centered.y * s, centered.x * s + centered.y * c);
    centered.x /= aspectFactor;
    centered += uCT;
    vec2 rotatedUV = centered + 0.5;
    rotatedUV.x += rgbShift;
    rotatedUV = (rotatedUV - 0.5) * uF + 0.5;
    vec2 mirroredUV = abs(mod(rotatedUV + 1.0, 2.0) - 1.0);
    return texture2D(uTex, mirroredUV).rgb;
}

void main() {
    vec3 finalColor = vec3(0.0);
    float a1 = -uMR * 0.01745329; 
    float cosA1 = cos(a1); float sinA1 = sin(a1);
    
    float modeBlend = smoothstep(0.0, 1.0, uMode);
    vec2 effectiveTilt = mix(uMTilt, vec2(0.0), modeBlend);
    vec2 effectiveTrans = uMT + mix(vec2(0.0), uMTilt * 2.0, modeBlend);

    for(int i=0; i<3; i++) {
        float mOff = (i==0) ? uMRGB : (i==2) ? -uMRGB : 0.0;
        vec2 uv = v - 0.5;
        
        float zM = 1.0 + (uv.x * effectiveTilt.x) + (uv.y * effectiveTilt.y);
        uv /= max(zM, 0.1);
        uv.x *= uA; 
        uv.x += mOff;
        
        uv = (uv + effectiveTrans) * uMZ * 4.0;
        uv = vec2(uv.x * cosA1 - uv.y * sinA1, uv.x * sinA1 + uv.y * cosA1);
        
        if(uAx > 1.1) {
            float r = length(uv);
            float slice = 6.2831853 / uAx;
            float angle = atan(uv.y, uv.x);
            float a = mod(angle, slice);
            if(mod(uAx, 2.0) < 0.1) a = abs(a - slice * 0.5);
            uv = vec2(cos(a), sin(a)) * r;
        }

        float rCircle = length(uv);
        float rBox = max(abs(uv.x), abs(uv.y));
        float dist = mix(rCircle, rBox, uSShape);
        float angle = atan(uv.y, uv.x);
        dist += sin(angle * 4.0 + dist * 10.0) * uFlux * dist;
        float safeDist = max(dist, 0.01);
        
        float projection = (uSFov * 0.8 + 0.2) / safeDist;
        
        vec2 tunnelUV;
        tunnelUV.x = (angle + (1.0/safeDist) * uTwist) / 3.14159; 
        tunnelUV.y = projection + uScroll; 
        
        if(abs(uCurve - 1.0) > 0.01) tunnelUV *= 1.0 + (uCurve - 1.0) * (1.0 - safeDist);

        vec2 flatUV = uv;
        flatUV.x /= uA;
        
        vec2 mixedUV = mix(flatUV, tunnelUV * 0.8, modeBlend);
        
        vec2 cameraUV = abs(mod(mixedUV + 1.0, 2.0) - 1.0);
        float sOff = (i==0) ? uRGB : (i==2) ? -uRGB : 0.0;
        vec3 smp = sampleCamera(cameraUV, sOff);
        
        if(i==0) finalColor.r = smp.r; 
        else if(i==1) finalColor.g = smp.g; 
        else finalColor.b = smp.b;
    }
    
    // --- COLOR GRADING SECTION (Fixed) ---
    
    // 1. Negative / Invert (uSol)
    // Wenn uSol 0 ist -> keine Änderung. Wenn 1 -> Volles Invertieren.
    finalColor = abs(finalColor - uSol);

    // 2. Hue Shift (uHue)
    if(uHue > 0.01) {
        finalColor = hueShift(finalColor, uHue * 6.28318);
    }

    // 3. Contrast (uC)
    finalColor = (finalColor - 0.5) * uC + 0.5;
    
    // 4. Vibrance & BW (uS)
    float l = dot(finalColor, vec3(0.299, 0.587, 0.114));
    finalColor = mix(vec3(l), finalColor, uS);
    
    // 5. Glow / Bloom
    if(uBloom > 0.01) finalColor += smoothstep(0.4, 1.0, l) * finalColor * uBloom * 2.0;
    
    gl_FragColor = vec4(finalColor, 1.0);
}""".trimIndent()

            kaleidoProgram = createProgram(vSrc, fSrc)
            val activeUniforms = IntArray(1)
            GLES20.glGetProgramiv(kaleidoProgram, GLES20.GL_ACTIVE_UNIFORMS, activeUniforms, 0)
            val lenBuf = IntArray(1)
            val sizeBuf = IntArray(1)
            val typeBuf = IntArray(1)
            val nameBuf = ByteArray(256)

            for (i in 0 until activeUniforms[0]) {
                GLES20.glGetActiveUniform(kaleidoProgram, i, 256, lenBuf, 0, sizeBuf, 0, typeBuf, 0, nameBuf, 0)
                val name = String(nameBuf, 0, lenBuf[0])
                val loc = GLES20.glGetUniformLocation(kaleidoProgram, name)
                if (loc != -1) uLocs[name] = loc
            }

            // --- 2. COMPILE SIMPLE COPY SHADER ---
            val fSrcSimple = """
            precision mediump float;
            varying vec2 v;
            uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, v); }
            """.trimIndent()

            simpleProgram = createProgram(vSrc, fSrcSimple)
            simpleULocs["uTex"] = GLES20.glGetUniformLocation(simpleProgram, "uTex")

            // --- 3. SETUP TEXTURES & FBO ---
            cameraTexId = createOESTex()
            surfaceTexture = SurfaceTexture(cameraTexId)
            initFBO(fboWidth, fboHeight)
            pBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0) }
            tBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)).position(0) }
            ctx.runOnUiThread { ctx.startCamera() }
        }

        private fun initFBO(w: Int, h: Int) {
            val fb = IntArray(1); val tx = IntArray(1)
            GLES20.glGenFramebuffers(1, fb, 0)
            GLES20.glGenTextures(1, tx, 0)
            fboId = fb[0]; fboTexId = tx[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTexId, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            viewWidth = w; viewHeight = h
        }
// Ensure "var scrollAccum = 0.0f" exists at the top of KaleidoscopeRenderer

        private var deltaTime = 0.0f // Add this line

        override fun onDrawFrame(gl: GL10?) {
            val now = System.nanoTime()
            // Update the class-level variable so all functions can see it
            deltaTime = (now - lastTime) / 1e9f
            lastTime = now

            // Safety checks
            if (!ctx.controlsMap.containsKey("S_SHAPE") || !uLocs.containsKey("uSShape")) return
            try { surfaceTexture?.updateTexImage() } catch (e: Exception) { return }
            manageSurfaces()

            // 1. Update Physics
            updateMovementPhysics()

            // 2. Render to Framebuffer (FBO)
            renderToFBO()

            // 3. Output to screens and recorder
            renderToScreen()
            renderToExternal()
            renderToRecorder()

            handleCapture()
        }
        private fun resolveModulation(id: String): Float {
            val c = ctx.controlsMap[id] ?: return 0f
            val normValue = c.getNormalized()

            // Performance optimization
            if (!c.hasModulation || (c.modRate == 0 && c.modDepth == 0)) return normValue

            // Calculate LFO Drift
            val r = c.getModRateNormalized().toDouble()
            val tpi = 2.0 * PI
            // Use 'this.deltaTime'
            c.lfoDrift += (r * 0.4) * deltaTime.toDouble() * tpi

            val modSignal = sin(c.lfoDrift).toFloat() * c.getModDepthNormalized()
            val combined = normValue + modSignal

            if (c.modMode == PropertyControl.ModMode.WRAP) {
                return combined - floor(combined)
            } else {
                // Mirror Logic
                val t = combined % 2.0f
                val res = if (t < 0) t + 2.0f else t
                return if (res > 1.0f) 2.0f - res else res
            }
        }

        private fun updateMovementPhysics() {
            // Flight Speed (Curved)
            val speedCtrl = ctx.controlsMap["S_SPEED"]
            if (speedCtrl != null) {
                val rawVal = speedCtrl.getNormalized() - 0.5f
                val sign = sign(rawVal)
                val curvedSpeed = sign * (abs(rawVal) * 2.0f).pow(2.2f)
                scrollAccum += curvedSpeed * deltaTime * 0.6f
            }

            // Rotations (Explicit Float/Double casting to fix Ambiguity errors)
            val mRotCtrl = ctx.controlsMap["M_ROT"]
            if (mRotCtrl != null) {
                mRotAccum += mRotCtrl.getMapped(-1.5f, 1.5f).toDouble().pow(3.0) * 120.0 * deltaTime.toDouble()
            }

            val cRotCtrl = ctx.controlsMap["C_ROT"]
            if (cRotCtrl != null) {
                cRotAccum += cRotCtrl.getMapped(-1.5f, 1.5f).toDouble().pow(3.0) * 120.0 * deltaTime.toDouble()
            }
        }

        private fun renderToFBO() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glViewport(0, 0, fboWidth, fboHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(kaleidoProgram)

            fun safeUni(name: String, v: Float) { uLocs[name]?.let { GLES20.glUniform1f(it, v) } }
            fun safeUni2(name: String, v1: Float, v2: Float) { uLocs[name]?.let { GLES20.glUniform2f(it, v1, v2) } }

            // Now allows access to 'resolveModulation'
            val vMAngle = resolveModulation("M_ANGLE"); val vMZoom = resolveModulation("M_ZOOM")
            val vMTx = resolveModulation("M_TX"); val vMTy = resolveModulation("M_TY")
            val vMTiltX = resolveModulation("M_TILTX"); val vMTiltY = resolveModulation("M_TILTY")
            val v3DMix = resolveModulation("3D_MIX")

            safeUni("uAx", axisCount)
            safeUni("uA", fboWidth.toFloat() / fboHeight.toFloat())
            safeUni("uMR", (vMAngle * 360f + mRotAccum).toFloat() + 90f)
            safeUni("uMZ", 0.1f + (vMZoom * 2.5f))
            safeUni2("uMT", (vMTx - 0.5f) * 2f, (vMTy - 0.5f) * 2f)
            safeUni2("uMTilt", (vMTiltX - 0.5f) * 1.5f, (vMTiltY - 0.5f) * 1.5f)

            safeUni("uMode", v3DMix.pow(2.0f))
            safeUni("uScroll", scrollAccum)
            safeUni("uSShape", resolveModulation("S_SHAPE"))
            safeUni("uSFov", resolveModulation("S_FOV"))

            val cRaw = ctx.controlsMap["CURVE"]?.getMapped(0f, 1f) ?: 0.5f
            safeUni("uCurve", if (cRaw > 0.5f) 1.0f + (cRaw - 0.5f) * 6.0f else 0.2f + (cRaw * 1.6f))
            safeUni("uTwist", ctx.controlsMap["TWIST"]?.getMapped(-5.0f, 5.0f) ?: 0f)
            safeUni("uFlux", resolveModulation("FLUX") * 0.2f)

            val vCZoom = resolveModulation("C_ZOOM"); val vCAngle = resolveModulation("C_ANGLE")
            val vCTx = resolveModulation("C_TX"); val vCTy = resolveModulation("C_TY")
            val vCTiltX = resolveModulation("C_TILTX"); val vCTiltY = resolveModulation("C_TILTY")
            safeUni("uCZ", 0.3f + (vCZoom * 2.0f))
            safeUni("uCR", (vCAngle * 360f + cRotAccum).toFloat())
            safeUni2("uCT", (vCTx - 0.5f), (vCTy - 0.5f))
            safeUni2("uCTilt", (vCTiltX - 0.5f) * 1.2f, (vCTiltY - 0.5f) * 1.2f)
            safeUni2("uF", if (rot180) -flipX else flipX, if (rot180) -flipY else flipY)
            safeUni("uWarp", ctx.controlsMap["WARP"]?.getNormalized() ?: 0f)

            safeUni("uC", ctx.controlsMap["CONTRAST"]?.getMapped(0f, 2f) ?: 1f)
            safeUni("uS", ctx.controlsMap["VIBRANCE"]?.getMapped(0f, 2f) ?: 1f)
            safeUni("uHue", resolveModulation("HUE")); safeUni("uSol", resolveModulation("NEG"))
            safeUni("uBloom", resolveModulation("GLOW")); safeUni("uRGB", resolveModulation("RGB") * 0.05f)
            safeUni("uMRGB", resolveModulation("M_RGB") * 0.1f)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
            uLocs["uTex"]?.let { GLES20.glUniform1i(it, 0) }
            bindCommonAttribs(kaleidoProgram)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
        /**
         * Updates physics with a Quadratic Curve to prevent speed rushing.
         * Center stickiness makes 0.0 speed easier to hit.
         */
        private fun updateMovementPhysics(d: Float) {
            val speedCtrl = ctx.controlsMap["S_SPEED"] ?: return

            // 1. Get raw -0.5 to 0.5 range
            val rawVal = speedCtrl.getNormalized() - 0.5f
            val sign = sign(rawVal)

            // 2. Apply Quadratic Curve (x^2)
            // This makes small movements of the slider result in VERY small speed changes.
            // Large movements still allow for high speed.
            val curvedSpeed = sign * (abs(rawVal) * 2.0f).pow(2.2f)

            // 3. Accumulate
            // Reduced multiplier (1.2) for more controlled flight
            scrollAccum += curvedSpeed * d * 1.2f

            // Rotation Physics
            val mRotCtrl = ctx.controlsMap["M_ROT"] ?: return
            mRotAccum += mRotCtrl.getMapped(-1.5f, 1.5f).toDouble().pow(3.0) * 120.0 * d.toDouble()
            val cRotCtrl = ctx.controlsMap["C_ROT"] ?: return
            cRotAccum += cRotCtrl.getMapped(-1.5f, 1.5f).toDouble().pow(3.0) * 120.0 * d.toDouble()
        }

        private fun renderToScreen() {
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawSimpleTexture(fboTexId)
        }

        private fun renderToExternal() {
            if (extEglSurface != EGL14.EGL_NO_SURFACE) {
                val oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
                val oldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
                if (EGL14.eglMakeCurrent(mSavedDisplay, extEglSurface, extEglSurface, mSavedContext)) {
                    GLES20.glViewport(0, 0, extWidth, extHeight)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    drawSimpleTexture(fboTexId)
                    EGLExt.eglPresentationTimeANDROID(mSavedDisplay, extEglSurface!!, System.nanoTime())
                    EGL14.eglSwapBuffers(mSavedDisplay, extEglSurface)
                }
                EGL14.eglMakeCurrent(mSavedDisplay, oldDraw, oldRead, mSavedContext)
            }
        }

        private fun renderToRecorder() {
            if (recordSurface != EGL14.EGL_NO_SURFACE && videoRecorder != null) {
                val oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
                val oldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
                if (EGL14.eglMakeCurrent(mSavedDisplay, recordSurface, recordSurface, mSavedContext)) {
                    GLES20.glViewport(0, 0, viewWidth, viewHeight)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    drawSimpleTexture(fboTexId)
                    val timeNow = System.nanoTime()
                    if (recordStartTimeNs == 0L) recordStartTimeNs = timeNow
                    EGLExt.eglPresentationTimeANDROID(mSavedDisplay, recordSurface!!, timeNow - recordStartTimeNs)
                    EGL14.eglSwapBuffers(mSavedDisplay, recordSurface)
                    videoRecorder?.drain(false)
                }
                EGL14.eglMakeCurrent(mSavedDisplay, oldDraw, oldRead, mSavedContext)
                handleStopRecording()
            }
        }

        private fun drawSimpleTexture(texId: Int) {
            GLES20.glUseProgram(simpleProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(simpleULocs["uTex"]!!, 0)
            bindCommonAttribs(simpleProgram)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun bindCommonAttribs(prog: Int) {
            val pL = GLES20.glGetAttribLocation(prog, "p")
            val tL = GLES20.glGetAttribLocation(prog, "t")
            GLES20.glEnableVertexAttribArray(pL)
            GLES20.glVertexAttribPointer(pL, 2, GLES20.GL_FLOAT, false, 0, pBuf)
            GLES20.glEnableVertexAttribArray(tL)
            GLES20.glVertexAttribPointer(tL, 2, GLES20.GL_FLOAT, false, 0, tBuf)
        }

        private fun createProgram(v: String, f: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, v))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, f))
            GLES20.glLinkProgram(p)
            return p
        }
        private fun compile(t: Int, s: String) = GLES20.glCreateShader(t).apply { GLES20.glShaderSource(this, s); GLES20.glCompileShader(this) }
        private fun createOESTex(): Int { val t=IntArray(1); GLES20.glGenTextures(1,t,0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,t[0]); GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR); GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR); return t[0] }

        fun provideSurface(req: SurfaceRequest) { glView.queueEvent { surfaceTexture?.let { st -> st.setDefaultBufferSize(req.resolution.width, req.resolution.height); val s = Surface(st); req.provideSurface(s, ContextCompat.getMainExecutor(ctx)) { s.release() } } } }
        fun setExternalSurface(s: Surface, w: Int, h: Int) { extSurfaceArgs = Triple(s, w, h) }
        fun removeExternalSurface() { extSurfaceArgs = null }

        private fun setupEGL() {
            mSavedDisplay = EGL14.eglGetCurrentDisplay()
            mSavedContext = EGL14.eglGetCurrentContext()
            val currentConfigId = IntArray(1)
            EGL14.eglQueryContext(mSavedDisplay, mSavedContext, EGL14.EGL_CONFIG_ID, currentConfigId, 0)
            val configs = arrayOfNulls<EGL14EGLConfig>(1); val num = IntArray(1)
            EGL14.eglChooseConfig(mSavedDisplay, intArrayOf(EGL14.EGL_CONFIG_ID, currentConfigId[0], EGL14.EGL_NONE), 0, configs, 0, 1, num, 0)
            mEglConfig = configs[0]
        }

        private fun manageSurfaces() {
            if (extSurfaceArgs != null && extEglSurface == EGL14.EGL_NO_SURFACE) {
                val (rawSurf, w, h) = extSurfaceArgs!!
                extWidth = w; extHeight = h
                extEglSurface = EGL14.eglCreateWindowSurface(mSavedDisplay, mEglConfig, rawSurf, intArrayOf(EGL14.EGL_NONE), 0)
            }
            if (extSurfaceArgs == null && extEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(mSavedDisplay, extEglSurface); extEglSurface = EGL14.EGL_NO_SURFACE
            }
            if (pendingRecordFile != null) {
                videoRecorder = VideoRecorder(viewWidth, viewHeight, pendingRecordFile!!)
                recordSurface = EGL14.eglCreateWindowSurface(mSavedDisplay, mEglConfig, videoRecorder!!.inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
                pendingRecordFile = null
            }
        }

        fun updateTextureSize(width: Int, height: Int) {
            glView.queueEvent { surfaceTexture?.setDefaultBufferSize(width, height) }
        }

        // --- NON-RECURSIVE STOP FUNCTION ---
        private fun handleStopRecording() {
            if (isStopRequested) {
                videoRecorder?.drain(true)
                val out = videoRecorder?.file
                if (recordSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(mSavedDisplay, recordSurface)
                    recordSurface = EGL14.EGL_NO_SURFACE
                }
                videoRecorder?.release()
                videoRecorder = null
                isStopRequested = false
                onStopCallback?.invoke(out)
            }
        }

        private fun handleCapture() {
            if (captureRequested) {
                captureRequested = false
                val b = ByteBuffer.allocate(fboWidth * fboHeight * 4)
                GLES20.glReadPixels(0, 0, fboWidth, fboHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
                Thread {
                    val bmp = Bitmap.createBitmap(fboWidth, fboHeight, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(b) }
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "SB_${System.currentTimeMillis()}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SpaceBeam")
                    }
                    ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    }
                }.start()
            }
        }
    }
}

class VideoRecorder(val width: Int, val height: Int, val file: File) {

    // REMOVED: private val bufferInfo = MediaCodec.BufferInfo() (This was causing the crash)

    private var muxer: MediaMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var muxerStarted = false

    // --- VIDEO VARIABLES ---
    private var videoEncoder: MediaCodec
    val inputSurface: Surface
    private var videoTrackIndex = -1

    // --- AUDIO VARIABLES ---
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrackIndex = -1
    private var audioThread: Thread? = null
    private var isRecording = true

    // Audio configuration
    private val sampleRate = 44100
    private val channelCount = 1
    private val audioBitRate = 128000

    init {
        // 1. Setup Video Encoder
        // Ensure even dimensions (required by some codecs)
        val w = if (width % 2 == 0) width else width - 1
        val h = if (height % 2 == 0) height else height - 1

        val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = videoEncoder.createInputSurface()
        videoEncoder.start()

        // 2. Setup Audio Encoder & Recorder
        setupAudio()
    }

    private fun setupAudio() {
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = minBufferSize * 4

            try {
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            } catch (e: SecurityException) {
                Log.e("VideoRecorder", "Permission denied for AudioRecord")
                audioRecord = null
                return
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("VideoRecorder", "AudioRecord failed to initialize")
                audioRecord = null
                return
            }

            val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, audioBitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioEncoder?.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioEncoder?.start()

            isRecording = true
            audioRecord?.startRecording()

            audioThread = Thread { audioLoop() }
            audioThread?.start()

        } catch (e: Exception) {
            Log.e("VideoRecorder", "Audio setup crashed", e)
            audioEncoder = null
            audioRecord = null
        }
    }

    // Called by Renderer to drain VIDEO frames
    fun drain(endOfStream: Boolean) {
        if (endOfStream) {
            try { videoEncoder.signalEndOfInputStream() } catch (e: Exception) { }
        }
        drainEncoder(videoEncoder, isVideo = true)
    }

    private fun audioLoop() {
        val buffer = ByteArray(2048)
        var totalBytesRead = 0L

        while (isRecording && audioEncoder != null && audioRecord != null) {
            val readBytes = audioRecord!!.read(buffer, 0, buffer.size)
            if (readBytes > 0) {
                totalBytesRead += readBytes

                try {
                    val inputBufferIndex = audioEncoder!!.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = audioEncoder!!.getInputBuffer(inputBufferIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(buffer, 0, readBytes)

                        // Timestamp calculation
                        val pts = (totalBytesRead * 1_000_000L) / (sampleRate * 2)

                        audioEncoder!!.queueInputBuffer(inputBufferIndex, 0, readBytes, pts, 0)
                    }
                    drainEncoder(audioEncoder!!, isVideo = false)
                } catch (e: Exception) {
                    Log.e("VideoRecorder", "Audio encoding error", e)
                }
            }
        }
    }

    private fun drainEncoder(encoder: MediaCodec, isVideo: Boolean) {
        val timeoutUs = if (isVideo) 0L else 10000L

        // FIX: Create a local BufferInfo.
        // This ensures the Audio Thread and Video Thread do not overwrite each other's data.
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val idx = try { encoder.dequeueOutputBuffer(bufferInfo, timeoutUs) } catch (e: Exception) { -1 }

            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!isVideo && !isRecording) break
                break
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(this) {
                    if (muxerStarted) return
                    val newFormat = encoder.outputFormat
                    if (isVideo) {
                        videoTrackIndex = muxer.addTrack(newFormat)
                    } else {
                        audioTrackIndex = muxer.addTrack(newFormat)
                    }
                    startMuxerIfReady()
                }
            } else if (idx >= 0) {
                val encodedData = encoder.getOutputBuffer(idx) ?: continue

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }

                if (bufferInfo.size != 0) {
                    synchronized(this) {
                        if (muxerStarted) {
                            val trackIndex = if (isVideo) videoTrackIndex else audioTrackIndex

                            if (trackIndex >= 0) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                        }
                    }
                }
                encoder.releaseOutputBuffer(idx, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    private fun startMuxerIfReady() {
        val audioReady = (audioEncoder == null) || (audioTrackIndex >= 0)
        val videoReady = (videoTrackIndex >= 0)

        if (videoReady && audioReady && !muxerStarted) {
            muxer.start()
            muxerStarted = true
        }
    }

    fun release() {
        isRecording = false
        try { audioThread?.join(500) } catch (e: Exception) {}

        try {
            if (muxerStarted) muxer.stop()
            muxer.release()

            videoEncoder.stop()
            videoEncoder.release()
            inputSurface.release()

            audioEncoder?.stop()
            audioEncoder?.release()
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("VideoRecorder", "Cleanup failed", e)
        }
    }
}