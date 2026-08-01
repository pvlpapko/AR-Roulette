package com.pavelpapko.arroulette

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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

    enum class ReticleState {
        INVALID,
        ACQUIRING,
        STABLE
    }

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val polygonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 40, 120, 246)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = resources.getDimension(R.dimen.measurement_label_text_size)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.4f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val reticleDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(42, 255, 255, 255)
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }

    private var points: List<PointF> = emptyList()
    private var closePolygon: Boolean = false
    private var label: String? = null
    private var reticleState: ReticleState = ReticleState.INVALID
    private var showGrid: Boolean = false
    private var measurementMode: MeasurementMode = MeasurementMode.RULER

    fun update(
        points: List<PointF>,
        closePolygon: Boolean,
        label: String?,
        reticleState: ReticleState,
        showGrid: Boolean,
        measurementMode: MeasurementMode
    ) {
        this.points = points
        this.closePolygon = closePolygon
        this.label = label
        this.reticleState = reticleState
        this.showGrid = showGrid
        this.measurementMode = measurementMode
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (showGrid) drawGrid(canvas)
        drawMeasurement(canvas)
        drawReticle(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        val step = 48f * density
        var x = width / 2f
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += step
        }
        x = width / 2f - step
        while (x > 0f) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x -= step
        }
        var y = height / 2f
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += step
        }
        y = height / 2f - step
        while (y > 0f) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y -= step
        }
    }

    private fun drawMeasurement(canvas: Canvas) {
        if (points.isEmpty()) return
        val lineColor = modeColor(measurementMode)
        linePaint.color = lineColor
        markerPaint.color = lineColor
        labelBackgroundPaint.color = lineColor
        polygonPaint.color = Color.argb(45, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor))

        if (points.size >= 2) {
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                if (closePolygon) close()
            }
            if (closePolygon) canvas.drawPath(path, polygonPaint)
            canvas.drawPath(path, linePaint)
        }

        points.forEach { drawMarker(canvas, it) }
        label?.let { text -> drawLabel(canvas, labelCenter(), text) }
    }

    private fun drawReticle(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = 14f * density
        val outerRadius = 23f * density
        val gap = 5f * density
        val arm = 10f * density
        val color = when (reticleState) {
            ReticleState.INVALID -> Color.rgb(255, 69, 58)
            ReticleState.ACQUIRING -> Color.rgb(255, 176, 32)
            ReticleState.STABLE -> Color.WHITE
        }
        reticlePaint.color = color
        reticleDotPaint.color = color

        canvas.drawCircle(centerX, centerY, radius, reticlePaint)
        canvas.drawCircle(centerX, centerY, outerRadius, reticlePaint.apply { alpha = 115 })
        reticlePaint.alpha = 255
        canvas.drawCircle(centerX, centerY, 2.7f * density, reticleDotPaint)
        canvas.drawLine(centerX - radius - arm, centerY, centerX - radius - gap, centerY, reticlePaint)
        canvas.drawLine(centerX + radius + gap, centerY, centerX + radius + arm, centerY, reticlePaint)
        canvas.drawLine(centerX, centerY - radius - arm, centerX, centerY - radius - gap, reticlePaint)
        canvas.drawLine(centerX, centerY + radius + gap, centerX, centerY + radius + arm, reticlePaint)
    }

    private fun drawMarker(canvas: Canvas, point: PointF) {
        val outerRadius = 12f * density
        val innerRadius = 6f * density
        markerStrokePaint.alpha = 105
        canvas.drawCircle(point.x, point.y, outerRadius, markerStrokePaint)
        markerStrokePaint.alpha = 255
        canvas.drawCircle(point.x, point.y, innerRadius, markerPaint)
        canvas.drawCircle(point.x, point.y, innerRadius, markerStrokePaint)
    }

    private fun drawLabel(canvas: Canvas, center: PointF, text: String) {
        val horizontalPadding = 12f * density
        val verticalPadding = 7f * density
        val textWidth = labelPaint.measureText(text)
        val fontMetrics = labelPaint.fontMetrics
        val textHeight = fontMetrics.bottom - fontMetrics.top
        val rect = RectF(
            center.x - textWidth / 2f - horizontalPadding,
            center.y - textHeight / 2f - verticalPadding,
            center.x + textWidth / 2f + horizontalPadding,
            center.y + textHeight / 2f + verticalPadding
        )
        val safeRect = RectF(
            max(8f * density, rect.left),
            max(8f * density, rect.top),
            rect.right.coerceAtMost(width - 8f * density),
            rect.bottom.coerceAtMost(height - 8f * density)
        )
        canvas.drawRoundRect(safeRect, 10f * density, 10f * density, labelBackgroundPaint)
        val baseline = safeRect.centerY() - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(text, safeRect.centerX(), baseline, labelPaint)
    }

    private fun labelCenter(): PointF {
        if (points.isEmpty()) return PointF(width / 2f, height / 2f)
        if (points.size == 2) {
            return PointF(
                (points[0].x + points[1].x) / 2f,
                (points[0].y + points[1].y) / 2f
            )
        }
        return PointF(
            points.map { it.x }.average().toFloat(),
            points.map { it.y }.average().toFloat()
        )
    }

    private fun modeColor(mode: MeasurementMode): Int = when (mode) {
        MeasurementMode.RULER, MeasurementMode.POINT -> Color.rgb(40, 120, 246)
        MeasurementMode.HEIGHT -> Color.rgb(255, 190, 40)
        MeasurementMode.AREA -> Color.rgb(52, 211, 153)
        MeasurementMode.DISTANCE_TO_OBJECT -> Color.rgb(168, 85, 247)
    }
}
