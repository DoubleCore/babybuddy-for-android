package eu.pkgsoftware.babybuddywidgets.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import eu.pkgsoftware.babybuddywidgets.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Horizontal proportional breakdown view for feeding types/methods, 8 diaper stool colors,
 * and medication dose distributions.
 */
class CategoryBreakdownView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class BreakdownRow(
        val label: String,
        val value: Float,
        val valueFormatted: String,
        val color: Int,
    )

    private var rows: List<BreakdownRow> = emptyList()
    private var emptyMessage: String = context.getString(R.string.stats_no_data)

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary_warm)
        textSize = 12f * scaledDensity
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary_warm)
        textSize = 11.5f * scaledDensity
        textAlign = Paint.Align.RIGHT
        isFakeBoldText = true
    }

    private val emptyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted_warm)
        textSize = 13f * scaledDensity
        textAlign = Paint.Align.CENTER
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chip_surface)
        style = Paint.Style.FILL
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val swatchStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card_stroke)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val rect = RectF()
    private val rowHeightDp = 34f

    fun setRows(
        items: List<BreakdownRow>,
        emptyText: String = context.getString(R.string.stats_no_data),
    ) {
        this.rows = items.filter { it.value > 0f }
        this.emptyMessage = emptyText
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = if (rows.isEmpty()) {
            (110f * density).roundToInt()
        } else {
            ((rows.size * rowHeightDp + 12f) * density).roundToInt()
        }
        val resolvedW = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val resolvedH = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(resolvedW, resolvedH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val total = rows.sumOf { it.value.toDouble() }.toFloat()
        if (rows.isEmpty() || total <= 0f) {
            canvas.drawText(emptyMessage, w / 2f, h / 2f + 4f * density, emptyTextPaint)
            return
        }

        val maxVal = rows.maxOf { it.value }.coerceAtLeast(1f)
        val padX = 4f * density
        val rowH = rowHeightDp * density

        for ((idx, row) in rows.withIndex()) {
            val top = 6f * density + idx * rowH
            val textBaseline = top + 14f * density

            // Color dot swatch
            val dotCx = padX + 6f * density
            val dotCy = textBaseline - 4f * density
            fillPaint.color = row.color
            canvas.drawCircle(dotCx, dotCy, 5f * density, fillPaint)
            canvas.drawCircle(dotCx, dotCy, 5f * density, swatchStrokePaint)

            // Label + Right value & pct
            canvas.drawText(row.label, dotCx + 10f * density, textBaseline, labelPaint)
            val pct = ((row.value / total) * 100f).roundToInt()
            val rightStr = "${row.valueFormatted} ($pct%)"
            canvas.drawText(rightStr, w - padX, textBaseline, valuePaint)

            // Bar track & fill
            val barTop = textBaseline + 5f * density
            val barBottom = barTop + 7f * density
            val trackW = max(1f, w - 2f * padX)
            rect.set(padX, barTop, padX + trackW, barBottom)
            canvas.drawRoundRect(rect, 3.5f * density, 3.5f * density, trackPaint)

            val fillW = max(6f * density, (row.value / maxVal) * trackW)
            rect.set(padX, barTop, padX + fillW, barBottom)
            canvas.drawRoundRect(rect, 3.5f * density, 3.5f * density, fillPaint)
        }
    }
}
