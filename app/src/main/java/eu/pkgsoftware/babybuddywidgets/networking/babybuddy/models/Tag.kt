package eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import eu.pkgsoftware.babybuddywidgets.DateTimeDeserializer
import eu.pkgsoftware.babybuddywidgets.DateTimeSerializer
import java.util.Date

/**
 * Colored tag model matching upstream Baby Buddy `core.models.Tag`.
 */
@UIPath("tags")
@APIPath("tags")
@JsonIgnoreProperties(ignoreUnknown = true)
data class TagEntry(
    @JsonProperty("id", required = true) val id: Int,
    @JsonProperty("slug", required = true) val slug: String,
    @JsonProperty("name", required = true) val name: String,
    @JsonProperty("color", required = false) val color: String = DEFAULT_COLORS[0],
    @JsonProperty("last_used", required = false)
    @JsonDeserialize(using = DateTimeDeserializer::class)
    @JsonSerialize(using = DateTimeSerializer::class)
    val lastUsed: Date = Date(),
) {
    /**
     * Exact YIQ contrast text color calculation from upstream Baby Buddy `Tag.complementary_color`.
     */
    @JsonIgnore
    fun complementaryTextColorHex(): String {
        val hex = color.trim().removePrefix("#")
        if (hex.length != 6) return "#FFFFFF"
        val r = hex.substring(0, 2).toIntOrNull(16) ?: return "#FFFFFF"
        val g = hex.substring(2, 4).toIntOrNull(16) ?: return "#FFFFFF"
        val b = hex.substring(4, 6).toIntOrNull(16) ?: return "#FFFFFF"
        val yiq = ((r * 299) + (g * 587) + (b * 114)) / 1000
        return if (yiq >= 145) "#2D2522" else "#FFFFFF"
    }

    companion object {
        val DEFAULT_COLORS = listOf(
            "#E07A5F", // Warm Terracotta
            "#5B7FD1", // Nursery Blue
            "#469E70", // Sage Green
            "#D99B26", // Honey Amber
            "#A05EB8", // Soft Plum
            "#3B97A5", // Teal
            "#D84B78", // Rose
            "#7E57C2", // Lavender
            "#E67E22", // Tangerine
            "#6D7986", // Slate
        )
    }
}
