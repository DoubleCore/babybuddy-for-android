package eu.pkgsoftware.babybuddywidgets.children

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
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
import eu.pkgsoftware.babybuddywidgets.history.BabySummaryHelper
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child

object ChildManagerBottomSheet {
    fun show(
        activity: MainActivity,
        selectedSlug: String?,
        onSelectChild: (Child) -> Unit,
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
            text = ctx.getString(R.string.child_manager_title)
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
            ).apply { topMargin = dp(14) }
            text = ctx.getString(R.string.child_manager_add_baby)
            isAllCaps = false
            cornerRadius = dp(12)
        }
        container.addView(addButton)

        fun rebuildList() {
            listContainer.removeAllViews()
            val children = activity.repository.listChildren()
            if (children.isEmpty()) {
                val empty = TextView(ctx).apply {
                    text = ctx.getString(R.string.logged_in_no_children_found)
                    textSize = 13.5f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_muted_warm))
                    setPadding(dp(4), dp(12), dp(4), dp(12))
                }
                listContainer.addView(empty)
                return
            }

            for (child in children) {
                val isSelected = (child.slug == selectedSlug)
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(if (isSelected) R.drawable.bg_tile_active else R.drawable.bg_tile_idle)
                    setPadding(dp(12), dp(10), dp(10), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                    setOnClickListener {
                        sheet.dismiss()
                        onSelectChild(child)
                    }
                }

                val avatarFrame = FrameLayout(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ContextCompat.getColor(ctx, R.color.feeding_enabled))
                    }
                    clipToOutline = true
                }
                val avatarImg = ImageView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                val file = activity.repository.mediaStorage.resolveMediaFile(child.picture)
                val bmp = file?.let { BitmapFactory.decodeFile(it.absolutePath) }
                if (bmp != null) {
                    avatarImg.scaleType = ImageView.ScaleType.CENTER_CROP
                    avatarImg.setImageBitmap(bmp)
                } else {
                    val p = dp(9)
                    avatarImg.setPadding(p, p, p, p)
                    avatarImg.scaleType = ImageView.ScaleType.FIT_CENTER
                    avatarImg.setImageResource(R.drawable.ic_baby_face)
                    avatarImg.imageTintList = ContextCompat.getColorStateList(ctx, R.color.feeding_accent)
                }
                avatarFrame.addView(avatarImg)
                row.addView(avatarFrame)

                val textCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(10)
                    }
                }
                val nameText = TextView(ctx).apply {
                    text = child.fullName
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
                }
                val ageStr = BabySummaryHelper.formatBabyAge(ctx.resources, child.birthData)
                val correctedPart = if (child.hasCorrectedPrematureAge && child.dueDate != null) {
                    val corr = BabySummaryHelper.formatBabyAge(ctx.resources, child.dueDate)
                    " · ${Phrase.from(ctx, R.string.child_profile_corrected_age_badge).put("age", corr).format()}"
                } else {
                    ""
                }
                val subText = TextView(ctx).apply {
                    text = "$ageStr$correctedPart"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
                }
                textCol.addView(nameText)
                textCol.addView(subText)
                row.addView(textCol)

                val editBtn = ImageButton(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(4) }
                    setBackgroundResource(R.drawable.bg_badge_rounded)
                    setImageResource(R.drawable.ic_edit_outline)
                    imageTintList = ContextCompat.getColorStateList(ctx, R.color.text_secondary_warm)
                    val p = dp(8)
                    setPadding(p, p, p, p)
                    contentDescription = ctx.getString(R.string.common_edit)
                    setOnClickListener {
                        ChildProfileDialog.show(activity, child) { updated ->
                            rebuildList()
                            if (child.slug == selectedSlug) {
                                onSelectChild(updated)
                            }
                        }
                    }
                }
                row.addView(editBtn)

                val delBtn = ImageButton(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                    setBackgroundResource(R.drawable.bg_badge_rounded)
                    backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.danger_soft_bg)
                    setImageResource(R.drawable.ic_delete_outline)
                    imageTintList = ContextCompat.getColorStateList(ctx, R.color.danger_soft_fg)
                    val p = dp(8)
                    setPadding(p, p, p, p)
                    contentDescription = ctx.getString(R.string.timeline_delete_desc)
                    setOnClickListener {
                        val confirmMsg = Phrase.from(ctx, R.string.child_manager_delete_confirm)
                            .put("name", child.fullName)
                            .format()
                            .toString()
                        AlertDialog.Builder(ctx)
                            .setTitle(R.string.history_delete_title)
                            .setMessage(confirmMsg)
                            .setPositiveButton(R.string.history_delete_question_delete_button) { _, _ ->
                                activity.repository.deleteChild(child.id)
                                rebuildList()
                                activity.repository.listChildren().firstOrNull()?.let { onSelectChild(it) }
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
            ChildProfileDialog.show(activity, null) { created ->
                sheet.dismiss()
                onSelectChild(created)
            }
        }

        rebuildList()
        sheet.setContentView(scroll)
        sheet.show()
    }
}
