package com.example.personcounter

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Orchestrates three detection pipelines on each camera frame:
 *
 *  1. **Face Detection** — ML Kit FaceDetector (multi-face, fast mode)
 *  2. **Person Detection** — EfficientDet-Lite0 TFLite
 *  3. **Raised Hand Detection** — ML Kit PoseDetector run on each person crop
 *
 * All three run sequentially on the caller's thread (typically the single
 * cameraExecutor background thread).  ML Kit calls are async internally, but
 * we block with a [CountDownLatch] so the combined result is available before
 * returning.
 */
class DetectionHelper(
    private val context: android.content.Context,
    threshold: Float = SettingsManager.DEFAULT_THRESHOLD,
    private val listener: DetectorListener
) {

    // Live threshold — updated from settings without rebuilding detectors
    @Volatile var threshold: Float = threshold
        set(value) { field = value.coerceIn(0.1f, 0.95f) }

    // -------------------------------------------------------------------------
    // ML detectors
    // -------------------------------------------------------------------------

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.10f)   // ignore faces smaller than 10 % of frame width
            .build()
    )

    private val poseDetector = PoseDetection.getClient(
        AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build()
    )

    private var objectDetector: ObjectDetector? = null

    // -------------------------------------------------------------------------
    // Init
    // -------------------------------------------------------------------------

    /** Call once on the background thread before the first [detect] invocation. */
    fun setup() {
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setNumThreads(2).build())
            .setMaxResults(10)
            .setScoreThreshold(threshold)
            .build()
        try {
            objectDetector = ObjectDetector.createFromFileAndOptions(
                context, MODEL_FILE, options
            )
            Log.d(TAG, "ObjectDetector ready.")
        } catch (e: Exception) {
            Log.e(TAG, "ObjectDetector init failed: ${e.message}", e)
            listener.onError("Detector init failed: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Main detection entry point
    // -------------------------------------------------------------------------

    /**
     * Process one [bitmap] frame (already rotation-corrected, ARGB_8888).
     * Blocks until all three pipelines finish, then fires [DetectorListener.onResults].
     */
    fun detect(bitmap: Bitmap) {
        val startMs = SystemClock.uptimeMillis()

        // --- 1. Face detection -----------------------------------------------
        val faces = runFaceDetection(bitmap)

        // --- 2. Person detection (TFLite) -------------------------------------
        val persons = runPersonDetection(bitmap)

        // --- 3. Raised hand detection (pose per person crop) ------------------
        val raisedHandBoxes = runRaisedHandDetection(bitmap, persons)

        val inferenceMs = SystemClock.uptimeMillis() - startMs

        listener.onResults(
            DetectionResult(
                faceBoxes        = faces.map { it.boundingBox.toRectF() },
                personBoxes      = persons.map { it.boundingBox },
                raisedHandBoxes  = raisedHandBoxes,
                inferenceMs      = inferenceMs,
                imageWidth       = bitmap.width,
                imageHeight      = bitmap.height
            )
        )
    }

    // -------------------------------------------------------------------------
    // Pipeline steps
    // -------------------------------------------------------------------------

    private fun runFaceDetection(bitmap: Bitmap): List<Face> {
        val latch  = CountDownLatch(1)
        val result = mutableListOf<Face>()
        val image  = InputImage.fromBitmap(bitmap, 0)
        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                // Filter by minimum confidence proxy: face size relative to frame
                result.addAll(faces.filter { face ->
                    val faceW = face.boundingBox.width().toFloat()
                    faceW / bitmap.width >= 0.05f   // at least 5 % of frame width
                })
                latch.countDown()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Face detection failed: ${e.message}")
                latch.countDown()
            }
        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result
    }

    private fun runPersonDetection(bitmap: Bitmap): List<Detection> {
        val detector = objectDetector ?: return emptyList()
        // Re-create detector with updated threshold if it changed since setup()
        val tensorImage: TensorImage = TensorImage.fromBitmap(bitmap)
        return try {
            detector.detect(tensorImage)
                .filter { it.categories.any { c -> c.label.equals("person", true) && c.score >= threshold } }
        } catch (e: Exception) {
            Log.w(TAG, "Person detection failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * For each detected person, crop the bitmap and run pose detection.
     * Returns the bounding boxes of persons where at least one hand is raised.
     * Capped at [MAX_POSE_PERSONS] to keep latency reasonable.
     */
    private fun runRaisedHandDetection(bitmap: Bitmap, persons: List<Detection>): List<RectF> {
        val raised = mutableListOf<RectF>()
        val bitmapW = bitmap.width.toFloat()
        val bitmapH = bitmap.height.toFloat()

        for (person in persons.take(MAX_POSE_PERSONS)) {
            val box = person.boundingBox
            // Clamp crop to bitmap bounds
            val left   = box.left.coerceIn(0f, bitmapW - 1)
            val top    = box.top.coerceIn(0f, bitmapH - 1)
            val right  = box.right.coerceIn(left + 1, bitmapW)
            val bottom = box.bottom.coerceIn(top + 1, bitmapH)

            val cropW = (right - left).toInt()
            val cropH = (bottom - top).toInt()
            if (cropW < MIN_CROP_PX || cropH < MIN_CROP_PX) continue

            val crop = Bitmap.createBitmap(bitmap, left.toInt(), top.toInt(), cropW, cropH)
            val pose = runSinglePose(crop) ?: continue

            if (isHandRaised(pose)) {
                raised.add(RectF(left, top, right, bottom))
            }
        }
        return raised
    }

    private fun runSinglePose(crop: Bitmap): Pose? {
        val latch = CountDownLatch(1)
        var pose: Pose? = null
        val image = InputImage.fromBitmap(crop, 0)
        poseDetector.process(image)
            .addOnSuccessListener { p -> pose = p; latch.countDown() }
            .addOnFailureListener { latch.countDown() }
        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return pose
    }

    /**
     * A hand is raised if either wrist landmark is above (lower Y value in image
     * coordinates) the corresponding shoulder, and both landmarks have sufficient
     * in-frame likelihood.
     */
    private fun isHandRaised(pose: Pose): Boolean {
        val minLikelihood = 0.5f

        fun isAbove(wristType: Int, shoulderType: Int): Boolean {
            val wrist    = pose.getPoseLandmark(wristType)    ?: return false
            val shoulder = pose.getPoseLandmark(shoulderType) ?: return false
            if (wrist.inFrameLikelihood < minLikelihood) return false
            if (shoulder.inFrameLikelihood < minLikelihood) return false
            return wrist.position.y < shoulder.position.y   // y increases downward
        }

        return isAbove(PoseLandmark.LEFT_WRIST,  PoseLandmark.LEFT_SHOULDER) ||
               isAbove(PoseLandmark.RIGHT_WRIST, PoseLandmark.RIGHT_SHOULDER)
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    fun release() {
        objectDetector?.close()
        objectDetector = null
        faceDetector.close()
        poseDetector.close()
    }

    // -------------------------------------------------------------------------
    // Data classes & interface
    // -------------------------------------------------------------------------

    data class DetectionResult(
        val faceBoxes:       List<RectF>,   // face bounding boxes (image coords)
        val personBoxes:     List<RectF>,   // person bounding boxes (image coords)
        val raisedHandBoxes: List<RectF>,   // person boxes where hand is raised
        val inferenceMs:     Long,
        val imageWidth:      Int,
        val imageHeight:     Int
    ) {
        val faceCount:       Int get() = faceBoxes.size
        val personCount:     Int get() = personBoxes.size
        val raisedHandCount: Int get() = raisedHandBoxes.size
    }

    interface DetectorListener {
        fun onError(error: String)
        fun onResults(result: DetectionResult)
    }

    companion object {
        private const val TAG            = "DetectionHelper"
        private const val MODEL_FILE     = "efficientdet_lite0.tflite"
        private const val TIMEOUT_MS     = 500L   // max wait per ML Kit call
        private const val MAX_POSE_PERSONS = 5    // cap pose detection per frame
        private const val MIN_CROP_PX    = 40     // skip very small person crops
    }
}

// Extension: android.graphics.Rect → RectF
private fun android.graphics.Rect.toRectF() =
    RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
