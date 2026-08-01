package com.pavelpapko.arroulette

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class MeasurementOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(102, 227, 164)
        style = Paint.Style.FILL
    }
    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 20, 24, 32)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = resources.getDimension(R.dimen.measurement_label_text_size)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var firstPoint: PointF? = null
    private var secondPoint: PointF? = null
    private var label: String? = null
    private var reticleValid: Boolean = false

    fun update(
        firstPoint: PointF?,
        secondPoint: PointF?,
        label: String?,
        reticleValid: Boolean
    ) {
        this.firstPoint = firstPoint
        this.secondPoint = secondPoint
        this.label = label
        this.reticleValid = reticleValid
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawReticle(canvas)

        val first = firstPoint
        val second = secondPoint
        if (first != null && second != null) {
            canvas.drawLine(first.x, first.y, second.x, second.y, linePaint)
            drawMarker(canvas, first)
            drawMarker(canvas, second)
            label?.let { drawLabel(canvas, first, second, it) }
        } else if (first != null) {
            drawMarker(canvas, first)
        }
    }

    private fun drawReticle(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = 13f * density
        val gap = 5f * density
        val arm = 10f * density
        reticlePaint.color = if (reticleValid) Color.rgb(102, 227, 164) else Color.rgb(255, 107, 107)
        canvas.drawCircle(centerX, centerY, radius, reticlePaint)
        canvas.drawLine(centerX - radius - arm, centerY, centerX - radius - gap, centerY, reticlePaint)
        canvas.drawLine(centerX + radius + gap, centerY, centerX + radius + arm, centerY, reticlePaint)
        canvas.drawLine(centerX, centerY - radius - arm, centerX, centerY - radius - gap, reticlePaint)
        canvas.drawLine(centerX, centerY + radius + gap, centerX, centerY + radius + arm, reticlePaint)
    }

    private fun drawMarker(canvas: Canvas, point: PointF) {
        val radius = 8f * density
        canvas.drawCircle(point.x, point.y, radius, markerPaint)
        canvas.drawCircle(point.x, point.y, radius, markerStrokePaint)
    }

    private fun drawLabel(canvas: Canvas, first: PointF, second: PointF, text: String) {
        val centerX = (first.x + second.x) / 2f
        val centerY = (first.y + second.y) / 2f
        val horizontalPadding = 12f * density
        val verticalPadding = 8f * density
        val textWidth = labelPaint.measureText(text)
        val fontMetrics = labelPaint.fontMetrics
        val textHeight = fontMetrics.bottom - fontMetrics.top
        val rect = RectF(
            centerX - textWidth / 2f - horizontalPadding,
            centerY - textHeight / 2f - verticalPadding,
            centerX + textWidth / 2f + horizontalPadding,
            centerY + textHeight / 2f + verticalPadding
        )
        val safeRect = RectF(
            max(8f * density, rect.left),
            max(8f * density, rect.top),
            rect.right.coerceAtMost(width - 8f * density),
            rect.bottom.coerceAtMost(height - 8f * density)
        )
        canvas.drawRoundRect(safeRect, 12f * density, 12f * density, labelBackgroundPaint)
        val baseline = safeRect.centerY() - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(text, safeRect.centerX(), baseline, labelPaint)
    }
}
