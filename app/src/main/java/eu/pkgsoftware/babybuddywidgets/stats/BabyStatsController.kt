package eu.pkgsoftware.babybuddywidgets.stats

import android.graphics.Color
import android.view.View
import androidx.core.content.ContextCompat
import com.squareup.phrase.Phrase
import eu.pkgsoftware.babybuddywidgets.BaseFragment
import eu.pkgsoftware.babybuddywidgets.Constants
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingTypeEnum
import eu.pkgsoftware.babybuddywidgets.R
import eu.pkgsoftware.babybuddywidgets.databinding.BabyStatsViewBinding
import eu.pkgsoftware.babybuddywidgets.debugging.GlobalDebugObject
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.util.Date
import kotlin.math.roundToInt

class BabyStatsController(private val fragment: BaseFragment) {
    val binding: BabyStatsViewBinding = BabyStatsViewBinding.inflate(fragment.layoutInflater)
    val rootView: View get() = binding.root

    private var currentChild: Child? = null
    private var selectedDays: Int = 7
    private var latestStats: StatsAggregator.PeriodStats? = null
    private var loadJob: Job? = null
    private var isVisibleTab: Boolean = false
    private val cachedEntriesByChild = mutableMapOf<Int, List<TimeEntry>>()
    private val oneDecimal = DecimalFormat("0.0")
    private val twoDecimal = DecimalFormat("0.##")

    init {
        binding.statsSwipeRefresh.setColorSchemeResources(
            R.color.primary_color,
            R.color.teal_200
        )
        binding.statsSwipeRefresh.setOnRefreshListener {
            refreshData(forceReload = true)
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
    }

    fun setVisibleTab(visible: Boolean) {
        isVisibleTab = visible
        if (visible) {
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
        if (!forceReload) {
            cachedEntriesByChild[child.id]?.let {
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
                renderStats(StatsAggregator.aggregate(combined, selectedDays, convertServerOffset = false))
            } catch (e: Exception) {
                GlobalDebugObject.log("Local stats load failed: ${e.message}")
            } finally {
                binding.statsSwipeRefresh.isRefreshing = false
            }
        }
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
