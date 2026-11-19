package eu.pkgsoftware.babybuddywidgets

import com.fasterxml.jackson.databind.ObjectMapper
import eu.pkgsoftware.babybuddywidgets.Constants.MedicationDosageUnitEnum
import eu.pkgsoftware.babybuddywidgets.Constants.SolidDiaperColorEnum
import eu.pkgsoftware.babybuddywidgets.local.ModelValidators
import eu.pkgsoftware.babybuddywidgets.local.ValidationException
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.MedicationEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TagEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.TimeZone

class ModelValidatorsAndMedicationTest {
    private val shanghaiZone = ZoneId.of("Asia/Shanghai")
    private val shanghaiTz = TimeZone.getTimeZone("Asia/Shanghai")

    private fun epochMs(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, shanghaiZone)
            .toInstant()
            .toEpochMilli()
    }

    @Test
    fun futureAndDurationValidation() {
        val now = epochMs(2026, 11, 15, 10, 0)
        // Within 10-minute tolerance passes
        ModelValidators.validateNotFuture(now + 5 * 60_000L, nowMillis = now)

        // Beyond tolerance throws ValidationException
        var futureThrew = false
        try {
            ModelValidators.validateNotFuture(now + 30 * 60_000L, nowMillis = now)
        } catch (_: ValidationException) {
            futureThrew = true
        }
        assertTrue(futureThrew)

        // Negative duration throws ValidationException
        var negativeThrew = false
        try {
            ModelValidators.validateDuration(now, now - 60_000L)
        } catch (_: ValidationException) {
            negativeThrew = true
        }
        assertTrue(negativeThrew)

        // > 24h duration throws ValidationException
        var tooLongThrew = false
        try {
            ModelValidators.validateDuration(now, now + 25 * 3_600_000L)
        } catch (_: ValidationException) {
            tooLongThrew = true
        }
        assertTrue(tooLongThrew)
    }

    @Test
    fun autoNapWindowDetection() {
        // Default daytime nap window: 06:00 .. 18:00
        assertTrue(
            ModelValidators.resolveAutoNap(
                epochMs(2026, 11, 15, 13, 30),
                napStartMin = "06:00",
                napStartMax = "18:00",
                timeZone = shanghaiTz
            )
        )
        assertFalse(
            ModelValidators.resolveAutoNap(
                epochMs(2026, 11, 15, 21, 15),
                napStartMin = "06:00",
                napStartMax = "18:00",
                timeZone = shanghaiTz
            )
        )
        // Boundary inclusive check
        assertTrue(
            ModelValidators.resolveAutoNap(
                epochMs(2026, 11, 15, 6, 0),
                napStartMin = "06:00",
                napStartMax = "18:00",
                timeZone = shanghaiTz
            )
        )
    }

    @Test
    fun splitSleepAcrossMidnightBoundaries() {
        val start = epochMs(2026, 11, 14, 21, 30)
        val end = epochMs(2026, 11, 15, 6, 45)

        val slices = ModelValidators.splitSleepAcrossMidnights(start, end, shanghaiTz)
        assertEquals(2, slices.size)

        assertEquals("2026-11-14", slices[0].dayIso)
        assertEquals(150.0, slices[0].durationMinutes, 1e-4)
        assertEquals(21 * 60.0 + 30.0, slices[0].startMinuteOfDay, 1e-4)
        assertEquals(1440.0, slices[0].endMinuteOfDay, 1e-4)

        assertEquals("2026-11-15", slices[1].dayIso)
        assertEquals(405.0, slices[1].durationMinutes, 1e-4)
        assertEquals(0.0, slices[1].startMinuteOfDay, 1e-4)
        assertEquals(6 * 60.0 + 45.0, slices[1].endMinuteOfDay, 1e-4)
    }

    @Test
    fun bmiAndUnicodeSlugGeneration() {
        // 8.5 kg at 72.0 cm -> 8.5 / (0.72 * 0.72) = 16.3966 -> 16.40
        assertEquals(16.4, ModelValidators.calculateBmi(8.5, 72.0)!!, 1e-3)
        assertNull(ModelValidators.calculateBmi(0.0, 70.0))
        assertNull(ModelValidators.calculateBmi(7.0, -50.0))

        val existing = mutableSetOf<String>()
        val s1 = ModelValidators.slugifyUnique("小团子 Baby!", existing)
        existing.add(s1)
        assertEquals("小团子-baby", s1)

        val s2 = ModelValidators.slugifyUnique("小团子 Baby!", existing)
        existing.add(s2)
        assertEquals("小团子-baby-2", s2)

        val s3 = ModelValidators.slugifyUnique("   ---   ", existing, fallbackPrefix = "tag")
        assertEquals("tag", s3)
    }

    @Test
    fun eightStoolColorsAndTagContrastAndMedicationCountdown() {
        // Verify all 8 upstream Baby Buddy diaper colors exist in exact order
        val expectedColors = listOf("black", "brown", "gray", "green", "orange", "red", "white", "yellow")
        assertEquals(expectedColors, SolidDiaperColorEnum.entries.map { it.post_name })

        // Verify YIQ contrast text color
        val darkTag = TagEntry(id = 1, slug = "dark", name = "Dark", color = "#1E293B")
        val lightTag = TagEntry(id = 2, slug = "light", name = "Light", color = "#FFF3CD")
        assertEquals("#FFFFFF", darkTag.complementaryTextColorHex())
        assertEquals("#2D2522", lightTag.complementaryTextColorHex())

        // Verify MedicationEntry next-dose calculation & JSON round-trip
        val doseStart = Date(epochMs(2026, 11, 15, 8, 0))
        val med = MedicationEntry(
            id = 42,
            childId = 1,
            start = doseStart,
            name = "Vitamin D3",
            dosage = 400.0,
            dosageUnit = MedicationDosageUnitEnum.DROPS,
            nextDoseIntervalMinutes = 360, // 6 hours -> 14:00
            _notes = "After morning milk",
            tags = listOf("supplement")
        )
        assertNotNull(med.nextDoseTime)
        assertEquals(epochMs(2026, 11, 15, 14, 0), med.nextDoseTime!!.time)
        assertFalse(med.isNextDoseReady(epochMs(2026, 11, 15, 12, 0)))
        assertEquals(120L * 60_000L, med.remainingMillisUntilNextDose(epochMs(2026, 11, 15, 12, 0)))
        assertTrue(med.isNextDoseReady(epochMs(2026, 11, 15, 14, 5)))

        val mapper = ObjectMapper()
        val json = mapper.writeValueAsString(med)
        val parsed = mapper.readValue(json, MedicationEntry::class.java)
        assertEquals("Vitamin D3", parsed.name)
        assertEquals(400.0, parsed.dosage, 1e-6)
        assertEquals(MedicationDosageUnitEnum.DROPS, parsed.dosageUnit)
        assertEquals(360, parsed.nextDoseIntervalMinutes)
        assertEquals(listOf("supplement"), parsed.tags)
    }
}
