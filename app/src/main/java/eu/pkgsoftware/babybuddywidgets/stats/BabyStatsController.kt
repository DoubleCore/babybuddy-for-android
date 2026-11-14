package eu.pkgsoftware.babybuddywidgets.stats

import android.view.View
import androidx.core.content.ContextCompat
import com.squareup.phrase.Phrase
import eu.pkgsoftware.babybuddywidgets.BaseFragment
import eu.pkgsoftware.babybuddywidgets.R
import eu.pkgsoftware.babybuddywidgets.databinding.BabyStatsViewBinding
import eu.pkgsoftware.babybuddywidgets.debugging.GlobalDebugObject
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.BmiEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.ChangeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.FeedingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeadCircumferenceEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeightEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.SleepEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TemperatureEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.WeightEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.text.DecimalFormat
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
            refreshData(forceNetwork = true)
        }

        binding.rangeChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val newDays = when (checkedId) {
                R.id.chipRange14d -> 14
                R.id.chipRange30d -> 30
                else -> 7
            }
            if (newDays != selectedDays) {
                selectedDays = newDays
                recomputeOrFetch()
            }
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
        if (currentChild?.id == child?.id) {
            currentChild = child
            return
        }
        currentChild = child
        latestStats = null
        if (isVisibleTab) {
            recomputeOrFetch()
        }
    }

    fun invalidateCacheForChild(childId: Int) {
        cachedEntriesByChild.remove(childId)
        if (isVisibleTab && currentChild?.id == childId) {
            refreshData(forceNetwork = true)
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
            renderStats(StatsAggregator.aggregate(cached, selectedDays))
        } else {
            refreshData(forceNetwork = true)
        }
    }

    fun refreshData(forceNetwork: Boolean) {
        val child = currentChild ?: run {
            binding.statsSwipeRefresh.isRefreshing = false
            return
        }
        if (!forceNetwork) {
            cachedEntriesByChild[child.id]?.let {
                renderStats(StatsAggregator.aggregate(it, selectedDays))
                return
            }
        }

        loadJob?.cancel()
        binding.statsSwipeRefresh.isRefreshing = true
        loadJob = fragment.mainActivity.scope.launch {
            try {
                val client = fragment.mainActivity.client.v2client
                val classes = listOf(
                    FeedingEntry::class,
                    SleepEntry::class,
                    ChangeEntry::class,
                    WeightEntry::class,
                    HeightEntry::class,
                    HeadCircumferenceEntry::class,
                    BmiEntry::class,
                    TemperatureEntry::class,
                )
                val fetchedLists = coroutineScope {
                    classes.map { cls ->
                        async {
                            try {
                                val limit = if (cls == FeedingEntry::class || cls == ChangeEntry::class || cls == SleepEntry::class) {
                                    350
                                } else {
                                    100
                                }
                                client.getEntries(cls, offset = 0, limit = limit, childId = child.id).entries
                            } catch (e: Exception) {
                                GlobalDebugObject.log("Stats fetch for ${cls.simpleName} failed: ${e.message}")
                                emptyList<TimeEntry>()
                            }
                        }
                    }.awaitAll()
                }
                val combined = fetchedLists.flatten()
                cachedEntriesByChild[child.id] = combined
                renderStats(StatsAggregator.aggregate(combined, selectedDays))
            } finally {
                binding.statsSwipeRefresh.isRefreshing = false
            }
        }
    }

    private fun renderStats(stats: StatsAggregator.PeriodStats) {
        latestStats = stats
        val ctx = fragment.requireContext()
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

        // Feeding chart
        binding.feedingChartSubtitle.text = Phrase.from(res, R.string.stats_chart_feeding_subtitle)
            .put("total", stats.totalFeedingMl.roundToInt())
            .put("count", stats.totalFeedingCount)
            .put("breast", stats.breastMilkCount)
            .put("bottle", stats.formulaCount)
            .put("solid", stats.solidFoodCount)
            .format()
        binding.feedingBarChart.setSeries(
            items = stats.dailyFeedingMl,
            primaryBarColor = ContextCompat.getColor(ctx, R.color.feeding_accent),
            unit = res.getString(R.string.unit_ml),
            emptyText = res.getString(R.string.stats_no_data)
        )

        // Sleep chart
        binding.sleepChartSubtitle.text = Phrase.from(res, R.string.stats_chart_sleep_subtitle)
            .put("hours", oneDecimal.format(stats.totalSleepHours))
            .put("count", stats.totalSleepCount)
            .format()
        binding.sleepBarChart.setSeries(
            items = stats.dailySleepHours,
            primaryBarColor = ContextCompat.getColor(ctx, R.color.sleep_accent),
            unit = "h",
            emptyText = res.getString(R.string.stats_no_data)
        )

        // Diaper chart (stacked wet + solid)
        binding.diaperChartSubtitle.text = Phrase.from(res, R.string.stats_chart_diaper_subtitle)
            .put("wet", stats.totalWetCount)
            .put("solid", stats.totalSolidCount)
            .put("total", stats.totalDiaperCount)
            .format()
        binding.diaperBarChart.setSeries(
            items = stats.dailyDiapers,
            primaryBarColor = ContextCompat.getColor(ctx, R.color.notes_accent),
            secondaryBarColor = ContextCompat.getColor(ctx, R.color.diaper_accent),
            unit = "",
            emptyText = res.getString(R.string.stats_no_data)
        )

        updateGrowthSection(stats)
    }

    private fun updateGrowthSection(stats: StatsAggregator.PeriodStats) {
        val ctx = fragment.requireContext()
        val res = fragment.resources
        val checkedId = binding.growthMetricChipGroup.checkedChipId

        data class GrowthSeriesConfig(
            val points: List<StatsAggregator.GrowthPoint>,
            val colorRes: Int,
            val unit: String
        )

        val cfg = when (checkedId) {
            R.id.chipGrowthHeight -> GrowthSeriesConfig(
                stats.heightTrend, R.color.height_accent, res.getString(R.string.unit_cm)
            )
            R.id.chipGrowthHead -> GrowthSeriesConfig(
                stats.headCircumferenceTrend, R.color.head_circumference_accent, res.getString(R.string.unit_cm)
            )
            R.id.chipGrowthBmi -> GrowthSeriesConfig(
                stats.bmiTrend, R.color.bmi_accent, ""
            )
            R.id.chipGrowthTemp -> GrowthSeriesConfig(
                stats.temperatureTrend, R.color.temperature_accent, res.getString(R.string.unit_celsius)
            )
            else -> GrowthSeriesConfig(
                stats.weightTrend, R.color.weight_accent, res.getString(R.string.unit_kg)
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

        binding.growthTrendChart.setPoints(
            items = cfg.points,
            accentColor = ContextCompat.getColor(ctx, cfg.colorRes),
            unit = cfg.unit,
            emptyText = res.getString(R.string.stats_no_data)
        )
    }
}
