package eu.pkgsoftware.babybuddywidgets.stats

import eu.pkgsoftware.babybuddywidgets.Constants.FeedingTypeEnum
import eu.pkgsoftware.babybuddywidgets.history.BabySummaryHelper
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.BmiEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.ChangeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.FeedingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeadCircumferenceEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeightEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.SleepEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TemperatureEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.WeightEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlin.math.max
import kotlin.math.min

object StatsAggregator {
    data class DayBucketStat(
        val date: LocalDate,
        val label: String,
        val primaryValue: Float,
        val secondaryValue: Float = 0f,
    ) {
        val totalValue: Float get() = primaryValue + secondaryValue
    }

    data class GrowthPoint(
        val date: LocalDate,
        val timestampMs: Long,
        val label: String,
        val value: Float,
    )

    data class PeriodStats(
        val daysCount: Int,
        val dailyFeedingMl: List<DayBucketStat>,
        val dailySleepHours: List<DayBucketStat>,
        val dailyDiapers: List<DayBucketStat>,
        val totalFeedingMl: Double,
        val totalFeedingCount: Int,
        val breastMilkCount: Int,
        val formulaCount: Int,
        val solidFoodCount: Int,
        val avgFeedingMlPerDay: Double,
        val avgFeedingCountPerDay: Double,
        val totalSleepHours: Double,
        val totalSleepCount: Int,
        val avgSleepHoursPerDay: Double,
        val totalDiaperCount: Int,
        val totalWetCount: Int,
        val totalSolidCount: Int,
        val avgDiapersPerDay: Double,
        val weightTrend: List<GrowthPoint>,
        val heightTrend: List<GrowthPoint>,
        val headCircumferenceTrend: List<GrowthPoint>,
        val bmiTrend: List<GrowthPoint>,
        val temperatureTrend: List<GrowthPoint>,
    )

    private val SHORT_DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd")

    fun aggregate(
        entries: List<TimeEntry>,
        daysCount: Int,
        nowClientTime: Date = Date(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        convertServerOffset: Boolean = true,
    ): PeriodStats {
        val safeDays = max(1, daysCount)
        val endDate = Instant.ofEpochMilli(nowClientTime.time).atZone(zoneId).toLocalDate()
        val startDate = endDate.minusDays((safeDays - 1).toLong())

        val rangeStartMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val rangeEndMs = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

        val dates = (0 until safeDays).map { startDate.plusDays(it.toLong()) }
        val dayIndexByDate = dates.withIndex().associate { it.value to it.index }

        val feedingMlByDay = DoubleArray(safeDays)
        val sleepMsByDay = LongArray(safeDays)
        val wetByDay = IntArray(safeDays)
        val solidOnlyOrBothByDay = IntArray(safeDays)

        var totalFeedingMl = 0.0
        var totalFeedingCount = 0
        var breastMilkCount = 0
        var formulaCount = 0
        var solidFoodCount = 0

        var totalSleepCount = 0
        var totalDiaperCount = 0
        var totalWetCount = 0
        var totalSolidCount = 0

        val weightPoints = mutableListOf<GrowthPoint>()
        val heightPoints = mutableListOf<GrowthPoint>()
        val headPoints = mutableListOf<GrowthPoint>()
        val bmiPoints = mutableListOf<GrowthPoint>()
        val tempPoints = mutableListOf<GrowthPoint>()

        // Growth curves look best even if slightly sparse, so we include entries within max(daysCount, 90) days
        // if few entries exist in the strict window, or strictly within window when plenty exist.
        // Here we filter strictly within [startDate, endDate] for temperature, and for growth measurements
        // we include all measurements in [startDate, endDate] (falling back to latest 10 historical points if <2 in range).
        val allWeights = mutableListOf<GrowthPoint>()
        val allHeights = mutableListOf<GrowthPoint>()
        val allHeads = mutableListOf<GrowthPoint>()
        val allBmis = mutableListOf<GrowthPoint>()

        for (entry in entries) {
            val clientStart = BabySummaryHelper.entryClientStart(entry, convertServerOffset)
            val clientEnd = BabySummaryHelper.entryClientEnd(entry, convertServerOffset)
            val entryLocalDate = Instant.ofEpochMilli(clientStart.time).atZone(zoneId).toLocalDate()
            val label = entryLocalDate.format(SHORT_DAY_FORMATTER)

            when (entry) {
                is FeedingEntry -> {
                    val idx = dayIndexByDate[entryLocalDate]
                    if (idx != null) {
                        val amt = entry.amount ?: 0.0
                        feedingMlByDay[idx] += amt
                        totalFeedingMl += amt
                        totalFeedingCount++
                        when (entry.feedingType) {
                            FeedingTypeEnum.BREAST_MILK,
                            FeedingTypeEnum.FORTIFIED_BREAST_MILK -> breastMilkCount++
                            FeedingTypeEnum.FORMULA -> formulaCount++
                            FeedingTypeEnum.SOLID_FOOD -> solidFoodCount++
                        }
                    }
                }
                is SleepEntry -> {
                    val overlapStart = max(clientStart.time, rangeStartMs)
                    val overlapEnd = min(clientEnd.time, rangeEndMs)
                    if (overlapEnd > overlapStart) {
                        totalSleepCount++
                        for (i in 0 until safeDays) {
                            val d = dates[i]
                            val dStart = d.atStartOfDay(zoneId).toInstant().toEpochMilli()
                            val dEnd = d.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                            val s = max(overlapStart, dStart)
                            val e = min(overlapEnd, dEnd)
                            if (e > s) {
                                sleepMsByDay[i] += (e - s)
                            }
                        }
                    }
                }
                is ChangeEntry -> {
                    val idx = dayIndexByDate[entryLocalDate]
                    if (idx != null) {
                        totalDiaperCount++
                        if (entry.wet) {
                            totalWetCount++
                            wetByDay[idx]++
                        }
                        if (entry.solid) {
                            totalSolidCount++
                            solidOnlyOrBothByDay[idx]++
                        }
                        if (!entry.wet && !entry.solid) {
                            wetByDay[idx]++
                        }
                    }
                }
                is WeightEntry -> {
                    val pt = GrowthPoint(entryLocalDate, clientStart.time, label, entry.weight.toFloat())
                    allWeights.add(pt)
                    if (!entryLocalDate.isBefore(startDate) && !entryLocalDate.isAfter(endDate)) {
                        weightPoints.add(pt)
                    }
                }
                is HeightEntry -> {
                    val pt = GrowthPoint(entryLocalDate, clientStart.time, label, entry.height.toFloat())
                    allHeights.add(pt)
                    if (!entryLocalDate.isBefore(startDate) && !entryLocalDate.isAfter(endDate)) {
                        heightPoints.add(pt)
                    }
                }
                is HeadCircumferenceEntry -> {
                    val pt = GrowthPoint(entryLocalDate, clientStart.time, label, entry.head_circumference.toFloat())
                    allHeads.add(pt)
                    if (!entryLocalDate.isBefore(startDate) && !entryLocalDate.isAfter(endDate)) {
                        headPoints.add(pt)
                    }
                }
                is BmiEntry -> {
                    val pt = GrowthPoint(entryLocalDate, clientStart.time, label, entry.bmi.toFloat())
                    allBmis.add(pt)
                    if (!entryLocalDate.isBefore(startDate) && !entryLocalDate.isAfter(endDate)) {
                        bmiPoints.add(pt)
                    }
                }
                is TemperatureEntry -> {
                    if (!entryLocalDate.isBefore(startDate) && !entryLocalDate.isAfter(endDate)) {
                        tempPoints.add(
                            GrowthPoint(entryLocalDate, clientStart.time, label, entry.temperature.toFloat())
                        )
                    }
                }
            }
        }

        val dailyFeeding = dates.mapIndexed { i, d ->
            DayBucketStat(d, d.format(SHORT_DAY_FORMATTER), feedingMlByDay[i].toFloat())
        }
        val dailySleep = dates.mapIndexed { i, d ->
            val hours = sleepMsByDay[i].toDouble() / 3_600_000.0
            DayBucketStat(d, d.format(SHORT_DAY_FORMATTER), hours.toFloat())
        }
        val dailyDiapers = dates.mapIndexed { i, d ->
            DayBucketStat(
                d,
                d.format(SHORT_DAY_FORMATTER),
                wetByDay[i].toFloat(),
                solidOnlyOrBothByDay[i].toFloat()
            )
        }

        val totalSleepHours = sleepMsByDay.sum().toDouble() / 3_600_000.0

        fun selectGrowthSeries(inWindow: List<GrowthPoint>, all: List<GrowthPoint>): List<GrowthPoint> {
            val base = if (inWindow.size >= 2) {
                inWindow
            } else {
                all.sortedBy { it.timestampMs }.takeLast(10)
            }
            return base.sortedBy { it.timestampMs }
        }

        return PeriodStats(
            daysCount = safeDays,
            dailyFeedingMl = dailyFeeding,
            dailySleepHours = dailySleep,
            dailyDiapers = dailyDiapers,
            totalFeedingMl = totalFeedingMl,
            totalFeedingCount = totalFeedingCount,
            breastMilkCount = breastMilkCount,
            formulaCount = formulaCount,
            solidFoodCount = solidFoodCount,
            avgFeedingMlPerDay = totalFeedingMl / safeDays,
            avgFeedingCountPerDay = totalFeedingCount.toDouble() / safeDays,
            totalSleepHours = totalSleepHours,
            totalSleepCount = totalSleepCount,
            avgSleepHoursPerDay = totalSleepHours / safeDays,
            totalDiaperCount = totalDiaperCount,
            totalWetCount = totalWetCount,
            totalSolidCount = totalSolidCount,
            avgDiapersPerDay = totalDiaperCount.toDouble() / safeDays,
            weightTrend = selectGrowthSeries(weightPoints, allWeights),
            heightTrend = selectGrowthSeries(heightPoints, allHeights),
            headCircumferenceTrend = selectGrowthSeries(headPoints, allHeads),
            bmiTrend = selectGrowthSeries(bmiPoints, allBmis),
            temperatureTrend = tempPoints.sortedBy { it.timestampMs },
        )
    }
}
