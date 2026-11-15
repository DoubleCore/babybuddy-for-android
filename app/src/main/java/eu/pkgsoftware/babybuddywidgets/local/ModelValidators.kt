package eu.pkgsoftware.babybuddywidgets.local

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

class ValidationException(message: String) : IllegalArgumentException(message)

/**
 * Represents a slice of a sleep/timed entry split across local midnight boundaries (`00:00:00`),
 * matching upstream Baby Buddy `reports/graphs/sleep_totals.py` & `sleep_pattern.py`.
 */
data class DayDurationSlice(
    val dayIso: String,
    val dayStartMillis: Long,
    val sliceStartMillis: Long,
    val sliceEndMillis: Long,
    val startMinuteOfDay: Double,
    val endMinuteOfDay: Double,
) {
    val durationMillis: Long get() = (sliceEndMillis - sliceStartMillis).coerceAtLeast(0L)
    val durationHours: Double get() = durationMillis / 3_600_000.0
    val durationMinutes: Double get() = durationMillis / 60_000.0
}

/**
 * Pure domain validation & calculation logic matching upstream Baby Buddy `core/models.py`.
 */
object ModelValidators {
    const val DEFAULT_MAX_DURATION_HOURS = 24
    const val DEFAULT_FUTURE_TOLERANCE_MILLIS = 10L * 60_000L

    fun validateNotFuture(
        timeMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        toleranceMillis: Long = DEFAULT_FUTURE_TOLERANCE_MILLIS,
    ) {
        if (timeMillis > nowMillis + toleranceMillis) {
            throw ValidationException("Date/time cannot be in the future.")
        }
    }

    fun validateDuration(
        startMillis: Long,
        endMillis: Long,
        maxHours: Int = DEFAULT_MAX_DURATION_HOURS,
    ) {
        if (endMillis < startMillis) {
            throw ValidationException("End time cannot be earlier than start time.")
        }
        val maxMillis = maxHours * 3_600_000L
        if (endMillis - startMillis > maxMillis) {
            throw ValidationException("Duration cannot exceed $maxHours hours.")
        }
    }

    /**
     * Auto-determines whether a sleep session starting at [startMillis] is a daytime nap,
     * matching upstream `Sleep.settings.nap_start_min <= localtime(start).time() <= nap_start_max`.
     */
    fun resolveAutoNap(
        startMillis: Long,
        napStartMin: String = NapAndAppSettings.DEFAULT_NAP_START_MIN,
        napStartMax: String = NapAndAppSettings.DEFAULT_NAP_START_MAX,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Boolean {
        val minMinutes = parseHHmmToMinutes(napStartMin) ?: (6 * 60)
        val maxMinutes = parseHHmmToMinutes(napStartMax) ?: (18 * 60)

        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = startMillis
        }
        val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (minMinutes <= maxMinutes) {
            currentMinutes in minMinutes..maxMinutes
        } else {
            // Wrap-around window (e.g. 22:00..05:00)
            currentMinutes >= minMinutes || currentMinutes <= maxMinutes
        }
    }

    fun parseHHmmToMinutes(hhmm: String?): Int? {
        if (hhmm.isNullOrBlank()) return null
        val parts = hhmm.trim().split(":")
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /**
     * Splits `[startMillis, endMillis]` across local `00:00:00` midnight boundaries so cross-midnight
     * sleep sessions are accurately attributed to each calendar day.
     */
    fun splitSleepAcrossMidnights(
        startMillis: Long,
        endMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<DayDurationSlice> {
        if (endMillis <= startMillis) return emptyList()
        val slices = mutableListOf<DayDurationSlice>()
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            this.timeZone = timeZone
        }

        var cursor = startMillis
        while (cursor < endMillis) {
            val dayStartCal = Calendar.getInstance(timeZone).apply {
                timeInMillis = cursor
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val dayStart = dayStartCal.timeInMillis
            val nextMidnightCal = (dayStartCal.clone() as Calendar).apply {
                add(Calendar.DAY_OF_MONTH, 1)
            }
            val nextMidnight = nextMidnightCal.timeInMillis
            val sliceEnd = minOf(endMillis, nextMidnight)

            val startMin = ((cursor - dayStart) / 60_000.0).coerceIn(0.0, 1440.0)
            val endMin = if (sliceEnd == nextMidnight) {
                1440.0
            } else {
                ((sliceEnd - dayStart) / 60_000.0).coerceIn(startMin, 1440.0)
            }

            slices.add(
                DayDurationSlice(
                    dayIso = dayFmt.format(Date(dayStart)),
                    dayStartMillis = dayStart,
                    sliceStartMillis = cursor,
                    sliceEndMillis = sliceEnd,
                    startMinuteOfDay = startMin,
                    endMinuteOfDay = endMin,
                )
            )
            cursor = sliceEnd
        }
        return slices
    }

    /**
     * Standard BMI formula: `weightKg / ((heightCm / 100)^2)` rounded to 2 decimal places.
     */
    fun calculateBmi(weightKg: Double, heightCm: Double): Double? {
        if (weightKg <= 0.0 || heightCm <= 0.0) return null
        val heightM = heightCm / 100.0
        val raw = weightKg / (heightM * heightM)
        if (!raw.isFinite() || raw <= 0.0) return null
        return (raw * 100.0).roundToInt() / 100.0
    }

    /**
     * Unicode-preserving unique slug generator (supports Chinese, English, German, Dutch names).
     */
    fun slugifyUnique(
        raw: String,
        existingSlugs: Set<String>,
        fallbackPrefix: String = "item",
    ): String {
        val cleaned = raw.trim().lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
            .ifEmpty { fallbackPrefix }

        val lowerExisting = existingSlugs.map { it.lowercase(Locale.ROOT) }.toSet()
        if (cleaned !in lowerExisting) {
            return cleaned
        }
        var suffix = 2
        while ("$cleaned-$suffix" in lowerExisting) {
            suffix++
        }
        return "$cleaned-$suffix"
    }

    fun formatIsoDate(date: Date, timeZone: TimeZone = TimeZone.getDefault()): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = timeZone
        return sdf.format(date)
    }

    fun parseIsoDateAtNoon(isoDate: String, timeZone: TimeZone = TimeZone.getDefault()): Date {
        val parts = isoDate.trim().take(10).split("-")
        if (parts.size == 3) {
            val y = parts[0].toIntOrNull()
            val m = parts[1].toIntOrNull()
            val d = parts[2].toIntOrNull()
            if (y != null && m != null && d != null) {
                val cal = Calendar.getInstance(timeZone).apply {
                    set(Calendar.YEAR, y)
                    set(Calendar.MONTH, m - 1)
                    set(Calendar.DAY_OF_MONTH, d)
                    set(Calendar.HOUR_OF_DAY, 12)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                return cal.time
            }
        }
        return Date()
    }
}
