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
import kotlin.math.max
import kotlin.math.min

/**
 * 24-hour daily pattern chart (matching upstream Baby Buddy `sleep_pattern` & `feeding_pattern`):
 * X-axis = calendar days, Y-axis = time of day from 00:00 (top) to 24:00 (bottom).
 */
class DailyPatternChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class PatternBlock(
        val dayIndex: Int,
        val startMinuteOfDay: Float,
        val endMinuteOfDay: Float,
        val color: Int,
        val detail: String,
    )

    data class LegendItem(
        val label: String,
        val color: Int,
    )

    private var dayLabels: List<String> = emptyList()
    private var blocks: List<PatternBlock> = emptyList()
    private var legendItems: List<LegendItem> = emptyList()
    private var emptyMessage: String = context.getString(R.string.stats_no_data)
    private var selectedDayIndex: Int = -1

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
        textSize = 10f * scaledDensity
    }

    private val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary_warm)
        textSize = 10.5f * scaledDensity
    }

    private val emptyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted_warm)
        textSize = 13f * scaledDensity
        textAlign = Paint.Align.CENTER
    }

    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
        textSize = 11f * scaledDensity
        isFakeBoldText = true
    }

    private val rect = RectF()

    fun setPattern(
        dayLabels: List<String>,
        blocks: List<PatternBlock>,
        legend: List<LegendItem> = emptyList(),
        emptyText: String = context.getString(R.string.stats_no_data),
    ) {
        this.dayLabels = dayLabels
        this.blocks = blocks
        this.legendItems = legend
        this.emptyMessage = emptyText
        this.selectedDayIndex = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (dayLabels.isEmpty() || blocks.isEmpty()) {
            canvas.drawText(emptyMessage, w / 2f, h / 2f + 4f * density, emptyTextPaint)
            return
        }

        val leftPad = 38f * density
        val rightPad = 10f * density
        val topPad = 28f * density
        val bottomPad = 24f * density

        val chartW = max(1f, w - leftPad - rightPad)
        val chartH = max(1f, h - topPad - bottomPad)

        // Legend on top-left when no tooltip is active
        if (selectedDayIndex !in dayLabels.indices && legendItems.isNotEmpty()) {
            var lx = leftPad
            val ly = 14f * density
            for (item in legendItems) {
                blockPaint.color = item.color
                canvas.drawCircle(lx + 4f * density, ly - 3.5f * density, 4f * density, blockPaint)
                lx += 11f * density
                canvas.drawText(item.label, lx, ly, legendTextPaint)
                lx += legendTextPaint.measureText(item.label) + 12f * density
                if (lx > w - rightPad - 30f * density) break
            }
        }

        // 24-hour horizontal grid lines: 00:00, 06:00, 12:00, 18:00, 24:00
        val hourTicks = listOf(0, 6, 12, 18, 24)
        axisTextPaint.textAlign = Paint.Align.RIGHT
        for (hr in hourTicks) {
            val y = topPad + (hr / 24f) * chartH
            canvas.drawLine(leftPad, y, leftPad + chartW, y, gridPaint)
            val lbl = "%02d:00".format(hr)
            canvas.drawText(lbl, leftPad - 5f * density, y + 3.5f * density, axisTextPaint)
        }

        val dayCount = dayLabels.size
        val slotW = chartW / dayCount
        val barW = min(20f * density, max(3f * density, slotW * 0.65f))
        val cornerR = min(barW / 2f, 4f * density)

        if (selectedDayIndex in dayLabels.indices) {
            val cx = leftPad + slotW * (selectedDayIndex + 0.5f)
            rect.set(cx - slotW * 0.48f, topPad, cx + slotW * 0.48f, topPad + chartH)
            canvas.drawRoundRect(rect, 5f * density, 5f * density, highlightPaint)
        }

        for (b in blocks) {
            if (b.dayIndex !in 0 until dayCount) continue
            val cx = leftPad + slotW * (b.dayIndex + 0.5f)
            val yTop = topPad + (b.startMinuteOfDay.coerceIn(0f, 1440f) / 1440f) * chartH
            val rawBottom = topPad + (b.endMinuteOfDay.coerceIn(0f, 1440f) / 1440f) * chartH
            val yBottom = max(yTop + 4f * density, min(topPad + chartH, rawBottom))
            blockPaint.color = b.color
            rect.set(cx - barW / 2f, yTop, cx + barW / 2f, yBottom)
            canvas.drawRoundRect(rect, cornerR, cornerR, blockPaint)
        }

        val labelStep = when {
            dayCount <= 7 -> 1
            dayCount <= 14 -> 2
            dayCount <= 35 -> 5
            else -> max(7, dayCount / 6)
        }
        axisTextPaint.textAlign = Paint.Align.CENTER
        for (i in dayLabels.indices) {
            if (i % labelStep == 0 || i == dayCount - 1) {
                if (i == dayCount - 1 && i % labelStep != 0 && (i % labelStep) < labelStep / 2) continue
                val cx = leftPad + slotW * (i + 0.5f)
                canvas.drawText(dayLabels[i], cx, topPad + chartH + 15f * density, axisTextPaint)
            }
        }

        if (selectedDayIndex in dayLabels.indices) {
            val dayBlocks = blocks.filter { it.dayIndex == selectedDayIndex }
            val summary = if (dayBlocks.isEmpty()) {
                "${dayLabels[selectedDayIndex]}: 0"
            } else {
                val preview = dayBlocks.first().detail
                if (dayBlocks.size == 1) {
                    "${dayLabels[selectedDayIndex]} · $preview"
                } else {
                    "${dayLabels[selectedDayIndex]} · ${dayBlocks.size}x ($preview…)"
                }
            }
            val textW = tooltipTextPaint.measureText(summary)
            val pillW = min(w - leftPad - rightPad, textW + 16f * density)
            val pillH = 22f * density
            val rawCx = leftPad + slotW * (selectedDayIndex + 0.5f)
            val clampedLeft = max(leftPad, min(w - rightPad - pillW, rawCx - pillW / 2f))
            rect.set(clampedLeft, 2f * density, clampedLeft + pillW, 2f * density + pillH)
            canvas.drawRoundRect(rect, pillH / 2f, pillH / 2f, tooltipBgPaint)
            canvas.drawText(summary, clampedLeft + 8f * density, 2f * density + pillH * 0.68f, tooltipTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dayLabels.isEmpty()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                val leftPad = 38f * density
                val rightPad = 10f * density
                val chartW = max(1f, width - leftPad - rightPad)
                val idx = (((event.x - leftPad) / chartW) * dayLabels.size)
                    .toInt()
                    .coerceIn(0, dayLabels.size - 1)
                if (idx != selectedDayIndex) {
                    selectedDayIndex = idx
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
