package com.calmyjane.spacebeam

import android.Manifest
import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.*
import android.graphics.drawable.shapes.PathShape
import android.opengl.*
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
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
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: KaleidoscopeRenderer
    private var currentSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private lateinit var hudContainer: FrameLayout
    private val sliders = mutableMapOf<String, SeekBar>()
    private val presetButtons = mutableMapOf<Int, Button>()
    private var currentAnimator: ValueAnimator? = null
    private var activePreset: Int = -1

    private lateinit var flipXBtn: ImageButton
    private lateinit var flipYBtn: ImageButton
    private lateinit var rot180Btn: ImageButton
    private lateinit var readabilityBtn: ImageButton
    private lateinit var resetBtn: ImageButton
    private lateinit var photoBtn: ImageButton
    private lateinit var menuToggleBtn: Button
    private lateinit var togglePanel: FrameLayout
    private lateinit var flashOverlay: View

    private var axisLocked = false
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
    private var readabilityMode = false
    private var isHudVisible = true
    private var isMenuExpanded = true

    private lateinit var sliderBox: ScrollView
    private lateinit var sideBox: LinearLayout
    private lateinit var presetBox: LinearLayout
    private lateinit var captureBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        hideSystemUI()

        renderer = KaleidoscopeRenderer(this)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
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
        setupUltraMinimalHUD()
        initDefaultPresets()

        glView.post {
            globalReset()
            applyPreset(1)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
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
            } catch (e: Exception) {
                Log.e("SpaceBeam", "Camera Error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleInteraction(event: MotionEvent) {
        if (event.pointerCount >= 2) {
            val p1x = event.getX(0); val p1y = event.getY(0)
            val p2x = event.getX(1); val p2y = event.getY(1)
            val focusX = (p1x + p2x) / 2f
            val focusY = (p1y + p2y) / 2f
            val dist = hypot(p1x - p2x, p1y - p2y)
            val angle = Math.toDegrees(atan2((p1y - p2y).toDouble(), (p1x - p2x).toDouble())).toFloat()

            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val dx = (focusX - lastFingerFocusX) / glView.width.toFloat() * 2.0f
                val dy = (focusY - lastFingerFocusY) / glView.height.toFloat() * 2.0f

                sliders["M_TX"]?.let { it.progress = (it.progress - (dx * 500).toInt()).coerceIn(0, 1000) }
                sliders["M_TY"]?.let { it.progress = (it.progress + (dy * 500).toInt()).coerceIn(0, 1000) }

                val scaleFactor = dist / lastFingerDist
                sliders["M_ZOOM"]?.let { it.progress = (it.progress - (log2(scaleFactor) * 300).toInt()).coerceIn(0, 1000) }

                val dAngle = angle - lastFingerAngle
                // Fixed: Inverted gesture rotation
                sliders["M_ANGLE"]?.let { it.progress = (it.progress + (dAngle * (1000f / 360f)).toInt() + 1000) % 1000 }
            }
            lastFingerDist = dist; lastFingerAngle = angle; lastFingerFocusX = focusX; lastFingerFocusY = focusY
        } else if (event.action == MotionEvent.ACTION_UP) {
            if (event.eventTime - event.downTime < 200) toggleHud()
        }
    }

    private fun createLogoDrawable(): ShapeDrawable {
        val p = Path().apply {
            moveTo(46.64f, 131.26f); lineTo(46.64f, 162.60f); lineTo(159.91f, 162.60f); lineTo(159.91f, 144.77f); lineTo(64.56f, 144.77f); lineTo(64.56f, 131.26f); close()
            moveTo(126.77f, 99.98f); lineTo(145.39f, 99.98f); lineTo(145.39f, 118.61f); lineTo(126.77f, 118.61f); close()
            moveTo(64.19f, 99.98f); lineTo(82.81f, 99.98f); lineTo(82.81f, 118.61f); lineTo(64.19f, 99.98f); close()
            moveTo(28.34f, 67.34f); lineTo(28.34f, 193.34f); lineTo(178.29f, 193.34f); lineTo(178.29f, 67.34f); close()
            moveTo(33.84f, 72.84f); lineTo(172.79f, 72.84f); lineTo(172.79f, 187.84f); lineTo(33.84f, 187.84f); close()
        }
        return ShapeDrawable(PathShape(p, 200f, 200f)).apply { paint.color = Color.WHITE; paint.isAntiAlias = true }
    }

    private fun createLockDrawable(locked: Boolean): BitmapDrawable {
        val b = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 8f; isAntiAlias = true }
        val shackle = RectF(30f, 20f, 70f, 60f)
        if (locked) c.drawArc(shackle, 180f, 180f, false, p) else c.drawArc(shackle, 160f, 180f, false, p)
        p.style = Paint.Style.FILL
        c.drawRoundRect(RectF(25f, 50f, 75f, 85f), 8f, 8f, p)
        return BitmapDrawable(resources, b)
    }

    private fun setupUltraMinimalHUD() {
        hudContainer = FrameLayout(this).apply { layoutParams = FrameLayout.LayoutParams(-1, -1) }
        flashOverlay = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f; layoutParams = FrameLayout.LayoutParams(-1, -1) }

        val logoView = ImageView(this).apply { setImageDrawable(createLogoDrawable()); alpha = 0.4f; layoutParams = FrameLayout.LayoutParams(180, 180).apply { gravity = Gravity.TOP or Gravity.START; topMargin = 40; leftMargin = 40 } }

        val leftMenuContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = FrameLayout.LayoutParams(-2, -1).apply { gravity = Gravity.START } }
        sliderBox = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(850, -1); layoutDirection = View.LAYOUT_DIRECTION_RTL; isVerticalScrollBarEnabled = false }
        val menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 20, 240); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        sliderBox.addView(menu)

        togglePanel = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(140, -1)
            menuToggleBtn = Button(this@MainActivity).apply {
                text = "<"; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.8f; textSize = 32f
                layoutParams = FrameLayout.LayoutParams(-1, 400, Gravity.CENTER_VERTICAL); setOnClickListener { toggleMenu() }
            }
            addView(menuToggleBtn)
        }
        leftMenuContainer.addView(sliderBox); leftMenuContainer.addView(togglePanel)

        fun textToIcon(t: String, size: Float = 60f): BitmapDrawable {
            val b = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint().apply { color = Color.WHITE; textSize = size; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
            c.drawText(t, 60f, size + 20f, p); return BitmapDrawable(resources, b)
        }

        val axisContainer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 10) }
        val axisLabel = TextView(this).apply { text = "COUNT"; setTextColor(Color.WHITE); textSize = 8f; minWidth = 140; alpha = 0.8f }
        val axisSb = SeekBar(this).apply {
            max = 15; progress = 1; layoutParams = LinearLayout.LayoutParams(540, 65)
            thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(16, 32) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { renderer.axisCount = (p + 1).toFloat() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        sliders["AXIS"] = axisSb

        lockBtn = Button(this).apply {
            background = createLockDrawable(false)
            layoutParams = LinearLayout.LayoutParams(80, 80).apply { leftMargin = 20 }
            setOnClickListener {
                axisLocked = !axisLocked
                background = createLockDrawable(axisLocked)
                alpha = if(axisLocked) 1.0f else 0.4f
            }
            alpha = 0.4f
        }

        axisContainer.addView(axisLabel); axisContainer.addView(axisSb); axisContainer.addView(lockBtn)
        menu.addView(axisContainer)

        fun createSlider(id: String, label: String, max: Int, start: Int, onP: (Int) -> Unit) {
            val container = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 4, 0, 0) }
            val tv = TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 8f; minWidth = 140; alpha = 0.8f; setOnClickListener { sliders[id]?.progress = start } }
            val sb = SeekBar(this).apply {
                this.max = max; progress = start; layoutParams = LinearLayout.LayoutParams(610, 65)
                thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(16, 32) }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) = onP(p)
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            sliders[id] = sb; container.addView(tv); container.addView(sb); menu.addView(container)
        }

        fun createModPair(baseId: String, internalId: String) {
            val container = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(140, -12, 0, 8) }
            fun sub(subId: String, icon: String) = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(0, 50, 1f)
                val iv = TextView(this@MainActivity).apply { text = icon; setTextColor(Color.WHITE); textSize = 11f; setTypeface(null, Typeface.BOLD); setPadding(0, 0, 4, 0); setOnClickListener { sliders[subId]?.progress = 0 } }
                val sb = SeekBar(this@MainActivity).apply {
                    max = 1000; progress = 0; layoutParams = LinearLayout.LayoutParams(-1, -1)
                    thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(12, 22) }
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                            if(subId.contains("MOD")) renderer.setMod(internalId, (p/1000f).pow(3f))
                            else renderer.setRate(internalId, (p/1000f + 0.05f).pow(3f))
                        }
                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) {}
                    })
                }
                sliders[subId] = sb; addView(iv); addView(sb)
            }
            container.addView(sub("${baseId}_MOD", "↕")); container.addView(sub("${baseId}_RATE", "⏱")); menu.addView(container)
        }

        addHeader(menu, "MASTER PIPE")
        createSlider("M_ANGLE", "ANGLE", 1000, 0) { renderer.mAngleBase = (it/1000f) * 360f }; createModPair("M_ANGLE", "M_ANGLE")
        createSlider("M_ROT", "ROTATION", 1000, 500) { renderer.mRotSpd = ((it-500)/500.0).pow(3.0)*1.5 }
        createSlider("M_ZOOM", "ZOOM", 1000, 250) { renderer.mZoomBase = 0.1 + ((it/1000.0).pow(2.0)*9.9) }; createModPair("M_ZOOM", "M_ZOOM")
        createSlider("M_TX", "TRANSLATE X", 1000, 500) { renderer.mTx = (it - 500) / 250f }; createModPair("M_TX", "M_TX")
        createSlider("M_TY", "TRANSLATE Y", 1000, 500) { renderer.mTy = (it - 500) / 250f }; createModPair("M_TY", "M_TY")
        createSlider("M_RGB", "RGB SMUDGE", 1000, 0) { renderer.mRgbShiftBase = it / 1000f * 0.15f }; createModPair("M_RGB", "M_RGB")

        addHeader(menu, "COLOR & CAMERA")
        createSlider("C_ANGLE", "CAM ANGLE", 1000, 0) { renderer.lAngleBase = (it/1000f) * 360f }; createModPair("C_ANGLE", "C_ANGLE")
        createSlider("RGB", "RGB SHIFT", 1000, 0) { renderer.rgbShiftBase = it / 1000f * 0.05f }; createModPair("RGB", "RGB")
        createSlider("HUE", "HUE", 1000, 0) { renderer.hueShiftBase = it / 1000f }; createModPair("HUE", "HUE")
        createSlider("NEG", "NEGATIVE", 1000, 0) { renderer.solarizeBase = it / 1000f }; createModPair("NEG", "NEG")
        createSlider("GLOW", "GLOW", 1000, 0) { renderer.bloomBase = it / 1000f }; createModPair("GLOW", "GLOW")
        createSlider("CONTRAST", "CONTRAST", 1000, 500) { renderer.contrast = (it / 500f) }
        createSlider("VIBRANCE", "VIBRANCE", 1000, 500) { renderer.saturation = (it / 500f) }

        sideBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(10, 20, 10, 20) }
        fun createSideBtn(action: () -> Unit) = ImageButton(this).apply { setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.3f; layoutParams = LinearLayout.LayoutParams(100, 100); setOnClickListener { action(); updateSidebarVisuals() } }
        sideBox.addView(createSideBtn { currentSelector = if (currentSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA; startCamera() }.apply { setImageResource(android.R.drawable.ic_menu_camera) })
        flipXBtn = createSideBtn { renderer.flipX = if(renderer.flipX == 1f) -1f else 1f }.apply { setImageDrawable(textToIcon("↔")) }
        flipYBtn = createSideBtn { renderer.flipY = if(renderer.flipY == 1f) -1f else 1f }.apply { setImageDrawable(textToIcon("↕")) }
        rot180Btn = createSideBtn { renderer.rot180 = !renderer.rot180 }.apply { setImageResource(android.R.drawable.ic_menu_rotate) }
        sideBox.addView(flipXBtn); sideBox.addView(flipYBtn); sideBox.addView(rot180Btn)

        captureBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(20, 10, 20, 10) }
        photoBtn = ImageButton(this).apply { setImageDrawable(textToIcon("[  ]", 50f)); setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE); alpha = 0.8f; scaleX = 1.5f; scaleY = 1.5f; layoutParams = LinearLayout.LayoutParams(150, 150); setOnClickListener { renderer.capturePhoto(); triggerFlashPulse() } }
        captureBox.addView(photoBtn)

        presetBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(15, 10, 15, 30) }
        val transContainer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(10, 0, 10, 10) }
        val timeLabel = TextView(this).apply { text = "1.0s"; setTextColor(Color.WHITE); textSize = 9f; setPadding(4, 0, 8, 0) }
        val transSeekBar = SeekBar(this).apply {
            max = 1000; progress = 333; layoutParams = LinearLayout.LayoutParams(540, 45)
            thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(12, 24) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { transitionMs = ((p/1000f).pow(3.0f)*30000).toLong(); timeLabel.text = "%.1fs".format(transitionMs/1000f) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        transContainer.addView(timeLabel); transContainer.addView(transSeekBar)

        val presetRow = FrameLayout(this)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun createPresetBtn(idx: Int) = Button(this).apply {
            text = idx.toString(); setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.8f; textSize = 16f
            layoutParams = LinearLayout.LayoutParams(80, 140); setPadding(0, 0, 0, 20)
            setOnClickListener { applyPreset(idx) }
            setOnLongClickListener {
                pendingSaveIndex = idx
                saveConfirmBtn.visibility = View.VISIBLE
                saveConfirmBtn.text = "SAVE $idx?"
                true
            }
        }
        (8 downTo 1).forEach { val b = createPresetBtn(it); presetButtons[it] = b; btnRow.addView(b) }

        saveConfirmBtn = Button(this).apply {
            visibility = View.GONE; setTextColor(Color.BLACK); textSize = 12f; setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8f }
            layoutParams = FrameLayout.LayoutParams(250, 100, Gravity.CENTER)
            setOnClickListener { pendingSaveIndex?.let { savePreset(it) }; visibility = View.GONE }
        }

        presetRow.addView(btnRow); presetRow.addView(saveConfirmBtn)
        presetBox.addView(transContainer); presetBox.addView(presetRow)

        val blackCirc = GradientDrawable().apply { setColor(Color.BLACK); shape = GradientDrawable.OVAL }
        readabilityBtn = ImageButton(this).apply { setImageResource(android.R.drawable.ic_menu_view); setColorFilter(Color.WHITE); background = blackCirc; alpha = 0.3f; layoutParams = FrameLayout.LayoutParams(110, 110).apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 140; rightMargin = 35 }; setOnClickListener { toggleReadability() } }
        resetBtn = ImageButton(this).apply { setImageResource(android.R.drawable.ic_menu_close_clear_cancel); setColorFilter(Color.WHITE); background = blackCirc; alpha = 0.3f; layoutParams = FrameLayout.LayoutParams(110, 110).apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 30; rightMargin = 35 }; setOnClickListener { globalReset() } }

        hudContainer.addView(flashOverlay); hudContainer.addView(logoView); hudContainer.addView(leftMenuContainer)
        hudContainer.addView(captureBox, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = 30 })
        hudContainer.addView(sideBox, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.END; topMargin = 40; rightMargin = 40 })
        hudContainer.addView(presetBox, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 15; rightMargin = 180 })
        hudContainer.addView(readabilityBtn); hudContainer.addView(resetBtn)
        addContentView(hudContainer, ViewGroup.LayoutParams(-1, -1)); updateSidebarVisuals()
    }

    private fun globalReset() {
        currentAnimator?.cancel(); renderer.mRotAccum = 0.0; renderer.lRotAccum = 0.0; renderer.resetPhases()
        renderer.flipX = 1f; renderer.flipY = -1f; renderer.rot180 = false; activePreset = -1; updatePresetHighlights()
        sliders.forEach { (id, sb) ->
            sb.progress = when (id) {
                "AXIS" -> 1; "M_ROT", "CONTRAST", "VIBRANCE", "M_TX", "M_TY", "C_TX", "C_TY" -> 500; "M_ZOOM" -> 250; "C_ANGLE" -> 0; else -> 0
            }
        }
        updateSidebarVisuals()
    }

    private fun updatePresetHighlights() {
        presetButtons.forEach { (idx, btn) -> btn.background = GradientDrawable().apply { setColor(Color.TRANSPARENT); if (idx == activePreset) setStroke(4, Color.WHITE); cornerRadius = 12f } }
    }

    private fun triggerFlashPulse() {
        flashOverlay.alpha = 0.6f; flashOverlay.animate().alpha(0f).setDuration(400).start()
        photoBtn.animate().scaleX(1.8f).scaleY(1.8f).setDuration(100).withEndAction { photoBtn.animate().scaleX(1.5f).scaleY(1.5f).setDuration(200).start() }.start()
    }

    private fun applyPreset(idx: Int) {
        val p = presets[idx] ?: return
        activePreset = idx; updatePresetHighlights(); currentAnimator?.cancel()
        if (!axisLocked) { sliders["AXIS"]?.progress = p.axis - 1 }
        val startValues = sliders.mapValues { it.value.progress }
        currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = transitionMs
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                sliders.forEach { (id, sb) ->
                    val start = startValues[id] ?: sb.progress
                    if (id == "AXIS") {
                        if (!axisLocked) sb.progress = (start + ((p.axis - 1) - start) * t).toInt()
                    } else {
                        val target = p.sliderValues[id] ?: start
                        sb.progress = (start + (target - start) * t).toInt()
                    }
                }
                renderer.flipX = p.flipX; renderer.flipY = p.flipY; renderer.rot180 = p.rot180; updateSidebarVisuals()
            }
            start()
        }
    }

    private fun savePreset(idx: Int) {
        presets[idx] = Preset(sliders.filter { it.key != "AXIS" }.mapValues { it.value.progress }, renderer.flipX, renderer.flipY, renderer.rot180, sliders["AXIS"]?.progress?.plus(1) ?: 1)
        activePreset = idx; updatePresetHighlights()
        Toast.makeText(this, "Preset $idx Saved", Toast.LENGTH_SHORT).show()
    }

    private fun initDefaultPresets() {
        fun p(ax: Int=1, mAng: Int=0, mAm: Int=0, mAr: Int=0, mRot: Int=500, mZm: Int=250, mZMod: Int=0, mZR: Int=0,
              cAng: Int=0, cAm: Int=0, cAr: Int=0, rgb: Int=0, rR: Int=0, rM: Int=0, hue: Int=0, hR: Int=0, hM: Int=0,
              neg: Int=0, glw: Int=0, gR: Int=0, gM: Int=0, mRgb: Int=0, txM: Int=0, txR: Int=0,
              satur: Int=500, contrast: Int=500) = Preset(mapOf(
            "M_ANGLE" to mAng, "M_ANGLE_MOD" to mAm, "M_ANGLE_RATE" to mAr, "M_ROT" to mRot,
            "M_ZOOM" to mZm, "M_ZOOM_MOD" to mZMod, "M_ZOOM_RATE" to mZR,
            "C_ANGLE" to cAng, "C_ANGLE_MOD" to cAm, "C_ANGLE_RATE" to cAr,
            "RGB" to rgb, "RGB_RATE" to rR, "RGB_MOD" to rM, "HUE" to hue, "HUE_RATE" to hR, "HUE_MOD" to hM,
            "NEG" to neg, "GLOW" to glw, "GLOW_RATE" to gR, "GLOW_MOD" to gM, "M_RGB" to mRgb,
            "M_TX_MOD" to txM, "M_TX_RATE" to txR, "CONTRAST" to contrast, "VIBRANCE" to satur, "M_TX" to 500, "M_TY" to 500
        ), 1f, -1f, false, ax)

        presets[1] = p(ax=2, mRot=505, mAm=80, mAr=50, mZm=250, mZMod=120, mZR=80)
        presets[2] = p(ax=3, mRot=510, cAm=150, cAr=100, glw=200, gM=300, gR=120)
        presets[3] = p(ax=4, mRot=500, mZm=300, hue=200, hM=800, hR=150, satur=700)
        presets[4] = p(ax=8, mRot=502, mZm=180, mZMod=600, mZR=200, glw=350, contrast=650)
        presets[5] = p(ax=6, mRot=520, mRgb=120, rgb=100, rM=300, rR=150, neg=200, glw=500, contrast=800)
        presets[6] = p(ax=12, mRot=505, mAm=1000, mAr=50, txM=400, txR=100, hue=400, glw=400)
        presets[7] = p(ax=5, mRot=560, mRgb=250, rgb=200, rM=600, rR=300, neg=400, glw=700, contrast=1000, satur=800)
        presets[8] = p(ax=16, mRot=500, mAm=1000, mAr=400, mZMod=800, mZR=350, cAm=1000, cAr=200, neg=1000, glw=1000, satur=1000)
    }

    private fun toggleHud() { isHudVisible = !isHudVisible; hudContainer.visibility = if (isHudVisible) View.VISIBLE else View.GONE; if (isHudVisible) hideSystemUI() }
    private fun toggleMenu() { isMenuExpanded = !isMenuExpanded; sliderBox.visibility = if (isMenuExpanded) View.VISIBLE else View.GONE; menuToggleBtn.text = if (isMenuExpanded) "<" else ">" }
    private fun toggleReadability() {
        readabilityMode = !readabilityMode
        val bg = if (readabilityMode) GradientDrawable().apply { setColor(Color.argb(205, 12, 12, 12)); cornerRadius = 32f } else null
        sliderBox.background = bg; sideBox.background = bg; presetBox.background = bg; captureBox.background = bg; togglePanel.background = bg
        readabilityBtn.alpha = if (readabilityMode) 1.0f else 0.3f; resetBtn.alpha = if (readabilityMode) 1.0f else 0.3f
    }
    private fun updateSidebarVisuals() { flipXBtn.alpha = if (renderer.flipX < 0f) 1.0f else 0.3f; flipYBtn.alpha = if (renderer.flipY > 0f) 1.0f else 0.3f; rot180Btn.alpha = if (renderer.rot180) 1.0f else 0.3f }
    private fun addHeader(m: LinearLayout, t: String) = m.addView(TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 8f; alpha = 0.3f; setPadding(0, 45, 0, 5) })
    private fun hideSystemUI() { window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) }

    // --- RENDERER ---
    inner class KaleidoscopeRenderer(private val ctx: MainActivity) : GLSurfaceView.Renderer {
        private var program = 0; private var texID = -1; private var surfaceTexture: SurfaceTexture? = null
        private lateinit var pBuf: FloatBuffer; private lateinit var tBuf: FloatBuffer
        private var uLocs = mutableMapOf<String, Int>()

        var axisCount = 2.0f; var flipX = 1.0f; var flipY = -1.0f; var rot180 = false; var contrast = 1.0f; var saturation = 1.0f
        var mAngleBase = 0f; var mRotSpd = 0.0; var mRotAccum = 0.0; var mZoomBase = 1.0; var mRgbShiftBase = 0f
        var lAngleBase = 0f; var lRotSpd = 0.0; var lRotAccum = 0.0; var lZoomBase = 1.0
        var rgbShiftBase = 0f; var hueShiftBase = 0f; var solarizeBase = 0f; var bloomBase = 0f
        var mTx = 0f; var mTy = 0f; var lTx = 0f; var lTy = 0f

        private val mods = mutableMapOf<String, Float>(); private val rates = mutableMapOf<String, Float>()
        private val phases = mutableMapOf<String, Double>(); private val driftPhases = mutableMapOf<String, Double>()
        private var captureRequested = false

        fun setMod(id: String, v: Float) { mods[id] = v }; fun setRate(id: String, v: Float) { rates[id] = v }
        fun resetPhases() { phases.clear(); driftPhases.clear(); mods.clear(); rates.clear() }
        fun capturePhoto() { captureRequested = true }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
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
                        float off = (i==0) ? uMRGB : (i==2) ? -uMRGB : 0.0;
                        vec2 coord = v + vec2(off, 0.0);
                        vec2 uv = coord - 0.5;
                        uv.x *= uA;
                        uv += uMT;
                        uv *= uMZ;
                        float a1 = uMR * 0.01745329;
                        uv = vec2(uv.x * cos(a1) - uv.y * sin(a1), uv.x * sin(a1) + uv.y * cos(a1));
                        if(uAx > 1.1) {
                            float r = length(uv);
                            float slice = 6.283185 / uAx;
                            float a = mod(atan(uv.y, uv.x), slice);
                            if(mod(uAx, 2.0) < 0.1) a = abs(a - slice * 0.5);
                            uv = vec2(cos(a), sin(a)) * r;
                        }
                        uv += uCT;
                        uv *= uLZ;
                        float a2 = uLR * 0.01745329;
                        uv = vec2(uv.x * cos(a2) - uv.y * sin(a2), uv.x * sin(a2) + uv.y * cos(a2));
                        uv.x /= uA;
                        vec2 fuv = (uAx > 1.1) ? abs(fract(uv - 0.5) * 2.0 - 1.0) : fract(uv + 0.5);
                        if(uF.x < 0.0) fuv.x = 1.0 - fuv.x;
                        if(uF.y > 0.0) fuv.y = 1.0 - fuv.y;
                        if(uRGB > 0.001) {
                             if(i==0) finalColor.r = texture2D(uTex, fuv + vec2(uRGB, 0.0)).r;
                             else if(i==1) finalColor.g = texture2D(uTex, fuv).g;
                             else finalColor.b = texture2D(uTex, fuv - vec2(uRGB, 0.0)).b;
                        } else {
                             vec3 smp = texture2D(uTex, fuv).rgb;
                             if(i==0) finalColor.r = smp.r; else if(i==1) finalColor.g = smp.g; else finalColor.b = smp.b;
                        }
                    }
                    if(uSol > 0.01) finalColor = mix(finalColor, abs(finalColor - uSol), step(0.1, uSol));
                    if(uHue > 0.001) {
                        const vec3 k = vec3(0.57735);
                        float ca = cos(uHue * 6.28318);
                        finalColor = finalColor * ca + cross(k, finalColor) * sin(uHue * 6.28318) + k * dot(k, finalColor) * (1.0 - ca);
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

            listOf("uMR", "uLR", "uA", "uMZ", "uLZ", "uAx", "uC", "uS", "uHue", "uSol", "uBloom", "uRGB", "uMRGB", "uF", "uMT", "uCT", "uTex").forEach {
                uLocs[it] = GLES20.glGetUniformLocation(program, it)
            }
            texID = createOESTex()
            surfaceTexture = SurfaceTexture(texID)
            pBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)).position(0) }
            tBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(0f,0f, 1f,0f, 0f,1f, 1f,1f)).position(0) }
            ctx.runOnUiThread { ctx.startCamera() }
        }

        private var viewWidth = 1; private var viewHeight = 1; private var lastTime = System.nanoTime()
        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { viewWidth = w; viewHeight = h; GLES20.glViewport(0, 0, w, h) }

        override fun onDrawFrame(gl: GL10?) {
            val now = System.nanoTime(); val d = (now - lastTime) / 1e9; lastTime = now; val tpi = 2.0 * PI
            try { surfaceTexture?.updateTexImage() } catch (e: Exception) { return }
            fun calcPh(id: String): Float {
                val r = (rates[id] ?: 0f).toDouble(); val drift = (driftPhases[id] ?: 0.0) + (r * 0.13) * d * tpi; driftPhases[id] = drift
                val p = (phases[id] ?: 0.0) + (r + sin(drift) * r * 0.4) * d * tpi; phases[id] = p; return sin(p).toFloat() * (mods[id] ?: 0f)
            }
            val ids = listOf("M_ANGLE", "C_ANGLE", "M_ZOOM", "C_ZOOM", "HUE", "NEG", "GLOW", "RGB", "M_RGB", "M_TX", "M_TY", "C_TX", "C_TY")
            val phMap = ids.associateWith { calcPh(it) }
            mRotAccum += mRotSpd * 120.0 * d; lRotAccum += lRotSpd * 120.0 * d
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawScene(phMap, viewWidth, viewHeight)
            if (captureRequested) {
                captureRequested = false
                val b = ByteBuffer.allocate(viewWidth * viewHeight * 4)
                GLES20.glReadPixels(0, 0, viewWidth, viewHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
                Thread {
                    val bmp = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(b) }
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "SB_${System.currentTimeMillis()}.jpg"); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SpaceBeam")
                    }
                    ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri -> ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
                }.start()
            }
        }

        private fun drawScene(ph: Map<String, Float>, w: Int, h: Int) {
            GLES20.glUseProgram(program)
            GLES20.glUniform1f(uLocs["uMR"]!!, (mAngleBase + mRotAccum + (ph["M_ANGLE"] ?: 0f) * 90.0).toFloat() + (if(rot180) 180f else 0f))
            // Fixed: Added 90 degree initial offset to Camera Rotation (uLR)
            GLES20.glUniform1f(uLocs["uLR"]!!, (lAngleBase + lRotAccum + (ph["C_ANGLE"] ?: 0f) * 45.0 + 90.0).toFloat())
            GLES20.glUniform1f(uLocs["uA"]!!, w.toFloat()/h.toFloat()); GLES20.glUniform1f(uLocs["uMZ"]!!, (mZoomBase + (ph["M_ZOOM"] ?: 0f) * 0.5 * mZoomBase).toFloat())
            GLES20.glUniform1f(uLocs["uLZ"]!!, (lZoomBase + (ph["C_ZOOM"] ?: 0f) * 0.5 * lZoomBase).toFloat()); GLES20.glUniform1f(uLocs["uAx"]!!, axisCount)
            GLES20.glUniform2f(uLocs["uF"]!!, flipX, flipY); GLES20.glUniform1f(uLocs["uC"]!!, contrast); GLES20.glUniform1f(uLocs["uS"]!!, saturation)
            GLES20.glUniform1f(uLocs["uHue"]!!, hueShiftBase + (ph["HUE"] ?: 0f)); GLES20.glUniform1f(uLocs["uSol"]!!, solarizeBase + (ph["NEG"] ?: 0f))
            GLES20.glUniform1f(uLocs["uBloom"]!!, bloomBase + (ph["GLOW"] ?: 0f)); GLES20.glUniform1f(uLocs["uRGB"]!!, rgbShiftBase + (ph["RGB"] ?: 0f))
            GLES20.glUniform1f(uLocs["uMRGB"]!!, mRgbShiftBase + (ph["M_RGB"] ?: 0f))
            GLES20.glUniform2f(uLocs["uMT"]!!, mTx + (ph["M_TX"] ?: 0f), mTy + (ph["M_TY"] ?: 0f))
            GLES20.glUniform2f(uLocs["uCT"]!!, lTx + (ph["C_TX"] ?: 0f), lTy + (ph["C_TY"] ?: 0f))
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texID); GLES20.glUniform1i(uLocs["uTex"]!!, 0)
            val pL = GLES20.glGetAttribLocation(program, "p"); val tL = GLES20.glGetAttribLocation(program, "t")
            GLES20.glEnableVertexAttribArray(pL); GLES20.glVertexAttribPointer(pL, 2, GLES20.GL_FLOAT, false, 0, pBuf)
            GLES20.glEnableVertexAttribArray(tL); GLES20.glVertexAttribPointer(tL, 2, GLES20.GL_FLOAT, false, 0, tBuf)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun provideSurface(req: SurfaceRequest) {
            glView.queueEvent {
                val st = surfaceTexture ?: return@queueEvent
                st.setDefaultBufferSize(req.resolution.width, req.resolution.height)
                val s = Surface(st)
                req.provideSurface(s, ContextCompat.getMainExecutor(ctx)) { s.release() }
            }
        }

        private fun createOESTex(): Int {
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return t[0]
        }

        private fun compile(t: Int, s: String): Int = GLES20.glCreateShader(t).apply {
            GLES20.glShaderSource(this, s); GLES20.glCompileShader(this)
        }
    }
}