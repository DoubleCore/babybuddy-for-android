package eu.pkgsoftware.babybuddywidgets

import eu.pkgsoftware.babybuddywidgets.Constants.FeedingMethodEnum
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingTypeEnum
import eu.pkgsoftware.babybuddywidgets.Constants.MedicationDosageUnitEnum
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.BmiEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.ChangeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.FeedingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeadCircumferenceEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeightEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.MedicationEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.PumpingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.SleepEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TemperatureEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TummyTimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.WeightEntry
import eu.pkgsoftware.babybuddywidgets.stats.StatsAggregator
import eu.pkgsoftware.babybuddywidgets.stats.WhoGrowthStandards
import eu.pkgsoftware.babybuddywidgets.stats.WhoGrowthStandards.GrowthMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

class WhoGrowthAndStatsAggregatorTest {
    private val testZone = ZoneId.of("Asia/Shanghai")

    private fun localDate(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Date {
        return Date(
            ZonedDateTime.of(year, month, day, hour, minute, 0, 0, testZone)
                .toInstant()
                .toEpochMilli()
        )
    }

    @Test
    fun whoPercentileBandsAndMedianEvaluation() {
        for (metric in GrowthMetric.entries) {
            for (sex in listOf(Child.SEX_BOY, Child.SEX_GIRL, null)) {
                val bandAt6m = WhoGrowthStandards.percentileBandAtAge(metric, sex, 6.0)
                assertNotNull("Missing WHO band for $metric / $sex", bandAt6m)
                assertTrue(bandAt6m!!.p3 < bandAt6m.p15)
                assertTrue(bandAt6m.p15 < bandAt6m.p50)
                assertTrue(bandAt6m.p50 < bandAt6m.p85)
                assertTrue(bandAt6m.p85 < bandAt6m.p97)
            }
        }

        val birth = localDate(2026, 5, 1)
        val measureAtBirth = localDate(2026, 5, 1)
        val boy = Child(
            id = 1,
            firstName = "Leo",
            lastName = "Lin",
            birthData = birth,
            slug = "leo-lin",
            sex = Child.SEX_BOY
        )
        val band0 = WhoGrowthStandards.percentileBandAtAge(GrowthMetric.WEIGHT, Child.SEX_BOY, 0.0)!!
        val evalMedian = WhoGrowthStandards.evaluateMeasurement(
            child = boy,
            metric = GrowthMetric.WEIGHT,
            measurementDate = measureAtBirth,
            value = band0.p50.toDouble()
        )
        assertNotNull(evalMedian)
        assertEquals(0.0, evalMedian!!.zScore, 0.02)
        assertEquals(50.0, evalMedian.percentile, 0.8)
        assertFalse(evalMedian.usedCorrectedAge)
        assertFalse(evalMedian.usedNeutralSexAverage)
    }

    @Test
    fun prematureDueDateCorrectedAgeEvaluation() {
        // Born 2 months early: birth 2026-01-01, expected due date 2026-03-02 (~60 days later)
        val birth = localDate(2026, 1, 1)
        val due = localDate(2026, 3, 2)
        val preemieGirl = Child(
            id = 2,
            firstName = "Mia",
            lastName = "Chen",
            birthData = birth,
            slug = "mia-chen",
            dueDate = due,
            sex = Child.SEX_GIRL
        )
        assertTrue(preemieGirl.hasCorrectedPrematureAge)

        // Measurement on 2026-06-01 (~5m chronological, ~3m corrected)
        val checkupDate = localDate(2026, 6, 1)
        val (effectiveMonths, usedCorrected) = WhoGrowthStandards.computeEffectiveAgeMonths(preemieGirl, checkupDate.time)
        assertTrue(usedCorrected)
        assertTrue(effectiveMonths in 2.8..3.2)

        val band3m = WhoGrowthStandards.percentileBandAtAge(GrowthMetric.WEIGHT, Child.SEX_GIRL, effectiveMonths)!!
        val eval = WhoGrowthStandards.evaluateMeasurement(
            child = preemieGirl,
            metric = GrowthMetric.WEIGHT,
            measurementDate = checkupDate,
            value = band3m.p50.toDouble()
        )
        assertNotNull(eval)
        assertTrue(eval!!.usedCorrectedAge)
        assertEquals(50.0, eval.percentile, 1.5)
    }

    @Test
    fun full18ReportStatsAggregationAndAllTimeRange() {
        val now = localDate(2026, 11, 15, 20, 0)
        val entries = listOf(
            // Breast + Bottle feedings
            FeedingEntry(1, 1, localDate(2026, 11, 14, 8, 0), localDate(2026, 11, 14, 8, 20), "", FeedingTypeEnum.BREAST_MILK, FeedingMethodEnum.LEFT_BREAST, 90.0),
            FeedingEntry(2, 1, localDate(2026, 11, 14, 11, 0), localDate(2026, 11, 14, 11, 15), "", FeedingTypeEnum.BREAST_MILK, FeedingMethodEnum.RIGHT_BREAST, 95.0),
            FeedingEntry(3, 1, localDate(2026, 11, 15, 9, 0), localDate(2026, 11, 15, 9, 25), "", FeedingTypeEnum.FORMULA, FeedingMethodEnum.BOTTLE, 140.0),
            // Night sleep + Daytime nap
            SleepEntry(4, 1, localDate(2026, 11, 14, 21, 0), localDate(2026, 11, 15, 6, 0), "", nap = false),
            SleepEntry(5, 1, localDate(2026, 11, 15, 12, 30), localDate(2026, 11, 15, 14, 0), "", nap = true),
            // Diapers including gray/orange stool & empty diaper
            ChangeEntry(6, 1, localDate(2026, 11, 14, 10, 0), "", wet = true, solid = true, color = "orange", amount = 45.0),
            ChangeEntry(7, 1, localDate(2026, 11, 15, 10, 0), "", wet = false, solid = true, color = "green", amount = 30.0),
            ChangeEntry(8, 1, localDate(2026, 11, 15, 15, 0), "", wet = false, solid = false, color = "", amount = null),
            // Pumping, Tummy Time with milestone, Medications
            PumpingEntry(9, 1, localDate(2026, 11, 15, 10, 30), localDate(2026, 11, 15, 10, 50), "", 110.0),
            TummyTimeEntry(10, 1, localDate(2026, 11, 15, 16, 0), localDate(2026, 11, 15, 16, 15), "Rolled front to back", "Smiling"),
            MedicationEntry(11, 1, localDate(2026, 11, 14, 9, 0), "Vitamin D3", 1.0, MedicationDosageUnitEnum.DROPS, 1440),
            MedicationEntry(12, 1, localDate(2026, 11, 15, 9, 0), "Vitamin D3", 1.0, MedicationDosageUnitEnum.DROPS, 1440),
            // Growth & Temperature
            WeightEntry(13, 1, localDate(2026, 11, 8, 12, 0), "", 6.10),
            WeightEntry(14, 1, localDate(2026, 11, 15, 12, 0), "", 6.31),
            HeightEntry(15, 1, localDate(2026, 11, 15, 12, 0), "", 63.2),
            HeadCircumferenceEntry(16, 1, localDate(2026, 11, 15, 12, 0), "", 41.0),
            BmiEntry(17, 1, localDate(2026, 11, 15, 12, 0), "", 15.8),
            TemperatureEntry(18, 1, localDate(2026, 11, 15, 18, 0), "", 36.9),
        )

        val stats = StatsAggregator.aggregate(
            entries = entries,
            daysCount = 0, // All-time dynamic span
            nowClientTime = now,
            zoneId = testZone,
            convertServerOffset = false
        )

        assertTrue(stats.daysCount >= 8)
        assertEquals(325.0, stats.totalFeedingMl, 1e-4)
        assertEquals(1, stats.breastLeftCount)
        assertEquals(1, stats.breastRightCount)
        assertEquals(3, stats.feedingPatternSlices.size)

        // 9h night sleep + 1.5h nap = 10.5h total
        assertEquals(9.0, stats.totalNightSleepHours, 1e-3)
        assertEquals(1.5, stats.totalNapHours, 1e-3)
        assertEquals(10.5, stats.totalSleepHours, 1e-3)
        assertEquals(1, stats.napCount)
        assertTrue(stats.sleepPatternSlices.size >= 3) // split across midnight + nap

        // Diapers
        assertEquals(3, stats.totalDiaperCount)
        assertEquals(1, stats.totalEmptyDiaperCount)
        assertEquals(1, stats.diaperColorCounts["orange"])
        assertEquals(1, stats.diaperColorCounts["green"])

        // Pumping, Tummy, Medication
        assertEquals(110.0, stats.totalPumpingMl, 1e-3)
        assertEquals(15.0, stats.totalTummyMinutes, 1e-3)
        assertEquals(1, stats.tummyMilestonesCount)
        assertEquals(2, stats.totalMedicationCount)
        assertEquals(2, stats.medicationCountsByName["Vitamin D3"])

        // Weight gain: +210g over 7 days = +210 g/wk
        assertNotNull(stats.weightChangeGramsPerWeek)
        assertEquals(210.0, stats.weightChangeGramsPerWeek!!, 5.0)
    }
}
