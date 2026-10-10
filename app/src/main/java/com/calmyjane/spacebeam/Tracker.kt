package com.calmyjane.spacebeam

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor

/**
 * One CameraX analysis stream (CameraX allows only one per camera) shared by all body trackers.
 * Each frame is rotated upright for the current screen orientation (and mirrored for the front camera)
 * once, then handed to every enabled tracker that is due.
 *
 * The stream is always bound together with the preview and idles without an analyzer; switching a
 * tracker on/off only attaches/detaches the analyzer. Rebinding the camera instead would freeze the
 * camera image for a moment on every toggle.
 */
class VisionPipeline {
    // Below-normal priority: model loading and detection must never steal time from rendering
    val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vision").apply { priority = Thread.NORM_PRIORITY - 2 }
    }
    val trackers = mutableListOf<Tracker>()
    private var analysis: ImageAnalysis? = null
    @Volatile private var mirror = false
    /** True once the analysis stream is bound next to the preview. */
    var available = false; private set
    /** True when the current camera can't run preview + analysis together (as opposed to: camera not started yet). */
    var unsupported = false; private set

    /** Analysis use case to bind next to the camera preview (idle until a tracker is on). */
    fun createAnalysis(isFrontCamera: Boolean, displayRotation: Int): ImageAnalysis {
        analysis?.clearAnalyzer()
        mirror = isFrontCamera
        val a = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(displayRotation)
            .build()
        analysis = a
        available = true
        unsupported = false
        updateAnalyzer()
        return a
    }

    /** The bind with analysis failed: this camera runs preview only. */
    fun dropAnalysis() {
        analysis?.clearAnalyzer()
        analysis = null
        available = false
        unsupported = true
    }

    /** Attach the analyzer while any tracker is on, detach it otherwise (no frames are converted then). */
    fun updateAnalyzer() {
        val a = analysis ?: return
        if (trackers.none { it.enabled }) { a.clearAnalyzer(); return }
        a.setAnalyzer(executor) { proxy ->
            try {
                val now = SystemClock.uptimeMillis()
                if (trackers.any { it.isDue(now) }) {
                    val src = proxy.toBitmap()
                    val m = Matrix().apply {
                        postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                        if (mirror) postScale(-1f, 1f)
                    }
                    val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                    if (bmp !== src) src.recycle()
                    for (t in trackers) if (t.isDue(now)) t.process(bmp, now)
                    bmp.recycle()
                }
            } catch (e: Exception) {
                Log.w("VisionPipeline", "Analysis failed", e)
            } finally {
                proxy.close()
            }
        }
    }

    fun setDisplayRotation(rotation: Int) {
        analysis?.targetRotation = rotation
    }

    fun close() {
        trackers.forEach { it.disable(); it.release() }
        analysis?.clearAnalyzer()
        executor.shutdown()
    }
}

/**
 * Shared behaviour of the hand and face trackers: [values] 0..1 per channel, smoothed per rendered frame,
 * a spike filter against single bad detections, a circular angle channel, and [blend], which fades the
 * whole modulation in/out when tracking is switched so parameters never jump.
 *
 * @param angleRangeTurns tilt (in turns) that spans the full 0..1 range of the clamped angle value;
 *                        on wrapping parameters the same tilt gives half a turn of relative rotation
 */
abstract class Tracker(
    protected val activity: MainActivity,
    protected val pipeline: VisionPipeline,
    val channels: Int,
    val angleChannel: Int,
    private val angleRangeTurns: Float,
    private val jumpLimitAngle: Float,
    val modelAsset: String
) {
    companion object {
        const val DEFAULT_SMOOTHING = 480  // ≈ 0.25 s
        private const val MIN_INTERVAL_MS = 50L      // max ~20 detections/s per tracker
        // Fade time when tracking is switched on/off (and on the first detection after switching on)
        private const val FADE_S = 0.8f
        // Spike filter: a detection that jumps further than this from the last accepted one (per ~50 ms)
        // is held back until the next detection confirms it (within CONFIRM_TOL)
        private const val JUMP_LIMIT = 0.3f
        private const val CONFIRM_TOL = 0.15f
        // Large remaining differences glide slower: k is scaled by 1 / (1 + (diff / ADAPT_DIFF)²)
        private const val ADAPT_DIFF = 0.3f

        /** Wrap into [-0.5, 0.5) turns. */
        fun wrapHalf(x: Float): Float = x - floor(x + 0.5f)
    }

    init { pipeline.trackers.add(this) }

    @Volatile var enabled = false; private set
    /** Smoothed channels 0..1. Hold their last values while nothing is visible. */
    val values = FloatArray(channels)
    private val targets = FloatArray(channels)  // latest accepted detection, written on the analysis thread
    private val candidate = FloatArray(channels)
    private val hasCandidate = BooleanArray(channels)
    private var lastSeen = false
    /** Tilt in turns, circular (0 = upright, + = clockwise), smoothed the short way around. */
    @Volatile var angleTurns = 0f; private set
    private var angleTarget = 0f
    /** Relative rotation for wrapping parameters: ±angleRange tilt → ±0.5. */
    val angleOffset: Float get() = angleTurns / (2f * angleRangeTurns)
    /** 0..1000 from Settings: 0 ≈ raw (0.02 s) .. 1000 = very slow (1 s). Quadratic for fine control at the low end. */
    @Volatile var smoothing = DEFAULT_SMOOTHING
    @Volatile var visible = false; private set
    /** 0..1 global weight of the modulation: eases in once the subject is first seen, out when switched off. */
    @Volatile var blend = 0f; private set
    private var blendLinear = 0f
    @Volatile private var hasData = false
    @Volatile private var snapPending = false
    @Volatile var imageWidth = 480; private set
    @Volatile var imageHeight = 640; private set
    var onUpdate: (() -> Unit)? = null

    private var detector: AutoCloseable? = null
    private var lastRunMs = 0L

    /** Per-channel factor on the smoothing time (below 1 = snappier than the Settings slider alone). */
    protected open fun smoothingScale(channel: Int): Float = 1f

    /** Build the MediaPipe task (on the pipeline thread). */
    protected abstract fun createDetector(): AutoCloseable
    /** Run detection; fill landmarks and call [accept] per channel. Returns whether the subject was found. */
    protected abstract fun runDetection(detector: AutoCloseable, bmp: Bitmap, timestampMs: Long): Boolean

    /** Loads the model off the UI thread, then calls [onDone] on the UI thread with success. */
    fun enable(onDone: (Boolean) -> Unit) {
        pipeline.executor.execute {
            val ok = try {
                if (detector == null) detector = createDetector()
                true
            } catch (e: Exception) {
                Log.e("Tracker", "Failed to load $modelAsset", e)
                false
            }
            hasData = false
            enabled = ok
            activity.runOnUiThread { onDone(ok); onUpdate?.invoke() }
        }
    }

    /** Load the model in the background ahead of time so switching on is instant. */
    fun preload() {
        try {
            pipeline.executor.execute {
                try { if (detector == null) detector = createDetector() } catch (e: Exception) { Log.w("Tracker", "Preload of $modelAsset failed", e) }
            }
        } catch (_: Exception) {}
    }

    /** The detector stays loaded so the next switch-on is instant; [release] frees it. */
    fun disable() {
        enabled = false
        visible = false
        onUpdate?.invoke()
    }

    fun release() {
        try { pipeline.executor.execute { detector?.close(); detector = null } } catch (_: Exception) {}
    }

    fun isDue(now: Long) = enabled && detector != null && now - lastRunMs >= MIN_INTERVAL_MS

    /** Pipeline thread. */
    fun process(bmp: Bitmap, now: Long) {
        val d = detector ?: return
        lastRunMs = now
        imageWidth = bmp.width; imageHeight = bmp.height
        val seen = try { runDetection(d, bmp, now) } catch (e: Exception) { Log.w("Tracker", "Detection failed", e); false }
        if (seen) {
            if (!hasData) { hasData = true; snapPending = true }
            lastSeen = true
            visible = true
        } else {
            lastSeen = false
            visible = false
        }
        onUpdate?.invoke()
    }

    /** Spike filter for one channel's new measurement (angle channel in turns, circular). */
    protected fun accept(i: Int, raw: Float) {
        val circular = i == angleChannel
        val current = if (circular) angleTarget else targets[i]
        fun dist(a: Float, b: Float) = if (circular) abs(wrapHalf(a - b)) else abs(a - b)
        val limit = if (circular) jumpLimitAngle else JUMP_LIMIT
        // Right after (re)acquiring the subject any position is legit; otherwise big jumps need a second opinion
        val ok = !lastSeen || dist(raw, current) <= limit || (hasCandidate[i] && dist(raw, candidate[i]) <= CONFIRM_TOL)
        if (ok) {
            if (circular) angleTarget = raw else targets[i] = raw
            hasCandidate[i] = false
        } else {
            candidate[i] = raw
            hasCandidate[i] = true
        }
    }

    /** Called once per rendered frame: glide [values] toward the latest detection and ease [blend]. */
    fun tick(deltaTime: Float) {
        if (snapPending) {
            snapPending = false
            if (blend <= 0f) {
                for (i in 0 until channels) values[i] = targets[i]
                angleTurns = angleTarget
            }
        }
        if (enabled) {
            val s = smoothing / 1000f
            val tau = 0.02f + s * s * 0.98f
            fun k(i: Int) = 1f - exp(-deltaTime / (tau * smoothingScale(i)))
            fun adapt(diff: Float): Float { val r = diff / ADAPT_DIFF; return 1f / (1f + r * r) }
            for (i in 0 until channels) {
                if (i == angleChannel) continue
                val d = targets[i] - values[i]
                values[i] += d * k(i) * adapt(abs(d))
            }
            val da = wrapHalf(angleTarget - angleTurns)
            angleTurns = wrapHalf(angleTurns + da * k(angleChannel) * adapt(abs(da) / (2f * angleRangeTurns)))
            values[angleChannel] = (0.5f + angleOffset).coerceIn(0f, 1f)
        }
        // After switching off, values hold their last state while the blend fades the modulation out
        val goal = if (enabled && hasData) 1f else 0f
        val step = deltaTime / FADE_S
        blendLinear = if (blendLinear < goal) minOf(goal, blendLinear + step) else maxOf(goal, blendLinear - step)
        blend = blendLinear * blendLinear * (3f - 2f * blendLinear)
    }
}
