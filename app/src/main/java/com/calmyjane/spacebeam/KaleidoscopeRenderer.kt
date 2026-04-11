package com.calmyjane.spacebeam

import android.content.ContentValues
import android.graphics.*
import android.opengl.*
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.opengles.GL10
import javax.microedition.khronos.egl.EGLConfig as GL10EGLConfig
import android.opengl.EGLConfig as EGL14EGLConfig
import android.os.Environment
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import kotlin.math.*

class KaleidoscopeRenderer(val ctx: MainActivity) : GLSurfaceView.Renderer {
    private var fpsFrameCount = 0
    private var fpsLastCalcTime = System.currentTimeMillis()
    var globalTime = 0f
    private var simpleProgram = 0
    private var copyOesProgram = 0
    private var copy2dProgram = 0
    // Cached uniform locations for built-in programs
    private var locSimpleTex = -1; private var locSimpleMVP = -1
    private var locOesTex = -1; private var locOesAlpha = -1; private var locOesRot = -1
    private var locOesFlip = -1; private var locOesScale = -1; private var locOesST = -1
    private var loc2dTex = -1; private var loc2dAlpha = -1; private var loc2dRot = -1
    private var loc2dFlip = -1; private var loc2dScale = -1
    // Per-source transform shader
    private var srcTransformProg = 0
    private var locSrcTrTex = -1; private var locSrcTrZoom = -1; private var locSrcTrAngle = -1
    private var locSrcTrMove = -1; private var locSrcTrRatio = -1; private var locSrcTrWrap = -1
    private var srcTransformFbo = 0; private var srcTransformTex = 0
    val stMatrix = FloatArray(16).apply { android.opengl.Matrix.setIdentityM(this, 0) }

    @Volatile private var isSurfaceReady = false
    private val mvpMatrix = FloatArray(16)
    private val identityMatrix = FloatArray(16).apply { android.opengl.Matrix.setIdentityM(this, 0) }

    // Rotation accumulation logic (shared context)
    var mRotAccum = 0.0
    var cRotAccum = 0.0
    var lRotAccum = 0.0
    var axisCount = 2.0f
    var flipX = 1.0f
    var flipY = -1.0f
    var rot180 = false

    private val FIXED_WIDTH = 1920
    private val FIXED_HEIGHT = 1080
    private var viewWidth = 1
    private var viewHeight = 1

    private var lastTime = System.nanoTime()
    private var deltaTime = 0.0f

    val sources = java.util.concurrent.CopyOnWriteArrayList<SourceChannel>()
    private val MAX_SOURCES = 8

    private var isContinuousRendering = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (isContinuousRendering) {
                ctx.glView.requestRender()
                // Hooks directly into the display's hardware refresh rate
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    fun startContinuousRendering() {
        if (!isContinuousRendering) {
            isContinuousRendering = true
            ctx.runOnUiThread {
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }
    }

    fun stopContinuousRendering() {
        isContinuousRendering = false
        ctx.runOnUiThread {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    inner class SourceChannel(val type: SourceType, val id: String) : SurfaceTexture.OnFrameAvailableListener {
        @Volatile var isReady = false
        var onSurfaceReady: ((Surface) -> Unit)? = null

        var fboId = 0; var fboTexId = 0
        var width = 1920; var height = 1080
        var rotation = 0f
        var userFlipX = 1.0f; var userFlipY = 1.0f; var userRot180 = false
        var blendMode = BlendMode.SCREEN
        var injectionPoint: String = "FX_MIXER"  // default: into the mixer at the start

        // Per-source transform
        var srcZoom = 1.0f
        var srcAngle = 0f
        var srcMoveX = 0f
        var srcMoveY = 0f
        var srcWrapMode = 0  // 0=mirror, 1=hold, 2=repeat

        var customShaderCode: String? = null
        var customProgram: Int = 0
        private var customLocITime = -1; private var customLocUTime = -1
        private var customLocIResolution = -1; private var customLocUFlip = -1; private var customLocURotation = -1

        // Per-source feedback buffer
        var feedbackTapEffectId: String = "FX_SWIRL"
        var feedbackDelay = 1
        private var fbFbo = 0
        private var fbTextures = IntArray(0)
        private var fbBufferSize = 0
        private var fbWriteIndex = 0
        private var fbPendingResize = 1

        val feedbackTexId: Int get() {
            if (fbBufferSize == 0) return 0
            val readIdx = ((fbWriteIndex - feedbackDelay) % fbBufferSize + fbBufferSize) % fbBufferSize
            return fbTextures[readIdx]
        }

        fun setFeedbackBufferSize(size: Int) {
            val clamped = size.coerceIn(1, 60)
            if (clamped != fbBufferSize) fbPendingResize = clamped
        }

        fun initFeedbackBuffer(w: Int, h: Int) {
            if (fbFbo != 0) return // already initialized
            val f = IntArray(1); val t = IntArray(1)
            GLES20.glGenFramebuffers(1, f, 0); GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            fbFbo = f[0]
            fbTextures = IntArray(1) { t[0] }
            fbBufferSize = 1
            fbWriteIndex = 0
            fbPendingResize = feedbackDelay.coerceAtLeast(1)
        }

        fun writeFeedbackSlot(srcTex: Int, copyProg: Int, copyLocTex: Int) {
            if (fbFbo == 0 || srcTex == 0 || copyProg == 0 || fbBufferSize == 0) return
            resizeFeedbackBuffer(copyProg)
            val destTex = fbTextures[fbWriteIndex]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, destTex, 0)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(copyProg)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
            GLES20.glUniform1i(copyLocTex, 0)
            ShaderHelper.bindQuad(copyProg)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            fbWriteIndex = (fbWriteIndex + 1) % fbBufferSize
        }

        private fun resizeFeedbackBuffer(copyProg: Int) {
            val target = fbPendingResize
            if (target == fbBufferSize || target < 1 || width == 0) return
            if (target > fbBufferSize) {
                val newTextures = IntArray(target)
                for (i in 0 until fbBufferSize) newTextures[i] = fbTextures[i]
                val extra = target - fbBufferSize
                val texIds = IntArray(extra)
                GLES20.glGenTextures(extra, texIds, 0)
                for (i in 0 until extra) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[i])
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    newTextures[fbBufferSize + i] = texIds[i]
                }
                fbTextures = newTextures
            } else {
                val excess = fbBufferSize - target
                val toDelete = IntArray(excess)
                for (i in 0 until excess) toDelete[i] = fbTextures[target + i]
                GLES20.glDeleteTextures(excess, toDelete, 0)
                fbTextures = fbTextures.copyOf(target)
                fbWriteIndex = fbWriteIndex % target
            }
            fbBufferSize = target
        }

        fun releaseFeedbackBuffer() {
            if (fbFbo != 0) { GLES20.glDeleteFramebuffers(1, IntArray(1) { fbFbo }, 0) }
            if (fbBufferSize > 0) { GLES20.glDeleteTextures(fbBufferSize, fbTextures, 0) }
            fbFbo = 0; fbTextures = IntArray(0); fbBufferSize = 0; fbWriteIndex = 0
        }

        // Playlist & Crossfade specifics
        @Volatile var baseLayerIndex = 0
        @Volatile var topLayerAlpha = 0f
        @Volatile var isEmpty = false

        inner class MediaLayer(val side: Int) {
            var isVideo = true
            var oesTexId = 0
            var tex2dId = 0
            var surfaceTexture: SurfaceTexture? = null
            var surface: Surface? = null
            var bitmap: Bitmap? = null

            @Volatile var imageUploaded = false
            @Volatile var frameAvailable = false

            var stMatrix = FloatArray(16)
            var width = 1920
            var height = 1080
            var rotation = 0f

            fun init() {
                val tex = IntArray(2)
                GLES20.glGenTextures(2, tex, 0)
                oesTexId = tex[0]
                tex2dId = tex[1]

                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                surfaceTexture = SurfaceTexture(oesTexId)
                surfaceTexture?.setDefaultBufferSize(width, height)
                surfaceTexture?.setOnFrameAvailableListener(this@SourceChannel)
                surface = Surface(surfaceTexture)

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex2dId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            }

            fun release() {
                surface?.release(); surfaceTexture?.release()
                val t = IntArray(2) { if (it == 0) oesTexId else tex2dId }
                GLES20.glDeleteTextures(2, t, 0)
            }
        }

        val layerA = MediaLayer(0)
        val layerB = MediaLayer(1)

        fun init() {
            if (isReady) return
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {}

            val fb = IntArray(1); val tx = IntArray(1)
            GLES20.glGenFramebuffers(1, fb, 0); GLES20.glGenTextures(1, tx, 0)
            fboId = fb[0]; fboTexId = tx[0]
            if (fboId == 0 || fboTexId == 0) return

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, FIXED_WIDTH, FIXED_HEIGHT, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTexId, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            if (type == SourceType.FEEDBACK) {
                // Feedback source only needs the FBO — no layers, no SurfaceTexture
                // Default flip to correct FBO coordinate orientation
                userFlipX = -1.0f
                userFlipY = -1.0f
                isReady = true
                return
            }

            if (type == SourceType.SHADER) {
                val vSrc = """
                    attribute vec4 p; attribute vec2 t; varying vec2 v;
                    uniform vec2 uFlip; uniform float uRotation;
                    void main() {
                        gl_Position = p; vec2 uv = t - 0.5; uv = uv * uFlip;
                        float c = cos(uRotation); float s = sin(uRotation);
                        uv = vec2(uv.x * c - uv.y * s, uv.x * s + uv.y * c);
                        v = uv + 0.5;
                    }
                """.trimIndent()
                val fSrc = ctx.wrapShaderCode(customShaderCode ?: "void main(){ gl_FragColor=vec4(0.0); }")
                customProgram = ShaderHelper.createProgram(vSrc, fSrc)
                customLocITime = GLES20.glGetUniformLocation(customProgram, "iTime")
                customLocUTime = GLES20.glGetUniformLocation(customProgram, "uTime")
                customLocIResolution = GLES20.glGetUniformLocation(customProgram, "iResolution")
                customLocUFlip = GLES20.glGetUniformLocation(customProgram, "uFlip")
                customLocURotation = GLES20.glGetUniformLocation(customProgram, "uRotation")
                isReady = true
                return
            }

            layerA.init()
            layerB.init()

            if (type == SourceType.MEDIA_IMAGE) layerA.isVideo = false

            if (onSurfaceReady != null) {
                val s = layerA.surface!!
                android.os.Handler(android.os.Looper.getMainLooper()).post { onSurfaceReady?.invoke(s) }
            }
            isReady = true
        }

        fun getSurfaceForInput(): Surface? {
            if (!isReady) init()
            return layerA.surface
        }

        override fun onFrameAvailable(st: SurfaceTexture?) {
            if (st == layerA.surfaceTexture) layerA.frameAvailable = true
            if (st == layerB.surfaceTexture) layerB.frameAvailable = true
        }

        fun release() {
            isReady = false
            if (customProgram != 0) {
                GLES20.glDeleteProgram(customProgram); customProgram = 0
                customLocITime = -1; customLocUTime = -1; customLocIResolution = -1; customLocUFlip = -1; customLocURotation = -1
            }
            releaseFeedbackBuffer()
            layerA.release(); layerB.release()
            if (fboId != 0) { val f = IntArray(1){fboId}; GLES20.glDeleteFramebuffers(1, f, 0); fboId = 0 }
            if (fboTexId != 0) { val t = IntArray(1){fboTexId}; GLES20.glDeleteTextures(1, t, 0); fboTexId = 0 }
        }

        fun updateSize(w: Int, h: Int) {
            width = w; height = h
            layerA.width = w; layerA.height = h
            layerB.width = w; layerB.height = h
            if (type != SourceType.MEDIA_IMAGE && type != SourceType.SHADER && type != SourceType.FEEDBACK) {
                ctx.glView.queueEvent { layerA.surfaceTexture?.setDefaultBufferSize(w, h) }
            }
        }

        private fun updateSurfaces() {
            // Safely drain Layer A
            if (layerA.isVideo) {
                if (layerA.frameAvailable) {
                    layerA.frameAvailable = false
                    try {
                        layerA.surfaceTexture?.updateTexImage()
                        if (type != SourceType.CAMERA) layerA.surfaceTexture?.getTransformMatrix(layerA.stMatrix)
                    } catch (e: Exception) {}
                }
            } else {
                if (!layerA.imageUploaded && layerA.bitmap != null) {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layerA.tex2dId)
                    try { android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, layerA.bitmap, 0); layerA.imageUploaded = true } catch (e: Exception) {}
                }
            }

            // Safely drain Layer B
            if (layerB.isVideo) {
                if (layerB.frameAvailable) {
                    layerB.frameAvailable = false
                    try {
                        layerB.surfaceTexture?.updateTexImage()
                        if (type != SourceType.CAMERA) layerB.surfaceTexture?.getTransformMatrix(layerB.stMatrix)
                    } catch (e: Exception) {}
                }
            } else {
                if (!layerB.imageUploaded && layerB.bitmap != null) {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layerB.tex2dId)
                    try { android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, layerB.bitmap, 0); layerB.imageUploaded = true } catch (e: Exception) {}
                }
            }
        }

        private fun drawMediaLayer(layer: MediaLayer, alpha: Float) {
            val program = if (layer.isVideo) copyOesProgram else copy2dProgram
            val target = if (layer.isVideo) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
            if (program == 0) return
            if (!layer.isVideo && !layer.imageUploaded) return

            if (layer.isVideo && type == SourceType.CAMERA) {
                android.opengl.Matrix.setIdentityM(layer.stMatrix, 0)
            }

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(target, if(layer.isVideo) layer.oesTexId else layer.tex2dId)

            val locTex   = if (layer.isVideo) locOesTex   else loc2dTex
            val locAlpha = if (layer.isVideo) locOesAlpha else loc2dAlpha
            val locRot   = if (layer.isVideo) locOesRot   else loc2dRot
            val locFlip  = if (layer.isVideo) locOesFlip  else loc2dFlip
            val locScale = if (layer.isVideo) locOesScale else loc2dScale
            GLES20.glUniform1i(locTex, 0)
            GLES20.glUniform1f(locAlpha, alpha)

            val extraRot = if (userRot180) 180f else 0f
            val finalRot = rotation + layer.rotation + extraRot
            val rad = Math.toRadians(-finalRot.toDouble()).toFloat()
            GLES20.glUniform1f(locRot, rad)
            GLES20.glUniform2f(locFlip, userFlipX, userFlipY)

            val isSideways = (kotlin.math.abs(rotation + layer.rotation) % 180f) > 45f
            val effectiveW = if (isSideways) layer.height.toFloat() else layer.width.toFloat()
            val effectiveH = if (isSideways) layer.width.toFloat() else layer.height.toFloat()
            val fboAspect = FIXED_WIDTH.toFloat() / FIXED_HEIGHT.toFloat()
            val safeH = if (effectiveH > 0) effectiveH else 1.0f
            val srcAspect = effectiveW / safeH
            var sx = 1.0f; var sy = 1.0f
            if (fboAspect > srcAspect) { sy = srcAspect / fboAspect } else { sx = fboAspect / srcAspect }
            if (isSideways) { val temp = sx; sx = sy; sy = temp }

            GLES20.glUniform2f(locScale, sx, sy)

            if (layer.isVideo) {
                GLES20.glUniformMatrix4fv(locOesST, 1, false, layer.stMatrix, 0)
            }

            ShaderHelper.bindQuad(program)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun processToFbo() {
            if (!isReady) return

            if (type != SourceType.FEEDBACK) {
                updateSurfaces() // Always drain decoders independent of alpha!
            }

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glViewport(0, 0, FIXED_WIDTH, FIXED_HEIGHT)

            if (type == SourceType.FEEDBACK) {
                // Copy this source's own feedback texture into its FBO
                val fbTex = feedbackTexId
                if (fbTex != 0) {
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glUseProgram(copy2dProgram)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fbTex)
                    GLES20.glUniform1i(loc2dTex, 0)
                    GLES20.glUniform1f(loc2dAlpha, 1.0f)
                    val extraRot = if (userRot180) 180f else 0f
                    GLES20.glUniform1f(loc2dRot, Math.toRadians(-extraRot.toDouble()).toFloat())
                    GLES20.glUniform2f(loc2dFlip, userFlipX, userFlipY)
                    GLES20.glUniform2f(loc2dScale, 1.0f, 1.0f)
                    ShaderHelper.bindQuad(copy2dProgram)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                } else {
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                }
            } else if (type == SourceType.SHADER) {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glUseProgram(customProgram)
                GLES20.glUniform1f(customLocITime, globalTime)
                GLES20.glUniform1f(customLocUTime, globalTime)
                GLES20.glUniform2f(customLocIResolution, FIXED_WIDTH.toFloat(), FIXED_HEIGHT.toFloat())
                GLES20.glUniform2f(customLocUFlip, userFlipX, userFlipY)
                GLES20.glUniform1f(customLocURotation, Math.toRadians((rotation + if(userRot180) 180f else 0f).toDouble()).toFloat())
                ShaderHelper.bindQuad(customProgram)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } else if (type == SourceType.PLAYLIST) {
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                if (!isEmpty) {
                    val base = if (baseLayerIndex == 0) layerA else layerB
                    val top = if (baseLayerIndex == 0) layerB else layerA

                    drawMediaLayer(base, 1.0f)

                    if (topLayerAlpha > 0.01f) {
                        GLES20.glEnable(GLES20.GL_BLEND)
                        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                        drawMediaLayer(top, topLayerAlpha)
                        GLES20.glDisable(GLES20.GL_BLEND)
                    }
                }
            } else {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                drawMediaLayer(layerA, 1.0f)
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
    }

    fun addSource(type: SourceType, id: String, bitmap: Bitmap? = null): SourceChannel? {
        if (sources.size >= MAX_SOURCES) return null
        val ch = SourceChannel(type, id)

        // Assign the bitmap to the new base layer instead of the channel root
        ch.layerA.bitmap = bitmap

        if (bitmap != null) {
            // Update dimensions for both the channel and the layer
            ch.width = bitmap.width
            ch.height = bitmap.height
            ch.layerA.width = bitmap.width
            ch.layerA.height = bitmap.height
        }

        sources.add(ch)
        return ch
    }

    fun removeSource(id: String) {
        val toRemove = sources.find { it.id == id }
        if (toRemove != null) {
            sources.remove(toRemove)
            ctx.glView.queueEvent { toRemove.release() }
        }
    }

    fun getSource(id: String): SourceChannel? = sources.find { it.id == id }

    private var fboId = 0; private var fboTexId = 0
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
    private var extWidth = 0; private var extHeight = 0

    private var rotTargetM: Double? = null; private var rotStartM: Double = 0.0
    private var rotTargetC: Double? = null; private var rotStartC: Double = 0.0
    private var rotAnimDuration: Float = 0f; private var rotAnimTime: Float = 0f; private var isRotAnimating = false

    fun animateRotationTo(targetM: Double, targetC: Double, duration: Float) {
        rotTargetM = targetM; rotStartM = mRotAccum
        rotTargetC = targetC; rotStartC = cRotAccum
        rotAnimDuration = duration; rotAnimTime = 0f; isRotAnimating = true
    }
    fun stopRotationAnim() { isRotAnimating = false }
    fun resetPhases() { ctx.controls.forEach { it.lfoPhase = 0.0; it.resetRampAccum() }; mRotAccum = 0.0; cRotAccum = 0.0; lRotAccum = 0.0 }
    fun capturePhoto() { captureRequested = true }
    fun stopRecording(callback: (File?) -> Unit) { onStopCallback = callback; isStopRequested = true }
    fun startRecording(file: File) { pendingRecordFile = file; recordStartTimeNs = 0 }
    fun setExternalSurface(s: Surface, w: Int, h: Int) { extSurfaceArgs = Triple(s, w, h) }
    fun removeExternalSurface() { extSurfaceArgs = null }

    fun provideCameraSurface(req: SurfaceRequest) {
        val cam = getSource("CAM_MAIN") ?: return
        val surf = cam.getSurfaceForInput()
        if (cam.isReady && surf != null) {
            req.provideSurface(surf, ContextCompat.getMainExecutor(ctx)) {}
        } else {
            cam.onSurfaceReady = { surface -> req.provideSurface(surface, ContextCompat.getMainExecutor(ctx)) {} }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: GL10EGLConfig?) {
        setupEGL()
        ShaderHelper.clearAttribCache()
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        val vSrc = "attribute vec4 p; attribute vec2 t; varying vec2 v; void main() { gl_Position = p; v = t; }"

        val fSrcCopyOes = """#extension GL_OES_EGL_image_external : require
        precision mediump float; varying vec2 v; 
        uniform samplerExternalOES uTex; 
        uniform vec2 uScale; 
        uniform float uRotation;
        uniform vec2 uFlip;
        uniform mat4 uSTMatrix;
        uniform float uAlpha;
        void main() {
            vec2 uv = v - 0.5;
            uv = uv * uScale;
            uv = uv * uFlip; 
            float c = cos(uRotation);
            float s = sin(uRotation);
            uv = vec2(uv.x * c - uv.y * s, uv.x * s + uv.y * c);
            uv = uv + 0.5;
            uv = abs(mod(uv + 1.0, 2.0) - 1.0);
            
            vec2 stUV = (uSTMatrix * vec4(uv, 0.0, 1.0)).xy;
            gl_FragColor = vec4(texture2D(uTex, stUV).rgb, uAlpha);
        }""".trimIndent()
        copyOesProgram = ShaderHelper.createProgram(vSrc, fSrcCopyOes)
        locOesTex = GLES20.glGetUniformLocation(copyOesProgram, "uTex")
        locOesAlpha = GLES20.glGetUniformLocation(copyOesProgram, "uAlpha")
        locOesRot = GLES20.glGetUniformLocation(copyOesProgram, "uRotation")
        locOesFlip = GLES20.glGetUniformLocation(copyOesProgram, "uFlip")
        locOesScale = GLES20.glGetUniformLocation(copyOesProgram, "uScale")
        locOesST = GLES20.glGetUniformLocation(copyOesProgram, "uSTMatrix")

        val fSrcCopy2d = """
        precision mediump float; varying vec2 v; 
        uniform sampler2D uTex; 
        uniform vec2 uScale; 
        uniform float uRotation;
        uniform vec2 uFlip;
        uniform float uAlpha;
        void main() {
            vec2 uv = v - 0.5;
            uv = uv * uScale;
            uv = uv * uFlip;
            float c = cos(uRotation);
            float s = sin(uRotation);
            uv = vec2(uv.x * c - uv.y * s, uv.x * s + uv.y * c);
            uv = uv + 0.5;
            uv = abs(mod(uv + 1.0, 2.0) - 1.0);
            
            gl_FragColor = vec4(texture2D(uTex, uv).rgb, uAlpha);
        }""".trimIndent()
        copy2dProgram = ShaderHelper.createProgram(vSrc, fSrcCopy2d)
        loc2dTex = GLES20.glGetUniformLocation(copy2dProgram, "uTex")
        loc2dAlpha = GLES20.glGetUniformLocation(copy2dProgram, "uAlpha")
        loc2dRot = GLES20.glGetUniformLocation(copy2dProgram, "uRotation")
        loc2dFlip = GLES20.glGetUniformLocation(copy2dProgram, "uFlip")
        loc2dScale = GLES20.glGetUniformLocation(copy2dProgram, "uScale")

        val fSimple = "precision mediump float; varying vec2 v; uniform sampler2D uTex; void main() { gl_FragColor = texture2D(uTex, v); }"
        simpleProgram = ShaderHelper.createProgram("attribute vec4 p; attribute vec2 t; varying vec2 v; uniform mat4 uMVPMatrix; void main() { gl_Position = uMVPMatrix * p; v = t; }", fSimple)
        locSimpleTex = GLES20.glGetUniformLocation(simpleProgram, "uTex")
        locSimpleMVP = GLES20.glGetUniformLocation(simpleProgram, "uMVPMatrix")

        // Per-source transform shader (zoom, rotate, move with mirror repeat)
        val fSrcTr = """
        precision mediump float; varying vec2 v;
        uniform sampler2D uTex; uniform float uZoom, uAngle; uniform vec2 uMove; uniform float uRatio;
        uniform int uWrap;
        void main() {
            vec2 uv = v - 0.5;
            uv /= uZoom;
            float af = uRatio;
            uv.x *= af;
            float c = cos(uAngle); float s = sin(uAngle);
            uv = vec2(uv.x*c - uv.y*s, uv.x*s + uv.y*c);
            uv.x /= af;
            uv += uMove;
            uv += 0.5;
            if (uWrap == 1) { uv = clamp(uv, 0.0, 1.0); }
            else if (uWrap == 2) { uv = fract(uv); }
            else { uv = abs(mod(uv + 1.0, 2.0) - 1.0); }
            gl_FragColor = texture2D(uTex, uv);
        }""".trimIndent()
        srcTransformProg = ShaderHelper.createProgram(vSrc, fSrcTr)
        locSrcTrTex = GLES20.glGetUniformLocation(srcTransformProg, "uTex")
        locSrcTrZoom = GLES20.glGetUniformLocation(srcTransformProg, "uZoom")
        locSrcTrAngle = GLES20.glGetUniformLocation(srcTransformProg, "uAngle")
        locSrcTrMove = GLES20.glGetUniformLocation(srcTransformProg, "uMove")
        locSrcTrRatio = GLES20.glGetUniformLocation(srcTransformProg, "uRatio")
        locSrcTrWrap = GLES20.glGetUniformLocation(srcTransformProg, "uWrap")

        // Shared temp FBO for per-source transform
        run {
            val f = IntArray(1); val t = IntArray(1)
            GLES20.glGenFramebuffers(1, f, 0); GLES20.glGenTextures(1, t, 0)
            srcTransformFbo = f[0]; srcTransformTex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTransformTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, FIXED_WIDTH, FIXED_HEIGHT, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, srcTransformFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, srcTransformTex, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        initMainFBO(FIXED_WIDTH, FIXED_HEIGHT)
        ctx.effectChain.init(FIXED_WIDTH, FIXED_HEIGHT)

        sources.forEach { it.init() }
        ctx.runOnUiThread { ctx.startCamera() }
    }

    private fun initMainFBO(w: Int, h: Int) {
        if (fboId != 0) { val fb = IntArray(1) { fboId }; val tx = IntArray(1) { fboTexId }; GLES20.glDeleteFramebuffers(1, fb, 0); GLES20.glDeleteTextures(1, tx, 0) }
        val fb = IntArray(1); val tx = IntArray(1); GLES20.glGenFramebuffers(1, fb, 0); GLES20.glGenTextures(1, tx, 0)
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
        if (w == 0 || h == 0) return
        viewWidth = w; viewHeight = h
        isSurfaceReady = true
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!isSurfaceReady) return
        sources.forEach { if (!it.isReady) it.init() }

        val now = System.nanoTime()
        deltaTime = (now - lastTime) / 1e9f
        globalTime += deltaTime
        lastTime = now

        fpsFrameCount++
        val currentMillis = System.currentTimeMillis()
        if (currentMillis - fpsLastCalcTime >= 500) {
            val fps = (fpsFrameCount * 1000f / (currentMillis - fpsLastCalcTime)).toInt()
            fpsFrameCount = 0
            fpsLastCalcTime = currentMillis
            ctx.runOnUiThread {
                ctx.updateFpsUI(fps)
            }
        }

        if (isRotAnimating && rotTargetM != null) {
            rotAnimTime += deltaTime
            if (rotAnimTime >= rotAnimDuration) {
                mRotAccum = rotTargetM!!; cRotAccum = rotTargetC!!; isRotAnimating = false
            } else {
                val t = (rotAnimTime / rotAnimDuration).coerceIn(0f, 1f)
                val ease = t * t * (3f - 2f * t)
                mRotAccum = rotStartM + (rotTargetM!! - rotStartM) * ease
                cRotAccum = rotStartC + (rotTargetC!! - rotStartC) * ease
            }
        }

        ctx.controls.forEach { it.update(deltaTime) }
        ctx.effectChain.effects.forEach { if(it.active) it.update(deltaTime) }

        sources.forEach { it.processToFbo() }
        // Apply per-source transform (zoom, angle, move) if non-default
        sources.forEach { src ->
            if (src.srcZoom != 1.0f || src.srcAngle != 0f || src.srcMoveX != 0f || src.srcMoveY != 0f || src.srcWrapMode != 0) {
                // Render from source FBO through transform shader into temp FBO
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, srcTransformFbo)
                GLES20.glViewport(0, 0, FIXED_WIDTH, FIXED_HEIGHT)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glUseProgram(srcTransformProg)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src.fboTexId)
                GLES20.glUniform1i(locSrcTrTex, 0)
                GLES20.glUniform1f(locSrcTrZoom, src.srcZoom)
                GLES20.glUniform1f(locSrcTrAngle, Math.toRadians(src.srcAngle.toDouble()).toFloat())
                GLES20.glUniform2f(locSrcTrMove, src.srcMoveX, src.srcMoveY)
                GLES20.glUniform1f(locSrcTrRatio, FIXED_WIDTH.toFloat() / FIXED_HEIGHT.toFloat())
                GLES20.glUniform1i(locSrcTrWrap, src.srcWrapMode)
                ShaderHelper.bindQuad(srcTransformProg)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                // Copy back from temp to source FBO
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, src.fboId)
                GLES20.glViewport(0, 0, FIXED_WIDTH, FIXED_HEIGHT)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glUseProgram(copy2dProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTransformTex)
                GLES20.glUniform1i(loc2dTex, 0)
                GLES20.glUniform1f(loc2dAlpha, 1.0f)
                GLES20.glUniform1f(loc2dRot, 0f)
                GLES20.glUniform2f(loc2dFlip, 1f, 1f)
                GLES20.glUniform2f(loc2dScale, 1f, 1f)
                ShaderHelper.bindQuad(copy2dProgram)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            }
        }
        manageSurfaces()

        val finalTex = ctx.effectChain.process(this)

        // Mask texture: kick off async generation, upload when ready
        val mask = ctx.maskManager
        if (mask.needsRegenerate) {
            mask.needsRegenerate = false
            mask.initShader()
            mask.requestRegenerate(FIXED_WIDTH, FIXED_HEIGHT)
        }
        if (mask.needsHiResRegenerate) {
            mask.needsHiResRegenerate = false
            mask.initShader()
            mask.requestHiResRegenerate(FIXED_WIDTH, FIXED_HEIGHT)
        }
        mask.uploadPendingMask()

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glViewport(0, 0, FIXED_WIDTH, FIXED_HEIGHT)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (mask.enabled && mask.maskTexId != 0) {
            mask.drawMasked(finalTex, identityMatrix)
        } else {
            GLES20.glUseProgram(simpleProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, finalTex)
            GLES20.glUniform1i(locSimpleTex, 0)
            GLES20.glUniformMatrix4fv(locSimpleMVP, 1, false, identityMatrix, 0)
            ShaderHelper.bindQuad(simpleProgram)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        handleCapture()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        renderToScreen()
        renderToExternal()
        renderToRecorder()
    }

    private fun renderToScreen() {
        if (simpleProgram == 0) return
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val isPortrait = viewWidth < viewHeight
        android.opengl.Matrix.setIdentityM(mvpMatrix, 0)

        val fboRatio = FIXED_WIDTH.toFloat() / FIXED_HEIGHT.toFloat()
        val screenRatio = viewWidth.toFloat() / viewHeight.toFloat()

        if (isPortrait) {
            android.opengl.Matrix.rotateM(mvpMatrix, 0, -90f, 0f, 0f, 1f)
            val rotatedFboRatio = 1f / fboRatio
            if (screenRatio < rotatedFboRatio) {
                val scale = rotatedFboRatio / screenRatio
                android.opengl.Matrix.scaleM(mvpMatrix, 0, 1f, scale, 1f)
            } else {
                val scale = screenRatio / rotatedFboRatio
                android.opengl.Matrix.scaleM(mvpMatrix, 0, scale, 1f, 1f)
            }
        } else {
            if (screenRatio > fboRatio) {
                val scale = screenRatio / fboRatio
                android.opengl.Matrix.scaleM(mvpMatrix, 0, 1f, scale, 1f)
            } else {
                val scale = fboRatio / screenRatio
                android.opengl.Matrix.scaleM(mvpMatrix, 0, scale, 1f, 1f)
            }
        }

        GLES20.glUseProgram(simpleProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)

        GLES20.glUniform1i(locSimpleTex, 0)
        GLES20.glUniformMatrix4fv(locSimpleMVP, 1, false, mvpMatrix, 0)

        ShaderHelper.bindQuad(simpleProgram)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
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
                GLES20.glViewport(0, 0, videoRecorder!!.width, videoRecorder!!.height)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                if (videoRecorder!!.isPortrait) {
                    val recMvp = FloatArray(16)
                    android.opengl.Matrix.setIdentityM(recMvp, 0)
                    android.opengl.Matrix.rotateM(recMvp, 0, -90f, 0f, 0f, 1f)
                    GLES20.glUseProgram(simpleProgram)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
                    GLES20.glUniform1i(locSimpleTex, 0)
                    GLES20.glUniformMatrix4fv(locSimpleMVP, 1, false, recMvp, 0)
                    ShaderHelper.bindQuad(simpleProgram)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                } else {
                    drawSimpleTexture(fboTexId)
                }
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
        GLES20.glUseProgram(simpleProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)

        GLES20.glUniform1i(locSimpleTex, 0)
        GLES20.glUniformMatrix4fv(locSimpleMVP, 1, false, identityMatrix, 0)

        ShaderHelper.bindQuad(simpleProgram)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun setupEGL() {
        mSavedDisplay = EGL14.eglGetCurrentDisplay()
        mSavedContext = EGL14.eglGetCurrentContext()
        val currentConfigId = IntArray(1)
        EGL14.eglQueryContext(mSavedDisplay, mSavedContext, EGL14.EGL_CONFIG_ID, currentConfigId, 0)
        val configs = arrayOfNulls<EGL14EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(mSavedDisplay, intArrayOf(EGL14.EGL_CONFIG_ID, currentConfigId[0], EGL14.EGL_NONE), 0, configs, 0, 1, num, 0)
        mEglConfig = configs[0]
    }

    private fun manageSurfaces() {
        val args = extSurfaceArgs
        if (args != null && extEglSurface == EGL14.EGL_NO_SURFACE) {
            val rawSurf = args.first; extWidth = args.second; extHeight = args.third
            extEglSurface = EGL14.eglCreateWindowSurface(mSavedDisplay, mEglConfig, rawSurf, intArrayOf(EGL14.EGL_NONE), 0)
        }
        if (args == null && extEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(mSavedDisplay, extEglSurface)
            extEglSurface = EGL14.EGL_NO_SURFACE
        }
        if (pendingRecordFile != null) {
            videoRecorder = VideoRecorder(ctx, viewWidth, viewHeight, pendingRecordFile!!)
            recordSurface = EGL14.eglCreateWindowSurface(mSavedDisplay, mEglConfig, videoRecorder!!.inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
            pendingRecordFile = null
        }
    }

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
            val isPortrait = viewWidth < viewHeight
            val b = ByteBuffer.allocate(FIXED_WIDTH * FIXED_HEIGHT * 4)
            GLES20.glReadPixels(0, 0, FIXED_WIDTH, FIXED_HEIGHT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, b)
            Thread {
                var bmp = Bitmap.createBitmap(FIXED_WIDTH, FIXED_HEIGHT, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(b) }
                if (isPortrait) {
                    val matrix = android.graphics.Matrix().apply { postRotate(-90f) }
                    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                    bmp.recycle()
                    bmp = rotated
                }
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
