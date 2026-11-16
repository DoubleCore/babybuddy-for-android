package eu.pkgsoftware.babybuddywidgets.stats

import android.app.AlertDialog
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
import eu.pkgsoftware.babybuddywidgets.R
import eu.pkgsoftware.babybuddywidgets.babyflow.BabyFlowLocalStore
import eu.pkgsoftware.babybuddywidgets.babyflow.GrowthMoment
import eu.pkgsoftware.babybuddywidgets.databinding.BabyStatsViewBinding
import eu.pkgsoftware.babybuddywidgets.debugging.GlobalDebugObject
import eu.pkgsoftware.babybuddywidgets.history.BabySummaryHelper
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
import java.time.LocalDate
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
            R.color.teal_200
        )
        binding.statsSwipeRefresh.setOnRefreshListener {
            refreshData(forceNetwork = true)
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
        if (currentChild?.id == child?.id) {
            currentChild = child
            return
        }
        currentChild = child
        latestStats = null
        if (isVisibleTab) {
            renderGrowthMoments()
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
            renderMeasurementHistoryCards(cached)
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
        renderGrowthMoments()
        if (!forceNetwork) {
            cachedEntriesByChild[child.id]?.let {
                renderMeasurementHistoryCards(it)
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
                renderMeasurementHistoryCards(combined)
                renderStats(StatsAggregator.aggregate(combined, selectedDays))
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
            val d = BabySummaryHelper.toLocalDate(BabySummaryHelper.entryClientStart(e))
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
            primaryBarColor = ContextCompat.getColor(ctx, R.color.sleep_accent),
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
