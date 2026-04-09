package com.example.personcounter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Transparent overlay that draws three layers of detection results:
 *
 *  • **Blue boxes**  — face detections ("face")
 *  • **Green boxes** — person detections ("person N%")
 *  • **Red circles** — persons with a raised hand (drawn at top of their box)
 *
 * All coordinates arrive in model-image space and are scaled linearly to
 * view-pixel space via scaleX / scaleY.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private var result: DetectionHelper.DetectionResult? = null

    // -------------------------------------------------------------------------
    // Paint objects
    // -------------------------------------------------------------------------

    // Person — green box
    private val personBoxPaint = Paint().apply {
        color       = Color.parseColor("#FF4CAF50")
        strokeWidth = 6f
        style       = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val personLabelBg = Paint().apply {
        color       = Color.parseColor("#CC1B5E20")
        style       = Paint.Style.FILL
        isAntiAlias = true
    }

    // Face — blue box
    private val faceBoxPaint = Paint().apply {
        color       = Color.parseColor("#FF2196F3")
        strokeWidth = 5f
        style       = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val faceLabelBg = Paint().apply {
        color       = Color.parseColor("#CC0D47A1")
        style       = Paint.Style.FILL
        isAntiAlias = true
    }

    // Raised hand — red filled circle
    private val raisedBoxPaint = Paint().apply {
        color       = Color.parseColor("#FFF44336")
        strokeWidth = 7f
        style       = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val handDotPaint = Paint().apply {
        color       = Color.parseColor("#FFF44336")
        style       = Paint.Style.FILL
        isAntiAlias = true
    }

    // Label text (shared)
    private val textPaint = Paint().apply {
        color          = Color.WHITE
        textSize       = 36f
        isAntiAlias    = true
        isFakeBoldText = true
    }

    private val textBounds = Rect()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Update detection results and request redraw. Call on the **main thread**.
     */
    fun setResults(detectionResult: DetectionHelper.DetectionResult) {
        result = detectionResult
        invalidate()
    }

    fun clear() {
        result = null
        invalidate()
    }

    // -------------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = result ?: return

        val scaleX = width.toFloat()  / r.imageWidth.toFloat()
        val scaleY = height.toFloat() / r.imageHeight.toFloat()

        // Draw person boxes (green) — bottom layer
        for (box in r.personBoxes) {
            drawBox(canvas, box, scaleX, scaleY, personBoxPaint, personLabelBg, "person")
        }

        // Draw face boxes (blue) — middle layer
        for (box in r.faceBoxes) {
            drawBox(canvas, box, scaleX, scaleY, faceBoxPaint, faceLabelBg, "face")
        }

        // Draw raised-hand overlays (red) — top layer
        for (box in r.raisedHandBoxes) {
            drawRaisedHandMarker(canvas, box, scaleX, scaleY)
        }
    }

    private fun drawBox(
        canvas: Canvas,
        box: RectF,
        scaleX: Float,
        scaleY: Float,
        boxPaint: Paint,
        bgPaint: Paint,
        label: String
    ) {
        val l = box.left   * scaleX
        val t = box.top    * scaleY
        val r = box.right  * scaleX
        val b = box.bottom * scaleY

        canvas.drawRoundRect(RectF(l, t, r, b), CORNER_RADIUS, CORNER_RADIUS, boxPaint)

        textPaint.getTextBounds(label, 0, label.length, textBounds)
        val labelW = textBounds.width()  + PAD * 2
        val labelH = textBounds.height() + PAD * 2
        val labelTop = max(0f, t - labelH)

        canvas.drawRoundRect(
            RectF(l, labelTop, l + labelW, labelTop + labelH),
            CORNER_RADIUS / 2, CORNER_RADIUS / 2, bgPaint
        )
        canvas.drawText(label, l + PAD, labelTop + labelH - PAD, textPaint)
    }

    /** Draws a red dashed rectangle + a red circle at the top-centre of the person box. */
    private fun drawRaisedHandMarker(
        canvas: Canvas,
        box: RectF,
        scaleX: Float,
        scaleY: Float
    ) {
        val l = box.left   * scaleX
        val t = box.top    * scaleY
        val r = box.right  * scaleX
        val b = box.bottom * scaleY
        val cx = (l + r) / 2f

        // Red border overlay
        canvas.drawRoundRect(RectF(l, t, r, b), CORNER_RADIUS, CORNER_RADIUS, raisedBoxPaint)

        // Red filled dot above the person box (hand indicator)
        val dotRadius = 18f
        val dotY = max(dotRadius, t - dotRadius - 4f)
        canvas.drawCircle(cx, dotY, dotRadius, handDotPaint)

        // "✋" label inside dot area
        textPaint.textSize = 22f
        canvas.drawText("✋", cx - 11f, dotY + 8f, textPaint)
        textPaint.textSize = 36f   // restore
    }

    companion object {
        private const val CORNER_RADIUS = 8f
        private const val PAD           = 8f
    }
}
