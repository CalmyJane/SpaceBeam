package com.calmyjane.spacebeam

import android.Manifest
import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.*
import android.graphics.drawable.shapes.PathShape
import android.opengl.*
import android.os.Bundle
import android.os.Environment
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*
import javax.microedition.khronos.opengles.GL10
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: KaleidoscopeRenderer
    private var currentSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private lateinit var overlayHUD: FrameLayout
    private val sliders = mutableMapOf<String, SeekBar>()
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

    // Added class-level reference to fix ghost background issue
    private lateinit var leftHUDContainer: LinearLayout

    private var axisLocked = true
    private lateinit var lockBtn: Button
    private lateinit var saveConfirmBtn: Button

    private var lastFingerDist = 0f
    private var lastFingerAngle = 0f
    private var lastFingerFocusX = 0f
    private var lastFingerFocusY = 0f

    private data class Preset(val sliderValues: Map<String, Int>, val flipX: Float, val flipY: Float, val rot180: Boolean, val axis: Int)
    private val presets = mutableMapOf<Int, Preset>()
    private var pendingSaveIndex: Int? = null

    private var transitionMs: Long = 1000L
    private var isHudVisible = true
    private var isMenuExpanded = true
    private var isRecording = false
    private var recordingSeconds = 0
    private val handler = Handler(Looper.getMainLooper())
    private var recordTicker: Runnable? = null

    // 0: Off, 1: Dark + Stroke, 2: Natural Glow
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
                sliders["M_TX"]?.let { it.progress = (it.progress - (dx * 500).toInt()).coerceIn(0, 1000) }
                sliders["M_TY"]?.let { it.progress = (it.progress + (dy * 500).toInt()).coerceIn(0, 1000) }
                val scaleFactor = dist / lastFingerDist
                if (scaleFactor > 0) sliders["M_ZOOM"]?.let { it.progress = (it.progress - (log2(scaleFactor) * 300).toInt()).coerceIn(0, 1000) }
                val dAngle = angle - lastFingerAngle
                sliders["M_ANGLE"]?.let { it.progress = (it.progress + (dAngle * (1000f / 360f)).toInt() + 1000) % 1000 }
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

        // Initialize leftHUDContainer
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
        val axisSb = SeekBar(this).apply {
            max = 15; progress = 1; layoutParams = LinearLayout.LayoutParams(540, 65); thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(16, 32) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { renderer.axisCount = (p + 1).toFloat() }; override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} })
        }
        sliders["AXIS"] = axisSb
        lockBtn = Button(this).apply {
            background = createLockDrawable(axisLocked); layoutParams = LinearLayout.LayoutParams(80, 80).apply { leftMargin = 20 }
            setOnClickListener { axisLocked = !axisLocked; background = createLockDrawable(axisLocked); alpha = if(axisLocked) 1.0f else 0.4f }
            alpha = if(axisLocked) 1.0f else 0.4f
        }
        axisContainer.addView(TextView(this).apply { text = "COUNT"; setTextColor(Color.WHITE); textSize = 8f; minWidth = 140; alpha = 0.8f }); axisContainer.addView(axisSb); axisContainer.addView(lockBtn); menuLayout.addView(axisContainer)

        fun createSlider(id: String, label: String, max: Int, start: Int, onP: (Int) -> Unit) {
            val container = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 4, 0, 0) }
            val sb = SeekBar(this).apply {
                this.max = max; progress = start; layoutParams = LinearLayout.LayoutParams(610, 65); thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(16, 32) }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) = onP(p); override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} })
            }
            sliders[id] = sb; container.addView(TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 8f; minWidth = 140; alpha = 0.8f; setOnClickListener { sliders[id]?.progress = start } }); container.addView(sb); menuLayout.addView(container)
        }

        fun createModPair(baseId: String, internalId: String) {
            val container = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(140, -12, 0, 8) }
            fun sub(subId: String, icon: String) = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(0, 50, 1f)
                val sb = SeekBar(this@MainActivity).apply {
                    max = 1000; progress = 0; layoutParams = LinearLayout.LayoutParams(-1, -1); thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(12, 22) }
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { if(subId.contains("MOD")) renderer.setMod(internalId, (p/1000f).pow(3f)) else renderer.setRate(internalId, (p/1000f + 0.05f).pow(3f)) }
                        override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {}
                    })
                }
                sliders[subId] = sb; addView(TextView(this@MainActivity).apply { text = icon; setTextColor(Color.WHITE); textSize = 11f; setTypeface(null, Typeface.BOLD); setPadding(0, 0, 4, 0); setOnClickListener { sliders[subId]?.progress = 0 } }); addView(sb)
            }
            container.addView(sub("${baseId}_MOD", "↕")); container.addView(sub("${baseId}_RATE", "⏱")); menuLayout.addView(container)
        }

        addHeader(menuLayout, "MASTER PIPE")
        createSlider("M_ANGLE", "ANGLE", 1000, 0) { renderer.mAngleBase = (it/1000f) * 360f }; createModPair("M_ANGLE", "M_ANGLE")
        createSlider("M_ROT", "ROTATION", 1000, 500) { renderer.mRotSpd = ((it-500)/500.0).pow(3.0)*1.5 }
        createSlider("M_ZOOM", "ZOOM", 1000, 250) { renderer.mZoomBase = 0.1 + ((it/1000.0).pow(2.0)*9.9) }; createModPair("M_ZOOM", "M_ZOOM")
        createSlider("M_TX", "TRANSLATE X", 1000, 500) { renderer.mTx = (it - 500) / 250f }; createModPair("M_TX", "M_TX")
        createSlider("M_TY", "TRANSLATE Y", 1000, 500) { renderer.mTy = (it - 500) / 250f }; createModPair("M_TY", "M_TY")
        createSlider("M_RGB", "RGB SMUDGE", 1000, 0) { renderer.mRgbShiftBase = it / 1000f * 0.15f }; createModPair("M_RGB", "M_RGB")
        addHeader(menuLayout, "CAMERA")
        createSlider("C_ANGLE", "CAM ANGLE", 1000, 0) { renderer.lAngleBase = (it/1000f) * 360f }; createModPair("C_ANGLE", "C_ANGLE")
        createSlider("RGB", "RGB SHIFT", 1000, 0) { renderer.rgbShiftBase = it / 1000f * 0.05f }; createModPair("RGB", "RGB")
        createSlider("HUE", "HUE", 1000, 0) { renderer.hueShiftBase = it / 1000f }; createModPair("HUE", "HUE")
        createSlider("NEG", "NEGATIVE", 1000, 0) { renderer.solarizeBase = it / 1000f }; createModPair("NEG", "NEG")
        createSlider("GLOW", "GLOW", 1000, 0) { renderer.bloomBase = it / 1000f }; createModPair("GLOW", "GLOW")
        createSlider("CONTRAST", "CONTRAST", 1000, 500) { renderer.contrast = (it / 500f) }; createSlider("VIBRANCE", "VIBRANCE", 1000, 500) { renderer.saturation = (it / 500f) }

        cameraSettingsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(10, 20, 10, 20) }
        fun createSideBtn(action: () -> Unit) = ImageButton(this).apply { setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.3f; layoutParams = LinearLayout.LayoutParams(100, 100); setOnClickListener { action(); updateSidebarVisuals() } }
        cameraSettingsPanel.addView(createSideBtn { currentSelector = if (currentSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA; startCamera() }.apply { setImageResource(android.R.drawable.ic_menu_camera) })
        flipXBtn = createSideBtn { renderer.flipX = if(renderer.flipX == 1f) -1f else 1f }.apply { setImageDrawable(textToIcon("↔")) }
        flipYBtn = createSideBtn { renderer.flipY = if(renderer.flipY == 1f) -1f else 1f }.apply { setImageDrawable(textToIcon("↕")) }
        rot180Btn = createSideBtn { renderer.rot180 = !renderer.rot180 }.apply { setImageResource(android.R.drawable.ic_menu_rotate) }
        cameraSettingsPanel.addView(flipXBtn); cameraSettingsPanel.addView(flipYBtn); cameraSettingsPanel.addView(rot180Btn)

        recordControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(20, 10, 20, 10) }
        photoBtn = ImageButton(this).apply { setImageDrawable(textToIcon("[  ]", 50f)); setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE); alpha = 0.8f; scaleX = 1.5f; scaleY = 1.5f; layoutParams = LinearLayout.LayoutParams(150, 150); setOnClickListener { renderer.capturePhoto(); triggerFlashPulse() } }
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
        // High-contrast deep black backgrounds
        val getBg = { alpha: Int ->
            GradientDrawable().apply {
                setColor(Color.argb(alpha, 5, 5, 5))
                setStroke(3, Color.argb(180, 40, 40, 40))
                cornerRadius = 45f
                shape = GradientDrawable.RECTANGLE
            }
        }

        val panels = listOf(leftHUDContainer, cameraSettingsPanel, presetPanel, recordControls)
        val utils = listOf(galleryBtn, readabilityBtn, resetBtn)

        panels.forEach {
            it.background = null
            it.setPadding(30, 30, 30, 30) // Increased padding for "framing"
            it.clipToOutline = true
        }

        when (readabilityLevel) {
            1 -> { // Dark Mode
                panels.forEach { it.background = getBg(210) }
                utils.forEach { it.background = getBg(230).apply { shape = GradientDrawable.OVAL } }
                applyRecursiveGlow(overlayHUD, false)
            }
            2 -> { // "VOID" GLOW MODE
                panels.forEach { it.background = getBg(160) }
                utils.forEach { it.background = getBg(200).apply { shape = GradientDrawable.OVAL } }
                applyRecursiveGlow(overlayHUD, true)
            }
            else -> { // Off
                panels.forEach { it.setPadding(0, 0, 0, 0); it.background = null }
                applyRecursiveGlow(overlayHUD, false)
            }
        }
    }

    private fun applyRecursiveGlow(view: View, enabled: Boolean) {
        if (view is TextView) {
            if (enabled) {
                // Triple-stacked shadow for a "heavy" dark aura
                view.setShadowLayer(50f, 0f, 0f, Color.BLACK)
                view.paint.maskFilter = null // Clear any clipping
            } else {
                view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
        } else if (view is ImageButton || view is Button) {
            view.elevation = if (enabled) 50f else 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && enabled) {
                view.outlineAmbientShadowColor = Color.BLACK
                view.outlineSpotShadowColor = Color.BLACK
            }
        }

        if (view is ViewGroup) {
            (0 until view.childCount).forEach { applyRecursiveGlow(view.getChildAt(it), enabled) }
        }
    }

    private fun toggleReadability() { readabilityLevel = (readabilityLevel + 1) % 3; applyReadabilityStyle() }

    private fun openGallery() {
        try {
            val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent)
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
                runOnUiThread {
                    recordBtn.setImageDrawable(textToIcon("REC", 40f)); recordBtn.alpha = 0.5f
                    if (savedFile != null && savedFile.exists()) saveVideoToGallery(savedFile)
                }
            }
        }
    }

    private fun saveVideoToGallery(file: File) {
        if (file.length() == 0L) return
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SpaceBeam"); put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        try {
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0); contentResolver.update(it, values, null, null)
                }
                MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
                file.delete(); Toast.makeText(this, "Video Saved", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) { }
    }

    private fun globalReset() {
        currentAnimator?.cancel(); renderer.mRotAccum = 0.0; renderer.lRotAccum = 0.0; renderer.resetPhases()
        renderer.flipX = 1f; renderer.flipY = -1f; renderer.rot180 = false; activePreset = -1; updatePresetHighlights()
        sliders.forEach { (id, sb) -> sb.progress = when (id) { "AXIS" -> 1; "M_ROT", "CONTRAST", "VIBRANCE", "M_TX", "M_TY" -> 500; "M_ZOOM" -> 250; "C_ANGLE" -> 0; else -> 0 } }
        updateSidebarVisuals()
    }

    private fun updatePresetHighlights() { presetButtons.forEach { (idx, btn) -> btn.background = GradientDrawable().apply { setColor(Color.TRANSPARENT); if (idx == activePreset) setStroke(4, Color.WHITE); cornerRadius = 12f } } }
    private fun triggerFlashPulse() { flashOverlay.alpha = 0.6f; flashOverlay.animate().alpha(0f).setDuration(400).start(); photoBtn.animate().scaleX(1.8f).scaleY(1.8f).setDuration(100).withEndAction { photoBtn.animate().scaleX(1.5f).scaleY(1.5f).setDuration(200).start() }.start() }
    private fun updateSidebarVisuals() { flipXBtn.alpha = if (renderer.flipX < 0f) 1.0f else 0.3f; flipYBtn.alpha = if (renderer.flipY > 0f) 1.0f else 0.3f; rot180Btn.alpha = if (renderer.rot180) 1.0f else 0.3f }
    private fun addHeader(m: LinearLayout, t: String) = m.addView(TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 8f; alpha = 0.3f; setPadding(0, 45, 0, 5) })
    private fun hideSystemUI() { window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) }

    private fun applyPreset(idx: Int) {
        val p = presets[idx] ?: return; activePreset = idx; updatePresetHighlights(); currentAnimator?.cancel()
        if (!axisLocked) sliders["AXIS"]?.progress = p.axis - 1
        val startValues = sliders.mapValues { it.value.progress }
        currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = transitionMs
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                sliders.forEach { (id, sb) ->
                    val start = startValues[id] ?: sb.progress
                    if (id == "AXIS") { if (!axisLocked) sb.progress = (start + ((p.axis - 1) - start) * t).toInt() }
                    else { val target = p.sliderValues[id] ?: start; sb.progress = (start + (target - start) * t).toInt() }
                }
                renderer.flipX = p.flipX; renderer.flipY = p.flipY; renderer.rot180 = p.rot180; updateSidebarVisuals()
            }
            start()
        }
    }

    private fun savePreset(idx: Int) { presets[idx] = Preset(sliders.filter { it.key != "AXIS" }.mapValues { it.value.progress }, renderer.flipX, renderer.flipY, renderer.rot180, sliders["AXIS"]?.progress?.plus(1) ?: 1); activePreset = idx; updatePresetHighlights(); Toast.makeText(this, "Preset Saved", Toast.LENGTH_SHORT).show() }

    private fun initDefaultPresets() {
        fun p(ax: Int=1, mAng: Int=0, mAm: Int=0, mAr: Int=0, mRot: Int=500, mZm: Int=250, mZMod: Int=0, mZR: Int=0, cAng: Int=0, cAm: Int=0, cAr: Int=0, rgb: Int=0, rR: Int=0, rM: Int=0, hue: Int=0, hR: Int=0, hM: Int=0, neg: Int=0, glw: Int=0, gR: Int=0, gM: Int=0, mRgb: Int=0, txM: Int=0, txR: Int=0, satur: Int=500, contrast: Int=500) = Preset(mapOf("M_ANGLE" to mAng, "M_ANGLE_MOD" to mAm, "M_ANGLE_RATE" to mAr, "M_ROT" to mRot, "M_ZOOM" to mZm, "M_ZOOM_MOD" to mZMod, "M_ZOOM_RATE" to mZR, "C_ANGLE" to cAng, "C_ANGLE_MOD" to cAm, "C_ANGLE_RATE" to cAr, "RGB" to rgb, "RGB_RATE" to rR, "RGB_MOD" to rM, "HUE" to hue, "HUE_RATE" to hR, "HUE_MOD" to hM, "NEG" to neg, "GLOW" to glw, "GLOW_RATE" to gR, "GLOW_MOD" to gM, "M_RGB" to mRgb, "M_TX_MOD" to txM, "M_TX_RATE" to txR, "CONTRAST" to contrast, "VIBRANCE" to satur, "M_TX" to 500, "M_TY" to 500), 1f, -1f, false, ax)
        presets[1] = p(ax=2, mRot=505, mAm=80, mAr=50, mZm=250, mZMod=120, mZR=80); presets[2] = p(ax=3, mRot=510, cAm=150, cAr=100, glw=200, gM=300, gR=120); presets[3] = p(ax=4, mRot=500, mZm=300, hue=200, hM=800, hR=150, satur=700); presets[4] = p(ax=8, mRot=502, mZm=180, mZMod=600, mZR=200, glw=350, contrast=650); presets[5] = p(ax=6, mRot=520, mRgb=120, rgb=100, rM=300, rR=150, neg=200, glw=500, contrast=800); presets[6] = p(ax=12, mRot=505, mAm=1000, mAr=50, txM=400, txR=100, hue=400, glw=400); presets[7] = p(ax=5, mRot=560, mRgb=250, rgb=200, rM=600, rR=300, neg=400, glw=700, contrast=1000, satur=800); presets[8] = p(ax=16, mRot=500, mAm=1000, mAr=400, mZMod=800, mZR=350, cAm=1000, cAr=200, neg=1000, glw=1000, satur=1000)
    }

    private fun toggleHud() { isHudVisible = !isHudVisible; overlayHUD.visibility = if (isHudVisible) View.VISIBLE else View.GONE; if (isHudVisible) hideSystemUI() }
    private fun toggleMenu() { isMenuExpanded = !isMenuExpanded; parameterPanel.visibility = if (isMenuExpanded) View.VISIBLE else View.GONE; parameterToggleBtn.text = if (isMenuExpanded) "<" else ">" }

    inner class KaleidoscopeRenderer(private val ctx: MainActivity) : GLSurfaceView.Renderer {
        private var program = 0; private var texID = -1; private var surfaceTexture: SurfaceTexture? = null
        private lateinit var pBuf: FloatBuffer; private lateinit var tBuf: FloatBuffer
        private var uLocs = mutableMapOf<String, Int>()
        private var viewWidth = 1; private var viewHeight = 1; private var lastTime = System.nanoTime()

        var axisCount = 2.0f; var flipX = 1.0f; var flipY = -1.0f; var rot180 = false; var contrast = 1.0f; var saturation = 1.0f
        var mAngleBase = 0f; var mRotSpd = 0.0; var mRotAccum = 0.0; var mZoomBase = 1.0; var mRgbShiftBase = 0f
        var lAngleBase = 0f; var lRotSpd = 0.0; var lRotAccum = 0.0; var lZoomBase = 1.0
        var rgbShiftBase = 0f; var hueShiftBase = 0f; var solarizeBase = 0f; var bloomBase = 0f
        var mTx = 0f; var mTy = 0f; var lTx = 0f; var lTy = 0f

        private val mods = mutableMapOf<String, Float>(); private val rates = mutableMapOf<String, Float>()
        private val phases = mutableMapOf<String, Double>(); private val driftPhases = mutableMapOf<String, Double>()
        private var captureRequested = false; private var videoRecorder: VideoRecorder? = null
        private var recordSurface: EGLSurface? = EGL14.EGL_NO_SURFACE; private var pendingRecordFile: File? = null
        private var onStopCallback: ((File?) -> Unit)? = null; private var isStopRequested = false
        private var mSavedDisplay = EGL14.EGL_NO_DISPLAY; private var mSavedContext = EGL14.eglGetCurrentContext(); private var mEglConfig: android.opengl.EGLConfig? = null

        fun setMod(id: String, v: Float) { mods[id] = v }; fun setRate(id: String, v: Float) { rates[id] = v }
        fun resetPhases() { phases.clear(); driftPhases.clear(); mods.clear(); rates.clear() }
        fun capturePhoto() { captureRequested = true }
        fun startRecording(file: File) { pendingRecordFile = file }
        fun stopRecording(callback: (File?) -> Unit) { onStopCallback = callback; isStopRequested = true }

        override fun onSurfaceCreated(gl: GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
            mSavedDisplay = EGL14.eglGetCurrentDisplay(); mSavedContext = EGL14.eglGetCurrentContext()
            val currentConfigId = IntArray(1); EGL14.eglQueryContext(mSavedDisplay, mSavedContext, EGL14.EGL_CONFIG_ID, currentConfigId, 0)
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(mSavedDisplay, intArrayOf(EGL14.EGL_CONFIG_ID, currentConfigId[0], EGL14.EGL_NONE), 0, configs, 0, 1, numConfigs, 0)
            mEglConfig = configs[0]; GLES20.glClearColor(0f, 0f, 0f, 1f)
            val vSrc = "attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }"
            val fSrc = """
                #extension GL_OES_EGL_image_external : require
                precision highp float;
                varying vec2 v;
                uniform samplerExternalOES uTex;
                uniform float uMR, uLR, uA, uMZ, uLZ, uAx, uC, uS, uHue, uSol, uBloom, uRGB, uMRGB;
                uniform vec2 uMT, uCT, uF;
                void main() {
                    vec3 finalColor;
                    for(int i=0; i<3; i++) {
                        // 1. SOURCE RGB SHIFT & FLIPS (Applied to raw camera feed)
                        float sOff = (i==0) ? uRGB : (i==2) ? -uRGB : 0.0;
                        vec2 srcUV = v;
                        srcUV.x += sOff; 
                        srcUV = (srcUV - 0.5) * uF + 0.5; // Apply Flips/180 here
                        
                        // 2. MASTER KALEIDOSCOPE MATH
                        float mOff = (i==0) ? uMRGB : (i==2) ? -uMRGB : 0.0;
                        vec2 uv = srcUV - 0.5; 
                        uv.x += mOff;
                        uv.x *= uA; uv += uMT; uv *= uMZ;
                        float a1 = uMR * 0.01745329; uv = vec2(uv.x * cos(a1) - uv.y * sin(a1), uv.x * sin(a1) + uv.y * cos(a1));
                        
                        if(uAx > 1.1) {
                            float r = length(uv); float slice = 6.283185 / uAx; float a = mod(atan(uv.y, uv.x), slice);
                            if(mod(uAx, 2.0) < 0.1) a = abs(a - slice * 0.5); uv = vec2(cos(a), sin(a)) * r;
                        }
                        
                        // 3. POST-MIRROR TRANSFORMS
                        uv += uCT; uv *= uLZ; float a2 = uLR * 0.01745329; uv = vec2(uv.x * cos(a2) - uv.y * sin(a2), uv.x * sin(a2) + uv.y * cos(a2));
                        uv.x /= uA; 
                        vec2 fuv = (uAx > 1.1) ? abs(fract(uv - 0.5) * 2.0 - 1.0) : fract(uv + 0.5);
                        
                        vec3 smp = texture2D(uTex, fuv).rgb;
                        if(i==0) finalColor.r = smp.r; else if(i==1) finalColor.g = smp.g; else finalColor.b = smp.b;
                    }
                    // Color Grading
                    if(uSol > 0.01) finalColor = mix(finalColor, abs(finalColor - uSol), step(0.1, uSol));
                    if(uHue > 0.001) {
                        const vec3 k = vec3(0.57735); float ca = cos(uHue * 6.28318);
                        finalColor = finalColor * ca + cross(k, finalColor) * sin(uHue * 6.28318) + k * dot(k, finalColor) * (1.0 - ca);
                    }
                    finalColor = (finalColor - 0.5) * uC + 0.5; 
                    float l = dot(finalColor, vec3(0.299, 0.587, 0.114));
                    finalColor = mix(vec3(l), finalColor, uS); 
                    if(uBloom > 0.01) finalColor += smoothstep(0.5, 1.0, l) * finalColor * uBloom * 2.5;
                    gl_FragColor = vec4(finalColor, 1.0);
                }
            """.trimIndent()
            program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vSrc)); GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fSrc)); GLES20.glLinkProgram(program)
            listOf("uMR", "uLR", "uA", "uMZ", "uLZ", "uAx", "uC", "uS", "uHue", "uSol", "uBloom", "uRGB", "uMRGB", "uF", "uMT", "uCT", "uTex").forEach { uLocs[it] = GLES20.glGetUniformLocation(program, it) }
            texID = createOESTex(); surfaceTexture = SurfaceTexture(texID); pBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)).position(0) }; tBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(0f,0f, 1f,0f, 0f,1f, 1f,1f)).position(0) }
            ctx.runOnUiThread { ctx.startCamera() }
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { viewWidth = w; viewHeight = h; GLES20.glViewport(0, 0, w, h) }
        override fun onDrawFrame(gl: GL10?) {
            val now = System.nanoTime(); val d = (now - lastTime) / 1e9; lastTime = now; val tpi = 2.0 * PI
            try { surfaceTexture?.updateTexImage() } catch (e: Exception) { return }
            if (pendingRecordFile != null) {
                videoRecorder = VideoRecorder(viewWidth, viewHeight, pendingRecordFile!!)
                recordSurface = EGL14.eglCreateWindowSurface(mSavedDisplay, mEglConfig, videoRecorder!!.inputSurface, intArrayOf(EGL14.EGL_NONE), 0); pendingRecordFile = null
            }
            fun calcPh(id: String): Float {
                val r = (rates[id] ?: 0f).toDouble(); val drift = (driftPhases[id] ?: 0.0) + (r * 0.13) * d * tpi; driftPhases[id] = drift
                val p = (phases[id] ?: 0.0) + (r + sin(drift) * r * 0.4) * d * tpi; phases[id] = p; return sin(p).toFloat() * (mods[id] ?: 0f)
            }
            val phMap = listOf("M_ANGLE", "C_ANGLE", "M_ZOOM", "C_ZOOM", "HUE", "NEG", "GLOW", "RGB", "M_RGB", "M_TX", "M_TY", "C_TX", "C_TY").associateWith { calcPh(it) }
            mRotAccum += mRotSpd * 120.0 * d; lRotAccum += lRotSpd * 120.0 * d; GLES20.glViewport(0, 0, viewWidth, viewHeight); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); drawScene(phMap, viewWidth, viewHeight)
            if (recordSurface != EGL14.EGL_NO_SURFACE && videoRecorder != null) {
                val oldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW); val oldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
                if (EGL14.eglMakeCurrent(mSavedDisplay, recordSurface, recordSurface, mSavedContext)) {
                    GLES20.glViewport(0, 0, viewWidth, viewHeight); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); drawScene(phMap, viewWidth, viewHeight)
                    EGLExt.eglPresentationTimeANDROID(mSavedDisplay, recordSurface!!, System.nanoTime()); EGL14.eglSwapBuffers(mSavedDisplay, recordSurface); videoRecorder?.drain(false)
                }
                EGL14.eglMakeCurrent(mSavedDisplay, oldDraw, oldRead, mSavedContext)
                if (isStopRequested) {
                    videoRecorder?.drain(true); val out = videoRecorder?.file; EGL14.eglDestroySurface(mSavedDisplay, recordSurface); recordSurface = EGL14.EGL_NO_SURFACE; videoRecorder?.release(); videoRecorder = null; isStopRequested = false; onStopCallback?.invoke(out)
                }
            }
            if (captureRequested) {
                captureRequested = false; val b = ByteBuffer.allocate(viewWidth * viewHeight * 4); GLES20.glReadPixels(0, 0, viewWidth, viewHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
                Thread {
                    val bmp = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(b) }
                    val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "SB_${System.currentTimeMillis()}.jpg"); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SpaceBeam") }
                    ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri -> ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
                }.start()
            }
        }

        private fun drawScene(ph: Map<String, Float>, w: Int, h: Int) {
            GLES20.glUseProgram(program)
            // Removed rot180 from uMR calculation
            GLES20.glUniform1f(uLocs["uMR"]!!, (mAngleBase + mRotAccum + (ph["M_ANGLE"] ?: 0f) * 90.0).toFloat())
            GLES20.glUniform1f(uLocs["uLR"]!!, (lAngleBase + lRotAccum + (ph["C_ANGLE"] ?: 0f) * 45.0 + 90.0).toFloat()); GLES20.glUniform1f(uLocs["uA"]!!, w.toFloat()/h.toFloat()); GLES20.glUniform1f(uLocs["uMZ"]!!, (mZoomBase + (ph["M_ZOOM"] ?: 0f) * 0.5 * mZoomBase).toFloat()); GLES20.glUniform1f(uLocs["uLZ"]!!, (lZoomBase + (ph["C_ZOOM"] ?: 0f) * 0.5 * lZoomBase).toFloat()); GLES20.glUniform1f(uLocs["uAx"]!!, axisCount)

            // Combine flips and rotation into a single effective flip vector for the shader
            val effectiveFx = if (rot180) -flipX else flipX
            val effectiveFy = if (rot180) -flipY else flipY
            GLES20.glUniform2f(uLocs["uF"]!!, effectiveFx, effectiveFy)

            GLES20.glUniform1f(uLocs["uC"]!!, contrast); GLES20.glUniform1f(uLocs["uS"]!!, saturation); GLES20.glUniform1f(uLocs["uHue"]!!, hueShiftBase + (ph["HUE"] ?: 0f)); GLES20.glUniform1f(uLocs["uSol"]!!, solarizeBase + (ph["NEG"] ?: 0f)); GLES20.glUniform1f(uLocs["uBloom"]!!, bloomBase + (ph["GLOW"] ?: 0f)); GLES20.glUniform1f(uLocs["uRGB"]!!, rgbShiftBase + (ph["RGB"] ?: 0f)); GLES20.glUniform1f(uLocs["uMRGB"]!!, mRgbShiftBase + (ph["M_RGB"] ?: 0f)); GLES20.glUniform2f(uLocs["uMT"]!!, mTx + (ph["M_TX"] ?: 0f), mTy + (ph["M_TY"] ?: 0f)); GLES20.glUniform2f(uLocs["uCT"]!!, lTx + (ph["C_TX"] ?: 0f), lTy + (ph["C_TY"] ?: 0f))
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texID); GLES20.glUniform1i(uLocs["uTex"]!!, 0)
            val pL = GLES20.glGetAttribLocation(program, "p"); val tL = GLES20.glGetAttribLocation(program, "t"); GLES20.glEnableVertexAttribArray(pL); GLES20.glVertexAttribPointer(pL, 2, GLES20.GL_FLOAT, false, 0, pBuf); GLES20.glEnableVertexAttribArray(tL); GLES20.glVertexAttribPointer(tL, 2, GLES20.GL_FLOAT, false, 0, tBuf); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        fun provideSurface(req: SurfaceRequest) { glView.queueEvent { surfaceTexture?.let { st -> st.setDefaultBufferSize(req.resolution.width, req.resolution.height); val s = Surface(st); req.provideSurface(s, ContextCompat.getMainExecutor(ctx)) { s.release() } } } }
        private fun createOESTex(): Int { val t = IntArray(1); GLES20.glGenTextures(1, t, 0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]); GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR); GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR); return t[0] }
        private fun compile(t: Int, s: String): Int = GLES20.glCreateShader(t).apply { GLES20.glShaderSource(this, s); GLES20.glCompileShader(this) }
    }
}

class VideoRecorder(val width: Int, val height: Int, val file: File) {
    private var encoder: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC); private var muxer: MediaMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); val inputSurface: Surface; private var trackIndex = -1; private var muxerStarted = false; private val bufferInfo = MediaCodec.BufferInfo()
    init {
        val w = if (width % 2 == 0) width else width - 1; val h = if (height % 2 == 0) height else height - 1
        encoder.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply { setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface); setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000); setInteger(MediaFormat.KEY_FRAME_RATE, 30); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); inputSurface = encoder.createInputSurface(); encoder.start()
    }
    fun drain(endOfStream: Boolean) {
        if (endOfStream) try { encoder.signalEndOfInputStream() } catch (e: Exception) { }
        while (true) {
            val idx = try { encoder.dequeueOutputBuffer(bufferInfo, 10000) } catch (e: Exception) { -1 }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!endOfStream) break }
            else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { trackIndex = muxer.addTrack(encoder.outputFormat); try { muxer.start(); muxerStarted = true } catch (e: Exception) { } }
            else if (idx >= 0) { val data = encoder.getOutputBuffer(idx) ?: continue; if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bufferInfo.size = 0; if (bufferInfo.size != 0 && muxerStarted) { try { data.position(bufferInfo.offset); data.limit(bufferInfo.offset + bufferInfo.size); muxer.writeSampleData(trackIndex, data, bufferInfo) } catch (e: Exception) { } }; encoder.releaseOutputBuffer(idx, false); if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break } else if (idx < 0 && endOfStream) break
        }
    }
    fun release() { try { if (muxerStarted) muxer.stop(); muxer.release(); encoder.stop(); encoder.release(); inputSurface.release() } catch (e: Exception) { } }
}