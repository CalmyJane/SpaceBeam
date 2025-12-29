package com.calmyjane.spacebeam

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.opengl.*
import android.os.Bundle
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
    private var isHudVisible = true
    private val sliders = mutableMapOf<String, SeekBar>()

    private lateinit var flipXBtn: ImageButton
    private lateinit var flipYBtn: ImageButton
    private lateinit var rot180Btn: ImageButton
    private lateinit var readabilityBtn: ImageButton

    private data class Preset(val sliderValues: Map<String, Int>, val flipX: Float, val flipY: Float, val rot180: Boolean)
    private val presets = mutableMapOf<Int, Preset>()
    private var heldPresetIndex: Int? = null

    private var transitionMs: Long = 1000L
    private var readabilityMode = false

    private lateinit var sliderBox: ScrollView
    private lateinit var sideBox: LinearLayout
    private lateinit var presetBox: LinearLayout
    private lateinit var toolBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        hideSystemUI()

        renderer = KaleidoscopeRenderer()
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)

        setupUltraMinimalHUD()
        initDefaultPresets()

        // Ensure Clean State on Start
        glView.post { globalReset(); applyPreset(1) } // Start with Preset 1 subtle movement

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
    }

    private fun initDefaultPresets() {
        fun p(ax: Int=1, mRot: Int=500, mRm: Int=0, mRr: Int=0, mZm: Int=250, mZm_m: Int=0, mZm_r: Int=0,
              cRot: Int=500, cRm: Int=0, cRr: Int=0, cZm: Int=250, cZm_m: Int=0, cZm_r: Int=0,
              rgb: Int=0, hue: Int=0, neg: Int=0, glw: Int=0, mRgb: Int=0) = Preset(mapOf(
            "M_ROT" to mRot, "M_ROT_MOD" to mRm, "M_ROT_RATE" to mRr,
            "M_ZOOM" to mZm, "M_ZOOM_MOD" to mZm_m, "M_ZOOM_RATE" to mZm_r,
            "C_ROT" to cRot, "C_ROT_MOD" to cRm, "C_ROT_RATE" to cRr,
            "C_ZOOM" to cZm, "C_ZOOM_MOD" to cZm_m, "C_ZOOM_RATE" to cZm_r,
            "RGB" to rgb, "HUE" to hue, "NEG" to neg, "GLOW" to glw, "M_RGB" to mRgb,
            "CONTRAST" to 500, "VIBRANCE" to 500
        ), 1f, -1f, false)

        // Preset 1: Very subtle MASTER ONLY drift
        presets[1] = p(ax=3, mRot=505, mRm=120, mRr=80, mZm=250, mZm_m=100, mZm_r=50)
        // Preset 2: Local Camera movement joins in
        presets[2] = p(ax=4, mRot=510, mRm=180, mRr=100, mZm=270, mZm_m=150, cRm=120, cRr=60)
        // Presets 3-8 build in intensity...
        presets[3] = p(ax=5, mRot=520, mRm=250, mZm=300, rgb=150, glw=150)
        presets[4] = p(ax=6, mRot=540, mRm=400, mZm=400, hue=300, cRm=250, cRr=150)
        presets[5] = p(ax=8, mRot=570, mRm=600, mZm=600, mRgb=200, neg=300)
        presets[6] = p(ax=10, mRot=650, mRm=800, mRr=500, mZm=800, glw=600, rgb=400)
        presets[7] = p(ax=12, mRot=750, mRm=950, mZm=450, neg=800, hue=700, mRgb=500)
        presets[8] = p(ax=15, mRot=1000, mRm=1000, mRr=1000, mZm=1000, glw=1000, rgb=1000)
    }

    private fun setupUltraMinimalHUD() {
        hudContainer = FrameLayout(this)

        // --- 1. SLIDER MENU ---
        sliderBox = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(850, -1)
            verticalScrollbarPosition = View.SCROLLBAR_POSITION_LEFT
            isVerticalScrollBarEnabled = true
        }
        val menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 20, 240) }
        sliderBox.addView(menu)

        // AXIS SLIDER
        val axisCont = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 10, 0, 25) }
        val axisTv = TextView(this).apply { text = "AXIS"; setTextColor(Color.WHITE); textSize = 10f; setTypeface(null, Typeface.BOLD); minWidth = 140; setOnClickListener { sliders["AXIS"]?.progress = 1 } }
        val axisSb = SeekBar(this).apply {
            max = 15; progress = 1; layoutParams = LinearLayout.LayoutParams(610, 80)
            thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(26, 52) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { renderer.axisCount = (p + 1).toFloat() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        sliders["AXIS"] = axisSb; axisCont.addView(axisTv); axisCont.addView(axisSb); menu.addView(axisCont)

        fun createSlider(id: String, label: String, max: Int, start: Int, onP: (Int) -> Unit) {
            val container = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 4, 0, 0) }
            val tv = TextView(this).apply {
                text = label; setTextColor(Color.WHITE); textSize = 8f; minWidth = 140; alpha = 0.8f
                setOnClickListener {
                    sliders[id]?.progress = start
                    if(id.contains("ROT")) { if(id.startsWith("M")) renderer.mAngle=0.0 else renderer.lAngle=0.0 }
                }
            }
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

        fun createModPair(baseId: String) {
            val container = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(140, -12, 0, 8) }
            fun sub(subId: String, icon: String, onP: (Int) -> Unit) = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(0, 50, 1f)
                val iv = TextView(this@MainActivity).apply { text = icon; setTextColor(Color.WHITE); textSize = 11f; setTypeface(null, Typeface.BOLD); setPadding(0, 0, 5, 0); setOnClickListener { sliders[subId]?.progress = 0 } }
                val sb = SeekBar(this@MainActivity).apply {
                    max = 1000; progress = 0; layoutParams = LinearLayout.LayoutParams(-1, -1)
                    thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(12, 22) }
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) = onP(p)
                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) {}
                    })
                }
                sliders[subId] = sb; addView(iv); addView(sb)
            }
            container.addView(sub("${baseId}_MOD", "↕") { renderer.setMod(baseId, (it/1000f).pow(3f)) })
            container.addView(sub("${baseId}_RATE", "⏱") { renderer.setRate(baseId, (it/1000f).pow(3f)) })
            menu.addView(container)
        }

        addHeader(menu, "MASTER")
        createSlider("M_ROT", "ROTATION", 1000, 500) { renderer.mRotSpd = ((it-500)/500.0).pow(3.0)*1.5 }; createModPair("M_ROT")
        createSlider("M_ZOOM", "ZOOM", 1000, 250) { renderer.mZoomBase = 0.1 + ((it/1000.0).pow(2.0)*9.9) }; createModPair("M_ZOOM")
        createSlider("M_RGB", "RGB-SMUDGE", 1000, 0) { renderer.mRgbShift = it / 1000f * 0.05f }

        addHeader(menu, "CAMERA")
        createSlider("C_ROT", "ROTATION", 1000, 500) { renderer.lRotSpd = ((it-500)/500.0).pow(3.0)*1.5 }; createModPair("C_ROT")
        createSlider("C_ZOOM", "ZOOM", 1000, 250) { renderer.lZoomBase = 0.1 + ((it/1000.0).pow(2.0)*9.9) }; createModPair("C_ZOOM")
        createSlider("RGB", "RGB-SHIFT", 1000, 0) { renderer.rgbShiftBase = it / 1000f * 0.05f }; createModPair("RGB")
        createSlider("HUE", "HUE", 1000, 0) { renderer.hueShiftBase = it / 1000f }; createModPair("HUE")
        createSlider("NEG", "NEGATIVE", 1000, 0) { renderer.solarizeBase = it / 1000f }; createModPair("NEG")
        createSlider("GLOW", "GLOW", 1000, 0) { renderer.bloomBase = it / 1000f }; createModPair("GLOW")
        createSlider("CONTRAST", "CONTRAST", 1000, 500) { renderer.contrast = 0.5f + (it / 500f) }
        createSlider("VIBRANCE", "VIBRANCE", 1000, 500) { renderer.saturation = it / 500f }

        // --- Side/Preset/Tool Panels ---
        sideBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(10, 20, 10, 20) }
        fun textToIcon(t: String): BitmapDrawable {
            val b = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint().apply { color = Color.WHITE; textSize = 60f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
            c.drawText(t, 60f, 80f, p); return BitmapDrawable(resources, b)
        }
        fun createSideBtn(action: () -> Unit) = ImageButton(this).apply { setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.3f; layoutParams = LinearLayout.LayoutParams(100, 100); setOnClickListener { action(); updateSidebarVisuals() } }
        sideBox.addView(createSideBtn { currentSelector = if (currentSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA; startCamera() }.apply { setImageResource(android.R.drawable.ic_menu_camera) })
        flipXBtn = createSideBtn { renderer.flipX = if(renderer.flipX == 1f) -1f else 1f }.apply { setImageDrawable(textToIcon("↔")) }
        flipYBtn = createSideBtn { renderer.flipY = if(renderer.flipY == 1f) -1f else 1f }.apply { setImageDrawable(textToIcon("↕")) }
        rot180Btn = createSideBtn { renderer.rot180 = !renderer.rot180 }.apply { setImageResource(android.R.drawable.ic_menu_rotate) }
        sideBox.addView(flipXBtn); sideBox.addView(flipYBtn); sideBox.addView(rot180Btn)

        presetBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(25, 10, 25, 15) }
        val transContainer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val stopwatch = ImageView(this).apply { setImageResource(android.R.drawable.ic_menu_recent_history); setColorFilter(Color.WHITE); alpha = 0.6f; scaleX=0.55f; scaleY=0.55f }
        val timeLabel = TextView(this).apply { text = "1.0s"; setTextColor(Color.WHITE); textSize = 9f; minWidth = 100; setPadding(10,0,0,0) }
        val transSeekBar = SeekBar(this).apply {
            max = 1000; progress = 333; layoutParams = LinearLayout.LayoutParams(600, 45)
            thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(14, 28) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { transitionMs = ((p/1000f).pow(3.0f)*30000).toLong(); timeLabel.text = "%.1fs".format(transitionMs/1000f) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        transContainer.addView(stopwatch); transContainer.addView(timeLabel); transContainer.addView(transSeekBar)

        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun createPresetBtn(idx: Int) = Button(this).apply {
            text = idx.toString(); setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.5f; textSize = 16f
            layoutParams = LinearLayout.LayoutParams(85, 110); setPadding(0, 0, 0, 10)
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN) { heldPresetIndex = idx; alpha = 1f }
                if (ev.action == MotionEvent.ACTION_UP) { if (heldPresetIndex == idx) applyPreset(idx); heldPresetIndex = null; alpha = 0.5f; performClick() }
                true
            }
        }
        (8 downTo 1).forEach { presetRow.addView(createPresetBtn(it)) }
        presetBox.addView(transContainer); presetBox.addView(presetRow)

        toolBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(10, 10, 10, 10) }
        readabilityBtn = ImageButton(this).apply { setImageResource(android.R.drawable.ic_menu_view); setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.3f; layoutParams = LinearLayout.LayoutParams(100, 100); setOnClickListener { toggleReadability() } }
        val resetBtn = ImageButton(this).apply { setImageResource(android.R.drawable.ic_menu_close_clear_cancel); setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.3f; layoutParams = LinearLayout.LayoutParams(100, 100); setOnClickListener { heldPresetIndex?.let { savePreset(it) } ?: globalReset() } }
        toolBox.addView(readabilityBtn); toolBox.addView(resetBtn)

        hudContainer.addView(sliderBox)
        hudContainer.addView(sideBox, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.END; topMargin = 40; rightMargin = 40 })
        hudContainer.addView(presetBox, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 15; rightMargin = 160 })
        hudContainer.addView(toolBox, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 15; rightMargin = 40 })
        addContentView(hudContainer, ViewGroup.LayoutParams(-1, -1)); updateSidebarVisuals()
    }

    private fun toggleReadability() {
        readabilityMode = !readabilityMode
        val bg = if (readabilityMode) GradientDrawable().apply { setColor(Color.argb(205, 12, 12, 12)); cornerRadius = 32f } else null
        sliderBox.background = bg; sideBox.background = bg; presetBox.background = bg; toolBox.background = bg
        readabilityBtn.alpha = if (readabilityMode) 1.0f else 0.3f
    }

    private fun applyPreset(idx: Int) {
        val p = presets[idx] ?: return
        val startValues = sliders.mapValues { it.value.progress }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = transitionMs
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                sliders.forEach { (id, sb) ->
                    if (id != "AXIS") {
                        val start = startValues[id] ?: sb.progress
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
        val vals = sliders.filter { it.key != "AXIS" }.mapValues { it.value.progress }
        presets[idx] = Preset(vals, renderer.flipX, renderer.flipY, renderer.rot180)
        Toast.makeText(this, "Preset $idx Saved", Toast.LENGTH_SHORT).show()
    }

    private fun updateSidebarVisuals() {
        flipXBtn.alpha = if (renderer.flipX < 0f) 1.0f else 0.3f
        flipYBtn.alpha = if (renderer.flipY > 0f) 1.0f else 0.3f
        rot180Btn.alpha = if (renderer.rot180) 1.0f else 0.3f
    }

    private fun globalReset() {
        renderer.mAngle = 0.0; renderer.lAngle = 0.0; renderer.resetPhases()
        renderer.flipX = 1f; renderer.flipY = -1f; renderer.rot180 = false
        sliders.forEach { (id, sb) ->
            sb.progress = when (id) {
                "AXIS" -> 1
                "M_ROT", "C_ROT", "CONTRAST", "VIBRANCE" -> 500
                "M_ZOOM", "C_ZOOM" -> 250
                else -> 0
            }
        }
        updateSidebarVisuals()
    }

    private fun addHeader(m: LinearLayout, t: String) = m.addView(TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 8f; alpha = 0.3f; setPadding(0, 45, 0, 5) })
    private fun startCamera() { val cp = ProcessCameraProvider.getInstance(this); cp.addListener({ val provider = cp.get(); val preview = Preview.Builder().build().apply { setSurfaceProvider { renderer.provideSurface(it) } }; try { provider.unbindAll(); provider.bindToLifecycle(this, currentSelector, preview) } catch (e: Exception) {} }, ContextCompat.getMainExecutor(this)) }
    private fun hideSystemUI() { window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) }
    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    inner class KaleidoscopeRenderer : GLSurfaceView.Renderer {
        private var program = 0; private var texID = -1; private var surfaceTexture: SurfaceTexture? = null
        private lateinit var pBuf: FloatBuffer; private lateinit var tBuf: FloatBuffer
        private var uLocs = mutableMapOf<String, Int>()

        var axisCount = 2.0f; var flipX = 1.0f; var flipY = -1.0f; var rot180 = false
        var contrast = 1.0f; var saturation = 1.0f
        var mRotSpd = 0.0; var mAngle = 0.0; var mZoomBase = 1.0; var mRgbShift = 0f
        var lRotSpd = 0.0; var lAngle = 0.0; var lZoomBase = 1.0
        var rgbShiftBase = 0f; var hueShiftBase = 0f; var solarizeBase = 0f; var bloomBase = 0f

        private val mods = mutableMapOf<String, Float>()
        private val rates = mutableMapOf<String, Float>()
        private val phases = mutableMapOf<String, Double>()

        fun setMod(id: String, v: Float) { mods[id] = v }
        fun setRate(id: String, v: Float) { rates[id] = v }
        fun resetPhases() { phases.clear(); mods.clear(); rates.clear() }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val vSrc = "attribute vec4 p; attribute vec2 t; varying vec2 v; void main(){ gl_Position=p; v=t; }"
            val fSrc = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v; uniform samplerExternalOES uTex; uniform float uMR, uLR, uA, uMZ, uLZ, uAx, uC, uS, uHue, uSol, uBloom, uRGB, uMRGB; uniform vec2 uF; 
            mat2 rot(float d) { float a=radians(d); float s=sin(a), c=cos(a); return mat2(c,-s,s,c); }
            vec3 hS(vec3 c, float h) { const vec3 k=vec3(0.57735); float ca=cos(h*6.28); return c*ca+cross(k,c)*sin(h*6.28)+k*dot(k,c)*(1.0-ca); }
            void main() {
                // Pre-transform sampling for Camera RGB
                vec2 fv = v;
                vec2 uvOrig = (fv - 0.5); 
                uvOrig.x *= uA;
                
                // Camera Transform
                vec2 cUv = uvOrig;
                cUv *= uLZ; cUv = rot(uLR) * cUv;
                
                // Kaleidoscope
                if(uAx > 1.1) {
                    float r = length(cUv); float a = atan(cUv.y, cUv.x); float s = 6.283185/uAx;
                    a = mod(a, s); if(mod(uAx, 2.0) < 0.1) a = abs(a - s/2.0);
                    cUv = vec2(cos(a), sin(a)) * r;
                }
                
                // Master Transform
                vec2 mUv = cUv;
                mUv *= uMZ; mUv = rot(uMR) * mUv;
                mUv.x /= uA;
                
                vec2 finalUv = (uAx > 1.1) ? abs(fract(mUv - 0.5) * 2.0 - 1.0) : fract(mUv + 0.5);
                if(uF.x < 0.0) finalUv.x = 1.0 - finalUv.x; if(uF.y > 0.0) finalUv.y = 1.0 - finalUv.y; 
                
                // Sampling Camera Stream with Symmetric RGB Shift
                vec3 col; 
                if (uRGB > 0.001) col = vec3(texture2D(uTex, finalUv + vec2(uRGB, 0)).r, texture2D(uTex, finalUv).g, texture2D(uTex, finalUv - vec2(uRGB, 0)).b); 
                else col = texture2D(uTex, finalUv).rgb;

                // Color Effects
                if(uSol > 0.01) col = mix(col, abs(col - uSol), step(0.1, uSol)); 
                if(uHue > 0.001) col = hS(col, uHue); 
                col = (col - 0.5) * uC + 0.5;
                float l = dot(col, vec3(0.3, 0.6, 0.1)); 
                col = mix(vec3(l), col, uS); 
                if(uBloom > 0.01) col += smoothstep(0.6, 1.0, l) * col * uBloom * 3.0;

                // POST-KALEIDOSCOPE Master RGB Smudge
                if(uMRGB > 0.001) { 
                   vec3 sm;
                   sm.r = texture2D(uTex, fract(v + vec2(uMRGB, 0.0))).r;
                   sm.g = col.g;
                   sm.b = texture2D(uTex, fract(v - vec2(uMRGB, 0.0))).b;
                   col = mix(col, sm, 0.4);
                }
                gl_FragColor = vec4(col, 1.0);
            }
            """.trimIndent()
            program = GLES20.glCreateProgram().apply { GLES20.glAttachShader(this, compile(GLES20.GL_VERTEX_SHADER, vSrc)); GLES20.glAttachShader(this, compile(GLES20.GL_FRAGMENT_SHADER, fSrc)); GLES20.glLinkProgram(this) }
            listOf("uMR", "uLR", "uA", "uMZ", "uLZ", "uAx", "uC", "uS", "uHue", "uSol", "uBloom", "uRGB", "uMRGB", "uF", "uTex").forEach { uLocs[it] = GLES20.glGetUniformLocation(program, it) }
            texID = createOESTex(); surfaceTexture = SurfaceTexture(texID)
            pBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)).position(0) }
            tBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(0f,0f, 1f,0f, 0f,1f, 1f,1f)).position(0) }
        }

        private var aspect = 1.0f; private var lastTime = System.nanoTime()
        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { aspect = w.toFloat() / h.toFloat(); GLES20.glViewport(0, 0, w, h) }
        override fun onDrawFrame(gl: GL10?) {
            val now = System.nanoTime(); val d = (now - lastTime) / 1e9; lastTime = now; val tpi = 2.0 * PI
            fun updPh(id: String): Float { val p = (phases[id] ?: 0.0) + (rates[id] ?: 0f) * d * tpi; phases[id] = p; return sin(p).toFloat() * (mods[id] ?: 0f) }
            mAngle = (mAngle + mRotSpd * 120.0 * d) % 360.0; lAngle = (lAngle + lRotSpd * 120.0 * d) % 360.0
            surfaceTexture?.updateTexImage(); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); GLES20.glUseProgram(program)
            GLES20.glUniform1f(uLocs["uMR"]!!, (mAngle + updPh("M_ROT") * 90.0).toFloat() + (if(rot180) 180f else 0f))
            GLES20.glUniform1f(uLocs["uLR"]!!, (lAngle + updPh("C_ROT") * 45.0).toFloat())
            GLES20.glUniform1f(uLocs["uA"]!!, aspect); GLES20.glUniform1f(uLocs["uMZ"]!!, (mZoomBase + updPh("M_ZOOM") * 0.5 * mZoomBase).toFloat())
            GLES20.glUniform1f(uLocs["uLZ"]!!, (lZoomBase + updPh("C_ZOOM") * 0.5 * lZoomBase).toFloat()); GLES20.glUniform1f(uLocs["uAx"]!!, axisCount)
            GLES20.glUniform2f(uLocs["uF"]!!, flipX, flipY); GLES20.glUniform1f(uLocs["uC"]!!, contrast); GLES20.glUniform1f(uLocs["uS"]!!, saturation)
            GLES20.glUniform1f(uLocs["uHue"]!!, hueShiftBase + updPh("HUE")); GLES20.glUniform1f(uLocs["uSol"]!!, solarizeBase + updPh("NEG"))
            GLES20.glUniform1f(uLocs["uBloom"]!!, bloomBase + updPh("GLOW")); GLES20.glUniform1f(uLocs["uRGB"]!!, rgbShiftBase + updPh("RGB"))
            GLES20.glUniform1f(uLocs["uMRGB"]!!, mRgbShift); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texID); GLES20.glUniform1i(uLocs["uTex"]!!, 0)
            val pL = GLES20.glGetAttribLocation(program, "p"); val tL = GLES20.glGetAttribLocation(program, "t"); GLES20.glEnableVertexAttribArray(pL); GLES20.glVertexAttribPointer(pL, 2, GLES20.GL_FLOAT, false, 0, pBuf); GLES20.glEnableVertexAttribArray(tL); GLES20.glVertexAttribPointer(tL, 2, GLES20.GL_FLOAT, false, 0, tBuf); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        fun provideSurface(req: SurfaceRequest) { glView.queueEvent { surfaceTexture?.setDefaultBufferSize(req.resolution.width, req.resolution.height); val s = android.view.Surface(surfaceTexture); req.provideSurface(s, ContextCompat.getMainExecutor(this@MainActivity)) { s.release() } } }
        private fun createOESTex(): Int { val t = IntArray(1); GLES20.glGenTextures(1, t, 0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]); GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR); GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR); return t[0] }
        private fun compile(t: Int, s: String): Int = GLES20.glCreateShader(t).apply { GLES20.glShaderSource(this, s); GLES20.glCompileShader(this) }
    }
}