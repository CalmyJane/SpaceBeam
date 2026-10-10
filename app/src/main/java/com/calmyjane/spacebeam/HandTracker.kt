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
        if (hand == null || world == null || hand.size < 21 || world.size < 21) return false
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

    // HUD obstacle rects (in this view's coordinates), rebuilt per draw from a reused pool
    private val hudRects = ArrayList<RectF>()
    private val rectPool = ArrayList<RectF>()
    private val loc = IntArray(2)
    private val myLoc = IntArray(2)
    private val spotRect = RectF()

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

    override fun onDraw(canvas: Canvas) {
        if (!hand.enabled && !face.enabled && !sensors.enabled) return
        // Sensors change continuously without a detection callback: keep refreshing while they are shown
        if (sensors.enabled) postInvalidateDelayed(50)

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

/** (Unused) earlier hand button icon: two fingertips with the distance line between them. */
class PinchIconDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        val ox = bounds.left.toFloat(); val oy = bounds.top.toFloat()
        val ax = ox + w * 0.28f; val ay = oy + h * 0.70f
        val bx = ox + w * 0.72f; val by = oy + h * 0.30f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = w * 0.05f
        paint.pathEffect = DashPathEffect(floatArrayOf(w * 0.06f, w * 0.06f), 0f)
        canvas.drawLine(ax, ay, bx, by, paint)
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        canvas.drawCircle(ax, ay, w * 0.11f, paint)
        canvas.drawCircle(bx, by, w * 0.11f, paint)
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
