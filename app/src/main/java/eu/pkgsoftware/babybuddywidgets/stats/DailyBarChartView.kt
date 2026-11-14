package eu.pkgsoftware.babybuddywidgets.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import eu.pkgsoftware.babybuddywidgets.R
import java.text.DecimalFormat
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class DailyBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var data: List<StatsAggregator.DayBucketStat> = emptyList()
    private var primaryColor: Int = ContextCompat.getColor(context, R.color.feeding_accent)
    private var secondaryColor: Int = ContextCompat.getColor(context, R.color.diaper_accent)
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

    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted_warm)
        textSize = 10.5f * scaledDensity
    }

    private val emptyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted_warm)
        textSize = 13f * scaledDensity
        textAlign = Paint.Align.CENTER
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chip_surface)
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

    private val barRect = RectF()
    private val tooltipRect = RectF()
    private val numFormat = DecimalFormat("0.#")

    fun setSeries(
        items: List<StatsAggregator.DayBucketStat>,
        primaryBarColor: Int,
        secondaryBarColor: Int = primaryBarColor,
        unit: String = "",
        emptyText: String = context.getString(R.string.stats_no_data)
    ) {
        this.data = items
        this.primaryColor = primaryBarColor
        this.secondaryColor = secondaryBarColor
        this.unitSuffix = unit
        this.emptyMessage = emptyText
        this.selectedIndex = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val hasAnyPositive = data.any { it.totalValue > 0.001f }
        if (data.isEmpty() || !hasAnyPositive) {
            canvas.drawText(emptyMessage, w / 2f, h / 2f + 4f * density, emptyTextPaint)
            return
        }

        val leftPad = 36f * density
        val rightPad = 10f * density
        val topPad = 28f * density
        val bottomPad = 24f * density

        val chartW = max(1f, w - leftPad - rightPad)
        val chartH = max(1f, h - topPad - bottomPad)
        val baselineY = topPad + chartH

        val rawMax = data.maxOf { it.totalValue }
        val niceMax = computeNiceMax(rawMax)

        // Draw 3 horizontal grid lines (0%, 50%, 100%)
        val ticks = listOf(0f, niceMax / 2f, niceMax)
        for (tick in ticks) {
            val ratio = if (niceMax > 0f) tick / niceMax else 0f
            val y = baselineY - ratio * chartH
            canvas.drawLine(leftPad, y, leftPad + chartW, y, gridPaint)
            val tickLabel = numFormat.format(tick.toDouble())
            axisTextPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(tickLabel, leftPad - 6f * density, y + 3.5f * density, axisTextPaint)
        }

        val count = data.size
        val slotW = chartW / count
        val barW = min(22f * density, max(4f * density, slotW * 0.58f))
        val cornerR = min(barW / 2f, 6f * density)

        val labelStep = when {
            count <= 7 -> 1
            count <= 14 -> 2
            else -> 5
        }

        axisTextPaint.textAlign = Paint.Align.CENTER
        for (i in data.indices) {
            val item = data[i]
            val centerX = leftPad + slotW * (i + 0.5f)

            if (i == selectedIndex) {
                barRect.set(
                    centerX - slotW * 0.45f,
                    topPad,
                    centerX + slotW * 0.45f,
                    baselineY
                )
                canvas.drawRoundRect(barRect, 6f * density, 6f * density, highlightPaint)
            }

            val totalH = (item.totalValue / niceMax) * chartH
            val primaryH = (item.primaryValue / niceMax) * chartH

            if (item.secondaryValue > 0.001f && totalH > 0.5f) {
                // Draw total height in secondary color first, then primary segment at bottom
                barPaint.color = secondaryColor
                barRect.set(centerX - barW / 2f, baselineY - totalH, centerX + barW / 2f, baselineY)
                canvas.drawRoundRect(barRect, cornerR, cornerR, barPaint)

                if (primaryH > 0.5f) {
                    barPaint.color = primaryColor
                    barRect.set(centerX - barW / 2f, baselineY - primaryH, centerX + barW / 2f, baselineY)
                    canvas.drawRoundRect(barRect, cornerR, cornerR, barPaint)
                }
            } else if (primaryH > 0.5f) {
                barPaint.color = primaryColor
                val drawH = max(primaryH, 3f * density)
                barRect.set(centerX - barW / 2f, baselineY - drawH, centerX + barW / 2f, baselineY)
                canvas.drawRoundRect(barRect, cornerR, cornerR, barPaint)
            }

            if (i % labelStep == 0 || i == count - 1) {
                // Avoid crowding the last label with the previous stepped label
                if (i == count - 1 && i % labelStep != 0 && (i % labelStep) < labelStep / 2) {
                    continue
                }
                canvas.drawText(item.label, centerX, baselineY + 16f * density, axisTextPaint)
            }
        }

        // Draw floating tooltip for selected bar
        if (selectedIndex in data.indices) {
            val sel = data[selectedIndex]
            val valueDesc = if (sel.secondaryValue > 0.001f) {
                "${sel.label}: ${numFormat.format(sel.primaryValue)} + ${numFormat.format(sel.secondaryValue)}"
            } else {
                val u = if (unitSuffix.isNotEmpty()) " $unitSuffix" else ""
                "${sel.label}: ${numFormat.format(sel.primaryValue)}$u"
            }
            val textW = tooltipTextPaint.measureText(valueDesc)
            val pillW = textW + 16f * density
            val pillH = 22f * density
            val rawCenterX = leftPad + slotW * (selectedIndex + 0.5f)
            val clampedLeft = max(leftPad, min(w - rightPad - pillW, rawCenterX - pillW / 2f))
            tooltipRect.set(clampedLeft, 2f * density, clampedLeft + pillW, 2f * density + pillH)
            canvas.drawRoundRect(tooltipRect, pillH / 2f, pillH / 2f, tooltipBgPaint)
            canvas.drawText(
                valueDesc,
                clampedLeft + 8f * density,
                2f * density + pillH * 0.68f,
                tooltipTextPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (data.isEmpty()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                val leftPad = 36f * density
                val rightPad = 10f * density
                val chartW = max(1f, width - leftPad - rightPad)
                val relX = event.x - leftPad
                val idx = ((relX / chartW) * data.size).toInt().coerceIn(0, data.size - 1)
                if (idx != selectedIndex) {
                    selectedIndex = idx
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun computeNiceMax(rawMax: Float): Float {
        if (rawMax <= 1f) return 1f
        if (rawMax <= 5f) return ceil(rawMax)
        if (rawMax <= 24f) return (ceil(rawMax / 2f) * 2f)
        val step = when {
            rawMax <= 100f -> 20f
            rawMax <= 500f -> 50f
            else -> 100f
        }
        return ceil(rawMax / step) * step
    }
}
