package com.example.personcounter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector

/**
 * Wraps TensorFlow Lite's [ObjectDetector] Task API.
 *
 * Responsibilities:
 *  - Load the EfficientDet-Lite0 COCO model from assets/
 *  - Accept raw [Bitmap] frames from CameraX ImageAnalysis
 *  - Apply rotation correction before inference
 *  - Filter results to "person" class only
 *  - Report results (or errors) via [DetectorListener]
 *
 * Threading: [setupObjectDetector] and [detect] must both be called
 * on the same single background thread (the CameraX cameraExecutor).
 * No synchronisation is needed because of this design.
 */
class ObjectDetectorHelper(
    private val context: Context,
    /** Minimum confidence score to keep a detection (0.0 – 1.0). */
    private val threshold: Float = 0.5f,
    /** Maximum number of objects the model may return per frame. */
    private val maxResults: Int = 10,
    /** Number of CPU threads used for inference. */
    private val numThreads: Int = 2,
    private val listener: DetectorListener
) {

    private var objectDetector: ObjectDetector? = null

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    /**
     * Creates and configures the [ObjectDetector].
     * Call once on the background thread before the first [detect] call.
     */
    fun setupObjectDetector() {
        val baseOptions = BaseOptions.builder()
            .setNumThreads(numThreads)
            .build()

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setMaxResults(maxResults)
            .setScoreThreshold(threshold)
            .build()

        try {
            // createFromFileAndOptions reads MODEL_FILE from the assets/ folder automatically.
            objectDetector = ObjectDetector.createFromFileAndOptions(
                context,
                MODEL_FILE,
                options
            )
            Log.d(TAG, "ObjectDetector initialised successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "ObjectDetector initialisation failed: ${e.message}", e)
            listener.onError("Failed to initialise detector: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Inference
    // -------------------------------------------------------------------------

    /**
     * Run person detection on a single [bitmap] frame.
     *
     * @param bitmap              Raw ARGB_8888 bitmap from CameraX (sensor orientation).
     * @param imageRotationDegrees CW rotation needed to make the image display-upright,
     *                             supplied by [ImageProxy.imageInfo.rotationDegrees].
     *
     * Called on the background cameraExecutor thread.
     */
    fun detect(bitmap: Bitmap, imageRotationDegrees: Int) {
        if (objectDetector == null) {
            Log.w(TAG, "detect() called before setupObjectDetector() completed — skipping frame.")
            return
        }

        // Rotate the bitmap to display-upright orientation using Android Matrix.
        // This aligns the model's bounding-box coordinates with the OverlayView space.
        val rotatedBitmap = if (imageRotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(imageRotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        val tensorImage: TensorImage = TensorImage.fromBitmap(rotatedBitmap)

        val startTime = SystemClock.uptimeMillis()
        val allDetections: List<Detection> = objectDetector!!.detect(tensorImage)
        val inferenceTime = SystemClock.uptimeMillis() - startTime

        // Keep only "person" detections — the COCO label for humans.
        val personDetections = allDetections.filter { detection ->
            detection.categories.any { category ->
                category.label.equals("person", ignoreCase = true)
            }
        }

        Log.d(TAG, "Inference: ${inferenceTime}ms | persons: ${personDetections.size}")

        listener.onResults(
            results        = personDetections,
            inferenceTime  = inferenceTime,
            imageHeight    = tensorImage.height,
            imageWidth     = tensorImage.width
        )
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    /**
     * Releases the native TFLite resources. Call from [Activity.onDestroy].
     */
    fun clearObjectDetector() {
        objectDetector?.close()
        objectDetector = null
    }

    // -------------------------------------------------------------------------
    // Listener interface
    // -------------------------------------------------------------------------

    interface DetectorListener {
        /** Called when a non-recoverable error occurs during setup or inference. */
        fun onError(error: String)

        /**
         * Called after every successful inference with the filtered person detections.
         *
         * @param results       Person detections in model-image pixel coordinates.
         * @param inferenceTime Wall-clock inference duration in milliseconds.
         * @param imageHeight   Height of the image fed to the model (after rotation).
         * @param imageWidth    Width of the image fed to the model (after rotation).
         *
         * Note: called on the background cameraExecutor thread — post UI updates
         * to the main thread before touching any Views.
         */
        fun onResults(
            results: List<Detection>,
            inferenceTime: Long,
            imageHeight: Int,
            imageWidth: Int
        )
    }

    companion object {
        private const val TAG        = "ObjectDetectorHelper"
        /** Filename of the TFLite model placed in app/src/main/assets/. */
        private const val MODEL_FILE = "efficientdet_lite0.tflite"
    }
}
