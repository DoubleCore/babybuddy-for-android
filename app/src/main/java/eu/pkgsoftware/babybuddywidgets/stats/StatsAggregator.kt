package eu.pkgsoftware.babybuddywidgets.stats

import eu.pkgsoftware.babybuddywidgets.Constants.FeedingMethodEnum
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingTypeEnum
import eu.pkgsoftware.babybuddywidgets.history.BabySummaryHelper
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.BmiEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.ChangeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.FeedingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeadCircumferenceEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeightEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.MedicationEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.PumpingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.SleepEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TemperatureEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TummyTimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.WeightEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.EnumMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

    data class SleepPatternSlice(
        val dayIndex: Int,
        val startMinuteOfDay: Float,
        val endMinuteOfDay: Float,
        val isNap: Boolean,
        val durationMinutes: Int,
    )

    data class FeedingPatternSlice(
        val dayIndex: Int,
        val startMinuteOfDay: Float,
        val endMinuteOfDay: Float,
        val feedingType: FeedingTypeEnum,
        val amountMl: Double?,
    )

    data class PeriodStats(
        val daysCount: Int,
        val dayLabels: List<String> = emptyList(),
        // Feeding (5 reports)
        val dailyFeedingMl: List<DayBucketStat>,
        val feedingPatternSlices: List<FeedingPatternSlice> = emptyList(),
        val dailyFeedingMinutes: List<DayBucketStat> = emptyList(),
        val dailyFeedingIntervalHours: List<DayBucketStat> = emptyList(),
        val feedingTypeCounts: Map<FeedingTypeEnum, Int> = emptyMap(),
        val feedingMethodCounts: Map<FeedingMethodEnum, Int> = emptyMap(),
        val totalFeedingMl: Double,
        val totalFeedingCount: Int,
        val totalFeedingMinutes: Double = 0.0,
        val breastMilkCount: Int,
        val formulaCount: Int,
        val solidFoodCount: Int,
        val breastLeftCount: Int = 0,
        val breastRightCount: Int = 0,
        val breastBothCount: Int = 0,
        val avgFeedingMlPerDay: Double,
        val avgFeedingCountPerDay: Double,
        val avgFeedingIntervalHours: Double? = null,
        // Sleep (4 reports)
        val dailySleepHours: List<DayBucketStat>,
        val sleepPatternSlices: List<SleepPatternSlice> = emptyList(),
        val dailyAvgSleepMinutes: List<DayBucketStat> = emptyList(),
        val dailyAwakeIntervalHours: List<DayBucketStat> = emptyList(),
        val totalSleepHours: Double,
        val totalNightSleepHours: Double = 0.0,
        val totalNapHours: Double = 0.0,
        val totalSleepCount: Int,
        val napCount: Int = 0,
        val avgSleepHoursPerDay: Double,
        val avgNapDurationMinutes: Double? = null,
        val avgNightSleepDurationMinutes: Double? = null,
        val avgAwakeWindowMinutes: Double? = null,
        // Diapers (4 reports)
        val dailyDiapers: List<DayBucketStat>,
        val dailyDiaperIntervalHours: List<DayBucketStat> = emptyList(),
        val dailyDiaperAmounts: List<DayBucketStat> = emptyList(),
        val diaperColorCounts: Map<String, Int> = emptyMap(),
        val totalDiaperCount: Int,
        val totalWetCount: Int,
        val totalSolidCount: Int,
        val totalEmptyDiaperCount: Int = 0,
        val totalDiaperAmount: Double = 0.0,
        val avgDiapersPerDay: Double,
        val avgDiaperIntervalHours: Double? = null,
        // Pumping, Tummy Time & Medication (3 reports)
        val dailyPumpingMl: List<DayBucketStat> = emptyList(),
        val totalPumpingMl: Double = 0.0,
        val totalPumpingCount: Int = 0,
        val dailyTummyMinutes: List<DayBucketStat> = emptyList(),
        val totalTummyMinutes: Double = 0.0,
        val totalTummyCount: Int = 0,
        val tummyMilestonesCount: Int = 0,
        val dailyMedicationDoses: List<DayBucketStat> = emptyList(),
        val totalMedicationCount: Int = 0,
        val medicationCountsByName: Map<String, Int> = emptyMap(),
        // Growth & Temperature (WHO Growth + Temperature + Weekly Rates)
        val weightTrend: List<GrowthPoint>,
        val heightTrend: List<GrowthPoint>,
        val headCircumferenceTrend: List<GrowthPoint>,
        val bmiTrend: List<GrowthPoint>,
        val temperatureTrend: List<GrowthPoint>,
        val weightChangeGramsPerWeek: Double? = null,
        val heightChangeCmPerWeek: Double? = null,
    )

    private val SHORT_DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd")

    fun aggregate(
        entries: List<TimeEntry>,
        daysCount: Int,
        nowClientTime: Date = Date(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        convertServerOffset: Boolean = true,
    ): PeriodStats {
        val endDate = Instant.ofEpochMilli(nowClientTime.time).atZone(zoneId).toLocalDate()
        val safeDays: Int = if (daysCount <= 0) {
            val earliest = entries.minOfOrNull { entry ->
                val cs = BabySummaryHelper.entryClientStart(entry, convertServerOffset)
                Instant.ofEpochMilli(cs.time).atZone(zoneId).toLocalDate()
            }
            if (earliest == null || earliest.isAfter(endDate)) {
                7
            } else {
                val spanDays = (endDate.toEpochDay() - earliest.toEpochDay() + 1L).toInt()
                spanDays.coerceIn(7, 365)
            }
        } else {
            max(1, daysCount)
        }

        val startDate = endDate.minusDays((safeDays - 1).toLong())
        val rangeStartMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val rangeEndMs = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

        val dates = (0 until safeDays).map { startDate.plusDays(it.toLong()) }
        val dayLabels = dates.map { it.format(SHORT_DAY_FORMATTER) }
        val dayIndexByDate = dates.withIndex().associate { it.value to it.index }
        val dayStartMsArr = LongArray(safeDays) { i ->
            dates[i].atStartOfDay(zoneId).toInstant().toEpochMilli()
        }
        val dayEndMsArr = LongArray(safeDays) { i ->
            dates[i].plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        }

        val feedingPrimaryMlByDay = DoubleArray(safeDays)
        val feedingSecondaryMlByDay = DoubleArray(safeDays)
        val feedingMinutesByDay = DoubleArray(safeDays)
        val feedingPatternSlices = mutableListOf<FeedingPatternSlice>()
        val feedingsInRange = mutableListOf<Pair<Long, Long>>() // startMs to endMs
        val feedingTypeCounts = EnumMap<FeedingTypeEnum, Int>(FeedingTypeEnum::class.java)
        val feedingMethodCounts = EnumMap<FeedingMethodEnum, Int>(FeedingMethodEnum::class.java)

        val nightSleepMsByDay = LongArray(safeDays)
        val napSleepMsByDay = LongArray(safeDays)
        val sleepSessionMinutesByDay = Array(safeDays) { mutableListOf<Double>() }
        val sleepPatternSlices = mutableListOf<SleepPatternSlice>()
        val sleepsInRange = mutableListOf<Triple<Long, Long, Boolean>>() // startMs, endMs, isNap

        val wetByDay = IntArray(safeDays)
        val solidByDay = IntArray(safeDays)
        val diaperAmountByDay = DoubleArray(safeDays)
        val diaperTimesInRange = mutableListOf<Long>()
        val diaperColorCounts = LinkedHashMap<String, Int>()

        val pumpingMlByDay = DoubleArray(safeDays)
        var totalPumpingMl = 0.0
        var totalPumpingCount = 0

        val tummyMinByDay = DoubleArray(safeDays)
        var totalTummyMinutes = 0.0
        var totalTummyCount = 0
        var tummyMilestonesCount = 0

        val medDosesByDay = IntArray(safeDays)
        var totalMedicationCount = 0
        val medicationCountsByName = LinkedHashMap<String, Int>()

        var totalFeedingMl = 0.0
        var totalFeedingCount = 0
        var totalFeedingMinutes = 0.0
        var breastMilkCount = 0
        var formulaCount = 0
        var solidFoodCount = 0
        var breastLeftCount = 0
        var breastRightCount = 0
        var breastBothCount = 0

        var totalSleepCount = 0
        var napCount = 0
        var totalDiaperCount = 0
        var totalWetCount = 0
        var totalSolidCount = 0
        var totalEmptyDiaperCount = 0
        var totalDiaperAmount = 0.0

        val weightPoints = mutableListOf<GrowthPoint>()
        val heightPoints = mutableListOf<GrowthPoint>()
        val headPoints = mutableListOf<GrowthPoint>()
        val bmiPoints = mutableListOf<GrowthPoint>()
        val tempPoints = mutableListOf<GrowthPoint>()

        val allWeights = mutableListOf<GrowthPoint>()
        val allHeights = mutableListOf<GrowthPoint>()
        val allHeads = mutableListOf<GrowthPoint>()
        val allBmis = mutableListOf<GrowthPoint>()

        for (entry in entries) {
            val clientStart = BabySummaryHelper.entryClientStart(entry, convertServerOffset)
            val clientEnd = BabySummaryHelper.entryClientEnd(entry, convertServerOffset)
            val zdtStart = Instant.ofEpochMilli(clientStart.time).atZone(zoneId)
            val entryLocalDate = zdtStart.toLocalDate()
            val label = entryLocalDate.format(SHORT_DAY_FORMATTER)

            when (entry) {
                is FeedingEntry -> {
                    val idx = dayIndexByDate[entryLocalDate]
                    if (idx != null) {
                        val amt = entry.amount ?: 0.0
                        totalFeedingMl += amt
                        totalFeedingCount++
                        val durMin = max(0L, clientEnd.time - clientStart.time) / 60_000.0
                        feedingMinutesByDay[idx] += durMin
                        totalFeedingMinutes += durMin
                        feedingsInRange.add(clientStart.time to clientEnd.time)

                        feedingTypeCounts[entry.feedingType] =
                            (feedingTypeCounts[entry.feedingType] ?: 0) + 1
                        feedingMethodCounts[entry.feedingMethod] =
                            (feedingMethodCounts[entry.feedingMethod] ?: 0) + 1

                        when (entry.feedingType) {
                            FeedingTypeEnum.BREAST_MILK,
                            FeedingTypeEnum.FORTIFIED_BREAST_MILK -> {
                                breastMilkCount++
                                feedingPrimaryMlByDay[idx] += amt
                            }
                            FeedingTypeEnum.FORMULA -> {
                                formulaCount++
                                feedingSecondaryMlByDay[idx] += amt
                            }
                            FeedingTypeEnum.SOLID_FOOD -> {
                                solidFoodCount++
                                feedingSecondaryMlByDay[idx] += amt
                            }
                        }

                        when (entry.feedingMethod) {
                            FeedingMethodEnum.LEFT_BREAST -> breastLeftCount++
                            FeedingMethodEnum.RIGHT_BREAST -> breastRightCount++
                            FeedingMethodEnum.BOTH_BREASTS -> breastBothCount++
                            else -> Unit
                        }

                        val startMinOfDay = zdtStart.hour * 60f + zdtStart.minute + zdtStart.second / 60f
                        val endMinOfDay = min(1440f, startMinOfDay + max(10f, durMin.toFloat()))
                        feedingPatternSlices.add(
                            FeedingPatternSlice(
                                dayIndex = idx,
                                startMinuteOfDay = startMinOfDay,
                                endMinuteOfDay = endMinOfDay,
                                feedingType = entry.feedingType,
                                amountMl = entry.amount,
                            )
                        )
                    }
                }
                is SleepEntry -> {
                    val overlapStart = max(clientStart.time, rangeStartMs)
                    val overlapEnd = min(clientEnd.time, rangeEndMs)
                    if (overlapEnd > overlapStart) {
                        totalSleepCount++
                        if (entry.isNap) napCount++
                        val fullSessionMin = max(0L, clientEnd.time - clientStart.time) / 60_000.0
                        sleepsInRange.add(Triple(clientStart.time, clientEnd.time, entry.isNap))

                        dayIndexByDate[entryLocalDate]?.let { startIdx ->
                            sleepSessionMinutesByDay[startIdx].add(fullSessionMin)
                        }

                        for (i in 0 until safeDays) {
                            val dStart = dayStartMsArr[i]
                            val dEnd = dayEndMsArr[i]
                            val s = max(overlapStart, dStart)
                            val e = min(overlapEnd, dEnd)
                            if (e > s) {
                                val sliceMs = e - s
                                if (entry.isNap) {
                                    napSleepMsByDay[i] += sliceMs
                                } else {
                                    nightSleepMsByDay[i] += sliceMs
                                }
                                val minStart = ((s - dStart) / 60_000.0).toFloat().coerceIn(0f, 1440f)
                                val minEnd = ((e - dStart) / 60_000.0).toFloat().coerceIn(minStart, 1440f)
                                sleepPatternSlices.add(
                                    SleepPatternSlice(
                                        dayIndex = i,
                                        startMinuteOfDay = minStart,
                                        endMinuteOfDay = minEnd,
                                        isNap = entry.isNap,
                                        durationMinutes = (sliceMs / 60_000.0).roundToInt(),
                                    )
                                )
                            }
                        }
                    }
                }
                is ChangeEntry -> {
                    val idx = dayIndexByDate[entryLocalDate]
                    if (idx != null) {
                        totalDiaperCount++
                        diaperTimesInRange.add(clientStart.time)
                        if (entry.wet) {
                            totalWetCount++
                            wetByDay[idx]++
                        }
                        if (entry.solid) {
                            totalSolidCount++
                            solidByDay[idx]++
                        }
                        if (!entry.wet && !entry.solid) {
                            totalEmptyDiaperCount++
                            wetByDay[idx]++
                        }
                        entry.amount?.takeIf { it > 0.0 }?.let { amt ->
                            diaperAmountByDay[idx] += amt
                            totalDiaperAmount += amt
                        }
                        val cleanColor = entry.color.trim().lowercase()
                        if (cleanColor.isNotEmpty()) {
                            diaperColorCounts[cleanColor] = (diaperColorCounts[cleanColor] ?: 0) + 1
                        }
                    }
                }
                is PumpingEntry -> {
                    val idx = dayIndexByDate[entryLocalDate]
                    if (idx != null) {
                        totalPumpingCount++
                        totalPumpingMl += entry.amount
                        pumpingMlByDay[idx] += entry.amount
                    }
                }
                is TummyTimeEntry -> {
                    val overlapStart = max(clientStart.time, rangeStartMs)
                    val overlapEnd = min(clientEnd.time, rangeEndMs)
                    if (overlapEnd > overlapStart) {
                        totalTummyCount++
                        if (entry.milestone.isNotBlank()) tummyMilestonesCount++
                        for (i in 0 until safeDays) {
                            val s = max(overlapStart, dayStartMsArr[i])
                            val e = min(overlapEnd, dayEndMsArr[i])
                            if (e > s) {
                                val mins = (e - s) / 60_000.0
                                tummyMinByDay[i] += mins
                                totalTummyMinutes += mins
                            }
                        }
                    }
                }
                is MedicationEntry -> {
                    val idx = dayIndexByDate[entryLocalDate]
                    if (idx != null) {
                        totalMedicationCount++
                        medDosesByDay[idx]++
                        val cleanName = entry.name.trim()
                        if (cleanName.isNotEmpty()) {
                            medicationCountsByName[cleanName] = (medicationCountsByName[cleanName] ?: 0) + 1
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

        // Feeding intervals (start-to-start between consecutive feedings)
        feedingsInRange.sortBy { it.first }
        val feedingIntervalHoursByDay = Array(safeDays) { mutableListOf<Double>() }
        val allFeedingIntervalsHours = mutableListOf<Double>()
        for (i in 1 until feedingsInRange.size) {
            val gapHours = (feedingsInRange[i].first - feedingsInRange[i - 1].first) / 3_600_000.0
            if (gapHours in 0.05..24.0) {
                allFeedingIntervalsHours.add(gapHours)
                val d = Instant.ofEpochMilli(feedingsInRange[i].first).atZone(zoneId).toLocalDate()
                dayIndexByDate[d]?.let { feedingIntervalHoursByDay[it].add(gapHours) }
            }
        }

        // Sleep session stats & Awake windows (end of sleep i-1 to start of sleep i)
        sleepsInRange.sortBy { it.first }
        val napDurationsMin = sleepsInRange.filter { it.third }.map { max(0L, it.second - it.first) / 60_000.0 }
        val nightDurationsMin = sleepsInRange.filter { !it.third }.map { max(0L, it.second - it.first) / 60_000.0 }
        val awakeIntervalsByDay = Array(safeDays) { mutableListOf<Double>() }
        val allAwakeMinutes = mutableListOf<Double>()
        for (i in 1 until sleepsInRange.size) {
            val wakeMs = sleepsInRange[i].first - sleepsInRange[i - 1].second
            if (wakeMs in 60_000L..(24L * 3_600_000L)) {
                val wakeMin = wakeMs / 60_000.0
                allAwakeMinutes.add(wakeMin)
                val wakeDate = Instant.ofEpochMilli(sleepsInRange[i - 1].second).atZone(zoneId).toLocalDate()
                dayIndexByDate[wakeDate]?.let { awakeIntervalsByDay[it].add(wakeMin / 60.0) }
            }
        }

        // Diaper lifetimes / intervals
        diaperTimesInRange.sort()
        val diaperIntervalsByDay = Array(safeDays) { mutableListOf<Double>() }
        val allDiaperIntervalHours = mutableListOf<Double>()
        for (i in 1 until diaperTimesInRange.size) {
            val gapHours = (diaperTimesInRange[i] - diaperTimesInRange[i - 1]) / 3_600_000.0
            if (gapHours in 0.02..36.0) {
                allDiaperIntervalHours.add(gapHours)
                val d = Instant.ofEpochMilli(diaperTimesInRange[i]).atZone(zoneId).toLocalDate()
                dayIndexByDate[d]?.let { diaperIntervalsByDay[it].add(gapHours) }
            }
        }

        val dailyFeeding = dates.mapIndexed { i, d ->
            DayBucketStat(
                d,
                dayLabels[i],
                feedingPrimaryMlByDay[i].toFloat(),
                feedingSecondaryMlByDay[i].toFloat(),
            )
        }
        val dailyFeedingMinutes = dates.mapIndexed { i, d ->
            DayBucketStat(d, dayLabels[i], feedingMinutesByDay[i].toFloat())
        }
        val dailyFeedingIntervals = dates.mapIndexed { i, d ->
            val avg = if (feedingIntervalHoursByDay[i].isEmpty()) 0.0 else feedingIntervalHoursByDay[i].average()
            DayBucketStat(d, dayLabels[i], avg.toFloat())
        }

        val dailySleep = dates.mapIndexed { i, d ->
            val nightH = nightSleepMsByDay[i].toDouble() / 3_600_000.0
            val napH = napSleepMsByDay[i].toDouble() / 3_600_000.0
            DayBucketStat(d, dayLabels[i], nightH.toFloat(), napH.toFloat())
        }
        val dailyAvgSleepMinutes = dates.mapIndexed { i, d ->
            val avg = if (sleepSessionMinutesByDay[i].isEmpty()) 0.0 else sleepSessionMinutesByDay[i].average()
            DayBucketStat(d, dayLabels[i], avg.toFloat())
        }
        val dailyAwakeIntervalHours = dates.mapIndexed { i, d ->
            val avg = if (awakeIntervalsByDay[i].isEmpty()) 0.0 else awakeIntervalsByDay[i].average()
            DayBucketStat(d, dayLabels[i], avg.toFloat())
        }

        val dailyDiapers = dates.mapIndexed { i, d ->
            DayBucketStat(d, dayLabels[i], wetByDay[i].toFloat(), solidByDay[i].toFloat())
        }
        val dailyDiaperIntervals = dates.mapIndexed { i, d ->
            val avg = if (diaperIntervalsByDay[i].isEmpty()) 0.0 else diaperIntervalsByDay[i].average()
            DayBucketStat(d, dayLabels[i], avg.toFloat())
        }
        val dailyDiaperAmounts = dates.mapIndexed { i, d ->
            DayBucketStat(d, dayLabels[i], diaperAmountByDay[i].toFloat())
        }

        val dailyPumping = dates.mapIndexed { i, d ->
            DayBucketStat(d, dayLabels[i], pumpingMlByDay[i].toFloat())
        }
        val dailyTummy = dates.mapIndexed { i, d ->
            DayBucketStat(d, dayLabels[i], tummyMinByDay[i].toFloat())
        }
        val dailyMedDoses = dates.mapIndexed { i, d ->
            DayBucketStat(d, dayLabels[i], medDosesByDay[i].toFloat())
        }

        val totalNightHours = nightSleepMsByDay.sum().toDouble() / 3_600_000.0
        val totalNapHours = napSleepMsByDay.sum().toDouble() / 3_600_000.0
        val totalSleepHours = totalNightHours + totalNapHours

        fun selectGrowthSeries(inWindow: List<GrowthPoint>, all: List<GrowthPoint>): List<GrowthPoint> {
            val base = if (inWindow.size >= 2) {
                inWindow
            } else {
                all.sortedBy { it.timestampMs }.takeLast(12)
            }
            return base.sortedBy { it.timestampMs }
        }

        val selectedWeights = selectGrowthSeries(weightPoints, allWeights)
        val selectedHeights = selectGrowthSeries(heightPoints, allHeights)

        val weightRateGPerWeek: Double? = if (selectedWeights.size >= 2) {
            val first = selectedWeights.first()
            val last = selectedWeights.last()
            val daysDiff = (last.timestampMs - first.timestampMs) / 86_400_000.0
            if (daysDiff >= 1.0) ((last.value - first.value) * 1000.0) / (daysDiff / 7.0) else null
        } else null

        val heightRateCmPerWeek: Double? = if (selectedHeights.size >= 2) {
            val first = selectedHeights.first()
            val last = selectedHeights.last()
            val daysDiff = (last.timestampMs - first.timestampMs) / 86_400_000.0
            if (daysDiff >= 1.0) ((last.value - first.value).toDouble()) / (daysDiff / 7.0) else null
        } else null

        return PeriodStats(
            daysCount = safeDays,
            dayLabels = dayLabels,
            dailyFeedingMl = dailyFeeding,
            feedingPatternSlices = feedingPatternSlices,
            dailyFeedingMinutes = dailyFeedingMinutes,
            dailyFeedingIntervalHours = dailyFeedingIntervals,
            feedingTypeCounts = feedingTypeCounts,
            feedingMethodCounts = feedingMethodCounts,
            totalFeedingMl = totalFeedingMl,
            totalFeedingCount = totalFeedingCount,
            totalFeedingMinutes = totalFeedingMinutes,
            breastMilkCount = breastMilkCount,
            formulaCount = formulaCount,
            solidFoodCount = solidFoodCount,
            breastLeftCount = breastLeftCount,
            breastRightCount = breastRightCount,
            breastBothCount = breastBothCount,
            avgFeedingMlPerDay = totalFeedingMl / safeDays,
            avgFeedingCountPerDay = totalFeedingCount.toDouble() / safeDays,
            avgFeedingIntervalHours = allFeedingIntervalsHours.takeIf { it.isNotEmpty() }?.average(),
            dailySleepHours = dailySleep,
            sleepPatternSlices = sleepPatternSlices,
            dailyAvgSleepMinutes = dailyAvgSleepMinutes,
            dailyAwakeIntervalHours = dailyAwakeIntervalHours,
            totalSleepHours = totalSleepHours,
            totalNightSleepHours = totalNightHours,
            totalNapHours = totalNapHours,
            totalSleepCount = totalSleepCount,
            napCount = napCount,
            avgSleepHoursPerDay = totalSleepHours / safeDays,
            avgNapDurationMinutes = napDurationsMin.takeIf { it.isNotEmpty() }?.average(),
            avgNightSleepDurationMinutes = nightDurationsMin.takeIf { it.isNotEmpty() }?.average(),
            avgAwakeWindowMinutes = allAwakeMinutes.takeIf { it.isNotEmpty() }?.average(),
            dailyDiapers = dailyDiapers,
            dailyDiaperIntervalHours = dailyDiaperIntervals,
            dailyDiaperAmounts = dailyDiaperAmounts,
            diaperColorCounts = diaperColorCounts,
            totalDiaperCount = totalDiaperCount,
            totalWetCount = totalWetCount,
            totalSolidCount = totalSolidCount,
            totalEmptyDiaperCount = totalEmptyDiaperCount,
            totalDiaperAmount = totalDiaperAmount,
            avgDiapersPerDay = totalDiaperCount.toDouble() / safeDays,
            avgDiaperIntervalHours = allDiaperIntervalHours.takeIf { it.isNotEmpty() }?.average(),
            dailyPumpingMl = dailyPumping,
            totalPumpingMl = totalPumpingMl,
            totalPumpingCount = totalPumpingCount,
            dailyTummyMinutes = dailyTummy,
            totalTummyMinutes = totalTummyMinutes,
            totalTummyCount = totalTummyCount,
            tummyMilestonesCount = tummyMilestonesCount,
            dailyMedicationDoses = dailyMedDoses,
            totalMedicationCount = totalMedicationCount,
            medicationCountsByName = medicationCountsByName,
            weightTrend = selectedWeights,
            heightTrend = selectedHeights,
            headCircumferenceTrend = selectGrowthSeries(headPoints, allHeads),
            bmiTrend = selectGrowthSeries(bmiPoints, allBmis),
            temperatureTrend = tempPoints.sortedBy { it.timestampMs },
            weightChangeGramsPerWeek = weightRateGPerWeek,
            heightChangeCmPerWeek = heightRateCmPerWeek,
        )
    }
}
