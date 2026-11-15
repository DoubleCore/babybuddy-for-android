package eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import eu.pkgsoftware.babybuddywidgets.AnyDateTimeDeserializer
import eu.pkgsoftware.babybuddywidgets.DateOnlySerializer
import java.util.Date

fun childIndexBySlug(children: Array<Child>, slug: String): Int {
    for (i in children.indices) {
        if (children[i].slug == slug) {
            return i
        }
    }
    return -1
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Child(
    @JsonProperty("id", required = true) val id: Int,
    @JsonProperty("first_name", required = true) val firstName: String,
    @JsonProperty("last_name", required = false) val lastName: String = "",
    @JsonProperty("birth_date", required = true)
    @JsonDeserialize(using = AnyDateTimeDeserializer::class)
    @JsonSerialize(using = DateOnlySerializer::class)
    val birthData: Date,
    @JsonProperty("slug", required = true) val slug: String,
    @JsonProperty("picture", required = false) val picture: String? = null,
    @JsonProperty("birth_time", required = false) val birthTime: String? = null,
    @JsonProperty("due_date", required = false)
    @JsonDeserialize(using = AnyDateTimeDeserializer::class)
    @JsonSerialize(using = DateOnlySerializer::class)
    val dueDate: Date? = null,
    @JsonProperty("sex", required = false) val sex: String? = null,
) {
    @get:JsonIgnore
    val fullName: String
        get() = listOf(firstName.trim(), lastName.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    @get:JsonIgnore
    val hasCorrectedPrematureAge: Boolean
        get() = dueDate != null && dueDate.time > birthData.time + 24L * 3600_000L

    companion object {
        const val SEX_BOY = "boy"
        const val SEX_GIRL = "girl"

        fun childIndexBySlug(children: Array<Child>, slug: String): Int {
            return eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.childIndexBySlug(children, slug)
        }
    }
}

data class ChildrenList(
    @JsonProperty("children", required = true) val children: Array<Child>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChildrenList
        return children.contentEquals(other.children)
    }

    override fun hashCode(): Int {
        return children.contentHashCode()
    }
}
