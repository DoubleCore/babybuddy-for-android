package eu.pkgsoftware.babybuddywidgets

import eu.pkgsoftware.babybuddywidgets.Constants.FeedingMethodEnum
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingTypeEnum
import eu.pkgsoftware.babybuddywidgets.history.BabySummaryHelper
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.ChangeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.FeedingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.SleepEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TemperatureEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.WeightEntry
import eu.pkgsoftware.babybuddywidgets.stats.StatsAggregator
import eu.pkgsoftware.babybuddywidgets.widgets.HorizontalDecIncEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

class BabySummaryAndStatsTest {
    private val testZone = ZoneId.of("Asia/Shanghai")

    private fun localTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Date {
        return Date(
            ZonedDateTime.of(year, month, day, hour, minute, 0, 0, testZone)
                .toInstant()
                .toEpochMilli()
        )
    }

    @Test
    fun stepEditorLinearRoundingFix() {
        // Previously 100 + increment jumped to 200 and 100 - increment dropped to 0
        assertEquals(110.0, HorizontalDecIncEditor.stepValue(100.0, 10.0, allowNull = true)!!, 1e-6)
        assertEquals(90.0, HorizontalDecIncEditor.stepValue(100.0, -10.0, allowNull = true)!!, 1e-6)

        // Decimal steps for temperature / weight without IEEE-754 noise
        val steppedTemp = HorizontalDecIncEditor.stepValue(36.8, 0.1, allowNull = true, defaultBaseline = 36.8)!!
        assertEquals(36.9, steppedTemp, 1e-6)
        assertEquals("36.9", HorizontalDecIncEditor.formatClean(steppedTemp))

        // Starting from null with defaultBaseline
        val fromBaseline = HorizontalDecIncEditor.stepValue(null, 0.1, allowNull = true, defaultBaseline = 36.8)!!
        assertEquals(36.9, fromBaseline, 1e-6)
    }

    @Test
    fun babyAgeCalculation() {
        val birth = LocalDate.of(2026, 8, 2)
        assertEquals(
            BabySummaryHelper.AgeParts(0, 0, 0),
            BabySummaryHelper.computeAgeParts(birth, LocalDate.of(2026, 8, 2))
        )
        assertEquals(
            BabySummaryHelper.AgeParts(0, 0, 12),
            BabySummaryHelper.computeAgeParts(birth, LocalDate.of(2026, 8, 14))
        )
        assertEquals(
            BabySummaryHelper.AgeParts(0, 3, 12),
            BabySummaryHelper.computeAgeParts(birth, LocalDate.of(2026, 11, 14))
        )
        assertEquals(
            BabySummaryHelper.AgeParts(1, 2, 5),
            BabySummaryHelper.computeAgeParts(birth, LocalDate.of(2027, 10, 7))
        )
    }

    @Test
    fun todaySummaryAndOvernightSleep() {
        val now = localTime(2026, 11, 14, 15, 0)
        val entries = listOf(
            FeedingEntry(
                id = 1,
                childId = 1,
                start = localTime(2026, 11, 14, 9, 0),
                end = localTime(2026, 11, 14, 9, 20),
                _notes = "",
                feedingType = FeedingTypeEnum.BREAST_MILK,
                feedingMethod = FeedingMethodEnum.LEFT_BREAST,
                amount = 90.0
            ),
            FeedingEntry(
                id = 2,
                childId = 1,
                start = localTime(2026, 11, 14, 13, 0),
                end = localTime(2026, 11, 14, 13, 15),
                _notes = "",
                feedingType = FeedingTypeEnum.FORMULA,
                feedingMethod = FeedingMethodEnum.BOTTLE,
                amount = 120.0
            ),
            // Overnight sleep from 22:00 yesterday to 06:30 today -> 390 minutes today
            SleepEntry(
                id = 3,
                childId = 1,
                start = localTime(2026, 11, 13, 22, 0),
                end = localTime(2026, 11, 14, 6, 30),
                _notes = ""
            ),
            ChangeEntry(
                id = 4,
                childId = 1,
                start = localTime(2026, 11, 14, 14, 0),
                _notes = "",
                wet = true,
                solid = true,
                color = "yellow",
                amount = null
            )
        )

        val summary = BabySummaryHelper.computeTodaySummary(
            entries = entries,
            nowClientTime = now,
            zoneId = testZone,
            convertServerOffset = false
        )

        assertNotNull(summary.lastFeeding)
        assertEquals(2, summary.lastFeeding!!.id)
        assertEquals(2, summary.todayFeedingCount)
        assertEquals(210.0, summary.todayFeedingAmountMl, 1e-6)
        assertEquals(390, summary.todaySleepMinutes)
        assertEquals(1, summary.todayDiaperTotal)
        assertEquals(1, summary.todayDiaperWet)
        assertEquals(1, summary.todayDiaperSolid)
        assertEquals(105L, BabySummaryHelper.computeElapsedMinutes(summary.lastFeedingClientTime!!, now))
    }

    @Test
    fun statsAggregationAcross7Days() {
        val now = localTime(2026, 11, 14, 20, 0)
        val entries = listOf(
            FeedingEntry(
                id = 10,
                childId = 1,
                start = localTime(2026, 11, 13, 10, 0),
                end = localTime(2026, 11, 13, 10, 20),
                _notes = "",
                feedingType = FeedingTypeEnum.BREAST_MILK,
                feedingMethod = FeedingMethodEnum.LEFT_BREAST,
                amount = 100.0
            ),
            FeedingEntry(
                id = 11,
                childId = 1,
                start = localTime(2026, 11, 14, 11, 0),
                end = localTime(2026, 11, 14, 11, 15),
                _notes = "",
                feedingType = FeedingTypeEnum.FORMULA,
                feedingMethod = FeedingMethodEnum.BOTTLE,
                amount = 150.0
            ),
            SleepEntry(
                id = 12,
                childId = 1,
                start = localTime(2026, 11, 13, 22, 0),
                end = localTime(2026, 11, 14, 6, 0),
                _notes = ""
            ),
            WeightEntry(
                id = 13,
                childId = 1,
                start = localTime(2026, 11, 10, 12, 0),
                _notes = "",
                weight = 6.2
            ),
            WeightEntry(
                id = 14,
                childId = 1,
                start = localTime(2026, 11, 14, 12, 0),
                _notes = "",
                weight = 6.45
            ),
            TemperatureEntry(
                id = 15,
                childId = 1,
                start = localTime(2026, 11, 14, 18, 0),
                _notes = "",
                temperature = 36.8
            )
        )

        val stats = StatsAggregator.aggregate(
            entries = entries,
            daysCount = 7,
            nowClientTime = now,
            zoneId = testZone,
            convertServerOffset = false
        )

        assertEquals(7, stats.dailyFeedingMl.size)
        assertEquals(250.0, stats.totalFeedingMl, 1e-4)
        assertEquals(1, stats.breastMilkCount)
        assertEquals(1, stats.formulaCount)

        // Overnight sleep 22:00 Nov 13 -> 06:00 Nov 14 splits into 2.0h on Nov 13 and 6.0h on Nov 14
        assertEquals(2.0f, stats.dailySleepHours[5].primaryValue, 1e-3f)
        assertEquals(6.0f, stats.dailySleepHours[6].primaryValue, 1e-3f)
        assertEquals(8.0, stats.totalSleepHours, 1e-3)

        assertEquals(2, stats.weightTrend.size)
        assertEquals(6.2f, stats.weightTrend[0].value, 1e-3f)
        assertEquals(6.45f, stats.weightTrend[1].value, 1e-3f)
        assertEquals(1, stats.temperatureTrend.size)
        assertEquals(36.8f, stats.temperatureTrend[0].value, 1e-3f)
    }

    @Test
    fun sixCategorySummaryAndVaccineSchedule() {
        val now = localTime(2026, 11, 14, 18, 30)
        val entries = listOf(
            eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.PumpingEntry(
                id = 21,
                childId = 1,
                _start = localTime(2026, 11, 14, 10, 0),
                _end = localTime(2026, 11, 14, 10, 15),
                _notes = "",
                amount = 110.0,
                _legacyTime = null
            ),
            eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TummyTimeEntry(
                id = 22,
                childId = 1,
                start = localTime(2026, 11, 14, 11, 0),
                end = localTime(2026, 11, 14, 11, 24),
                _notes = ""
            ),
            eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.NoteEntry(
                id = 23,
                childId = 1,
                start = localTime(2026, 11, 14, 12, 0),
                _notes = "抚触护理"
            )
        )

        val summary = BabySummaryHelper.computeTodaySummary(
            entries = entries,
            nowClientTime = now,
            zoneId = testZone,
            convertServerOffset = false
        )
        assertEquals(1, summary.todayPumpingCount)
        assertEquals(110.0, summary.todayPumpingAmountMl, 1e-6)
        assertEquals(24, summary.todayTummyMinutes)
        assertEquals(1, summary.todayCareCount)

        val birth = LocalDate.of(2026, 5, 10)
        assertEquals(
            LocalDate.of(2026, 11, 10),
            eu.pkgsoftware.babybuddywidgets.babyflow.BabyFlowLocalStore.computeDueDate(birth, 6)
        )
    }
}
