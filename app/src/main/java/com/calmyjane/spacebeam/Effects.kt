package com.calmyjane.spacebeam

import android.graphics.*
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

// ShaderEffect base class
abstract class ShaderEffect(
    val id: String,
    val name: String,
    val mainActivity: MainActivity
) {
    val controls = mutableListOf<PropertyControl>()
    var active: Boolean = true

    abstract fun init()
    abstract fun release()
    abstract fun render(inputTexId: Int, outputFbo: Int, width: Int, height: Int)

    open fun update(deltaTime: Float) {}

    open fun reset() {}

    protected fun addControl(control: PropertyControl) {
        controls.add(control)
        mainActivity.controlsMap[control.id] = control
        mainActivity.controls.add(control)
    }
}

// MaskManager
class MaskManager {
    data class Node(var x: Float, var y: Float)  // Normalized 0..1

    var nodes = mutableListOf<Node>()
    var smoothness: Float = 0.005f  // Fraction of image width for edge softness
    var enabled: Boolean = false
    var maskTexId: Int = 0
    private var maskProgram: Int = 0
    private var locMaskTex: Int = -1
    private var locMaskMask: Int = -1
    private var locMaskMVP: Int = -1

    fun initDefaults() {
        nodes.clear()
        nodes.add(Node(0f, 0f))       // top-left
        nodes.add(Node(0.5f, 0f))     // top-center
        nodes.add(Node(1f, 0f))       // top-right
        nodes.add(Node(1f, 0.5f))     // right-center
        nodes.add(Node(1f, 1f))       // bottom-right
        nodes.add(Node(0.5f, 1f))     // bottom-center
        nodes.add(Node(0f, 1f))       // bottom-left
        nodes.add(Node(0f, 0.5f))     // left-center
    }

    fun initShader() {
        if (maskProgram != 0) return
        val vSrc = "attribute vec4 p; attribute vec2 t; varying vec2 v; uniform mat4 uMVPMatrix; void main() { gl_Position = uMVPMatrix * p; v = t; }"
        val fSrc = """
            precision mediump float;
            varying vec2 v;
            uniform sampler2D uTex;
            uniform sampler2D uMask;
            void main() {
                vec4 col = texture2D(uTex, v);
                float m = texture2D(uMask, v).r;
                gl_FragColor = col * m;
            }
        """.trimIndent()
        maskProgram = ShaderHelper.createProgram(vSrc, fSrc)
        locMaskTex = GLES20.glGetUniformLocation(maskProgram, "uTex")
        locMaskMask = GLES20.glGetUniformLocation(maskProgram, "uMask")
        locMaskMVP = GLES20.glGetUniformLocation(maskProgram, "uMVPMatrix")
    }

    /** Fast preview regeneration (low-res, for live dragging) */
    fun requestRegenerate(w: Int, h: Int) {
        val myGen = ++genCounter
        val curSmooth = smoothness
        val n = nodes.size
        val nxArr = FloatArray(n) { nodes[it].x }
        val nyArr = FloatArray(n) { nodes[it].y }
        maskExecutor.execute {
            val bmp = computeMaskBitmap(w, h, curSmooth, nxArr, nyArr, n, myGen, 120)
            if (bmp != null) {
                pendingMaskBitmap?.recycle()
                pendingMaskBitmap = bmp
            }
        }
    }

    /** High-quality regeneration (hi-res, called on release/settle) */
    fun requestHiResRegenerate(w: Int, h: Int) {
        val myGen = ++genCounter
        val curSmooth = smoothness
        val n = nodes.size
        val nxArr = FloatArray(n) { nodes[it].x }
        val nyArr = FloatArray(n) { nodes[it].y }
        maskExecutor.execute {
            val bmp = computeMaskBitmap(w, h, curSmooth, nxArr, nyArr, n, myGen, 480)
            if (bmp != null) {
                pendingMaskBitmap?.recycle()
                pendingMaskBitmap = bmp
            }
        }
    }

    /** CPU bitmap generation — runs on background thread */
    private fun computeMaskBitmap(w: Int, h: Int, smooth: Float,
                                  nx: FloatArray, ny: FloatArray, n: Int, gen: Int,
                                  targetRes: Int): Bitmap? {
        if (n < 3) return null

        val fadePixels = (smooth * w).coerceIn(0f, w * 0.5f)

        if (fadePixels < 1f) {
            // No smoothing — sharp polygon, fast path
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLACK)
            val path = Path()
            path.moveTo(nx[0] * w, (1f - ny[0]) * h)
            for (i in 1 until n) path.lineTo(nx[i] * w, (1f - ny[i]) * h)
            path.close()
            Canvas(bitmap).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; style = Paint.Style.FILL
            })
            return bitmap
        }

        // Distance-field at target resolution
        val scale = max(1, w / targetRes)
        val sw = w / scale
        val sh = h / scale
        val scaledFade = fadePixels / scale
        val fadeSq = scaledFade * scaledFade

        // Pre-compute edge data in scaled coords
        val ex1 = FloatArray(n); val ey1 = FloatArray(n)
        val edx = FloatArray(n); val edy = FloatArray(n); val elenSq = FloatArray(n)
        for (i in 0 until n) {
            val j = (i + 1) % n
            ex1[i] = nx[i] * sw; ey1[i] = (1f - ny[i]) * sh
            val x2 = nx[j] * sw; val y2 = (1f - ny[j]) * sh
            edx[i] = x2 - ex1[i]; edy[i] = y2 - ey1[i]
            elenSq[i] = edx[i] * edx[i] + edy[i] * edy[i]
        }

        // Pre-compute edge Y data for ray-casting point-in-polygon test
        val ey2 = FloatArray(n)
        val ex2 = FloatArray(n)
        for (i in 0 until n) {
            val j = (i + 1) % n
            ex2[i] = nx[j] * sw; ey2[i] = (1f - ny[j]) * sh
        }

        val resultPx = IntArray(sw * sh)
        val black = Color.BLACK
        for (py in 0 until sh) {
            if (gen != genCounter) return null
            val fpy = py + 0.5f
            for (px in 0 until sw) {
                val fpx = px + 0.5f

                // Ray-casting point-in-polygon (no bitmap needed)
                var inside = false
                for (i in 0 until n) {
                    val yi = ey1[i]; val yj = ey2[i]
                    if ((yi > fpy) != (yj > fpy)) {
                        val xi = ex1[i]; val xj = ex2[i]
                        if (fpx < xi + (fpy - yi) / (yj - yi) * (xj - xi)) {
                            inside = !inside
                        }
                    }
                }

                if (!inside) {
                    resultPx[py * sw + px] = black
                } else {
                    // Squared distance to nearest edge (no sqrt until final value)
                    var minDSq = fadeSq // clamp: anything >= fadeSq maps to 1.0
                    for (e in 0 until n) {
                        val rpx = fpx - ex1[e]; val rpy = fpy - ey1[e]
                        val len2 = elenSq[e]
                        val dSq: Float
                        if (len2 == 0f) {
                            dSq = rpx * rpx + rpy * rpy
                        } else {
                            val t = ((rpx * edx[e] + rpy * edy[e]) / len2).coerceIn(0f, 1f)
                            val cx = rpx - t * edx[e]; val cy = rpy - t * edy[e]
                            dSq = cx * cx + cy * cy
                        }
                        if (dSq < minDSq) minDSq = dSq
                    }
                    // Only one sqrt for the winning distance
                    val v = (sqrt(minDSq) / scaledFade).coerceIn(0f, 1f)
                    val b = (v * 255f).toInt()
                    resultPx[py * sw + px] = (0xFF shl 24) or (b shl 16) or (b shl 8) or b
                }
            }
        }

        val maskBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        maskBmp.setPixels(resultPx, 0, sw, 0, 0, sw, sh)
        // Scale up to full size with bilinear filtering
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        Canvas(bitmap).drawBitmap(maskBmp, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        maskBmp.recycle()
        return bitmap
    }

    /** Upload pending bitmap to GL texture — call on GL thread */
    fun uploadPendingMask() {
        val bmp = pendingMaskBitmap ?: return
        pendingMaskBitmap = null

        if (maskTexId == 0) {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            maskTexId = tex[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
    }

    /** Draw the final texture with mask applied */
    fun drawMasked(inputTex: Int, mvpMatrix: FloatArray) {
        if (maskProgram == 0) return
        GLES20.glUseProgram(maskProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTex)
        GLES20.glUniform1i(locMaskTex, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexId)
        GLES20.glUniform1i(locMaskMask, 1)

        GLES20.glUniformMatrix4fv(locMaskMVP, 1, false, mvpMatrix, 0)

        ShaderHelper.bindQuad(maskProgram)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Reset active texture to 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    @Volatile var needsRegenerate = false
    @Volatile var needsHiResRegenerate = false
    @Volatile var pendingMaskBitmap: Bitmap? = null
    private val maskExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var genCounter = 0

    fun saveToPrefs(prefs: android.content.SharedPreferences) {
        val nodesStr = nodes.joinToString(";") { "${it.x},${it.y}" }
        prefs.edit()
            .putString("MASK_NODES", nodesStr)
            .putFloat("MASK_SMOOTH", smoothness)
            .putBoolean("MASK_ENABLED", enabled)
            .apply()
    }

    fun loadFromPrefs(prefs: android.content.SharedPreferences) {
        val nodesStr = prefs.getString("MASK_NODES", null)
        if (nodesStr != null && nodesStr.isNotEmpty()) {
            nodes.clear()
            nodesStr.split(";").forEach { pair ->
                val parts = pair.split(",")
                if (parts.size == 2) {
                    val x = parts[0].toFloatOrNull() ?: return@forEach
                    val y = parts[1].toFloatOrNull() ?: return@forEach
                    nodes.add(Node(x, y))
                }
            }
        } else {
            initDefaults()
        }
        smoothness = prefs.getFloat("MASK_SMOOTH", 0.005f)
        enabled = prefs.getBoolean("MASK_ENABLED", false)
        if (enabled) { needsRegenerate = true; needsHiResRegenerate = true }
    }
}


// EffectChain
class EffectChain {
    val effects = mutableListOf<ShaderEffect>()
    private var fboA = 0
    private var texA = 0
    private var fboB = 0
    private var texB = 0
    private var width = 0
    private var height = 0
    private var isReady = false

    // Simple copy shader for feedback capture
    private var copyProg = 0
    private var copyLocTex = -1

    // Injection blend shader: composites one source onto the chain result
    private var injectProg = 0
    private var locInjectBase = -1; private var locInjectSrc = -1
    private var locInjectMix = -1; private var locInjectMode = -1

    fun init(w: Int, h: Int) {
        if (isReady && width == w && height == h) return
        width = w; height = h
        release() // Re-create if size changed

        fun createFBO(): Pair<Int, Int> {
            val f = IntArray(1); val t = IntArray(1)
            GLES20.glGenFramebuffers(1, f, 0); GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0)
            return Pair(f[0], t[0])
        }

        val a = createFBO(); fboA = a.first; texA = a.second
        val b = createFBO(); fboB = b.first; texB = b.second

        // Simple copy shader for feedback capture
        if (copyProg == 0) {
            val vSrc = "attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }"
            val fSrc = "precision mediump float; varying vec2 v; uniform sampler2D uTex; void main() { gl_FragColor = texture2D(uTex, v); }"
            copyProg = ShaderHelper.createProgram(vSrc, fSrc)
            copyLocTex = GLES20.glGetUniformLocation(copyProg, "uTex")
        }

        // Injection blend shader: composites one source onto the chain result using blend mode
        if (injectProg == 0) {
            val vSrc = "attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }"
            val fSrc = """
            precision mediump float; varying vec2 v;
            uniform sampler2D uBase; uniform sampler2D uSrc;
            uniform float uMix; uniform int uMode;
            vec3 blendOp(vec3 a, vec3 b, int mode) {
                if (mode == 1) return vec3(1.0) - (vec3(1.0) - a) * (vec3(1.0) - b);
                if (mode == 2) return a * b;
                if (mode == 3) return abs(a - b);
                if (mode == 4) return vec3(
                    a.r < 0.5 ? 2.0*a.r*b.r : 1.0 - 2.0*(1.0-a.r)*(1.0-b.r),
                    a.g < 0.5 ? 2.0*a.g*b.g : 1.0 - 2.0*(1.0-a.g)*(1.0-b.g),
                    a.b < 0.5 ? 2.0*a.b*b.b : 1.0 - 2.0*(1.0-a.b)*(1.0-b.b));
                if (mode == 5) return max(a, b);
                if (mode == 6) return min(a, b);
                if (mode == 7) return a - b;
                return a + b;
            }
            void main() {
                vec4 base = texture2D(uBase, v);
                vec3 src = texture2D(uSrc, v).rgb;
                base.rgb = mix(base.rgb, blendOp(base.rgb, src, uMode), uMix);
                gl_FragColor = clamp(base, 0.0, 1.0);
            }"""
            injectProg = ShaderHelper.createProgram(vSrc, fSrc)
            locInjectBase = GLES20.glGetUniformLocation(injectProg, "uBase")
            locInjectSrc = GLES20.glGetUniformLocation(injectProg, "uSrc")
            locInjectMix = GLES20.glGetUniformLocation(injectProg, "uMix")
            locInjectMode = GLES20.glGetUniformLocation(injectProg, "uMode")
        }

        isReady = true
        effects.forEach { it.init() }
    }

    fun process(renderer: KaleidoscopeRenderer): Int {
        if (!isReady || effects.isEmpty()) return 0

        val sources = renderer.sources

        effects[0].render(0, fboA, width, height)

        var currentInput = texA
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentInput)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)

        captureForSources(sources, effects[0].id, currentInput)
        currentInput = injectSourcesAfter(sources, effects[0].id, currentInput, renderer)

        for (i in 1 until effects.size) {
            val effect = effects[i]
            if (effect.active) {
                val outputFbo = if (currentInput == texA) fboB else fboA
                val outputTex = if (currentInput == texA) texB else texA
                effect.render(currentInput, outputFbo, width, height)

                currentInput = outputTex
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentInput)
                GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)

                captureForSources(sources, effect.id, currentInput)
                currentInput = injectSourcesAfter(sources, effect.id, currentInput, renderer)
            }
        }
        return currentInput
    }

    private fun captureForSources(sources: List<KaleidoscopeRenderer.SourceChannel>, effectId: String, srcTex: Int) {
        for (src in sources) {
            if (src.type != SourceType.FEEDBACK || src.feedbackTapEffectId != effectId) continue
            src.initFeedbackBuffer(width, height)
            src.writeFeedbackSlot(srcTex, copyProg, copyLocTex)
        }
    }

    // Inject sources that target this effect's output, compositing them onto the current chain result
    private fun injectSourcesAfter(
        sources: List<KaleidoscopeRenderer.SourceChannel>,
        effectId: String, inputTex: Int,
        renderer: KaleidoscopeRenderer
    ): Int {
        var readTex = inputTex
        for (src in sources) {
            if (src.injectionPoint == "FX_MIXER" || src.injectionPoint != effectId) continue
            val mixVal = renderer.ctx.controlsMap[src.id]?.computedValue ?: 0f
            if (mixVal <= 0f) continue
            val writeFbo = if (readTex == texA) fboB else fboA
            val writeTex = if (readTex == texA) texB else texA
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, writeFbo)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(injectProg)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, readTex)
            GLES20.glUniform1i(locInjectBase, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src.fboTexId)
            GLES20.glUniform1i(locInjectSrc, 1)
            GLES20.glUniform1f(locInjectMix, mixVal)
            GLES20.glUniform1i(locInjectMode, src.blendMode.ordinal)
            ShaderHelper.bindQuad(injectProg)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            readTex = writeTex
        }
        return readTex
    }

    fun release() {
        if (fboA != 0) { val f = IntArray(2){ if(it==0) fboA else fboB }; val t = IntArray(2){ if(it==0) texA else texB }; GLES20.glDeleteFramebuffers(2, f, 0); GLES20.glDeleteTextures(2, t, 0) }
        if (copyProg != 0) { GLES20.glDeleteProgram(copyProg); copyProg = 0 }
        if (injectProg != 0) { GLES20.glDeleteProgram(injectProg); injectProg = 0 }
        fboA = 0; isReady = false
        effects.forEach { it.release() }
    }
}

// SHADERS

// Effect implementations
class MixerEffect(val activity: MainActivity) : ShaderEffect("FX_MIXER", "MIXER", activity) {
    private var prog = 0
    private var locCount = -1
    private val locTex = IntArray(8)
    private val locMix = IntArray(8)
    private val locMode = IntArray(8)
    override fun init() {
        val fSrc = """
        precision mediump float; varying vec2 v;
        uniform sampler2D uTex[8]; uniform float uMix[8]; uniform int uMode[8]; uniform int uCount;
        vec3 blendOp(vec3 a, vec3 b, int mode) {
            if (mode == 1) return vec3(1.0) - (vec3(1.0) - a) * (vec3(1.0) - b);
            if (mode == 2) return a * b;
            if (mode == 3) return abs(a - b);
            if (mode == 4) return vec3(
                a.r < 0.5 ? 2.0*a.r*b.r : 1.0 - 2.0*(1.0-a.r)*(1.0-b.r),
                a.g < 0.5 ? 2.0*a.g*b.g : 1.0 - 2.0*(1.0-a.g)*(1.0-b.g),
                a.b < 0.5 ? 2.0*a.b*b.b : 1.0 - 2.0*(1.0-a.b)*(1.0-b.b));
            if (mode == 5) return max(a, b);
            if (mode == 6) return min(a, b);
            if (mode == 7) return a - b;
            return a + b;
        }
        void main() {
            vec4 r = vec4(0.0);
            if (uCount > 0 && uMix[0] > 0.0) r = texture2D(uTex[0], v) * uMix[0];
            if (uCount > 1 && uMix[1] > 0.0) { vec3 t = texture2D(uTex[1], v).rgb; r.rgb = mix(r.rgb, blendOp(r.rgb, t, uMode[1]), uMix[1]); }
            if (uCount > 2 && uMix[2] > 0.0) { vec3 t = texture2D(uTex[2], v).rgb; r.rgb = mix(r.rgb, blendOp(r.rgb, t, uMode[2]), uMix[2]); }
            if (uCount > 3 && uMix[3] > 0.0) { vec3 t = texture2D(uTex[3], v).rgb; r.rgb = mix(r.rgb, blendOp(r.rgb, t, uMode[3]), uMix[3]); }
            if (uCount > 4 && uMix[4] > 0.0) { vec3 t = texture2D(uTex[4], v).rgb; r.rgb = mix(r.rgb, blendOp(r.rgb, t, uMode[4]), uMix[4]); }
            if (uCount > 5 && uMix[5] > 0.0) { vec3 t = texture2D(uTex[5], v).rgb; r.rgb = mix(r.rgb, blendOp(r.rgb, t, uMode[5]), uMix[5]); }
            if (uCount > 6 && uMix[6] > 0.0) { vec3 t = texture2D(uTex[6], v).rgb; r.rgb = mix(r.rgb, blendOp(r.rgb, t, uMode[6]), uMix[6]); }
            if (uCount > 7 && uMix[7] > 0.0) { vec3 t = texture2D(uTex[7], v).rgb; r.rgb = mix(r.rgb, blendOp(r.rgb, t, uMode[7]), uMix[7]); }
            gl_FragColor = clamp(r, 0.0, 1.0);
        }"""
        prog = ShaderHelper.createProgram("attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }", fSrc)
        locCount = GLES20.glGetUniformLocation(prog, "uCount")
        for (i in 0 until 8) {
            locTex[i] = GLES20.glGetUniformLocation(prog, "uTex[$i]")
            locMix[i] = GLES20.glGetUniformLocation(prog, "uMix[$i]")
            locMode[i] = GLES20.glGetUniformLocation(prog, "uMode[$i]")
        }
    }

    override fun render(inputTexId: Int, outputFbo: Int, w: Int, h: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo); GLES20.glViewport(0, 0, w, h); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(prog)

        // Only include sources that target the mixer (FX_MIXER), no allocation
        var cnt = 0
        for (src in activity.renderer.sources) {
            if (src.injectionPoint != "FX_MIXER" || cnt >= 8) continue
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + cnt); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src.fboTexId)
            GLES20.glUniform1i(locTex[cnt], cnt)
            val v = activity.controlsMap[src.id]?.computedValue ?: 0f
            GLES20.glUniform1f(locMix[cnt], v)
            GLES20.glUniform1i(locMode[cnt], src.blendMode.ordinal)
            cnt++
        }
        GLES20.glUniform1i(locCount, cnt)
        ShaderHelper.bindQuad(prog); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
    override fun release() { GLES20.glDeleteProgram(prog) }
}

class TransformEffect(idPrefix: String, title: String, activity: MainActivity) : ShaderEffect(idPrefix, title, activity) {
    private var prog = 0
    private var locTex = -1; private var locRatio = -1; private var locZ = -1; private var locR = -1
    private var locTx = -1; private var locTy = -1; private var locTiX = -1; private var locTiY = -1
    private var locWarp = -1; private var locRGB = -1; private var locBend = -1; private var locWobble = -1
    private val pZoom = "${idPrefix}_ZOOM"
    private val pAngle = "${idPrefix}_ANGLE"
    private val pTx = "${idPrefix}_TX"
    private val pTy = "${idPrefix}_TY"
    private val pTiltX = "${idPrefix}_TILTX"
    private val pTiltY = "${idPrefix}_TILTY"
    private val pRgb = "${idPrefix}_RGB"
    private val pBend = "${idPrefix}_BEND"
    private val pWobble = "${idPrefix}_WOBBLE"

    init {
        addControl(PropertyControl(pAngle, "ANGLE", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true, modMode = PropertyControl.ModMode.WRAP))
        addControl(PropertyControl(pZoom, "ZOOM", defaultValue = 700, outMin=0.05f, outMax=2.0f, hasModulation = true, logPower = 2))
        addControl(PropertyControl(pTx, "MOVE X", defaultValue = 500, outMin=-1f, outMax=1f, hasModulation = true))
        addControl(PropertyControl(pTy, "MOVE Y", defaultValue = 500, outMin=-1f, outMax=1f, hasModulation = true))
        addControl(PropertyControl(pTiltX, "TILT X", defaultValue = 500, outMin=-1f, outMax=1f, hasModulation = true))
        addControl(PropertyControl(pTiltY, "TILT Y", defaultValue = 500, outMin=-1f, outMax=1f, hasModulation = true))
        addControl(PropertyControl(pBend, "BEND", defaultValue = 500, outMin=-3.0f, outMax=3.0f, hasModulation = true))
        addControl(PropertyControl(pWobble, "WOBBLE", defaultValue = 0, outMin=0f, outMax=1.0f, hasModulation = true))
        if(idPrefix == "C") addControl(PropertyControl("WARP", "DISTORT", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true))
        addControl(PropertyControl(pRgb, "RGB SHIFT", defaultValue = 0, outMin=0f, outMax=0.1f, hasModulation = true))
    }

    override fun init() {
        val fSrc = """
        precision highp float; varying vec2 v; uniform sampler2D uTex;
        uniform float uZ, uA, uR, uTx, uTy, uTiX, uTiY, uWarp, uRGB, uRatio, uBend, uWobble;

        float hash(vec2 p) {
            return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
        }
        
        float noise(vec2 p) {
            vec2 i = floor(p); vec2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
                       mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
        }

        vec2 transformUV(vec2 uv, float xOff) {
            float r2 = dot(uv, uv);
            
            float w = 0.0;
            if (uWobble > 0.001) {
                vec2 nUV = (uv * 4.0) - vec2(uTx, uTy) * 2.5;
                mat2 m = mat2(0.8, -0.6, 0.6, 0.8);
                float n = noise(nUV) + 0.5 * noise(m * nUV * 2.0);
                w = (n - 0.75) * uWobble;
            }
            
            float z = 1.0 + (uv.x * uTiX) + (uv.y * uTiY) - (uBend * r2) - w;
            uv /= max(z, 0.05);
            uv /= uZ;
            
            float af = mix(uRatio, 1.0, uWarp);
            uv.x *= af;
            float c = cos(uR); float s = sin(uR);
            uv = vec2(uv.x * c - uv.y * s, uv.x * s + uv.y * c);
            uv.x /= af;
            uv += vec2(uTx + xOff, uTy);
            
            return abs(mod(uv + 0.5, 2.0) - 1.0);
        }
        
        void main() {
            vec2 base = v - 0.5;
            if (uRGB < 0.001) {
                gl_FragColor = texture2D(uTex, transformUV(base, 0.0));
            } else {
                float r = texture2D(uTex, transformUV(base,  uRGB)).r;
                float g = texture2D(uTex, transformUV(base,  0.0 )).g;
                float b = texture2D(uTex, transformUV(base, -uRGB)).b;
                gl_FragColor = vec4(r, g, b, 1.0);
            }
        }"""
        prog = ShaderHelper.createProgram("attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }", fSrc)
        locTex = GLES20.glGetUniformLocation(prog, "uTex"); locRatio = GLES20.glGetUniformLocation(prog, "uRatio")
        locZ = GLES20.glGetUniformLocation(prog, "uZ"); locR = GLES20.glGetUniformLocation(prog, "uR")
        locTx = GLES20.glGetUniformLocation(prog, "uTx"); locTy = GLES20.glGetUniformLocation(prog, "uTy")
        locTiX = GLES20.glGetUniformLocation(prog, "uTiX"); locTiY = GLES20.glGetUniformLocation(prog, "uTiY")
        locWarp = GLES20.glGetUniformLocation(prog, "uWarp"); locRGB = GLES20.glGetUniformLocation(prog, "uRGB")
        locBend = GLES20.glGetUniformLocation(prog, "uBend"); locWobble = GLES20.glGetUniformLocation(prog, "uWobble")
    }

    override fun render(inputTexId: Int, outputFbo: Int, w: Int, h: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo); GLES20.glViewport(0, 0, w, h); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(prog); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)

        val rotAccum = if (id.startsWith("M")) mainActivity.getRendererMRot() else mainActivity.getRendererCRot()
        val angle = (mainActivity.controlsMap[pAngle]?.computedValue ?: 0f) * 360f + rotAccum
        val rawZoom = mainActivity.controlsMap[pZoom]?.computedValue ?: 1f
        val correctedZoom = rawZoom * 0.7067f

        GLES20.glUniform1i(locTex, 0)
        GLES20.glUniform1f(locRatio, w.toFloat()/h.toFloat())
        GLES20.glUniform1f(locZ, correctedZoom)
        GLES20.glUniform1f(locR, Math.toRadians(angle).toFloat())
        GLES20.glUniform1f(locTx, mainActivity.controlsMap[pTx]?.computedValue ?: 0f)
        GLES20.glUniform1f(locTy, mainActivity.controlsMap[pTy]?.computedValue ?: 0f)
        GLES20.glUniform1f(locTiX, (mainActivity.controlsMap[pTiltX]?.computedValue ?: 0f) * 1.5f)
        GLES20.glUniform1f(locTiY, (mainActivity.controlsMap[pTiltY]?.computedValue ?: 0f) * 1.5f)
        GLES20.glUniform1f(locWarp, mainActivity.controlsMap["WARP"]?.computedValue ?: 0f)
        GLES20.glUniform1f(locRGB, mainActivity.controlsMap[pRgb]?.computedValue ?: 0f)
        GLES20.glUniform1f(locBend, mainActivity.controlsMap[pBend]?.computedValue ?: 0f)
        GLES20.glUniform1f(locWobble, mainActivity.controlsMap[pWobble]?.computedValue ?: 0f)

        ShaderHelper.bindQuad(prog); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
    override fun release() { GLES20.glDeleteProgram(prog) }
}

class KaleidoscopeEffect(activity: MainActivity) : ShaderEffect("FX_KALEIDO", "KALEIDOSCOPE", activity) {
    private var prog = 0
    private var locTex = -1; private var locAx = -1; private var locAmt = -1
    private var locZoom = -1; private var locRatio = -1
    init {
        addControl(PropertyControl("AXIS", "AXIS", min=1, max=25, sliderMax=25, defaultValue=2, includeInPreset=true, defaultLocked=true, allowSmoothing=false))
        addControl(PropertyControl("K_AMT", "AMOUNT", defaultValue=1000, outMin=0f, outMax=1f, hasModulation=true))
        // Zoom starts at 0 (1.0x) and goes up to 1000 (5.0x zoom out)
        addControl(PropertyControl("K_ZOOM", "K-ZOOM", defaultValue=0, outMin=1.0f, outMax=5.0f, hasModulation=true))
    }

    override fun init() {
        val fSrc = """
        precision highp float; 
        varying vec2 v; 
        uniform sampler2D uTex;
        uniform float uAx, uAmt, uZoom, uRatio;
    
        void main() {
            // Use a slight inset to avoid the absolute edge of the texture 
            // where the 1px wrap line usually lives.
            vec2 uv = v - 0.5;
            
            float zoomAmt = mix(1.0, 2.0, uAmt);
            float shift = mix(0.5, 0.0, uAmt);
            
            uv *= uZoom;
            uv *= zoomAmt;
            
            if (uAx > 2.1) {
                vec2 rUV = uv;
                rUV.x *= uRatio;
                float r = length(rUV);
                float angle = atan(rUV.y, rUV.x);
                float slice = 6.2831853 / uAx;
                float a = mod(angle, slice);
                a = abs(a - slice * 0.5);
                rUV = vec2(cos(a), sin(a)) * r;
                rUV.x /= uRatio;
                uv = mix(uv, rUV, uAmt);
            }
            
            uv += shift;
    
            // CLEAN MIRROR: This avoids the 'mod' jump by using a continuous triangle wave
            // 0.0001 offset pushes the "seam" into a sub-pixel area
            vec2 mirroredUV = 1.0 - abs(fract(uv * 0.5 + 0.0001) * 2.0 - 1.0);
            
            // Final safety clamp to keep sampler away from the 1.0/0.0 edge
            mirroredUV = clamp(mirroredUV, 0.002, 0.998);
            
            gl_FragColor = texture2D(uTex, mirroredUV);
        }"""
        prog = ShaderHelper.createProgram("attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }", fSrc)
        locTex = GLES20.glGetUniformLocation(prog, "uTex"); locAx = GLES20.glGetUniformLocation(prog, "uAx")
        locAmt = GLES20.glGetUniformLocation(prog, "uAmt"); locZoom = GLES20.glGetUniformLocation(prog, "uZoom")
        locRatio = GLES20.glGetUniformLocation(prog, "uRatio")
    }

    override fun render(inputTexId: Int, outputFbo: Int, w: Int, h: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo); GLES20.glViewport(0, 0, w, h); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(prog); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
        GLES20.glUniform1i(locTex, 0)
        GLES20.glUniform1f(locAx, mainActivity.controlsMap["AXIS"]?.value?.toFloat() ?: 2f)
        GLES20.glUniform1f(locAmt, mainActivity.controlsMap["K_AMT"]?.computedValue ?: 1f)
        GLES20.glUniform1f(locZoom, mainActivity.controlsMap["K_ZOOM"]?.computedValue ?: 1f)
        GLES20.glUniform1f(locRatio, w.toFloat()/h.toFloat())

        ShaderHelper.bindQuad(prog); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
    override fun release() { GLES20.glDeleteProgram(prog) }
}

class TunnelEffect(activity: MainActivity) : ShaderEffect("FX_TUNNEL", "3D TUNNEL", activity) {
    private var prog = 0
    private var scrollAccum = 0.0f
    private var locTex = -1; private var locRatio = -1; private var locMix = -1; private var locShape = -1
    private var locFov = -1; private var locScroll = -1; private var locHStr = -1; private var locHPos = -1
    private var locWStr = -1; private var locWPos = -1; private var locCurve = -1; private var locTwist = -1
    private var locFlux = -1; private var locFogD = -1; private var locFogH = -1; private var locFogS = -1; private var locFogV = -1

    init {
        addControl(PropertyControl("3D_MIX", "STRENGTH", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true))

        // Shape & Speed
        addControl(PropertyControl("S_SHAPE", "SHAPE", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true))
        addControl(PropertyControl("S_FOV", "FISHEYE", defaultValue = 500, outMin=0.2f, outMax=1.5f, hasModulation = true))
        addControl(PropertyControl("S_SPEED", "SPEED", defaultValue = 500, outMin=-2.0f, outMax=2.0f, hasModulation = true))

        // Fog
        addControl(PropertyControl("T_FOG", "FOG DIST", defaultValue = 0, outMin=0.0f, outMax=0.5f, hasModulation = true))
        addControl(PropertyControl("T_FOG_H", "FOG HUE", defaultValue = 0, outMin=0.0f, outMax=1.0f, hasModulation = true, modMode=PropertyControl.ModMode.WRAP))
        addControl(PropertyControl("T_FOG_S", "FOG SAT", defaultValue = 0, outMin=0.0f, outMax=1.0f, hasModulation = true))
        addControl(PropertyControl("T_FOG_V", "FOG BRIT", defaultValue = 1000, outMin=0.0f, outMax=1.0f, hasModulation = true))

        // Color & Distortion
        addControl(PropertyControl("T_HUE_STR", "RAINBOW STR", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true))
        addControl(PropertyControl("T_HUE_POS", "RAINBOW POS", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true, modMode=PropertyControl.ModMode.WRAP))
        addControl(PropertyControl("T_WAVE_STR", "WAVE STR", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true))
        addControl(PropertyControl("T_WAVE_POS", "WAVE POS", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true, modMode=PropertyControl.ModMode.WRAP))

        // Geometry
        // Curve: 1.0 = Straight. 0.0 = Left, 2.0 = Right.
        addControl(PropertyControl("CURVE", "CURVE", defaultValue = 500, outMin=0.0f, outMax=2.0f, hasModulation = true))
        // Twist: Reduced range to 10% (was +/- 5.0, now +/- 0.5)
        addControl(PropertyControl("TWIST", "VORTEX", defaultValue = 500, outMin=-0.5f, outMax=0.5f, hasModulation = true))
        addControl(PropertyControl("FLUX", "FLUX", defaultValue = 0, outMin=0f, outMax=0.5f, hasModulation = true))
    }

    override fun reset() {
        scrollAccum = 0.0f
    }

    override fun update(deltaTime: Float) {
        val speedCtrl = mainActivity.controlsMap["S_SPEED"] ?: return
        val rawSpeed = speedCtrl.computedValue
        val sign = sign(rawSpeed)
        val curvedSpeed = sign * (abs(rawSpeed)).pow(2.2f)
        scrollAccum += curvedSpeed * deltaTime * 0.6f
    }

    override fun init() {
        val fSrc = """
        precision highp float; varying vec2 v; uniform sampler2D uTex;
        uniform float uMix, uShape, uFov, uScroll, uHStr, uHPos, uWStr, uWPos, uRatio;
        uniform float uCurve, uTwist, uFlux;
        uniform float uFogD, uFogH, uFogS, uFogV;

        vec3 hsb2rgb(vec3 c) {
            vec3 rgb = clamp(abs(mod(c.x*6.0+vec3(0.0,4.0,2.0), 6.0)-3.0)-1.0, 0.0, 1.0);
            return c.z * mix(vec3(1.0), rgb, c.y);
        }

        void main() {
            vec2 flatUV = v - 0.5; 
            vec2 uv = flatUV;
            uv.x *= uRatio;
            
            // --- 1. Curve (Parabolic view bend) ---
            // We bend the view plane BEFORE calculating tunnel geometry.
            // uCurve 1.0 = 0.0 offset.
            // x += factor * y^2 creates a parabolic bend.
            float curveFactor = (uCurve - 1.0) * 2.0; 
            uv.x += curveFactor * (uv.y * uv.y);

            // --- 2. Geometry Calculation ---
            float rC = length(uv); 
            float rB = max(abs(uv.x), abs(uv.y)); 
            float dist = mix(rC, rB, uShape);
            
            // Flux distortion
            dist += sin(atan(uv.y, uv.x) * 4.0 + dist * 10.0) * uFlux * dist; 
            float safe = sqrt(dist * dist + 0.003);
            
            // Projection (Depth)
            float proj = (uFov * 0.8 + 0.2) / safe;
            
            // --- 3. Calculate Tunnel UVs ---
            vec2 tUV; 
            float ang = atan(uv.y, uv.x);
            tUV.x = (ang + (1.0/safe) * uTwist) / 3.14159; 
            tUV.y = proj + uScroll; // Global depth coordinate
            
            // --- 4. Mix & Wrap ---
            // Wrap Coordinates BEFORE Mixing (Fixes jump glitch)
            vec2 wrappedTunnel = abs(mod(tUV + 0.5, 2.0) - 1.0);
            vec2 wrappedFlat = abs(mod(flatUV + 0.5, 2.0) - 1.0);

            vec2 finalUV = mix(wrappedFlat, wrappedTunnel, uMix * uMix);
            vec4 col = texture2D(uTex, finalUV);

            // --- 5. Color Effects (Global Movement) ---
            // Use 'tUV.y' (infinite depth) instead of 'finalUV.y' (wrapped tile)
            // This ensures rainbow/wave move DOWN the tunnel, not ON the tile.
            
            // Rainbow
            if (uHStr > 0.01) { 
                float effectiveStr = uHStr * uMix; // Fade out if flat
                float ha = (tUV.y * 0.2) + uHPos; // *0.2 controls rainbow frequency
                vec3 rb = 0.5 + 0.5 * cos(6.28 * (ha + vec3(0.0, 0.33, 0.67))); 
                col.rgb = mix(col.rgb, col.rgb * rb * 2.0, effectiveStr); 
            }
            
            // Wave
            if (uWStr > 0.01) { 
                float effectiveWStr = uWStr * uMix; // Fade out if flat
                float wd = tUV.y - (uWPos * 10.0); // Use global depth
                float dw = abs(fract(wd) - 0.5); 
                float wp = smoothstep(0.15 + effectiveWStr*0.2, 0.0, dw); 
                col.rgb += vec3(0.5, 0.8, 1.0) * wp * effectiveWStr; 
            }
            
            // --- 6. Fog ---
            if (uFogD > 0.001) {
                float depth = 1.0 / safe;
                float fogAmt = 1.0 - exp(-depth * depth * uFogD * 0.1);
                fogAmt = clamp(fogAmt, 0.0, 1.0);
                
                vec3 fogColor = hsb2rgb(vec3(uFogH, uFogS, uFogV));
                float effectiveFog = fogAmt * smoothstep(0.0, 1.0, uMix);
                col.rgb = mix(col.rgb, fogColor, effectiveFog);
            }

            gl_FragColor = col;
        }"""
        prog = ShaderHelper.createProgram("attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }", fSrc)
        locTex = GLES20.glGetUniformLocation(prog, "uTex"); locRatio = GLES20.glGetUniformLocation(prog, "uRatio")
        locMix = GLES20.glGetUniformLocation(prog, "uMix"); locShape = GLES20.glGetUniformLocation(prog, "uShape")
        locFov = GLES20.glGetUniformLocation(prog, "uFov"); locScroll = GLES20.glGetUniformLocation(prog, "uScroll")
        locHStr = GLES20.glGetUniformLocation(prog, "uHStr"); locHPos = GLES20.glGetUniformLocation(prog, "uHPos")
        locWStr = GLES20.glGetUniformLocation(prog, "uWStr"); locWPos = GLES20.glGetUniformLocation(prog, "uWPos")
        locCurve = GLES20.glGetUniformLocation(prog, "uCurve"); locTwist = GLES20.glGetUniformLocation(prog, "uTwist")
        locFlux = GLES20.glGetUniformLocation(prog, "uFlux"); locFogD = GLES20.glGetUniformLocation(prog, "uFogD")
        locFogH = GLES20.glGetUniformLocation(prog, "uFogH"); locFogS = GLES20.glGetUniformLocation(prog, "uFogS")
        locFogV = GLES20.glGetUniformLocation(prog, "uFogV")
    }

    override fun render(inputTexId: Int, outputFbo: Int, w: Int, h: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo); GLES20.glViewport(0, 0, w, h); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(prog); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
        GLES20.glUniform1i(locTex, 0)
        GLES20.glUniform1f(locRatio, w.toFloat()/h.toFloat())
        GLES20.glUniform1f(locMix, mainActivity.controlsMap["3D_MIX"]?.computedValue ?: 0f)
        GLES20.glUniform1f(locShape, mainActivity.controlsMap["S_SHAPE"]?.computedValue ?: 0f)
        GLES20.glUniform1f(locFov, mainActivity.controlsMap["S_FOV"]?.computedValue ?: 0.5f)
        GLES20.glUniform1f(locScroll, scrollAccum)

        // Color
        GLES20.glUniform1f(locHStr, mainActivity.controlsMap["T_HUE_STR"]?.computedValue ?: 0f)
        GLES20.glUniform1f(locHPos, mainActivity.controlsMap["T_HUE_POS"]?.computedValue ?: 0f)
        GLES20.glUniform1f(locWStr, mainActivity.controlsMap["T_WAVE_STR"]?.computedValue ?: 0f)
        GLES20.glUniform1f(locWPos, mainActivity.controlsMap["T_WAVE_POS"]?.computedValue ?: 0f)

        // Distort
        GLES20.glUniform1f(locCurve, mainActivity.controlsMap["CURVE"]?.computedValue ?: 1.0f)
        GLES20.glUniform1f(locTwist, mainActivity.controlsMap["TWIST"]?.computedValue ?: 0f)
        GLES20.glUniform1f(locFlux, mainActivity.controlsMap["FLUX"]?.computedValue ?: 0f)

        // Fog
        GLES20.glUniform1f(locFogD, mainActivity.controlsMap["T_FOG"]?.computedValue ?: 0f)
        GLES20.glUniform1f(locFogH, mainActivity.controlsMap["T_FOG_H"]?.computedValue ?: 0f)
        GLES20.glUniform1f(locFogS, mainActivity.controlsMap["T_FOG_S"]?.computedValue ?: 0f)
        GLES20.glUniform1f(locFogV, mainActivity.controlsMap["T_FOG_V"]?.computedValue ?: 1f)

        ShaderHelper.bindQuad(prog); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
    override fun release() { GLES20.glDeleteProgram(prog) }
}

class SwirlEffect(activity: MainActivity) : ShaderEffect("FX_SWIRL", "SWIRL", activity) {
    private var prog = 0
    private var scrollAccum = 0.0f
    private var swayAccum = 0.0f
    private var locTex = -1; private var locRatio = -1; private var locStr = -1; private var locWide = -1
    private var locScroll = -1; private var locSwayTime = -1; private var locFogD = -1; private var locFogF = -1
    private var locFogH = -1; private var locFogS = -1; private var locFogV = -1

    init {
        addControl(PropertyControl("UTWIRL", "STRENGTH", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true))

        addControl(PropertyControl("S_WIDE", "WIDENESS", defaultValue = 500, outMin=0.0f, outMax=2.0f, hasModulation = true))
        addControl(PropertyControl("S_ACTIVITY", "ACTIVITY", defaultValue = 200, outMin=0.0f, outMax=2.0f, hasModulation = true))
        addControl(PropertyControl("SWIRL_SPEED", "SPEED", defaultValue = 500, outMin=-2.0f, outMax=2.0f, hasModulation = true))

        addControl(PropertyControl("S_FOG", "FOG DIST", defaultValue = 100, outMin=0.0f, outMax=1.0f, hasModulation = true))
        addControl(PropertyControl("S_FOG_FALLOFF", "FOG SOFT", defaultValue = 150, outMin=0.0f, outMax=80.0f, hasModulation = true))
        addControl(PropertyControl("S_FOG_H", "FOG HUE", defaultValue = 0, outMin=0.0f, outMax=1.0f, hasModulation = true, modMode=PropertyControl.ModMode.WRAP))
        addControl(PropertyControl("S_FOG_S", "FOG SAT", defaultValue = 0, outMin=0.0f, outMax=1.0f, hasModulation = true))
        addControl(PropertyControl("S_FOG_V", "FOG BRIT", defaultValue = 0, outMin=0.0f, outMax=1.0f, hasModulation = true))
    }

    override fun reset() {
        scrollAccum = 0.0f
        swayAccum = 0.0f
    }

    override fun update(deltaTime: Float) {
        val speedCtrl = mainActivity.controlsMap["SWIRL_SPEED"]
        if (speedCtrl != null) {
            val rawSpeed = speedCtrl.computedValue
            val sign = sign(rawSpeed)
            val curvedSpeed = sign * (abs(rawSpeed)).pow(2.0f)
            scrollAccum -= curvedSpeed * deltaTime * 2.0f
        }

        val actCtrl = mainActivity.controlsMap["S_ACTIVITY"]
        if (actCtrl != null) {
            swayAccum += actCtrl.computedValue * deltaTime
        }
    }

    override fun init() {
        val fSrc = """
        precision highp float; varying vec2 v; uniform sampler2D uTex;
        uniform float uStr, uWide, uScroll, uSwayTime, uRatio;
        uniform float uFogD, uFogF, uFogH, uFogS, uFogV;

        #define PI 3.14159
        #define FAR 80.0
        
        mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }
        mat3 lookAt(vec3 dir) {
            vec3 up = vec3(0., 1., 0.);
            vec3 rt = normalize(cross(dir, up));
            return mat3(rt, cross(rt, dir), dir);
        }
        
        vec3 hsb2rgb(vec3 c) {
            vec3 rgb = clamp(abs(mod(c.x*6.0+vec3(0.0,4.0,2.0), 6.0)-3.0)-1.0, 0.0, 1.0);
            return c.z * mix(vec3(1.0), rgb, c.y);
        }

        float gyroid(vec3 p) { return dot(cos(p), sin(p.zxy)) + 1.0; }

        float map(vec3 p) {
            vec3 q = p * 0.8;
            float d1 = gyroid(q);
            float d2 = gyroid(q - vec3(0.0, 0.0, PI));
            return min(d1, d2) * 0.5; 
        }

        void main() {
            vec2 flatUV = v - 0.5;
            vec2 aspectUV = flatUV;
            aspectUV.x *= uRatio; 
            
            vec3 ro = vec3(PI/2.0, 0.0, uScroll); 
            vec3 rd = normalize(vec3(aspectUV, -0.5)); 

            rd.xy = rot(sin(uSwayTime * 0.2) * uWide * 0.5) * rd.xy;
            vec3 targetOffsets = vec3(cos(uSwayTime * 0.4), sin(uSwayTime * 0.4), 4.0);
            targetOffsets.xy *= uWide; 
            vec3 ta = normalize(targetOffsets);
            rd = lookAt(ta) * rd;

            float t = 0.0;
            float d = 0.0;
            vec3 p = ro;
            
            for(int i = 0; i < 60; i++) {
                p = ro + rd * t;
                d = map(p);
                if(abs(d) < 0.01 || t > FAR) break;
                t += d;
            }
            
            vec2 tunnelUV;
            float fogAmt = 0.0;
            
            if(t < FAR) {
                float ang = atan(p.y, p.x);
                tunnelUV = vec2(ang / PI, p.z * 0.2);
                
                if (uFogD > 0.001) {
                    float fogWall = mix(FAR, 0.0, uFogD);
                    float fogStart = fogWall - max(uFogF, 0.001);
                    fogAmt = smoothstep(fogStart, fogWall, t);
                }
            } else {
                tunnelUV = flatUV; 
                fogAmt = 1.0; 
            }

            vec2 wrappedTunnel = abs(mod(tunnelUV + 0.5, 2.0) - 1.0);
            vec2 wrappedFlat = abs(mod(flatUV + 0.5, 2.0) - 1.0);

            vec2 finalUV = mix(wrappedFlat, wrappedTunnel, smoothstep(0.0, 1.0, uStr));
            vec4 col = texture2D(uTex, finalUV);
            
            if (uFogD > 0.001) {
                vec3 fogColor = hsb2rgb(vec3(uFogH, uFogS, uFogV));
                float effectiveFog = fogAmt * smoothstep(0.0, 1.0, uStr);
                col.rgb = mix(col.rgb, fogColor, effectiveFog);
            }

            gl_FragColor = col;
        }"""
        prog = ShaderHelper.createProgram("attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }", fSrc)
        locTex = GLES20.glGetUniformLocation(prog, "uTex"); locRatio = GLES20.glGetUniformLocation(prog, "uRatio")
        locStr = GLES20.glGetUniformLocation(prog, "uStr"); locWide = GLES20.glGetUniformLocation(prog, "uWide")
        locScroll = GLES20.glGetUniformLocation(prog, "uScroll"); locSwayTime = GLES20.glGetUniformLocation(prog, "uSwayTime")
        locFogD = GLES20.glGetUniformLocation(prog, "uFogD"); locFogF = GLES20.glGetUniformLocation(prog, "uFogF")
        locFogH = GLES20.glGetUniformLocation(prog, "uFogH"); locFogS = GLES20.glGetUniformLocation(prog, "uFogS")
        locFogV = GLES20.glGetUniformLocation(prog, "uFogV")
    }

    override fun render(inputTexId: Int, outputFbo: Int, w: Int, h: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo); GLES20.glViewport(0, 0, w, h); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(prog); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
        GLES20.glUniform1i(locTex, 0)

        GLES20.glUniform1f(locRatio, w.toFloat()/h.toFloat())
        GLES20.glUniform1f(locStr, mainActivity.controlsMap["UTWIRL"]?.computedValue ?: 0f)

        GLES20.glUniform1f(locWide, mainActivity.controlsMap["S_WIDE"]?.computedValue ?: 1f)
        GLES20.glUniform1f(locScroll, scrollAccum)
        GLES20.glUniform1f(locSwayTime, swayAccum)

        GLES20.glUniform1f(locFogD, mainActivity.controlsMap["S_FOG"]?.computedValue ?: 0f)
        GLES20.glUniform1f(locFogF, mainActivity.controlsMap["S_FOG_FALLOFF"]?.computedValue ?: 0f)
        GLES20.glUniform1f(locFogH, mainActivity.controlsMap["S_FOG_H"]?.computedValue ?: 0f)
        GLES20.glUniform1f(locFogS, mainActivity.controlsMap["S_FOG_S"]?.computedValue ?: 0f)
        GLES20.glUniform1f(locFogV, mainActivity.controlsMap["S_FOG_V"]?.computedValue ?: 0f)

        ShaderHelper.bindQuad(prog); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
    override fun release() { GLES20.glDeleteProgram(prog) }
}

class ColorEffect(activity: MainActivity) : ShaderEffect("FX_COLOR", "COLOR", activity) {
    private var prog = 0
    private var locTex = -1; private var locBrit = -1; private var locHue = -1; private var locNeg = -1
    private var locGlow = -1; private var locCon = -1; private var locVib = -1
    init {
        addControl(PropertyControl("BRIT", "BRIGHTNESS", defaultValue = 500, outMin=0f, outMax=2f, hasModulation = true))
        addControl(PropertyControl("HUE", "HUE", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true, modMode = PropertyControl.ModMode.WRAP))
        addControl(PropertyControl("NEG", "NEGATIVE", defaultValue = 0, outMin=0f, outMax=1f, hasModulation = true))
        addControl(PropertyControl("GLOW", "GLOW", defaultValue = 0, outMin=0f, outMax=2f, hasModulation = true))
        addControl(PropertyControl("CONTRAST", "CONTRAST", defaultValue = 500, outMin=0f, outMax=2f, hasModulation = true))
        addControl(PropertyControl("VIBRANCE", "SATURATION", defaultValue = 500, outMin=0f, outMax=2f, hasModulation = true))
    }
    override fun init() {
        val fSrc = """
        precision mediump float; varying vec2 v; uniform sampler2D uTex;
        uniform float uBrit, uHue, uNeg, uGlow, uCon, uVib;
        vec3 hueShift(vec3 c, float h) { const vec3 k = vec3(0.57735); float ca = cos(h); return c * ca + cross(k, c) * sin(h) + k * dot(k, c) * (1.0 - ca); }
        void main() {
            vec3 c = texture2D(uTex, v).rgb;
            c = abs(c - uNeg);
            if(uHue > 0.01) c = hueShift(c, uHue * 6.28);
            c = (c - 0.5) * uCon + 0.5;
            float l = dot(c, vec3(0.299, 0.587, 0.114));
            c = mix(vec3(l), c, uVib);
            if(uGlow > 0.01) c += smoothstep(0.4, 1.0, l) * c * uGlow * 2.0;
            c *= uBrit;
            gl_FragColor = vec4(c, 1.0);
        }"""
        prog = ShaderHelper.createProgram("attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }", fSrc)
        locTex = GLES20.glGetUniformLocation(prog, "uTex"); locBrit = GLES20.glGetUniformLocation(prog, "uBrit")
        locHue = GLES20.glGetUniformLocation(prog, "uHue"); locNeg = GLES20.glGetUniformLocation(prog, "uNeg")
        locGlow = GLES20.glGetUniformLocation(prog, "uGlow"); locCon = GLES20.glGetUniformLocation(prog, "uCon")
        locVib = GLES20.glGetUniformLocation(prog, "uVib")
    }
    override fun render(inputTexId: Int, outputFbo: Int, w: Int, h: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo); GLES20.glViewport(0, 0, w, h); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(prog); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
        GLES20.glUniform1i(locTex, 0)
        GLES20.glUniform1f(locBrit, mainActivity.controlsMap["BRIT"]?.computedValue ?: 1f)
        GLES20.glUniform1f(locHue, mainActivity.controlsMap["HUE"]?.computedValue ?: 0f)
        GLES20.glUniform1f(locNeg, mainActivity.controlsMap["NEG"]?.computedValue ?: 0f)
        GLES20.glUniform1f(locGlow, mainActivity.controlsMap["GLOW"]?.computedValue ?: 0f)
        GLES20.glUniform1f(locCon, mainActivity.controlsMap["CONTRAST"]?.computedValue ?: 1f)
        GLES20.glUniform1f(locVib, mainActivity.controlsMap["VIBRANCE"]?.computedValue ?: 1f)
        ShaderHelper.bindQuad(prog); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
    override fun release() { GLES20.glDeleteProgram(prog) }
}

class EdgeEffect(activity: MainActivity) : ShaderEffect("FX_EDGE", "EDGE DETECT", activity) {
    private var prog = 0
    private var locTex = -1; private var locAmt = -1; private var locThresh = -1
    private var locHue = -1; private var locSat = -1; private var locBrit = -1; private var locRes = -1

    init {
        addControl(PropertyControl("E_AMT", "AMOUNT", defaultValue = 0, outMin = 0f, outMax = 1f, hasModulation = true))
        addControl(PropertyControl("E_THRESH", "THRESHOLD", defaultValue = 300, outMin = -0.05f, outMax = 0.2f, hasModulation = true))
        addControl(PropertyControl("E_HUE", "HUE", defaultValue = 0, outMin = 0f, outMax = 1f, hasModulation = true, modMode = PropertyControl.ModMode.WRAP))
        addControl(PropertyControl("E_SAT", "SATURATION", defaultValue = 1000, outMin = 0f, outMax = 1f, hasModulation = true))
        addControl(PropertyControl("E_BRIT", "BRIGHTNESS", defaultValue = 500, outMin = 0f, outMax = 2f, hasModulation = true))
    }

    override fun init() {
        val fSrc = """
        precision mediump float; varying vec2 v; uniform sampler2D uTex;
        uniform float uAmt, uThresh, uHue, uSat, uBrit;
        uniform vec2 uRes;

        vec3 hsb2rgb(vec3 c) {
            vec3 rgb = clamp(abs(mod(c.x*6.0+vec3(0.0,4.0,2.0), 6.0)-3.0)-1.0, 0.0, 1.0);
            return c.z * mix(vec3(1.0), rgb, c.y);
        }

        float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

        void main() {
            vec2 px = uRes;
            vec3 video = texture2D(uTex, v).rgb;

            float l  = luma(texture2D(uTex, v).rgb);
            float lR = luma(texture2D(uTex, v + vec2(px.x, 0.0)).rgb);
            float lL = luma(texture2D(uTex, v - vec2(px.x, 0.0)).rgb);
            float lU = luma(texture2D(uTex, v + vec2(0.0, px.y)).rgb);
            float lD = luma(texture2D(uTex, v - vec2(0.0, px.y)).rgb);

            float gx = lR - lL;
            float gy = lU - lD;
            float edge = sqrt(gx*gx + gy*gy);
            edge = smoothstep(uThresh, uThresh + 0.08, edge);

            vec3 edgeColor = hsb2rgb(vec3(uHue, uSat, uBrit)) * edge;
            gl_FragColor = vec4(mix(video, edgeColor, uAmt), 1.0);
        }"""
        prog = ShaderHelper.createProgram("attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }", fSrc)
        locTex    = GLES20.glGetUniformLocation(prog, "uTex")
        locAmt    = GLES20.glGetUniformLocation(prog, "uAmt")
        locThresh = GLES20.glGetUniformLocation(prog, "uThresh")
        locHue    = GLES20.glGetUniformLocation(prog, "uHue")
        locSat    = GLES20.glGetUniformLocation(prog, "uSat")
        locBrit   = GLES20.glGetUniformLocation(prog, "uBrit")
        locRes    = GLES20.glGetUniformLocation(prog, "uRes")
    }

    override fun render(inputTexId: Int, outputFbo: Int, w: Int, h: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFbo); GLES20.glViewport(0, 0, w, h); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(prog); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)
        GLES20.glUniform1i(locTex, 0)
        GLES20.glUniform1f(locAmt,    mainActivity.controlsMap["E_AMT"]?.computedValue ?: 0f)
        GLES20.glUniform1f(locThresh, mainActivity.controlsMap["E_THRESH"]?.computedValue ?: 0.2f)
        GLES20.glUniform1f(locHue,    mainActivity.controlsMap["E_HUE"]?.computedValue ?: 0f)
        GLES20.glUniform1f(locSat,    mainActivity.controlsMap["E_SAT"]?.computedValue ?: 1f)
        GLES20.glUniform1f(locBrit,   mainActivity.controlsMap["E_BRIT"]?.computedValue ?: 1f)
        GLES20.glUniform2f(locRes, 1f / w.toFloat(), 1f / h.toFloat())
        ShaderHelper.bindQuad(prog); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() { GLES20.glDeleteProgram(prog) }
}
