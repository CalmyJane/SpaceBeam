package com.calmyjane.spacebeam

import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Face tracking via MediaPipe Face Landmarker (CPU, ~20 Hz on the shared analysis stream).
 * Channels: head rotation (roll, on the eye line), distance to camera (face size), mouth width.
 */
class FaceTracker(activity: MainActivity, pipeline: VisionPipeline) :
    Tracker(activity, pipeline, CHANNELS, ROT, angleRangeTurns = 0.125f, jumpLimitAngle = 0.08f, modelAsset = MODEL_ASSET) {
    companion object {
        const val MODEL_ASSET = "face_landmarker.task"
        const val CHANNELS = 3
        const val ROT = 0
        const val DIST = 1
        const val MOUTH = 2

        // Face mesh landmarks used (MediaPipe 468-point topology), stored in this order in [points]
        private val POINTS = intArrayOf(33, 263, 61, 291, 234, 454, 10, 152)
        const val P_EYE_L = 0; const val P_EYE_R = 1      // outer eye corners
        const val P_MOUTH_L = 2; const val P_MOUTH_R = 3  // mouth corners
        const val P_LEFT = 4; const val P_RIGHT = 5       // cheek edges (face width)
        const val P_TOP = 6; const val P_CHIN = 7         // forehead top / chin

        // Feature outlines for the overlay (MediaPipe face mesh contour indices)
        val CLOSED_CONTOURS = arrayOf(
            // face oval
            intArrayOf(10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109),
            // eyes
            intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246),
            intArrayOf(263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466),
            // lips, outer and inner
            intArrayOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185),
            intArrayOf(78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191)
        )
        val OPEN_CONTOURS = arrayOf(
            // eyebrows
            intArrayOf(70, 63, 105, 66, 107),
            intArrayOf(336, 296, 334, 293, 300),
            // nose bridge to tip, nostril base
            intArrayOf(168, 6, 197, 195, 5, 4),
            intArrayOf(98, 97, 2, 326, 327)
        )
        const val MESH_POINTS = 468

        // Distance from face width (fraction of the image's short side): close / far
        private const val FACE_SIZE_NEAR = 0.7f
        private const val FACE_SIZE_FAR = 0.12f
        // Mouth width relative to face width: pursed / wide smile
        private const val MOUTH_NARROW = 0.32f
        private const val MOUTH_WIDE = 0.52f
    }

    /** The landmarks listed in POINTS as x,y pairs, normalized to the upright (mirrored for front cam) image. */
    val points = FloatArray(POINTS.size * 2)
    /** All mesh landmarks as x,y pairs (same space), for drawing the feature outlines. */
    val mesh = FloatArray(MESH_POINTS * 2)

    // Mouth moves fast and in small steps: about a third of the smoothing time of rotation/distance
    override fun smoothingScale(channel: Int) = if (channel == MOUTH) 0.35f else 1f

    override fun createDetector(): AutoCloseable {
        val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        return FaceLandmarker.createFromOptions(activity, opts)
    }

    override fun runDetection(detector: AutoCloseable, bmp: Bitmap, timestampMs: Long): Boolean {
        val result = (detector as FaceLandmarker).detectForVideo(BitmapImageBuilder(bmp).build(), timestampMs)
        val lm = result.faceLandmarks().firstOrNull() ?: return false
        if (lm.size < MESH_POINTS) return false
        for (i in 0 until MESH_POINTS) {
            mesh[i * 2] = lm[i].x()
            mesh[i * 2 + 1] = lm[i].y()
        }
        for (i in POINTS.indices) {
            points[i * 2] = lm[POINTS[i]].x()
            points[i * 2 + 1] = lm[POINTS[i]].y()
        }
        fun px(a: Int, b: Int): Float {
            val dx = (points[a * 2] - points[b * 2]) * bmp.width; val dy = (points[a * 2 + 1] - points[b * 2 + 1]) * bmp.height
            return sqrt(dx * dx + dy * dy)
        }

        // Head roll on screen from the eye line; oriented left→right so mirroring doesn't flip it by 180°
        var ex = (points[P_EYE_R * 2] - points[P_EYE_L * 2]) * bmp.width
        var ey = (points[P_EYE_R * 2 + 1] - points[P_EYE_L * 2 + 1]) * bmp.height
        if (ex < 0f) { ex = -ex; ey = -ey }
        accept(ROT, atan2(ey, ex) / (2f * PI.toFloat()))  // turns: 0 = level, + = tilted clockwise

        // Distance to camera: 1/apparent face width is proportional to distance
        val faceW = px(P_LEFT, P_RIGHT)
        val size = faceW / minOf(bmp.width, bmp.height)
        if (size > 0.001f) {
            accept(DIST, ((1f / size - 1f / FACE_SIZE_NEAR) / (1f / FACE_SIZE_FAR - 1f / FACE_SIZE_NEAR)).coerceIn(0f, 1f))
        }

        // Mouth width relative to face width (independent of distance)
        if (faceW > 1f) {
            accept(MOUTH, ((px(P_MOUTH_L, P_MOUTH_R) / faceW - MOUTH_NARROW) / (MOUTH_WIDE - MOUTH_NARROW)).coerceIn(0f, 1f))
        }
        return true
    }
}
