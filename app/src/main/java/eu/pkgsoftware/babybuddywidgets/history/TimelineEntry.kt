package eu.pkgsoftware.babybuddywidgets.history

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.squareup.phrase.Phrase
import eu.pkgsoftware.babybuddywidgets.BaseFragment
import eu.pkgsoftware.babybuddywidgets.Constants
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingMethodEnum
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingTypeEnum
import eu.pkgsoftware.babybuddywidgets.Constants.MedicationDosageUnitEnum
import eu.pkgsoftware.babybuddywidgets.DialogCallback
import eu.pkgsoftware.babybuddywidgets.LoggedInFragment
import eu.pkgsoftware.babybuddywidgets.R
import eu.pkgsoftware.babybuddywidgets.databinding.TimelineItemBinding
import eu.pkgsoftware.babybuddywidgets.local.MedicationReminderScheduler
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient
import eu.pkgsoftware.babybuddywidgets.networking.RequestCodeFailure
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.BmiEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.ChangeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.FeedingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeadCircumferenceEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeightEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.MedicationEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.NoteEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.PumpingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.SleepEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TagEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TemperatureEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.WeightEntry
import eu.pkgsoftware.babybuddywidgets.tags.TagManagerDialog
import eu.pkgsoftware.babybuddywidgets.widgets.HorizontalDecIncEditor
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.DateFormat
import java.text.NumberFormat
import java.time.ZoneId
import kotlin.math.max

fun interpreteAmountValue(fragment: BaseFragment, amount: Double?): String {
    if (amount == null) {
        return ""
    }
    val nf = NumberFormat.getInstance()
    nf.maximumIntegerDigits = 4
    nf.maximumFractionDigits = 2
    nf.minimumFractionDigits = 0
    return Phrase.from(
        fragment.resources, R.string.history_amount_timeline_pattern
    ).put("amount", nf.format(amount)).format().toString()
}

class TimelineEntry(private val fragment: BaseFragment, private var _entry: TimeEntry?) {
    companion object {
        val DATE_FORMAT: DateFormat
            get() = DateFormat.getDateInstance(DateFormat.SHORT)
        val TIME_FORMAT: DateFormat
            get() = DateFormat.getTimeInstance(DateFormat.SHORT)
    }

    private val binding = TimelineItemBinding.inflate(fragment.mainActivity.layoutInflater)
    private var modifiedCallback: Runnable? = null

    var tagColorLookup: Map<String, TagEntry> = emptyMap()
    var onEditClickListener: ((TimeEntry) -> Unit)? = null

    var entry: TimeEntry?
        get() = _entry
        set(v) {
            _entry = v
            updateUi()
        }

    val view: View
        get() = binding.root

    fun setDateHeader(headerText: String?) {
        if (headerText.isNullOrBlank()) {
            binding.dateSectionHeader.visibility = View.GONE
        } else {
            binding.dateSectionHeader.text = headerText
            binding.dateSectionHeader.visibility = View.VISIBLE
        }
    }

    init {
        binding.entryCard.setOnClickListener { openEditor() }
        binding.entryCard.setOnLongClickListener { openEditor() }
        binding.entryCard.setOnTouchListener { v, event ->
            longClickStartStopHandler(v, event)
            false
        }
        binding.openWebButton.setOnClickListener { openEditor() }
        binding.removeButton.setOnClickListener { removeClick() }
        updateUi()
    }

    private fun setBadgeVisual(
        iconRes: Int,
        bgColorRes: Int,
        paddingDp: Int = 5,
        multiColor3d: Boolean = true
    ) {
        val ctx = fragment.requireContext()
        val padPx = (paddingDp * ctx.resources.displayMetrics.density).toInt()
        binding.entryIconBadge.setPadding(padPx, padPx, padPx, padPx)
        binding.entryIconBadge.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(ctx, bgColorRes))
        binding.entryIconImage.setImageResource(iconRes)
        if (multiColor3d) {
            androidx.core.widget.ImageViewCompat.setImageTintList(binding.entryIconImage, null)
        } else {
            androidx.core.widget.ImageViewCompat.setImageTintList(
                binding.entryIconImage,
                ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.fixed_color_button_text_color))
            )
        }
    }

    private fun updateUi() {
        val entry = _entry
        if (entry == null) {
            binding.entryCard.visibility = View.INVISIBLE
            return
        }
        binding.entryCard.visibility = View.VISIBLE
        binding.diaperWetImage.visibility = View.GONE
        binding.diaperSolidImage.visibility = View.GONE
        binding.diaperColorPreview.visibility = View.GONE
        binding.notePhotoPreview.visibility = View.GONE

        val ctx = fragment.requireContext()
        val res = fragment.resources
        val localStart = BabySummaryHelper.entryClientStart(entry)
        val localEnd = BabySummaryHelper.entryClientEnd(entry)
        val isDateOnly = BabySummaryHelper.isDateOnlyEntry(entry)

        val timeDiffSec = max(0L, (localEnd.time - localStart.time) / 1000L)
        val startTimeStr = TIME_FORMAT.format(localStart)
        val endTimeStr = TIME_FORMAT.format(localEnd)
        val durationHours = timeDiffSec / 3600L
        val durationMins = max(1L, (timeDiffSec % 3600L) / 60L).toString().padStart(2, '0')
        val timeRangeStr = when {
            isDateOnly -> DATE_FORMAT.format(localStart)
            timeDiffSec < 30L -> startTimeStr
            else -> "$startTimeStr - $endTimeStr ($durationHours:$durationMins)"
        }

        val activityTitle = fragment.translateActivityName(entry.appType)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        var titleLine = activityTitle
        var metaLine = timeRangeStr

        when (entry) {
            is FeedingEntry -> {
                when (entry.feedingType) {
                    FeedingTypeEnum.BREAST_MILK -> when (entry.feedingMethod) {
                        FeedingMethodEnum.LEFT_BREAST ->
                            setBadgeVisual(R.drawable.pkg_breast_left, R.color.bf_cell_feeding, 3, false)
                        FeedingMethodEnum.RIGHT_BREAST ->
                            setBadgeVisual(R.drawable.pkg_breast_right, R.color.bf_cell_feeding, 3, false)
                        FeedingMethodEnum.BOTH_BREASTS ->
                            setBadgeVisual(R.drawable.pkg_breast, R.color.bf_cell_feeding, 8, false)
                        else ->
                            setBadgeVisual(R.drawable.ic_bf_3d_bottle, R.color.bf_cell_feeding, 5, true)
                    }
                    FeedingTypeEnum.SOLID_FOOD ->
                        setBadgeVisual(R.drawable.pkg_solid_food, R.color.bf_cell_feeding, 8, false)
                    else ->
                        setBadgeVisual(R.drawable.ic_bf_3d_bottle, R.color.bf_cell_feeding, 5, true)
                }

                val detail = BabySummaryHelper.formatFeedingMethodDetail(res, entry)
                if (detail.isNotBlank()) {
                    titleLine = "$activityTitle · $detail"
                }
            }
            is SleepEntry -> {
                setBadgeVisual(R.drawable.ic_bf_3d_sleep, R.color.bf_cell_sleep, 5, true)
                if (entry.isNap) {
                    titleLine = "$activityTitle · ${res.getString(R.string.sleep_nap_label)}"
                }
            }
            is ChangeEntry -> {
                setBadgeVisual(R.drawable.ic_bf_3d_diaper, R.color.bf_cell_diaper, 5, true)
                binding.diaperWetImage.visibility = if (entry.wet) View.VISIBLE else View.GONE
                binding.diaperSolidImage.visibility = if (entry.solid) View.VISIBLE else View.GONE
                if (!entry.wet && !entry.solid) {
                    titleLine = "$activityTitle · ${res.getString(R.string.diaper_empty_label)}"
                }
                if (entry.color.isNotEmpty()) {
                    try {
                        val colorEnum = Constants.SolidDiaperColorEnum.byPostName(entry.color)
                        val c = ContextCompat.getColor(ctx, colorEnum.colorResId)
                        binding.diaperColorPreview.backgroundTintList = ColorStateList.valueOf(c)
                        binding.diaperColorPreview.visibility = View.VISIBLE
                    } catch (_: Exception) {
                        binding.diaperColorPreview.visibility = View.GONE
                    }
                }
                val amtStr = interpreteAmountValue(fragment, entry.amount)
                if (amtStr.isNotBlank()) {
                    metaLine = "$timeRangeStr · $amtStr"
                }
            }
            is PumpingEntry -> {
                setBadgeVisual(R.drawable.ic_bf_3d_supplement, R.color.bf_cell_supplement, 5, true)
                val cleanAmt = HorizontalDecIncEditor.formatClean(entry.amount)
                titleLine = "$activityTitle · $cleanAmt ${res.getString(R.string.unit_ml)}"
            }
            is MedicationEntry -> {
                setBadgeVisual(R.drawable.ic_medication, R.color.medication_enabled, 8, false)
                val unitLabel = when (entry.dosageUnit) {
                    MedicationDosageUnitEnum.ML -> res.getString(R.string.medication_unit_ml)
                    MedicationDosageUnitEnum.MG -> res.getString(R.string.medication_unit_mg)
                    MedicationDosageUnitEnum.DROPS -> res.getString(R.string.medication_unit_drops)
                    MedicationDosageUnitEnum.TABLETS -> res.getString(R.string.medication_unit_tablets)
                }
                val cleanDosage = HorizontalDecIncEditor.formatClean(entry.dosage)
                titleLine = "${entry.name} · $cleanDosage $unitLabel"
                entry.nextDoseTime?.let { nextTime ->
                    val nextStr = if (entry.isNextDoseReady()) {
                        res.getString(R.string.medication_next_dose_ready)
                    } else {
                        Phrase.from(res, R.string.medication_next_dose_at_pattern)
                            .put("time", TIME_FORMAT.format(nextTime))
                            .format()
                            .toString()
                    }
                    metaLine = "$timeRangeStr · $nextStr"
                }
            }
            is NoteEntry -> {
                setBadgeVisual(R.drawable.ic_bf_3d_care, R.color.bf_cell_care, 5, true)
                val file = fragment.mainActivity.repository.mediaStorage.resolveMediaFile(entry.imagePath)
                val bmp = file?.let { BitmapFactory.decodeFile(it.absolutePath) }
                if (bmp != null) {
                    binding.notePhotoPreview.setImageBitmap(bmp)
                    binding.notePhotoPreview.visibility = View.VISIBLE
                    binding.notePhotoPreview.setOnClickListener {
                        EntryEditBottomSheet.showImagePreview(fragment.mainActivity, entry.imagePath)
                    }
                }
            }
            is TemperatureEntry -> {
                setBadgeVisual(R.drawable.ic_temperature, R.color.temperature_enabled, 8, false)
                val valStr = Phrase.from(res, R.string.measurement_value_pattern)
                    .put("value", HorizontalDecIncEditor.formatClean(entry.temperature))
                    .put("unit", res.getString(R.string.unit_celsius))
                    .format()
                titleLine = "$activityTitle · $valStr"
            }
            is WeightEntry -> {
                setBadgeVisual(R.drawable.ic_weight, R.color.weight_enabled, 8, false)
                val valStr = Phrase.from(res, R.string.measurement_value_pattern)
                    .put("value", HorizontalDecIncEditor.formatClean(entry.weight))
                    .put("unit", res.getString(R.string.unit_kg))
                    .format()
                titleLine = "$activityTitle · $valStr"
            }
            is HeightEntry -> {
                setBadgeVisual(R.drawable.ic_height, R.color.height_enabled, 8, false)
                val valStr = Phrase.from(res, R.string.measurement_value_pattern)
                    .put("value", HorizontalDecIncEditor.formatClean(entry.height))
                    .put("unit", res.getString(R.string.unit_cm))
                    .format()
                titleLine = "$activityTitle · $valStr"
            }
            is HeadCircumferenceEntry -> {
                setBadgeVisual(R.drawable.ic_head_circumference, R.color.head_circumference_enabled, 8, false)
                val valStr = Phrase.from(res, R.string.measurement_value_pattern)
                    .put("value", HorizontalDecIncEditor.formatClean(entry.head_circumference))
                    .put("unit", res.getString(R.string.unit_cm))
                    .format()
                titleLine = "$activityTitle · $valStr"
            }
            is BmiEntry -> {
                setBadgeVisual(R.drawable.ic_bmi, R.color.bmi_enabled, 8, false)
                val valStr = Phrase.from(res, R.string.measurement_bmi_pattern)
                    .put("value", HorizontalDecIncEditor.formatClean(entry.bmi))
                    .format()
                titleLine = valStr.toString()
            }
            else -> {
                when (entry.appType) {
                    BabyBuddyClient.ACTIVITIES.SLEEP ->
                        setBadgeVisual(R.drawable.ic_bf_3d_sleep, R.color.bf_cell_sleep, 5, true)
                    BabyBuddyClient.ACTIVITIES.TUMMY_TIME ->
                        setBadgeVisual(R.drawable.ic_bf_3d_edu, R.color.bf_cell_edu, 5, true)
                    BabyBuddyClient.EVENTS.NOTE ->
                        setBadgeVisual(R.drawable.ic_bf_3d_care, R.color.bf_cell_care, 5, true)
                    else ->
                        setBadgeVisual(R.drawable.ic_bf_baby_avatar, R.color.bf_cell_feeding, 4, true)
                }
            }
        }

        binding.entryTitleText.text = titleLine
        binding.entryTimeText.text = metaLine

        val cleanNotes = entry.notes.trim()
        if (cleanNotes.isNotEmpty()) {
            binding.entrySubtitleText.text = cleanNotes
            binding.entrySubtitleText.visibility = View.VISIBLE
        } else {
            binding.entrySubtitleText.visibility = View.GONE
        }

        renderTagPills(ctx, entry.tags)
    }

    private fun renderTagPills(ctx: android.content.Context, tags: List<String>) {
        binding.entryTagsRow.removeAllViews()
        if (tags.isEmpty()) {
            binding.entryTagsScroll.visibility = View.GONE
            return
        }
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        for (tagName in tags) {
            val tagEntry = tagColorLookup[tagName]
            val bgHex = tagEntry?.color ?: TagEntry.DEFAULT_COLORS[0]
            val fgHex = tagEntry?.complementaryTextColorHex() ?: "#FFFFFF"

            val pill = TextView(ctx).apply {
                text = "#$tagName"
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(TagManagerDialog.parseColorSafe(fgHex, Color.WHITE))
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(TagManagerDialog.parseColorSafe(bgHex))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(5) }
            }
            binding.entryTagsRow.addView(pill)
        }
        binding.entryTagsScroll.visibility = View.VISIBLE
    }

    private fun defaultPhraseFields(phrase: Phrase): Phrase {
        val e = entry!!
        val localStartTime = BabySummaryHelper.entryClientStart(e)
        val localEndTime = BabySummaryHelper.entryClientEnd(e)

        val startUtc = localStartTime.toInstant().atZone(ZoneId.of("UTC"))
        val endUtc = localEndTime.toInstant().atZone(ZoneId.of("UTC"))
        val timeDiff = endUtc.toEpochSecond() - startUtc.toEpochSecond()
        val timeDiffHours = timeDiff / 3600
        val timeDiffMinutes = max(1, (timeDiff % 3600) / 60)
        val timeDiffMinutesStr = timeDiffMinutes.toString().padStart(2, '0')
        val timeDiffString = "$timeDiffHours:$timeDiffMinutesStr"

        val startTime = TIME_FORMAT.format(localStartTime)
        val endTime = TIME_FORMAT.format(localEndTime)
        val optTimeRange = if (timeDiff < 30) startTime else "$startTime - $endTime ($timeDiffString)"

        return phrase
            .putOptional("type", fragment.translateActivityName(e.appType))
            .putOptional("start_date", DATE_FORMAT.format(localStartTime))
            .putOptional("start_time", TIME_FORMAT.format(localStartTime))
            .putOptional("end_date", DATE_FORMAT.format(localEndTime))
            .putOptional("end_time", TIME_FORMAT.format(localEndTime))
            .putOptional("opt_time_range", optTimeRange)
            .putOptional("time_diff", timeDiffString)
            .putOptional("notes", e.notes.trim())
    }

    private fun longClickStartStopHandler(v: View, event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_DOWN) {
            binding.longclickBubble.startGrow()
        } else if (event.action in listOf(
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_OUTSIDE,
            )
        ) {
            binding.longclickBubble.stopGrow()
        }
    }

    private fun openEditor(): Boolean {
        binding.longclickBubble.stopGrow()
        val thisEntry = entry ?: return false
        val listener = onEditClickListener
        if (listener != null) {
            listener(thisEntry)
        } else {
            EntryEditBottomSheet.show(fragment, thisEntry) {
                modifiedCallback?.run()
            }
        }
        return true
    }

    private fun removeClick() {
        val thisEntry: TimeEntry = entry ?: return
        fragment.showQuestion(
            true,
            fragment.resources.getString(R.string.history_delete_title),
            defaultPhraseFields(
                Phrase.from(fragment.mainActivity, R.string.history_delete_question)
            ).format().toString().trim(),
            fragment.resources.getString(R.string.history_delete_question_delete_button),
            fragment.resources.getString(R.string.history_delete_question_cancel_button),
            object : DialogCallback {
                override fun call(b: Boolean) {
                    if (!b) {
                        return
                    }
                    val client = fragment.mainActivity.client
                    fragment.mainActivity.scope.launch {
                        try {
                            if (thisEntry is MedicationEntry) {
                                MedicationReminderScheduler.cancel(fragment.mainActivity, thisEntry.id)
                            }
                            client.v2client.deleteEntry(thisEntry)
                            (fragment as? LoggedInFragment)?.notifyChildDataChanged(thisEntry.childId)
                            this@TimelineEntry.entry = null
                            modifiedCallback?.run()
                        } catch (e: RequestCodeFailure) {
                            e.printStackTrace()
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        )
    }

    fun setModifiedCallback(r: Runnable?) {
        modifiedCallback = r
    }
}
