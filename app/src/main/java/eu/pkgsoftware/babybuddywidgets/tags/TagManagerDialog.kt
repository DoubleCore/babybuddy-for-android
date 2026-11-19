package eu.pkgsoftware.babybuddywidgets.tags

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.squareup.phrase.Phrase
import eu.pkgsoftware.babybuddywidgets.MainActivity
import eu.pkgsoftware.babybuddywidgets.R
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TagEntry

object TagManagerDialog {
    fun parseColorSafe(hex: String, fallback: Int = Color.parseColor("#E07A5F")): Int {
        return try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            fallback
        }
    }

    fun showEditor(
        activity: MainActivity,
        existing: TagEntry? = null,
        onSaved: (TagEntry) -> Unit = {},
    ) {
        val ctx = activity
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        var selectedColor = existing?.color ?: TagEntry.DEFAULT_COLORS[0]

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }

        val nameEdit = EditText(ctx).apply {
            hint = ctx.getString(R.string.tag_name_hint)
            setText(existing?.name ?: "")
            inputType = InputType.TYPE_CLASS_TEXT
            setBackgroundResource(R.drawable.bg_soft_pill)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            textSize = 14f
        }
        root.addView(nameEdit)

        val colorEdit = EditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            hint = "#E07A5F"
            setText(selectedColor)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setBackgroundResource(R.drawable.bg_soft_pill)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 13f
        }
        root.addView(colorEdit)

        val swatchScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val swatchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        swatchScroll.addView(swatchRow)
        root.addView(swatchScroll)

        fun rebuildSwatches() {
            swatchRow.removeAllViews()
            for (hex in TagEntry.DEFAULT_COLORS) {
                val isChosen = hex.equals(selectedColor, ignoreCase = true)
                val dot = View(ctx).apply {
                    val size = if (isChosen) dp(34) else dp(28)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        marginEnd = dp(8)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(parseColorSafe(hex))
                        if (isChosen) {
                            setStroke(dp(3), ContextCompat.getColor(ctx, R.color.text_primary_warm))
                        }
                    }
                    setOnClickListener {
                        selectedColor = hex
                        colorEdit.setText(hex)
                        rebuildSwatches()
                    }
                }
                swatchRow.addView(dot)
            }
        }
        rebuildSwatches()

        val errorView = TextView(ctx).apply {
            setTextColor(ContextCompat.getColor(ctx, R.color.danger_soft_fg))
            textSize = 12.5f
            visibility = View.GONE
            setPadding(dp(2), dp(6), dp(2), 0)
        }
        root.addView(errorView)

        val title = if (existing == null) {
            ctx.getString(R.string.tag_add_title)
        } else {
            ctx.getString(R.string.tag_edit_title)
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(root)
            .setPositiveButton(R.string.logging_save_button, null)
            .setNegativeButton(R.string.history_delete_question_cancel_button, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cleanName = nameEdit.text.toString().trim()
                if (cleanName.isEmpty()) {
                    errorView.text = ctx.getString(R.string.tag_name_required)
                    errorView.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                val rawHex = colorEdit.text.toString().trim().ifEmpty { selectedColor }
                try {
                    val saved = if (existing == null) {
                        val created = activity.repository.createTag(cleanName, rawHex)
                        activity.repository.updateTag(created.id, cleanName, rawHex)
                    } else {
                        activity.repository.updateTag(existing.id, cleanName, rawHex)
                    }
                    dialog.dismiss()
                    onSaved(saved)
                } catch (e: Exception) {
                    errorView.text = e.message ?: ctx.getString(R.string.activity_store_failure_message)
                    errorView.visibility = View.VISIBLE
                }
            }
        }
        dialog.show()
    }

    fun showManager(
        activity: MainActivity,
        onChanged: () -> Unit = {},
    ) {
        val ctx = activity
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val sheet = BottomSheetDialog(ctx)
        val scroll = ScrollView(ctx)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.card_surface))
        }
        scroll.addView(container)

        val titleView = TextView(ctx).apply {
            text = ctx.getString(R.string.tag_manager_title)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
        }
        container.addView(titleView)

        val listContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        container.addView(listContainer)

        val addButton = MaterialButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply { topMargin = dp(12) }
            text = ctx.getString(R.string.tag_add_title)
            isAllCaps = false
            cornerRadius = dp(12)
        }
        container.addView(addButton)

        fun rebuildList() {
            listContainer.removeAllViews()
            val tags = activity.repository.listTagsWithCounts()
            if (tags.isEmpty()) {
                val empty = TextView(ctx).apply {
                    text = ctx.getString(R.string.tag_manager_empty)
                    textSize = 13.5f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_muted_warm))
                    setPadding(dp(4), dp(12), dp(4), dp(12))
                }
                listContainer.addView(empty)
                return
            }

            for (item in tags) {
                val tag = item.tag
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_tile_idle)
                    setPadding(dp(12), dp(10), dp(10), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                }

                val pill = TextView(ctx).apply {
                    text = tag.name
                    textSize = 12.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(parseColorSafe(tag.complementaryTextColorHex(), Color.WHITE))
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(parseColorSafe(tag.color))
                    }
                }
                row.addView(pill)

                val countLabel = TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(10)
                    }
                    text = Phrase.from(ctx, R.string.tag_usage_count_pattern)
                        .put("count", item.usageCount)
                        .format()
                        .toString()
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
                }
                row.addView(countLabel)

                val editBtn = ImageButton(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(4) }
                    setBackgroundResource(R.drawable.bg_badge_rounded)
                    setImageResource(R.drawable.ic_edit_outline)
                    imageTintList = ContextCompat.getColorStateList(ctx, R.color.text_secondary_warm)
                    val p = dp(8)
                    setPadding(p, p, p, p)
                    setOnClickListener {
                        showEditor(activity, tag) {
                            rebuildList()
                            onChanged()
                        }
                    }
                }
                row.addView(editBtn)

                val delBtn = ImageButton(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                    setBackgroundResource(R.drawable.bg_badge_rounded)
                    backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.danger_soft_bg)
                    setImageResource(R.drawable.ic_delete_outline)
                    imageTintList = ContextCompat.getColorStateList(ctx, R.color.danger_soft_fg)
                    val p = dp(8)
                    setPadding(p, p, p, p)
                    setOnClickListener {
                        AlertDialog.Builder(ctx)
                            .setTitle(R.string.history_delete_title)
                            .setMessage(
                                Phrase.from(ctx, R.string.tag_delete_confirm)
                                    .put("name", tag.name)
                                    .format()
                                    .toString()
                            )
                            .setPositiveButton(R.string.history_delete_question_delete_button) { _, _ ->
                                activity.repository.deleteTag(tag.id)
                                rebuildList()
                                onChanged()
                            }
                            .setNegativeButton(R.string.history_delete_question_cancel_button, null)
                            .show()
                    }
                }
                row.addView(delBtn)

                listContainer.addView(row)
            }
        }

        addButton.setOnClickListener {
            showEditor(activity, null) {
                rebuildList()
                onChanged()
            }
        }

        rebuildList()
        sheet.setContentView(scroll)
        sheet.show()
    }
}
