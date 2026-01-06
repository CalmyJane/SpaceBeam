package com.calmyjane.spacebeam


import android.Manifest
import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.Context
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
import javax.microedition.khronos.egl.EGLConfig as GL10EGLConfig
import android.opengl.EGLConfig as EGL14EGLConfig
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.C

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
            if (presentation != null && presentation!!.display.displayId == externalDisplay.displayId) {
                // Optional: You might want to update the renderer's surface size if the *external* display changed res
                // but usually, we just return here to be safe.
                return
            }
            // Dismiss old one if display changed
            try { presentation?.dismiss() } catch(e: Exception) {}
            presentation = null
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
            try { presentation?.dismiss() } catch(e: Exception) {}
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
    enum class WaveShape { SINE, WOBBLE_SINE, POLY_SINE, RAMP, TRIANGLE, SMOOTH_NOISE, ROUGH_NOISE }

    companion object {
        var activeControl: PropertyControl? = null
        fun closeActiveMenu() {
            activeControl?.closeMenu()
        }
    }

    // --- State Variables ---
    @Volatile var value: Int = defaultValue
        private set
    @Volatile var preciseValue: Float = defaultValue.toFloat()
        private set

    // --- Animation State ---
    private var animTarget: Float? = null
    private var animStart: Float = 0f
    private var animDuration: Float = 0f
    private var animTime: Float = 0f
    private var isAnimating = false

    // --- Modulation State ---
    var modRate: Int = 200
    var modDepth: Int = 0
    var modShape: WaveShape = WaveShape.SINE

    // Physics / Internal
    var preciseModRate: Float = 200f
    var preciseModDepth: Float = 0f
    var lfoPhase: Double = 0.0
    private var lastComputedModulation: Float = 0f

    // --- Snapshot Morphing Variables ---
    private var modSnapshotValue: Float = 0f
    private var modRateStart = 0f
    private var modRateTarget: Float? = null
    private var modDepthStart = 0f
    private var modDepthTarget: Float? = null

    val computedValue: Float
        get() = applyModulation(getNormalized())

    // --- UI References ---
    private var mainSeekBar: SeekBar? = null
    private var modIndicator: View? = null
    private var mainRowLayout: LinearLayout? = null
    private var floatingPanel: LinearLayout? = null

    // UI References for the open modulation menu
    private var modPanelSpeedSeekBar: SeekBar? = null
    private var modPanelDepthSeekBar: SeekBar? = null
    private var modPanelShapeSpinner: Spinner? = null

    data class Snapshot(
        val value: Int,
        val active: Boolean,
        val rate: Int,
        val depth: Int,
        val shape: String
    )

    fun getSnapshot(): Snapshot = Snapshot(value, modDepth > 0, modRate, modDepth, modShape.name)

    fun restore(s: Snapshot, durationSec: Float) {
        animateTo(s.value.toFloat(), durationSec, s.shape)
        if (hasModulation) {
            animateModulation(s.rate.toFloat(), s.depth.toFloat(), durationSec)
        }
    }

    fun animateTo(target: Float, durationSec: Float, newShape: String? = null) {
        // Capture current state for blending
        modSnapshotValue = lastComputedModulation

        animTarget = target
        animStart = preciseValue
        animDuration = durationSec
        animTime = 0f
        isAnimating = true

        if (newShape != null) {
            try {
                val targetShape = WaveShape.valueOf(newShape)
                modShape = targetShape
                // Sync the spinner if the menu is open
                modPanelShapeSpinner?.setSelection(targetShape.ordinal)
            } catch (e: Exception) {}
        }
    }

    fun animateModulation(targetRate: Float, targetDepth: Float, durationSec: Float) {
        modRateStart = preciseModRate
        modRateTarget = targetRate
        modDepthStart = preciseModDepth
        modDepthTarget = targetDepth
    }

    fun update(deltaTime: Float) {
        val t = if (isAnimating && animDuration > 0) (animTime / animDuration).coerceIn(0f, 1f) else 1f
        val ease = 1f - (1f - t).pow(3f)

        // 1. Handle Parameter Transitions
        if (isAnimating && animTarget != null) {
            animTime += deltaTime
            if (animTime >= animDuration) {
                preciseValue = animTarget!!
                value = preciseValue.toInt()
                modRateTarget?.let { preciseModRate = it; modRate = it.toInt() }
                modDepthTarget?.let { preciseModDepth = it; modDepth = it.toInt() }
                modRateTarget = null; modDepthTarget = null
                isAnimating = false
                mainSeekBar?.post { if (mainSeekBar?.progress != value) mainSeekBar?.progress = value }
            } else {
                preciseValue = animStart + (animTarget!! - animStart) * ease
                modRateTarget?.let { preciseModRate = modRateStart + (it - modRateStart) * ease }
                modDepthTarget?.let { preciseModDepth = modDepthStart + (it - modDepthStart) * ease }
            }

            // Sync the open modulation panel UI elements
            modPanelSpeedSeekBar?.progress = preciseModRate.toInt()
            modPanelDepthSeekBar?.progress = preciseModDepth.toInt()
        }

        // 2. LFO Physics
        if (!hasModulation || (preciseModRate == 0f && preciseModDepth == 0f && modDepthTarget == null)) {
            lastComputedModulation = 0f
            return
        }

        val baseSpeed = (preciseModRate / 1000f + 0.05f).pow(3f)
        lfoPhase += baseSpeed * deltaTime * 2.0 * Math.PI

        val currentWave = when (modShape) {
            WaveShape.SINE -> sin(lfoPhase)
            WaveShape.WOBBLE_SINE -> sin(lfoPhase) * sin(lfoPhase * 0.5 + 0.5)
            WaveShape.POLY_SINE -> (sin(lfoPhase) + sin(lfoPhase * 1.5)) * 0.5
            WaveShape.RAMP -> ((lfoPhase / (2.0 * Math.PI)) % 1.0 * 2.0 - 1.0)
            WaveShape.TRIANGLE -> {
                val phase = (lfoPhase / (2.0 * Math.PI)) % 1.0
                if (phase < 0.5) (phase * 4.0 - 1.0) else (3.0 - phase * 4.0)
            }
            WaveShape.SMOOTH_NOISE -> (sin(lfoPhase) + sin(lfoPhase * 2.3) * 0.5 + sin(lfoPhase * 4.7) * 0.25) / 1.75
            WaveShape.ROUGH_NOISE -> (Math.random() * 2.0 - 1.0)
        }

        val depthNorm = (preciseModDepth / 1000f).pow(3f)
        val newModValue = (currentWave * depthNorm).toFloat()

        // 3. Blending Logic (Handover)
        lastComputedModulation = if (isAnimating) {
            (modSnapshotValue * (1.0f - ease)) + (newModValue * ease)
        } else {
            newModValue
        }

        modIndicator?.postInvalidate()
    }

    private fun applyModulation(baseNorm: Float): Float {
        if (!hasModulation) return baseNorm
        return (baseNorm + lastComputedModulation).coerceIn(0f, 1f)
    }

    fun setProgress(v: Int) {
        if (isAnimating) stopAnimation()
        if (activeControl != null && activeControl != this) closeActiveMenu()
        val clamped = v.coerceIn(min, max)
        value = clamped
        preciseValue = clamped.toFloat()
        if (mainSeekBar != null && mainSeekBar!!.progress != clamped) {
            mainSeekBar!!.progress = clamped
        }
        onValueChanged?.invoke(clamped)
    }

    fun updateModRate(v: Int) {
        modRate = v.coerceIn(0, 1000)
        preciseModRate = modRate.toFloat()
    }

    fun updateModDepth(v: Int) {
        modDepth = v.coerceIn(0, 1000)
        preciseModDepth = modDepth.toFloat()
        updateIndicatorVisuals()
    }

    fun setAnimatedValue(v: Float) {
        preciseValue = v.coerceIn(min.toFloat(), max.toFloat())
        val intVal = preciseValue.toInt()
        if (intVal != value) {
            value = intVal
            mainSeekBar?.post { if (mainSeekBar?.progress != value) mainSeekBar?.progress = value }
        }
    }

    fun reset() {
        stopAnimation()
        setProgress(defaultValue)
        if (hasModulation) {
            updateModRate(200)
            updateModDepth(0)
            modShape = WaveShape.SINE
            updateIndicatorVisuals()
        }
    }

    fun getNormalized(): Float = preciseValue / max.toFloat()
    fun getMapped(outMin: Float, outMax: Float): Float = outMin + (computedValue * (outMax - outMin))
    fun getModDepthNormalized(): Float = (preciseModDepth / 1000f).pow(3f)

    fun attachTo(parent: ViewGroup) {
        mainSeekBar = null
        modIndicator = null
        mainRowLayout = null

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 2, 0, 6)
        }

        val labelView = TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            alpha = 0.85f
            setOnClickListener { reset() }
        }
        container.addView(labelView)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 55)
        }
        this.mainRowLayout = row

        val sb = SeekBar(context).apply {
            max = this@PropertyControl.max
            progress = value
            thumb = GradientDrawable().apply {
                setColor(Color.WHITE)
                setSize(30, 30)
                cornerRadius = 15f
            }
            thumbOffset = 0
            splitTrack = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        this.mainSeekBar = sb

        sb.setOnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            if ((event.action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) {
                stopAnimation()
                if (activeControl != null && activeControl != this@PropertyControl) {
                    closeActiveMenu()
                }
            }
            if ((event.action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
                v.parent.requestDisallowInterceptTouchEvent(false)
            }
            v.onTouchEvent(event)
            true
        }

        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) setProgress(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        row.addView(sb)

        if (hasModulation) {
            modIndicator = object : View(context) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(canvas: Canvas) {
                    val cx = width / 2f
                    val cy = height / 2f
                    val r = (Math.min(width, height) / 2f) - 2f
                    val active = modDepth > 0
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 3f
                    paint.color = Color.WHITE
                    paint.alpha = 255
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.style = Paint.Style.FILL
                    paint.color = if (active) Color.WHITE else Color.LTGRAY
                    paint.alpha = if (active) 255 else 100
                    var dotRadius = r * 0.3f
                    if (active) {
                        val depthN = getModDepthNormalized().coerceAtLeast(0.001f)
                        val rawWave = lastComputedModulation / depthN
                        val sizeFactor = ((rawWave + 1.0) / 2.0) * 0.8 + 0.1
                        dotRadius = (r * sizeFactor).toFloat()
                    }
                    canvas.drawCircle(cx, cy, dotRadius, paint)
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(55, 55).apply { leftMargin = 15 }
                setOnClickListener { toggleMenu() }
            }
            row.addView(modIndicator)
        }
        container.addView(row)
        parent.addView(container)
    }

    private fun updateIndicatorVisuals() { modIndicator?.invalidate() }

    fun toggleMenu() {
        if (activeControl == this) closeMenu() else { activeControl?.closeMenu(); openMenu() }
    }

    private fun openMenu() {
        val activity = context as? MainActivity ?: return
        val rootLayout = activity.overlayHUD
        val dm = context.resources.displayMetrics
        val isPortrait = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

        floatingPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            background = GradientDrawable().apply {
                setColor(Color.argb(240, 20, 20, 20))
                cornerRadius = 20f
                setStroke(2, Color.GRAY)
            }
            elevation = 30f
            isClickable = true
            layoutParams = if (isPortrait) {
                val menuHeight = (dm.heightPixels * 0.40).toInt()
                FrameLayout.LayoutParams(700, -2).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = menuHeight + 20 }
            } else {
                FrameLayout.LayoutParams(600, -2).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START; leftMargin = 880 }
            }
        }

        floatingPanel?.addView(TextView(context).apply {
            text = "$label MODULATION"; textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 15 }
        })

        val shapeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 } }
        shapeRow.addView(TextView(context).apply { text="SHAPE"; textSize=10f; setTextColor(Color.LTGRAY); layoutParams=LinearLayout.LayoutParams(120, -2) })

        modPanelShapeSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, WaveShape.values())
            setSelection(modShape.ordinal)
            background.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP)
            layoutParams = LinearLayout.LayoutParams(0, 70, 1f)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) { modShape = WaveShape.values()[pos]; (p0?.getChildAt(0) as? TextView)?.setTextColor(Color.WHITE) }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }
        shapeRow.addView(modPanelShapeSpinner)
        floatingPanel?.addView(shapeRow)

        modPanelSpeedSeekBar = addSliderToPanel("SPEED", modRate) { updateModRate(it) }
        modPanelDepthSeekBar = addSliderToPanel("DEPTH", modDepth) { updateModDepth(it); updateIndicatorVisuals() }

        rootLayout.addView(floatingPanel)
        activeControl = this
    }

    private fun addSliderToPanel(name: String, current: Int, onChange: (Int) -> Unit): SeekBar {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 10, 0, 10) }
        row.addView(TextView(context).apply { text=name; textSize=10f; setTextColor(Color.LTGRAY); layoutParams=LinearLayout.LayoutParams(120, -2) })
        val sb = SeekBar(context).apply {
            max = 1000; progress = current; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(30, 30); cornerRadius = 15f }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { if (f) onChange(p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        row.addView(sb)
        floatingPanel?.addView(row)
        return sb
    }

    fun stopAnimation() { isAnimating = false; animTarget = null; modRateTarget = null; modDepthTarget = null }

    fun closeMenu() {
        if (floatingPanel != null) {
            (context as? MainActivity)?.overlayHUD?.removeView(floatingPanel)
            floatingPanel = null
            modPanelSpeedSeekBar = null
            modPanelDepthSeekBar = null
            modPanelShapeSpinner = null
        }
        if (activeControl == this) activeControl = null
    }
}


// --- MAIN ACTIVITY ---
class MainActivity : AppCompatActivity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: KaleidoscopeRenderer
    private var currentSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    lateinit var overlayHUD: FrameLayout
    private lateinit var displayHelper: ExternalDisplayHelper
    private lateinit var axisSb: SeekBar
    val controls = java.util.concurrent.CopyOnWriteArrayList<PropertyControl>()
    val controlsMap = mutableMapOf<String, PropertyControl>()
    private val presetButtons = mutableMapOf<Int, Button>()
    private lateinit var menuBtn: Button
    // private var currentAnimator: ValueAnimator? = null // REMOVED
    private var activePreset: Int = -1
    private lateinit var flipXBtn: ImageButton
    private lateinit var flipYBtn: ImageButton
    private lateinit var rot180Btn: ImageButton
    private lateinit var readabilityBtn: ImageButton
    private lateinit var resetBtn: ImageButton
    private lateinit var photoBtn: ImageButton
    private lateinit var recordBtn: ImageButton
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
    private var lastRtspUrl: String = "rtsp://wowzaec2demo.streamlock.net/vod/mp4:BigBuckBunny_115k.mp4"

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
    private lateinit var orientationBtn: ImageButton
    private var isOrientationLocked = false

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val activeControlId = PropertyControl.activeControl?.id
        PropertyControl.closeActiveMenu()
        overlayHUD.removeAllViews()
        setupOverlayHUD()
        applyReadabilityStyle()
        updateSidebarVisuals()
        if (activeControlId != null && controlsMap.containsKey(activeControlId)) {
            handler.postDelayed({ controlsMap[activeControlId]?.toggleMenu() }, 50)
        }
    }

    private val mediaPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, flags)
                } catch (e: Exception) {}
                startLocalMedia(uri)
            }
        }
    }

    private fun startLocalMedia(uri: android.net.Uri) {
        val cpFuture = ProcessCameraProvider.getInstance(this)
        cpFuture.addListener({ try { cpFuture.get().unbindAll() } catch (e: Exception) {} }, ContextCompat.getMainExecutor(this))
        val mimeType = contentResolver.getType(uri)
        val isImage = mimeType?.startsWith("image") == true

        glView.queueEvent {
            renderer.resetVideoTexture()
            val surface = renderer.getPlayerSurface() ?: return@queueEvent
            runOnUiThread {
                if (isImage) {
                    try {
                        exoPlayer?.stop()
                        exoPlayer?.clearVideoSurface()
                        val inputStream = contentResolver.openInputStream(uri)
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()
                        if (bitmap != null) {
                            renderer.updateTextureSize(bitmap.width, bitmap.height)
                            val canvas = surface.lockCanvas(null)
                            canvas.drawColor(Color.BLACK)
                            val destRect = Rect(0, 0, canvas.width, canvas.height)
                            val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                            canvas.drawBitmap(bitmap, srcRect, destRect, null)
                            surface.unlockCanvasAndPost(canvas)
                            isRtspMode = true
                            Toast.makeText(this, "Image Loaded", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) { Log.e("Media", "Image Load Failed", e) }
                } else {
                    if (exoPlayer == null) exoPlayer = ExoPlayer.Builder(this).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)).build()
                    exoPlayer?.stop()
                    exoPlayer?.clearVideoSurface()
                    exoPlayer?.setVideoSurface(surface)
                    exoPlayer?.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                    val mediaItem = MediaItem.fromUri(uri)
                    exoPlayer?.setMediaItem(mediaItem)
                    exoPlayer?.repeatMode = Player.REPEAT_MODE_ONE
                    exoPlayer?.volume = 0f
                    exoPlayer?.addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            if (videoSize.width > 0 && videoSize.height > 0) renderer.updateTextureSize(videoSize.width, videoSize.height)
                        }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Toast.makeText(this@MainActivity, "Video Error: Try a different file", Toast.LENGTH_SHORT).show()
                        }
                    })
                    exoPlayer?.prepare()
                    exoPlayer?.play()
                    isRtspMode = true
                    Toast.makeText(this, "Playing Video", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        hideSystemUI()

        renderer = KaleidoscopeRenderer(this)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        glView.setOnTouchListener { _, event ->
            // Clear save confirmation if user touches anywhere
            if (event.action == MotionEvent.ACTION_DOWN && saveConfirmBtn.visibility == View.VISIBLE) {
                saveConfirmBtn.visibility = View.GONE
                pendingSaveIndex = null
            }

            // Pass the event to our unified interaction handler
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
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 10)
        } else {
            startCamera()
        }
    }

    fun startCamera() {
        stopRtsp()
        isRtspMode = false
        val cpFuture = ProcessCameraProvider.getInstance(this)
        cpFuture.addListener({
            val provider = cpFuture.get()
            provider.unbindAll()
            glView.queueEvent {
                renderer.resetVideoTexture()
                runOnUiThread {
                    val preview = Preview.Builder().setTargetRotation(Surface.ROTATION_90).build()
                    preview.setSurfaceProvider { req -> renderer.provideSurface(req) }
                    try { provider.bindToLifecycle(this, currentSelector, preview) } catch (e: Exception) { Log.e("Camera", "Bind failed", e) }
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRtsp(url: String) {
        val cpFuture = ProcessCameraProvider.getInstance(this)
        cpFuture.addListener({ try { cpFuture.get().unbindAll() } catch (e: Exception) {} }, ContextCompat.getMainExecutor(this))
        glView.queueEvent {
            renderer.resetVideoTexture()
            val surface = renderer.getPlayerSurface()
            runOnUiThread {
                if (surface != null) {
                    if (exoPlayer == null) exoPlayer = ExoPlayer.Builder(this).build()
                    exoPlayer?.volume = 0f
                    exoPlayer?.stop()
                    exoPlayer?.clearVideoSurface()
                    exoPlayer?.setVideoSurface(surface)
                    val rtspSource = RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(5000).createMediaSource(MediaItem.fromUri(url))
                    exoPlayer?.setMediaSource(rtspSource)
                    exoPlayer?.addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            if (videoSize.width > 0 && videoSize.height > 0) renderer.updateTextureSize(videoSize.width, videoSize.height)
                        }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) { Toast.makeText(this@MainActivity, "Stream Error: ${error.message}", Toast.LENGTH_LONG).show() }
                    })
                    exoPlayer?.prepare()
                    exoPlayer?.play()
                    isRtspMode = true
                    lastRtspUrl = url
                    Toast.makeText(this, "Connecting (TCP)...", Toast.LENGTH_SHORT).show()
                } else { Toast.makeText(this, "Renderer not ready", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun stopRtsp() {
        exoPlayer?.stop()
        exoPlayer?.clearVideoSurface()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
        displayHelper.stop()
    }

    private fun handleInteraction(event: MotionEvent) {
        if (event.pointerCount >= 2) {
            val p1x = event.getX(0); val p1y = event.getY(0)
            val p2x = event.getX(1); val p2y = event.getY(1)
            val focusX = (p1x + p2x) / 2f; val focusY = (p1y + p2y) / 2f
            val dist = hypot(p1x - p2x, p1y - p2y)
            val angle = Math.toDegrees(atan2((p1y - p2y).toDouble(), (p1x - p2x).toDouble())).toFloat()
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val dx = (focusX - lastFingerFocusX) / glView.width.toFloat() * 2.0f
                val dy = (focusY - lastFingerFocusY) / glView.height.toFloat() * 2.0f
                controlsMap["M_TX"]?.let { it.setProgress((it.value - (dx * 500).toInt()).coerceIn(0, 1000)) }
                controlsMap["M_TY"]?.let { it.setProgress((it.value + (dy * 500).toInt()).coerceIn(0, 1000)) }
                val scaleFactor = dist / lastFingerDist
                if (scaleFactor > 0) {
                    controlsMap["M_ZOOM"]?.let { it.setProgress((it.value - (log2(scaleFactor) * 300).toInt()).coerceIn(0, 1000)) }
                }
                val dAngle = angle - lastFingerAngle
                controlsMap["M_ANGLE"]?.let { it.setProgress((it.value - (dAngle * (1000f / 360f)).toInt() + 1000) % 1000) }
            }
            lastFingerDist = dist; lastFingerAngle = angle; lastFingerFocusX = focusX; lastFingerFocusY = focusY
        } else if (event.action == MotionEvent.ACTION_UP) {
            if (PropertyControl.activeControl != null) {
                // Action 1: If a menu is open, close it.
                PropertyControl.closeActiveMenu()
            } else {
                // Action 2: If no menu is open, toggle the HUD.
                toggleHud()
            }
        }
    }

    private fun textToIcon(t: String, size: Float = 60f, color: Int = Color.WHITE): BitmapDrawable {
        val b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint().apply { this.color = color; textSize = size; textAlign = Paint.Align.CENTER; isFakeBoldText = true; isAntiAlias = true }
        c.drawText(t, 80f, 80f + (size / 3f), p); return BitmapDrawable(resources, b)
    }

    private fun createClockDrawable(): BitmapDrawable {
        val b = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true }
        c.drawCircle(50f, 55f, 35f, p); c.drawLine(50f, 55f, 50f, 35f, p); c.drawLine(50f, 55f, 65f, 55f, p)
        c.drawLine(40f, 15f, 60f, 15f, p); c.drawLine(50f, 15f, 50f, 20f, p); return BitmapDrawable(resources, b)
    }

    private fun createLogoDrawable(): ShapeDrawable {
        val p = Path().apply { moveTo(46f, 131f); lineTo(46f, 162f); lineTo(159f, 162f); lineTo(159f, 144f); lineTo(64f, 144f); lineTo(64f, 131f); close() }
        return ShapeDrawable(PathShape(p, 200f, 200f)).apply { paint.color = Color.WHITE; paint.isAntiAlias = true }
    }

    private fun createLockDrawable(locked: Boolean): BitmapDrawable {
        val b = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 8f; isAntiAlias = true }
        val shackle = RectF(30f, 20f, 70f, 60f); if (locked) c.drawArc(shackle, 180f, 180f, false, p) else c.drawArc(shackle, 160f, 180f, false, p)
        p.style = Paint.Style.FILL; c.drawRoundRect(RectF(25f, 50f, 75f, 85f), 8f, 8f, p); return BitmapDrawable(resources, b)
    }

    private fun setupOverlayHUD() {
        val isPortrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        overlayHUD = FrameLayout(this).apply { layoutParams = FrameLayout.LayoutParams(-1, -1) }
        flashOverlay = createFlashView()
        val logoView = createLogoView()
        setupParameterMenu()
        val cameraPanel = createCameraSettingsPanel()
        val recordPanel = createRecordControls()
        val presetPanel = createPresetPanel()

        val readabilityBtn = createReadabilityButton()
        val resetBtn = createResetButton()
        val orientationBtn = createOrientationButton() // NEW

        overlayHUD.addView(flashOverlay)
        overlayHUD.addView(logoView)
        overlayHUD.addView(leftHUDContainer)

        val recordParams = FrameLayout.LayoutParams(-2, -2).apply {
            if (isPortrait) {
                recordPanel.orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM or Gravity.START; bottomMargin = 450; leftMargin = 30
                (recordBtn.layoutParams as LinearLayout.LayoutParams).apply { topMargin = 40; leftMargin = 0 }
            } else {
                recordPanel.orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = 30; leftMargin = 250;
                (recordBtn.layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0; leftMargin = 30 }
            }
        }
        overlayHUD.addView(recordPanel, recordParams)

        val presetParams = FrameLayout.LayoutParams(-2, -2).apply {
            if (isPortrait) {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = 60; presetPanel.scaleX = 0.85f; presetPanel.scaleY = 0.85f
            } else {
                gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 15; rightMargin = 400; presetPanel.scaleX = 1.0f; presetPanel.scaleY = 1.0f
            }
        }
        overlayHUD.addView(presetPanel, presetParams)

        // --- UPDATED LAYOUT FOR UTILITY BUTTONS ---
        val baseBottom = 30; val baseRight = 30

        // 1. Reset (Bottom)
        resetBtn.layoutParams = (resetBtn.layoutParams as FrameLayout.LayoutParams).apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = baseBottom; rightMargin = baseRight }
        overlayHUD.addView(resetBtn)

        // 2. Readability (Above Reset)
        readabilityBtn.layoutParams = (readabilityBtn.layoutParams as FrameLayout.LayoutParams).apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = baseBottom + 120; rightMargin = baseRight }
        overlayHUD.addView(readabilityBtn)

        // 3. Orientation Lock (Above Readability) - NEW
        orientationBtn.layoutParams = (orientationBtn.layoutParams as FrameLayout.LayoutParams).apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = baseBottom + 240; rightMargin = baseRight }
        overlayHUD.addView(orientationBtn)

        val cameraParams = FrameLayout.LayoutParams(-2, -2).apply {
            if (isPortrait) { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = baseBottom + 480; rightMargin = 20 }
            else { gravity = Gravity.TOP or Gravity.END; topMargin = 40; rightMargin = 40 }
        }
        overlayHUD.addView(cameraPanel, cameraParams)
        addContentView(overlayHUD, ViewGroup.LayoutParams(-1, -1))
        updateSidebarVisuals()
    }

    private fun setupParameterMenu() {
        val isPortrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val dm = resources.displayMetrics

        // The root container remains transparent and doesn't clip
        leftHUDContainer = LinearLayout(this).apply {
            if (isPortrait) {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(-1, -2).apply {
                    gravity = Gravity.TOP
                }
            } else {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = FrameLayout.LayoutParams(-2, -1).apply {
                    gravity = Gravity.START
                }
            }
            clipChildren = false
            clipToPadding = false
        }

        // Move the background logic here so it disappears when this panel is GONE
        parameterPanel = ScrollView(this).apply {
            if (isPortrait) {
                layoutParams = LinearLayout.LayoutParams(-1, (dm.heightPixels * 0.40).toInt())
            } else {
                layoutParams = LinearLayout.LayoutParams(850, -1)
            }

            // This makes the background disappear with the menu
            id = View.generateViewId()
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            visibility = if (isMenuExpanded) View.VISIBLE else View.GONE
        }

        val menuLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(25, 20, 10, if (isPortrait) 20 else 240)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }
        }
        parameterPanel.addView(menuLayout)

        val toggleBtn = createMenuUtilityButton()

        if (isPortrait) {
            // Portrait: Menu on top, Button floating below it on the RIGHT
            leftHUDContainer.addView(parameterPanel)
            leftHUDContainer.addView(toggleBtn, LinearLayout.LayoutParams(120, 120).apply {
                gravity = Gravity.END
                topMargin = 10
                rightMargin = 30
            })
        } else {
            // Landscape: Menu on left, Button floating on the TOP RIGHT of it
            leftHUDContainer.addView(parameterPanel)
            leftHUDContainer.addView(toggleBtn, LinearLayout.LayoutParams(120, 120).apply {
                gravity = Gravity.TOP
                leftMargin = 10
                topMargin = 50
            })
        }

        populateParameterGroups(menuLayout)
    }
    private fun populateParameterGroups(menuLayout: LinearLayout) {
        var currentGroupContent: LinearLayout? = null
        fun createGroup(title: String, startOpen: Boolean = false) {
            val (container, content) = createCollapsibleGroupView(title, startOpen)
            menuLayout.addView(container)
            currentGroupContent = content
        }
        fun addControl(c: PropertyControl) {
            if (controlsMap.containsKey(c.id)) {
                val existingControl = controlsMap[c.id]!!
                currentGroupContent?.let { existingControl.attachTo(it) } ?: existingControl.attachTo(menuLayout)
            } else {
                controls.add(c)
                controlsMap[c.id] = c
                currentGroupContent?.let { c.attachTo(it) } ?: c.attachTo(menuLayout)
            }
        }
        createGroup("GEOMETRY", startOpen = true)
        setupGeometrySpecifics(currentGroupContent!!)

        createGroup("3D")
        addControl(PropertyControl(this, "3D_MIX", "STRENGTH", defaultValue = 0, hasModulation = true))
        addControl(PropertyControl(this, "S_SHAPE", "SHAPE", defaultValue = 0, hasModulation = true))
        addControl(PropertyControl(this, "S_SPEED", "SPEED", defaultValue = 500, hasModulation = true))
        addControl(PropertyControl(this, "S_FOV", "FISHEYE", defaultValue = 500, hasModulation = true))
        addControl(PropertyControl(this, "T_HUE_STR", "RAINBOW STR", defaultValue = 0))
        addControl(PropertyControl(this, "T_HUE_POS", "RAINBOW POS", defaultValue = 0, hasModulation = true))
        addControl(PropertyControl(this, "T_WAVE_STR", "WAVE STR", defaultValue = 0))
        addControl(PropertyControl(this, "T_WAVE_POS", "WAVE POS", defaultValue = 0, hasModulation = true))

        createGroup("MORPHING")
        addControl(PropertyControl(this, "CURVE", "CURVE", defaultValue = 500, hasModulation = true))
        addControl(PropertyControl(this, "TWIST", "VORTEX", defaultValue = 500, hasModulation = true))
        addControl(PropertyControl(this, "FLUX", "FLUX", defaultValue = 0, hasModulation = true))

        createGroup("MASTER TRANSFORM")
        addControl(PropertyControl(this, "M_ANGLE", "ANGLE", defaultValue = 0, hasModulation = true, modMode = PropertyControl.ModMode.WRAP))
        addControl(PropertyControl(this, "M_ZOOM", "ZOOM", defaultValue = 160, hasModulation = true))
        addControl(PropertyControl(this, "M_TX", "MOVE X", defaultValue = 500, hasModulation = true))
        addControl(PropertyControl(this, "M_TY", "MOVE Y", defaultValue = 500, hasModulation = true))
        addControl(PropertyControl(this, "M_TILTX", "TILT X", defaultValue = 500, hasModulation = true))
        addControl(PropertyControl(this, "M_TILTY", "TILT Y", defaultValue = 500, hasModulation = true))
        addControl(PropertyControl(this, "M_RGB", "RGB SHIFT", defaultValue = 0, hasModulation = true))

        createGroup("CAMERA TRANSFORM")
        setupCameraOrientationControls(currentGroupContent!!)
        addControl(PropertyControl(this, "C_ANGLE", "ANGLE", defaultValue = 0, hasModulation = true, modMode = PropertyControl.ModMode.WRAP))
        addControl(PropertyControl(this, "WARP", "WARP DISTORT", defaultValue = 0))
        addControl(PropertyControl(this, "C_ZOOM", "ZOOM", defaultValue = 300, hasModulation = true))
        addControl(PropertyControl(this, "C_TX", "MOVE X", defaultValue = 500, hasModulation = true))
        addControl(PropertyControl(this, "C_TY", "MOVE Y", defaultValue = 500, hasModulation = true))
        addControl(PropertyControl(this, "C_TILTX", "TILT X", defaultValue = 500, hasModulation = true))
        addControl(PropertyControl(this, "C_TILTY", "TILT Y", defaultValue = 500, hasModulation = true))
        addControl(PropertyControl(this, "RGB", "RGB SHIFT", defaultValue = 0, hasModulation = true))

        createGroup("COLOR")
        addControl(PropertyControl(this, "BRIT", "BRIGHTNESS", defaultValue = 500))
        addControl(PropertyControl(this, "HUE", "HUE", defaultValue = 0, hasModulation = true, modMode = PropertyControl.ModMode.WRAP))
        addControl(PropertyControl(this, "NEG", "NEGATIVE", defaultValue = 0, hasModulation = true))
        addControl(PropertyControl(this, "GLOW", "GLOW", defaultValue = 0, hasModulation = true))
        addControl(PropertyControl(this, "CONTRAST", "CONTRAST", defaultValue = 500))
        addControl(PropertyControl(this, "VIBRANCE", "SATURATION", defaultValue = 500))
    }

    private fun setupGeometrySpecifics(parent: LinearLayout) {
        val axisContainer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 10) }
        val axisId = "AXIS"
        val axisCtrl: PropertyControl
        if (controlsMap.containsKey(axisId)) {
            axisCtrl = controlsMap[axisId]!!
        } else {
            axisCtrl = PropertyControl(this, axisId, "COUNT", min = 0, max = 15, defaultValue = 1)
            controls.add(axisCtrl)
            controlsMap[axisId] = axisCtrl
        }
        axisSb = SeekBar(this).apply {
            max = 25
            progress = axisCtrl.value
            layoutParams = LinearLayout.LayoutParams(0, 65, 1f)
            thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(30, 30); cornerRadius = 15f }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    renderer.axisCount = (p + 1).toFloat()
                    axisCtrl.setProgress(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        lockBtn = Button(this).apply {
            background = createLockDrawable(axisLocked)
            layoutParams = LinearLayout.LayoutParams(80, 80).apply { leftMargin = 20 }
            alpha = if (axisLocked) 1.0f else 0.4f
            setOnClickListener {
                axisLocked = !axisLocked
                background = createLockDrawable(axisLocked)
                alpha = if (axisLocked) 1.0f else 0.4f
            }
        }
        renderer.axisCount = (axisCtrl.value + 1).toFloat()
        axisContainer.addView(TextView(this).apply { text = "COUNT"; setTextColor(Color.WHITE); textSize = 8f; minWidth = 100; alpha = 0.8f })
        axisContainer.addView(axisSb)
        axisContainer.addView(lockBtn)
        parent.addView(axisContainer)
    }

    private fun setupCameraOrientationControls(parent: LinearLayout) {
        val orientationRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 10, 0, 20); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120) }
        fun createParamBtn(icon: BitmapDrawable, action: () -> Unit): ImageButton {
            return ImageButton(this).apply { setImageDrawable(icon); background = GradientDrawable().apply { setColor(Color.parseColor("#22FFFFFF")); cornerRadius = 12f }; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(6, 0, 6, 0) }; setOnClickListener { action(); updateSidebarVisuals() } }
        }
        flipXBtn = createParamBtn(createCustomIcon(0)) { renderer.flipX = if (renderer.flipX == 1f) -1f else 1f }
        flipYBtn = createParamBtn(createCustomIcon(1)) { renderer.flipY = if (renderer.flipY == 1f) -1f else 1f }
        rot180Btn = createParamBtn(createCustomIcon(2)) { renderer.rot180 = !renderer.rot180 }
        orientationRow.addView(flipXBtn); orientationRow.addView(flipYBtn); orientationRow.addView(rot180Btn)
        parent.addView(orientationRow)
    }

    private fun createCameraSettingsPanel(): LinearLayout {
        cameraSettingsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(10, 20, 10, 20) }
        fun createSideBtn(resId: Int, action: () -> Unit) = ImageButton(this).apply { setImageResource(resId); setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.85f; layoutParams = LinearLayout.LayoutParams(100, 100); setOnClickListener { action(); updateSidebarVisuals() } }
        cameraSettingsPanel.addView(createSideBtn(android.R.drawable.ic_menu_camera) { currentSelector = if (currentSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA; startCamera() })
        cameraSettingsPanel.addView(createSideBtn(android.R.drawable.ic_menu_gallery) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*")) }
            mediaPickerLauncher.launch(intent)
        })
        cameraSettingsPanel.addView(createSideBtn(android.R.drawable.ic_menu_compass) { showRtspDialog() })
        return cameraSettingsPanel
    }

    private fun createRecordControls(): LinearLayout {
        recordControls = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(10, 10, 10, 10) }
        photoBtn = ImageButton(this).apply { setImageDrawable(textToIcon("[ ]", 50f)); setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE); alpha = 0.8f; scaleX = 1.5f; scaleY = 1.5f; layoutParams = LinearLayout.LayoutParams(150, 150); setOnClickListener { renderer.capturePhoto(); triggerFlashPulse() } }
        recordBtn = ImageButton(this).apply { setImageDrawable(textToIcon("REC", 40f)); setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE); alpha = 0.5f; layoutParams = LinearLayout.LayoutParams(150, 150); setOnClickListener { toggleRecording() } }
        recordControls.addView(photoBtn); recordControls.addView(recordBtn)
        return recordControls
    }

    private fun createPresetPanel(): LinearLayout {
        presetPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(15, 10, 15, 30) }
        val transContainer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(10, 0, 10, 10) }
        val timeLabel = TextView(this).apply { text = "1.0s"; setTextColor(Color.WHITE); textSize = 9f; setPadding(4, 0, 8, 0) }
        val transSeekBar = SeekBar(this).apply {
            max = 1000; progress = 333; layoutParams = LinearLayout.LayoutParams(500, 45)
            thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(30, 30); cornerRadius = 15f }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    transitionMs = ((p / 1000f).pow(3.0f) * 30000).toLong()
                    timeLabel.text = "%.1fs".format(transitionMs / 1000f)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        transContainer.addView(ImageView(this).apply { setImageDrawable(createClockDrawable()); alpha = 0.5f; layoutParams = LinearLayout.LayoutParams(45, 45).apply { rightMargin = 10 } })
        transContainer.addView(timeLabel); transContainer.addView(transSeekBar)
        val presetRow = FrameLayout(this)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        (8 downTo 1).forEach { idx ->
            val b = Button(this).apply {
                text = idx.toString(); setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.8f; textSize = 16f; layoutParams = LinearLayout.LayoutParams(80, 140); setPadding(0, 0, 0, 20)
                setOnClickListener { applyPreset(idx) }
                setOnLongClickListener { pendingSaveIndex = idx; saveConfirmBtn.visibility = View.VISIBLE; saveConfirmBtn.text = "SAVE $idx?"; true }
            }
            presetButtons[idx] = b; btnRow.addView(b)
        }
        saveConfirmBtn = Button(this).apply { visibility = View.GONE; setTextColor(Color.BLACK); textSize = 12f; setTypeface(null, Typeface.BOLD); background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8f }; layoutParams = FrameLayout.LayoutParams(250, 100, Gravity.CENTER); setOnClickListener { pendingSaveIndex?.let { savePreset(it) }; visibility = View.GONE } }
        presetRow.addView(btnRow); presetRow.addView(saveConfirmBtn)
        presetPanel.addView(transContainer); presetPanel.addView(presetRow)
        return presetPanel
    }

    private fun createFlashView() = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f; layoutParams = FrameLayout.LayoutParams(-1, -1) }
    private fun createLogoView() = ImageView(this).apply { setImageDrawable(createLogoDrawable()); alpha = 0.4f; layoutParams = FrameLayout.LayoutParams(180, 180).apply { gravity = Gravity.TOP or Gravity.START; topMargin = 40; leftMargin = 40 } }
    private fun createMenuUtilityButton() = Button(this).apply {
        val isPortrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

        // Set initial icon based on current state
        text = if (isPortrait) {
            if (isMenuExpanded) "▲" else "▼"
        } else {
            if (isMenuExpanded) "◀" else "▶"
        }

        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        stateListAnimator = null
        background = null
        alpha = 0.85f
        gravity = Gravity.CENTER
        setPadding(0, 0, 10, 10)
        menuBtn = this
        setOnClickListener { toggleMenu() }
    }
    private fun createReadabilityButton() = ImageButton(this).apply { setImageResource(android.R.drawable.ic_menu_view); setColorFilter(Color.WHITE); alpha = 0.85f; readabilityBtn = this; layoutParams = FrameLayout.LayoutParams(120, 120).apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 140; rightMargin = 35 }; setOnClickListener { toggleReadability() } }
    private fun createResetButton() = ImageButton(this).apply { setImageResource(android.R.drawable.ic_menu_close_clear_cancel); setColorFilter(Color.WHITE); alpha = 0.85f; resetBtn = this; layoutParams = FrameLayout.LayoutParams(120, 120).apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 30; rightMargin = 35 }; setOnClickListener { globalReset() } }

    private fun createCollapsibleGroupView(title: String, startOpen: Boolean): Pair<LinearLayout, LinearLayout> {
        val groupContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 }; layoutTransition = LayoutTransition() }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(15, 12, 15, 12); background = GradientDrawable().apply { setColor(Color.parseColor("#33FFFFFF")); cornerRadius = 8f; setStroke(1, Color.parseColor("#44FFFFFF")) } }
        val arrow = TextView(this).apply { text = "▶"; textSize = 9f; setTextColor(Color.LTGRAY); layoutParams = LinearLayout.LayoutParams(50, -2); rotation = if (startOpen) 90f else 0f }
        val label = TextView(this).apply { text = title; textSize = 10f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); letterSpacing = 0.15f }
        header.addView(arrow); header.addView(label)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (startOpen) View.VISIBLE else View.GONE; setPadding(6, 6, 6, 6) }
        header.setOnClickListener {
            val isVisible = content.visibility == View.VISIBLE
            if (isVisible) { content.visibility = View.GONE; arrow.animate().rotation(0f).setDuration(200).start() }
            else { content.visibility = View.VISIBLE; arrow.animate().rotation(90f).setDuration(200).start() }
        }
        groupContainer.addView(header); groupContainer.addView(content)
        return Pair(groupContainer, content)
    }

    private fun createCustomIcon(type: Int): BitmapDrawable {
        val size = 100; val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val paint = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true }
        val center = size / 2f
        when (type) {
            0 -> { paint.strokeWidth = 9f; val range = 20f; c.drawLine(center - range, center, center + range, center, paint); val p = Path().apply { moveTo(center - 8, center - 12); lineTo(center - range - 4, center); lineTo(center - 8, center + 12); moveTo(center + 8, center - 12); lineTo(center + range + 4, center); lineTo(center + 8, center + 12) }; c.drawPath(p, paint) }
            1 -> { paint.strokeWidth = 9f; val range = 20f; c.drawLine(center, center - range, center, center + range, paint); val p = Path().apply { moveTo(center - 12, center - 8); lineTo(center, center - range - 4); lineTo(center + 12, center - 8); moveTo(center - 12, center + 8); lineTo(center, center + range + 4); lineTo(center + 12, center + 8) }; c.drawPath(p, paint) }
            2 -> { paint.strokeWidth = 9f; val r = 24f; val box = RectF(center - r, center - r, center + r, center + r); c.drawArc(box, 180f + 20f, 140f, false, paint); c.drawArc(box, 0f + 20f, 140f, false, paint); val p = Path(); val endX1 = center + (r * cos(Math.toRadians(340.0))).toFloat(); val endY1 = center + (r * sin(Math.toRadians(340.0))).toFloat(); p.moveTo(endX1 - 5f, endY1 - 15f); p.lineTo(endX1, endY1); p.lineTo(endX1 - 18f, endY1 - 2f); val endX2 = center + (r * cos(Math.toRadians(160.0))).toFloat(); val endY2 = center + (r * sin(Math.toRadians(160.0))).toFloat(); p.moveTo(endX2 + 5f, endY2 + 15f); p.lineTo(endX2, endY2); p.lineTo(endX2 + 18f, endY2 + 2f); paint.style = Paint.Style.STROKE; c.drawPath(p, paint) }
        }
        return BitmapDrawable(resources, b)
    }

    private fun showRtspDialog() {
        val prefs = getSharedPreferences("SpaceBeam_RTSP", Context.MODE_PRIVATE)
        val historyKey = "RTSP_HISTORY"
        val rawSet = prefs.getStringSet(historyKey, null)
        val historyList = rawSet?.toMutableList() ?: mutableListOf()
        if (historyList.isEmpty()) historyList.add("rtsp://wowzaec2demo.streamlock.net/vod/mp4:BigBuckBunny_115k.mp4")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(40, 20, 40, 0) }
        val input = AutoCompleteTextView(this).apply { setText(lastRtspUrl); setTextColor(Color.BLACK); textSize = 16f; setPadding(20, 30, 20, 30); threshold = 1; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, historyList)) }
        val arrowBtn = ImageButton(this).apply { setImageResource(android.R.drawable.arrow_down_float); setBackgroundColor(Color.LTGRAY); alpha = 0.7f; scaleType = ImageView.ScaleType.CENTER_INSIDE; layoutParams = LinearLayout.LayoutParams(120, 100).apply { leftMargin = 10 }; setOnClickListener { val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager; imm.hideSoftInputFromWindow(input.windowToken, 0); input.showDropDown() } }
        row.addView(input); row.addView(arrowBtn)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Enter RTSP/Video URL").setView(row).setPositiveButton("Load", null).setNegativeButton("Cancel", null).create()
        fun performLoad() {
            val url = input.text.toString().trim()
            if (url.isNotEmpty()) {
                if (historyList.contains(url)) historyList.remove(url)
                historyList.add(0, url)
                while (historyList.size > 20) historyList.removeAt(historyList.lastIndex)
                prefs.edit().putStringSet(historyKey, historyList.toHashSet()).apply()
                startRtsp(url)
                dialog.dismiss()
            }
        }
        input.setOnEditorActionListener { _, actionId, _ -> if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { performLoad(); true } else false }
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        dialog.show()
        dialog.window?.decorView?.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { performLoad() }
    }

    private fun applyReadabilityStyle() {
        val getBg = { alpha: Int -> GradientDrawable().apply {
            setColor(Color.argb(alpha, 10, 10, 10))
            setStroke(2, Color.argb(120, 80, 80, 80))
            cornerRadius = 25f
        } }
        val getCircleBg = { alpha: Int -> getBg(alpha).apply { shape = GradientDrawable.OVAL } }

        // TARGET parameterPanel specifically for the background
        val panels = listOf(cameraSettingsPanel, presetPanel, recordControls)
        val utils = listOf(readabilityBtn, resetBtn, menuBtn, orientationBtn)

        panels.forEach { it.background = null; it.setPadding(15, 15, 15, 15); it.clipToOutline = true }
        parameterPanel.background = null // Clear parameter panel specifically

        when (readabilityLevel) {
            1 -> {
                panels.forEach { it.background = getBg(180) }
                parameterPanel.background = getBg(180)
                utils.forEach { it.background = getCircleBg(180) }
                applyRecursiveGlow(overlayHUD, false)
            }
            2 -> {
                panels.forEach { it.background = getBg(120) }
                parameterPanel.background = getBg(120)
                utils.forEach { it.background = getCircleBg(120) }
                applyRecursiveGlow(overlayHUD, true)
            }
            else -> {
                panels.forEach { it.setPadding(0, 0, 0, 0); it.background = null }
                parameterPanel.background = null
                utils.forEach { it.background = null }
                applyRecursiveGlow(overlayHUD, false)
            }
        }
    }

    private fun applyRecursiveGlow(view: View, enabled: Boolean) {
        if (view is TextView) { if (enabled) view.setShadowLayer(50f, 0f, 0f, Color.BLACK) else view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT) }
        else if (view is ImageButton || view is Button) { view.elevation = if (enabled) 50f else 0f }
        if (view is ViewGroup) (0 until view.childCount).forEach { applyRecursiveGlow(view.getChildAt(it), enabled) }
    }

    private fun toggleReadability() { readabilityLevel = (readabilityLevel + 1) % 3; applyReadabilityStyle() }

    private fun toggleRecording() {
        if (!isRecording) {
            val fileName = "SB_${System.currentTimeMillis()}.mp4"
            val tempFile = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), fileName)
            renderer.startRecording(tempFile); isRecording = true; recordingSeconds = 0; recordBtn.alpha = 1.0f
            recordTicker = object : Runnable {
                override fun run() {
                    recordingSeconds++; val m = recordingSeconds / 60; val s = recordingSeconds % 60
                    recordBtn.setImageDrawable(textToIcon("%d:%02d".format(m, s), 38f, Color.RED)); handler.postDelayed(this, 1000)
                }
            }; handler.post(recordTicker!!)
        } else {
            renderer.stopRecording { savedFile ->
                isRecording = false; recordTicker?.let { handler.removeCallbacks(it) }
                runOnUiThread {
                    recordBtn.setImageDrawable(textToIcon("REC", 40f)); recordBtn.alpha = 0.5f; if (savedFile != null && savedFile.exists()) saveVideoToGallery(savedFile)
                }
            }
        }
    }

    private fun saveVideoToGallery(file: File) {
        if (file.length() == 0L) return
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SpaceBeam"); put(MediaStore.Video.Media.IS_PENDING, 1) }
        }
        try {
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0); contentResolver.update(it, values, null, null) }
                MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
                file.delete(); Toast.makeText(this, "Video Saved", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {}
    }

    private fun globalReset() {
        renderer.stopRotationAnim() // STOP GL ANIM
        controls.forEach { it.stopAnimation() } // STOP GL ANIM

        renderer.mRotAccum = 0.0
        renderer.cRotAccum = 0.0
        renderer.lRotAccum = 0.0
        renderer.scrollAccum = 0.0f
        renderer.resetPhases()
        renderer.flipX = 1f
        renderer.flipY = -1f
        renderer.rot180 = false
        activePreset = -1
        updatePresetHighlights()
        controls.forEach { it.reset() }
        renderer.axisCount = 2.0f
        axisSb.progress = 1
        controlsMap["AXIS"]?.setProgress(1)
        updateSidebarVisuals()
    }

    private fun updatePresetHighlights() {
        presetButtons.forEach { (idx, btn) -> btn.background = GradientDrawable().apply { setColor(Color.TRANSPARENT); if (idx == activePreset) setStroke(4, Color.WHITE); cornerRadius = 12f } }
    }

    private fun triggerFlashPulse() {
        flashOverlay.alpha = 0.6f; flashOverlay.animate().alpha(0f).setDuration(400).start()
        photoBtn.animate().scaleX(1.8f).scaleY(1.8f).setDuration(100).withEndAction { photoBtn.animate().scaleX(1.5f).scaleY(1.5f).setDuration(200).start() }.start()
    }

    private fun updateSidebarVisuals() {
        flipXBtn.alpha = if (renderer.flipX < 0f) 1.0f else 0.3f; flipYBtn.alpha = if (renderer.flipY > 0f) 1.0f else 0.3f; rot180Btn.alpha = if (renderer.rot180) 1.0f else 0.3f
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
    }

    private fun applyPreset(idx: Int) {
        val p = presets[idx] ?: return
        activePreset = idx
        updatePresetHighlights()

        // FIXED: Use GL Thread Animation (No ValueAnimator)
        val durationSec = transitionMs / 1000f

        if (!axisLocked) {
            renderer.axisCount = p.axis.toFloat()
            axisSb.progress = p.axis - 1
            controlsMap["AXIS"]?.setProgress(p.axis - 1)
        }

        // 1. Calculate Rotation Targets
        val startMRot = renderer.mRotAccum
        val startCRot = renderer.cRotAccum
        val targetMRot = round(startMRot / 360.0) * 360.0
        val targetCRot = round(startCRot / 360.0) * 360.0

        // 2. Trigger Renderer Rotation Animation
        renderer.animateRotationTo(targetMRot, targetCRot, durationSec)

        // 3. Trigger Control Animations
        controls.forEach { control ->
            if (control.id == "AXIS") return@forEach
            val snap = p.controlSnapshots[control.id]
            if (snap != null) {
                // 1. Animate the main slider value AND the waveform shape
                control.animateTo(snap.value.toFloat(), durationSec, snap.shape)

                if (control.hasModulation) {
                    // 2. FIX: Instead of calling updateModDepth instantly,
                    // create new methods to animate these over time too.
                    control.animateModulation(snap.rate.toFloat(), snap.depth.toFloat(), durationSec)
                }
            }
        }

        renderer.flipX = p.flipX
        renderer.flipY = p.flipY
        renderer.rot180 = p.rot180
        updateSidebarVisuals()
    }

    private fun savePreset(idx: Int) {
        val snapshots = controls.filter { it.id != "AXIS" }.associate { it.id to it.getSnapshot() }
        val axisVal = controlsMap["AXIS"]?.value ?: 0
        val newPreset = Preset(snapshots, renderer.flipX, renderer.flipY, renderer.rot180, axisVal + 1)
        presets[idx] = newPreset
        activePreset = idx
        updatePresetHighlights()
        try {
            val rootObj = JSONObject()
            rootObj.put("axis", newPreset.axis)
            rootObj.put("flipX", newPreset.flipX.toDouble())
            rootObj.put("flipY", newPreset.flipY.toDouble())
            rootObj.put("rot180", newPreset.rot180)
            val controlsObj = JSONObject()
            newPreset.controlSnapshots.forEach { (key, snap) ->
                val snapObj = JSONObject()
                snapObj.put("v", snap.value); snapObj.put("r", snap.rate); snapObj.put("d", snap.depth); snapObj.put("shape", snap.shape)
                controlsObj.put(key, snapObj)
            }
            rootObj.put("controls", controlsObj)
            getSharedPreferences("SpaceBeam_Presets", Context.MODE_PRIVATE).edit().putString("PRESET_$idx", rootObj.toString()).apply()
            Toast.makeText(this, "Preset $idx Saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Save Failed", Toast.LENGTH_SHORT).show() }
    }

    private fun createLockedIconDrawable(locked: Boolean): BitmapDrawable {
        // 1. Create a canvas
        val size = 120
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)

        // 2. Draw the standard System Rotate Icon
        val icon = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_rotate)?.mutate()
        if (icon != null) {
            // Add some padding so it looks nice (20px padding)
            icon.setBounds(20, 20, size - 20, size - 20)
            icon.setTint(Color.WHITE)
            icon.draw(c)
        }

        // 3. If locked, draw a solid dot in the center
        if (locked) {
            val p = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            // Draw circle at center (60, 60) with radius 10
            c.drawCircle(size / 2f, size / 2f, 10f, p)
        }

        return BitmapDrawable(resources, b)
    }

    private fun createOrientationButton() = ImageButton(this).apply {
        // Set initial icon
        setImageDrawable(createLockedIconDrawable(isOrientationLocked))

        // Initial visual state
        background = null
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(0, 0, 0, 0) // REMOVED PADDING to maximize icon size

        // Set initial visuals based on current state
        if (isOrientationLocked) {
            setColorFilter(Color.WHITE)
            alpha = 1.0f
        } else {
            setColorFilter(Color.WHITE)
            alpha = 0.6f
        }

        orientationBtn = this
        layoutParams = FrameLayout.LayoutParams(120, 120).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = 260
            rightMargin = 35
        }

        setOnClickListener {
            isOrientationLocked = !isOrientationLocked

            // Update the Icon (Add/Remove Dot)
            setImageDrawable(createLockedIconDrawable(isOrientationLocked))

            if (isOrientationLocked) {
                // LOCK: Freezes the screen in the CURRENT orientation (Portrait or Landscape)
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED

                setColorFilter(Color.WHITE)
                alpha = 1.0f
                Toast.makeText(context, "Orientation Locked", Toast.LENGTH_SHORT).show()
            } else {
                // UNLOCK: Allow sensor rotation
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

                // Visuals: White + Greyed out (0.6f)
                setColorFilter(Color.WHITE)
                alpha = 0.6f
                Toast.makeText(context, "Orientation Unlocked", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initDefaultPresets() {
        fun p(ax: Int = 1, mRot: Int = 500, vararg overrides: Any): Preset {
            val baseSnapshots = controls.associate { it.id to it.getSnapshot() }.toMutableMap()
            baseSnapshots["M_ROT"] = PropertyControl.Snapshot(mRot, false, 0, 0, "SINE")
            var i = 0
            while (i < overrides.size) {
                val key = overrides[i] as String
                val value = overrides[i + 1] as Int
                if (i + 3 < overrides.size && overrides[i + 2] is Int && overrides[i + 3] is Int) {
                    val rate = overrides[i + 2] as Int; val depth = overrides[i + 3] as Int
                    baseSnapshots[key] = PropertyControl.Snapshot(value, true, rate, depth, "SINE")
                    i += 4
                } else {
                    baseSnapshots[key] = PropertyControl.Snapshot(value, false, 0, 0, "SINE")
                    i += 2
                }
            }
            return Preset(baseSnapshots, 1f, -1f, false, ax)
        }
        presets[1] = p(ax = 2, mRot = 500, "M_ZOOM", 300, 139, 307, "WARP", 1000, 0, 0)
        presets[2] = p(ax = 2, mRot = 615, "M_ZOOM", 248, 293, 383, "WARP", 1000, 0, 0)
        presets[3] = p(ax = 2, mRot = 673, "M_ZOOM", 268, 293, 559, "M_TILTX", 553, 305, 880, "M_TILTY", 500, 353, 1000, "WARP", 1000, 0, 0)
        presets[4] = p(ax = 2, mRot = 673, "M_ZOOM", 268, 293, 517, "M_TX", 500, 159, 624, "M_TY", 500, 309, 753, "M_TILTX", 553, 305, 880, "M_TILTY", 500, 353, 1000, "WARP", 1000, 0, 0)
        presets[5] = p(ax = 2, mRot = 673, "M_ZOOM", 359, 293, 517, "M_TX", 500, 159, 624, "M_TY", 500, 309, 753, "M_TILTX", 553, 305, 577, "M_TILTY", 500, 353, 854, "C_ROT", 657, 0, 0, "WARP", 0, 0, 0)
        presets[6] = p(ax = 2, mRot = 673, "M_ZOOM", 268, 293, 517, "M_TX", 500, 159, 624, "M_TY", 500, 309, 753, "M_TILTX", 553, 305, 1000, "M_TILTY", 500, 353, 1000, "C_ROT", 657, 0, 0, "WARP", 0, 0, 0, "C_TX", 500, 389, 739, "C_TY", 500, 209, 763, "GLOW", 164, 395, 129)
        presets[7] = p(ax = 2, mRot = 673, "M_ZOOM", 912, 293, 740, "M_TX", 500, 159, 624, "M_TY", 500, 309, 753, "M_TILTX", 553, 305, 1000, "M_TILTY", 500, 353, 1000, "C_ROT", 657, 0, 0, "WARP", 0, 0, 0, "C_TX", 500, 389, 739, "C_TY", 500, 209, 763, "C_TILTX", 500, 287, 677, "C_TILTY", 500, 443, 557, "GLOW", 164, 395, 129)
        presets[8] = p(ax = 2, mRot = 673, "M_ZOOM", 268, 293, 517, "M_TX", 500, 159, 624, "M_TY", 500, 309, 753, "M_TILTX", 553, 305, 1000, "M_TILTY", 500, 353, 1000, "C_ROT", 657, 0, 0, "WARP", 0, 0, 0, "C_TX", 500, 389, 739, "C_TY", 500, 209, 763, "C_TILTX", 500, 287, 677, "C_TILTY", 500, 443, 557, "RGB", 957, 0, 0, "GLOW", 164, 395, 129)

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
                    loadedSnapshots.putAll(presets[i]?.controlSnapshots ?: emptyMap())
                    val keysIterator = controlsObj.keys()
                    while (keysIterator.hasNext()) {
                        val key = keysIterator.next()
                        val snapObj = controlsObj.getJSONObject(key)
                        loadedSnapshots[key] = PropertyControl.Snapshot(
                            snapObj.getInt("v"), snapObj.optBoolean("active", false), snapObj.optInt("r", 0), snapObj.optInt("d", 0), snapObj.optString("shape", "SINE")
                        )
                    }
                    presets[i] = Preset(loadedSnapshots, loadedFlipX, loadedFlipY, loadedRot180, loadedAxis)
                } catch (e: Exception) { Log.e("PRESET", "Error loading preset $i", e) }
            }
        }
    }

    private fun toggleHud() {
        isHudVisible = !isHudVisible; overlayHUD.visibility = if (isHudVisible) View.VISIBLE else View.GONE; if (isHudVisible) hideSystemUI()
    }

    private fun toggleMenu() {
        PropertyControl.closeActiveMenu()
        isMenuExpanded = !isMenuExpanded

        // Toggle visibility of the panel (and its background)
        parameterPanel.visibility = if (isMenuExpanded) View.VISIBLE else View.GONE

        val isPortrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

        menuBtn.text = if (isPortrait) {
            if (isMenuExpanded) "▲" else "▼" // Down to hide, Up to show
        } else {
            if (isMenuExpanded) "◀" else "▶" // Left to hide, Right to show
        }
    }

    inner class KaleidoscopeRenderer(private val ctx: MainActivity) : GLSurfaceView.Renderer {
        private var kaleidoProgram = 0
        private var simpleProgram = 0
        @Volatile private var isSurfaceReady = false
        private val mvpMatrix = FloatArray(16)
        private val identityMatrix = FloatArray(16).apply { android.opengl.Matrix.setIdentityM(this, 0) }
        var scrollAccum = 0.0f
        var mRotAccum = 0.0
        var cRotAccum = 0.0
        var lRotAccum = 0.0
        var axisCount = 2.0f
        var flipX = 1.0f
        var flipY = -1.0f
        var rot180 = false
        private var lastTime = System.nanoTime()
        private var deltaTime = 0.0f
        private var cameraTexId = -1
        private var surfaceTexture: SurfaceTexture? = null
        private var playerSurface: Surface? = null
        private var fboId = 0
        private var fboTexId = 0
        private var fboWidth = 1920
        private var fboHeight = 1080
        private var captureRequested = false
        private var videoRecorder: VideoRecorder? = null
        private var recordSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
        private var pendingRecordFile: File? = null
        private var onStopCallback: ((File?) -> Unit)? = null
        private var isStopRequested = false
        private var recordStartTimeNs: Long = 0
        private var mSavedDisplay = EGL14.EGL_NO_DISPLAY
        private var mSavedContext = EGL14.eglGetCurrentContext()
        private var mEglConfig: EGL14EGLConfig? = null
        private var extSurfaceArgs: Triple<Surface, Int, Int>? = null
        private var extEglSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
        private var extWidth = 0
        private var extHeight = 0
        private lateinit var pBuf: FloatBuffer
        private lateinit var tBuf: FloatBuffer
        private var uLocs = mutableMapOf<String, Int>()
        private var simpleULocs = mutableMapOf<String, Int>()
        private var viewWidth = 1
        private var viewHeight = 1
        private val FIXED_WIDTH = 1920
        private val FIXED_HEIGHT = 1080

        // --- NEW: Rotation Animation State ---
        private var rotTargetM: Double? = null
        private var rotStartM: Double = 0.0
        private var rotTargetC: Double? = null
        private var rotStartC: Double = 0.0
        private var rotAnimDuration: Float = 0f
        private var rotAnimTime: Float = 0f
        private var isRotAnimating = false

        fun animateRotationTo(targetM: Double, targetC: Double, duration: Float) {
            rotTargetM = targetM
            rotStartM = mRotAccum
            rotTargetC = targetC
            rotStartC = cRotAccum
            rotAnimDuration = duration
            rotAnimTime = 0f
            isRotAnimating = true
        }

        fun stopRotationAnim() { isRotAnimating = false }

        fun resetPhases() { ctx.controls.forEach { it.lfoPhase = 0.0 }; mRotAccum = 0.0; cRotAccum = 0.0; lRotAccum = 0.0 }
        fun capturePhoto() { captureRequested = true }
        fun stopRecording(callback: (File?) -> Unit) { onStopCallback = callback; isStopRequested = true }
        fun getPlayerSurface(): Surface? { if (surfaceTexture == null) return null; if (playerSurface == null) { playerSurface = Surface(surfaceTexture) }; return playerSurface }
        fun startRecording(file: File) { pendingRecordFile = file; recordStartTimeNs = 0 }
        fun resetVideoTexture() {
            playerSurface?.release(); playerSurface = null; surfaceTexture?.release(); surfaceTexture = null
            if (cameraTexId != -1) { val t = IntArray(1); t[0] = cameraTexId; GLES20.glDeleteTextures(1, t, 0); cameraTexId = -1 }
            cameraTexId = createOESTex(); surfaceTexture = SurfaceTexture(cameraTexId); surfaceTexture?.setDefaultBufferSize(viewWidth, viewHeight)
        }
        fun provideSurface(req: SurfaceRequest) { glView.queueEvent { surfaceTexture?.let { st -> st.setDefaultBufferSize(req.resolution.width, req.resolution.height); val s = Surface(st); req.provideSurface(s, ContextCompat.getMainExecutor(ctx)) { s.release() } } } }
        fun setExternalSurface(s: Surface, w: Int, h: Int) { extSurfaceArgs = Triple(s, w, h) }
        fun removeExternalSurface() { extSurfaceArgs = null }
        fun updateTextureSize(width: Int, height: Int) { glView.queueEvent { surfaceTexture?.setDefaultBufferSize(width, height) } }


        override fun onSurfaceCreated(gl: GL10?, config: GL10EGLConfig?) {
            setupEGL(); GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            val vSrc = "attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }"
            val fSrc = """#extension GL_OES_EGL_image_external : require
            precision highp float; varying vec2 v; uniform samplerExternalOES uTex;
            uniform float uMR, uCR, uCZ, uA, uMZ, uAx, uC, uS, uHue, uSol, uBloom, uRGB, uMRGB, uWarp;
            uniform float uBrit, uTHueStr, uTHuePos, uTWaveStr, uTWavePos;
            uniform vec2 uMT, uCT, uF, uMTilt, uCTilt;
            uniform float uCurve, uTwist, uFlux, uSShape, uSFov, uScroll, uMode;
            vec3 hueShift(vec3 color, float hue) { const vec3 k = vec3(0.57735, 0.57735, 0.57735); float cosAngle = cos(hue); return vec3(color * cosAngle + cross(k, color) * sin(hue) + k * dot(k, color) * (1.0 - cosAngle)); }
            vec3 sampleCamera(vec2 uv, float rgbShift) {
                vec2 centered = uv - 0.5;
                float z = 1.0 + (centered.x * uCTilt.x) + (centered.y * uCTilt.y); centered /= max(z, 0.1); centered *= uCZ;
                float aspectFactor = mix(uA, 1.0, uWarp); centered.x *= aspectFactor;
                float cr = uCR * 0.01745329; float c = cos(cr); float s = sin(cr);
                centered = vec2(centered.x * c - centered.y * s, centered.x * s + centered.y * c);
                centered.x /= aspectFactor; centered += uCT;
                vec2 rotatedUV = centered + 0.5; rotatedUV.x += rgbShift; rotatedUV = (rotatedUV - 0.5) * uF + 0.5;
                vec2 mirroredUV = abs(mod(rotatedUV + 1.0, 2.0) - 1.0);
                return texture2D(uTex, mirroredUV).rgb;
            }
            void main() {
                vec3 finalColor = vec3(0.0);
                float a1 = -uMR * 0.01745329; float cosA1 = cos(a1); float sinA1 = sin(a1);
                float modeBlend = smoothstep(0.0, 1.0, uMode);
                vec2 effectiveTilt = mix(uMTilt, vec2(0.0), modeBlend);
                vec2 effectiveTrans = uMT + mix(vec2(0.0), uMTilt * 2.0, modeBlend);
                for(int i=0; i<3; i++) {
                    float mOff = (i==0) ? uMRGB : (i==2) ? -uMRGB : 0.0;
                    vec2 uv = v - 0.5;
                    float zM = 1.0 + (uv.x * effectiveTilt.x) + (uv.y * effectiveTilt.y); uv /= max(zM, 0.1); uv.x *= uA; uv.x += mOff;
                    uv = (uv + effectiveTrans) * uMZ * 4.0;
                    uv = vec2(uv.x * cosA1 - uv.y * sinA1, uv.x * sinA1 + uv.y * cosA1);
                    if(uAx > 1.1) {
                        float r = length(uv); float slice = 6.2831853 / uAx; float angle = atan(uv.y, uv.x);
                        float a = mod(angle, slice); if(mod(uAx, 2.0) < 0.1) a = abs(a - slice * 0.5);
                        uv = vec2(cos(a), sin(a)) * r;
                    }
                    float rCircle = length(uv); float rBox = max(abs(uv.x), abs(uv.y)); float dist = mix(rCircle, rBox, uSShape);
                    float angle = atan(uv.y, uv.x); dist += sin(angle * 4.0 + dist * 10.0) * uFlux * dist; float safeDist = max(dist, 0.01);
                    float projection = (uSFov * 0.8 + 0.2) / safeDist;
                    vec2 tunnelUV; tunnelUV.x = (angle + (1.0/safeDist) * uTwist) / 3.14159; tunnelUV.y = projection + uScroll; 
                    if(abs(uCurve - 1.0) > 0.01) tunnelUV *= 1.0 + (uCurve - 1.0) * (1.0 - safeDist);
                    vec2 flatUV = uv; flatUV.x /= uA;
                    vec2 mixedUV = mix(flatUV, tunnelUV * 0.8, modeBlend);
                    vec2 cameraUV = abs(mod(mixedUV + 1.0, 2.0) - 1.0);
                    float sOff = (i==0) ? uRGB : (i==2) ? -uRGB : 0.0;
                    vec3 smp = sampleCamera(cameraUV, sOff);
                    if (uMode > 0.01) {
                        if (uTHueStr > 0.01) { float hueArg = (mixedUV.y * 0.5) + uTHuePos; vec3 rainbow = 0.5 + 0.5 * cos(6.28318 * (hueArg + vec3(0.0, 0.33, 0.67))); smp = mix(smp, smp * rainbow * 2.0, uTHueStr * uMode); }
                        if (uTWaveStr > 0.01) { float waveDomain = mixedUV.y - (uTWavePos * 10.0); float distFromWave = abs(fract(waveDomain) - 0.5); float width = 0.15 + (uTWaveStr * 0.2); float wavePulse = smoothstep(width, 0.0, distFromWave); wavePulse = wavePulse * wavePulse; float intensity = (uTWaveStr * uTWaveStr) * 0.8; vec3 waveColor = vec3(0.5, 0.8, 1.0) * wavePulse * intensity; smp += waveColor; }
                    }
                    if(i==0) finalColor.r = smp.r; else if(i==1) finalColor.g = smp.g; else finalColor.b = smp.b;
                }
                finalColor = abs(finalColor - uSol);
                if(uHue > 0.01) finalColor = hueShift(finalColor, uHue * 6.28318);
                finalColor = (finalColor - 0.5) * uC + 0.5;
                float l = dot(finalColor, vec3(0.299, 0.587, 0.114)); finalColor = mix(vec3(l), finalColor, uS);
                if(uBloom > 0.01) finalColor += smoothstep(0.4, 1.0, l) * finalColor * uBloom * 2.0;
                finalColor *= uBrit; 
                gl_FragColor = vec4(finalColor, 1.0);
            }""".trimIndent()

            kaleidoProgram = createProgram(vSrc, fSrc)
            val activeUniforms = IntArray(1); GLES20.glGetProgramiv(kaleidoProgram, GLES20.GL_ACTIVE_UNIFORMS, activeUniforms, 0)
            val lenBuf = IntArray(1); val sizeBuf = IntArray(1); val typeBuf = IntArray(1); val nameBuf = ByteArray(256)
            for (i in 0 until activeUniforms[0]) {
                GLES20.glGetActiveUniform(kaleidoProgram, i, 256, lenBuf, 0, sizeBuf, 0, typeBuf, 0, nameBuf, 0)
                val name = String(nameBuf, 0, lenBuf[0]); val loc = GLES20.glGetUniformLocation(kaleidoProgram, name)
                if (loc != -1) uLocs[name] = loc
            }

            val vSrcSimple = "attribute vec4 p; attribute vec2 t; varying vec2 v; uniform mat4 uMVPMatrix; void main() { gl_Position = uMVPMatrix * p; v = t; }"
            val fSrcSimple = "precision mediump float; varying vec2 v; uniform sampler2D uTex; void main() { gl_FragColor = texture2D(uTex, v); }"
            simpleProgram = createProgram(vSrcSimple, fSrcSimple)
            if (simpleProgram != 0) {
                simpleULocs["uTex"] = GLES20.glGetUniformLocation(simpleProgram, "uTex")
                simpleULocs["uMVPMatrix"] = GLES20.glGetUniformLocation(simpleProgram, "uMVPMatrix")
            }

            cameraTexId = createOESTex()
            surfaceTexture = SurfaceTexture(cameraTexId)
            initFBO(FIXED_WIDTH, FIXED_HEIGHT)
            GLES20.glUseProgram(kaleidoProgram)
            uLocs["uA"]?.let { GLES20.glUniform1f(it, FIXED_WIDTH.toFloat() / FIXED_HEIGHT.toFloat()) }
            pBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0) }
            tBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)).position(0) }
            ctx.runOnUiThread { ctx.startCamera() }
        }

        private fun initFBO(w: Int, h: Int) {
            if (fboId != 0) { val fb = IntArray(1) { fboId }; val tx = IntArray(1) { fboTexId }; GLES20.glDeleteFramebuffers(1, fb, 0); GLES20.glDeleteTextures(1, tx, 0) }
            fboWidth = w; fboHeight = h; val fb = IntArray(1); val tx = IntArray(1); GLES20.glGenFramebuffers(1, fb, 0); GLES20.glGenTextures(1, tx, 0)
            fboId = fb[0]; fboTexId = tx[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTexId, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { if (w == 0 || h == 0) return; viewWidth = w; viewHeight = h; isSurfaceReady = true }

        override fun onDrawFrame(gl: GL10?) {
            if (!isSurfaceReady) return
            val now = System.nanoTime()
            deltaTime = (now - lastTime) / 1e9f
            lastTime = now

            // --- 1. Rotation Interpolation (GL Thread) ---
            if (isRotAnimating && rotTargetM != null) {
                rotAnimTime += deltaTime
                if (rotAnimTime >= rotAnimDuration) {
                    mRotAccum = rotTargetM!!
                    cRotAccum = rotTargetC!!
                    isRotAnimating = false
                } else {
                    val t = (rotAnimTime / rotAnimDuration).coerceIn(0f, 1f)
                    val ease = 1f - (1f - t).pow(3f)
                    mRotAccum = rotStartM + (rotTargetM!! - rotStartM) * ease
                    cRotAccum = rotStartC + (rotTargetC!! - rotStartC) * ease
                }
            }

            // --- 2. Update Controls (Physics + Animation) ---
            ctx.controls.forEach { it.update(deltaTime) }

            try { surfaceTexture?.updateTexImage() } catch (e: Exception) { return }
            manageSurfaces()
            updateMovementPhysics(deltaTime)
            renderToFBO()
            renderToScreen()
            renderToExternal()
            renderToRecorder()
            handleCapture()
        }

        private fun updateMovementPhysics(d: Float) {
            val speedCtrl = ctx.controlsMap["S_SPEED"] ?: return
            val rawVal = speedCtrl.getNormalized() - 0.5f
            val sign = sign(rawVal)
            val curvedSpeed = sign * (abs(rawVal) * 2.0f).pow(2.2f)
            scrollAccum += curvedSpeed * d * 0.6f
            val mRotCtrl = ctx.controlsMap["M_ROT"] ?: return
            mRotAccum += mRotCtrl.getMapped(-1.5f, 1.5f).toDouble().pow(3.0) * 120.0 * d.toDouble()
            val cRotCtrl = ctx.controlsMap["C_ROT"] ?: return
            cRotAccum += cRotCtrl.getMapped(-1.5f, 1.5f).toDouble().pow(3.0) * 120.0 * d.toDouble()
        }

        private fun renderToFBO() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId); GLES20.glViewport(0, 0, FIXED_WIDTH, FIXED_HEIGHT); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); GLES20.glUseProgram(kaleidoProgram)
            fun safeUni(name: String, v: Float) { uLocs[name]?.let { GLES20.glUniform1f(it, v) } }
            fun safeUni2(name: String, v1: Float, v2: Float) { uLocs[name]?.let { GLES20.glUniform2f(it, v1, v2) } }
            val widthF = fboWidth.toFloat(); val heightF = fboHeight.toFloat(); val aspect = widthF / heightF
            safeUni("uA", aspect)
            val camRatio = 1.777f; val screenRatio = FIXED_WIDTH / FIXED_HEIGHT; var scaleX = 1.0f; var scaleY = 1.0f
            if (screenRatio < camRatio) { scaleX = screenRatio.toFloat() / camRatio; scaleY = 1.0f } else { scaleX = 1.0f; scaleY = camRatio / screenRatio.toFloat() }
            safeUni2("uCamScale", scaleX, scaleY)

            val vMAngle = ctx.controlsMap["M_ANGLE"]?.computedValue ?: 0f; val vMZoom = ctx.controlsMap["M_ZOOM"]?.computedValue ?: 0f; val vMTx = ctx.controlsMap["M_TX"]?.computedValue ?: 0.5f; val vMTy = ctx.controlsMap["M_TY"]?.computedValue ?: 0.5f; val vMTiltX = ctx.controlsMap["M_TILTX"]?.computedValue ?: 0.5f; val vMTiltY = ctx.controlsMap["M_TILTY"]?.computedValue ?: 0.5f; val v3DMix = ctx.controlsMap["3D_MIX"]?.computedValue ?: 0f
            safeUni("uAx", axisCount); safeUni("uMR", (vMAngle * 360f + mRotAccum).toFloat() + 90f); safeUni("uMZ", 0.1f + (vMZoom * 2.5f)); safeUni2("uMT", (vMTx - 0.5f) * 2f, (vMTy - 0.5f) * 2f); safeUni2("uMTilt", (vMTiltX - 0.5f) * 1.5f, (vMTiltY - 0.5f) * 1.5f); safeUni("uMode", v3DMix.pow(2.0f)); safeUni("uScroll", scrollAccum); safeUni("uSShape", ctx.controlsMap["S_SHAPE"]?.computedValue ?: 0f); safeUni("uSFov", ctx.controlsMap["S_FOV"]?.computedValue ?: 0.5f); safeUni("uTHueStr", ctx.controlsMap["T_HUE_STR"]?.computedValue ?: 0f); safeUni("uTHuePos", ctx.controlsMap["T_HUE_POS"]?.computedValue ?: 0f); safeUni("uTWaveStr", ctx.controlsMap["T_WAVE_STR"]?.computedValue ?: 0f); safeUni("uTWavePos", ctx.controlsMap["T_WAVE_POS"]?.computedValue ?: 0f)
            val cRaw = ctx.controlsMap["CURVE"]?.computedValue ?: 0.5f; safeUni("uCurve", if (cRaw > 0.5f) 1.0f + (cRaw - 0.5f) * 6.0f else 0.2f + (cRaw * 1.6f)); safeUni("uTwist", ctx.controlsMap["TWIST"]?.getMapped(-5.0f, 5.0f) ?: 0f); safeUni("uFlux", (ctx.controlsMap["FLUX"]?.computedValue ?: 0f) * 0.2f)
            val vCZoom = ctx.controlsMap["C_ZOOM"]?.computedValue ?: 0f; val vCAngle = ctx.controlsMap["C_ANGLE"]?.computedValue ?: 0f; val vCTx = ctx.controlsMap["C_TX"]?.computedValue ?: 0.5f; val vCTy = ctx.controlsMap["C_TY"]?.computedValue ?: 0.5f; val vCTiltX = ctx.controlsMap["C_TILTX"]?.computedValue ?: 0.5f; val vCTiltY = ctx.controlsMap["C_TILTY"]?.computedValue ?: 0.5f
            safeUni("uCZ", 0.3f + (vCZoom * 2.0f)); safeUni("uCR", (vCAngle * 360f + cRotAccum).toFloat()); safeUni2("uCT", (vCTx - 0.5f), (vCTy - 0.5f)); safeUni2("uCTilt", (vCTiltX - 0.5f) * 1.2f, (vCTiltY - 0.5f) * 1.2f); safeUni2("uF", if (rot180) -flipX else flipX, if (rot180) -flipY else flipY); safeUni("uWarp", ctx.controlsMap["WARP"]?.computedValue ?: 0f)
            safeUni("uC", ctx.controlsMap["CONTRAST"]?.getMapped(0f, 2f) ?: 1f); safeUni("uS", ctx.controlsMap["VIBRANCE"]?.getMapped(0f, 2f) ?: 1f); safeUni("uHue", ctx.controlsMap["HUE"]?.computedValue ?: 0f); safeUni("uSol", ctx.controlsMap["NEG"]?.computedValue ?: 0f); safeUni("uBloom", ctx.controlsMap["GLOW"]?.computedValue ?: 0f); safeUni("uRGB", (ctx.controlsMap["RGB"]?.computedValue ?: 0f) * 0.05f); safeUni("uMRGB", (ctx.controlsMap["M_RGB"]?.computedValue ?: 0f) * 0.1f); safeUni("uBrit", ctx.controlsMap["BRIT"]?.getMapped(0.0f, 2.0f) ?: 1.0f)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId); uLocs["uTex"]?.let { GLES20.glUniform1i(it, 0) }
            bindCommonAttribs(kaleidoProgram); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4); GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        private fun renderToScreen() {
            if (simpleProgram == 0) return
            GLES20.glViewport(0, 0, viewWidth, viewHeight); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val isPortrait = viewWidth < viewHeight
            android.opengl.Matrix.setIdentityM(mvpMatrix, 0)
            if (isPortrait) {
                android.opengl.Matrix.rotateM(mvpMatrix, 0, -90f, 0f, 0f, 1f)
                val imageAspect = FIXED_HEIGHT.toFloat() / FIXED_WIDTH.toFloat(); val screenAspect = viewWidth.toFloat() / viewHeight.toFloat(); val scaleX = imageAspect / screenAspect
                android.opengl.Matrix.scaleM(mvpMatrix, 0, scaleX, 1f, 1f)
            }
            GLES20.glUseProgram(simpleProgram); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
            GLES20.glUniform1i(simpleULocs["uTex"] ?: -1, 0); GLES20.glUniformMatrix4fv(simpleULocs["uMVPMatrix"] ?: -1, 1, false, mvpMatrix, 0)
            bindCommonAttribs(simpleProgram); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun renderToExternal() {
            if (extEglSurface != EGL14.EGL_NO_SURFACE) {
                val oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW); val oldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
                if (EGL14.eglMakeCurrent(mSavedDisplay, extEglSurface, extEglSurface, mSavedContext)) {
                    GLES20.glViewport(0, 0, extWidth, extHeight); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); drawSimpleTexture(fboTexId)
                    EGLExt.eglPresentationTimeANDROID(mSavedDisplay, extEglSurface!!, System.nanoTime()); EGL14.eglSwapBuffers(mSavedDisplay, extEglSurface)
                }
                EGL14.eglMakeCurrent(mSavedDisplay, oldDraw, oldRead, mSavedContext)
            }
        }

        private fun renderToRecorder() {
            if (recordSurface != EGL14.EGL_NO_SURFACE && videoRecorder != null) {
                val oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
                val oldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)

                if (EGL14.eglMakeCurrent(mSavedDisplay, recordSurface, recordSurface, mSavedContext)) {
                    // The recorder is fixed at 1920x1080
                    GLES20.glViewport(0, 0, videoRecorder!!.width, videoRecorder!!.height)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                    // Use identityMatrix to match the stable Projector/FBO view
                    // This ignores the phone's physical tilt (Portrait/Landscape)
                    GLES20.glUseProgram(simpleProgram)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
                    GLES20.glUniform1i(simpleULocs["uTex"] ?: -1, 0)

                    // Critical: Use identityMatrix here, NOT a rotation matrix
                    GLES20.glUniformMatrix4fv(simpleULocs["uMVPMatrix"] ?: -1, 1, false, identityMatrix, 0)

                    bindCommonAttribs(simpleProgram)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

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
            if (simpleProgram == 0) return
            GLES20.glUseProgram(simpleProgram); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(simpleULocs["uTex"] ?: -1, 0); GLES20.glUniformMatrix4fv(simpleULocs["uMVPMatrix"] ?: -1, 1, false, identityMatrix, 0)
            bindCommonAttribs(simpleProgram); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun bindCommonAttribs(prog: Int) {
            val pL = GLES20.glGetAttribLocation(prog, "p"); val tL = GLES20.glGetAttribLocation(prog, "t")
            GLES20.glEnableVertexAttribArray(pL); GLES20.glVertexAttribPointer(pL, 2, GLES20.GL_FLOAT, false, 0, pBuf)
            GLES20.glEnableVertexAttribArray(tL); GLES20.glVertexAttribPointer(tL, 2, GLES20.GL_FLOAT, false, 0, tBuf)
        }
        private fun createProgram(vSrc: String, fSrc: String): Int {
            val vShader = compile(GLES20.GL_VERTEX_SHADER, vSrc); val fShader = compile(GLES20.GL_FRAGMENT_SHADER, fSrc)
            if (vShader == 0 || fShader == 0) return 0
            val prog = GLES20.glCreateProgram(); GLES20.glAttachShader(prog, vShader); GLES20.glAttachShader(prog, fShader); GLES20.glLinkProgram(prog)
            val linkStatus = IntArray(1); GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) { Log.e("GL", "Link Failed: " + GLES20.glGetProgramInfoLog(prog)); GLES20.glDeleteProgram(prog); return 0 }
            return prog
        }
        private fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, src); GLES20.glCompileShader(shader)
            val compiled = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) { Log.e("GL", "Compile Failed: " + GLES20.glGetShaderInfoLog(shader)); GLES20.glDeleteShader(shader); return 0 }
            return shader
        }
        private fun createOESTex(): Int { val t=IntArray(1); GLES20.glGenTextures(1,t,0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,t[0]); GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR); GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR); return t[0] }
        private fun setupEGL() { mSavedDisplay = EGL14.eglGetCurrentDisplay(); mSavedContext = EGL14.eglGetCurrentContext(); val currentConfigId = IntArray(1); EGL14.eglQueryContext(mSavedDisplay, mSavedContext, EGL14.EGL_CONFIG_ID, currentConfigId, 0); val configs = arrayOfNulls<EGL14EGLConfig>(1); val num = IntArray(1); EGL14.eglChooseConfig(mSavedDisplay, intArrayOf(EGL14.EGL_CONFIG_ID, currentConfigId[0], EGL14.EGL_NONE), 0, configs, 0, 1, num, 0); mEglConfig = configs[0] }
        private fun manageSurfaces() {
            val args = extSurfaceArgs
            if (args != null && extEglSurface == EGL14.EGL_NO_SURFACE) { val rawSurf = args.first; extWidth = args.second; extHeight = args.third; extEglSurface = EGL14.eglCreateWindowSurface(mSavedDisplay, mEglConfig, rawSurf, intArrayOf(EGL14.EGL_NONE), 0) }
            if (args == null && extEglSurface != EGL14.EGL_NO_SURFACE) { EGL14.eglDestroySurface(mSavedDisplay, extEglSurface); extEglSurface = EGL14.EGL_NO_SURFACE }
            if (pendingRecordFile != null) { videoRecorder = VideoRecorder(ctx, viewWidth, viewHeight, pendingRecordFile!!); recordSurface = EGL14.eglCreateWindowSurface(mSavedDisplay, mEglConfig, videoRecorder!!.inputSurface, intArrayOf(EGL14.EGL_NONE), 0); pendingRecordFile = null }
        }
        private fun handleStopRecording() { if (isStopRequested) { videoRecorder?.drain(true); val out = videoRecorder?.file; if (recordSurface != EGL14.EGL_NO_SURFACE) { EGL14.eglDestroySurface(mSavedDisplay, recordSurface); recordSurface = EGL14.EGL_NO_SURFACE }; videoRecorder?.release(); videoRecorder = null; isStopRequested = false; onStopCallback?.invoke(out) } }
        private fun handleCapture() {
            if (captureRequested) {
                captureRequested = false; val b = ByteBuffer.allocate(fboWidth * fboHeight * 4); GLES20.glReadPixels(0, 0, fboWidth, fboHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
                Thread {
                    val bmp = Bitmap.createBitmap(fboWidth, fboHeight, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(b) }
                    val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "SB_${System.currentTimeMillis()}.jpg"); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SpaceBeam") }
                    ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri -> ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
                }.start()
            }
        }
    }
}
class VideoRecorder(private val context: Context, val rawWidth: Int, val rawHeight: Int, val file: File) {

    private var muxer: MediaMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var muxerStarted = false

    // --- VIDEO VARIABLES ---
    private var videoEncoder: MediaCodec
    val inputSurface: Surface
    private var videoTrackIndex = -1

    // Expose the "Safe" dimensions to the Renderer
    val width: Int
    val height: Int

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
        // 1. Calculate Safe Dimensions (Multiple of 16)
        // We calculate this once and store it in the public properties
        width = 1920
        height = 1080

        // 2. Configure Format
        val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Keyframe every 1 second

            // FIX: Enforce Baseline Profile.
            // This ensures maximum compatibility for playback on the same device.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel3)
            }
        }

        // Use standard AVC (H.264)
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = videoEncoder.createInputSurface()
        videoEncoder.start()

        setupAudio()
    }

    // ... (Keep the rest of the class: setupAudio, drain, audioLoop, drainEncoder, startMuxerIfReady, release same as before) ...
    // Just ensure you include the rest of the functions from the previous version here.

    private fun setupAudio() {
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = minBufferSize * 4

            try {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    // In a real app, handle this. Here we assume permission is checked in MainActivity
                    return
                }
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            } catch (e: Exception) {
                Log.e("VideoRecorder", "Audio Init Failed", e)
                audioRecord = null
                return
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
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
                        val pts = (totalBytesRead * 1_000_000L) / (sampleRate * 2)
                        audioEncoder!!.queueInputBuffer(inputBufferIndex, 0, readBytes, pts, 0)
                    }
                    drainEncoder(audioEncoder!!, isVideo = false)
                } catch (e: Exception) { }
            }
        }
    }

    private fun drainEncoder(encoder: MediaCodec, isVideo: Boolean) {
        val timeoutUs = if (isVideo) 0L else 10000L
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
                    if (isVideo) videoTrackIndex = muxer.addTrack(newFormat)
                    else audioTrackIndex = muxer.addTrack(newFormat)
                    startMuxerIfReady()
                }
            } else if (idx >= 0) {
                val encodedData = encoder.getOutputBuffer(idx) ?: continue
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bufferInfo.size = 0
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
        } catch (e: Exception) { }
    }

    // Note: Add 'context' to constructor or pass it to setupAudio if specific permission checks needed inside class,
    // but assuming permission is already granted in MainActivity, this logic holds.
}