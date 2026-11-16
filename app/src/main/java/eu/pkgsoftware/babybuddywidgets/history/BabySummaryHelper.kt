package eu.pkgsoftware.babybuddywidgets.history

import android.content.res.Resources
import com.squareup.phrase.Phrase
import eu.pkgsoftware.babybuddywidgets.R
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.BmiEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.ChangeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.FeedingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeadCircumferenceEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeightEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.NoteEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.PumpingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.SleepEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TemperatureEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TummyTimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.WeightEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.serverTimeToClientTime
import java.text.DateFormat
import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.util.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object BabySummaryHelper {
    data class AgeParts(
        val years: Int,
        val months: Int,
        val days: Int,
    )

    enum class DayBucket {
        TODAY,
        YESTERDAY,
        OTHER
    }

    data class TodaySummary(
        val lastFeeding: FeedingEntry?,
        val lastFeedingClientTime: Date?,
        val lastSleep: SleepEntry?,
        val lastSleepClientTime: Date?,
        val lastDiaper: ChangeEntry?,
        val lastDiaperClientTime: Date?,
        val lastPumpingClientTime: Date?,
        val lastCareClientTime: Date?,
        val lastTummyClientTime: Date?,
        val todayFeedingCount: Int,
        val todayFeedingAmountMl: Double,
        val todaySleepMinutes: Int,
        val todayDiaperTotal: Int,
        val todayDiaperWet: Int,
        val todayDiaperSolid: Int,
        val todayPumpingCount: Int,
        val todayPumpingAmountMl: Double,
        val todayCareCount: Int,
        val todayTummyMinutes: Int,
    )

    fun formatGreeting(res: Resources, now: Date = Date(), zoneId: ZoneId = ZoneId.systemDefault()): String {
        val hour = Instant.ofEpochMilli(now.time).atZone(zoneId).hour
        return when (hour) {
            in 5..10 -> res.getString(R.string.bf_greeting_morning)
            in 11..13 -> res.getString(R.string.bf_greeting_noon)
            in 14..17 -> res.getString(R.string.bf_greeting_afternoon)
            else -> res.getString(R.string.bf_greeting_evening)
        }
    }

    fun formatMilestoneBadge(
        res: Resources,
        birthDate: Date,
        now: Date = Date(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Pair<String, String> {
        val birthLocal = Instant.ofEpochMilli(birthDate.time).atZone(zoneId).toLocalDate()
        val nowLocal = Instant.ofEpochMilli(now.time).atZone(zoneId).toLocalDate()
        if (birthLocal.isAfter(nowLocal)) {
            return Pair(res.getString(R.string.baby_age_newborn), res.getString(R.string.bf_milestone_growing))
        }
        val totalDays = java.time.temporal.ChronoUnit.DAYS.between(birthLocal, nowLocal).toInt()
        val parts = computeAgeParts(birthLocal, nowLocal)
        return when {
            totalDays < 30 -> {
                val left = max(1, 30 - totalDays)
                Pair(
                    res.getString(R.string.bf_milestone_full_month),
                    Phrase.from(res, R.string.bf_milestone_days_left).put("days", left).format().toString()
                )
            }
            totalDays < 100 -> {
                val left = max(1, 100 - totalDays)
                Pair(
                    res.getString(R.string.bf_milestone_100_days),
                    Phrase.from(res, R.string.bf_milestone_days_left).put("days", left).format().toString()
                )
            }
            parts.years < 1 -> {
                val oneYear = birthLocal.plusYears(1)
                val left = max(1, java.time.temporal.ChronoUnit.DAYS.between(nowLocal, oneYear).toInt())
                Pair(
                    Phrase.from(res, R.string.bf_milestone_year_n).put("years", 1).format().toString(),
                    Phrase.from(res, R.string.bf_milestone_days_left).put("days", left).format().toString()
                )
            }
            else -> {
                Pair(
                    Phrase.from(res, R.string.bf_milestone_year_n).put("years", parts.years).format().toString(),
                    res.getString(R.string.bf_milestone_growing)
                )
            }
        }
    }

    fun isDateOnlyEntry(entry: TimeEntry): Boolean {
        return entry is WeightEntry ||
            entry is HeightEntry ||
            entry is HeadCircumferenceEntry ||
            entry is BmiEntry
    }

    fun isGrowthOrHealthType(appType: String): Boolean {
        return appType == BabyBuddyClient.EVENTS.TEMPERATURE ||
            appType == BabyBuddyClient.EVENTS.WEIGHT ||
            appType == BabyBuddyClient.EVENTS.HEIGHT ||
            appType == BabyBuddyClient.EVENTS.HEAD_CIRCUMFERENCE ||
            appType == BabyBuddyClient.EVENTS.BMI
    }

    fun entryClientStart(entry: TimeEntry, convertServerOffset: Boolean = true): Date {
        return if (isDateOnlyEntry(entry) || !convertServerOffset) {
            entry.start
        } else {
            serverTimeToClientTime(entry.start)
        }
    }

    fun entryClientEnd(entry: TimeEntry, convertServerOffset: Boolean = true): Date {
        return if (isDateOnlyEntry(entry) || !convertServerOffset) {
            entry.end
        } else {
            serverTimeToClientTime(entry.end)
        }
    }

    fun computeAgeParts(birthDate: LocalDate, today: LocalDate): AgeParts {
        if (birthDate.isAfter(today)) {
            return AgeParts(0, 0, 0)
        }
        val period = Period.between(birthDate, today)
        return AgeParts(
            years = max(0, period.years),
            months = max(0, period.months),
            days = max(0, period.days),
        )
    }

    fun formatBabyAge(
        res: Resources,
        birthDate: Date,
        now: Date = Date(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val birthLocal = Instant.ofEpochMilli(birthDate.time).atZone(zoneId).toLocalDate()
        val nowLocal = Instant.ofEpochMilli(now.time).atZone(zoneId).toLocalDate()
        val parts = computeAgeParts(birthLocal, nowLocal)
        return when {
            parts.years == 0 && parts.months == 0 && parts.days <= 0 ->
                res.getString(R.string.baby_age_newborn)
            parts.years == 0 && parts.months == 0 ->
                Phrase.from(res, R.string.baby_age_days)
                    .put("days", parts.days)
                    .format()
                    .toString()
            parts.years == 0 ->
                Phrase.from(res, R.string.baby_age_months_days)
                    .put("months", parts.months)
                    .put("days", parts.days)
                    .format()
                    .toString()
            else ->
                Phrase.from(res, R.string.baby_age_years_months)
                    .put("years", parts.years)
                    .put("months", parts.months)
                    .format()
                    .toString()
        }
    }

    fun computeElapsedMinutes(pastClientTime: Date, nowClientTime: Date): Long {
        return max(0L, (nowClientTime.time - pastClientTime.time) / 60_000L)
    }

    fun formatTimeAgo(
        res: Resources,
        pastClientTime: Date?,
        nowClientTime: Date = Date()
    ): String {
        if (pastClientTime == null) {
            return res.getString(R.string.summary_no_record)
        }
        val mins = computeElapsedMinutes(pastClientTime, nowClientTime)
        return when {
            mins < 1L -> res.getString(R.string.summary_time_just_now)
            mins < 60L -> Phrase.from(res, R.string.summary_time_ago_m)
                .put("minutes", mins.toInt())
                .format()
                .toString()
            mins < 1440L -> Phrase.from(res, R.string.summary_time_ago_hm)
                .put("hours", (mins / 60L).toInt())
                .put("minutes", (mins % 60L).toInt())
                .format()
                .toString()
            else -> Phrase.from(res, R.string.summary_time_ago_d)
                .put("days", (mins / 1440L).toInt())
                .format()
                .toString()
        }
    }

    fun formatFeedingMethodDetail(res: Resources, feeding: FeedingEntry): String {
        val methods = res.getStringArray(R.array.feedingMethods)
        val types = res.getStringArray(R.array.feedingTypes)
        val methodText = methods.getOrNull(feeding.feedingMethod.value) ?: ""
        val typeText = types.getOrNull(feeding.feedingType.value) ?: ""
        val amountPart = feeding.amount?.takeIf { it > 0.0 }?.let {
            val clean = formatCleanNumber(it)
            " · $clean ${res.getString(R.string.unit_ml)}"
        } ?: ""
        val label = if (methodText.isNotEmpty()) methodText else typeText
        return "$label$amountPart"
    }

    fun formatCleanNumber(value: Double): String {
        val df = DecimalFormat("0.##")
        return df.format(value)
    }

    fun computeTodaySummary(
        entries: List<TimeEntry>,
        nowClientTime: Date = Date(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        convertServerOffset: Boolean = true,
    ): TodaySummary {
        val todayLocal = Instant.ofEpochMilli(nowClientTime.time).atZone(zoneId).toLocalDate()
        val dayStartMs = todayLocal.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val dayEndMs = todayLocal.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

        var lastFeeding: FeedingEntry? = null
        var lastFeedingTime: Date? = null
        var lastSleep: SleepEntry? = null
        var lastSleepTime: Date? = null
        var lastDiaper: ChangeEntry? = null
        var lastDiaperTime: Date? = null
        var lastPumpingTime: Date? = null
        var lastCareTime: Date? = null
        var lastTummyTime: Date? = null

        var todayFeedingCount = 0
        var todayFeedingAmount = 0.0
        var todaySleepMs = 0L
        var todayDiaperTotal = 0
        var todayDiaperWet = 0
        var todayDiaperSolid = 0
        var todayPumpingCount = 0
        var todayPumpingAmount = 0.0
        var todayCareCount = 0
        var todayTummyMs = 0L

        for (entry in entries) {
            val startClient = entryClientStart(entry, convertServerOffset)
            val endClient = entryClientEnd(entry, convertServerOffset)
            when (entry) {
                is FeedingEntry -> {
                    if (lastFeedingTime == null || endClient.after(lastFeedingTime)) {
                        lastFeeding = entry
                        lastFeedingTime = endClient
                    }
                    if (startClient.time in dayStartMs until dayEndMs) {
                        todayFeedingCount++
                        todayFeedingAmount += (entry.amount ?: 0.0)
                    }
                }
                is SleepEntry -> {
                    if (lastSleepTime == null || endClient.after(lastSleepTime)) {
                        lastSleep = entry
                        lastSleepTime = endClient
                    }
                    val overlapStart = max(startClient.time, dayStartMs)
                    val overlapEnd = min(endClient.time, dayEndMs)
                    if (overlapEnd > overlapStart) {
                        todaySleepMs += (overlapEnd - overlapStart)
                    }
                }
                is ChangeEntry -> {
                    if (lastDiaperTime == null || startClient.after(lastDiaperTime)) {
                        lastDiaper = entry
                        lastDiaperTime = startClient
                    }
                    if (startClient.time in dayStartMs until dayEndMs) {
                        todayDiaperTotal++
                        if (entry.wet) todayDiaperWet++
                        if (entry.solid) todayDiaperSolid++
                    }
                }
                is PumpingEntry -> {
                    if (lastPumpingTime == null || endClient.after(lastPumpingTime)) {
                        lastPumpingTime = endClient
                    }
                    if (startClient.time in dayStartMs until dayEndMs) {
                        todayPumpingCount++
                        todayPumpingAmount += entry.amount
                    }
                }
                is TummyTimeEntry -> {
                    if (lastTummyTime == null || endClient.after(lastTummyTime)) {
                        lastTummyTime = endClient
                    }
                    val overlapStart = max(startClient.time, dayStartMs)
                    val overlapEnd = min(endClient.time, dayEndMs)
                    if (overlapEnd > overlapStart) {
                        todayTummyMs += (overlapEnd - overlapStart)
                    }
                }
                is NoteEntry, is TemperatureEntry -> {
                    if (lastCareTime == null || startClient.after(lastCareTime)) {
                        lastCareTime = startClient
                    }
                    if (startClient.time in dayStartMs until dayEndMs) {
                        todayCareCount++
                    }
                }
            }
        }

        return TodaySummary(
            lastFeeding = lastFeeding,
            lastFeedingClientTime = lastFeedingTime,
            lastSleep = lastSleep,
            lastSleepClientTime = lastSleepTime,
            lastDiaper = lastDiaper,
            lastDiaperClientTime = lastDiaperTime,
            lastPumpingClientTime = lastPumpingTime,
            lastCareClientTime = lastCareTime,
            lastTummyClientTime = lastTummyTime,
            todayFeedingCount = todayFeedingCount,
            todayFeedingAmountMl = todayFeedingAmount,
            todaySleepMinutes = (todaySleepMs / 60_000.0).roundToInt(),
            todayDiaperTotal = todayDiaperTotal,
            todayDiaperWet = todayDiaperWet,
            todayDiaperSolid = todayDiaperSolid,
            todayPumpingCount = todayPumpingCount,
            todayPumpingAmountMl = todayPumpingAmount,
            todayCareCount = todayCareCount,
            todayTummyMinutes = (todayTummyMs / 60_000.0).roundToInt(),
        )
    }

    fun classifyDayBucket(entryDate: LocalDate, today: LocalDate): DayBucket {
        return when (entryDate) {
            today -> DayBucket.TODAY
            today.minusDays(1) -> DayBucket.YESTERDAY
            else -> DayBucket.OTHER
        }
    }

    fun toLocalDate(date: Date, zoneId: ZoneId = ZoneId.systemDefault()): LocalDate {
        return Instant.ofEpochMilli(date.time).atZone(zoneId).toLocalDate()
    }

    fun formatDateSectionHeader(
        res: Resources,
        clientDate: Date,
        now: Date = Date(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val entryLocal = toLocalDate(clientDate, zoneId)
        val todayLocal = toLocalDate(now, zoneId)
        val formattedDate = DateFormat.getDateInstance(DateFormat.MEDIUM).format(clientDate)
        return when (classifyDayBucket(entryLocal, todayLocal)) {
            DayBucket.TODAY -> "${res.getString(R.string.timeline_date_today)} · $formattedDate"
            DayBucket.YESTERDAY -> "${res.getString(R.string.timeline_date_yesterday)} · $formattedDate"
            DayBucket.OTHER -> formattedDate
        }
    }
}
