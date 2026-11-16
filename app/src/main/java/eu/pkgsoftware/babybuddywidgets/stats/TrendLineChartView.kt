package eu.pkgsoftware.babybuddywidgets.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import eu.pkgsoftware.babybuddywidgets.R
import java.text.DecimalFormat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TrendLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var points: List<StatsAggregator.GrowthPoint> = emptyList()
    private var lineColor: Int = ContextCompat.getColor(context, R.color.weight_accent)
    private var unitSuffix: String = ""
    private var emptyMessage: String = context.getString(R.string.stats_no_data)
    private var selectedIndex: Int = -1

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card_stroke)
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
    }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted_warm)
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }

    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted_warm)
        textSize = 10.5f * scaledDensity
    }

    private val emptyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted_warm)
        textSize = 13f * scaledDensity
        textAlign = Paint.Align.CENTER
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card_surface)
        style = Paint.Style.FILL
    }

    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary_warm)
        style = Paint.Style.FILL
    }

    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card_surface)
        textSize = 11.5f * scaledDensity
        isFakeBoldText = true
    }

    private val linePath = Path()
    private val fillPath = Path()
    private val tooltipRect = RectF()
    private val numFormat = DecimalFormat("0.##")

    fun setPoints(
        items: List<StatsAggregator.GrowthPoint>,
        accentColor: Int,
        unit: String,
        emptyText: String = context.getString(R.string.stats_no_data)
    ) {
        this.points = items
        this.lineColor = accentColor
        this.unitSuffix = unit
        this.emptyMessage = emptyText
        this.selectedIndex = if (items.isNotEmpty()) items.lastIndex else -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (points.isEmpty()) {
            canvas.drawText(emptyMessage, w / 2f, h / 2f + 4f * density, emptyTextPaint)
            return
        }

        val leftPad = 42f * density
        val rightPad = 16f * density
        val topPad = 30f * density
        val bottomPad = 24f * density

        val chartW = max(1f, w - leftPad - rightPad)
        val chartH = max(1f, h - topPad - bottomPad)
        val baselineY = topPad + chartH

        val rawMin = points.minOf { it.value }
        val rawMax = points.maxOf { it.value }
        val span = max(0.4f, rawMax - rawMin)
        val yMin = max(0f, rawMin - span * 0.2f)
        val yMax = rawMax + span * 0.2f
        val yRange = max(0.1f, yMax - yMin)

        // Grid & Y labels
        val yTicks = listOf(yMin, (yMin + yMax) / 2f, yMax)
        axisTextPaint.textAlign = Paint.Align.RIGHT
        for (tick in yTicks) {
            val ratio = (tick - yMin) / yRange
            val y = baselineY - ratio * chartH
            canvas.drawLine(leftPad, y, leftPad + chartW, y, gridPaint)
            canvas.drawText(numFormat.format(tick.toDouble()), leftPad - 6f * density, y + 3.5f * density, axisTextPaint)
        }

        val xs = FloatArray(points.size)
        val ys = FloatArray(points.size)
        for (i in points.indices) {
            xs[i] = if (points.size == 1) {
                leftPad + chartW / 2f
            } else {
                leftPad + (i.toFloat() / (points.size - 1).toFloat()) * chartW
            }
            val ratio = (points[i].value - yMin) / yRange
            ys[i] = baselineY - ratio * chartH
        }

        if (points.size >= 2) {
            // Soft WHO-style P10–P90 reference corridor band + P50 dashed median
            val bandPath = Path()
            val medianPath = Path()
            val bandHalfSpan = chartH * 0.14f
            bandPath.moveTo(xs[0], max(topPad, ys[0] - bandHalfSpan))
            medianPath.moveTo(xs[0], ys[0])
            for (i in 1 until points.size) {
                val smoothY = if (i in 1 until points.lastIndex) {
                    (ys[i - 1] + ys[i] + ys[i + 1]) / 3f
                } else {
                    ys[i]
                }
                bandPath.lineTo(xs[i], max(topPad, smoothY - bandHalfSpan))
                medianPath.lineTo(xs[i], smoothY)
            }
            for (i in points.indices.reversed()) {
                val smoothY = if (i in 1 until points.lastIndex) {
                    (ys[i - 1] + ys[i] + ys[i + 1]) / 3f
                } else {
                    ys[i]
                }
                bandPath.lineTo(xs[i], min(baselineY, smoothY + bandHalfSpan))
            }
            bandPath.close()

            fillPaint.shader = null
            fillPaint.color = ColorUtils.setAlphaComponent(lineColor, 34)
            canvas.drawPath(bandPath, fillPaint)

            val medianPaint = Paint(gridPaint).apply {
                color = ColorUtils.setAlphaComponent(lineColor, 120)
                strokeWidth = 1.2f * density
            }
            canvas.drawPath(medianPath, medianPaint)

            linePath.reset()
            fillPath.reset()
            linePath.moveTo(xs[0], ys[0])
            fillPath.moveTo(xs[0], baselineY)
            fillPath.lineTo(xs[0], ys[0])
            for (i in 1 until points.size) {
                linePath.lineTo(xs[i], ys[i])
                fillPath.lineTo(xs[i], ys[i])
            }
            fillPath.lineTo(xs.last(), baselineY)
            fillPath.close()

            fillPaint.shader = LinearGradient(
                0f,
                topPad,
                0f,
                baselineY,
                ColorUtils.setAlphaComponent(lineColor, 54),
                ColorUtils.setAlphaComponent(lineColor, 4),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(fillPath, fillPaint)

            linePaint.color = lineColor
            canvas.drawPath(linePath, linePaint)
        }

        // X-axis labels (first, middle if >= 5, last)
        axisTextPaint.textAlign = Paint.Align.CENTER
        if (points.size == 1) {
            canvas.drawText(points[0].label, xs[0], baselineY + 16f * density, axisTextPaint)
        } else {
            canvas.drawText(points.first().label, xs.first(), baselineY + 16f * density, axisTextPaint)
            if (points.size >= 5) {
                val mid = points.size / 2
                canvas.drawText(points[mid].label, xs[mid], baselineY + 16f * density, axisTextPaint)
            }
            canvas.drawText(points.last().label, xs.last(), baselineY + 16f * density, axisTextPaint)
        }

        // Selected vertical guide
        if (selectedIndex in points.indices) {
            canvas.drawLine(xs[selectedIndex], topPad, xs[selectedIndex], baselineY, guidePaint)
        }

        // Dots
        dotPaint.color = lineColor
        for (i in points.indices) {
            val r = if (i == selectedIndex) 5.5f * density else 3.8f * density
            canvas.drawCircle(xs[i], ys[i], r, dotPaint)
            canvas.drawCircle(xs[i], ys[i], r * 0.45f, dotCenterPaint)
        }

        // Tooltip
        if (selectedIndex in points.indices) {
            val pt = points[selectedIndex]
            val u = if (unitSuffix.isNotEmpty()) " $unitSuffix" else ""
            val label = "${pt.label} · ${numFormat.format(pt.value.toDouble())}$u"
            val textW = tooltipTextPaint.measureText(label)
            val pillW = textW + 16f * density
            val pillH = 22f * density
            val clampedLeft = max(leftPad, min(w - rightPad - pillW, xs[selectedIndex] - pillW / 2f))
            tooltipRect.set(clampedLeft, 2f * density, clampedLeft + pillW, 2f * density + pillH)
            canvas.drawRoundRect(tooltipRect, pillH / 2f, pillH / 2f, tooltipBgPaint)
            canvas.drawText(
                label,
                clampedLeft + 8f * density,
                2f * density + pillH * 0.68f,
                tooltipTextPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points.isEmpty()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                val leftPad = 42f * density
                val rightPad = 16f * density
                val chartW = max(1f, width - leftPad - rightPad)
                var bestIdx = 0
                var bestDist = Float.MAX_VALUE
                for (i in points.indices) {
                    val px = if (points.size == 1) leftPad + chartW / 2f
                    else leftPad + (i.toFloat() / (points.size - 1).toFloat()) * chartW
                    val d = abs(event.x - px)
                    if (d < bestDist) {
                        bestDist = d
                        bestIdx = i
                    }
                }
                if (bestIdx != selectedIndex) {
                    selectedIndex = bestIdx
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
