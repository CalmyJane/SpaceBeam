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

    // UI Containers
    private lateinit var sliderBox: ScrollView
    private lateinit var sideBox: LinearLayout
    private lateinit var presetBox: LinearLayout
    private lateinit var actionBox: LinearLayout

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
        setupTouchToggle()

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
    }

    private fun initDefaultPresets() {
        fun p(rgb: Int=0, hue: Int=0, sol: Int=0, blm: Int=0, con: Int=500, sat: Int=500, ms: Int=500, mr: Int=0, ma: Int=0, mz: Int=250, mzr: Int=0, mza: Int=0, ls: Int=500, lr: Int=0, la: Int=0, lz: Int=250, lzr: Int=0, lza: Int=0) =
            Preset(mapOf("RGB_SHT" to rgb, "HUE_SHT" to hue, "SOLAR" to sol, "BLOOM" to blm, "CON_AMT" to con, "SAT_AMT" to sat, "MR_SPD" to ms, "MR_RAT" to mr, "MR_AMP" to ma, "MZ_BAS" to mz, "MZ_RAT" to mzr, "MZ_AMP" to mza, "LR_SPD" to ls, "LR_RAT" to lr, "LR_AMP" to la, "LZ_BAS" to lz, "LZ_RAT" to lzr, "LZ_AMP" to lza), 1f, -1f, false)

        presets[1] = p()
        presets[2] = p(blm=150, ms=520, ma=100, mz=300)
        presets[3] = p(rgb=80, blm=300, ms=540, ma=300, mz=350, ls=520, la=150)
        presets[4] = p(rgb=200, hue=200, blm=500, ms=560, ma=500, mz=450, mzr=100, mza=150)
        presets[5] = p(sol=200, blm=600, ms=580, ma=700, mz=550, mzr=300, mza=400)
        presets[6] = p(rgb=400, hue=500, sol=150, con=700, ms=600, mz=650, ls=580, la=400)
        presets[7] = p(rgb=600, hue=800, sol=500, sat=800, ms=650, ma=1000, mz=800, lz=500)
        presets[8] = p(rgb=1000, hue=1000, sol=1000, blm=1000, ms=800, mz=1000, ls=800, lz=1000)
    }

    private fun setupTouchToggle() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                isHudVisible = !isHudVisible
                hudContainer.visibility = if (isHudVisible) View.VISIBLE else View.GONE
                if (isHudVisible) hideSystemUI()
                return true
            }
        })
        glView.setOnTouchListener { _, event -> gd.onTouchEvent(event); true }
    }

    private fun setupUltraMinimalHUD() {
        hudContainer = FrameLayout(this)

        // Island 1: Sliders
        sliderBox = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(1100, -1) }
        val menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(80, 60, 40, 240) }
        sliderBox.addView(menu)

        fun createSlider(id: String, label: String, max: Int, start: Int, onP: (Int) -> Unit) {
            val container = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 10, 0, 10) }
            val tv = TextView(this).apply {
                text = label; setTextColor(Color.WHITE); textSize = 8f; minWidth = 180; alpha = 0.7f
                setOnClickListener { sliders[id]?.progress = start; if(id.contains("ROT")) { if(id.startsWith("M")) renderer.mAngle=0.0 else renderer.lAngle=0.0 } }
            }
            val sb = SeekBar(this).apply {
                this.max = max; progress = start; layoutParams = LinearLayout.LayoutParams(600, 80)
                thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(20, 40) }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) = onP(p)
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            sliders[id] = sb; container.addView(tv); container.addView(sb); menu.addView(container)
        }

        addHeader(menu, "GLOBAL"); createSlider("AXIS", "AXIS", 15, 3) { renderer.axisCount = (it + 1).toFloat() }
        addHeader(menu, "POST"); createSlider("RGB_SHT", "RGB", 1000, 0) { renderer.rgbShift = it / 1000f * 0.05f }
        createSlider("HUE_SHT", "HUE", 1000, 0) { renderer.hueShift = it / 1000f }
        createSlider("BLOOM", "BLOOM", 1000, 0) { renderer.bloom = it / 1000f }
        addHeader(menu, "M-ROT"); createSlider("MR_SPD", "SPD", 1000, 500) { renderer.mRotSpd = ((it-500)/500.0).pow(3.0)*1.5 }
        createSlider("MR_AMP", "AMP", 1000, 0) { renderer.mRotAmp = (it/1000.0)*90.0 }
        addHeader(menu, "M-ZOOM"); createSlider("MZ_BAS", "BASE", 1000, 250) { renderer.mZoomBase = 0.1 + ((it/1000.0).pow(2.0)*9.9) }

        // Island 2: Sidebar (Top Right)
        sideBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(10, 20, 10, 20) }
        fun textToIcon(t: String): BitmapDrawable {
            val b = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint().apply { color = Color.WHITE; textSize = 60f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
            c.drawText(t, 60f, 80f, p); return BitmapDrawable(resources, b)
        }
        fun createSideBtn(action: () -> Unit) = ImageButton(this).apply { setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.3f; layoutParams = LinearLayout.LayoutParams(120, 120); setOnClickListener { action(); updateSidebarVisuals() } }
        val swBtn = createSideBtn { currentSelector = if (currentSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA; startCamera() }.apply { setImageResource(android.R.drawable.ic_menu_camera) }
        flipXBtn = createSideBtn { renderer.flipX = if(renderer.flipX == 1f) -1f else 1f }.apply { setImageDrawable(textToIcon("↔")) }
        flipYBtn = createSideBtn { renderer.flipY = if(renderer.flipY == 1f) -1f else 1f }.apply { setImageDrawable(textToIcon("↕")) }
        rot180Btn = createSideBtn { renderer.rot180 = !renderer.rot180 }.apply { setImageResource(android.R.drawable.ic_menu_rotate) }
        sideBox.addView(swBtn); sideBox.addView(flipXBtn); sideBox.addView(flipYBtn); sideBox.addView(rot180Btn)

        // Island 3: Preset Panel (Bottom Right)
        presetBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; setPadding(30, 20, 30, 20) }

        val transContainer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 20) }
        val stopwatch = ImageView(this).apply { setImageResource(android.R.drawable.ic_menu_recent_history); setColorFilter(Color.WHITE); alpha = 0.6f; scaleX=0.6f; scaleY=0.6f }
        val timeLabel = TextView(this).apply { text = "1.0s"; setTextColor(Color.WHITE); textSize = 10f; setPadding(10, 0, 20, 0) }
        val transSeekBar = SeekBar(this).apply {
            max = 1000; progress = 333
            layoutParams = LinearLayout.LayoutParams(800, 60)
            thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(20, 30) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    transitionMs = ((p / 1000f).pow(3f) * 30000).toLong().coerceAtLeast(0L)
                    timeLabel.text = "%.1fs".format(transitionMs / 1000f)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        transContainer.addView(stopwatch); transContainer.addView(timeLabel); transContainer.addView(transSeekBar)

        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun createPresetBtn(idx: Int) = Button(this).apply {
            text = idx.toString(); setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.4f
            layoutParams = LinearLayout.LayoutParams(110, 110).apply { setMargins(5, 0, 5, 0) }
            setPadding(0, 0, 0, 0) // Prevents number clipping
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN) { heldPresetIndex = idx; alpha = 1f }
                if (ev.action == MotionEvent.ACTION_UP) { if (heldPresetIndex == idx) applyPreset(idx); heldPresetIndex = null; alpha = 0.4f; performClick() }
                true
            }
        }
        (1..8).reversed().forEach { presetRow.addView(createPresetBtn(it)) }
        presetBox.addView(transContainer); presetBox.addView(presetRow)

        // Island 4: Action Panel (Far Bottom Right)
        actionBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(15, 15, 15, 15) }
        readabilityBtn = ImageButton(this).apply { setImageResource(android.R.drawable.ic_menu_view); setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.3f; layoutParams = LinearLayout.LayoutParams(120, 120); setOnClickListener { toggleReadability() } }
        val resetBtn = ImageButton(this).apply { setImageResource(android.R.drawable.ic_menu_close_clear_cancel); setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.3f; layoutParams = LinearLayout.LayoutParams(120, 120); setOnClickListener { heldPresetIndex?.let { savePreset(it) } ?: globalReset() } }
        actionBox.addView(readabilityBtn); actionBox.addView(resetBtn)

        hudContainer.addView(sliderBox)
        hudContainer.addView(sideBox, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.END; topMargin = 40; rightMargin = 40 })
        hudContainer.addView(presetBox, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 20; rightMargin = 180 })
        hudContainer.addView(actionBox, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 20; rightMargin = 40 })

        addContentView(hudContainer, ViewGroup.LayoutParams(-1, -1)); updateSidebarVisuals()
    }

    private fun toggleReadability() {
        readabilityMode = !readabilityMode
        val bg = if (readabilityMode) GradientDrawable().apply { setColor(Color.argb(160, 30, 30, 30)); cornerRadius = 25f } else null
        sliderBox.background = bg; sideBox.background = bg; presetBox.background = bg; actionBox.background = bg
        readabilityBtn.alpha = if (readabilityMode) 1.0f else 0.3f
    }

    private fun applyPreset(idx: Int) {
        val p = presets[idx] ?: return
        val startValues = sliders.mapValues { it.value.progress }
        val sFX = renderer.flipX; val sFY = renderer.flipY; val sRot = if(renderer.rot180) 1f else 0f; val eRot = if(p.rot180) 1f else 0f

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
                renderer.flipX = sFX + (p.flipX - sFX) * t; renderer.flipY = sFY + (p.flipY - sFY) * t
                renderer.rot180 = (sRot + (eRot - sRot) * t) > 0.5f; updateSidebarVisuals()
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
        renderer.mAngle = 0.0; renderer.lAngle = 0.0; renderer.resetMPh(); renderer.resetLPh()
        renderer.flipX = 1f; renderer.flipY = -1f; renderer.rot180 = false
        sliders["AXIS"]?.progress = 3
        val resetMap = mapOf("RGB_SHT" to 0, "HUE_SHT" to 0, "SOLAR" to 0, "BLOOM" to 0, "CON_AMT" to 500, "SAT_AMT" to 500, "MR_SPD" to 500, "MR_RAT" to 0, "MR_AMP" to 0, "MZ_BAS" to 250, "MZ_RAT" to 0, "MZ_AMP" to 0, "LR_SPD" to 500, "LR_RAT" to 0, "LR_AMP" to 0, "LZ_BAS" to 250, "LZ_RAT" to 0, "LZ_AMP" to 0)
        resetMap.forEach { (id, v) -> sliders[id]?.progress = v }
        updateSidebarVisuals()
    }

    private fun addHeader(m: LinearLayout, t: String) = m.addView(TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 10f; alpha = 0.4f; setPadding(0, 50, 0, 10) })
    private fun startCamera() { val cp = ProcessCameraProvider.getInstance(this); cp.addListener({ val provider = cp.get(); val preview = Preview.Builder().build().apply { setSurfaceProvider { renderer.provideSurface(it) } }; try { provider.unbindAll(); provider.bindToLifecycle(this, currentSelector, preview) } catch (e: Exception) {} }, ContextCompat.getMainExecutor(this)) }
    private fun hideSystemUI() { window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) }
    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    inner class KaleidoscopeRenderer : GLSurfaceView.Renderer {
        private var program = 0; private var texID = -1; private var surfaceTexture: SurfaceTexture? = null
        private lateinit var pBuf: FloatBuffer; private lateinit var tBuf: FloatBuffer
        private var uMRLoc = -1; private var uLRLoc = -1; private var uALoc = -1; private var uMZLoc = -1; private var uLZLoc = -1; private var uAxLoc = -1; private var uFLoc = -1; private var uTexLoc = -1; private var uCLoc = -1; private var uSLoc = -1; private var uHueLoc = -1; private var uSolLoc = -1; private var uBloomLoc = -1; private var uRGBLoc = -1
        var axisCount = 4.0f; var mZoomBase = 1.0; var mZoomAmp = 0.0; var mZoomFreq = 0.0; var lZoomBase = 1.0; var lZoomAmp = 0.0; var lZoomFreq = 0.0
        var mRotSpd = 0.0; var mAngle = 0.0; var mRotAmp = 0.0; var mRotFreq = 0.0; var lRotSpd = 0.0; var lAngle = 0.0; var lRotAmp = 0.0; var lRotFreq = 0.0
        var flipX = 1.0f; var flipY = -1.0f; var rot180 = false; var hueShift = 0.0f; var solarize = 0.0f; var bloom = 0.0f; var rgbShift = 0.0f; var contrast = 1.0f; var saturation = 1.0f
        private var mRotPh = 0.0; private var lRotPh = 0.0; private var mZPh = 0.0; private var lZPh = 0.0; private var aspect = 1.0f; private var lastTime = System.nanoTime()

        fun resetMPh() { mRotPh = 0.0; mZPh = 0.0 }; fun resetLPh() { lRotPh = 0.0; lZPh = 0.0 }
        fun provideSurface(req: SurfaceRequest) { glView.queueEvent { surfaceTexture?.setDefaultBufferSize(req.resolution.width, req.resolution.height); val s = android.view.Surface(surfaceTexture); req.provideSurface(s, ContextCompat.getMainExecutor(this@MainActivity)) { s.release() } } }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val vSrc = "attribute vec4 p; attribute vec2 t; varying vec2 v; void main(){ gl_Position=p; v=t; }"
            val fSrc = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v; uniform samplerExternalOES uTex; uniform float uMR, uLR, uA, uMZ, uLZ, uAx, uC, uS, uHue, uSol, uBloom, uRGB; uniform vec2 uF; 
            mat2 rot(float d) { float a=radians(d); float s=sin(a), c=cos(a); return mat2(c,-s,s,c); }
            vec3 hS(vec3 c, float h) { const vec3 k=vec3(0.57735); float ca=cos(h*6.28); return c*ca+cross(k,c)*sin(h*6.28)+k*dot(k,c)*(1.0-ca); }
            void main() {
                vec2 uv=(v-0.5); uv.x*=uA; uv*=uMZ; uv=rot(uMR)*uv;
                if(uAx>1.1) { float r=length(uv), a=atan(uv.y,uv.x), s=6.28/uAx; a=mod(a,s); if(mod(uAx,2.0)<0.1) a=abs(a-s/2.0); uv=vec2(cos(a),sin(a))*r; }
                uv*=uLZ; uv=rot(uLR)*uv; uv.x/=uA; vec2 fuv=(uAx>1.1)?abs(fract(uv-0.5)*2.0-1.0):fract(uv+0.5);
                if(uF.x<0.0) fuv.x=1.0-fuv.x; if(uF.y>0.0) fuv.y=1.0-fuv.y; 
                vec3 col; if(uRGB>0.001) col=vec3(texture2D(uTex,fuv+vec2(uRGB,0)).r, texture2D(uTex,fuv).g, texture2D(uTex,fuv-vec2(uRGB,0)).b); else col=texture2D(uTex,fuv).rgb;
                if(uSol>0.01) col=mix(col,abs(col-uSol),step(0.1,uSol)); if(uHue>0.001) col=hS(col,uHue); col=(col-0.5)*uC+0.5;
                float l=dot(col,vec3(0.3,0.6,0.1)); col=mix(vec3(l),col,uS); if(uBloom>0.01) col+=smoothstep(0.6,1.0,l)*col*uBloom*3.0; gl_FragColor=vec4(col,1.0);
            }
            """.trimIndent()
            program = createProgram(vSrc, fSrc); uMRLoc = GLES20.glGetUniformLocation(program, "uMR"); uLRLoc = GLES20.glGetUniformLocation(program, "uLR"); uALoc = GLES20.glGetUniformLocation(program, "uA"); uMZLoc = GLES20.glGetUniformLocation(program, "uMZ"); uLZLoc = GLES20.glGetUniformLocation(program, "uLZ"); uAxLoc = GLES20.glGetUniformLocation(program, "uAx"); uFLoc = GLES20.glGetUniformLocation(program, "uF"); uCLoc = GLES20.glGetUniformLocation(program, "uC"); uSLoc = GLES20.glGetUniformLocation(program, "uS"); uHueLoc = GLES20.glGetUniformLocation(program, "uHue"); uSolLoc = GLES20.glGetUniformLocation(program, "uSol"); uBloomLoc = GLES20.glGetUniformLocation(program, "uBloom"); uRGBLoc = GLES20.glGetUniformLocation(program, "uRGB"); uTexLoc = GLES20.glGetUniformLocation(program, "uTex")
            texID = createOESTex(); surfaceTexture = SurfaceTexture(texID)
            pBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)).position(0) }
            tBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(0f,0f, 1f,0f, 0f,1f, 1f,1f)).position(0) }
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { aspect = w.toFloat() / h.toFloat(); GLES20.glViewport(0, 0, w, h) }
        override fun onDrawFrame(gl: GL10?) {
            val now = System.nanoTime(); val d = (now - lastTime) / 1e9; lastTime = now; val tpi = 2.0 * PI
            mRotPh += mRotFreq * d * tpi; lRotPh += lRotFreq * d * tpi; mZPh += mZoomFreq * d * tpi; lZPh += lZoomFreq * d * tpi
            mAngle = (mAngle + mRotSpd * 120.0 * d) % 360.0; lAngle = (lAngle + lRotSpd * 120.0 * d) % 360.0
            surfaceTexture?.updateTexImage(); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); GLES20.glUseProgram(program)
            GLES20.glUniform1f(uMRLoc, (mAngle + sin(mRotPh) * mRotAmp).toFloat() + (if(rot180) 180f else 0f)); GLES20.glUniform1f(uLRLoc, (lAngle + sin(lRotPh) * lRotAmp).toFloat()); GLES20.glUniform1f(uALoc, aspect)
            GLES20.glUniform1f(uMZLoc, (mZoomBase + sin(mZPh) * mZoomAmp * mZoomBase).toFloat()); GLES20.glUniform1f(uLZLoc, (lZoomBase + sin(lZPh) * lZoomAmp * lZoomBase).toFloat()); GLES20.glUniform1f(uAxLoc, axisCount); GLES20.glUniform2f(uFLoc, flipX, flipY)
            GLES20.glUniform1f(uCLoc, contrast); GLES20.glUniform1f(uSLoc, saturation); GLES20.glUniform1f(uHueLoc, hueShift); GLES20.glUniform1f(uSolLoc, solarize); GLES20.glUniform1f(uBloomLoc, bloom); GLES20.glUniform1f(uRGBLoc, rgbShift)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texID); GLES20.glUniform1i(uTexLoc, 0)
            val pL = GLES20.glGetAttribLocation(program, "p"); val tL = GLES20.glGetAttribLocation(program, "t"); GLES20.glEnableVertexAttribArray(pL); GLES20.glVertexAttribPointer(pL, 2, GLES20.GL_FLOAT, false, 0, pBuf); GLES20.glEnableVertexAttribArray(tL); GLES20.glVertexAttribPointer(tL, 2, GLES20.GL_FLOAT, false, 0, tBuf); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        private fun createOESTex(): Int { val t = IntArray(1); GLES20.glGenTextures(1, t, 0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]); GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR); GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR); return t[0] }
        private fun createProgram(v: String, f: String): Int { val vs = compile(GLES20.GL_VERTEX_SHADER, v); val fs = compile(GLES20.GL_FRAGMENT_SHADER, f); return GLES20.glCreateProgram().apply { GLES20.glAttachShader(this, vs); GLES20.glAttachShader(this, fs); GLES20.glLinkProgram(this) } }
        private fun compile(t: Int, s: String): Int = GLES20.glCreateShader(t).apply { GLES20.glShaderSource(this, s); GLES20.glCompileShader(this) }
    }
}