package eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models

import com.fasterxml.jackson.annotation.JsonGetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingMethodEnum
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingTypeEnum
import eu.pkgsoftware.babybuddywidgets.Constants.MedicationDosageUnitEnum
import eu.pkgsoftware.babybuddywidgets.DateOnlyDeserializer
import eu.pkgsoftware.babybuddywidgets.DateOnlySerializer
import eu.pkgsoftware.babybuddywidgets.DateTimeDeserializer
import eu.pkgsoftware.babybuddywidgets.DateTimeSerializer
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient.ACTIVITIES
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient.EVENTS
import java.util.Date
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotations

annotation class ActivityName(val name: String)
annotation class APIPath(val path: String)
annotation class UIPath(val path: String)

interface TimeEntry {
    val appType: String
    val appTypeId: Int
    val id: Int
    val childId: Int
    val start: Date
    val end: Date
    val notes: String
    val tags: List<String>
        get() = emptyList()
}

@UIPath("sleep")
@APIPath("sleep")
@ActivityName(ACTIVITIES.SLEEP)
@JsonIgnoreProperties(ignoreUnknown = true)
data class SleepEntry(
    @JsonProperty("id", required = true) override val id: Int,
    @JsonProperty("child", required = true) override val childId: Int,
    @JsonDeserialize(using = DateTimeDeserializer::class)
    @JsonSerialize(using = DateTimeSerializer::class)
    @JsonProperty("start", required = true)
    override val start: Date,
    @JsonDeserialize(using = DateTimeDeserializer::class)
    @JsonSerialize(using = DateTimeSerializer::class)
    @JsonProperty("end", required = true)
    override val end: Date,
    @JsonSetter("notes") val _notes: String?,
    @JsonProperty("nap", required = false) val nap: Boolean? = null,
    @JsonProperty("tags", required = false) override val tags: List<String> = emptyList(),
) : TimeEntry {
    override @JsonIgnore val appType: String = ACTIVITIES.SLEEP
    override @JsonIgnore val appTypeId: Int = ACTIVITIES.index(appType)
    override @get:JsonGetter("notes") val notes: String = _notes ?: ""
    @get:JsonIgnore val isNap: Boolean get() = nap ?: false
}

@UIPath("tummy-time")
@APIPath("tummy-times")
@ActivityName(ACTIVITIES.TUMMY_TIME)
@JsonIgnoreProperties(ignoreUnknown = true)
data class TummyTimeEntry(
    @JsonProperty("id", required = true) override val id: Int,
    @JsonProperty("child", required = true) override val childId: Int,

    @JsonDeserialize(using = DateTimeDeserializer::class)
    @JsonSerialize(using = DateTimeSerializer::class)
    @JsonProperty("start", required = true)
    override val start: Date,

    @JsonDeserialize(using = DateTimeDeserializer::class)
    @JsonSerialize(using = DateTimeSerializer::class)
    @JsonProperty("end", required = true)
    override val end: Date,

    @JsonSetter("milestone") val _notes: String?,
    @JsonProperty("notes", required = false) val extraNotes: String? = null,
    @JsonProperty("tags", required = false) override val tags: List<String> = emptyList(),
) : TimeEntry {
    override @JsonIgnore val appType: String = ACTIVITIES.TUMMY_TIME
    override @JsonIgnore val appTypeId: Int = ACTIVITIES.index(appType)
    @get:JsonGetter("milestone") val milestone: String = _notes ?: ""
    override @get:JsonIgnore val notes: String = when {
        milestone.isNotBlank() && !extraNotes.isNullOrBlank() -> "$milestone · $extraNotes"
        milestone.isNotBlank() -> milestone
        else -> extraNotes ?: ""
    }
}

@UIPath("feedings")
@APIPath("feedings")
@ActivityName(ACTIVITIES.FEEDING)
@JsonIgnoreProperties(ignoreUnknown = true)
data class FeedingEntry(
    @JsonProperty("id", required = true) override val id: Int,
    @JsonProperty("child", required = true) override val childId: Int,

    @JsonDeserialize(using = DateTimeDeserializer::class)
    @JsonSerialize(using = DateTimeSerializer::class)
    @JsonProperty("start", required = true)
    override val start: Date,

    @JsonDeserialize(using = DateTimeDeserializer::class)
    @JsonSerialize(using = DateTimeSerializer::class)
    @JsonProperty("end", required = true)
    override val end: Date,

    @JsonSetter("notes") val _notes: String?,
    @JsonProperty("type", required = true) val feedingType: FeedingTypeEnum,
    @JsonProperty("method", required = true) val feedingMethod: FeedingMethodEnum,
    @JsonProperty("amount", required = true) val amount: Double?,
    @JsonProperty("tags", required = false) override val tags: List<String> = emptyList(),
) : TimeEntry {
    override @JsonIgnore val appType: String = ACTIVITIES.FEEDING
    override @JsonIgnore val appTypeId: Int = ACTIVITIES.index(appType)
    override @get:JsonGetter("notes") val notes: String = _notes ?: ""
}

@UIPath("pumping")
@APIPath("pumping")
@ActivityName(ACTIVITIES.PUMPING)
@JsonIgnoreProperties(ignoreUnknown = true)
data class PumpingEntry(
    @JsonProperty("id", required = true) override val id: Int,
    @JsonProperty("child", required = true) override val childId: Int,
    @JsonSetter("start") @JsonDeserialize(using = DateTimeDeserializer::class) val _start: Date?,
    @JsonSetter("end") @JsonDeserialize(using = DateTimeDeserializer::class) val _end: Date?,
    @JsonSetter("notes") val _notes: String?,
    @JsonProperty("amount", required = true) val amount: Double,
    @JsonSetter("time") @JsonDeserialize(using = DateTimeDeserializer::class) private val _legacyTime: Date? = null,
    @JsonProperty("tags", required = false) override val tags: List<String> = emptyList(),
) : TimeEntry {
    override @JsonIgnore val appType: String = ACTIVITIES.PUMPING
    override @JsonIgnore val appTypeId: Int = ACTIVITIES.index(appType)
    override @get:JsonGetter("start") @JsonSerialize(using = DateTimeSerializer::class) val start: Date =
        _start ?: _legacyTime ?: Date()
    override @get:JsonGetter("end") @JsonSerialize(using = DateTimeSerializer::class) val end: Date =
        _end ?: _start ?: _legacyTime ?: Date()
    override @get:JsonGetter("notes") val notes: String = _notes ?: ""
}

@UIPath("changes")
@APIPath("changes")
@ActivityName(EVENTS.CHANGE)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChangeEntry(
    @JsonProperty("id", required = true)
    override val id: Int,

    @JsonProperty("child", required = true)
    override val childId: Int,

    @JsonProperty("time", required = true)
    @JsonDeserialize(using = DateTimeDeserializer::class)
    @JsonSerialize(using = DateTimeSerializer::class)
    override val start: Date,

    @JsonSetter("notes") val _notes: String?,
    @JsonProperty("wet", required = true) val wet: Boolean,
    @JsonProperty("solid", required = true) val solid: Boolean,
    @JsonProperty("color", required = true) val color: String,
    @JsonProperty("amount", required = true) val amount: Double?,
    @JsonProperty("tags", required = false) override val tags: List<String> = emptyList(),
) : TimeEntry {
    override @JsonIgnore val appType: String = EVENTS.CHANGE
    override @JsonIgnore val appTypeId: Int = EVENTS.index(appType)
    override val end: Date = start
    override @get:JsonGetter("notes") val notes: String = _notes ?: ""
}

@UIPath("notes")
@APIPath("notes")
@ActivityName(EVENTS.NOTE)
@JsonIgnoreProperties(ignoreUnknown = true)
data class NoteEntry(
    @JsonProperty("id", required = true)
    override val id: Int,

    @JsonProperty("child", required = true)
    override val childId: Int,

    @JsonProperty("time", required = true)
    @JsonDeserialize(using = DateTimeDeserializer::class)
    @JsonSerialize(using = DateTimeSerializer::class)
    override val start: Date,

    @JsonProperty("note", required = false) val _notes: String?,
    @JsonProperty("image", required = false) val imagePath: String? = null,
    @JsonProperty("tags", required = false) override val tags: List<String> = emptyList(),
) : TimeEntry {
    override @JsonIgnore val appType: String = EVENTS.NOTE
    override @JsonIgnore val appTypeId: Int = EVENTS.index(appType)
    override val end: Date = start
    override val notes: String = _notes ?: ""
}

@UIPath("medications")
@APIPath("medications")
@ActivityName(EVENTS.MEDICATION)
@JsonIgnoreProperties(ignoreUnknown = true)
data class MedicationEntry(
    @JsonProperty("id", required = true) override val id: Int,
    @JsonProperty("child", required = true) override val childId: Int,
    @JsonProperty("time", required = true)
    @JsonDeserialize(using = DateTimeDeserializer::class)
    @JsonSerialize(using = DateTimeSerializer::class)
    override val start: Date,
    @JsonProperty("name", required = true) val name: String,
    @JsonProperty("dosage", required = true) val dosage: Double,
    @JsonProperty("dosage_unit", required = false) val dosageUnit: MedicationDosageUnitEnum = MedicationDosageUnitEnum.ML,
    @JsonProperty("next_dose_interval_minutes", required = false) val nextDoseIntervalMinutes: Int? = null,
    @param:JsonProperty("notes", required = false) @get:JsonIgnore val _notes: String? = null,
    @JsonProperty("tags", required = false) override val tags: List<String> = emptyList(),
) : TimeEntry {
    override @JsonIgnore val appType: String = EVENTS.MEDICATION
    override @JsonIgnore val appTypeId: Int = EVENTS.index(appType)
    override @get:JsonIgnore val end: Date = start
    override @get:JsonGetter("notes") val notes: String = _notes ?: ""

    @get:JsonIgnore
    val nextDoseTime: Date?
        get() = nextDoseIntervalMinutes?.takeIf { it > 0 }?.let { Date(start.time + it * 60_000L) }

    @JsonIgnore
    fun isNextDoseReady(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val target = nextDoseTime ?: return false
        return nowMillis >= target.time
    }

    @JsonIgnore
    fun remainingMillisUntilNextDose(nowMillis: Long = System.currentTimeMillis()): Long? {
        val target = nextDoseTime ?: return null
        return (target.time - nowMillis).coerceAtLeast(0L)
    }
}

@UIPath("bmi")
@APIPath("bmi")
@ActivityName(EVENTS.BMI)
@JsonIgnoreProperties(ignoreUnknown = true)
data class BmiEntry(
    @JsonProperty("id", required = true) override val id: Int,
    @JsonProperty("child", required = true) override val childId: Int,
    @JsonProperty("date", required = true)
    @JsonDeserialize(using = DateOnlyDeserializer::class)
    @JsonSerialize(using = DateOnlySerializer::class)
    override val start: Date,
    @param:JsonProperty("notes", required = false) @get:JsonIgnore val _notes: String?,
    @JsonProperty("bmi", required = true) val bmi: Double,
    @JsonProperty("tags", required = false) override val tags: List<String> = emptyList(),
) : TimeEntry {
    override @JsonIgnore val appType: String = EVENTS.BMI
    override @JsonIgnore val appTypeId: Int = EVENTS.index(appType)
    override @get:JsonIgnore val end: Date = start
    override @get:JsonGetter("notes") val notes: String = _notes ?: ""
}

@UIPath("temperature")
@APIPath("temperature")
@ActivityName(EVENTS.TEMPERATURE)
@JsonIgnoreProperties(ignoreUnknown = true)
data class TemperatureEntry(
    @JsonProperty("id", required = true) override val id: Int,
    @JsonProperty("child", required = true) override val childId: Int,
    @JsonProperty("time", required = true)
    @JsonDeserialize(using = DateTimeDeserializer::class)
    @JsonSerialize(using = DateTimeSerializer::class)
    override val start: Date,
    @param:JsonProperty("notes", required = false) @get:JsonIgnore val _notes: String?,
    @JsonProperty("temperature", required = true) val temperature: Double,
    @JsonProperty("tags", required = false) override val tags: List<String> = emptyList(),
) : TimeEntry {
    override @JsonIgnore val appType: String = EVENTS.TEMPERATURE
    override @JsonIgnore val appTypeId: Int = EVENTS.index(appType)
    override @get:JsonIgnore val end: Date = start
    override @get:JsonGetter("notes") val notes: String = _notes ?: ""
}

@UIPath("weight")
@APIPath("weight")
@ActivityName(EVENTS.WEIGHT)
@JsonIgnoreProperties(ignoreUnknown = true)
data class WeightEntry(
    @JsonProperty("id", required = true) override val id: Int,
    @JsonProperty("child", required = true) override val childId: Int,
    @JsonProperty("date", required = true)
    @JsonDeserialize(using = DateOnlyDeserializer::class)
    @JsonSerialize(using = DateOnlySerializer::class)
    override val start: Date,
    @param:JsonProperty("notes", required = false) @get:JsonIgnore val _notes: String?,
    @JsonProperty("weight", required = true) val weight: Double,
    @JsonProperty("tags", required = false) override val tags: List<String> = emptyList(),
) : TimeEntry {
    override @JsonIgnore val appType: String = EVENTS.WEIGHT
    override @JsonIgnore val appTypeId: Int = EVENTS.index(appType)
    override @get:JsonIgnore val end: Date = start
    override @get:JsonGetter("notes") val notes: String = _notes ?: ""
}

@UIPath("height")
@APIPath("height")
@ActivityName(EVENTS.HEIGHT)
@JsonIgnoreProperties(ignoreUnknown = true)
data class HeightEntry(
    @JsonProperty("id", required = true) override val id: Int,
    @JsonProperty("child", required = true) override val childId: Int,
    @JsonProperty("date", required = true)
    @JsonDeserialize(using = DateOnlyDeserializer::class)
    @JsonSerialize(using = DateOnlySerializer::class)
    override val start: Date,
    @param:JsonProperty("notes", required = false) @get:JsonIgnore val _notes: String?,
    @JsonProperty("height", required = true) val height: Double,
    @JsonProperty("tags", required = false) override val tags: List<String> = emptyList(),
) : TimeEntry {
    override @JsonIgnore val appType: String = EVENTS.HEIGHT
    override @JsonIgnore val appTypeId: Int = EVENTS.index(appType)
    override @get:JsonIgnore val end: Date = start
    override @get:JsonGetter("notes") val notes: String = _notes ?: ""
}

@UIPath("head-circumference")
@APIPath("head-circumference")
@ActivityName(EVENTS.HEAD_CIRCUMFERENCE)
@JsonIgnoreProperties(ignoreUnknown = true)
data class HeadCircumferenceEntry(
    @JsonProperty("id", required = true) override val id: Int,
    @JsonProperty("child", required = true) override val childId: Int,
    @JsonProperty("date", required = true)
    @JsonDeserialize(using = DateOnlyDeserializer::class)
    @JsonSerialize(using = DateOnlySerializer::class)
    override val start: Date,
    @param:JsonProperty("notes", required = false) @get:JsonIgnore val _notes: String?,
    @JsonProperty("head_circumference", required = true) val head_circumference: Double,
    @JsonProperty("tags", required = false) override val tags: List<String> = emptyList(),
) : TimeEntry {
    override @JsonIgnore val appType: String = EVENTS.HEAD_CIRCUMFERENCE
    override @JsonIgnore val appTypeId: Int = EVENTS.index(appType)
    override @get:JsonIgnore val end: Date = start
    override @get:JsonGetter("notes") val notes: String = _notes ?: ""
}

// Generic wrappers

@JsonIgnoreProperties(ignoreUnknown = true)
data class PaginatedEntries<T>(
    @JsonProperty("count", required = true) val count: Int,
    @JsonProperty("next", required = true) val nextUrl: String?,
    @JsonProperty("previous", required = true) val prevUrl: String?,
    @JsonProperty("results", required = true) val entries: List<T>,
)

fun classActivityName(cls: KClass<*>): String {
    val a = cls.findAnnotations(ActivityName::class)
    if (a.isEmpty()) {
        throw IncorrectApiConfiguration("@ActivityName missing for ${cls.qualifiedName}")
    }
    return a[0].name
}

fun classUIPath(cls: KClass<*>): String {
    val a = cls.findAnnotations(UIPath::class)
    if (a.isEmpty()) {
        throw IncorrectApiConfiguration("@UIPath missing for ${cls.qualifiedName}")
    }
    return a[0].path
}

fun classAPIPath(cls: KClass<*>): String {
    val a = cls.findAnnotations(APIPath::class)
    if (a.isEmpty()) {
        throw IncorrectApiConfiguration("@APIPath missing for ${cls.qualifiedName}")
    }
    return a[0].path
}
