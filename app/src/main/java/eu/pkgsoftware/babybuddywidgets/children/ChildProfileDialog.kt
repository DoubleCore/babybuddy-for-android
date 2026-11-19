package eu.pkgsoftware.babybuddywidgets.children

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.format.DateFormat as AndroidDateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import eu.pkgsoftware.babybuddywidgets.MainActivity
import eu.pkgsoftware.babybuddywidgets.R
import eu.pkgsoftware.babybuddywidgets.local.ModelValidators
import eu.pkgsoftware.babybuddywidgets.local.ValidationException
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object ChildProfileDialog {
    fun show(
        activity: MainActivity,
        existing: Child? = null,
        onSaved: (Child) -> Unit = {},
    ) {
        val ctx = activity
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        var birthDate: Date = existing?.birthData ?: Date()
        var birthTime: String? = existing?.birthTime
        var dueDate: Date? = existing?.dueDate
        var selectedSex: String? = existing?.sex
        var pictureRelativePath: String? = existing?.picture

        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }
        scroll.addView(root)

        // Avatar row
        val avatarRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val avatarFrame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(ctx, R.color.feeding_enabled))
            }
            clipToOutline = true
        }
        val avatarImage = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        avatarFrame.addView(avatarImage)
        avatarRow.addView(avatarFrame)

        val avatarButtonsCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = dp(12) }
        }
        val pickAvatarBtn = MaterialButton(
            ctx,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = ctx.getString(R.string.child_profile_pick_photo)
            isAllCaps = false
            textSize = 12.5f
        }
        val clearAvatarBtn = MaterialButton(
            ctx,
            null,
            androidx.appcompat.R.attr.borderlessButtonStyle
        ).apply {
            text = ctx.getString(R.string.child_profile_remove_photo)
            isAllCaps = false
            textSize = 12f
        }

        fun refreshAvatarPreview() {
            val file = activity.repository.mediaStorage.resolveMediaFile(pictureRelativePath)
            if (file != null) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    avatarImage.setPadding(0, 0, 0, 0)
                    avatarImage.imageTintList = null
                    avatarImage.scaleType = ImageView.ScaleType.CENTER_CROP
                    avatarImage.setImageBitmap(bmp)
                    clearAvatarBtn.visibility = View.VISIBLE
                    return
                }
            }
            val pad = dp(14)
            avatarImage.setPadding(pad, pad, pad, pad)
            avatarImage.scaleType = ImageView.ScaleType.FIT_CENTER
            avatarImage.setImageResource(R.drawable.ic_baby_face)
            avatarImage.imageTintList = ContextCompat.getColorStateList(ctx, R.color.feeding_accent)
            clearAvatarBtn.visibility = View.GONE
        }

        pickAvatarBtn.setOnClickListener {
            activity.pickPhoto { uri ->
                if (uri != null) {
                    val imported = activity.repository.mediaStorage.importImageFromUri(uri, "avatar")
                    if (imported != null) {
                        pictureRelativePath = imported
                        refreshAvatarPreview()
                    }
                }
            }
        }
        clearAvatarBtn.setOnClickListener {
            pictureRelativePath = null
            refreshAvatarPreview()
        }

        avatarButtonsCol.addView(pickAvatarBtn)
        avatarButtonsCol.addView(clearAvatarBtn)
        avatarRow.addView(avatarButtonsCol)
        root.addView(avatarRow)
        refreshAvatarPreview()

        fun makeInput(hintRes: Int, initialValue: String): EditText {
            return EditText(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
                hint = ctx.getString(hintRes)
                setText(initialValue)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                setBackgroundResource(R.drawable.bg_soft_pill)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                textSize = 14f
            }
        }

        val firstNameEdit = makeInput(R.string.child_profile_first_name_hint, existing?.firstName ?: "")
        val lastNameEdit = makeInput(R.string.child_profile_last_name_hint, existing?.lastName ?: "")
        root.addView(firstNameEdit)
        root.addView(lastNameEdit)

        // Sex selector (for WHO growth curves)
        val sexLabel = TextView(ctx).apply {
            text = ctx.getString(R.string.child_profile_sex_label)
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
            setPadding(dp(2), dp(12), dp(2), dp(4))
        }
        root.addView(sexLabel)

        val sexGroup = ChipGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val sexOptions = listOf(
            null to ctx.getString(R.string.child_profile_sex_unspecified),
            "boy" to ctx.getString(R.string.child_profile_sex_boy),
            "girl" to ctx.getString(R.string.child_profile_sex_girl),
        )
        for ((sexVal, label) in sexOptions) {
            val chip = Chip(ctx).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                isChecked = (selectedSex == sexVal)
                setOnClickListener { selectedSex = sexVal }
            }
            sexGroup.addView(chip)
        }
        root.addView(sexGroup)

        val dateFmt = DateFormat.getDateInstance(DateFormat.MEDIUM)

        // Birth date & optional birth time
        val birthRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val birthDateBtn = MaterialButton(
            ctx,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isAllCaps = false
            textSize = 12.5f
        }
        val birthTimeBtn = MaterialButton(
            ctx,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            isAllCaps = false
            textSize = 12.5f
        }

        fun refreshBirthLabels() {
            birthDateBtn.text = "${ctx.getString(R.string.child_profile_birth_date_label)}: ${dateFmt.format(birthDate)}"
            birthTimeBtn.text = birthTime
                ?: ctx.getString(R.string.child_profile_birth_time_optional)
        }

        birthDateBtn.setOnClickListener {
            val cal = Calendar.getInstance().apply { time = birthDate }
            DatePickerDialog(
                ctx,
                { _, y, m, d ->
                    val c = Calendar.getInstance().apply {
                        set(y, m, d, 12, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    birthDate = c.time
                    refreshBirthLabels()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        birthTimeBtn.setOnClickListener {
            val parts = birthTime?.split(":")
            val initH = parts?.getOrNull(0)?.toIntOrNull() ?: 8
            val initM = parts?.getOrNull(1)?.toIntOrNull() ?: 0
            TimePickerDialog(
                ctx,
                { _, h, m ->
                    birthTime = String.format(Locale.US, "%02d:%02d", h, m)
                    refreshBirthLabels()
                },
                initH,
                initM,
                AndroidDateFormat.is24HourFormat(ctx)
            ).show()
        }
        birthTimeBtn.setOnLongClickListener {
            birthTime = null
            refreshBirthLabels()
            true
        }
        birthRow.addView(birthDateBtn)
        birthRow.addView(birthTimeBtn)
        root.addView(birthRow)
        refreshBirthLabels()

        // Optional due date (premature corrected age)
        val dueRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val dueDateBtn = MaterialButton(
            ctx,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isAllCaps = false
            textSize = 12.5f
        }
        val clearDueBtn = MaterialButton(
            ctx,
            null,
            androidx.appcompat.R.attr.borderlessButtonStyle
        ).apply {
            text = ctx.getString(R.string.common_clear)
            isAllCaps = false
            textSize = 12f
        }

        fun refreshDueLabel() {
            val d = dueDate
            if (d != null) {
                dueDateBtn.text = "${ctx.getString(R.string.child_profile_due_date_label)}: ${dateFmt.format(d)}"
                clearDueBtn.visibility = View.VISIBLE
            } else {
                dueDateBtn.text = ctx.getString(R.string.child_profile_due_date_optional)
                clearDueBtn.visibility = View.GONE
            }
        }
        dueDateBtn.setOnClickListener {
            val cal = Calendar.getInstance().apply { time = dueDate ?: birthDate }
            DatePickerDialog(
                ctx,
                { _, y, m, d ->
                    val c = Calendar.getInstance().apply {
                        set(y, m, d, 12, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    dueDate = c.time
                    refreshDueLabel()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        clearDueBtn.setOnClickListener {
            dueDate = null
            refreshDueLabel()
        }
        dueRow.addView(dueDateBtn)
        dueRow.addView(clearDueBtn)
        root.addView(dueRow)
        refreshDueLabel()

        val errorView = TextView(ctx).apply {
            setTextColor(ContextCompat.getColor(ctx, R.color.danger_soft_fg))
            textSize = 12.5f
            visibility = View.GONE
            setPadding(dp(2), dp(8), dp(2), 0)
        }
        root.addView(errorView)

        val dialogTitle = if (existing == null) {
            ctx.getString(R.string.child_profile_add_title)
        } else {
            ctx.getString(R.string.child_profile_edit_title)
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(dialogTitle)
            .setView(scroll)
            .setPositiveButton(R.string.logging_save_button, null)
            .setNegativeButton(R.string.history_delete_question_cancel_button, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val first = firstNameEdit.text.toString().trim()
                val last = lastNameEdit.text.toString().trim()
                if (first.isEmpty()) {
                    errorView.text = ctx.getString(R.string.child_profile_error_first_name_required)
                    errorView.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                try {
                    ModelValidators.validateNotFuture(birthDate.time, toleranceMillis = 24L * 3600_000L)
                    val saved = if (existing == null) {
                        activity.repository.createChild(
                            firstName = first,
                            lastName = last,
                            birthDate = birthDate,
                            birthTime = birthTime,
                            dueDate = dueDate,
                            sex = selectedSex,
                            picturePath = pictureRelativePath,
                        )
                    } else {
                        activity.repository.updateChild(
                            id = existing.id,
                            firstName = first,
                            lastName = last,
                            birthDate = birthDate,
                            birthTime = birthTime,
                            dueDate = dueDate,
                            sex = selectedSex,
                            picturePath = pictureRelativePath,
                        )
                    }
                    dialog.dismiss()
                    onSaved(saved)
                } catch (e: ValidationException) {
                    errorView.text = e.message
                    errorView.visibility = View.VISIBLE
                } catch (e: Exception) {
                    errorView.text = e.message ?: ctx.getString(R.string.activity_store_failure_message)
                    errorView.visibility = View.VISIBLE
                }
            }
        }
        dialog.show()
    }
}
