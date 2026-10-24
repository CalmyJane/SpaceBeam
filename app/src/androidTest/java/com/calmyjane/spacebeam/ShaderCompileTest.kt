package com.calmyjane.spacebeam

import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compiles every shader on the phone's real GPU in an offscreen GL context.
 * Catches GLSL errors and driver differences (e.g. missing extensions on the S20 FE) before a gig.
 */
@RunWith(AndroidJUnit4::class)
class ShaderCompileTest {
    private lateinit var display: EGLDisplay
    private lateinit var context: EGLContext
    private lateinit var surface: EGLSurface

    @Before fun setUpGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        assertTrue(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE), 0, configs, 0, 1, num, 0)
        assertEquals(1, num[0])
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 64, EGL14.EGL_HEIGHT, 64, EGL14.EGL_NONE), 0)
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
        ShaderHelper.failures.clear()
    }

    @After fun tearDownGl() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }

    private fun assertNoFailures() =
        assertTrue(ShaderHelper.failures.joinToString("\n\n"), ShaderHelper.failures.isEmpty())

    @Test fun builtinShaderSourcesCompile() {
        val broken = BUILTIN_SHADERS.filter { (_, code) ->
            ShaderHelper.createProgram(ShaderHelper.CUSTOM_VERTEX, ShaderHelper.wrapShaderCode(code)) == 0
        }.keys
        assertTrue("broken builtin shaders: $broken\n" + ShaderHelper.failures.joinToString("\n\n"), broken.isEmpty())
    }

    @Test fun effectChainCompiles() {
        // The effects only need the activity for their parameter map; an unattached instance is enough
        lateinit var activity: MainActivity
        InstrumentationRegistry.getInstrumentation().runOnMainSync { activity = MainActivity() }
        val effects = listOf(
            MixerEffect(activity), TransformEffect("C", "CAMERA TRANSFORM", activity), ColorEffect(activity),
            EdgeEffect(activity), KaleidoscopeEffect(activity), DrosteEffect(activity),
            TransformEffect("M", "MASTER TRANSFORM", activity), TunnelEffect(activity), SwirlEffect(activity)
        )
        effects.forEach { it.init() }
        EffectChain().init(64, 64)
        MaskManager().initShader()
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
        assertNoFailures()
    }
}
