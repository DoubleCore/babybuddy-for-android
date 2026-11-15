package eu.pkgsoftware.babybuddywidgets.tags

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import eu.pkgsoftware.babybuddywidgets.MainActivity
import eu.pkgsoftware.babybuddywidgets.R
import java.util.Locale

class TagSelectorHelper(
    private val activity: MainActivity,
    initialTags: List<String> = emptyList(),
    private val onSelectionChanged: (List<String>) -> Unit = {},
) {
    private val selectedLowerSet = linkedSetOf<String>()
    private val canonicalNames = linkedMapOf<String, String>()

    val rootView: LinearLayout
    private val chipGroup: ChipGroup

    init {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        rootView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }

        val scroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        chipGroup = ChipGroup(activity).apply {
            isSingleLine = true
            chipSpacingHorizontal = dp(6)
        }
        scroll.addView(chipGroup)
        rootView.addView(scroll)

        setSelectedTagsInternal(initialTags)
        refreshChips()
    }

    var selectedTags: List<String>
        get() = selectedLowerSet.mapNotNull { canonicalNames[it] }
        set(value) {
            setSelectedTagsInternal(value)
            refreshChips()
        }

    private fun setSelectedTagsInternal(tags: List<String>) {
        selectedLowerSet.clear()
        canonicalNames.clear()
        for (raw in tags) {
            val clean = raw.trim()
            if (clean.isNotEmpty()) {
                val lower = clean.lowercase(Locale.ROOT)
                selectedLowerSet.add(lower)
                canonicalNames[lower] = clean
            }
        }
    }

    fun refreshChips() {
        chipGroup.removeAllViews()
        val allTags = activity.repository.listTags()
        val seenLower = mutableSetOf<String>()

        for (tag in allTags) {
            val lower = tag.name.lowercase(Locale.ROOT)
            seenLower.add(lower)
            canonicalNames[lower] = tag.name
            val checked = lower in selectedLowerSet
            val chip = buildTagChip(tag.name, tag.color, tag.complementaryTextColorHex(), checked) { nowChecked ->
                if (nowChecked) {
                    selectedLowerSet.add(lower)
                    canonicalNames[lower] = tag.name
                } else {
                    selectedLowerSet.remove(lower)
                }
                refreshChips()
                onSelectionChanged(selectedTags)
            }
            chipGroup.addView(chip)
        }

        // Render any ad-hoc tags not yet in DB
        for (lower in selectedLowerSet.toList()) {
            if (lower !in seenLower) {
                val name = canonicalNames[lower] ?: continue
                val chip = buildTagChip(name, "#E07A5F", "#FFFFFF", true) { nowChecked ->
                    if (!nowChecked) {
                        selectedLowerSet.remove(lower)
                    }
                    refreshChips()
                    onSelectionChanged(selectedTags)
                }
                chipGroup.addView(chip)
            }
        }

        val addChip = Chip(activity).apply {
            id = View.generateViewId()
            text = "+ ${activity.getString(R.string.tag_add_chip)}"
            textSize = 11.5f
            isCheckable = false
            setChipIconResource(R.drawable.ic_tag_outline)
            chipIconTint = ContextCompat.getColorStateList(activity, R.color.primary_color)
            isChipIconVisible = true
            setOnClickListener {
                TagManagerDialog.showEditor(activity, null) { created ->
                    val lower = created.name.lowercase(Locale.ROOT)
                    selectedLowerSet.add(lower)
                    canonicalNames[lower] = created.name
                    refreshChips()
                    onSelectionChanged(selectedTags)
                }
            }
            setOnLongClickListener {
                TagManagerDialog.showManager(activity) {
                    refreshChips()
                }
                true
            }
        }
        chipGroup.addView(addChip)
    }

    private fun buildTagChip(
        name: String,
        bgHex: String,
        fgHex: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit,
    ): Chip {
        return Chip(activity).apply {
            id = View.generateViewId()
            text = "#$name"
            textSize = 11.5f
            isCheckable = true
            isChecked = checked
            isCheckedIconVisible = false
            if (checked) {
                chipBackgroundColor = ColorStateList.valueOf(TagManagerDialog.parseColorSafe(bgHex))
                setTextColor(TagManagerDialog.parseColorSafe(fgHex, Color.WHITE))
            } else {
                chipBackgroundColor = ContextCompat.getColorStateList(activity, R.color.card_surface_elevated)
                setTextColor(ContextCompat.getColor(activity, R.color.text_secondary_warm))
                chipStrokeWidth = 1f * activity.resources.displayMetrics.density
                chipStrokeColor = ColorStateList.valueOf(TagManagerDialog.parseColorSafe(bgHex))
            }
            setOnClickListener {
                onToggle(!checked)
            }
        }
    }
}
