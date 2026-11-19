package eu.pkgsoftware.babybuddywidgets.stats

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.squareup.phrase.Phrase
import eu.pkgsoftware.babybuddywidgets.BaseFragment
import eu.pkgsoftware.babybuddywidgets.Constants
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingTypeEnum
import eu.pkgsoftware.babybuddywidgets.R
import eu.pkgsoftware.babybuddywidgets.babyflow.BabyFlowLocalStore
import eu.pkgsoftware.babybuddywidgets.babyflow.GrowthMoment
import eu.pkgsoftware.babybuddywidgets.databinding.BabyStatsViewBinding
import eu.pkgsoftware.babybuddywidgets.debugging.GlobalDebugObject
import eu.pkgsoftware.babybuddywidgets.history.BabySummaryHelper
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeadCircumferenceEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeightEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.WeightEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.time.LocalDate
import java.util.Date
import kotlin.math.roundToInt

class BabyStatsController(private val fragment: BaseFragment) {
    val binding: BabyStatsViewBinding = BabyStatsViewBinding.inflate(fragment.layoutInflater)
    val rootView: View get() = binding.root

    private val localStore = BabyFlowLocalStore(fragment.requireContext())

    private var currentChild: Child? = null
    private var selectedDays: Int = 7
    private var showStatsSubTab: Boolean = false
    private var latestStats: StatsAggregator.PeriodStats? = null
    private var loadJob: Job? = null
    private var isVisibleTab: Boolean = false
    private val cachedEntriesByChild = mutableMapOf<Int, List<TimeEntry>>()
    private val oneDecimal = DecimalFormat("0.0")
    private val twoDecimal = DecimalFormat("0.##")

    private fun Int.dp(): Int = (this * fragment.resources.displayMetrics.density).roundToInt()

    init {
        binding.statsSwipeRefresh.setColorSchemeResources(
            R.color.primary_color,
            R.color.bf_purple_primary
        )
        binding.statsSwipeRefresh.setOnRefreshListener {
            refreshData(forceReload = true)
        }

        // Sub-tab segmented switcher: 记录 vs 统计
        binding.segGrowthRecords.setOnClickListener {
            if (showStatsSubTab) {
                showStatsSubTab = false
                applySubTabVisuals()
            }
        }
        binding.segGrowthStats.setOnClickListener {
            if (!showStatsSubTab) {
                showStatsSubTab = true
                applySubTabVisuals()
            }
        }

        binding.btnAddGrowthMoment.setOnClickListener {
            showAddMomentDialog()
        }
        binding.addMomentBannerCard.setOnClickListener {
            showAddMomentDialog()
        }

        binding.rangeChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val newDays = when (checkedId) {
                R.id.chipRange14d -> 14
                R.id.chipRange30d -> 30
                R.id.chipRange90d -> 90
                R.id.chipRangeAll -> 0
                else -> 7
            }
            if (newDays != selectedDays) {
                selectedDays = newDays
                recomputeOrFetch()
            }
        }

        binding.feedingReportChipGroup.setOnCheckedStateChangeListener { _, _ ->
            latestStats?.let { updateFeedingSection(it) }
        }
        binding.sleepReportChipGroup.setOnCheckedStateChangeListener { _, _ ->
            latestStats?.let { updateSleepSection(it) }
        }
        binding.diaperReportChipGroup.setOnCheckedStateChangeListener { _, _ ->
            latestStats?.let { updateDiaperSection(it) }
        }
        binding.careMedsChipGroup.setOnCheckedStateChangeListener { _, _ ->
            latestStats?.let { updateCareMedsSection(it) }
        }
        binding.growthMetricChipGroup.setOnCheckedStateChangeListener { _, _ ->
            latestStats?.let { updateGrowthSection(it) }
        }

        applySubTabVisuals()
    }

    private fun applySubTabVisuals() {
        val ctx = fragment.context ?: return
        binding.growthRecordsContainer.visibility = if (showStatsSubTab) View.GONE else View.VISIBLE
        binding.growthStatsChartsContainer.visibility = if (showStatsSubTab) View.VISIBLE else View.GONE

        if (showStatsSubTab) {
            binding.segGrowthStats.setBackgroundResource(R.drawable.bg_bf_segment_selected)
            binding.segGrowthStats.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
            binding.segGrowthRecords.background = null
            binding.segGrowthRecords.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
        } else {
            binding.segGrowthRecords.setBackgroundResource(R.drawable.bg_bf_segment_selected)
            binding.segGrowthRecords.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
            binding.segGrowthStats.background = null
            binding.segGrowthStats.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
        }
    }

    fun setVisibleTab(visible: Boolean) {
        isVisibleTab = visible
        if (visible) {
            renderGrowthMoments()
            recomputeOrFetch()
        }
    }

    fun setChild(child: Child?) {
        if (currentChild == child) {
            return
        }
        val sameId = currentChild?.id == child?.id
        currentChild = child
        if (!sameId) {
            latestStats = null
        }
        if (isVisibleTab) {
            renderGrowthMoments()
            recomputeOrFetch()
        }
    }

    fun invalidateCacheForChild(childId: Int) {
        cachedEntriesByChild.remove(childId)
        if (isVisibleTab && currentChild?.id == childId) {
            refreshData(forceReload = true)
        }
    }

    fun close() {
        loadJob?.cancel()
        loadJob = null
    }

    private fun recomputeOrFetch() {
        val child = currentChild ?: return
        val cached = cachedEntriesByChild[child.id]
        if (cached != null) {
            renderMeasurementHistoryCards(cached)
            renderStats(StatsAggregator.aggregate(cached, selectedDays, convertServerOffset = false))
        } else {
            refreshData(forceReload = true)
        }
    }

    fun refreshData(forceReload: Boolean) {
        val child = currentChild ?: run {
            binding.statsSwipeRefresh.isRefreshing = false
            return
        }
        renderGrowthMoments()
        if (!forceReload) {
            cachedEntriesByChild[child.id]?.let {
                renderMeasurementHistoryCards(it)
                renderStats(StatsAggregator.aggregate(it, selectedDays, convertServerOffset = false))
                return
            }
        }

        loadJob?.cancel()
        binding.statsSwipeRefresh.isRefreshing = true
        val repo = fragment.mainActivity.repository
        loadJob = fragment.mainActivity.scope.launch {
            try {
                val combined = withContext(Dispatchers.IO) {
                    repo.getAllTimelineEntries(childId = child.id, limit = 0)
                }
                cachedEntriesByChild[child.id] = combined
                renderMeasurementHistoryCards(combined)
                renderStats(StatsAggregator.aggregate(combined, selectedDays, convertServerOffset = false))
            } catch (e: Exception) {
                GlobalDebugObject.log("Local stats load failed: ${e.message}")
            } finally {
                binding.statsSwipeRefresh.isRefreshing = false
            }
        }
    }

    private fun renderGrowthMoments() {
        val ctx = fragment.context ?: return
        val childId = currentChild?.id ?: 0
        val moments = localStore.getGrowthMoments(childId)
        val container = binding.growthMomentsList
        container.removeAllViews()

        val todayStr = LocalDate.now().toString()
        val todayCount = moments.count { it.dateStr == todayStr }
        binding.growthMomentsCountTitle.text = Phrase.from(fragment.resources, R.string.bf_growth_today_moment_count)
            .put("count", todayCount)
            .format()

        for (m in moments) {
            val card = MaterialCardView(ctx).apply {
                radius = 20.dp().toFloat()
                cardElevation = 0f
                setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_surface))
                strokeColor = ContextCompat.getColor(ctx, R.color.card_stroke)
                strokeWidth = 1.dp()
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10.dp() }
            }
            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            }
            val topMeta = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topMeta.addView(TextView(ctx).apply {
                text = m.tag
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.bf_rose_accent))
                setBackgroundResource(R.drawable.bg_bf_overview_cell)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.bf_cell_supplement)
                )
                setPadding(10.dp(), 3.dp(), 10.dp(), 3.dp())
            })
            topMeta.addView(TextView(ctx).apply {
                text = "  🚩 ${m.dateStr}"
                textSize = 11.5f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_muted_warm))
            })
            inner.addView(topMeta)

            inner.addView(TextView(ctx).apply {
                text = m.title
                textSize = 15.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
                setPadding(0, 8.dp(), 0, 3.dp())
            })
            if (m.content.isNotBlank()) {
                inner.addView(TextView(ctx).apply {
                    text = m.content
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
                })
            }
            card.addView(inner)
            container.addView(card)
        }
    }

    private fun renderMeasurementHistoryCards(entries: List<TimeEntry>) {
        val ctx = fragment.context ?: return
        val listView = binding.growthMeasurementHistoryList
        listView.removeAllViews()

        data class DayMeasure(
            var weightKg: Double? = null,
            var heightCm: Double? = null,
            var headCm: Double? = null
        )

        val byDate = sortedMapOf<LocalDate, DayMeasure>(compareByDescending { it })
        for (e in entries) {
            val d = BabySummaryHelper.toLocalDate(BabySummaryHelper.entryClientStart(e, convertServerOffset = false))
            when (e) {
                is WeightEntry -> byDate.getOrPut(d) { DayMeasure() }.weightKg = e.weight
                is HeightEntry -> byDate.getOrPut(d) { DayMeasure() }.heightCm = e.height
                is HeadCircumferenceEntry -> byDate.getOrPut(d) { DayMeasure() }.headCm = e.head_circumference
            }
        }

        if (byDate.isEmpty()) {
            listView.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.stats_no_data)
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_muted_warm))
                setPadding(12.dp(), 20.dp(), 12.dp(), 20.dp())
            })
            return
        }

        for ((date, dm) in byDate.entries.take(12)) {
            val card = MaterialCardView(ctx).apply {
                radius = 18.dp().toFloat()
                cardElevation = 0f
                setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_surface))
                strokeColor = ContextCompat.getColor(ctx, R.color.card_stroke)
                strokeWidth = 1.dp()
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8.dp() }
            }
            val body = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            }
            body.addView(TextView(ctx).apply {
                text = date.toString()
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
                setPadding(2.dp(), 0, 2.dp(), 8.dp())
            })

            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(makeMeasurePill(
                label = ctx.getString(R.string.tile_weight),
                value = dm.weightKg?.let { "${twoDecimal.format(it)} kg" } ?: "--",
                bgRes = R.color.bf_cell_feeding,
                fgRes = R.color.feeding_accent
            ))
            row.addView(makeMeasurePill(
                label = ctx.getString(R.string.tile_height),
                value = dm.heightCm?.let { "${twoDecimal.format(it)} cm" } ?: "--",
                bgRes = R.color.bf_cell_sleep,
                fgRes = R.color.sleep_accent
            ))
            row.addView(makeMeasurePill(
                label = ctx.getString(R.string.tile_head_circumference),
                value = dm.headCm?.let { "${twoDecimal.format(it)} cm" } ?: "--",
                bgRes = R.color.bf_cell_diaper,
                fgRes = R.color.diaper_accent
            ))
            body.addView(row)
            card.addView(body)
            listView.addView(card)
        }
    }

    private fun makeMeasurePill(label: String, value: String, bgRes: Int, fgRes: Int): View {
        val ctx = fragment.requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bf_overview_cell)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, bgRes)
            )
            setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 3.dp()
                marginEnd = 3.dp()
            }
            addView(TextView(ctx).apply {
                text = label
                textSize = 11f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
            })
            addView(TextView(ctx).apply {
                text = value
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, fgRes))
            })
        }
    }

    private fun showAddMomentDialog() {
        val ctx = fragment.context ?: return
        val childId = currentChild?.id ?: 0
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 12.dp(), 20.dp(), 4.dp())
        }
        val tagEd = EditText(ctx).apply {
            hint = ctx.getString(R.string.bf_growth_add_tag_hint)
            setText("#成长瞬间")
        }
        val titleEd = EditText(ctx).apply {
            hint = ctx.getString(R.string.bf_growth_add_title_hint)
        }
        val contentEd = EditText(ctx).apply {
            hint = ctx.getString(R.string.bf_growth_add_content_hint)
            minLines = 2
        }
        box.addView(tagEd)
        box.addView(titleEd)
        box.addView(contentEd)

        AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.bf_growth_add_moment))
            .setView(box)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val title = titleEd.text.toString().trim()
                if (title.isNotEmpty()) {
                    val rawTag = tagEd.text.toString().trim().ifBlank { "#成长" }
                    val cleanTag = if (rawTag.startsWith("#")) rawTag else "#$rawTag"
                    localStore.addGrowthMoment(
                        childId,
                        GrowthMoment(
                            tag = cleanTag,
                            title = title,
                            content = contentEd.text.toString().trim(),
                            dateStr = LocalDate.now().toString()
                        )
                    )
                    renderGrowthMoments()
                }
            }
            .setNegativeButton(R.string.history_delete_question_cancel_button, null)
            .show()
    }

    private fun formatHoursOrDash(hours: Double?): String {
        if (hours == null || hours <= 0.0) return "—"
        return "${oneDecimal.format(hours)} h"
    }

    private fun formatMinutesAsHmOrDash(mins: Double?): String {
        if (mins == null || mins <= 0.0) return "—"
        val totalM = mins.roundToInt()
        return if (totalM >= 60) "${totalM / 60}h ${totalM % 60}m" else "${totalM}m"
    }

    private fun renderStats(stats: StatsAggregator.PeriodStats) {
        if (fragment.isDetached || fragment.context == null) return
        latestStats = stats
        val res = fragment.resources

        binding.kpiAvgMilkValue.text = Phrase.from(res, R.string.stats_kpi_milk_value)
            .put("amount", stats.avgFeedingMlPerDay.roundToInt())
            .format()
        binding.kpiAvgFeedingsValue.text = Phrase.from(res, R.string.stats_kpi_count_value)
            .put("count", oneDecimal.format(stats.avgFeedingCountPerDay))
            .format()
        binding.kpiAvgSleepValue.text = Phrase.from(res, R.string.stats_kpi_hours_value)
            .put("hours", oneDecimal.format(stats.avgSleepHoursPerDay))
            .format()
        binding.kpiAvgDiapersValue.text = Phrase.from(res, R.string.stats_kpi_count_value)
            .put("count", oneDecimal.format(stats.avgDiapersPerDay))
            .format()

        binding.kpiAvgFeedIntervalValue.text = formatHoursOrDash(stats.avgFeedingIntervalHours)
        binding.kpiAvgAwakeValue.text = formatMinutesAsHmOrDash(stats.avgAwakeWindowMinutes)
        binding.kpiAvgDiaperIntervalValue.text = formatHoursOrDash(stats.avgDiaperIntervalHours)

        binding.kpiGrowthRateValue.text = stats.weightChangeGramsPerWeek?.let { gPerWk ->
            val sign = if (gPerWk >= 0) "+" else ""
            Phrase.from(res, R.string.stats_kpi_weekly_weight_pattern)
                .put("grams", "$sign${gPerWk.roundToInt()}")
                .format()
        } ?: "—"

        val totalBreastSessions = stats.breastLeftCount + stats.breastRightCount + stats.breastBothCount
        if (totalBreastSessions > 0) {
            binding.kpiBreastDetailText.text = Phrase.from(res, R.string.stats_kpi_breast_lr_pattern)
                .put("left", stats.breastLeftCount)
                .put("right", stats.breastRightCount)
                .put("both", stats.breastBothCount)
                .format()
            binding.kpiBreastDetailText.visibility = View.VISIBLE
        } else {
            binding.kpiBreastDetailText.visibility = View.GONE
        }

        updateFeedingSection(stats)
        updateSleepSection(stats)
        updateDiaperSection(stats)
        updateCareMedsSection(stats)
        updateGrowthSection(stats)
    }

    private fun updateFeedingSection(stats: StatsAggregator.PeriodStats) {
        val ctx = fragment.context ?: return
        val res = fragment.resources
        val checkedId = binding.feedingReportChipGroup.checkedChipId

        binding.feedingChartSubtitle.text = Phrase.from(res, R.string.stats_chart_feeding_subtitle)
            .put("total", stats.totalFeedingMl.roundToInt())
            .put("count", stats.totalFeedingCount)
            .put("breast", stats.breastMilkCount)
            .put("bottle", stats.formulaCount)
            .put("solid", stats.solidFoodCount)
            .format()

        binding.feedingBarChart.visibility = View.GONE
        binding.feedingPatternChart.visibility = View.GONE
        binding.feedingBreakdownView.visibility = View.GONE

        val feedAccent = ContextCompat.getColor(ctx, R.color.feeding_accent)
        val formulaColor = ContextCompat.getColor(ctx, R.color.pumping_accent)
        val solidColor = ContextCompat.getColor(ctx, R.color.diaper_accent)

        when (checkedId) {
            R.id.chipFeedPattern -> {
                val typeLabels = res.getStringArray(R.array.feedingTypes)
                val blocks = stats.feedingPatternSlices.map { s ->
                    val c = when (s.feedingType) {
                        FeedingTypeEnum.BREAST_MILK,
                        FeedingTypeEnum.FORTIFIED_BREAST_MILK -> feedAccent
                        FeedingTypeEnum.FORMULA -> formulaColor
                        FeedingTypeEnum.SOLID_FOOD -> solidColor
                    }
                    val tName = typeLabels.getOrNull(s.feedingType.value) ?: s.feedingType.post_name
                    val amtPart = s.amountMl?.takeIf { it > 0.0 }?.let { " ${it.roundToInt()} ml" } ?: ""
                    DailyPatternChartView.PatternBlock(
                        dayIndex = s.dayIndex,
                        startMinuteOfDay = s.startMinuteOfDay,
                        endMinuteOfDay = s.endMinuteOfDay,
                        color = c,
                        detail = "$tName$amtPart",
                    )
                }
                val legend = listOf(
                    DailyPatternChartView.LegendItem(typeLabels.getOrElse(0) { "Breast" }, feedAccent),
                    DailyPatternChartView.LegendItem(typeLabels.getOrElse(1) { "Formula" }, formulaColor),
                    DailyPatternChartView.LegendItem(typeLabels.getOrElse(3) { "Solid" }, solidColor),
                )
                binding.feedingPatternChart.setPattern(
                    dayLabels = stats.dayLabels,
                    blocks = blocks,
                    legend = legend,
                    emptyText = res.getString(R.string.stats_no_data),
                )
                binding.feedingPatternChart.visibility = View.VISIBLE
            }
            R.id.chipFeedDurations -> {
                binding.feedingBarChart.setSeries(
                    items = stats.dailyFeedingMinutes,
                    primaryBarColor = feedAccent,
                    unit = "min",
                    emptyText = res.getString(R.string.stats_no_data),
                )
                binding.feedingBarChart.visibility = View.VISIBLE
            }
            R.id.chipFeedIntervals -> {
                binding.feedingBarChart.setSeries(
                    items = stats.dailyFeedingIntervalHours,
                    primaryBarColor = feedAccent,
                    unit = "h",
                    emptyText = res.getString(R.string.stats_no_data),
                )
                binding.feedingBarChart.visibility = View.VISIBLE
            }
            R.id.chipFeedTypes -> {
                val typeLabels = res.getStringArray(R.array.feedingTypes)
                val methodLabels = res.getStringArray(R.array.feedingMethods)
                val rows = mutableListOf<CategoryBreakdownView.BreakdownRow>()
                val palette = listOf(feedAccent, formulaColor, ContextCompat.getColor(ctx, R.color.sleep_accent), solidColor)
                for ((t, cnt) in stats.feedingTypeCounts) {
                    if (cnt > 0) {
                        rows.add(
                            CategoryBreakdownView.BreakdownRow(
                                label = typeLabels.getOrElse(t.value) { t.post_name },
                                value = cnt.toFloat(),
                                valueFormatted = "${cnt}x",
                                color = palette[t.value % palette.size],
                            )
                        )
                    }
                }
                val methodColor = ContextCompat.getColor(ctx, R.color.tummy_time_accent)
                for ((m, cnt) in stats.feedingMethodCounts) {
                    if (cnt > 0) {
                        rows.add(
                            CategoryBreakdownView.BreakdownRow(
                                label = methodLabels.getOrElse(m.value) { m.post_name },
                                value = cnt.toFloat(),
                                valueFormatted = "${cnt}x",
                                color = methodColor,
                            )
                        )
                    }
                }
                binding.feedingBreakdownView.setRows(rows, res.getString(R.string.stats_no_data))
                binding.feedingBreakdownView.visibility = View.VISIBLE
            }
            else -> {
                binding.feedingBarChart.setSeries(
                    items = stats.dailyFeedingMl,
                    primaryBarColor = feedAccent,
                    secondaryBarColor = formulaColor,
                    unit = res.getString(R.string.unit_ml),
                    emptyText = res.getString(R.string.stats_no_data),
                )
                binding.feedingBarChart.visibility = View.VISIBLE
            }
        }
    }

    private fun updateSleepSection(stats: StatsAggregator.PeriodStats) {
        val ctx = fragment.context ?: return
        val res = fragment.resources
        val checkedId = binding.sleepReportChipGroup.checkedChipId

        binding.sleepChartSubtitle.text = Phrase.from(res, R.string.stats_chart_sleep_subtitle)
            .put("hours", oneDecimal.format(stats.totalSleepHours))
            .put("count", stats.totalSleepCount)
            .format()

        binding.sleepBarChart.visibility = View.GONE
        binding.sleepPatternChart.visibility = View.GONE

        val nightColor = ContextCompat.getColor(ctx, R.color.sleep_accent)
        val napColor = ContextCompat.getColor(ctx, R.color.tummy_time_accent)

        when (checkedId) {
            R.id.chipSleepPattern -> {
                val nightLbl = res.getString(R.string.stats_sleep_night_label)
                val napLbl = res.getString(R.string.stats_sleep_nap_label)
                val blocks = stats.sleepPatternSlices.map { s ->
                    DailyPatternChartView.PatternBlock(
                        dayIndex = s.dayIndex,
                        startMinuteOfDay = s.startMinuteOfDay,
                        endMinuteOfDay = s.endMinuteOfDay,
                        color = if (s.isNap) napColor else nightColor,
                        detail = "${if (s.isNap) napLbl else nightLbl} ${s.durationMinutes}m",
                    )
                }
                val legend = listOf(
                    DailyPatternChartView.LegendItem(nightLbl, nightColor),
                    DailyPatternChartView.LegendItem(napLbl, napColor),
                )
                binding.sleepPatternChart.setPattern(
                    dayLabels = stats.dayLabels,
                    blocks = blocks,
                    legend = legend,
                    emptyText = res.getString(R.string.stats_no_data),
                )
                binding.sleepPatternChart.visibility = View.VISIBLE
            }
            R.id.chipSleepDurations -> {
                binding.sleepBarChart.setSeries(
                    items = stats.dailyAvgSleepMinutes,
                    primaryBarColor = nightColor,
                    unit = "min",
                    emptyText = res.getString(R.string.stats_no_data),
                )
                binding.sleepBarChart.visibility = View.VISIBLE
            }
            R.id.chipSleepAwake -> {
                binding.sleepBarChart.setSeries(
                    items = stats.dailyAwakeIntervalHours,
                    primaryBarColor = napColor,
                    unit = "h",
                    emptyText = res.getString(R.string.stats_no_data),
                )
                binding.sleepBarChart.visibility = View.VISIBLE
            }
            else -> {
                binding.sleepBarChart.setSeries(
                    items = stats.dailySleepHours,
                    primaryBarColor = nightColor,
                    secondaryBarColor = napColor,
                    unit = "h",
                    emptyText = res.getString(R.string.stats_no_data),
                )
                binding.sleepBarChart.visibility = View.VISIBLE
            }
        }
    }

    private fun updateDiaperSection(stats: StatsAggregator.PeriodStats) {
        val ctx = fragment.context ?: return
        val res = fragment.resources
        val checkedId = binding.diaperReportChipGroup.checkedChipId

        binding.diaperChartSubtitle.text = Phrase.from(res, R.string.stats_chart_diaper_subtitle)
            .put("wet", stats.totalWetCount)
            .put("solid", stats.totalSolidCount)
            .put("total", stats.totalDiaperCount)
            .format()

        binding.diaperBarChart.visibility = View.GONE
        binding.diaperBreakdownView.visibility = View.GONE

        val wetColor = ContextCompat.getColor(ctx, R.color.notes_accent)
        val solidColor = ContextCompat.getColor(ctx, R.color.diaper_accent)

        when (checkedId) {
            R.id.chipDiaperIntervals -> {
                binding.diaperBarChart.setSeries(
                    items = stats.dailyDiaperIntervalHours,
                    primaryBarColor = solidColor,
                    unit = "h",
                    emptyText = res.getString(R.string.stats_no_data),
                )
                binding.diaperBarChart.visibility = View.VISIBLE
            }
            R.id.chipDiaperAmounts -> {
                binding.diaperBarChart.setSeries(
                    items = stats.dailyDiaperAmounts,
                    primaryBarColor = solidColor,
                    unit = "",
                    emptyText = res.getString(R.string.stats_no_data),
                )
                binding.diaperBarChart.visibility = View.VISIBLE
            }
            R.id.chipDiaperColors -> {
                val colorNames = res.getStringArray(R.array.solidDiaperColors)
                val colorSwatches = mapOf(
                    "black" to Color.parseColor("#2B2B2B"),
                    "brown" to Color.parseColor("#7B4F28"),
                    "gray" to Color.parseColor("#8E9299"),
                    "green" to Color.parseColor("#4D8B55"),
                    "orange" to Color.parseColor("#E08A3C"),
                    "red" to Color.parseColor("#C94A4A"),
                    "white" to Color.parseColor("#E5E0D8"),
                    "yellow" to Color.parseColor("#E3B842"),
                )
                val rows = Constants.SolidDiaperColorEnum.entries.mapNotNull { cEnum ->
                    val cnt = stats.diaperColorCounts[cEnum.post_name] ?: 0
                    if (cnt <= 0) return@mapNotNull null
                    val label = colorNames.getOrElse(cEnum.value) { cEnum.post_name }
                    CategoryBreakdownView.BreakdownRow(
                        label = label,
                        value = cnt.toFloat(),
                        valueFormatted = "${cnt}x",
                        color = colorSwatches[cEnum.post_name] ?: solidColor,
                    )
                }
                binding.diaperBreakdownView.setRows(rows, res.getString(R.string.stats_no_data))
                binding.diaperBreakdownView.visibility = View.VISIBLE
            }
            else -> {
                binding.diaperBarChart.setSeries(
                    items = stats.dailyDiapers,
                    primaryBarColor = wetColor,
                    secondaryBarColor = solidColor,
                    unit = "",
                    emptyText = res.getString(R.string.stats_no_data),
                )
                binding.diaperBarChart.visibility = View.VISIBLE
            }
        }
    }

    private fun updateCareMedsSection(stats: StatsAggregator.PeriodStats) {
        val ctx = fragment.context ?: return
        val res = fragment.resources
        val checkedId = binding.careMedsChipGroup.checkedChipId

        binding.careMedsBarChart.visibility = View.VISIBLE
        binding.careMedsBreakdownView.visibility = View.GONE

        when (checkedId) {
            R.id.chipCareTummy -> {
                binding.careMedsSubtitle.text = Phrase.from(res, R.string.stats_tummy_subtitle_pattern)
                    .put("total_m", stats.totalTummyMinutes.roundToInt())
                    .put("count", stats.totalTummyCount)
                    .put("milestones", stats.tummyMilestonesCount)
                    .format()
                binding.careMedsBarChart.setSeries(
                    items = stats.dailyTummyMinutes,
                    primaryBarColor = ContextCompat.getColor(ctx, R.color.tummy_time_accent),
                    unit = "min",
                    emptyText = res.getString(R.string.stats_no_data),
                )
            }
            R.id.chipCareMedication -> {
                val medColor = ContextCompat.getColor(ctx, R.color.medication_accent)
                binding.careMedsSubtitle.text = Phrase.from(res, R.string.stats_medication_subtitle_pattern)
                    .put("count", stats.totalMedicationCount)
                    .format()
                binding.careMedsBarChart.setSeries(
                    items = stats.dailyMedicationDoses,
                    primaryBarColor = medColor,
                    unit = "",
                    emptyText = res.getString(R.string.stats_no_data),
                )
                if (stats.medicationCountsByName.isNotEmpty()) {
                    val rows = stats.medicationCountsByName.entries
                        .sortedByDescending { it.value }
                        .map { (name, cnt) ->
                            CategoryBreakdownView.BreakdownRow(
                                label = name,
                                value = cnt.toFloat(),
                                valueFormatted = "${cnt}x",
                                color = medColor,
                            )
                        }
                    binding.careMedsBreakdownView.setRows(rows, res.getString(R.string.stats_no_data))
                    binding.careMedsBreakdownView.visibility = View.VISIBLE
                }
            }
            else -> {
                binding.careMedsSubtitle.text = Phrase.from(res, R.string.stats_pumping_subtitle_pattern)
                    .put("total_ml", stats.totalPumpingMl.roundToInt())
                    .put("count", stats.totalPumpingCount)
                    .format()
                binding.careMedsBarChart.setSeries(
                    items = stats.dailyPumpingMl,
                    primaryBarColor = ContextCompat.getColor(ctx, R.color.pumping_accent),
                    unit = res.getString(R.string.unit_ml),
                    emptyText = res.getString(R.string.stats_no_data),
                )
            }
        }
    }

    private fun updateGrowthSection(stats: StatsAggregator.PeriodStats) {
        val ctx = fragment.context ?: return
        val res = fragment.resources
        val child = currentChild
        val checkedId = binding.growthMetricChipGroup.checkedChipId

        data class GrowthSeriesConfig(
            val points: List<StatsAggregator.GrowthPoint>,
            val colorRes: Int,
            val unit: String,
            val whoMetric: WhoGrowthStandards.GrowthMetric?,
            val normalBand: Pair<Float, Float>? = null,
        )

        val cfg = when (checkedId) {
            R.id.chipGrowthHeight -> GrowthSeriesConfig(
                stats.heightTrend,
                R.color.height_accent,
                res.getString(R.string.unit_cm),
                WhoGrowthStandards.GrowthMetric.HEIGHT,
            )
            R.id.chipGrowthHead -> GrowthSeriesConfig(
                stats.headCircumferenceTrend,
                R.color.head_circumference_accent,
                res.getString(R.string.unit_cm),
                WhoGrowthStandards.GrowthMetric.HEAD_CIRCUMFERENCE,
            )
            R.id.chipGrowthBmi -> GrowthSeriesConfig(
                stats.bmiTrend,
                R.color.bmi_accent,
                "",
                WhoGrowthStandards.GrowthMetric.BMI,
            )
            R.id.chipGrowthTemp -> GrowthSeriesConfig(
                stats.temperatureTrend,
                R.color.temperature_accent,
                res.getString(R.string.unit_celsius),
                whoMetric = null,
                normalBand = 36.5f to 37.5f,
            )
            else -> GrowthSeriesConfig(
                stats.weightTrend,
                R.color.weight_accent,
                res.getString(R.string.unit_kg),
                WhoGrowthStandards.GrowthMetric.WEIGHT,
            )
        }

        val unitSuffix = if (cfg.unit.isNotEmpty()) " ${cfg.unit}" else ""
        binding.growthLatestSummary.text = when {
            cfg.points.isEmpty() -> res.getString(R.string.stats_no_data)
            cfg.points.size == 1 -> {
                val latestStr = "${twoDecimal.format(cfg.points.last().value.toDouble())}$unitSuffix"
                Phrase.from(res, R.string.stats_growth_latest_single)
                    .put("latest", latestStr)
                    .format()
            }
            else -> {
                val lastVal = cfg.points.last().value.toDouble()
                val prevVal = cfg.points[cfg.points.size - 2].value.toDouble()
                val diff = lastVal - prevVal
                val sign = if (diff >= 0.0) "+" else ""
                val latestStr = "${twoDecimal.format(lastVal)}$unitSuffix"
                val deltaStr = "$sign${twoDecimal.format(diff)}$unitSuffix"
                Phrase.from(res, R.string.stats_growth_latest_summary)
                    .put("latest", latestStr)
                    .put("delta", deltaStr)
                    .format()
            }
        }

        val whoBands: List<WhoGrowthStandards.PercentileBand?> =
            if (child != null && cfg.whoMetric != null && cfg.points.isNotEmpty()) {
                cfg.points.map { pt ->
                    val (ageM, _) = WhoGrowthStandards.computeEffectiveAgeMonths(child, pt.timestampMs)
                    WhoGrowthStandards.percentileBandAtAge(cfg.whoMetric, child.sex, ageM, ctx)
                }
            } else {
                emptyList()
            }

        if (child != null && cfg.whoMetric != null && cfg.points.isNotEmpty()) {
            val lastPt = cfg.points.last()
            val eval = WhoGrowthStandards.evaluateMeasurement(
                child = child,
                metric = cfg.whoMetric,
                measurementDate = Date(lastPt.timestampMs),
                value = lastPt.value.toDouble(),
                context = ctx,
            )
            if (eval != null) {
                val zSign = if (eval.zScore >= 0.0) "+" else ""
                val baseBadge = Phrase.from(res, R.string.stats_who_percentile_badge)
                    .put("percentile", oneDecimal.format(eval.percentile))
                    .put("zscore", "$zSign${twoDecimal.format(eval.zScore)}")
                    .format()
                    .toString()
                val corrSuffix = if (eval.usedCorrectedAge) " · ${res.getString(R.string.stats_who_corrected_suffix)}" else ""
                binding.growthWhoBadge.text = "$baseBadge$corrSuffix"
                binding.growthWhoBadge.visibility = View.VISIBLE
            } else {
                binding.growthWhoBadge.visibility = View.GONE
            }
        } else {
            binding.growthWhoBadge.visibility = View.GONE
        }

        binding.growthTrendChart.setPoints(
            items = cfg.points,
            accentColor = ContextCompat.getColor(ctx, cfg.colorRes),
            unit = cfg.unit,
            emptyText = res.getString(R.string.stats_no_data),
            whoBands = whoBands,
            normalReferenceBand = cfg.normalBand,
        )
    }
}
