package com.calmyjane.spacebeam

import android.Manifest
import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

// Use type aliases to distinguish between the incompatible EGL classes
import javax.microedition.khronos.egl.EGLConfig as GL10EGLConfig
import android.opengl.EGLConfig as EGL14EGLConfig

// --- REFACTORED SLIDER OBJECT ---

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
        mainSeekBar?.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60)
        container.addView(mainSeekBar)

        if (hasModulation) {
            val modContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 0, 0, 0)
            }
            modContainer.addView(createSubRow("SPEED", modRate) { p -> setModRate(p) }.also { rateSeekBar = it.second }.first)
            modContainer.addView(createSubRow("DEPTH", modDepth) { p -> setModDepth(p) }.also { depthSeekBar = it.second }.first)
            container.addView(modContainer)
        }
        parent.addView(container)
    }

    private fun createSubRow(label: String, startVal: Int, onChange: (Int) -> Unit): Pair<LinearLayout, SeekBar> {
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
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { if(f) listener(p) } // Only trigger on user touch to avoid loops
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
    private var readabilityLevel = 0

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

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        }
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 10) else startCamera()
    }

    fun startCamera() {
        val cpFuture = ProcessCameraProvider.getInstance(this)
        cpFuture.addListener({
            val provider = cpFuture.get()
            val preview = Preview.Builder().setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9).build()
            preview.setSurfaceProvider { req -> renderer.provideSurface(req) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, currentSelector, preview)
            } catch (e: Exception) { }
        }, ContextCompat.getMainExecutor(this))
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
                controlsMap["M_ANGLE"]?.let {
                    it.setProgress((it.value - (dAngle * (1000f / 360f)).toInt() + 1000) % 1000)
                }
            }
            lastFingerDist = dist; lastFingerAngle = angle; lastFingerFocusX = focusX; lastFingerFocusY = focusY
        } else if (event.action == MotionEvent.ACTION_UP) {
            if (event.eventTime - event.downTime < 200) toggleHud()
        }
    }

    private fun textToIcon(t: String, size: Float = 60f, color: Int = Color.WHITE): BitmapDrawable {
        val b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint().apply { this.color = color; textSize = size; textAlign = Paint.Align.CENTER; isFakeBoldText = true; isAntiAlias = true }
        c.drawText(t, 80f, 80f + (size/3f), p); return BitmapDrawable(resources, b)
    }
    private fun createGalleryDrawable(): BitmapDrawable {
        val b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 8f; isAntiAlias = true; strokeJoin = Paint.Join.ROUND }
        c.drawRoundRect(RectF(50f, 30f, 130f, 110f), 12f, 12f, p)
        p.style = Paint.Style.FILL; p.color = Color.BLACK; val front = RectF(30f, 50f, 110f, 130f); c.drawRoundRect(front, 12f, 12f, p)
        p.style = Paint.Style.STROKE; p.color = Color.WHITE; c.drawRoundRect(front, 12f, 12f, p)
        val path = Path().apply { moveTo(38f, 115f); lineTo(60f, 85f); lineTo(80f, 105f); lineTo(95f, 75f); lineTo(105f, 115f) }; c.drawPath(path, p)
        c.drawCircle(90f, 70f, 8f, p); return BitmapDrawable(resources, b)
    }
    private fun createClockDrawable(): BitmapDrawable {
        val b = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true }
        c.drawCircle(50f, 55f, 35f, p); c.drawLine(50f, 55f, 50f, 35f, p); c.drawLine(50f, 55f, 65f, 55f, p)
        c.drawLine(40f, 15f, 60f, 15f, p); c.drawLine(50f, 15f, 50f, 20f, p); return BitmapDrawable(resources, b)
    }
    private fun createLogoDrawable(): ShapeDrawable {
        val p = Path().apply { moveTo(46f, 131f); lineTo(46f, 162f); lineTo(159f, 162f); lineTo(159f, 144f); lineTo(64f, 144f); lineTo(64f, 131f); close() }
        return ShapeDrawable(PathShape(p, 200f, 200f)).apply { paint.color = Color.WHITE; paint.isAntiAlias = true }
    }
    private fun createLockDrawable(locked: Boolean): BitmapDrawable {
        val b = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 8f; isAntiAlias = true }
        val shackle = RectF(30f, 20f, 70f, 60f); if (locked) c.drawArc(shackle, 180f, 180f, false, p) else c.drawArc(shackle, 160f, 180f, false, p)
        p.style = Paint.Style.FILL; c.drawRoundRect(RectF(25f, 50f, 75f, 85f), 8f, 8f, p); return BitmapDrawable(resources, b)
    }

    private fun setupOverlayHUD() {
        overlayHUD = FrameLayout(this).apply { layoutParams = FrameLayout.LayoutParams(-1, -1) }
        flashOverlay = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f; layoutParams = FrameLayout.LayoutParams(-1, -1) }
        val logoView = ImageView(this).apply { setImageDrawable(createLogoDrawable()); alpha = 0.4f; layoutParams = FrameLayout.LayoutParams(180, 180).apply { gravity = Gravity.TOP or Gravity.START; topMargin = 40; leftMargin = 40 } }

        leftHUDContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = FrameLayout.LayoutParams(-2, -1).apply { gravity = Gravity.START } }

        parameterPanel = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(850, -1); layoutDirection = View.LAYOUT_DIRECTION_RTL; isVerticalScrollBarEnabled = true; scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        }
        val menuLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 20, 240); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        parameterPanel.addView(menuLayout)

        parameterToggleContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(140, -1)
            parameterToggleBtn = Button(this@MainActivity).apply {
                text = "<"; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.8f; textSize = 32f; layoutParams = FrameLayout.LayoutParams(-1, 400, Gravity.CENTER_VERTICAL); setOnClickListener { toggleMenu() }
            }
            addView(parameterToggleBtn)
        }
        leftHUDContainer.addView(parameterPanel); leftHUDContainer.addView(parameterToggleContainer)

        val axisContainer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 10) }

        val axisCtrl = PropertyControl(this, "AXIS", "COUNT", min = 0, max = 15, defaultValue = 1).apply {
            // This is manually updated by the SeekBar listener below
        }
        controls.add(axisCtrl); controlsMap["AXIS"] = axisCtrl

        axisSb = SeekBar(this).apply {
            max = 15; progress = 1; layoutParams = LinearLayout.LayoutParams(540, 65); thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(16, 32) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    renderer.axisCount = (p + 1).toFloat()
                    axisCtrl.setProgress(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        lockBtn = Button(this).apply {
            background = createLockDrawable(axisLocked); layoutParams = LinearLayout.LayoutParams(80, 80).apply { leftMargin = 20 }
            setOnClickListener { axisLocked = !axisLocked; background = createLockDrawable(axisLocked); alpha = if(axisLocked) 1.0f else 0.4f }
            alpha = if(axisLocked) 1.0f else 0.4f
        }
        axisContainer.addView(TextView(this).apply { text = "COUNT"; setTextColor(Color.WHITE); textSize = 8f; minWidth = 140; alpha = 0.8f }); axisContainer.addView(axisSb); axisContainer.addView(lockBtn); menuLayout.addView(axisContainer)

        // --- DYNAMIC CONTROLS ---

        fun addControl(c: PropertyControl) {
            controls.add(c)
            controlsMap[c.id] = c
            c.attachTo(menuLayout)
        }

        addHeader(menuLayout, "MASTER")
        // WRAP: 360 degrees just rolls over
        addControl(PropertyControl(this, "M_ANGLE", "ANGLE", defaultValue = 0, hasModulation = true, modMode = PropertyControl.ModMode.WRAP))
        addControl(PropertyControl(this, "M_ROT", "ROTATION", defaultValue = 500))
        // MIRROR: Zoom shouldn't snap, it should bounce back
        addControl(PropertyControl(this, "M_ZOOM", "ZOOM", defaultValue = 300, hasModulation = true, modMode = PropertyControl.ModMode.MIRROR))
        addControl(PropertyControl(this, "M_TX", "TRANSLATE X", defaultValue = 500, hasModulation = true, modMode = PropertyControl.ModMode.MIRROR))
        addControl(PropertyControl(this, "M_TY", "TRANSLATE Y", defaultValue = 500, hasModulation = true, modMode = PropertyControl.ModMode.MIRROR))
        addControl(PropertyControl(this, "M_TILTX", "TILT X", defaultValue = 500, hasModulation = true))
        addControl(PropertyControl(this, "M_TILTY", "TILT Y", defaultValue = 500, hasModulation = true))
        addControl(PropertyControl(this, "M_RGB", "RGB SHIFT", defaultValue = 0, hasModulation = true, modMode = PropertyControl.ModMode.MIRROR))

        addHeader(menuLayout, "CAMERA")
        addControl(PropertyControl(this, "C_ANGLE", "ANGLE", defaultValue = 0, hasModulation = true, modMode = PropertyControl.ModMode.WRAP))
        addControl(PropertyControl(this, "C_ROT", "ROTATION", defaultValue = 500))
        addControl(PropertyControl(this, "WARP", "WARP DISTORT", defaultValue = 1000))
        addControl(PropertyControl(this, "C_ZOOM", "ZOOM", defaultValue = 300, hasModulation = true, modMode = PropertyControl.ModMode.MIRROR))
        addControl(PropertyControl(this, "C_TX", "TRANSLATE X", defaultValue = 500, hasModulation = true, modMode = PropertyControl.ModMode.MIRROR))
        addControl(PropertyControl(this, "C_TY", "TRANSLATE Y", defaultValue = 500, hasModulation = true, modMode = PropertyControl.ModMode.MIRROR))
        addControl(PropertyControl(this, "C_TILTX", "TILT X", defaultValue = 500, hasModulation = true))
        addControl(PropertyControl(this, "C_TILTY", "TILT Y", defaultValue = 500, hasModulation = true))
        addControl(PropertyControl(this, "RGB", "RGB SHIFT", defaultValue = 0, hasModulation = true, modMode = PropertyControl.ModMode.MIRROR))

        addHeader(menuLayout, "COLORS")
        addControl(PropertyControl(this, "HUE", "HUE", defaultValue = 0, hasModulation = true, modMode = PropertyControl.ModMode.WRAP))
        addControl(PropertyControl(this, "NEG", "NEGATIVE", defaultValue = 0, hasModulation = true, modMode = PropertyControl.ModMode.MIRROR))
        addControl(PropertyControl(this, "GLOW", "GLOW", defaultValue = 0, hasModulation = true, modMode = PropertyControl.ModMode.MIRROR))
        addControl(PropertyControl(this, "CONTRAST", "CONTRAST", defaultValue = 500))
        addControl(PropertyControl(this, "VIBRANCE", "VIBRANCE", defaultValue = 500))
        // --- UI BUTTONS & LAYOUTS ---

        cameraSettingsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(10, 20, 10, 20) }
        fun createSideBtn(action: () -> Unit) = ImageButton(this).apply { setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.3f; layoutParams = LinearLayout.LayoutParams(100, 100); setOnClickListener { action(); updateSidebarVisuals() } }
        cameraSettingsPanel.addView(createSideBtn { currentSelector = if (currentSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA; startCamera() }.apply { setImageResource(android.R.drawable.ic_menu_camera) })
        flipXBtn = createSideBtn { renderer.flipX = if(renderer.flipX == 1f) -1f else 1f }.apply { setImageDrawable(textToIcon("↔")) }
        flipYBtn = createSideBtn { renderer.flipY = if(renderer.flipY == 1f) -1f else 1f }.apply { setImageDrawable(textToIcon("↕")) }
        rot180Btn = createSideBtn { renderer.rot180 = !renderer.rot180 }.apply { setImageResource(android.R.drawable.ic_menu_rotate) }
        cameraSettingsPanel.addView(flipXBtn); cameraSettingsPanel.addView(flipYBtn); cameraSettingsPanel.addView(rot180Btn)

        recordControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(20, 10, 20, 10)
            translationX = resources.displayMetrics.widthPixels * 0.2f
        }
        photoBtn = ImageButton(this).apply { setImageDrawable(textToIcon("[ ]", 50f)); setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE); alpha = 0.8f; scaleX = 1.5f; scaleY = 1.5f; layoutParams = LinearLayout.LayoutParams(150, 150); setOnClickListener { renderer.capturePhoto(); triggerFlashPulse() } }
        recordBtn = ImageButton(this).apply { setImageDrawable(textToIcon("REC", 40f)); setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE); alpha = 0.5f; layoutParams = LinearLayout.LayoutParams(150, 150).apply { leftMargin = 40 }; setOnClickListener { toggleRecording() } }
        recordControls.addView(photoBtn); recordControls.addView(recordBtn)

        presetPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(15, 10, 15, 30) }
        val transContainer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(10, 0, 10, 10) }
        val clockIcon = ImageView(this).apply { setImageDrawable(createClockDrawable()); alpha = 0.5f; layoutParams = LinearLayout.LayoutParams(45, 45).apply { rightMargin = 10 } }
        val timeLabel = TextView(this).apply { text = "1.0s"; setTextColor(Color.WHITE); textSize = 9f; setPadding(4, 0, 8, 0) }
        val transSeekBar = SeekBar(this).apply {
            max = 1000; progress = 333; layoutParams = LinearLayout.LayoutParams(500, 45); thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(12, 24) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { transitionMs = ((p/1000f).pow(3.0f)*30000).toLong(); timeLabel.text = "%.1fs".format(transitionMs/1000f) }; override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} })
        }
        transContainer.addView(clockIcon); transContainer.addView(timeLabel); transContainer.addView(transSeekBar)
        val presetRow = FrameLayout(this); val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun createPresetBtn(idx: Int) = Button(this).apply { text = idx.toString(); setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.8f; textSize = 16f; layoutParams = LinearLayout.LayoutParams(80, 140); setPadding(0, 0, 0, 20); setOnClickListener { applyPreset(idx) }; setOnLongClickListener { pendingSaveIndex = idx; saveConfirmBtn.visibility = View.VISIBLE; saveConfirmBtn.text = "SAVE $idx?"; true } }
        (8 downTo 1).forEach { val b = createPresetBtn(it); presetButtons[it] = b; btnRow.addView(b) }
        saveConfirmBtn = Button(this).apply { visibility = View.GONE; setTextColor(Color.BLACK); textSize = 12f; setTypeface(null, Typeface.BOLD); background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8f }; layoutParams = FrameLayout.LayoutParams(250, 100, Gravity.CENTER); setOnClickListener { pendingSaveIndex?.let { savePreset(it) }; visibility = View.GONE } }
        presetRow.addView(btnRow); presetRow.addView(saveConfirmBtn); presetPanel.addView(transContainer); presetPanel.addView(presetRow)

        galleryBtn = ImageButton(this).apply { setImageDrawable(createGalleryDrawable()); setColorFilter(Color.WHITE); alpha = 0.4f; layoutParams = FrameLayout.LayoutParams(100, 100).apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 250; rightMargin = 45 }; setOnClickListener { openGallery() } }
        readabilityBtn = ImageButton(this).apply { setImageResource(android.R.drawable.ic_menu_view); setColorFilter(Color.WHITE); alpha = 0.4f; layoutParams = FrameLayout.LayoutParams(120, 120).apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 140; rightMargin = 35 }; setOnClickListener { toggleReadability() } }
        resetBtn = ImageButton(this).apply { setImageResource(android.R.drawable.ic_menu_close_clear_cancel); setColorFilter(Color.WHITE); alpha = 0.4f; layoutParams = FrameLayout.LayoutParams(120, 120).apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 30; rightMargin = 35 }; setOnClickListener { globalReset() } }

        overlayHUD.addView(flashOverlay); overlayHUD.addView(logoView); overlayHUD.addView(leftHUDContainer); overlayHUD.addView(recordControls, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = 30 }); overlayHUD.addView(cameraSettingsPanel, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.END; topMargin = 40; rightMargin = 40 }); overlayHUD.addView(presetPanel, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 15; rightMargin = 180 }); overlayHUD.addView(galleryBtn); overlayHUD.addView(readabilityBtn); overlayHUD.addView(resetBtn)
        addContentView(overlayHUD, ViewGroup.LayoutParams(-1, -1)); updateSidebarVisuals()
    }

    private fun applyReadabilityStyle() {
        val getBg = { alpha: Int -> GradientDrawable().apply { setColor(Color.argb(alpha, 5, 5, 5)); setStroke(3, Color.argb(180, 40, 40, 40)); cornerRadius = 45f; shape = GradientDrawable.RECTANGLE } }
        val panels = listOf(leftHUDContainer, cameraSettingsPanel, presetPanel, recordControls)
        val utils = listOf(galleryBtn, readabilityBtn, resetBtn)
        panels.forEach { it.background = null; it.setPadding(30, 30, 30, 30); it.clipToOutline = true }

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
            if (enabled) view.setShadowLayer(50f, 0f, 0f, Color.BLACK) else view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        } else if (view is ImageButton || view is Button) {
            view.elevation = if (enabled) 50f else 0f
        }
        if (view is ViewGroup) (0 until view.childCount).forEach { applyRecursiveGlow(view.getChildAt(it), enabled) }
    }

    private fun toggleReadability() { readabilityLevel = (readabilityLevel + 1) % 3; applyReadabilityStyle() }
    private fun openGallery() {
        try {
            val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            startActivity(intent)
        } catch (e: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW).apply { type = "image/*"; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            try { startActivity(fallback) } catch (ex: Exception) { Toast.makeText(this, "Gallery app not found", Toast.LENGTH_SHORT).show() }
        }
    }

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
                runOnUiThread { recordBtn.setImageDrawable(textToIcon("REC", 40f)); recordBtn.alpha = 0.5f; if (savedFile != null && savedFile.exists()) saveVideoToGallery(savedFile) }
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
        } catch (e: Exception) { }
    }

    private fun globalReset() {
        currentAnimator?.cancel();
        renderer.mRotAccum = 0.0
        renderer.cRotAccum = 0.0
        renderer.lRotAccum = 0.0
        renderer.resetPhases()
        renderer.flipX = 1f; renderer.flipY = -1f; renderer.rot180 = false; activePreset = -1; updatePresetHighlights()
        controls.forEach { it.reset() }

        // Force Axis Reset
        renderer.axisCount = 2.0f
        axisSb.progress = 1
        controlsMap["AXIS"]?.setProgress(1)
        renderer

        updateSidebarVisuals()
    }

    private fun updatePresetHighlights() { presetButtons.forEach { (idx, btn) -> btn.background = GradientDrawable().apply { setColor(Color.TRANSPARENT); if (idx == activePreset) setStroke(4, Color.WHITE); cornerRadius = 12f } } }
    private fun triggerFlashPulse() { flashOverlay.alpha = 0.6f; flashOverlay.animate().alpha(0f).setDuration(400).start(); photoBtn.animate().scaleX(1.8f).scaleY(1.8f).setDuration(100).withEndAction { photoBtn.animate().scaleX(1.5f).scaleY(1.5f).setDuration(200).start() }.start() }
    private fun updateSidebarVisuals() { flipXBtn.alpha = if (renderer.flipX < 0f) 1.0f else 0.3f; flipYBtn.alpha = if (renderer.flipY > 0f) 1.0f else 0.3f; rot180Btn.alpha = if (renderer.rot180) 1.0f else 0.3f }
    private fun addHeader(m: LinearLayout, t: String) = m.addView(TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 8f; alpha = 0.3f; setPadding(0, 45, 0, 5) })
    private fun hideSystemUI() { window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) }

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

        // Snapshot current STATE as Floats (using preciseValue)
        val startValues = controls.associate { it.id to it.preciseValue }
        val startRates = controls.associate { it.id to it.preciseModRate }
        val startDepths = controls.associate { it.id to it.preciseModDepth }

        currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = transitionMs
            // Use Linear Interpolator for pure calculation, or AccelerateDecelerate for feel
            // interpolator = android.view.animation.LinearInterpolator()

            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                controls.forEach { control ->
                    if (control.id == "AXIS") return@forEach

                    val target = p.controlSnapshots[control.id]

                    if (target != null) {
                        // Calculate Float interpolation
                        val sVal = startValues[control.id] ?: 0f
                        val newVal = sVal + (target.value - sVal) * t
                        control.setAnimatedValue(newVal) // Updates float internally

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
                renderer.flipX = p.flipX
                renderer.flipY = p.flipY
                renderer.rot180 = p.rot180
                updateSidebarVisuals()
            }
            start()
        }
    }

    private fun savePreset(idx: Int) {
        val snapshots = controls.filter { it.id != "AXIS" }.associate { it.id to it.getSnapshot() }
        val axisVal = controlsMap["AXIS"]?.value ?: 0
        presets[idx] = Preset(
            snapshots,
            renderer.flipX, renderer.flipY, renderer.rot180,
            axisVal + 1
        )
        activePreset = idx
        val newPreset = presets[idx]!!
        logPresetCode(idx, newPreset) // Add this line
        updatePresetHighlights()
        Toast.makeText(this, "Preset Saved", Toast.LENGTH_SHORT).show()
    }

    private fun logPresetCode(idx: Int, p: Preset) {
        val sb = StringBuilder()
        sb.append("presets[$idx] = p(ax=${p.axis}, mRot=${p.controlSnapshots["M_ROT"]?.value ?: 500}")

        // Iterate through all modified controls
        p.controlSnapshots.forEach { (id, snap) ->
            // We skip M_ROT because we handled it above, and AXIS is separate
            if (id != "M_ROT" && id != "AXIS") {
                // Only log if it's not at default (optional, but keeps it clean)
                if (snap.value != 500 || snap.rate != 0 || snap.depth != 0) {
                    sb.append(", \"$id\", ${snap.value}, ${snap.rate}, ${snap.depth}")
                }
            }
        }
        sb.append(")")
        android.util.Log.d("PRESET_STUDIO", sb.toString())
    }

    private fun initDefaultPresets() {
        fun p(
            ax: Int = 1,
            mRot: Int = 500,
            vararg overrides: Any
        ): Preset {
            val baseSnapshots = controls.associate { it.id to it.getSnapshot() }.toMutableMap()
            baseSnapshots["M_ROT"] = PropertyControl.Snapshot(mRot, 0, 0)

            var i = 0
            while(i < overrides.size) {
                val key = overrides[i] as String
                val value = overrides[i+1] as Int
                if (i+3 < overrides.size && overrides[i+2] is Int && overrides[i+3] is Int) {
                    val rate = overrides[i+2] as Int
                    val depth = overrides[i+3] as Int
                    baseSnapshots[key] = PropertyControl.Snapshot(value, rate, depth)
                    i += 4
                } else {
                    baseSnapshots[key] = PropertyControl.Snapshot(value, 0, 0)
                    i += 2
                }
            }
            return Preset(baseSnapshots, 1f, -1f, false, ax)
        }

        // --- PRESET STUDIO DATA ---

        // Preset 1: Gentle Master Zoom LFO
        presets[1] = p(ax=2, mRot=500, "M_ZOOM", 300, 139, 307, "WARP", 1000, 0, 0)

        // Preset 2: Faster Rotation & High Intensity Zoom
        presets[2] = p(ax=2, mRot=615, "M_ZOOM", 248, 293, 383, "WARP", 1000, 0, 0)

        // Preset 3: Master Tilt Perspective Intro
        presets[3] = p(ax=2, mRot=673, "M_ZOOM", 268, 293, 559, "M_TILTX", 553, 305, 880, "M_TILTY", 500, 353, 1000, "WARP", 1000, 0, 0)

        // Preset 4: Tilt + Drifting Master Translation
        presets[4] = p(ax=2, mRot=673, "M_ZOOM", 268, 293, 517, "M_TX", 500, 159, 624, "M_TY", 500, 309, 753, "M_TILTX", 553, 305, 880, "M_TILTY", 500, 353, 1000, "WARP", 1000, 0, 0)

        // Preset 5: Camera Rotation + No Warp Distort
        presets[5] = p(ax=2, mRot=673, "M_ZOOM", 359, 293, 517, "M_TX", 500, 159, 624, "M_TY", 500, 309, 753, "M_TILTX", 553, 305, 577, "M_TILTY", 500, 353, 854, "C_ROT", 657, 0, 0, "WARP", 0, 0, 0)

        // Preset 6: Drifting Camera Offset + Pulsing Glow
        presets[6] = p(ax=2, mRot=673, "M_ZOOM", 268, 293, 517, "M_TX", 500, 159, 624, "M_TY", 500, 309, 753, "M_TILTX", 553, 305, 1000, "M_TILTY", 500, 353, 1000, "C_ROT", 657, 0, 0, "WARP", 0, 0, 0, "C_TX", 500, 389, 739, "C_TY", 500, 209, 763, "GLOW", 164, 395, 129)

        // Preset 7: Full Dual-Tilt (Master & Camera)
        presets[7] = p(ax=2, mRot=673, "M_ZOOM", 912, 293, 740, "M_TX", 500, 159, 624, "M_TY", 500, 309, 753, "M_TILTX", 553, 305, 1000, "M_TILTY", 500, 353, 1000, "C_ROT", 657, 0, 0, "WARP", 0, 0, 0, "C_TX", 500, 389, 739, "C_TY", 500, 209, 763, "C_TILTX", 500, 287, 677, "C_TILTY", 500, 443, 557, "GLOW", 164, 395, 129)

        // Preset 8: Maximum Distortion & RGB Shift
        presets[8] = p(ax=2, mRot=673, "M_ZOOM", 268, 293, 517, "M_TX", 500, 159, 624, "M_TY", 500, 309, 753, "M_TILTX", 553, 305, 1000, "M_TILTY", 500, 353, 1000, "C_ROT", 657, 0, 0, "WARP", 0, 0, 0, "C_TX", 500, 389, 739, "C_TY", 500, 209, 763, "C_TILTX", 500, 287, 677, "C_TILTY", 500, 443, 557, "RGB", 957, 0, 0, "GLOW", 164, 395, 129)
    }

    private fun toggleHud() { isHudVisible = !isHudVisible; overlayHUD.visibility = if (isHudVisible) View.VISIBLE else View.GONE; if (isHudVisible) hideSystemUI() }
    private fun toggleMenu() { isMenuExpanded = !isMenuExpanded; parameterPanel.visibility = if (isMenuExpanded) View.VISIBLE else View.GONE; parameterToggleBtn.text = if (isMenuExpanded) "<" else ">" }

    inner class KaleidoscopeRenderer(private val ctx: MainActivity) : GLSurfaceView.Renderer {
        private var program = 0
        private var texID = -1
        private var surfaceTexture: SurfaceTexture? = null
        private lateinit var pBuf: FloatBuffer
        private lateinit var tBuf: FloatBuffer
        private var uLocs = mutableMapOf<String, Int>()
        private var viewWidth = 1
        private var viewHeight = 1
        private var lastTime = System.nanoTime()

        // Geometric properties
        var axisCount = 2.0f
        var flipX = 1.0f
        var flipY = -1.0f
        var rot180 = false

        // Rotation Accumulators
        var mRotAccum = 0.0 // Master Rotation
        var cRotAccum = 0.0 // Camera Rotation (NEW)
        var lRotAccum = 0.0 // Leftover from original code (L-System)

        // Media / Capture
        private var captureRequested = false
        private var videoRecorder: VideoRecorder? = null
        private var recordSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
        private var pendingRecordFile: File? = null
        private var onStopCallback: ((File?) -> Unit)? = null
        private var isStopRequested = false
        private var mSavedDisplay = EGL14.EGL_NO_DISPLAY
        private var mSavedContext = EGL14.eglGetCurrentContext()
        private var mEglConfig: EGL14EGLConfig? = null

        fun resetPhases() {
            ctx.controls.forEach { it.lfoPhase = 0.0; it.lfoDrift = 0.0 }
            mRotAccum = 0.0
            cRotAccum = 0.0
            lRotAccum = 0.0
        }

        fun capturePhoto() { captureRequested = true }
        fun startRecording(file: File) { pendingRecordFile = file }
        fun stopRecording(callback: (File?) -> Unit) { onStopCallback = callback; isStopRequested = true }

        override fun onSurfaceCreated(gl: GL10?, config: GL10EGLConfig?) {
            mSavedDisplay = EGL14.eglGetCurrentDisplay()
            mSavedContext = EGL14.eglGetCurrentContext()
            val currentConfigId = IntArray(1)
            EGL14.eglQueryContext(mSavedDisplay, mSavedContext, EGL14.EGL_CONFIG_ID, currentConfigId, 0)
            val configs = arrayOfNulls<EGL14EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(mSavedDisplay, intArrayOf(EGL14.EGL_CONFIG_ID, currentConfigId[0], EGL14.EGL_NONE), 0, configs, 0, 1, numConfigs, 0)
            mEglConfig = configs[0]
            GLES20.glClearColor(0f, 0f, 0f, 1f)

            val vSrc = "attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }"

            val fSrc = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 v;
            uniform samplerExternalOES uTex;
            
            uniform float uMR, uLR, uCR, uCZ, uA, uMZ, uLZ, uAx, uC, uS, uHue, uSol, uBloom, uRGB, uMRGB, uWarp;
            uniform vec2 uMT, uCT, uF;
            uniform vec2 uMTilt, uCTilt;
            
            vec3 sampleCamera(vec2 uv, float rgbShift) {
                // Keep centered logic within small range
                vec2 centered = uv - 0.5;
                
                // --- PERSPECTIVE TILT (CAMERA) ---
                float z = 1.0 + (centered.x * uCTilt.x) + (centered.y * uCTilt.y);
                centered /= max(z, 0.1);
                
                centered *= uCZ;
            
                float aspectFactor = mix(uA, 1.0, uWarp);
                centered.x *= aspectFactor;
            
                // Camera Rotation
                float cr = uCR * 0.01745329; 
                float c = cos(cr);
                float s = sin(cr);
                centered = vec2(centered.x * c - centered.y * s, centered.x * s + centered.y * c);
                
                centered.x /= aspectFactor;
                centered += uCT; // Translation
            
                vec2 rotatedUV = centered + 0.5;
                rotatedUV.x += rgbShift;
                
                // Flip Logic
                rotatedUV = (rotatedUV - 0.5) * uF + 0.5;
                
                // Mirroring using fract and abs - handles tiling smoothly
                vec2 mirroredUV = abs(mod(rotatedUV + 1.0, 2.0) - 1.0);
                
                return texture2D(uTex, mirroredUV).rgb;
            }
            
            void main() {
                vec3 finalColor = vec3(0.0);
                
                // Pre-calculate constants for rotation to avoid repeated math
                float a1 = -uMR * 0.01745329;
                float cosA1 = cos(a1);
                float sinA1 = sin(a1);
                
                float a2 = uLR * 0.01745329;
                float cosA2 = cos(a2);
                float sinA2 = sin(a2);
            
                for(int i=0; i<3; i++) {
                    float mOff = (i==0) ? uMRGB : (i==2) ? -uMRGB : 0.0;
                    vec2 uv = v - 0.5;
                    
                    // --- MASTER PERSPECTIVE ---
                    float zM = 1.0 + (uv.x * uMTilt.x) + (uv.y * uMTilt.y);
                    uv /= max(zM, 0.1);
                    
                    uv.x *= uA; 
                    uv.x += mOff;
                    
                    // Apply Translation and Master Zoom
                    uv = (uv + uMT) * uMZ;
                    
                    // Master Rotation
                    uv = vec2(uv.x * cosA1 - uv.y * sinA1, uv.x * sinA1 + uv.y * cosA1);
                    
                    // Kaleidoscope folding
                    if(uAx > 1.1) {
                        float r = length(uv);
                        float slice = 6.2831853 / uAx;
                        float angle = atan(uv.y, uv.x);
                        // Use small local angle to maintain precision
                        float a = mod(angle, slice);
                        if(mod(uAx, 2.0) < 0.1) a = abs(a - slice * 0.5);
                        uv = vec2(cos(a), sin(a)) * r;
                    }
                    
                    uv *= uLZ;
                    
                    // Secondary Rotation
                    uv = vec2(uv.x * cosA2 - uv.y * sinA2, uv.x * sinA2 + uv.y * cosA2);
                    
                    uv.x /= uA; 
                    
                    // TILE PRECISION FIX: 
                    // Instead of pure fract(uv), we use a mirrored repeat pattern 
                    // that handles large numbers better.
                    vec2 cameraUV = abs(mod(uv + 1.0, 2.0) - 1.0);
                    
                    float sOff = (i==0) ? uRGB : (i==2) ? -uRGB : 0.0;
                    vec3 smp = sampleCamera(cameraUV, sOff);
                    
                    if(i==0) finalColor.r = smp.r;
                    else if(i==1) finalColor.g = smp.g;
                    else finalColor.b = smp.b;
                }
                
                // --- POST PROCESSING ---
                if(uSol > 0.01) finalColor = mix(finalColor, abs(finalColor - uSol), step(0.1, uSol));
                
                if(uHue > 0.001) {
                    const vec3 k = vec3(0.57735); 
                    float hueAngle = uHue * 6.2831853;
                    float ca = cos(hueAngle);
                    finalColor = finalColor * ca + cross(k, finalColor) * sin(hueAngle) + k * dot(k, finalColor) * (1.0 - ca);
                }
                
                finalColor = (finalColor - 0.5) * uC + 0.5;
                float l = dot(finalColor, vec3(0.299, 0.587, 0.114));
                finalColor = mix(vec3(l), finalColor, uS);
                
                if(uBloom > 0.01) finalColor += smoothstep(0.5, 1.0, l) * finalColor * uBloom * 2.5;
                
                gl_FragColor = vec4(finalColor, 1.0);
            }
            """.trimIndent()

            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vSrc))
            GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fSrc))
            GLES20.glLinkProgram(program)

            listOf("uMR", "uLR", "uCR", "uCZ", "uA", "uMZ", "uLZ", "uAx", "uC", "uS", "uHue", "uSol", "uBloom", "uRGB", "uMRGB", "uF", "uMT", "uCT", "uTex", "uWarp", "uMTilt", "uCTilt").forEach {
                uLocs[it] = GLES20.glGetUniformLocation(program, it)
            }

            texID = createOESTex()
            surfaceTexture = SurfaceTexture(texID)

            pBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)).position(0) }
            tBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(floatArrayOf(0f,0f, 1f,0f, 0f,1f, 1f,1f)).position(0) }

            ctx.runOnUiThread { ctx.startCamera() }
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            viewWidth = w
            viewHeight = h
            GLES20.glViewport(0, 0, w, h)
        }

        override fun onDrawFrame(gl: GL10?) {
            val now = System.nanoTime()
            val d = (now - lastTime) / 1e9 // Delta time in seconds
            lastTime = now
            val tpi = 2.0 * PI

            try { surfaceTexture?.updateTexImage() } catch (e: Exception) { return }

            // Start Recording setup if needed
            if (pendingRecordFile != null) {
                videoRecorder = VideoRecorder(viewWidth, viewHeight, pendingRecordFile!!)
                recordSurface = EGL14.eglCreateWindowSurface(mSavedDisplay, mEglConfig, videoRecorder!!.inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
                pendingRecordFile = null
            }

            // --- MODULATION LOGIC ---

            // This function handles the "Ping Pong" (Mirror) vs "Wrap" logic
            fun resolveModulation(id: String): Float {
                val c = ctx.controlsMap[id] ?: return 0f
                val normValue = c.getNormalized()

                if (!c.hasModulation || (c.modRate == 0 && c.modDepth == 0)) return normValue

                // Speed calculation: (0..1) -> nonlinear curve
                val r = c.getModRateNormalized().toDouble()

                // LFO Accumulator
                val drift = c.lfoDrift + (r * 0.4) * d * tpi
                c.lfoDrift = drift

                // Sine Wave (-1 to 1) scaled by depth
                val modSignal = sin(drift).toFloat() * c.getModDepthNormalized()

                var combined = normValue + modSignal

                // Determine Physics based on property type (ModMode)
                if (c.modMode == PropertyControl.ModMode.WRAP) {
                    // WRAP LOGIC: 1.1 becomes 0.1
                    return combined - floor(combined)
                } else {
                    // MIRROR LOGIC: 1.1 becomes 0.9 (Bounces back)
                    val t = combined % 2.0f
                    val res = if (t < 0) t + 2.0f else t
                    return if (res > 1.0f) 2.0f - res else res
                }
            }

            // --- CALCULATE VALUES ---

            // 1. Continuous Rotations (Accumulators)
            val mRotCtrl = ctx.controlsMap["M_ROT"]!!
            val mRotSpd = mRotCtrl.getMapped(-1.5f, 1.5f).toDouble().pow(3.0)
            mRotAccum += mRotSpd * 120.0 * d

            val cRotCtrl = ctx.controlsMap["C_ROT"]!!
            val cRotSpd = cRotCtrl.getMapped(-1.5f, 1.5f).toDouble().pow(3.0)
            cRotAccum += cRotSpd * 120.0 * d

            // Resolve Modulations
            val vMTiltX = resolveModulation("M_TILTX")
            val vMTiltY = resolveModulation("M_TILTY")
            val vCTiltX = resolveModulation("C_TILTX")
            val vCTiltY = resolveModulation("C_TILTY")

            // Pass Master Tilt (-0.8 to 0.8 range)
            GLES20.glUniform2f(uLocs["uMTilt"]!!, (vMTiltX - 0.5f) * 1.6f, (vMTiltY - 0.5f) * 1.6f)

            // Pass Camera Tilt
            GLES20.glUniform2f(uLocs["uCTilt"]!!, (vCTiltX - 0.5f) * 1.6f, (vCTiltY - 0.5f) * 1.6f)

            // 2. Resolve Modulated Values
            // WRAP Controls (Angles)
            val vMAngle = resolveModulation("M_ANGLE")
            val vCAngle = resolveModulation("C_ANGLE")
            val vHue    = resolveModulation("HUE")

            // MIRROR Controls
            val vMZoom  = resolveModulation("M_ZOOM")
            val vMTx    = resolveModulation("M_TX")
            val vMTy    = resolveModulation("M_TY")
            val vMRGB   = resolveModulation("M_RGB")

            // New Camera Controls
            val vCZoom  = resolveModulation("C_ZOOM")
            val vCTx    = resolveModulation("C_TX")
            val vCTy    = resolveModulation("C_TY")

            val vRGB    = resolveModulation("RGB")
            val vNeg    = resolveModulation("NEG")
            val vGlow   = resolveModulation("GLOW")

            // --- RENDER ---

            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            // Pass Uniforms

            // Master Angle
            GLES20.glUniform1f(uLocs["uMR"]!!, (vMAngle * 360f + mRotAccum).toFloat() + 90f)

            // Camera Angle
            GLES20.glUniform1f(uLocs["uCR"]!!, (vCAngle * 360f + cRotAccum).toFloat())
            GLES20.glUniform1f(uLocs["uLR"]!!, 0f)

            GLES20.glUniform1f(uLocs["uA"]!!, viewWidth.toFloat() / viewHeight.toFloat())

            // Master ZOOM (Non-linear)
            val mZoomBase = 0.1 + (vMZoom.toDouble().pow(2.0) * 9.9 * 2)
            GLES20.glUniform1f(uLocs["uMZ"]!!, mZoomBase.toFloat())

            // Camera ZOOM (Mapping for reasonable range)
            // 0.3 (Zoomed In) to 2.0 (Zoomed Out)
            val cZoomBase = 0.3f + (vCZoom * 1.8f)
            GLES20.glUniform1f(uLocs["uCZ"]!!, cZoomBase)

            val vWarp = ctx.controlsMap["WARP"]?.getNormalized() ?: 0f
            GLES20.glUniform1f(uLocs["uWarp"]!!, vWarp)

            // Standard Layer Zoom (fixed at 1.0 for now)
            GLES20.glUniform1f(uLocs["uLZ"]!!, 1.0f)

            GLES20.glUniform1f(uLocs["uAx"]!!, axisCount)

            // Flip Logic
            val effectiveFx = if (rot180) -flipX else flipX
            val effectiveFy = if (rot180) -flipY else flipY
            GLES20.glUniform2f(uLocs["uF"]!!, effectiveFx, effectiveFy)

            // Color/Post-Fx
            GLES20.glUniform1f(uLocs["uC"]!!, ctx.controlsMap["CONTRAST"]!!.getMapped(0f, 2f))
            GLES20.glUniform1f(uLocs["uS"]!!, ctx.controlsMap["VIBRANCE"]!!.getMapped(0f, 2f))
            GLES20.glUniform1f(uLocs["uHue"]!!, vHue)
            GLES20.glUniform1f(uLocs["uSol"]!!, vNeg * 1.5f)
            GLES20.glUniform1f(uLocs["uBloom"]!!, vGlow)
            GLES20.glUniform1f(uLocs["uRGB"]!!, vRGB * 0.05f)
            GLES20.glUniform1f(uLocs["uMRGB"]!!, vMRGB * 0.15f)

            // Master Translations
            val mTxMapped = -2f + (vMTx * 4f)
            val mTyMapped = -2f + (vMTy * 4f)
            GLES20.glUniform2f(uLocs["uMT"]!!, mTxMapped, mTyMapped)

            // Camera Translations
            val cTxMapped = -0.5f + vCTx // Range -0.5 to 0.5
            val cTyMapped = -0.5f + vCTy
            GLES20.glUniform2f(uLocs["uCT"]!!, cTxMapped, cTyMapped)

            // Draw
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texID)
            GLES20.glUniform1i(uLocs["uTex"]!!, 0)

            val pL = GLES20.glGetAttribLocation(program, "p")
            val tL = GLES20.glGetAttribLocation(program, "t")
            GLES20.glEnableVertexAttribArray(pL)
            GLES20.glVertexAttribPointer(pL, 2, GLES20.GL_FLOAT, false, 0, pBuf)
            GLES20.glEnableVertexAttribArray(tL)
            GLES20.glVertexAttribPointer(tL, 2, GLES20.GL_FLOAT, false, 0, tBuf)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // Handle Recording Output
            if (recordSurface != EGL14.EGL_NO_SURFACE && videoRecorder != null) {
                val oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
                val oldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
                if (EGL14.eglMakeCurrent(mSavedDisplay, recordSurface, recordSurface, mSavedContext)) {
                    GLES20.glViewport(0, 0, viewWidth, viewHeight)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    EGLExt.eglPresentationTimeANDROID(mSavedDisplay, recordSurface!!, System.nanoTime())
                    EGL14.eglSwapBuffers(mSavedDisplay, recordSurface)
                    videoRecorder?.drain(false)
                }
                EGL14.eglMakeCurrent(mSavedDisplay, oldDraw, oldRead, mSavedContext)

                if (isStopRequested) {
                    videoRecorder?.drain(true)
                    val out = videoRecorder?.file
                    EGL14.eglDestroySurface(mSavedDisplay, recordSurface)
                    recordSurface = EGL14.EGL_NO_SURFACE
                    videoRecorder?.release()
                    videoRecorder = null
                    isStopRequested = false
                    onStopCallback?.invoke(out)
                }
            }

            // Handle Photo Capture
            if (captureRequested) {
                captureRequested = false
                val b = ByteBuffer.allocate(viewWidth * viewHeight * 4)
                GLES20.glReadPixels(0, 0, viewWidth, viewHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
                Thread {
                    val bmp = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(b) }
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

        fun provideSurface(req: SurfaceRequest) {
            glView.queueEvent {
                surfaceTexture?.let { st ->
                    st.setDefaultBufferSize(req.resolution.width, req.resolution.height)
                    val s = Surface(st)
                    req.provideSurface(s, ContextCompat.getMainExecutor(ctx)) { s.release() }
                }
            }
        }

        private fun createOESTex(): Int {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            return t[0]
        }

        private fun compile(t: Int, s: String): Int = GLES20.glCreateShader(t).apply {
            GLES20.glShaderSource(this, s)
            GLES20.glCompileShader(this)
        }
    }
}

class VideoRecorder(val width: Int, val height: Int, val file: File) {
    private var encoder: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private var muxer: MediaMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val inputSurface: Surface
    private var trackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()

    init {
        val w = if (width % 2 == 0) width else width - 1
        val h = if (height % 2 == 0) height else height - 1
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()
    }

    fun drain(endOfStream: Boolean) {
        if (endOfStream) try { encoder.signalEndOfInputStream() } catch (e: Exception) { }
        while (true) {
            val idx = try { encoder.dequeueOutputBuffer(bufferInfo, 10000) } catch (e: Exception) { -1 }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!endOfStream) break }
            else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                trackIndex = muxer.addTrack(encoder.outputFormat)
                try { muxer.start(); muxerStarted = true } catch (e: Exception) { }
            }
            else if (idx >= 0) {
                val data = encoder.getOutputBuffer(idx) ?: continue
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bufferInfo.size = 0
                if (bufferInfo.size != 0 && muxerStarted) {
                    try {
                        data.position(bufferInfo.offset)
                        data.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, data, bufferInfo)
                    } catch (e: Exception) { }
                }
                encoder.releaseOutputBuffer(idx, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else if (idx < 0 && endOfStream) break
        }
    }

    fun release() {
        try {
            if (muxerStarted) muxer.stop()
            muxer.release()
            encoder.stop()
            encoder.release()
            inputSurface.release()
        } catch (e: Exception) { }
    }
}