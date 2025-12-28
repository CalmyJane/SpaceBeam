package com.calmyjane.spacebeam

import android.Manifest
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
        setupTouchToggle()

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
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

        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(1100, -1)
            isVerticalScrollBarEnabled = true
            scrollBarSize = 6
            verticalScrollbarPosition = View.SCROLLBAR_POSITION_LEFT
            verticalScrollbarThumbDrawable = GradientDrawable().apply { setColor(Color.WHITE) }
        }
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(80, 60, 40, 240)
        }
        scrollView.addView(menu)

        fun createSlider(id: String, label: String, max: Int, start: Int, onP: (Int) -> Unit) {
            val container = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 10, 0, 10) }
            val tv = TextView(this).apply {
                text = label; setTextColor(Color.WHITE); textSize = 8f; minWidth = 180; alpha = 0.7f
                setOnClickListener { sliders[id]?.progress = start }
            }
            val sliderWrapper = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(600, 80) }
            val sb = SeekBar(this).apply {
                this.max = max; progress = start
                layoutParams = FrameLayout.LayoutParams(-1, -1)
                splitTrack = false
                progressDrawable = GradientDrawable().apply { setStroke(1, Color.argb(100, 255, 255, 255)) }
                thumb = GradientDrawable().apply { setColor(Color.WHITE); setSize(20, 40) }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) = onP(p)
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            sliders[id] = sb
            sliderWrapper.addView(sb)
            container.addView(tv); container.addView(sliderWrapper)
            menu.addView(container)
        }

        fun addHeader(t: String) = menu.addView(TextView(this).apply {
            text = t; setTextColor(Color.WHITE); textSize = 10f; alpha = 0.4f
            setPadding(0, 50, 0, 10)
        })

        addHeader("GLOBAL")
        createSlider("AXIS", "AXIS", 15, 3) { renderer.axisCount = (it + 1).toFloat() }

        addHeader("POST EFFECTS")
        createSlider("RGB_SHT", "RGB ABERRATION", 1000, 0) { renderer.rgbShift = it / 1000f * 0.05f }
        createSlider("HUE_SHT", "HUE CYCLE", 1000, 0) { renderer.hueShift = it / 1000f }
        createSlider("SOLAR", "SOLARIZE", 1000, 0) { renderer.solarize = it / 1000f }
        createSlider("BLOOM", "BLOOM", 1000, 0) { renderer.bloom = it / 1000f }
        createSlider("CON_AMT", "CONTRAST", 1000, 500) { renderer.contrast = 0.5f + (it / 500f) }
        createSlider("SAT_AMT", "VIBRANCE", 1000, 500) { renderer.saturation = it / 500f }

        addHeader("MASTER ROTATION")
        createSlider("MR_SPD", "SPEED", 1000, 500) { renderer.mRotSpd = ((it-500)/500.0).pow(3.0)*1.5 }
        createSlider("MR_RAT", "RATE", 1000, 0) { renderer.mRotFreq = (it/1000.0)*0.5 }
        createSlider("MR_AMP", "AMP", 1000, 0) { renderer.mRotAmp = (it/1000.0)*90.0 }

        addHeader("MASTER ZOOM")
        createSlider("MZ_BAS", "BASE", 1000, 250) { renderer.mZoomBase = 0.1 + ((it/1000.0).pow(2.0)*9.9) }
        createSlider("MZ_RAT", "RATE", 1000, 0) { renderer.mZoomFreq = (it/1000.0)*0.5 }
        createSlider("MZ_AMP", "AMP", 1000, 0) { renderer.mZoomAmp = (it/1000.0)*0.5 }

        addHeader("CHANNEL ROTATION")
        createSlider("LR_SPD", "SPEED", 1000, 500) { renderer.lRotSpd = ((it-500)/500.0).pow(3.0)*1.5 }
        createSlider("LR_RAT", "RATE", 1000, 0) { renderer.lRotFreq = (it/1000.0)*0.5 }
        createSlider("LR_AMP", "AMP", 1000, 0) { renderer.lRotAmp = (it/1000.0)*45.0 }

        addHeader("CHANNEL ZOOM")
        createSlider("LZ_BAS", "BASE", 1000, 250) { renderer.lZoomBase = 0.1 + ((it/1000.0).pow(2.0)*9.9) }
        createSlider("LZ_RAT", "RATE", 1000, 0) { renderer.lZoomFreq = (it/1000.0)*0.5 }
        createSlider("LZ_AMP", "AMP", 1000, 0) { renderer.lZoomAmp = (it/1000.0)*0.5 }

        // --- SIDEBAR ---
        val topBtnContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        fun createSideBtn(action: () -> Unit) = ImageButton(this).apply {
            setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.3f
            layoutParams = LinearLayout.LayoutParams(120, 120)
            setOnClickListener { action(); updateSidebarVisuals() }
        }

        fun textToIcon(text: String): BitmapDrawable {
            val b = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val p = Paint().apply { color = Color.WHITE; textSize = 60f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
            c.drawText(text, 60f, 80f, p)
            return BitmapDrawable(resources, b)
        }

        val switchBtn = createSideBtn {
            currentSelector = if (currentSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            startCamera()
        }.apply { setImageResource(android.R.drawable.ic_menu_camera) }

        flipXBtn = createSideBtn { renderer.flipX = if(renderer.flipX == 1f) -1f else 1f }.apply { setImageDrawable(textToIcon("↔")) }
        flipYBtn = createSideBtn { renderer.flipY = if(renderer.flipY == 1f) -1f else 1f }.apply { setImageDrawable(textToIcon("↕")) }
        rot180Btn = createSideBtn { renderer.rot180 = !renderer.rot180 }.apply { setImageResource(android.R.drawable.ic_menu_rotate) }

        topBtnContainer.addView(switchBtn); topBtnContainer.addView(flipXBtn); topBtnContainer.addView(flipYBtn); topBtnContainer.addView(rot180Btn)

        val resetBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); alpha = 0.3f
            setOnClickListener { globalReset() }
        }

        hudContainer.addView(scrollView)
        hudContainer.addView(topBtnContainer, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.END; topMargin = 40; rightMargin = 40 })
        hudContainer.addView(resetBtn, FrameLayout.LayoutParams(140, 140).apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = 40; rightMargin = 40 })

        addContentView(hudContainer, ViewGroup.LayoutParams(-1, -1))
        updateSidebarVisuals()
    }

    private fun updateSidebarVisuals() {
        flipXBtn.alpha = if (renderer.flipX == -1f) 1.0f else 0.3f
        flipYBtn.alpha = if (renderer.flipY == -1f) 1.0f else 0.3f
        rot180Btn.alpha = if (renderer.rot180) 1.0f else 0.3f
    }

    private fun globalReset() {
        sliders["AXIS"]?.progress = 3 // Defaults to 4
        sliders["RGB_SHT"]?.progress = 0; sliders["HUE_SHT"]?.progress = 0; sliders["SOLAR"]?.progress = 0; sliders["BLOOM"]?.progress = 0
        sliders["CON_AMT"]?.progress = 500; sliders["SAT_AMT"]?.progress = 500
        sliders["MR_SPD"]?.progress = 500; sliders["MR_RAT"]?.progress = 0; sliders["MR_AMP"]?.progress = 0
        sliders["MZ_BAS"]?.progress = 250; sliders["MZ_RAT"]?.progress = 0; sliders["MZ_AMP"]?.progress = 0
        sliders["LR_SPD"]?.progress = 500; sliders["LR_RAT"]?.progress = 0; sliders["LR_AMP"]?.progress = 0
        sliders["LZ_BAS"]?.progress = 250; sliders["LZ_RAT"]?.progress = 0; sliders["LZ_AMP"]?.progress = 0
        renderer.mAngle = 0.0; renderer.lAngle = 0.0
        renderer.flipX = 1f; renderer.flipY = -1f; renderer.rot180 = false
        updateSidebarVisuals()
    }

    private fun startCamera() {
        val cp = ProcessCameraProvider.getInstance(this)
        cp.addListener({
            val provider = cp.get()
            val preview = Preview.Builder().build().apply { setSurfaceProvider { renderer.provideSurface(it) } }
            try { provider.unbindAll(); provider.bindToLifecycle(this, currentSelector, preview) } catch (e: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    inner class KaleidoscopeRenderer : GLSurfaceView.Renderer {
        private var program = 0; private var texID = -1; private var surfaceTexture: SurfaceTexture? = null
        private lateinit var pBuf: FloatBuffer; private lateinit var tBuf: FloatBuffer
        private var uMRLoc = -1; private var uLRLoc = -1; private var uALoc = -1; private var uMZLoc = -1; private var uLZLoc = -1; private var uAxLoc = -1
        private var uFLoc = -1; private var uTexLoc = -1; private var uCLoc = -1; private var uSLoc = -1; private var uHueLoc = -1; private var uSolLoc = -1; private var uBloomLoc = -1; private var uRGBLoc = -1

        var axisCount = 4.0f // Defaults to 4 axes
        var mZoomBase = 1.0; var mZoomAmp = 0.0; var mZoomFreq = 0.0
        var lZoomBase = 1.0; var lZoomAmp = 0.0; var lZoomFreq = 0.0
        var mRotSpd = 0.0; var mAngle = 0.0; var mRotAmp = 0.0; var mRotFreq = 0.0
        var lRotSpd = 0.0; var lAngle = 0.0; var lRotAmp = 0.0; var lRotFreq = 0.0

        var flipX = 1.0f; var flipY = -1.0f; var rot180 = false
        var hueShift = 0.0f; var solarize = 0.0f; var bloom = 0.0f; var rgbShift = 0.0f
        var contrast = 1.0f; var saturation = 1.0f

        private var mRotPh = 0.0; private var lRotPh = 0.0; private var mZPh = 0.0; private var lZPh = 0.0
        private var aspect = 1.0f; private var lastTime = System.nanoTime()

        fun provideSurface(req: SurfaceRequest) {
            glView.queueEvent {
                surfaceTexture?.setDefaultBufferSize(req.resolution.width, req.resolution.height)
                val s = android.view.Surface(surfaceTexture)
                req.provideSurface(s, ContextCompat.getMainExecutor(this@MainActivity)) { s.release() }
            }
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val vSrc = "attribute vec4 p; attribute vec2 t; varying vec2 v; void main(){ gl_Position=p; v=t; }"

            val fSrc = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v;
            uniform samplerExternalOES uTex;
            uniform float uMR, uLR, uA, uMZ, uLZ, uAx, uC, uS, uHue, uSol, uBloom, uRGB;
            uniform vec2 uF; 
        
            mat2 rot(float d) { 
                float a = radians(d); 
                float s = sin(a), c = cos(a); 
                return mat2(c, -s, s, c); 
            }
        
            vec3 hueShift(vec3 color, float hue) {
                const vec3 k = vec3(0.57735, 0.57735, 0.57735);
                float cosAngle = cos(hue * 6.283185);
                return vec3(color * cosAngle + cross(k, color) * sin(hue * 6.283185) + k * dot(k, color) * (1.0 - cosAngle));
            }
        
            void main() {
                // 1. Center and aspect correction
                vec2 uv = (v - 0.5); 
                uv.x *= uA;
        
                // 2. Master Zoom & Rotation
                uv *= uMZ; 
                uv = rot(uMR) * uv;
        
                // 3. Kaleidoscope Symmetry
                if(uAx > 1.1) {
                    float r = length(uv);
                    float a = atan(uv.y, uv.x);
                    float slice = 6.283185 / uAx;
                    
                    // This creates the "pie slice"
                    a = mod(a, slice); 
                    // This mirrors every other slice for seamless symmetry
                    if(mod(uAx, 2.0) < 0.1) a = abs(a - slice / 2.0);
                    
                    uv = vec2(cos(a), sin(a)) * r;
                }
        
                // 4. Local (Channel) Zoom & Rotation
                uv *= uLZ; 
                uv = rot(uLR) * uv;
                uv.x /= uA;
        
                // 5. Final Wrap & Flip Application
                // We use fract to tile the image, then apply uF to the result
                vec2 finalUV;
                if(uAx > 1.1) {
                    finalUV = abs(fract(uv - 0.5) * 2.0 - 1.0);
                } else {
                    finalUV = fract(uv + 0.5);
                }
        
                // --- THE FIX: APPLY FLIP TO FINAL SAMPLING COORDINATES ---
                if(uF.x < 0.0) finalUV.x = 1.0 - finalUV.x;
                if(uF.y > 0.0) finalUV.y = 1.0 - finalUV.y; 
        
                // 6. Sampling with RGB Shift
                vec3 color;
                if (uRGB > 0.001) {
                    color.r = texture2D(uTex, finalUV + vec2(uRGB, 0.0)).r;
                    color.g = texture2D(uTex, finalUV).g;
                    color.b = texture2D(uTex, finalUV - vec2(uRGB, 0.0)).b;
                } else {
                    color = texture2D(uTex, finalUV).rgb;
                }
        
                // 7. Post-processing (Hue, Contrast, Saturation, etc.)
                if (uSol > 0.01) color = mix(color, abs(color - uSol), step(0.1, uSol));
                if (uHue > 0.001) color = hueShift(color, uHue);
                color = (color - 0.5) * uC + 0.5;
                float luma = dot(color, vec3(0.299, 0.587, 0.114));
                color = mix(vec3(luma), color, uS);
                if (uBloom > 0.01) color += smoothstep(0.6, 1.0, luma) * color * uBloom * 3.0;
        
                gl_FragColor = vec4(color, 1.0);
            }
        """.trimIndent()
            program = createProgram(vSrc, fSrc)
            uMRLoc = GLES20.glGetUniformLocation(program, "uMR")
            uLRLoc = GLES20.glGetUniformLocation(program, "uLR")
            uALoc = GLES20.glGetUniformLocation(program, "uA")
            uMZLoc = GLES20.glGetUniformLocation(program, "uMZ")
            uLZLoc = GLES20.glGetUniformLocation(program, "uLZ")
            uAxLoc = GLES20.glGetUniformLocation(program, "uAx")
            uFLoc = GLES20.glGetUniformLocation(program, "uF")
            uCLoc = GLES20.glGetUniformLocation(program, "uC")
            uSLoc = GLES20.glGetUniformLocation(program, "uS")
            uHueLoc = GLES20.glGetUniformLocation(program, "uHue")
            uSolLoc = GLES20.glGetUniformLocation(program, "uSol")
            uBloomLoc = GLES20.glGetUniformLocation(program, "uBloom")
            uRGBLoc = GLES20.glGetUniformLocation(program, "uRGB")
            uTexLoc = GLES20.glGetUniformLocation(program, "uTex")
            texID = createOESTex()
            surfaceTexture = SurfaceTexture(texID)
            pBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)).position(0) }
            tBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(0f,0f, 1f,0f, 0f,1f, 1f,1f)).position(0) }
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            aspect = w.toFloat() / h.toFloat(); GLES20.glViewport(0, 0, w, h)
        }

        override fun onDrawFrame(gl: GL10?) {
            val now = System.nanoTime(); val d = (now - lastTime) / 1e9; lastTime = now; val tpi = 2.0 * PI
            mRotPh += mRotFreq * d * tpi; lRotPh += lRotFreq * d * tpi; mZPh += mZoomFreq * d * tpi; lZPh += lZoomFreq * d * tpi
            mAngle = (mAngle + mRotSpd * 120.0 * d) % 360.0; lAngle = (lAngle + lRotSpd * 120.0 * d) % 360.0
            val fMR = (mAngle + sin(mRotPh) * mRotAmp).toFloat() + (if(rot180) 180f else 0f)
            surfaceTexture?.updateTexImage()
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); GLES20.glUseProgram(program)
            GLES20.glUniform1f(uMRLoc, fMR)
            GLES20.glUniform1f(uLRLoc, (lAngle + sin(lRotPh) * lRotAmp).toFloat())
            GLES20.glUniform1f(uALoc, aspect)
            GLES20.glUniform1f(uMZLoc, (mZoomBase + sin(mZPh) * mZoomAmp * mZoomBase).toFloat())
            GLES20.glUniform1f(uLZLoc, (lZoomBase + sin(lZPh) * lZoomAmp * lZoomBase).toFloat())
            GLES20.glUniform1f(uAxLoc, axisCount)
            GLES20.glUniform2f(uFLoc, flipX, flipY)
            GLES20.glUniform1f(uCLoc, contrast); GLES20.glUniform1f(uSLoc, saturation)
            GLES20.glUniform1f(uHueLoc, hueShift); GLES20.glUniform1f(uSolLoc, solarize)
            GLES20.glUniform1f(uBloomLoc, bloom); GLES20.glUniform1f(uRGBLoc, rgbShift)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texID); GLES20.glUniform1i(uTexLoc, 0)
            val pL = GLES20.glGetAttribLocation(program, "p"); val tL = GLES20.glGetAttribLocation(program, "t")
            GLES20.glEnableVertexAttribArray(pL); GLES20.glVertexAttribPointer(pL, 2, GLES20.GL_FLOAT, false, 0, pBuf)
            GLES20.glEnableVertexAttribArray(tL); GLES20.glVertexAttribPointer(tL, 2, GLES20.GL_FLOAT, false, 0, tBuf)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun createOESTex(): Int {
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            return t[0]
        }
        private fun createProgram(v: String, f: String): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, v); val fs = compile(GLES20.GL_FRAGMENT_SHADER, f)
            return GLES20.glCreateProgram().apply { GLES20.glAttachShader(this, vs); GLES20.glAttachShader(this, fs); GLES20.glLinkProgram(this) }
        }
        private fun compile(t: Int, s: String): Int = GLES20.glCreateShader(t).apply { GLES20.glShaderSource(this, s); GLES20.glCompileShader(this) }
    }
}