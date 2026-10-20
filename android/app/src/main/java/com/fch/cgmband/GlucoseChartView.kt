package com.fch.cgmband

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.fch.cgmband.core.ChartCursor
import com.fch.cgmband.core.ChartData
import com.fch.cgmband.core.CursorReadout
import com.fch.cgmband.core.GlucosePoint
import com.fch.cgmband.core.TargetRange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Glucose line chart in the style of the iCan app: grey yesterday line, dark today line
 * that turns orange above and red below the target range, shaded target band, and the
 * range limits labelled on the right edge. Touching the chart shows a cursor that can be
 * dragged sideways; [onCursorChanged] reports what it points at (null when it hides).
 */
class GlucoseChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var data: ChartData? = null
    private var range: TargetRange = TargetRange()
    private var cursor: CursorReadout? = null

    /** Called with the cursor readout while it is shown, and with null when it hides. */
    var onCursorChanged: ((CursorReadout?) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var draggingSideways = false
    private val hideCursor = Runnable { setCursor(null) }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
    private val plot = RectF()

    private val gridPaint = strokePaint(R.color.chart_grid, 1f)
    private val bandPaint = Paint().apply { color = color(R.color.chart_target_band) }
    private val yesterdayPaint = strokePaint(R.color.chart_yesterday, 2.5f)
    private val todayPaint = strokePaint(R.color.chart_today, 3f)
    private val highPaint = strokePaint(R.color.level_high, 3f)
    private val lowPaint = strokePaint(R.color.level_low, 3f)
    private val axisTextPaint = textPaint(R.color.text_secondary, 12f)
    private val highLabelPaint = textPaint(R.color.level_high, 13f)
    private val lowLabelPaint = textPaint(R.color.level_low, 13f)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val emptyPaint = textPaint(R.color.text_secondary, 14f).apply { textAlign = Paint.Align.CENTER }
    private val cursorPaint = strokePaint(R.color.text_secondary, 1f)
    private val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.card_bg) }
    private val markerRingPaint = strokePaint(R.color.chart_today, 2.5f)
    private val yesterdayMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.chart_yesterday) }

    fun setData(data: ChartData, range: TargetRange) {
        this.data = data
        this.range = range
        // New readings or another span: keep the cursor where it was, re-read against the new data.
        cursor?.let { setCursor(ChartCursor.readout(data, it.at)) } ?: invalidate()
    }

    @SuppressLint("ClickableViewAccessibility") // the cursor is a visual aid; values stay readable without it
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val chart = data ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                draggingSideways = false
                removeCallbacks(hideCursor)
                setCursor(ChartCursor.readout(chart, timeAt(event.x, chart)))
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                // Claim the gesture only once it is clearly horizontal, so the page still scrolls vertically.
                if (!draggingSideways && dx > touchSlop && dx > dy) {
                    draggingSideways = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                setCursor(ChartCursor.readout(chart, timeAt(event.x, chart)))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                postDelayed(hideCursor, CURSOR_HOLD_MS)
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideCursor)
        super.onDetachedFromWindow()
    }

    private fun setCursor(readout: CursorReadout?) {
        cursor = readout
        invalidate()
        onCursorChanged?.invoke(readout)
    }

    private fun timeAt(x: Float, chart: ChartData): Long {
        val fraction = ((x - plot.left) / plot.width()).coerceIn(0f, 1f)
        return chart.window.start + (fraction * (chart.window.end - chart.window.start)).toLong()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val chart = data ?: return
        plot.set(dp(28f), dp(10f), width - dp(36f), height - dp(26f))

        drawGrid(canvas, chart)
        drawTargetBand(canvas, chart)
        drawTimeAxis(canvas, chart)

        if (chart.today.isEmpty() && chart.yesterday.isEmpty()) {
            canvas.drawText(context.getString(R.string.chart_empty), plot.centerX(), plot.centerY(), emptyPaint)
            return
        }
        drawSeries(canvas, chart, chart.yesterday, yesterdayPaint)
        drawToday(canvas, chart)
        cursor?.let { drawCursor(canvas, chart, it) }
    }

    private fun drawCursor(canvas: Canvas, chart: ChartData, readout: CursorReadout) {
        val x = xOf(readout.at, chart)
        canvas.drawLine(x, plot.top, x, plot.bottom, cursorPaint)
        readout.yesterday?.let { canvas.drawCircle(x, yOf(it.mmol, chart), dp(3.5f), yesterdayMarkerPaint) }
        readout.today?.let {
            val y = yOf(it.mmol, chart)
            canvas.drawCircle(x, y, dp(5f), markerFillPaint)
            canvas.drawCircle(x, y, dp(5f), markerRingPaint)
        }
    }

    private fun drawGrid(canvas: Canvas, chart: ChartData) {
        generateSequence(0.0) { it + Y_STEP }.takeWhile { it <= chart.yMax }.forEach { value ->
            val y = yOf(value, chart)
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
            canvas.drawText(value.toInt().toString(), 0f, y + dp(4f), axisTextPaint)
        }
    }

    private fun drawTargetBand(canvas: Canvas, chart: ChartData) {
        val top = yOf(range.high, chart)
        val bottom = yOf(range.low, chart)
        canvas.drawRect(plot.left, top, plot.right, bottom, bandPaint)
        canvas.drawText(format(range.high), plot.right + dp(6f), top + dp(4f), highLabelPaint)
        canvas.drawText(format(range.low), plot.right + dp(6f), bottom + dp(4f), lowLabelPaint)
    }

    private fun drawTimeAxis(canvas: Canvas, chart: ChartData) {
        val y = height - dp(6f)
        chart.window.ticks.forEach { tick ->
            val label = timeFormat.format(Date(tick))
            val x = (xOf(tick, chart) - axisTextPaint.measureText(label) / 2)
                .coerceIn(0f, width - axisTextPaint.measureText(label))
            canvas.drawText(label, x, y, axisTextPaint)
        }
    }

    /** Draw today's line three times, clipped to the low / in-range / high bands. */
    private fun drawToday(canvas: Canvas, chart: ChartData) {
        val highY = yOf(range.high, chart)
        val lowY = yOf(range.low, chart)
        clipped(canvas, 0f, highY) { drawSeries(canvas, chart, chart.today, highPaint) }
        clipped(canvas, highY, lowY) { drawSeries(canvas, chart, chart.today, todayPaint) }
        clipped(canvas, lowY, height.toFloat()) { drawSeries(canvas, chart, chart.today, lowPaint) }
    }

    private fun clipped(canvas: Canvas, top: Float, bottom: Float, draw: () -> Unit) {
        val saved = canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), bottom)
        draw()
        canvas.restoreToCount(saved)
    }

    private fun drawSeries(canvas: Canvas, chart: ChartData, points: List<GlucosePoint>, paint: Paint) {
        ChartData.segments(points).forEach { segment ->
            if (segment.size == 1) {
                val p = segment.first()
                dotPaint.color = paint.color
                canvas.drawCircle(xOf(p.at, chart), yOf(p.mmol, chart), dp(3.5f), dotPaint)
                return@forEach
            }
            val path = Path()
            segment.forEachIndexed { i, p ->
                val x = xOf(p.at, chart)
                val y = yOf(p.mmol, chart)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
    }

    private fun xOf(at: Long, chart: ChartData): Float {
        val span = (chart.window.end - chart.window.start).toFloat()
        return plot.left + (at - chart.window.start) / span * plot.width()
    }

    private fun yOf(mmol: Double, chart: ChartData): Float =
        plot.bottom - (mmol.coerceIn(0.0, chart.yMax) / chart.yMax).toFloat() * plot.height()

    private fun format(value: Double) = String.format(Locale.US, "%.1f", value)

    private fun color(id: Int) = context.getColor(id)

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun strokePaint(colorId: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(colorId)
        style = Paint.Style.STROKE
        strokeWidth = dp(widthDp)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun textPaint(colorId: Int, sizeSp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(colorId)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
    }

    private companion object {
        const val Y_STEP = 3.0
        const val CURSOR_HOLD_MS = 8_000L
    }
}
