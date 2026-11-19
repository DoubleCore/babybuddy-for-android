package eu.pkgsoftware.babybuddywidgets.history

import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.format.DateFormat as AndroidDateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.squareup.phrase.Phrase
import eu.pkgsoftware.babybuddywidgets.BaseFragment
import eu.pkgsoftware.babybuddywidgets.Constants
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingMethodEnum
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingTypeEnum
import eu.pkgsoftware.babybuddywidgets.Constants.MedicationDosageUnitEnum
import eu.pkgsoftware.babybuddywidgets.LoggedInFragment
import eu.pkgsoftware.babybuddywidgets.MainActivity
import eu.pkgsoftware.babybuddywidgets.R
import eu.pkgsoftware.babybuddywidgets.local.MedicationReminderScheduler
import eu.pkgsoftware.babybuddywidgets.local.ValidationException
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.BmiEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.ChangeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.FeedingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeadCircumferenceEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeightEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.MedicationEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.NoteEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.PumpingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.SleepEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TemperatureEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TummyTimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.WeightEntry
import eu.pkgsoftware.babybuddywidgets.tags.TagSelectorHelper
import eu.pkgsoftware.babybuddywidgets.widgets.HorizontalDecIncEditor
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

object EntryEditBottomSheet {

    fun showImagePreview(activity: MainActivity, relativeImagePath: String?) {
        val file = activity.repository.mediaStorage.resolveMediaFile(relativeImagePath) ?: return
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#EE121212"))
            setPadding(dp(16), dp(24), dp(16), dp(24))
            setOnClickListener { dialog.dismiss() }
        }
        val imageView = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bmp)
            setOnClickListener { dialog.dismiss() }
        }
        val closeBtn = MaterialButton(activity).apply {
            text = activity.getString(R.string.dialog_ok)
            isAllCaps = false
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(imageView)
        root.addView(closeBtn)
        dialog.setContentView(root)
        dialog.show()
    }

    fun show(
        fragment: BaseFragment,
        entry: TimeEntry,
        onSaved: (TimeEntry) -> Unit = {},
    ) {
        val activity = fragment.mainActivity
        val ctx = activity
        val res = ctx.resources
        val density = res.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val sheet = BottomSheetDialog(ctx)
        val scroll = ScrollView(ctx)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.card_surface))
        }
        scroll.addView(container)

        val actName = fragment.translateActivityName(entry.appType)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val headerView = TextView(ctx).apply {
            text = "${ctx.getString(R.string.common_edit)} · $actName"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
        }
        container.addView(headerView)

        var editStart: Date = Date(entry.start.time)
        var editEnd: Date = Date(entry.end.time)
        val isDuration = entry is FeedingEntry || entry is SleepEntry || entry is TummyTimeEntry || entry is PumpingEntry
        val isDateOnly = BabySummaryHelper.isDateOnlyEntry(entry)

        val dateFmt = DateFormat.getDateInstance(DateFormat.SHORT)
        val timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT)

        // Date / Time Row
        val timeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val dateBtn = MaterialButton(
            ctx,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            isAllCaps = false
            textSize = 12f
        }
        val startTimeBtn = MaterialButton(
            ctx,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
            isAllCaps = false
            textSize = 12f
            visibility = if (isDateOnly) View.GONE else View.VISIBLE
        }
        val endTimeBtn = MaterialButton(
            ctx,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
            isAllCaps = false
            textSize = 12f
            visibility = if (isDuration) View.VISIBLE else View.GONE
        }

        fun refreshDateTimeButtons() {
            dateBtn.text = dateFmt.format(editStart)
            if (!isDateOnly) {
                startTimeBtn.text = if (isDuration) {
                    "${res.getString(R.string.logging_start_label)} ${timeFmt.format(editStart)}"
                } else {
                    timeFmt.format(editStart)
                }
            }
            if (isDuration) {
                endTimeBtn.text = "${res.getString(R.string.logging_end_label)} ${timeFmt.format(editEnd)}"
            }
        }

        dateBtn.setOnClickListener {
            val cal = Calendar.getInstance().apply { time = editStart }
            DatePickerDialog(
                ctx,
                { _, y, m, d ->
                    val durMs = (editEnd.time - editStart.time).coerceAtLeast(0L)
                    val next = Calendar.getInstance().apply {
                        time = editStart
                        set(Calendar.YEAR, y)
                        set(Calendar.MONTH, m)
                        set(Calendar.DAY_OF_MONTH, d)
                        if (isDateOnly) {
                            set(Calendar.HOUR_OF_DAY, 12)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                        }
                    }
                    editStart = next.time
                    editEnd = Date(editStart.time + durMs)
                    refreshDateTimeButtons()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        startTimeBtn.setOnClickListener {
            val cal = Calendar.getInstance().apply { time = editStart }
            TimePickerDialog(
                ctx,
                { _, h, min ->
                    val next = Calendar.getInstance().apply {
                        time = editStart
                        set(Calendar.HOUR_OF_DAY, h)
                        set(Calendar.MINUTE, min)
                        set(Calendar.SECOND, 0)
                    }
                    editStart = next.time
                    if (isDuration && editEnd.before(editStart)) {
                        editEnd = Date(editStart.time + 15 * 60_000L)
                    } else if (!isDuration) {
                        editEnd = editStart
                    }
                    refreshDateTimeButtons()
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                AndroidDateFormat.is24HourFormat(ctx)
            ).show()
        }

        endTimeBtn.setOnClickListener {
            val cal = Calendar.getInstance().apply { time = editEnd }
            TimePickerDialog(
                ctx,
                { _, h, min ->
                    val next = Calendar.getInstance().apply {
                        time = editStart
                        set(Calendar.HOUR_OF_DAY, h)
                        set(Calendar.MINUTE, min)
                        set(Calendar.SECOND, 0)
                    }
                    if (next.time.before(editStart)) {
                        next.add(Calendar.DAY_OF_MONTH, 1)
                    }
                    editEnd = next.time
                    refreshDateTimeButtons()
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                AndroidDateFormat.is24HourFormat(ctx)
            ).show()
        }

        timeRow.addView(dateBtn)
        timeRow.addView(startTimeBtn)
        timeRow.addView(endTimeBtn)
        container.addView(timeRow)
        refreshDateTimeButtons()

        fun addLabel(text: String): TextView {
            val tv = TextView(ctx).apply {
                this.text = text
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
                setPadding(dp(2), dp(10), dp(2), dp(4))
            }
            container.addView(tv)
            return tv
        }

        fun makeMultilineEdit(hintStr: String, initial: String): EditText {
            return EditText(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                hint = hintStr
                setText(initial)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 2
                maxLines = 5
                gravity = Gravity.TOP or Gravity.START
                setBackgroundResource(R.drawable.bg_soft_pill)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                textSize = 14f
            }
        }

        // State holders per entry subtype
        var feedType = (entry as? FeedingEntry)?.feedingType ?: FeedingTypeEnum.BREAST_MILK
        var feedMethod = (entry as? FeedingEntry)?.feedingMethod ?: FeedingMethodEnum.BOTTLE
        var sleepNap = (entry as? SleepEntry)?.isNap ?: false
        var diaperWet = (entry as? ChangeEntry)?.wet ?: false
        var diaperSolid = (entry as? ChangeEntry)?.solid ?: false
        var diaperColor = (entry as? ChangeEntry)?.color ?: ""
        var tummyMilestoneEdit: EditText? = null
        var tummyExtraNotesEdit: EditText? = null
        var medNameEdit: EditText? = null
        var medUnit = (entry as? MedicationEntry)?.dosageUnit ?: MedicationDosageUnitEnum.ML
        var medIntervalMin: Int? = (entry as? MedicationEntry)?.nextDoseIntervalMinutes
        var noteImagePath: String? = (entry as? NoteEntry)?.imagePath
        var numericEditor: HorizontalDecIncEditor? = null

        when (entry) {
            is FeedingEntry -> {
                addLabel(res.getString(R.string.tile_feeding))
                val typeLabels = res.getStringArray(R.array.feedingTypes)
                val typeChips = ChipGroup(ctx).apply {
                    isSingleSelection = true
                    isSelectionRequired = true
                }
                for (ft in FeedingTypeEnum.entries) {
                    val chip = Chip(ctx).apply {
                        id = View.generateViewId()
                        text = typeLabels.getOrElse(ft.value) { ft.post_name }
                        isCheckable = true
                        isChecked = (ft == feedType)
                        setOnClickListener { feedType = ft }
                    }
                    typeChips.addView(chip)
                }
                container.addView(typeChips)

                val methodLabels = res.getStringArray(R.array.feedingMethods)
                val methodChips = ChipGroup(ctx).apply {
                    isSingleSelection = true
                    isSelectionRequired = true
                }
                for (fm in FeedingMethodEnum.entries) {
                    val chip = Chip(ctx).apply {
                        id = View.generateViewId()
                        text = methodLabels.getOrElse(fm.value) { fm.post_name }
                        isCheckable = true
                        isChecked = (fm == feedMethod)
                        setOnClickListener { feedMethod = fm }
                    }
                    methodChips.addView(chip)
                }
                container.addView(methodChips)

                addLabel(res.getString(R.string.feeding_amount_title))
                numericEditor = HorizontalDecIncEditor(ctx).apply {
                    allowNull = true
                    stepSize = 10.0
                    unitSuffix = res.getString(R.string.unit_ml)
                    value = entry.amount
                }
                container.addView(numericEditor)
            }

            is SleepEntry -> {
                val napChip = Chip(ctx).apply {
                    id = View.generateViewId()
                    text = res.getString(R.string.sleep_nap_label)
                    isCheckable = true
                    isChecked = sleepNap
                    setOnCheckedChangeListener { _, checked -> sleepNap = checked }
                }
                container.addView(napChip)
            }

            is ChangeEntry -> {
                val wsGroup = ChipGroup(ctx).apply {
                    isSingleSelection = false
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                }
                val wetChip = Chip(ctx).apply {
                    text = res.getString(R.string.diaper_wet_label)
                    isCheckable = true
                    isChecked = diaperWet
                    setOnCheckedChangeListener { _, c -> diaperWet = c }
                }
                val solidChip = Chip(ctx).apply {
                    text = res.getString(R.string.diaper_solid_label)
                    isCheckable = true
                    isChecked = diaperSolid
                    setOnCheckedChangeListener { _, c -> diaperSolid = c }
                }
                wsGroup.addView(wetChip)
                wsGroup.addView(solidChip)
                container.addView(wsGroup)

                addLabel(res.getString(R.string.diaper_color_label))
                val colorScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
                val colorRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                colorScroll.addView(colorRow)
                container.addView(colorScroll)

                fun rebuildDiaperColors() {
                    colorRow.removeAllViews()
                    val noneChip = Chip(ctx).apply {
                        text = res.getString(R.string.common_none)
                        isCheckable = true
                        isChecked = diaperColor.isEmpty()
                        setOnClickListener {
                            diaperColor = ""
                            rebuildDiaperColors()
                        }
                    }
                    colorRow.addView(noneChip)
                    for (cEnum in Constants.SolidDiaperColorEnum.entries) {
                        val selected = diaperColor.equals(cEnum.post_name, ignoreCase = true)
                        val swatch = View(ctx).apply {
                            val sz = if (selected) dp(34) else dp(28)
                            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginStart = dp(8) }
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(ContextCompat.getColor(ctx, cEnum.colorResId))
                                setStroke(
                                    dp(if (selected) 3 else 1),
                                    ContextCompat.getColor(
                                        ctx,
                                        if (selected) R.color.primary_color else R.color.card_stroke
                                    )
                                )
                            }
                            setOnClickListener {
                                diaperColor = cEnum.post_name
                                rebuildDiaperColors()
                            }
                        }
                        colorRow.addView(swatch)
                    }
                }
                rebuildDiaperColors()

                addLabel(res.getString(R.string.diaper_amount_title))
                numericEditor = HorizontalDecIncEditor(ctx).apply {
                    allowNull = true
                    stepSize = 10.0
                    value = entry.amount
                }
                container.addView(numericEditor)
            }

            is TummyTimeEntry -> {
                addLabel(res.getString(R.string.tummy_milestone_label))
                tummyMilestoneEdit = makeMultilineEdit(
                    res.getString(R.string.tummy_milestone_hint),
                    entry.milestone
                )
                container.addView(tummyMilestoneEdit)

                addLabel(res.getString(R.string.activity_notes_hint))
                tummyExtraNotesEdit = makeMultilineEdit(
                    res.getString(R.string.activity_notes_hint),
                    entry.extraNotes ?: ""
                )
                container.addView(tummyExtraNotesEdit)
            }

            is PumpingEntry -> {
                addLabel(res.getString(R.string.pumping_amount_title))
                numericEditor = HorizontalDecIncEditor(ctx).apply {
                    allowNull = false
                    stepSize = 10.0
                    unitSuffix = res.getString(R.string.unit_ml)
                    value = entry.amount
                }
                container.addView(numericEditor)
            }

            is MedicationEntry -> {
                addLabel(res.getString(R.string.medication_name_label))
                medNameEdit = EditText(ctx).apply {
                    hint = res.getString(R.string.medication_name_hint)
                    setText(entry.name)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                    setBackgroundResource(R.drawable.bg_soft_pill)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    textSize = 14f
                }
                container.addView(medNameEdit)

                val recentNames = activity.repository.getRecentMedicationNames(entry.childId)
                if (recentNames.isNotEmpty()) {
                    val nameScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
                    val nameChips = ChipGroup(ctx).apply { isSingleLine = true }
                    for (n in recentNames) {
                        nameChips.addView(Chip(ctx).apply {
                            text = n
                            textSize = 11.5f
                            setOnClickListener { medNameEdit?.setText(n) }
                        })
                    }
                    nameScroll.addView(nameChips)
                    container.addView(nameScroll)
                }

                addLabel(res.getString(R.string.medication_dosage_label))
                numericEditor = HorizontalDecIncEditor(ctx).apply {
                    allowNull = false
                    stepSize = 0.5
                    defaultBaseline = 5.0
                    value = entry.dosage
                }
                container.addView(numericEditor)

                val unitGroup = ChipGroup(ctx).apply {
                    isSingleSelection = true
                    isSelectionRequired = true
                }
                val unitLabels = mapOf(
                    MedicationDosageUnitEnum.ML to res.getString(R.string.medication_unit_ml),
                    MedicationDosageUnitEnum.MG to res.getString(R.string.medication_unit_mg),
                    MedicationDosageUnitEnum.DROPS to res.getString(R.string.medication_unit_drops),
                    MedicationDosageUnitEnum.TABLETS to res.getString(R.string.medication_unit_tablets),
                )
                for (u in MedicationDosageUnitEnum.entries) {
                    unitGroup.addView(Chip(ctx).apply {
                        id = View.generateViewId()
                        text = unitLabels[u] ?: u.post_name
                        isCheckable = true
                        isChecked = (u == medUnit)
                        setOnClickListener { medUnit = u }
                    })
                }
                container.addView(unitGroup)

                addLabel(res.getString(R.string.medication_interval_label))
                val intervalGroup = ChipGroup(ctx).apply {
                    isSingleSelection = true
                    isSelectionRequired = true
                }
                val intervals = listOf<Pair<Int?, String>>(
                    null to res.getString(R.string.common_none),
                    240 to "4h",
                    360 to "6h",
                    480 to "8h",
                    720 to "12h",
                    1440 to "24h",
                )
                for ((mins, label) in intervals) {
                    intervalGroup.addView(Chip(ctx).apply {
                        id = View.generateViewId()
                        text = label
                        isCheckable = true
                        isChecked = (medIntervalMin == mins)
                        setOnClickListener { medIntervalMin = mins }
                    })
                }
                container.addView(intervalGroup)
            }

            is NoteEntry -> {
                val imgPreview = ImageView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(160)
                    ).apply { topMargin = dp(8) }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    visibility = View.GONE
                    setOnClickListener { showImagePreview(activity, noteImagePath) }
                }
                val photoBtnRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                }
                val pickPhotoBtn = MaterialButton(
                    ctx,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = res.getString(R.string.note_attach_photo)
                    isAllCaps = false
                    textSize = 12f
                }
                val removePhotoBtn = MaterialButton(
                    ctx,
                    null,
                    androidx.appcompat.R.attr.borderlessButtonStyle
                ).apply {
                    text = res.getString(R.string.child_profile_remove_photo)
                    isAllCaps = false
                    textSize = 12f
                }

                fun refreshNotePhoto() {
                    val file = activity.repository.mediaStorage.resolveMediaFile(noteImagePath)
                    val bmp = file?.let { BitmapFactory.decodeFile(it.absolutePath) }
                    if (bmp != null) {
                        imgPreview.setImageBitmap(bmp)
                        imgPreview.visibility = View.VISIBLE
                        removePhotoBtn.visibility = View.VISIBLE
                    } else {
                        imgPreview.visibility = View.GONE
                        removePhotoBtn.visibility = View.GONE
                    }
                }
                pickPhotoBtn.setOnClickListener {
                    activity.pickPhoto { uri ->
                        if (uri != null) {
                            val imported = activity.repository.mediaStorage.importImageFromUri(uri, "note")
                            if (imported != null) {
                                noteImagePath = imported
                                refreshNotePhoto()
                            }
                        }
                    }
                }
                removePhotoBtn.setOnClickListener {
                    noteImagePath = null
                    refreshNotePhoto()
                }
                photoBtnRow.addView(pickPhotoBtn)
                photoBtnRow.addView(removePhotoBtn)
                container.addView(imgPreview)
                container.addView(photoBtnRow)
                refreshNotePhoto()
            }

            is TemperatureEntry -> {
                numericEditor = HorizontalDecIncEditor(ctx).apply {
                    allowNull = false
                    stepSize = 0.1
                    unitSuffix = res.getString(R.string.unit_celsius)
                    value = entry.temperature
                }
                container.addView(numericEditor)
            }

            is WeightEntry -> {
                numericEditor = HorizontalDecIncEditor(ctx).apply {
                    allowNull = false
                    stepSize = 0.1
                    unitSuffix = res.getString(R.string.unit_kg)
                    value = entry.weight
                }
                container.addView(numericEditor)
            }

            is HeightEntry -> {
                numericEditor = HorizontalDecIncEditor(ctx).apply {
                    allowNull = false
                    stepSize = 0.5
                    unitSuffix = res.getString(R.string.unit_cm)
                    value = entry.height
                }
                container.addView(numericEditor)
            }

            is HeadCircumferenceEntry -> {
                numericEditor = HorizontalDecIncEditor(ctx).apply {
                    allowNull = false
                    stepSize = 0.5
                    unitSuffix = res.getString(R.string.unit_cm)
                    value = entry.head_circumference
                }
                container.addView(numericEditor)
            }

            is BmiEntry -> {
                numericEditor = HorizontalDecIncEditor(ctx).apply {
                    allowNull = false
                    stepSize = 0.1
                    value = entry.bmi
                }
                container.addView(numericEditor)
                activity.repository.computeSuggestedBmi(entry.childId)?.let { suggested ->
                    val autoChip = Chip(ctx).apply {
                        text = Phrase.from(ctx, R.string.bmi_auto_calc_chip)
                            .put("bmi", BabySummaryHelper.formatCleanNumber(suggested.bmi))
                            .format()
                            .toString()
                        setOnClickListener { numericEditor?.value = suggested.bmi }
                    }
                    container.addView(autoChip)
                }
            }
        }

        // Common Notes field (except TummyTimeEntry which already rendered separate milestone + extraNotes)
        val notesEdit: EditText? = if (entry is TummyTimeEntry) {
            null
        } else {
            makeMultilineEdit(res.getString(R.string.activity_notes_hint), entry.notes).also {
                container.addView(it)
            }
        }

        // Tags Selector
        val tagSelector = TagSelectorHelper(activity, entry.tags)
        container.addView(tagSelector.rootView)

        val errorView = TextView(ctx).apply {
            setTextColor(ContextCompat.getColor(ctx, R.color.danger_soft_fg))
            textSize = 12.5f
            visibility = View.GONE
            setPadding(dp(2), dp(8), dp(2), 0)
        }
        container.addView(errorView)

        val saveBtn = MaterialButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply { topMargin = dp(12) }
            text = res.getString(R.string.logging_save_button)
            isAllCaps = false
            cornerRadius = dp(12)
        }
        container.addView(saveBtn)

        saveBtn.setOnClickListener {
            try {
                val chosenTags = tagSelector.selectedTags
                val commonNotes = notesEdit?.text?.toString() ?: ""

                val updatedModel: TimeEntry = when (entry) {
                    is FeedingEntry -> entry.copy(
                        start = editStart,
                        end = editEnd,
                        feedingType = feedType,
                        feedingMethod = feedMethod,
                        amount = numericEditor?.value,
                        _notes = commonNotes,
                        tags = chosenTags,
                    )
                    is SleepEntry -> entry.copy(
                        start = editStart,
                        end = editEnd,
                        nap = sleepNap,
                        _notes = commonNotes,
                        tags = chosenTags,
                    )
                    is ChangeEntry -> entry.copy(
                        start = editStart,
                        wet = diaperWet,
                        solid = diaperSolid,
                        color = diaperColor,
                        amount = numericEditor?.value,
                        _notes = commonNotes,
                        tags = chosenTags,
                    )
                    is TummyTimeEntry -> entry.copy(
                        start = editStart,
                        end = editEnd,
                        _notes = tummyMilestoneEdit?.text?.toString() ?: "",
                        extraNotes = tummyExtraNotesEdit?.text?.toString() ?: "",
                        tags = chosenTags,
                    )
                    is PumpingEntry -> entry.copy(
                        _start = editStart,
                        _end = editEnd,
                        amount = numericEditor?.value ?: 0.0,
                        _notes = commonNotes,
                        _legacyTime = editStart,
                        tags = chosenTags,
                    )
                    is MedicationEntry -> {
                        val name = medNameEdit?.text?.toString()?.trim().orEmpty()
                        if (name.isEmpty()) {
                            throw ValidationException(res.getString(R.string.medication_error_name_required))
                        }
                        val dosage = numericEditor?.value ?: 0.0
                        if (dosage <= 0.0) {
                            throw ValidationException(res.getString(R.string.medication_error_dosage_positive))
                        }
                        entry.copy(
                            start = editStart,
                            name = name,
                            dosage = dosage,
                            dosageUnit = medUnit,
                            nextDoseIntervalMinutes = medIntervalMin,
                            _notes = commonNotes,
                            tags = chosenTags,
                        )
                    }
                    is NoteEntry -> entry.copy(
                        start = editStart,
                        _notes = commonNotes,
                        imagePath = noteImagePath,
                        tags = chosenTags,
                    )
                    is TemperatureEntry -> entry.copy(
                        start = editStart,
                        temperature = numericEditor?.value ?: entry.temperature,
                        _notes = commonNotes,
                        tags = chosenTags,
                    )
                    is WeightEntry -> entry.copy(
                        start = editStart,
                        weight = numericEditor?.value ?: entry.weight,
                        _notes = commonNotes,
                        tags = chosenTags,
                    )
                    is HeightEntry -> entry.copy(
                        start = editStart,
                        height = numericEditor?.value ?: entry.height,
                        _notes = commonNotes,
                        tags = chosenTags,
                    )
                    is HeadCircumferenceEntry -> entry.copy(
                        start = editStart,
                        head_circumference = numericEditor?.value ?: entry.head_circumference,
                        _notes = commonNotes,
                        tags = chosenTags,
                    )
                    is BmiEntry -> entry.copy(
                        start = editStart,
                        bmi = numericEditor?.value ?: entry.bmi,
                        _notes = commonNotes,
                        tags = chosenTags,
                    )
                    else -> entry
                }

                val saved = activity.repository.updateEntry(updatedModel)
                if (saved is MedicationEntry) {
                    val childName = activity.repository.getChildById(saved.childId)?.fullName ?: ""
                    MedicationReminderScheduler.cancel(ctx, saved.id)
                    MedicationReminderScheduler.scheduleIfNeeded(ctx, saved, childName)
                }
                (fragment as? LoggedInFragment)?.notifyChildDataChanged(saved.childId)
                sheet.dismiss()
                onSaved(saved)
            } catch (e: ValidationException) {
                errorView.text = e.message
                errorView.visibility = View.VISIBLE
            } catch (e: Exception) {
                errorView.text = e.message ?: res.getString(R.string.activity_store_failure_message)
                errorView.visibility = View.VISIBLE
            }
        }

        sheet.setContentView(scroll)
        sheet.show()
    }
}
