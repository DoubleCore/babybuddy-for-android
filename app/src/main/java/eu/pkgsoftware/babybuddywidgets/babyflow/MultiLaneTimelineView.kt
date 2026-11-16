package eu.pkgsoftware.babybuddywidgets.babyflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import eu.pkgsoftware.babybuddywidgets.R
import eu.pkgsoftware.babybuddywidgets.history.BabySummaryHelper
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.ChangeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.FeedingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.NoteEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.PumpingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.SleepEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TemperatureEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TummyTimeEntry
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class MultiLaneTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class LaneEvent(
        val lane: Int, // 0: Feeding, 1: Sleep, 2: Diaper, 3: Care/Supplement
        val timeMs: Long,
        val endTimeMs: Long,
        val timeText: String,
        val titleText: String,
        val subText: String,
        val isDurationStart: Boolean = false,
        val isDurationEnd: Boolean = false,
        val pairId: Int = -1
    )

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private var events: List<LaneEvent> = emptyList()
    private val rowHeightPx = 74f * density
    private val headerHeightPx = 44f * density
    private val leftTimeWidthPx = 56f * density

    private val laneBgColors = intArrayOf(
        0x55FCF5EC,
        0x55F2F0FA,
        0x55EEF7F2,
        0x55FBF1F3
    )

    private val lanePillColors = intArrayOf(
        ContextCompat.getColor(context, R.color.bf_tile_feeding),
        ContextCompat.getColor(context, R.color.bf_tile_sleep),
        ContextCompat.getColor(context, R.color.bf_tile_diaper),
        ContextCompat.getColor(context, R.color.bf_tile_supplement)
    )

    private val laneStrokeColors = intArrayOf(
        ContextCompat.getColor(context, R.color.feeding_accent),
        ContextCompat.getColor(context, R.color.sleep_accent),
        ContextCompat.getColor(context, R.color.diaper_accent),
        ContextCompat.getColor(context, R.color.pumping_accent)
    )

    private val laneFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card_surface)
        style = Paint.Style.FILL
    }
    private val cardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val connectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
    }
    private val axisLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card_stroke)
        strokeWidth = 1.2f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * scaledDensity
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val timeAxisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary_warm)
        textSize = 11f * scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val cardTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted_warm)
        textSize = 9.5f * scaledDensity
    }
    private val cardTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * scaledDensity
        isFakeBoldText = true
    }
    private val cardSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary_warm)
        textSize = 10f * scaledDensity
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted_warm)
        textSize = 13.5f * scaledDensity
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun setDayEntries(entries: List<TimeEntry>, targetDate: LocalDate = LocalDate.now()) {
        val zone = ZoneId.systemDefault()
        val dayStart = targetDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = targetDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val built = mutableListOf<LaneEvent>()
        val res = context.resources

        for (e in entries) {
            val s = BabySummaryHelper.entryClientStart(e).time
            val end = BabySummaryHelper.entryClientEnd(e).time
            val overlaps = (s in dayStart until dayEnd) || (end in dayStart until dayEnd)
            if (!overlaps) continue

            when (e) {
                is FeedingEntry -> {
                    val durMin = max(0L, (end - s) / 60_000L)
                    val mlPart = e.amount?.takeIf { it > 0.0 }?.let { "${it.roundToInt()} ml" } ?: ""
                    val methods = res.getStringArray(R.array.feedingMethods)
                    val methodLabel = methods.getOrNull(e.feedingMethod.value) ?: res.getString(R.string.tile_feeding)
                    val sub = listOfNotNull(
                        mlPart.ifBlank { null },
                        if (durMin > 0) "${durMin}${res.getString(R.string.bf_min_short)}" else null
                    ).joinToString(" · ")
                    built.add(
                        LaneEvent(
                            lane = 0,
                            timeMs = s,
                            endTimeMs = end,
                            timeText = timeFmt.format(Date(s)),
                            titleText = methodLabel,
                            subText = sub
                        )
                    )
                }
                is SleepEntry -> {
                    val durMin = max(1L, (end - s) / 60_000L)
                    val h = durMin / 60
                    val m = durMin % 60
                    val durStr = if (h > 0) {
                        "${h}${res.getString(R.string.bf_hour_short)} ${m}${res.getString(R.string.bf_min_short)}"
                    } else {
                        "${m}${res.getString(R.string.bf_min_short)}"
                    }
                    if (s in dayStart until dayEnd) {
                        built.add(
                            LaneEvent(
                                lane = 1,
                                timeMs = s,
                                endTimeMs = end,
                                timeText = timeFmt.format(Date(s)),
                                titleText = res.getString(R.string.logging_start_label),
                                subText = e.notes.ifBlank { res.getString(R.string.tile_sleep) },
                                isDurationStart = true,
                                pairId = e.id
                            )
                        )
                    }
                    if (end in dayStart until dayEnd && end > s + 60_000L) {
                        built.add(
                            LaneEvent(
                                lane = 1,
                                timeMs = end,
                                endTimeMs = end,
                                timeText = timeFmt.format(Date(end)),
                                titleText = res.getString(R.string.logging_end_label),
                                subText = durStr,
                                isDurationEnd = true,
                                pairId = e.id
                            )
                        )
                    }
                }
                is ChangeEntry -> {
                    val title = when {
                        e.wet && e.solid -> res.getString(R.string.bf_diaper_both_short)
                        e.solid -> res.getString(R.string.diaper_solid_label)
                        else -> res.getString(R.string.diaper_wet_label)
                    }
                    built.add(
                        LaneEvent(
                            lane = 2,
                            timeMs = s,
                            endTimeMs = s,
                            timeText = timeFmt.format(Date(s)),
                            titleText = title,
                            subText = e.notes.ifBlank { res.getString(R.string.tile_diaper) }
                        )
                    )
                }
                is PumpingEntry -> {
                    val ml = if (e.amount > 0.0) "${e.amount.roundToInt()} ml" else ""
                    built.add(
                        LaneEvent(
                            lane = 3,
                            timeMs = s,
                            endTimeMs = end,
                            timeText = timeFmt.format(Date(s)),
                            titleText = res.getString(R.string.bf_cat_supplement),
                            subText = ml.ifBlank { e.notes }
                        )
                    )
                }
                is TummyTimeEntry -> {
                    val durMin = max(1L, (end - s) / 60_000L)
                    built.add(
                        LaneEvent(
                            lane = 3,
                            timeMs = s,
                            endTimeMs = end,
                            timeText = timeFmt.format(Date(s)),
                            titleText = res.getString(R.string.bf_cat_edu),
                            subText = e.notes.ifBlank { "${durMin}${res.getString(R.string.bf_min_short)}" }
                        )
                    )
                }
                is TemperatureEntry -> {
                    built.add(
                        LaneEvent(
                            lane = 3,
                            timeMs = s,
                            endTimeMs = s,
                            timeText = timeFmt.format(Date(s)),
                            titleText = res.getString(R.string.tile_temperature),
                            subText = "${BabySummaryHelper.formatCleanNumber(e.temperature)} °C"
                        )
                    )
                }
                is NoteEntry -> {
                    built.add(
                        LaneEvent(
                            lane = 3,
                            timeMs = s,
                            endTimeMs = s,
                            timeText = timeFmt.format(Date(s)),
                            titleText = res.getString(R.string.bf_cat_care),
                            subText = e.notes.take(12)
                        )
                    )
                }
            }
        }

        events = built.sortedBy { it.timeMs }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val contentRows = max(5, events.size)
        val desiredH = (headerHeightPx + contentRows * rowHeightPx + 32f * density).toInt()
        setMeasuredDimension(w, resolveSize(desiredH, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val laneAreaLeft = leftTimeWidthPx
        val laneWidth = (w - laneAreaLeft - 8f * density) / 4f
        val res = context.resources

        // 1. Time Header & Lane Pill Headers
        canvas.drawText(
            res.getString(R.string.logging_time_label),
            leftTimeWidthPx / 2f,
            headerHeightPx * 0.62f,
            timeAxisPaint
        )

        val headers = arrayOf(
            res.getString(R.string.tile_feeding),
            res.getString(R.string.tile_sleep),
            res.getString(R.string.bf_cat_diaper),
            res.getString(R.string.bf_cat_care)
        )

        for (col in 0 until 4) {
            val l = laneAreaLeft + col * laneWidth + 3f * density
            val r = laneAreaLeft + (col + 1) * laneWidth - 3f * density

            // Header capsule
            laneFillPaint.color = lanePillColors[col]
            rect.set(l, 6f * density, r, headerHeightPx - 6f * density)
            canvas.drawRoundRect(rect, 14f * density, 14f * density, laneFillPaint)

            headerTextPaint.color = laneStrokeColors[col]
            canvas.drawText(
                headers[col],
                (l + r) / 2f,
                headerHeightPx * 0.62f,
                headerTextPaint
            )

            // Subtle vertical column tint
            laneFillPaint.color = laneBgColors[col]
            rect.set(l, headerHeightPx + 2f * density, r, h - 8f * density)
            canvas.drawRoundRect(rect, 10f * density, 10f * density, laneFillPaint)
        }

        // Left vertical axis line
        val axisX = leftTimeWidthPx * 0.5f
        canvas.drawLine(axisX, headerHeightPx + 8f * density, axisX, h - 12f * density, axisLinePaint)

        if (events.isEmpty()) {
            canvas.drawText(
                res.getString(R.string.timeline_empty_filtered),
                laneAreaLeft + (w - laneAreaLeft) / 2f,
                headerHeightPx + 110f * density,
                emptyPaint
            )
            return
        }

        // 2. Draw vertical connectors between Sleep Start & Sleep End or Feeding intervals
        val sleepStarts = mutableMapOf<Int, Float>()
        var lastFeedingBottomY: Float? = null

        for (idx in events.indices) {
            val ev = events[idx]
            val topY = headerHeightPx + 10f * density + idx * rowHeightPx
            val cardBottomY = topY + 56f * density
            val colCenterX = laneAreaLeft + (ev.lane + 0.5f) * laneWidth

            if (ev.lane == 1 && ev.isDurationStart && ev.pairId >= 0) {
                sleepStarts[ev.pairId] = cardBottomY
            } else if (ev.lane == 1 && ev.isDurationEnd && ev.pairId >= 0) {
                val startBottom = sleepStarts[ev.pairId]
                if (startBottom != null && topY > startBottom) {
                    connectorPaint.color = laneStrokeColors[1]
                    canvas.drawLine(colCenterX, startBottom, colCenterX, topY, connectorPaint)
                }
            }

            if (ev.lane == 0) {
                if (lastFeedingBottomY != null && topY > lastFeedingBottomY!!) {
                    dashedPaint.color = laneStrokeColors[0]
                    canvas.drawLine(colCenterX, lastFeedingBottomY!!, colCenterX, topY, dashedPaint)
                }
                lastFeedingBottomY = cardBottomY
            }
        }

        // 3. Draw Left Time Nodes & Event Cards
        for (idx in events.indices) {
            val ev = events[idx]
            val topY = headerHeightPx + 10f * density + idx * rowHeightPx
            val cardBottomY = topY + 56f * density

            // Time label + colored dot on left axis
            canvas.drawText(ev.timeText, axisX, topY + 14f * density, timeAxisPaint)
            dotPaint.color = laneStrokeColors[ev.lane]
            canvas.drawCircle(axisX, topY + 23f * density, 3.5f * density, dotPaint)

            // Event card in its lane
            val l = laneAreaLeft + ev.lane * laneWidth + 4f * density
            val r = laneAreaLeft + (ev.lane + 1) * laneWidth - 4f * density
            rect.set(l, topY, r, cardBottomY)

            canvas.drawRoundRect(rect, 12f * density, 12f * density, cardFillPaint)
            cardStrokePaint.color = laneStrokeColors[ev.lane]
            canvas.drawRoundRect(rect, 12f * density, 12f * density, cardStrokePaint)

            val padX = l + 7f * density
            canvas.drawText(ev.timeText, padX, topY + 14f * density, cardTimePaint)

            cardTitlePaint.color = laneStrokeColors[ev.lane]
            val clippedTitle = ellipsizeText(ev.titleText, cardTitlePaint, r - l - 12f * density)
            canvas.drawText(clippedTitle, padX, topY + 31f * density, cardTitlePaint)

            if (ev.subText.isNotBlank()) {
                val clippedSub = ellipsizeText(ev.subText, cardSubPaint, r - l - 12f * density)
                canvas.drawText(clippedSub, padX, topY + 46f * density, cardSubPaint)
            }
        }
    }

    private fun ellipsizeText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }
}
