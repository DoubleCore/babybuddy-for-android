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
    private var whoBands: List<WhoGrowthStandards.PercentileBand?> = emptyList()
    private var normalBand: Pair<Float, Float>? = null
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

    private val whoBandOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val whoBandInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val whoMedianPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 3f * density), 0f)
    }

    private val whoBoundaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
    }

    private val whoLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted_warm)
        textSize = 9f * scaledDensity
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
    private val bandPath = Path()
    private val tooltipRect = RectF()
    private val numFormat = DecimalFormat("0.##")

    fun setPoints(
        items: List<StatsAggregator.GrowthPoint>,
        accentColor: Int,
        unit: String,
        emptyText: String = context.getString(R.string.stats_no_data),
        whoBands: List<WhoGrowthStandards.PercentileBand?> = emptyList(),
        normalReferenceBand: Pair<Float, Float>? = null,
    ) {
        this.points = items
        this.whoBands = whoBands
        this.normalBand = normalReferenceBand
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

        val hasWho = whoBands.size == points.size && whoBands.any { it != null }
        val leftPad = 42f * density
        val rightPad = if (hasWho) 30f * density else 16f * density
        val topPad = 30f * density
        val bottomPad = 24f * density

        val chartW = max(1f, w - leftPad - rightPad)
        val chartH = max(1f, h - topPad - bottomPad)
        val baselineY = topPad + chartH

        var rawMin = points.minOf { it.value }
        var rawMax = points.maxOf { it.value }
        if (hasWho) {
            for (b in whoBands) {
                if (b != null) {
                    rawMin = min(rawMin, b.p3)
                    rawMax = max(rawMax, b.p97)
                }
            }
        }
        normalBand?.let { (lo, hi) ->
            rawMin = min(rawMin, lo)
            rawMax = max(rawMax, hi)
        }

        val span = max(0.4f, rawMax - rawMin)
        val yMin = max(0f, rawMin - span * 0.14f)
        val yMax = rawMax + span * 0.14f
        val yRange = max(0.1f, yMax - yMin)
        fun yOf(v: Float): Float = baselineY - ((v - yMin) / yRange) * chartH

        // Grid & Y labels
        val yTicks = listOf(yMin, (yMin + yMax) / 2f, yMax)
        axisTextPaint.textAlign = Paint.Align.RIGHT
        for (tick in yTicks) {
            val y = yOf(tick)
            canvas.drawLine(leftPad, y, leftPad + chartW, y, gridPaint)
            canvas.drawText(numFormat.format(tick.toDouble()), leftPad - 6f * density, y + 3.5f * density, axisTextPaint)
        }

        // Optional flat normal band (e.g., 36.5 - 37.5 °C for Temperature)
        normalBand?.let { (lo, hi) ->
            whoBandInnerPaint.color = ColorUtils.setAlphaComponent(lineColor, 28)
            val topY = yOf(hi)
            val botY = yOf(lo)
            canvas.drawRect(leftPad, topY, leftPad + chartW, botY, whoBandInnerPaint)
        }

        val xs = FloatArray(points.size)
        val ys = FloatArray(points.size)
        for (i in points.indices) {
            xs[i] = if (points.size == 1) {
                leftPad + chartW / 2f
            } else {
                leftPad + (i.toFloat() / (points.size - 1).toFloat()) * chartW
            }
            ys[i] = yOf(points[i].value)
        }

        // Draw WHO P3/P15/P50/P85/P97 bands & curves
        if (hasWho) {
            whoBandOuterPaint.color = ColorUtils.setAlphaComponent(lineColor, 20)
            whoBandInnerPaint.color = ColorUtils.setAlphaComponent(lineColor, 34)
            whoMedianPaint.color = ColorUtils.setAlphaComponent(lineColor, 130)
            whoBoundaryPaint.color = ColorUtils.setAlphaComponent(lineColor, 85)

            val validIndices = whoBands.indices.filter { whoBands[it] != null }
            if (validIndices.size == 1) {
                val idx = validIndices.first()
                val b = whoBands[idx]!!
                val xL = leftPad + chartW * 0.15f
                val xR = leftPad + chartW * 0.85f
                canvas.drawRect(xL, yOf(b.p97), xR, yOf(b.p3), whoBandOuterPaint)
                canvas.drawRect(xL, yOf(b.p85), xR, yOf(b.p15), whoBandInnerPaint)
                canvas.drawLine(xL, yOf(b.p50), xR, yOf(b.p50), whoMedianPaint)
                canvas.drawLine(xL, yOf(b.p97), xR, yOf(b.p97), whoBoundaryPaint)
                canvas.drawLine(xL, yOf(b.p3), xR, yOf(b.p3), whoBoundaryPaint)
                whoLabelPaint.textAlign = Paint.Align.LEFT
                canvas.drawText("P97", xR + 3f * density, yOf(b.p97) + 3f * density, whoLabelPaint)
                canvas.drawText("P50", xR + 3f * density, yOf(b.p50) + 3f * density, whoLabelPaint)
                canvas.drawText("P3", xR + 3f * density, yOf(b.p3) + 3f * density, whoLabelPaint)
            } else if (validIndices.size >= 2) {
                // P3..P97 outer polygon
                bandPath.reset()
                val first = validIndices.first()
                bandPath.moveTo(xs[first], yOf(whoBands[first]!!.p97))
                for (k in 1 until validIndices.size) {
                    val idx = validIndices[k]
                    bandPath.lineTo(xs[idx], yOf(whoBands[idx]!!.p97))
                }
                for (k in validIndices.size - 1 downTo 0) {
                    val idx = validIndices[k]
                    bandPath.lineTo(xs[idx], yOf(whoBands[idx]!!.p3))
                }
                bandPath.close()
                canvas.drawPath(bandPath, whoBandOuterPaint)

                // P15..P85 inner polygon
                bandPath.reset()
                bandPath.moveTo(xs[first], yOf(whoBands[first]!!.p85))
                for (k in 1 until validIndices.size) {
                    val idx = validIndices[k]
                    bandPath.lineTo(xs[idx], yOf(whoBands[idx]!!.p85))
                }
                for (k in validIndices.size - 1 downTo 0) {
                    val idx = validIndices[k]
                    bandPath.lineTo(xs[idx], yOf(whoBands[idx]!!.p15))
                }
                bandPath.close()
                canvas.drawPath(bandPath, whoBandInnerPaint)

                // P50 median, P97, and P3 curves
                fun drawCurve(selector: (WhoGrowthStandards.PercentileBand) -> Float, paint: Paint) {
                    linePath.reset()
                    linePath.moveTo(xs[first], yOf(selector(whoBands[first]!!)))
                    for (k in 1 until validIndices.size) {
                        val idx = validIndices[k]
                        linePath.lineTo(xs[idx], yOf(selector(whoBands[idx]!!)))
                    }
                    canvas.drawPath(linePath, paint)
                }
                drawCurve({ it.p97 }, whoBoundaryPaint)
                drawCurve({ it.p50 }, whoMedianPaint)
                drawCurve({ it.p3 }, whoBoundaryPaint)

                val lastIdx = validIndices.last()
                val lastB = whoBands[lastIdx]!!
                whoLabelPaint.textAlign = Paint.Align.LEFT
                canvas.drawText("P97", xs[lastIdx] + 3f * density, yOf(lastB.p97) + 3f * density, whoLabelPaint)
                canvas.drawText("P50", xs[lastIdx] + 3f * density, yOf(lastB.p50) + 3f * density, whoLabelPaint)
                canvas.drawText("P3", xs[lastIdx] + 3f * density, yOf(lastB.p3) + 3f * density, whoLabelPaint)
            }
        }

        if (points.size >= 2) {
            linePath.reset()
            linePath.moveTo(xs[0], ys[0])
            for (i in 1 until points.size) {
                linePath.lineTo(xs[i], ys[i])
            }

            if (!hasWho) {
                fillPath.reset()
                fillPath.moveTo(xs[0], baselineY)
                for (i in points.indices) {
                    fillPath.lineTo(xs[i], ys[i])
                }
                fillPath.lineTo(xs.last(), baselineY)
                fillPath.close()
                fillPaint.shader = LinearGradient(
                    0f,
                    topPad,
                    0f,
                    baselineY,
                    ColorUtils.setAlphaComponent(lineColor, 72),
                    ColorUtils.setAlphaComponent(lineColor, 4),
                    Shader.TileMode.CLAMP
                )
                canvas.drawPath(fillPath, fillPaint)
            }

            linePaint.color = lineColor
            canvas.drawPath(linePath, linePaint)
        }

        // X-axis labels
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
            val whoExtra = whoBands.getOrNull(selectedIndex)?.let { b ->
                " (P50=${numFormat.format(b.p50.toDouble())})"
            } ?: ""
            val label = "${pt.label} · ${numFormat.format(pt.value.toDouble())}$u$whoExtra"
            val textW = tooltipTextPaint.measureText(label)
            val pillW = min(w - 8f * density, textW + 16f * density)
            val pillH = 22f * density
            val clampedLeft = max(4f * density, min(w - 4f * density - pillW, xs[selectedIndex] - pillW / 2f))
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
                val hasWho = whoBands.size == points.size && whoBands.any { it != null }
                val leftPad = 42f * density
                val rightPad = if (hasWho) 30f * density else 16f * density
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
