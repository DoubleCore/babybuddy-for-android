package eu.pkgsoftware.babybuddywidgets

import androidx.annotation.ColorRes
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import java.text.SimpleDateFormat
import java.util.Locale

object Constants {
    @JvmField
    val SERVER_DATE_FORMAT = SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z", Locale.ENGLISH)

    @JsonSerialize(using = FeedingTypeEnum.FeedingTypeEnumSerializer::class)
    @JsonDeserialize(using = FeedingTypeEnum.FeedingTypeEnumDeserializer::class)
    enum class FeedingTypeEnum(@JvmField var value: Int, @JvmField var post_name: String) {
        BREAST_MILK(0, "breast milk"),
        FORMULA(1, "formula"),
        FORTIFIED_BREAST_MILK(2, "fortified breast milk"),
        SOLID_FOOD(3, "solid food");

        companion object {
            @JvmStatic
            fun byPostName(name: String): FeedingTypeEnum {
                return entries.first { it.post_name.equals(name.trim(), ignoreCase = true) }
            }

            @JvmStatic
            fun byPostNameOrNull(name: String?): FeedingTypeEnum? {
                if (name.isNullOrBlank()) return null
                return entries.firstOrNull { it.post_name.equals(name.trim(), ignoreCase = true) }
            }
        }

        class FeedingTypeEnumSerializer : JsonSerializer<FeedingTypeEnum>() {
            override fun serialize(value: FeedingTypeEnum?, gen: JsonGenerator?, serializers: SerializerProvider?) {
                gen?.writeString(value?.post_name)
            }
        }

        class FeedingTypeEnumDeserializer : JsonDeserializer<FeedingTypeEnum>() {
            override fun deserialize(parser: JsonParser?, ctxt: DeserializationContext?): FeedingTypeEnum? =
                parser?.text?.let { byPostName(it) }
        }
    }

    @JsonSerialize(using = FeedingMethodEnum.FeedingMethodEnumSerializer::class)
    @JsonDeserialize(using = FeedingMethodEnum.FeedingMethodEnumDeserializer::class)
    enum class FeedingMethodEnum(@JvmField var value: Int, @JvmField var post_name: String) {
        BOTTLE(0, "bottle"),
        LEFT_BREAST(1, "left breast"),
        RIGHT_BREAST(2, "right breast"),
        BOTH_BREASTS(3, "both breasts"),
        PARENT_FED(4, "parent fed"),
        SELF_FED(5, "self fed");

        companion object {
            @JvmStatic
            fun byValue(value: Int): FeedingMethodEnum {
                return entries.first { it.value == value }
            }

            @JvmStatic
            fun byPostName(name: String): FeedingMethodEnum {
                return entries.first { it.post_name.equals(name.trim(), ignoreCase = true) }
            }

            @JvmStatic
            fun byPostNameOrNull(name: String?): FeedingMethodEnum? {
                if (name.isNullOrBlank()) return null
                return entries.firstOrNull { it.post_name.equals(name.trim(), ignoreCase = true) }
            }
        }

        class FeedingMethodEnumSerializer : JsonSerializer<FeedingMethodEnum>() {
            override fun serialize(value: FeedingMethodEnum?, gen: JsonGenerator?, serializers: SerializerProvider?) {
                gen?.writeString(value?.post_name)
            }
        }

        class FeedingMethodEnumDeserializer : JsonDeserializer<FeedingMethodEnum>() {
            override fun deserialize(parser: JsonParser?, ctxt: DeserializationContext?): FeedingMethodEnum? =
                parser?.text?.let { byPostName(it) }
        }
    }

    @JvmField
    var FeedingTypeEnumValues: Map<Int, FeedingTypeEnum>

    init {
        val m = mutableMapOf<Int, FeedingTypeEnum>()
        for (e in FeedingTypeEnum.entries) {
            m[e.value] = e
        }
        FeedingTypeEnumValues = m.toMap()
    }

    @JvmField
    var FeedingMethodEnumValues: Map<Int, FeedingMethodEnum>

    init {
        val m = mutableMapOf<Int, FeedingMethodEnum>()
        for (e in FeedingMethodEnum.entries) {
            m[e.value] = e
        }
        FeedingMethodEnumValues = m.toMap()
    }

    /**
     * Full 8-color stool palette matching upstream Baby Buddy `DiaperChange.COLORS`:
     * black, brown, gray, green, orange, red, white, yellow.
     */
    enum class SolidDiaperColorEnum(var value: Int, var post_name: String, @ColorRes var colorResId: Int) {
        BLACK(0, "black", R.color.diaperBlack),
        BROWN(1, "brown", R.color.diaperBrown),
        GRAY(2, "gray", R.color.diaperGray),
        GREEN(3, "green", R.color.diaperGreen),
        ORANGE(4, "orange", R.color.diaperOrange),
        RED(5, "red", R.color.diaperRed),
        WHITE(6, "white", R.color.diaperWhite),
        YELLOW(7, "yellow", R.color.diaperYellow);

        companion object {
            @JvmStatic
            fun byValue(value: Int): SolidDiaperColorEnum {
                return entries.first { it.value == value }
            }

            @JvmStatic
            fun byPostName(name: String): SolidDiaperColorEnum {
                return entries.first { it.post_name.equals(name.trim(), ignoreCase = true) }
            }

            @JvmStatic
            fun byPostNameOrNull(name: String?): SolidDiaperColorEnum? {
                if (name.isNullOrBlank()) return null
                return entries.firstOrNull { it.post_name.equals(name.trim(), ignoreCase = true) }
            }
        }
    }

    /**
     * Medication dosage units matching upstream Baby Buddy `Medication.DOSAGE_UNITS`:
     * mg, ml, tablets, drops.
     */
    @JsonSerialize(using = MedicationDosageUnitEnum.MedicationDosageUnitSerializer::class)
    @JsonDeserialize(using = MedicationDosageUnitEnum.MedicationDosageUnitDeserializer::class)
    enum class MedicationDosageUnitEnum(@JvmField val value: Int, @JvmField val post_name: String) {
        MG(0, "mg"),
        ML(1, "ml"),
        TABLETS(2, "tablets"),
        DROPS(3, "drops");

        val postName: String
            get() = post_name

        companion object {
            @JvmStatic
            fun byPostName(name: String): MedicationDosageUnitEnum {
                return entries.firstOrNull { it.post_name.equals(name.trim(), ignoreCase = true) } ?: ML
            }

            @JvmStatic
            fun byPostNameOrNull(name: String?): MedicationDosageUnitEnum? {
                if (name.isNullOrBlank()) return null
                return entries.firstOrNull { it.post_name.equals(name.trim(), ignoreCase = true) }
            }

            @JvmStatic
            fun fromPostName(name: String?): MedicationDosageUnitEnum {
                return byPostNameOrNull(name) ?: ML
            }
        }

        class MedicationDosageUnitSerializer : JsonSerializer<MedicationDosageUnitEnum>() {
            override fun serialize(value: MedicationDosageUnitEnum?, gen: JsonGenerator?, serializers: SerializerProvider?) {
                gen?.writeString(value?.post_name ?: ML.post_name)
            }
        }

        class MedicationDosageUnitDeserializer : JsonDeserializer<MedicationDosageUnitEnum>() {
            override fun deserialize(parser: JsonParser?, ctxt: DeserializationContext?): MedicationDosageUnitEnum =
                byPostName(parser?.text ?: ML.post_name)
        }
    }
}
