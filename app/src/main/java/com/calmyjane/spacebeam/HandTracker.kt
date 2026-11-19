package com.calmyjane.spacebeam

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Hand tracking via MediaPipe Hand Landmarker (CPU, ~20 Hz on the shared analysis stream).
 * Channels: 4 thumb-to-finger distances, hand tilt, distance to camera.
 */
class HandTracker(activity: MainActivity, pipeline: VisionPipeline) :
    Tracker(activity, pipeline, CHANNELS, ANGLE, angleRangeTurns = 0.25f, jumpLimitAngle = 0.15f, modelAsset = MODEL_ASSET) {
    companion object {
        const val MODEL_ASSET = "hand_landmarker.task"
        const val CHANNELS = 6
        const val ANGLE = 4
        const val DIST = 5
        val FINGER_TIPS = intArrayOf(8, 12, 16, 20)
        const val THUMB_TIP = 4
        const val WRIST = 0
        const val MIDDLE_MCP = 9
        // Thumb-to-fingertip distance in world space (meters): touching / fully spread, per finger
        private const val PINCH_CLOSED_M = 0.02f
        private val PINCH_OPEN_M = floatArrayOf(0.12f, 0.13f, 0.13f, 0.14f)
        // Distance to camera from apparent palm size (fraction of the image's short side): close / far
        private const val PALM_SIZE_NEAR = 0.5f
        private const val PALM_SIZE_FAR = 0.08f
        // Gestures: fingertip-to-wrist distance relative to knuckle-to-wrist (world space, any hand rotation).
        // Straight finger ≈ 1.9, folded into the palm ≈ 1.0
        private const val FINGER_EXTENDED = 1.6f
        private const val FINGER_CURLED = 1.35f
        private const val THUMB_OUT = 1.35f
        const val THUMB_MCP = 2
        const val INDEX_MCP = 5
        const val PINKY_MCP = 17
        // Detections in a row (~20/s) before a gesture fires, other frames in a row before the same one can fire
        // again, min gap between any two
        private const val GESTURE_HOLD = 5
        private const val GESTURE_RELEASE = 3
        private const val GESTURE_LOST = 10  // ~0.5 s without a clear hand
        private const val GESTURE_COOLDOWN_MS = 1200L
        val FINGER_MCPS = intArrayOf(5, 9, 13, 17)

        // Landmark pairs forming the hand skeleton (MediaPipe hand topology)
        val CONNECTIONS = intArrayOf(
            0, 1, 1, 2, 2, 3, 3, 4,           // thumb
            0, 5, 5, 6, 6, 7, 7, 8,           // index
            5, 9, 9, 10, 10, 11, 11, 12,      // middle
            9, 13, 13, 14, 14, 15, 15, 16,    // ring
            13, 17, 0, 17, 17, 18, 18, 19, 19, 20  // pinky + palm
        )
    }

    /** 21 landmarks as x,y pairs, normalized to the upright (and mirrored for front cam) analysis image. */
    val landmarks = FloatArray(42)

    /**
     * A recognized [HandGesture] calls [onGesture] once per showing while [gestures] is on, on the analysis thread,
     * with the point between its raised fingertips (normalized like [landmarks]).
     */
    var onGesture: ((gesture: HandGesture, x: Float, y: Float) -> Unit)? = null
    private var heldGesture: HandGesture? = null
    private var heldFrames = 0
    private var latched: HandGesture? = null  // fired and still shown: must go away before it fires again
    private var latchFrames = 0
    private var unclearFrames = 0
    private var lastGestureMs = -GESTURE_COOLDOWN_MS
    private var lastFrameMs = 0L

    /**
     * Pipeline thread, once per detection. [clear] = a hand was seen with every finger clearly up or down; frames without
     * a hand or with a finger half bent don't count as "the gesture went away", so a held gesture that flickers in bad
     * light doesn't fire again. Only a clearly different hand, or no clear hand for [GESTURE_LOST] frames, re-arms it.
     */
    private fun updateGesture(g: HandGesture?, clear: Boolean, now: Long) {
        // Gestures off, or detection paused (switched off, app in background): start from scratch next time
        if (!gestures || now - lastFrameMs > 500L) {
            heldGesture = null; heldFrames = 0; latched = null; latchFrames = 0; unclearFrames = 0
        }
        lastFrameMs = now
        if (!gestures) return
        if (g == heldGesture) heldFrames++ else { heldGesture = g; heldFrames = 1 }
        if (latched != null) {
            if (g == latched) { latchFrames = 0; unclearFrames = 0 }
            else if (clear) { unclearFrames = 0; if (++latchFrames >= GESTURE_RELEASE) latched = null }
            else if (++unclearFrames >= GESTURE_LOST) latched = null
        }
        if (g == null || g == latched || heldFrames < GESTURE_HOLD || now - lastGestureMs < GESTURE_COOLDOWN_MS) return
        latched = g; latchFrames = 0; unclearFrames = 0
        lastGestureMs = now
        // Feedback point: between the raised fingertips (and the thumb tip when it is part of the gesture),
        // the middle knuckle for a fist
        val points = g.raised.map { FINGER_TIPS[it] } + (if (g.thumb != Thumb.ANY) listOf(THUMB_TIP) else emptyList())
        val at = points.ifEmpty { listOf(MIDDLE_MCP) }
        onGesture?.invoke(g, at.sumOf { landmarks[it * 2].toDouble() }.toFloat() / at.size,
            at.sumOf { landmarks[it * 2 + 1].toDouble() }.toFloat() / at.size)
    }

    /** Gestures with a command (set from the UI thread): only these are matched, so unused ones never block others. */
    @Volatile var activeGestures: Set<HandGesture> = emptySet()

    private fun dist(l: List<com.google.mediapipe.tasks.components.containers.Landmark>, a: Int, b: Int): Float {
        val dx = l[a].x() - l[b].x(); val dy = l[a].y() - l[b].y(); val dz = l[a].z() - l[b].z()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    override fun createDetector(): AutoCloseable {
        val opts = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .build()
        return HandLandmarker.createFromOptions(activity, opts)
    }

    override fun runDetection(detector: AutoCloseable, bmp: Bitmap, timestampMs: Long): Boolean {
        val result = (detector as HandLandmarker).detectForVideo(BitmapImageBuilder(bmp).build(), timestampMs)
        val hand = result.landmarks().firstOrNull()
        val world = result.worldLandmarks().firstOrNull()
        if (hand == null || world == null || hand.size < 21 || world.size < 21) { updateGesture(null, false, timestampMs); return false }
        for (i in 0 until 21) {
            landmarks[i * 2] = hand[i].x()
            landmarks[i * 2 + 1] = hand[i].y()
        }
        // World landmarks are in meters around the hand center: independent of distance to the camera
        val t = world[THUMB_TIP]
        for (f in 0 until 4) {
            val tip = world[FINGER_TIPS[f]]
            val dx = t.x() - tip.x(); val dy = t.y() - tip.y(); val dz = t.z() - tip.z()
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            accept(f, ((dist - PINCH_CLOSED_M) / (PINCH_OPEN_M[f] - PINCH_CLOSED_M)).coerceIn(0f, 1f))
        }
        // Finger extension per finger: costs a few float ops on the landmarks we already have
        val w = world[WRIST]
        fun fromWrist(i: Int): Float {
            val dx = world[i].x() - w.x(); val dy = world[i].y() - w.y(); val dz = world[i].z() - w.z()
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
        fun ext(f: Int): Float = fromWrist(FINGER_TIPS[f]) / maxOf(fromWrist(FINGER_MCPS[f]), 0.001f)
        // Each finger up, down or in between (in between matches no gesture)
        var up = 0; var down = 0
        for (f in 0 until 4) { val e = ext(f); if (e > FINGER_EXTENDED) up = up or (1 shl f) else if (e < FINGER_CURLED) down = down or (1 shl f) }
        // Thumb sticks out when its tip is far from the pinky knuckle compared to the knuckle span (folded across ≈ 0.7,
        // spread ≈ 1.7); up/down from its on-screen direction (thumb knuckle → tip, mostly vertical)
        val thumbOut = dist(world, THUMB_TIP, PINKY_MCP) / maxOf(dist(world, INDEX_MCP, PINKY_MCP), 0.001f) > THUMB_OUT
        val tdx = (hand[THUMB_TIP].x() - hand[THUMB_MCP].x()) * bmp.width
        val tdy = (hand[THUMB_TIP].y() - hand[THUMB_MCP].y()) * bmp.height
        val vertical = abs(tdy) > abs(tdx) * 1.2f
        fun thumbOk(t: Thumb) = when (t) {
            Thumb.ANY -> true
            Thumb.OUT -> thumbOut
            Thumb.UP -> thumbOut && vertical && tdy < 0f
            Thumb.DOWN -> thumbOut && vertical && tdy > 0f
        }
        val active = activeGestures
        val clear = (up or down) == 0xF
        val g = if (!clear) null
                else HandGesture.values().firstOrNull { it in active && it.mask == up && thumbOk(it.thumb) }
        updateGesture(g, clear, timestampMs)
        // Whole-hand tilt on screen: wrist → middle knuckle, in pixels (image space is upright + mirrored like the screen)
        val ax = (hand[MIDDLE_MCP].x() - hand[WRIST].x()) * bmp.width
        val ay = (hand[MIDDLE_MCP].y() - hand[WRIST].y()) * bmp.height
        accept(ANGLE, atan2(ax, -ay) / (2f * PI.toFloat()))  // turns: 0 = fingers up, + = tilted clockwise
        // Distance to camera from apparent palm size: wrist→middle knuckle and the knuckle span, whichever is
        // larger (less sensitive to tilting the hand). 1/size is proportional to distance.
        fun px(a: Int, b: Int): Float {
            val dx = (hand[a].x() - hand[b].x()) * bmp.width; val dy = (hand[a].y() - hand[b].y()) * bmp.height
            return sqrt(dx * dx + dy * dy)
        }
        val palm = maxOf(px(0, 9), px(5, 17) * 1.25f) / minOf(bmp.width, bmp.height)
        if (palm > 0.001f) {
            accept(DIST, ((1f / palm - 1f / PALM_SIZE_NEAR) / (1f / PALM_SIZE_FAR - 1f / PALM_SIZE_NEAR)).coerceIn(0f, 1f))
        }
        return true
    }
}

/** Hand gestures (raised fingers, 0 = index .. 3 = pinky; all others folded), with their Settings key and default command. */
enum class Thumb { ANY, OUT, UP, DOWN }

/**
 * Hand gestures: raised fingers (0 = index .. 3 = pinky; all others folded) plus the thumb. Detection tries them in
 * this order among the ones with a command, so the stricter thumb-out variants come before their thumb-any twins
 * (L before Point, thumbs up/down before Fist).
 */
enum class HandGesture(val label: String, val raised: IntArray, val thumb: Thumb, val defaultAction: String = "OFF") {
    PEACE("Peace  ✌", intArrayOf(0, 1), Thumb.ANY, "NEXT_RANDOM"),
    METAL("Metal  🤘", intArrayOf(0, 3), Thumb.ANY, "PRESET_1"),
    L_SHAPE("L  (thumb + index)", intArrayOf(0), Thumb.OUT),
    POINT("Point  ☝", intArrayOf(0), Thumb.ANY),
    THREE("Three fingers", intArrayOf(0, 1, 2), Thumb.ANY),
    OPEN("Open hand  ✋", intArrayOf(0, 1, 2, 3), Thumb.ANY),
    SHAKA("Shaka  🤙", intArrayOf(3), Thumb.OUT),
    THUMBS_UP("Thumbs up  👍", intArrayOf(), Thumb.UP),
    THUMBS_DOWN("Thumbs down  👎", intArrayOf(), Thumb.DOWN),
    FIST("Fist  ✊", intArrayOf(), Thumb.ANY);

    val mask: Int = raised.fold(0) { m, f -> m or (1 shl f) }
    val prefKey: String get() = "GESTURE_$name"
}

/** Commands a gesture can run, stored by key in the Settings prefs. */
object GestureActions {
    val ALL = listOf("OFF", "NEXT", "NEXT_ORDER", "NEXT_RANDOM", "PREVIOUS", "AUTOPLAY") + (1..9).map { "PRESET_$it" }

    fun label(action: String): String = when (action) {
        "OFF" -> "Nothing"
        "NEXT" -> "Next (Auto-Play order)"
        "NEXT_ORDER" -> "Next in order"
        "NEXT_RANDOM" -> "Next random"
        "PREVIOUS" -> "Previous"
        "AUTOPLAY" -> "Auto-Play on/off"
        else -> "Load preset " + action.removePrefix("PRESET_")
    }
}

/**
 * Phone-only overlay (part of the HUD, never in the GL output): faint hand skeleton / face outline,
 * colored lines for mapped measurements, and one bar per mapped channel placed clear of the HUD.
 */
class TrackingOverlayView(
    context: Context,
    private val activity: MainActivity,
    private val hand: HandTracker,
    private val face: FaceTracker,
    private val sensors: SensorHelper
) : View(context) {
    // Hand: index, middle, ring, pinky, angle, distance — face: rotation, distance, mouth
    private val handColors = intArrayOf(Color.rgb(0, 220, 255), Color.rgb(255, 80, 200), Color.rgb(255, 210, 0), Color.rgb(90, 255, 120), Color.rgb(255, 140, 0), Color.rgb(170, 120, 255))
    private val faceColors = intArrayOf(Color.rgb(255, 190, 110), Color.rgb(130, 180, 255), Color.rgb(255, 110, 120))
    // Sensors: pitch, roll, yaw, accel X, Y, Z
    private val sensorColors = intArrayOf(Color.rgb(240, 240, 240), Color.rgb(255, 230, 150), Color.rgb(150, 255, 230), Color.rgb(255, 150, 150), Color.rgb(150, 255, 150), Color.rgb(150, 150, 255))
    private val sensorMapped = BooleanArray(6)
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255); strokeWidth = 4f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255); style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 6f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0) }
    private val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); textSize = 30f; typeface = Typeface.DEFAULT_BOLD }
    private val pts = FloatArray(42)
    private val barRect = RectF()
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255); strokeWidth = 3f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val facePath = Path()
    private val handMapped = BooleanArray(HandTracker.CHANNELS)
    private val faceMapped = BooleanArray(FaceTracker.CHANNELS)

    // Gesture feedback: two thin rings ripple out from between the fingertips, the new preset number fades through
    private val burstPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
    private val burstTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 46f; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private var burstStart = 0L
    private var burstX = 0.5f
    private var burstY = 0.5f
    private var burstLabel = ""

    // HUD obstacle rects (in this view's coordinates), rebuilt per draw from a reused pool
    private val hudRects = ArrayList<RectF>()
    private val rectPool = ArrayList<RectF>()
    private val loc = IntArray(2)
    private val myLoc = IntArray(2)
    private val spotRect = RectF()
    // The HUD rarely moves: reuse the last free spot for a moment instead of walking all HUD views every draw
    private var spotTime = 0L
    private var spotW = -1f
    private var spotH = -1f
    // One pending refresh at most while sensor bars are shown (each draw must not start its own timer chain)
    private var refreshScheduled = false
    private val refresh = Runnable { refreshScheduled = false; invalidate() }

    init {
        isClickable = false
        isFocusable = false
    }

    private fun collectHudRects(v: View) {
        if (v === this || v.visibility != VISIBLE || v.alpha < 0.05f || v.width == 0 || v.height == 0) return
        // Full-screen layers (flash, dimmed dialogs) aren't obstacles to avoid
        if (v.width >= width * 0.9f && v.height >= height * 0.9f) {
            if (v is ViewGroup && v.background == null) for (i in 0 until v.childCount) collectHudRects(v.getChildAt(i))
            return
        }
        // Background-less containers are just layout: only their content counts
        if (v is ViewGroup && v.background == null) {
            for (i in 0 until v.childCount) collectHudRects(v.getChildAt(i))
            return
        }
        v.getLocationInWindow(loc)
        val r = if (hudRects.size < rectPool.size) rectPool[hudRects.size] else RectF().also { rectPool.add(it) }
        val x = (loc[0] - myLoc[0]).toFloat(); val y = (loc[1] - myLoc[1]).toFloat()
        r.set(x, y, x + v.width, y + v.height)
        hudRects.add(r)
    }

    /** Top-left of a free area of size w x h: top center, bottom center, the corners, then a scan. */
    private fun findFreeSpot(w: Float, h: Float): RectF {
        val now = android.os.SystemClock.uptimeMillis()
        if (w == spotW && h == spotH && now - spotTime < 400L) return spotRect
        spotW = w; spotH = h; spotTime = now
        val margin = 16f
        hudRects.clear()
        getLocationInWindow(myLoc)
        (parent as? ViewGroup)?.let { p -> for (i in 0 until p.childCount) collectHudRects(p.getChildAt(i)) }

        fun free(x: Float, y: Float): Boolean {
            if (x < margin || y < margin || x + w > width - margin || y + h > height - margin) return false
            for (r in hudRects) {
                if (x - margin < r.right && x + w + margin > r.left && y - margin < r.bottom && y + h + margin > r.top) return false
            }
            return true
        }

        val vw = width.toFloat(); val vh = height.toFloat()
        val cx = (vw - w) / 2f
        val candidates = floatArrayOf(
            cx, 24f,  cx, vh - h - 24f,
            vw * 0.25f - w / 2f, 24f,  vw * 0.75f - w / 2f, 24f,
            vw * 0.25f - w / 2f, vh - h - 24f,  vw * 0.75f - w / 2f, vh - h - 24f
        )
        var i = 0
        while (i < candidates.size) {
            if (free(candidates[i], candidates[i + 1])) return spotRect.apply { set(candidates[i], candidates[i + 1], candidates[i] + w, candidates[i + 1] + h) }
            i += 2
        }
        // Scan: center column first, then the quarter columns, top to bottom
        for (x in floatArrayOf(cx, vw * 0.25f - w / 2f, vw * 0.75f - w / 2f)) {
            var y = 24f
            while (y + h < vh - 24f) {
                if (free(x, y)) return spotRect.apply { set(x, y, x + w, y + h) }
                y += 24f
            }
        }
        return spotRect.apply { set(cx, 24f, cx + w, 24f + h) }  // fully covered screen: fall back to top center
    }

    /** Map a normalized analysis-image point onto the view (center-crop, like the camera fills the screen). */
    private fun mapX(t: Tracker, x: Float): Float { val s = maxOf(width / t.imageWidth.toFloat(), height / t.imageHeight.toFloat()); return width / 2f + (x - 0.5f) * t.imageWidth * s }
    private fun mapY(t: Tracker, y: Float): Float { val s = maxOf(width / t.imageWidth.toFloat(), height / t.imageHeight.toFloat()); return height / 2f + (y - 0.5f) * t.imageHeight * s }

    /** Gesture feedback at a point in hand landmark coordinates, with what it did (e.g. the new preset number). UI thread. */
    fun gestureBurst(nx: Float, ny: Float, label: String) {
        burstX = nx; burstY = ny; burstLabel = label
        burstStart = android.os.SystemClock.uptimeMillis()
        invalidate()
    }

    private fun drawBurst(canvas: Canvas) {
        val t = (android.os.SystemClock.uptimeMillis() - burstStart) / 700f
        if (t >= 1f) { burstStart = 0L; return }
        val x = mapX(hand, burstX); val y = mapY(hand, burstY)
        // Second ring trails the first a little; each eases out (fast start, soft stop) while thinning and fading
        for (k in 0 until 2) {
            val p = ((t - k * 0.16f) / 0.84f).coerceIn(0f, 1f)
            if (p <= 0f || p >= 1f) continue
            val q = 1f - p
            val e = 1f - q * q * q
            burstPaint.strokeWidth = 1.5f + 4f * q
            burstPaint.alpha = ((if (k == 0) 220 else 130) * q).toInt()
            canvas.drawCircle(x, y, 24f + 150f * e, burstPaint)
        }
        if (burstLabel.isNotEmpty()) {
            val a = if (t < 0.12f) t / 0.12f else ((1f - t) / 0.88f)
            burstTextPaint.alpha = (230 * a).toInt().coerceIn(0, 255)
            canvas.drawText(burstLabel, x, y + burstTextPaint.textSize * 0.35f - 18f * t, burstTextPaint)
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (burstStart != 0L) drawBurst(canvas)
        if (!hand.enabled && !face.enabled && !sensors.enabled) return
        // Sensors change continuously without a detection callback: keep refreshing (~20 Hz) while they are shown
        if (sensors.enabled && !refreshScheduled) { refreshScheduled = true; postDelayed(refresh, 50) }

        // Which channels anything is mapped to — unmapped ones stay hidden
        handMapped.fill(false); faceMapped.fill(false); sensorMapped.fill(false)
        for (c in activity.controls) {
            for (f in 0 until 6) if (c.sensorAmount(f) != 500) sensorMapped[f] = true
            for (f in 0 until HandTracker.CHANNELS) if (c.handAmount[f] != 0) handMapped[f] = true
            for (f in 0 until FaceTracker.CHANNELS) if (c.faceAmount[f] != 0) faceMapped[f] = true
        }
        val handActive = hand.enabled && handMapped.any { it }
        val faceActive = face.enabled && faceMapped.any { it }
        val sensorsActive = sensors.enabled && sensorMapped.any { it }

        if (handActive && hand.visible) drawHand(canvas)
        if (faceActive && face.visible) drawFace(canvas)

        // Trackers that are on but drive nothing get a short hint instead of bars
        val hints = buildList {
            if (hand.enabled && !handActive) add("HAND ON  ·  nothing mapped (parameter menu → HAND)")
            if (face.enabled && !faceActive) add("FACE ON  ·  nothing mapped (parameter menu → FACE)")
            if (sensors.enabled && !sensorsActive) add("GYRO ON  ·  nothing mapped (parameter menu → GYRO)")
        }

        // Bars: hand channels, a small gap, then face channels — one left-aligned column, clear of the HUD
        val barW = 260f; val barH = 12f; val rowH = 22f; val groupGap = 10f; val hintH = 40f
        val handRows = if (handActive) handMapped.count { it } else 0
        val faceRows = if (faceActive) faceMapped.count { it } else 0
        val sensorRows = if (sensorsActive) sensorMapped.count { it } else 0
        val groups = listOf(handRows, faceRows, sensorRows).count { it > 0 }
        var totalH = (handRows + faceRows + sensorRows) * rowH + hints.size * hintH
        if (groups > 1) totalH += groupGap * (groups - 1)
        val blockW = maxOf(barW, hints.maxOfOrNull { textPaint.measureText(it) } ?: 0f)
        val spot = findFreeSpot(blockW, totalH)
        val left = spot.left
        var top = spot.top

        fun bars(t: Tracker, mapped: BooleanArray, colors: IntArray) {
            val alpha = if (t.visible) 200 else 90  // dimmed while nothing is seen
            for (f in mapped.indices) {
                if (!mapped[f]) continue
                barRect.set(left, top, left + barW, top + barH)
                canvas.drawRoundRect(barRect, 6f, 6f, barBgPaint)
                barFillPaint.color = colors[f]; barFillPaint.alpha = alpha
                barRect.set(left, top, left + barW * t.values[f], top + barH)
                canvas.drawRoundRect(barRect, 6f, 6f, barFillPaint)
                top += rowH
            }
        }
        if (handActive) { bars(hand, handMapped, handColors); top += groupGap }
        if (faceActive) { bars(face, faceMapped, faceColors); top += groupGap }
        if (sensorsActive) {
            // Sensor channels are bipolar (-1..1 around the neutral position): bars grow from the center
            val mid = left + barW / 2f
            for (f in 0 until 6) {
                if (!sensorMapped[f]) continue
                barRect.set(left, top, left + barW, top + barH)
                canvas.drawRoundRect(barRect, 6f, 6f, barBgPaint)
                val end = mid + sensors.relative(f).coerceIn(-1f, 1f) * barW / 2f
                barFillPaint.color = sensorColors[f]; barFillPaint.alpha = 200
                barRect.set(minOf(mid, end), top, maxOf(mid, end), top + barH)
                canvas.drawRoundRect(barRect, 6f, 6f, barFillPaint)
                top += rowH
            }
            top += groupGap
        }
        textPaint.alpha = 110
        for (hint in hints) {
            canvas.drawText(hint, left, top + textPaint.textSize * 0.8f, textPaint)
            top += hintH
        }
    }

    private fun drawHand(canvas: Canvas) {
        for (i in 0 until 21) {
            pts[i * 2] = mapX(hand, hand.landmarks[i * 2])
            pts[i * 2 + 1] = mapY(hand, hand.landmarks[i * 2 + 1])
        }
        val c = HandTracker.CONNECTIONS
        var k = 0
        while (k < c.size) {
            val a = c[k]; val b = c[k + 1]
            canvas.drawLine(pts[a * 2], pts[a * 2 + 1], pts[b * 2], pts[b * 2 + 1], bonePaint)
            k += 2
        }
        for (i in 0 until 21) canvas.drawCircle(pts[i * 2], pts[i * 2 + 1], 6f, jointPaint)
        // Thumb tip to each mapped fingertip, in the finger's color
        val tx = pts[HandTracker.THUMB_TIP * 2]; val ty = pts[HandTracker.THUMB_TIP * 2 + 1]
        for (f in 0 until 4) {
            if (!handMapped[f]) continue
            val tip = HandTracker.FINGER_TIPS[f]
            linePaint.color = handColors[f]; linePaint.alpha = 200
            canvas.drawLine(tx, ty, pts[tip * 2], pts[tip * 2 + 1], linePaint)
        }
        // Angle: the palm axis it is measured on, wrist → middle knuckle (distance has no line, only its bar)
        if (handMapped[HandTracker.ANGLE]) {
            val w0 = HandTracker.WRIST * 2; val m9 = HandTracker.MIDDLE_MCP * 2
            linePaint.color = handColors[HandTracker.ANGLE]; linePaint.alpha = 200
            canvas.drawLine(pts[w0], pts[w0 + 1], pts[m9], pts[m9 + 1], linePaint)
        }
    }

    private fun drawFace(canvas: Canvas) {
        val p = face.points  // x,y pairs in FaceTracker.POINTS order
        fun x(i: Int) = mapX(face, p[i * 2])
        fun y(i: Int) = mapY(face, p[i * 2 + 1])
        // Faint line drawing of the face: outline, eyes, lips, eyebrows, nose
        val m = face.mesh
        facePath.rewind()
        fun contour(idx: IntArray, closed: Boolean) {
            facePath.moveTo(mapX(face, m[idx[0] * 2]), mapY(face, m[idx[0] * 2 + 1]))
            for (k in 1 until idx.size) facePath.lineTo(mapX(face, m[idx[k] * 2]), mapY(face, m[idx[k] * 2 + 1]))
            if (closed) facePath.close()
        }
        for (c in FaceTracker.CLOSED_CONTOURS) contour(c, true)
        for (c in FaceTracker.OPEN_CONTOURS) contour(c, false)
        canvas.drawPath(facePath, facePaint)
        // Only the mouth width gets a line (corner to corner); rotation and distance show as bars only —
        // a line on the eyes (where rotation is measured) reads as "eye distance"
        if (faceMapped[FaceTracker.MOUTH]) {
            linePaint.color = faceColors[FaceTracker.MOUTH]; linePaint.alpha = 200
            canvas.drawLine(x(FaceTracker.P_MOUTH_L), y(FaceTracker.P_MOUTH_L), x(FaceTracker.P_MOUTH_R), y(FaceTracker.P_MOUTH_R), linePaint)
        }
    }
}

/** Button icon: open hand (palm, four fingers, thumb). */
class HandIconDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val palm = RectF()

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        val ox = bounds.left.toFloat(); val oy = bounds.top.toFloat()
        paint.style = Paint.Style.FILL
        palm.set(ox + w * 0.30f, oy + h * 0.50f, ox + w * 0.74f, oy + h * 0.92f)
        canvas.drawRoundRect(palm, w * 0.12f, w * 0.12f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = w * 0.095f
        // Four fingers: x position, tip height
        val fx = floatArrayOf(0.355f, 0.465f, 0.575f, 0.685f)
        val tipY = floatArrayOf(0.20f, 0.10f, 0.14f, 0.26f)
        for (i in 0 until 4) canvas.drawLine(ox + w * fx[i], oy + h * 0.58f, ox + w * fx[i], oy + h * tipY[i], paint)
        // Thumb, angled out to the side
        canvas.drawLine(ox + w * 0.36f, oy + h * 0.74f, ox + w * 0.14f, oy + h * 0.50f, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = 90
    override fun getIntrinsicHeight(): Int = 90
}

/** Button icon: peace sign (palm with two fingers spread in a V). */
class PeaceIconDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val palm = RectF()

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        val ox = bounds.left.toFloat(); val oy = bounds.top.toFloat()
        paint.style = Paint.Style.FILL
        palm.set(ox + w * 0.30f, oy + h * 0.52f, ox + w * 0.72f, oy + h * 0.92f)
        canvas.drawRoundRect(palm, w * 0.13f, w * 0.13f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = w * 0.10f
        canvas.drawLine(ox + w * 0.43f, oy + h * 0.60f, ox + w * 0.30f, oy + h * 0.12f, paint)
        canvas.drawLine(ox + w * 0.57f, oy + h * 0.60f, ox + w * 0.70f, oy + h * 0.12f, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = 90
    override fun getIntrinsicHeight(): Int = 90
}

/** Button icon: tilted phone with rotation arcs. */
class GyroIconDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val r = RectF()

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        val cx = bounds.left + w / 2f; val cy = bounds.top + h / 2f
        paint.strokeWidth = w * 0.06f
        canvas.save()
        canvas.rotate(-25f, cx, cy)
        r.set(cx - w * 0.16f, cy - h * 0.28f, cx + w * 0.16f, cy + h * 0.28f)
        canvas.drawRoundRect(r, w * 0.05f, w * 0.05f, paint)
        canvas.restore()
        r.set(cx - w * 0.42f, cy - h * 0.42f, cx + w * 0.42f, cy + h * 0.42f)
        canvas.drawArc(r, -60f, 70f, false, paint)
        canvas.drawArc(r, 120f, 70f, false, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = 90
    override fun getIntrinsicHeight(): Int = 90
}

/** Button icon: two beamed eighth notes (beat-synced LFOs). */
class BeatIconDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val oval = RectF()

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        val ox = bounds.left.toFloat(); val oy = bounds.top.toFloat()
        // Note heads: tilted ovals
        paint.style = Paint.Style.FILL
        for (x in floatArrayOf(0.30f, 0.66f)) {
            canvas.save()
            canvas.rotate(-20f, ox + w * x, oy + h * 0.74f)
            oval.set(ox + w * (x - 0.12f), oy + h * 0.66f, ox + w * (x + 0.12f), oy + h * 0.82f)
            canvas.drawOval(oval, paint)
            canvas.restore()
        }
        // Stems and the beam joining them
        paint.style = Paint.Style.STROKE; paint.strokeWidth = w * 0.06f
        canvas.drawLine(ox + w * 0.40f, oy + h * 0.72f, ox + w * 0.40f, oy + h * 0.22f, paint)
        canvas.drawLine(ox + w * 0.76f, oy + h * 0.72f, ox + w * 0.76f, oy + h * 0.16f, paint)
        paint.strokeWidth = w * 0.11f
        canvas.drawLine(ox + w * 0.40f, oy + h * 0.24f, ox + w * 0.76f, oy + h * 0.18f, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = 90
    override fun getIntrinsicHeight(): Int = 90
}

/** Button icon: face outline with eyes and mouth. */
class FaceIconDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val oval = RectF()

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        val ox = bounds.left.toFloat(); val oy = bounds.top.toFloat()
        paint.style = Paint.Style.STROKE; paint.strokeWidth = w * 0.06f
        oval.set(ox + w * 0.22f, oy + h * 0.12f, ox + w * 0.78f, oy + h * 0.88f)
        canvas.drawOval(oval, paint)
        canvas.drawLine(ox + w * 0.40f, oy + h * 0.66f, ox + w * 0.60f, oy + h * 0.66f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(ox + w * 0.40f, oy + h * 0.42f, w * 0.05f, paint)
        canvas.drawCircle(ox + w * 0.60f, oy + h * 0.42f, w * 0.05f, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = 90
    override fun getIntrinsicHeight(): Int = 90
}
