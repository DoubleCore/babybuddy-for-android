package eu.pkgsoftware.babybuddywidgets.timers

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.text.format.DateFormat as AndroidDateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.squareup.phrase.Phrase
import eu.pkgsoftware.babybuddywidgets.BaseFragment
import eu.pkgsoftware.babybuddywidgets.Constants
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingMethodEnum
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingTypeEnum
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingTypeEnumValues
import eu.pkgsoftware.babybuddywidgets.Constants.MedicationDosageUnitEnum
import eu.pkgsoftware.babybuddywidgets.DialogCallback
import eu.pkgsoftware.babybuddywidgets.R
import eu.pkgsoftware.babybuddywidgets.StoreFunction
import eu.pkgsoftware.babybuddywidgets.databinding.BabyManagerBinding
import eu.pkgsoftware.babybuddywidgets.databinding.DiaperLoggingEntryBinding
import eu.pkgsoftware.babybuddywidgets.databinding.FeedingLoggingEntryBinding
import eu.pkgsoftware.babybuddywidgets.databinding.GenericTimerLoggingEntryBinding
import eu.pkgsoftware.babybuddywidgets.databinding.IncludeTimerModeControlsBinding
import eu.pkgsoftware.babybuddywidgets.databinding.MeasurementLoggingEntryBinding
import eu.pkgsoftware.babybuddywidgets.databinding.MedicationLoggingEntryBinding
import eu.pkgsoftware.babybuddywidgets.databinding.NoteLoggingEntryBinding
import eu.pkgsoftware.babybuddywidgets.databinding.PumpingLoggingEntryBinding
import eu.pkgsoftware.babybuddywidgets.history.BabySummaryHelper
import eu.pkgsoftware.babybuddywidgets.history.EntryEditBottomSheet
import eu.pkgsoftware.babybuddywidgets.local.MedicationReminderScheduler
import eu.pkgsoftware.babybuddywidgets.local.ModelValidators
import eu.pkgsoftware.babybuddywidgets.local.ValidationException
import eu.pkgsoftware.babybuddywidgets.login.Utils
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient.Timer
import eu.pkgsoftware.babybuddywidgets.networking.RequestCodeFailure
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.clientToServerTime
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.maxDate
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.BmiEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.ChangeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child
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
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.classActivityName
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.nowServer
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.serverTimeToClientTime
import eu.pkgsoftware.babybuddywidgets.tags.TagSelectorHelper
import eu.pkgsoftware.babybuddywidgets.utils.AsyncPromise
import eu.pkgsoftware.babybuddywidgets.utils.AsyncPromiseFailure
import eu.pkgsoftware.babybuddywidgets.utils.ConcurrentEventBlocker
import eu.pkgsoftware.babybuddywidgets.utils.Promise
import eu.pkgsoftware.babybuddywidgets.widgets.HorizontalDecIncEditor
import eu.pkgsoftware.babybuddywidgets.widgets.SwitchButtonLogic
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlin.math.max
import kotlin.reflect.KClass

interface ButtonListCallback {
    fun onSelectionChanged(i: Int)
}

interface FragmentCallbacks {
    fun insertControls(view: View)
    fun removeControls(view: View)
    fun updateTimeline(newEntry: TimeEntry?)
}

abstract class LoggingControls(val childId: Int) {
    abstract val saveButton: View
    abstract val controlsView: View

    abstract fun storeStateForSuspend()
    abstract fun reset()
    abstract suspend fun save(): TimeEntry?

    open fun updateVisuals() {}
    open fun postInit() {}
    open fun postStart() {}
}

interface TimerBase {
    fun updateTimer(timer: Timer?)
}

private fun attachTagSelectorAboveSaveButton(saveBtn: View, tagSelector: TagSelectorHelper) {
    val parent = saveBtn.parent as? ViewGroup ?: return
    if (tagSelector.rootView.parent != null) return
    val idx = parent.indexOfChild(saveBtn).coerceAtLeast(0)
    parent.addView(tagSelector.rootView, idx)
}

private fun populateVolumePresetChips(
    context: Context,
    chipGroup: ChipGroup,
    editor: HorizontalDecIncEditor
) {
    chipGroup.removeAllViews()
    val mlLabel = context.getString(R.string.unit_ml)
    val fixedPresets = listOf(60, 90, 120, 150, 180)
    for (preset in fixedPresets) {
        val chip = Chip(context).apply {
            text = "$preset $mlLabel"
            textSize = 11.5f
            isCheckable = false
            setOnClickListener {
                editor.value = preset.toDouble()
            }
        }
        chipGroup.addView(chip)
    }
    val plus30Chip = Chip(context).apply {
        text = context.getString(R.string.logging_preset_plus_30ml)
        textSize = 11.5f
        isCheckable = false
        setOnClickListener {
            editor.value = (editor.value ?: 0.0) + 30.0
        }
    }
    chipGroup.addView(plus30Chip)
}

private fun showDateDialog(context: Context, initial: Date, onPicked: (Int, Int, Int) -> Unit) {
    val cal = Calendar.getInstance().apply { time = initial }
    DatePickerDialog(
        context,
        { _, y, m, d -> onPicked(y, m, d) },
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH),
        cal.get(Calendar.DAY_OF_MONTH)
    ).show()
}

private fun showTimeDialog(context: Context, initial: Date, onPicked: (Int, Int) -> Unit) {
    val cal = Calendar.getInstance().apply { time = initial }
    TimePickerDialog(
        context,
        { _, h, min -> onPicked(h, min) },
        cal.get(Calendar.HOUR_OF_DAY),
        cal.get(Calendar.MINUTE),
        AndroidDateFormat.is24HourFormat(context)
    ).show()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class GenericTimerRecord(
    @JsonProperty("note") val note: String,
    @JsonProperty("milestone") val milestone: String = "",
    @JsonProperty("tags") val tags: List<String> = emptyList(),
)

abstract class GenericLoggingController(
    val fragment: BaseFragment,
    childId: Int,
    val timerControl: TimerControlInterface,
    val entryKlass: KClass<*>
) : LoggingControls(childId), TimerBase, StoreFunction<TimeEntry> {
    protected abstract suspend fun createEntryRange(startServer: Date, endServer: Date): TimeEntry

    protected open suspend fun createEntry(timer: Timer): TimeEntry {
        return createEntryRange(timer.start, maxDate(timer.start, nowServer()))
    }

    val bindings = GenericTimerLoggingEntryBinding.inflate(fragment.layoutInflater)
    val typeName: String = classActivityName(entryKlass)

    open val uiIconList = bindings.icons.children
    open val uiNoteEditor = bindings.noteEditor
    open val uiCurrentTimerTime = bindings.currentTimerTime
    open val uiTimerMode: IncludeTimerModeControlsBinding
        get() = bindings.timerModeSection

    override val saveButton: View = bindings.sendButton
    override val controlsView: View = bindings.root

    val tagSelector = TagSelectorHelper(fragment.mainActivity)

    var isManualMode: Boolean = false
        private set
    protected var manualStartClient: Date = Date(System.currentTimeMillis() - 15 * 60_000L)
    protected var manualEndClient: Date = Date()
    private var suppressModeListener: Boolean = false

    var onModeChangedByUser: ((Boolean) -> Unit)? = null

    protected var timer: Timer? = null
    private var storingPromise: Promise<TimeEntry?, Exception>? = null

    override fun postInit() {
        attachTagSelectorAboveSaveButton(saveButton, tagSelector)

        fragment.mainActivity.storage.child<GenericTimerRecord>(childId, typeName)?.let {
            uiNoteEditor.setText(it.note)
            bindings.milestoneEditor.setText(it.milestone)
            tagSelector.selectedTags = it.tags
        }

        val children = uiIconList.toList()
        for (i in BabyBuddyClient.ACTIVITIES.ALL.indices) {
            if (i < children.size) {
                children[i].visibility =
                    if (BabyBuddyClient.ACTIVITIES.ALL[i] == typeName) View.VISIBLE else View.GONE
            }
        }

        setupManualModeControls()
        updateVisuals()
    }

    override fun postStart() {
        super.postStart()
        tagSelector.refreshChips()
    }

    protected open fun onActiveStartReferenceChanged(startMillis: Long) {}

    protected fun setupManualModeControls() {
        val modeUi = uiTimerMode
        modeUi.liveTimerBadge.setOnClickListener {
            val activeTimer = timer ?: return@setOnClickListener
            if (activeTimer.id <= 0) return@setOnClickListener
            val ctx = fragment.context ?: return@setOnClickListener
            val options = arrayOf(
                ctx.getString(R.string.timer_adjust_minus_5m),
                ctx.getString(R.string.timer_adjust_minus_15m),
                ctx.getString(R.string.timer_adjust_pick_time),
                ctx.getString(R.string.timer_adjust_restart_now),
            )
            AlertDialog.Builder(ctx)
                .setTitle(R.string.timer_adjust_title)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> updateRunningTimerStart(activeTimer, activeTimer.start.time - 5 * 60_000L)
                        1 -> updateRunningTimerStart(activeTimer, activeTimer.start.time - 15 * 60_000L)
                        2 -> {
                            showTimeDialog(ctx, activeTimer.start) { h, m ->
                                val cal = Calendar.getInstance().apply {
                                    time = activeTimer.start
                                    set(Calendar.HOUR_OF_DAY, h)
                                    set(Calendar.MINUTE, m)
                                    set(Calendar.SECOND, 0)
                                }
                                if (cal.timeInMillis > System.currentTimeMillis()) {
                                    cal.add(Calendar.DAY_OF_MONTH, -1)
                                }
                                updateRunningTimerStart(activeTimer, cal.timeInMillis)
                            }
                        }
                        3 -> updateRunningTimerStart(activeTimer, System.currentTimeMillis())
                    }
                }
                .show()
        }

        modeUi.modeChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressModeListener) return@setOnCheckedStateChangeListener
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val wantManual = (checkedId == R.id.chipModeManual)
            if (wantManual != isManualMode) {
                isManualMode = wantManual
                if (wantManual) {
                    val now = Date()
                    manualEndClient = now
                    manualStartClient = timer?.start?.let { serverTimeToClientTime(it) }
                        ?: Date(now.time - 15 * 60_000L)
                    if (manualStartClient.after(manualEndClient)) {
                        manualStartClient = Date(manualEndClient.time - 5 * 60_000L)
                    }
                    onActiveStartReferenceChanged(manualStartClient.time)
                }
                updateManualTimeButtons()
                updateVisuals()
                onModeChangedByUser?.invoke(wantManual)
            }
        }

        modeUi.manualDateButton.setOnClickListener {
            val ctx = fragment.context ?: return@setOnClickListener
            showDateDialog(ctx, manualStartClient) { y, m, d ->
                val startCal = Calendar.getInstance().apply {
                    time = manualStartClient
                    set(Calendar.YEAR, y)
                    set(Calendar.MONTH, m)
                    set(Calendar.DAY_OF_MONTH, d)
                }
                val durationMs = max(0L, manualEndClient.time - manualStartClient.time)
                manualStartClient = startCal.time
                manualEndClient = Date(manualStartClient.time + durationMs)
                onActiveStartReferenceChanged(manualStartClient.time)
                updateManualTimeButtons()
            }
        }

        modeUi.manualStartTimeButton.setOnClickListener {
            val ctx = fragment.context ?: return@setOnClickListener
            showTimeDialog(ctx, manualStartClient) { h, min ->
                val cal = Calendar.getInstance().apply {
                    time = manualStartClient
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, min)
                    set(Calendar.SECOND, 0)
                }
                manualStartClient = cal.time
                if (manualEndClient.before(manualStartClient)) {
                    manualEndClient = Date(manualStartClient.time + 15 * 60_000L)
                }
                onActiveStartReferenceChanged(manualStartClient.time)
                updateManualTimeButtons()
            }
        }

        modeUi.manualEndTimeButton.setOnClickListener {
            val ctx = fragment.context ?: return@setOnClickListener
            showTimeDialog(ctx, manualEndClient) { h, min ->
                val cal = Calendar.getInstance().apply {
                    time = manualStartClient
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, min)
                    set(Calendar.SECOND, 0)
                }
                if (cal.time.before(manualStartClient)) {
                    cal.add(Calendar.DAY_OF_MONTH, 1)
                }
                manualEndClient = cal.time
                updateManualTimeButtons()
            }
        }

        modeUi.chipMinus15m.setOnClickListener { applyQuickOffsetMinutes(15) }
        modeUi.chipMinus30m.setOnClickListener { applyQuickOffsetMinutes(30) }
        modeUi.chipMinus1h.setOnClickListener { applyQuickOffsetMinutes(60) }

        updateManualTimeButtons()
    }

    private fun updateRunningTimerStart(activeTimer: Timer, newStartMs: Long) {
        val safeStart = newStartMs.coerceAtMost(System.currentTimeMillis())
        fragment.mainActivity.repository.updateTimerStart(activeTimer.id, safeStart)
        activeTimer.start = Date(safeStart)
        onActiveStartReferenceChanged(safeStart)
        updateVisuals()
    }

    private fun applyQuickOffsetMinutes(minutes: Int) {
        val now = Date()
        manualEndClient = now
        manualStartClient = Date(now.time - minutes * 60_000L)
        onActiveStartReferenceChanged(manualStartClient.time)
        updateManualTimeButtons()
    }

    private fun updateManualTimeButtons() {
        val ctx = fragment.context ?: return
        val res = ctx.resources
        val dateFmt = DateFormat.getDateInstance(DateFormat.SHORT)
        val timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT)

        val modeUi = uiTimerMode
        modeUi.manualDateButton.text = dateFmt.format(manualStartClient)
        modeUi.manualStartTimeButton.text =
            "${res.getString(R.string.logging_start_label)} ${timeFmt.format(manualStartClient)}"
        modeUi.manualEndTimeButton.text =
            "${res.getString(R.string.logging_end_label)} ${timeFmt.format(manualEndClient)}"
    }

    fun switchToTimerModeSilently() {
        suppressModeListener = true
        isManualMode = false
        uiTimerMode.chipModeTimer.isChecked = true
        suppressModeListener = false
        updateVisuals()
    }

    override fun storeStateForSuspend() {
        fragment.mainActivity.storage.child(
            childId,
            typeName,
            GenericTimerRecord(
                uiNoteEditor.text.toString(),
                bindings.milestoneEditor.text.toString(),
                tagSelector.selectedTags,
            )
        )
    }

    override fun reset() {
        uiNoteEditor.setText("")
        bindings.milestoneEditor.setText("")
        tagSelector.selectedTags = emptyList()
        switchToTimerModeSilently()
        storeStateForSuspend()
    }

    override suspend fun save(): TimeEntry? {
        storingPromise?.let {
            throw IOException("Already storing activity of type $typeName")
        }

        if (isManualMode) {
            val startSrv = clientToServerTime(manualStartClient)
            val endSrv = maxDate(startSrv, clientToServerTime(manualEndClient))
            return createEntryRange(startSrv, endSrv)
        }

        timer?.let { activeTimer ->
            try {
                try {
                    return AsyncPromise.call<TimeEntry?, Exception> { promise ->
                        storingPromise = promise
                        fragment.mainActivity.storeActivity(activeTimer, this)
                    }
                } finally {
                    storingPromise = null
                }
            } catch (e: AsyncPromiseFailure) {
                fragment.showError(
                    true,
                    R.string.activity_store_failure_message,
                    R.string.activity_store_failure_server_error
                )
            }
        }
        throw IOException("Could not store activity of type $typeName")
    }

    override fun updateTimer(timer: Timer?) {
        this.timer = timer
        if (timer != null) {
            onActiveStartReferenceChanged(timer.start.time)
            if (isManualMode) {
                switchToTimerModeSilently()
            }
        }
        updateVisuals()
    }

    override fun updateVisuals() {
        val modeUi = uiTimerMode
        modeUi.manualTimeContainer.visibility = if (isManualMode) View.VISIBLE else View.GONE
        modeUi.liveTimerBadge.visibility = if (isManualMode) View.GONE else View.VISIBLE

        val now = Date()
        val startRef = timer?.start?.let { serverTimeToClientTime(it) } ?: now
        val diff = max(0L, now.time - startRef.time)
        val seconds = (diff / 1000L).toInt()
        val minutes = seconds / 60
        val hours = minutes / 60

        val formatted = "${hours}:" +
            "${Utils.padToLen("" + minutes % 60, '0', 2)}:" +
            Utils.padToLen("" + seconds % 60, '0', 2)
        uiCurrentTimerTime.text = formatted
        modeUi.liveTimerBadge.text = formatted
    }

    override fun store(
        timer: Timer,
        callback: BabyBuddyClient.RequestCallback<TimeEntry>
    ) {
        fragment.mainActivity.scope.launch {
            try {
                val result = createEntry(timer)
                timerControl.stopTimer(
                    timer,
                    object : Promise<Any, TranslatedException> {
                        override fun succeeded(s: Any?) {
                            callback.response(result)
                        }

                        override fun failed(f: TranslatedException?) {
                            callback.error(
                                f?.originalError
                                    ?: IOException("Failed to stop timer")
                            )
                        }
                    })
            } catch (e: Exception) {
                callback.error(e)
            }
        }
    }

    override fun name(): String {
        return this@GenericLoggingController.typeName
    }

    override fun stopTimer(timer: Timer) {
        timerControl.stopTimer(
            timer,
            object : Promise<Any, TranslatedException> {
                override fun succeeded(s: Any?) {
                    storingPromise!!.succeeded(null)
                }

                override fun failed(f: TranslatedException?) {
                    storingPromise!!.failed(f)
                }
            })
    }

    override fun cancel() {
        storingPromise!!.succeeded(null)
    }

    override fun error(error: java.lang.Exception) {
        var message = "" + (error.message ?: "")
        if ((error is RequestCodeFailure) && error.hasJSONMessage()) {
            message = Phrase.from(
                fragment.requireContext(),
                R.string.activity_store_failure_server_error
            )
                .put("message", fragment.getString(R.string.activity_store_failure_server_error_general))
                .put("server_message", error.jsonErrorMessages().joinToString(", "))
                .format().toString()
        }

        fragment.showQuestion(
            true,
            fragment.getString(R.string.activity_store_failure_message),
            message,
            fragment.getString(R.string.activity_store_failure_cancel),
            fragment.getString(R.string.activity_store_failure_stop_timer),
            object : DialogCallback {
                override fun call(b: Boolean) {
                    if (!b) {
                        timer?.let { stopTimer(it) }
                    } else {
                        storingPromise!!.succeeded(null)
                    }
                }
            }
        )
    }

    override fun response(response: TimeEntry?) {
        storingPromise!!.succeeded(response)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class DiaperDataRecord(
    @JsonProperty("wet") val wet: Boolean,
    @JsonProperty("solid") val solid: Boolean,
    @JsonProperty("note") val note: String,
    @JsonProperty("extra_options_open") val extraOptionsOpen: Boolean,
    @JsonProperty("color") val color: String?,
    @JsonProperty("amount") val amount: Double?,
    @JsonProperty("tags") val tags: List<String> = emptyList(),
)

class DiaperLoggingController(val fragment: BaseFragment, childId: Int) : LoggingControls(childId) {
    val bindings = DiaperLoggingEntryBinding.inflate(fragment.layoutInflater)
    override val controlsView = bindings.root
    override val saveButton = bindings.sendButton

    val tagSelector = TagSelectorHelper(fragment.mainActivity)

    val wetLogic = SwitchButtonLogic(
        bindings.wetDisabledButton, bindings.wetEnabledButton, false
    )
    val solidLogic = SwitchButtonLogic(
        bindings.solidDisabledButton, bindings.solidEnabledButton, false
    )
    val extraOptionsLogic = SwitchButtonLogic(
        bindings.openExtraOptions,
        bindings.closeExtraOptions,
        false
    )
    var diaperColor: Constants.SolidDiaperColorEnum? = null
        set(value) {
            field = value
            updateColorButtons()
        }

    private var customClientTime: Date? = null
    val noteEditor = bindings.noteEditor
    private val colorChoices: List<Constants.SolidDiaperColorEnum?> =
        Constants.SolidDiaperColorEnum.entries + listOf(null)

    init {
        attachTagSelectorAboveSaveButton(saveButton, tagSelector)

        wetLogic.addStateListener { _, _ -> updateSaveEnabledState() }
        solidLogic.addStateListener { _, _ -> updateSaveEnabledState() }
        extraOptionsLogic.addStateListener { state, _ ->
            bindings.extraOptionsList.visibility = if (state) View.VISIBLE else View.GONE
        }

        bindings.amountEditor.allowNull = true
        bindings.amountEditor.stepSize = 10.0

        bindings.diaperTimeButton.setOnClickListener {
            val ctx = fragment.context ?: return@setOnClickListener
            val base = customClientTime ?: Date()
            showTimeDialog(ctx, base) { h, m ->
                val cal = Calendar.getInstance().apply {
                    time = base
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, m)
                    set(Calendar.SECOND, 0)
                }
                if (cal.time.after(Date())) {
                    cal.add(Calendar.DAY_OF_MONTH, -1)
                }
                customClientTime = cal.time
                updateTimeButtonLabel()
            }
        }

        buildAllEightDiaperColorButtons()

        fragment.mainActivity.storage.child<DiaperDataRecord>(childId, "diaper")?.let {
            wetLogic.state = it.wet
            solidLogic.state = it.solid
            noteEditor.setText(it.note)
            extraOptionsLogic.state = it.extraOptionsOpen
            diaperColor = it.color?.let { c ->
                Constants.SolidDiaperColorEnum.byPostNameOrNull(c)
            }
            bindings.amountEditor.value = it.amount
            tagSelector.selectedTags = it.tags
        }

        updateSaveEnabledState()
        updateColorButtons()
        updateTimeButtonLabel()
    }

    private fun buildAllEightDiaperColorButtons() {
        val ctx = fragment.requireContext()
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        bindings.diaperColorGrid.removeAllViews()
        for (choice in colorChoices) {
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(2), dp(3), dp(2))
            }
            val topArrow = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
                setImageResource(android.R.drawable.arrow_down_float)
            }
            val btn = ImageButton(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
                if (choice != null) {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ContextCompat.getColor(ctx, choice.colorResId))
                        setStroke(dp(1), ContextCompat.getColor(ctx, R.color.card_stroke))
                    }
                } else {
                    setBackgroundResource(R.drawable.bg_tile_idle)
                    setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    val p = dp(6)
                    setPadding(p, p, p, p)
                }
                setOnClickListener { diaperColor = choice }
            }
            val bottomArrow = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
                setImageResource(android.R.drawable.arrow_up_float)
            }
            cell.addView(topArrow)
            cell.addView(btn)
            cell.addView(bottomArrow)
            bindings.diaperColorGrid.addView(cell)
        }
    }

    private fun updateTimeButtonLabel() {
        val ctx = fragment.context ?: return
        val label = customClientTime?.let {
            DateFormat.getTimeInstance(DateFormat.SHORT).format(it)
        } ?: ctx.getString(R.string.summary_time_just_now)
        bindings.diaperTimeButton.text = "${ctx.getString(R.string.logging_time_label)}: $label"
    }

    override fun postStart() {
        super.postStart()
        customClientTime = null
        tagSelector.refreshChips()
        updateTimeButtonLabel()
    }

    private fun updateColorButtons() {
        for (i in 0 until bindings.diaperColorGrid.childCount) {
            val ll = bindings.diaperColorGrid.getChildAt(i) as? LinearLayout ?: continue
            val choice = colorChoices.getOrNull(i)
            val isSelected = (choice == diaperColor)
            if (ll.childCount >= 3) {
                ll.getChildAt(0).visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
                ll.getChildAt(2).visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    fun updateSaveEnabledState() {
        // Baby Buddy 2.x allows saving dry/empty diapers as well as wet/solid ones
        saveButton.visibility = View.VISIBLE
        val ctx = fragment.context ?: return
        bindings.sendButton.text = if (!wetLogic.state && !solidLogic.state) {
            "${ctx.getString(R.string.logging_save_button)} (${ctx.getString(R.string.diaper_empty_label)})"
        } else {
            ctx.getString(R.string.logging_save_button)
        }
    }

    override fun storeStateForSuspend() {
        val ddr = DiaperDataRecord(
            wetLogic.state,
            solidLogic.state,
            noteEditor.text.toString(),
            extraOptionsLogic.state,
            diaperColor?.post_name,
            bindings.amountEditor.value,
            tagSelector.selectedTags,
        )
        fragment.mainActivity.storage.child(childId, "diaper", ddr)
    }

    override fun reset() {
        noteEditor.setText("")
        wetLogic.state = false
        solidLogic.state = false
        extraOptionsLogic.state = false
        diaperColor = null
        customClientTime = null
        bindings.amountEditor.value = null
        tagSelector.selectedTags = emptyList()
        updateTimeButtonLabel()
        updateSaveEnabledState()
    }

    override suspend fun save(): TimeEntry {
        val color = if (extraOptionsLogic.state) diaperColor?.post_name else null
        val amount = if (extraOptionsLogic.state) bindings.amountEditor.value else null
        val serverTimestamp = customClientTime?.let { clientToServerTime(it) } ?: nowServer()

        return fragment.mainActivity.client.v2client.createEntry(
            ChangeEntry::class,
            ChangeEntry(
                id = 0,
                childId = childId,
                start = serverTimestamp,
                _notes = noteEditor.text.toString(),
                wet = wetLogic.state,
                solid = solidLogic.state,
                color = color ?: "",
                amount = amount,
                tags = tagSelector.selectedTags,
            )
        )
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class NotesDataRecord(
    @JsonProperty("note") val note: String,
    @JsonProperty("image_path") val imagePath: String? = null,
    @JsonProperty("tags") val tags: List<String> = emptyList(),
)

class NotesLoggingController(val fragment: BaseFragment, childId: Int) : LoggingControls(childId) {
    val bindings = NoteLoggingEntryBinding.inflate(fragment.layoutInflater)
    override val controlsView = bindings.root
    override val saveButton = bindings.sendButton

    val noteEditor = bindings.noteEditor
    val tagSelector = TagSelectorHelper(fragment.mainActivity)
    private var attachedImagePath: String? = null
    private var customClientTime: Date? = null

    init {
        attachTagSelectorAboveSaveButton(saveButton, tagSelector)

        noteEditor.setOnFocusChangeListener { view, hasFocus ->
            val imm =
                view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
                    ?: return@setOnFocusChangeListener
            when (hasFocus) {
                true -> imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                false -> imm.hideSoftInputFromWindow(view.windowToken, InputMethodManager.HIDE_IMPLICIT_ONLY)
            }
        }

        noteEditor.addTextChangedListener {
            updateVisuals()
        }

        bindings.noteTimeButton.setOnClickListener {
            val ctx = fragment.context ?: return@setOnClickListener
            val base = customClientTime ?: Date()
            showTimeDialog(ctx, base) { h, m ->
                val cal = Calendar.getInstance().apply {
                    time = base
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, m)
                    set(Calendar.SECOND, 0)
                }
                if (cal.time.after(Date())) {
                    cal.add(Calendar.DAY_OF_MONTH, -1)
                }
                customClientTime = cal.time
                updateTimeLabel()
            }
        }

        bindings.notePickPhotoButton.setOnClickListener {
            fragment.mainActivity.pickPhoto { uri ->
                if (uri != null) {
                    val savedPath = fragment.mainActivity.repository.mediaStorage.importImageFromUri(uri, "note")
                    if (savedPath != null) {
                        attachedImagePath = savedPath
                        refreshPhotoPreview()
                        updateVisuals()
                    }
                }
            }
        }
        bindings.noteRemovePhotoButton.setOnClickListener {
            attachedImagePath = null
            refreshPhotoPreview()
            updateVisuals()
        }
        bindings.noteAttachedImage.setOnClickListener {
            EntryEditBottomSheet.showImagePreview(fragment.mainActivity, attachedImagePath)
        }

        fragment.mainActivity.storage.child<NotesDataRecord>(childId, "notes")?.let {
            noteEditor.setText(it.note)
            attachedImagePath = it.imagePath
            tagSelector.selectedTags = it.tags
        }
        refreshPhotoPreview()
        updateTimeLabel()
        updateVisuals()
    }

    private fun updateTimeLabel() {
        val ctx = fragment.context ?: return
        val label = customClientTime?.let {
            DateFormat.getTimeInstance(DateFormat.SHORT).format(it)
        } ?: ctx.getString(R.string.summary_time_just_now)
        bindings.noteTimeButton.text = "${ctx.getString(R.string.logging_time_label)}: $label"
    }

    private fun refreshPhotoPreview() {
        val file = fragment.mainActivity.repository.mediaStorage.resolveMediaFile(attachedImagePath)
        val bmp = file?.let { BitmapFactory.decodeFile(it.absolutePath) }
        if (bmp != null) {
            bindings.noteAttachedImage.setImageBitmap(bmp)
            bindings.noteAttachedImage.visibility = View.VISIBLE
            bindings.noteRemovePhotoButton.visibility = View.VISIBLE
        } else {
            bindings.noteAttachedImage.visibility = View.GONE
            bindings.noteRemovePhotoButton.visibility = View.GONE
        }
    }

    override fun storeStateForSuspend() {
        val ddr = NotesDataRecord(
            noteEditor.text.toString(),
            attachedImagePath,
            tagSelector.selectedTags,
        )
        fragment.mainActivity.storage.child(childId, "notes", ddr)
    }

    override fun reset() {
        noteEditor.setText("")
        attachedImagePath = null
        customClientTime = null
        tagSelector.selectedTags = emptyList()
        refreshPhotoPreview()
        updateTimeLabel()
        updateVisuals()
    }

    override suspend fun save(): TimeEntry {
        val ts = customClientTime?.let { clientToServerTime(it) } ?: nowServer()
        return fragment.mainActivity.client.v2client.createEntry(
            NoteEntry::class,
            NoteEntry(
                id = 0,
                childId = childId,
                start = ts,
                _notes = noteEditor.text.toString(),
                imagePath = attachedImagePath,
                tags = tagSelector.selectedTags,
            )
        )
    }

    override fun updateVisuals() {
        super.updateVisuals()
        val hasContent = noteEditor.text.isNotBlank() || !attachedImagePath.isNullOrBlank()
        saveButton.visibility = if (hasContent) View.VISIBLE else View.GONE
    }

    override fun postStart() {
        super.postStart()
        customClientTime = null
        tagSelector.refreshChips()
        updateTimeLabel()
        noteEditor.requestFocus()
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class MedicationDataRecord(
    @JsonProperty("name") val name: String = "",
    @JsonProperty("dosage") val dosage: Double? = null,
    @JsonProperty("unit") val unit: String = "ml",
    @JsonProperty("interval_minutes") val intervalMinutes: Int? = null,
    @JsonProperty("note") val note: String = "",
    @JsonProperty("tags") val tags: List<String> = emptyList(),
)

class MedicationLoggingController(
    val fragment: BaseFragment,
    childId: Int,
) : LoggingControls(childId) {
    val bindings = MedicationLoggingEntryBinding.inflate(fragment.layoutInflater)
    override val controlsView: View = bindings.root
    override val saveButton: View = bindings.sendButton

    val tagSelector = TagSelectorHelper(fragment.mainActivity)
    private var selectedClientDateTime: Date = Date()
    private var selectedUnit: MedicationDosageUnitEnum = MedicationDosageUnitEnum.ML
    private var selectedIntervalMinutes: Int? = null

    override fun postInit() {
        attachTagSelectorAboveSaveButton(saveButton, tagSelector)
        val ctx = fragment.requireContext()
        val res = ctx.resources

        bindings.medicationDosageEditor.allowNull = true
        bindings.medicationDosageEditor.stepSize = 0.5
        bindings.medicationDosageEditor.defaultBaseline = 5.0
        bindings.medicationDosageEditor.binding.numberEditor.addTextChangedListener {
            updateSaveVisibility()
        }
        bindings.medicationNameEditor.addTextChangedListener {
            updateSaveVisibility()
        }

        bindings.medicationDateButton.setOnClickListener {
            val c = fragment.context ?: return@setOnClickListener
            showDateDialog(c, selectedClientDateTime) { y, m, d ->
                val cal = Calendar.getInstance().apply {
                    time = selectedClientDateTime
                    set(Calendar.YEAR, y)
                    set(Calendar.MONTH, m)
                    set(Calendar.DAY_OF_MONTH, d)
                }
                selectedClientDateTime = cal.time
                updateDateTimeLabels()
            }
        }
        bindings.medicationTimeButton.setOnClickListener {
            val c = fragment.context ?: return@setOnClickListener
            showTimeDialog(c, selectedClientDateTime) { h, min ->
                val cal = Calendar.getInstance().apply {
                    time = selectedClientDateTime
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, min)
                    set(Calendar.SECOND, 0)
                }
                selectedClientDateTime = cal.time
                updateDateTimeLabels()
            }
        }

        fragment.mainActivity.storage.child<MedicationDataRecord>(childId, "medication")?.let {
            bindings.medicationNameEditor.setText(it.name)
            bindings.medicationDosageEditor.value = it.dosage
            selectedUnit = MedicationDosageUnitEnum.byPostNameOrNull(it.unit) ?: MedicationDosageUnitEnum.ML
            selectedIntervalMinutes = it.intervalMinutes
            bindings.noteEditor.setText(it.note)
            tagSelector.selectedTags = it.tags
        }

        populateUnitAndIntervalChips(res)
        refreshRecentMedicationNames()
        updateDateTimeLabels()
        updateSaveVisibility()
    }

    private fun populateUnitAndIntervalChips(res: android.content.res.Resources) {
        val ctx = fragment.requireContext()
        bindings.medicationUnitChips.removeAllViews()
        val unitLabels = mapOf(
            MedicationDosageUnitEnum.ML to res.getString(R.string.medication_unit_ml),
            MedicationDosageUnitEnum.MG to res.getString(R.string.medication_unit_mg),
            MedicationDosageUnitEnum.DROPS to res.getString(R.string.medication_unit_drops),
            MedicationDosageUnitEnum.TABLETS to res.getString(R.string.medication_unit_tablets),
        )
        for (u in MedicationDosageUnitEnum.entries) {
            val chip = Chip(ctx).apply {
                id = View.generateViewId()
                text = unitLabels[u] ?: u.post_name
                textSize = 11.5f
                isCheckable = true
                isChecked = (u == selectedUnit)
                setOnClickListener {
                    selectedUnit = u
                    bindings.medicationDosageEditor.unitSuffix = unitLabels[u] ?: u.post_name
                }
            }
            bindings.medicationUnitChips.addView(chip)
        }
        bindings.medicationDosageEditor.unitSuffix = unitLabels[selectedUnit] ?: selectedUnit.post_name

        bindings.medicationIntervalChips.removeAllViews()
        val intervals = listOf<Pair<Int?, String>>(
            null to res.getString(R.string.common_none),
            240 to "4h",
            360 to "6h",
            480 to "8h",
            720 to "12h",
            1440 to "24h",
        )
        for ((mins, label) in intervals) {
            val chip = Chip(ctx).apply {
                id = View.generateViewId()
                text = label
                textSize = 11.5f
                isCheckable = true
                isChecked = (selectedIntervalMinutes == mins)
                setOnClickListener { selectedIntervalMinutes = mins }
            }
            bindings.medicationIntervalChips.addView(chip)
        }
    }

    private fun refreshRecentMedicationNames() {
        val ctx = fragment.context ?: return
        bindings.medicationRecentNamesGroup.removeAllViews()
        val names = fragment.mainActivity.repository.getRecentMedicationNames(childId)
        if (names.isEmpty()) {
            bindings.medicationRecentNamesScroll.visibility = View.GONE
            return
        }
        for (n in names) {
            bindings.medicationRecentNamesGroup.addView(
                Chip(ctx).apply {
                    text = n
                    textSize = 11.5f
                    isCheckable = false
                    setOnClickListener {
                        bindings.medicationNameEditor.setText(n)
                        updateSaveVisibility()
                    }
                }
            )
        }
        bindings.medicationRecentNamesScroll.visibility = View.VISIBLE
    }

    override fun postStart() {
        super.postStart()
        selectedClientDateTime = Date()
        tagSelector.refreshChips()
        refreshRecentMedicationNames()
        updateDateTimeLabels()
        updateSaveVisibility()
    }

    private fun updateDateTimeLabels() {
        bindings.medicationDateButton.text =
            DateFormat.getDateInstance(DateFormat.SHORT).format(selectedClientDateTime)
        bindings.medicationTimeButton.text =
            DateFormat.getTimeInstance(DateFormat.SHORT).format(selectedClientDateTime)
    }

    private fun updateSaveVisibility() {
        val hasName = bindings.medicationNameEditor.text.toString().trim().isNotEmpty()
        val dose = bindings.medicationDosageEditor.value
        saveButton.visibility = if (hasName && dose != null && dose > 0.0) View.VISIBLE else View.GONE
    }

    override fun storeStateForSuspend() {
        fragment.mainActivity.storage.child(
            childId,
            "medication",
            MedicationDataRecord(
                name = bindings.medicationNameEditor.text.toString(),
                dosage = bindings.medicationDosageEditor.value,
                unit = selectedUnit.post_name,
                intervalMinutes = selectedIntervalMinutes,
                note = bindings.noteEditor.text.toString(),
                tags = tagSelector.selectedTags,
            )
        )
    }

    override fun reset() {
        bindings.medicationNameEditor.setText("")
        bindings.medicationDosageEditor.value = null
        bindings.noteEditor.setText("")
        selectedIntervalMinutes = null
        selectedClientDateTime = Date()
        tagSelector.selectedTags = emptyList()
        fragment.context?.resources?.let { populateUnitAndIntervalChips(it) }
        updateDateTimeLabels()
        updateSaveVisibility()
        storeStateForSuspend()
    }

    override suspend fun save(): TimeEntry {
        val name = bindings.medicationNameEditor.text.toString().trim()
        if (name.isEmpty()) {
            throw ValidationException(fragment.getString(R.string.medication_error_name_required))
        }
        val dosage = bindings.medicationDosageEditor.value
            ?: throw ValidationException(fragment.getString(R.string.medication_error_dosage_positive))
        val created = fragment.mainActivity.client.v2client.createEntry(
            MedicationEntry::class,
            MedicationEntry(
                id = 0,
                childId = childId,
                start = clientToServerTime(selectedClientDateTime),
                name = name,
                dosage = dosage,
                dosageUnit = selectedUnit,
                nextDoseIntervalMinutes = selectedIntervalMinutes,
                _notes = bindings.noteEditor.text.toString(),
                tags = tagSelector.selectedTags,
            )
        )
        val childName = fragment.mainActivity.repository.getChildById(childId)?.fullName ?: ""
        MedicationReminderScheduler.scheduleIfNeeded(fragment.mainActivity, created, childName)
        return created
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class MeasurementDataRecord(
    @JsonProperty("value") val value: Double?,
    @JsonProperty("note") val note: String,
    @JsonProperty("tags") val tags: List<String> = emptyList(),
)

class MeasurementLoggingController(
    val fragment: BaseFragment,
    childId: Int,
    val eventType: String,
) : LoggingControls(childId) {
    val bindings = MeasurementLoggingEntryBinding.inflate(fragment.layoutInflater)
    override val controlsView: View = bindings.root
    override val saveButton: View = bindings.sendButton

    val tagSelector = TagSelectorHelper(fragment.mainActivity)
    private val hasTimeComponent: Boolean = (eventType == BabyBuddyClient.EVENTS.TEMPERATURE)
    private var selectedClientDateTime: Date = Date()
    private var currentPresets: List<Double> = emptyList()
    private var currentUnit: String = ""

    override fun postInit() {
        attachTagSelectorAboveSaveButton(saveButton, tagSelector)
        val ctx = fragment.requireContext()
        val res = ctx.resources

        data class VisualSpec(
            val iconRes: Int,
            val badgeBgRes: Int,
            val accentRes: Int,
            val unit: String,
            val step: Double,
            val baseline: Double,
            val presets: List<Double>
        )

        val spec = when (eventType) {
            BabyBuddyClient.EVENTS.TEMPERATURE -> VisualSpec(
                R.drawable.ic_temperature,
                R.color.temperature_enabled,
                R.color.temperature_accent,
                res.getString(R.string.unit_celsius),
                0.1,
                36.8,
                listOf(36.5, 36.8, 37.2, 37.5, 38.0, 38.5)
            )
            BabyBuddyClient.EVENTS.WEIGHT -> VisualSpec(
                R.drawable.ic_weight,
                R.color.weight_enabled,
                R.color.weight_accent,
                res.getString(R.string.unit_kg),
                0.1,
                5.0,
                listOf(3.5, 5.0, 6.5, 8.0, 10.0, 12.0)
            )
            BabyBuddyClient.EVENTS.HEIGHT -> VisualSpec(
                R.drawable.ic_height,
                R.color.height_enabled,
                R.color.height_accent,
                res.getString(R.string.unit_cm),
                0.5,
                60.0,
                listOf(50.0, 55.0, 60.0, 65.0, 70.0, 75.0)
            )
            BabyBuddyClient.EVENTS.HEAD_CIRCUMFERENCE -> VisualSpec(
                R.drawable.ic_head_circumference,
                R.color.head_circumference_enabled,
                R.color.head_circumference_accent,
                res.getString(R.string.unit_cm),
                0.5,
                40.0,
                listOf(34.0, 36.0, 38.0, 40.0, 42.0, 44.0)
            )
            else -> VisualSpec(
                R.drawable.ic_bmi,
                R.color.bmi_enabled,
                R.color.bmi_accent,
                "",
                0.1,
                15.0,
                listOf(13.0, 14.0, 15.0, 16.0, 17.0)
            )
        }
        currentPresets = spec.presets
        currentUnit = spec.unit

        bindings.measurementCard.strokeColor = ContextCompat.getColor(ctx, spec.accentRes)
        bindings.measurementIconBadge.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(ctx, spec.badgeBgRes))
        bindings.measurementIconImage.setImageResource(spec.iconRes)

        val actName = fragment.translateActivityName(eventType)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        bindings.measurementTitle.text = if (spec.unit.isNotEmpty()) "$actName (${spec.unit})" else actName

        bindings.measurementValueEditor.allowNull = true
        bindings.measurementValueEditor.stepSize = spec.step
        bindings.measurementValueEditor.defaultBaseline = spec.baseline
        bindings.measurementValueEditor.unitSuffix = spec.unit
        bindings.measurementValueEditor.binding.numberEditor.addTextChangedListener {
            updateSaveVisibility()
        }

        rebuildMeasurementPresetChips()

        bindings.measurementTimeButton.visibility = if (hasTimeComponent) View.VISIBLE else View.GONE
        bindings.measurementDateButton.setOnClickListener {
            val c = fragment.context ?: return@setOnClickListener
            showDateDialog(c, selectedClientDateTime) { y, m, d ->
                val cal = Calendar.getInstance().apply {
                    time = selectedClientDateTime
                    set(Calendar.YEAR, y)
                    set(Calendar.MONTH, m)
                    set(Calendar.DAY_OF_MONTH, d)
                }
                selectedClientDateTime = cal.time
                updateDateTimeLabels()
            }
        }
        bindings.measurementTimeButton.setOnClickListener {
            val c = fragment.context ?: return@setOnClickListener
            showTimeDialog(c, selectedClientDateTime) { h, min ->
                val cal = Calendar.getInstance().apply {
                    time = selectedClientDateTime
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, min)
                    set(Calendar.SECOND, 0)
                }
                selectedClientDateTime = cal.time
                updateDateTimeLabels()
            }
        }

        fragment.mainActivity.storage.child<MeasurementDataRecord>(childId, "measure_$eventType")?.let {
            bindings.measurementValueEditor.value = it.value
            bindings.noteEditor.setText(it.note)
            tagSelector.selectedTags = it.tags
        }

        selectedClientDateTime = Date()
        updateDateTimeLabels()
        updateSaveVisibility()
    }

    private fun rebuildMeasurementPresetChips() {
        val ctx = fragment.context ?: return
        bindings.measurementPresetsGroup.removeAllViews()
        if (eventType == BabyBuddyClient.EVENTS.BMI) {
            fragment.mainActivity.repository.computeSuggestedBmi(childId)?.let { suggested ->
                val autoChip = Chip(ctx).apply {
                    text = Phrase.from(ctx, R.string.bmi_auto_calc_chip)
                        .put("bmi", BabySummaryHelper.formatCleanNumber(suggested.bmi))
                        .format()
                        .toString()
                    textSize = 11.5f
                    isCheckable = false
                    setOnClickListener {
                        bindings.measurementValueEditor.value = suggested.bmi
                        updateSaveVisibility()
                    }
                }
                bindings.measurementPresetsGroup.addView(autoChip)
            }
        }
        for (p in currentPresets) {
            val chip = Chip(ctx).apply {
                val u = if (currentUnit.isNotEmpty()) " $currentUnit" else ""
                text = "${HorizontalDecIncEditor.formatClean(p)}$u"
                textSize = 11.5f
                isCheckable = false
                setOnClickListener {
                    bindings.measurementValueEditor.value = p
                    updateSaveVisibility()
                }
            }
            bindings.measurementPresetsGroup.addView(chip)
        }
    }

    override fun postStart() {
        super.postStart()
        selectedClientDateTime = Date()
        tagSelector.refreshChips()
        rebuildMeasurementPresetChips()
        updateDateTimeLabels()
        updateSaveVisibility()
    }

    private fun updateDateTimeLabels() {
        bindings.measurementDateButton.text =
            DateFormat.getDateInstance(DateFormat.SHORT).format(selectedClientDateTime)
        if (hasTimeComponent) {
            bindings.measurementTimeButton.text =
                DateFormat.getTimeInstance(DateFormat.SHORT).format(selectedClientDateTime)
        }
    }

    private fun updateSaveVisibility() {
        val v = bindings.measurementValueEditor.value
        saveButton.visibility = if (v != null && v > 0.0) View.VISIBLE else View.GONE
    }

    override fun storeStateForSuspend() {
        fragment.mainActivity.storage.child(
            childId,
            "measure_$eventType",
            MeasurementDataRecord(
                bindings.measurementValueEditor.value,
                bindings.noteEditor.text.toString(),
                tagSelector.selectedTags,
            )
        )
    }

    override fun reset() {
        bindings.measurementValueEditor.value = null
        bindings.noteEditor.setText("")
        tagSelector.selectedTags = emptyList()
        selectedClientDateTime = Date()
        updateDateTimeLabels()
        updateSaveVisibility()
        storeStateForSuspend()
    }

    override suspend fun save(): TimeEntry {
        val v = bindings.measurementValueEditor.value
            ?: throw IOException("Measurement value is empty")
        val note = bindings.noteEditor.text.toString()
        val chosenTags = tagSelector.selectedTags
        val client = fragment.mainActivity.client.v2client

        val normalizedLocalNoon = Calendar.getInstance().apply {
            time = selectedClientDateTime
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }.time

        return when (eventType) {
            BabyBuddyClient.EVENTS.TEMPERATURE -> client.createEntry(
                TemperatureEntry::class,
                TemperatureEntry(
                    id = 0,
                    childId = childId,
                    start = clientToServerTime(selectedClientDateTime),
                    _notes = note,
                    temperature = v,
                    tags = chosenTags,
                )
            )
            BabyBuddyClient.EVENTS.WEIGHT -> client.createEntry(
                WeightEntry::class,
                WeightEntry(
                    id = 0,
                    childId = childId,
                    start = normalizedLocalNoon,
                    _notes = note,
                    weight = v,
                    tags = chosenTags,
                )
            )
            BabyBuddyClient.EVENTS.HEIGHT -> client.createEntry(
                HeightEntry::class,
                HeightEntry(
                    id = 0,
                    childId = childId,
                    start = normalizedLocalNoon,
                    _notes = note,
                    height = v,
                    tags = chosenTags,
                )
            )
            BabyBuddyClient.EVENTS.HEAD_CIRCUMFERENCE -> client.createEntry(
                HeadCircumferenceEntry::class,
                HeadCircumferenceEntry(
                    id = 0,
                    childId = childId,
                    start = normalizedLocalNoon,
                    _notes = note,
                    head_circumference = v,
                    tags = chosenTags,
                )
            )
            BabyBuddyClient.EVENTS.BMI -> client.createEntry(
                BmiEntry::class,
                BmiEntry(
                    id = 0,
                    childId = childId,
                    start = normalizedLocalNoon,
                    _notes = note,
                    bmi = v,
                    tags = chosenTags,
                )
            )
            else -> throw IOException("Unsupported measurement type: $eventType")
        }
    }
}

class SleepLoggingController(
    fragment: BaseFragment,
    childId: Int,
    timerControl: TimerControlInterface
) : GenericLoggingController(fragment, childId, timerControl, SleepEntry::class) {
    private var userOverrodeNap = false

    override fun postInit() {
        super.postInit()
        bindings.sleepNapChip.visibility = View.VISIBLE
        syncAutoNap(System.currentTimeMillis())
        bindings.sleepNapChip.setOnClickListener {
            userOverrodeNap = true
        }
    }

    override fun onActiveStartReferenceChanged(startMillis: Long) {
        if (!userOverrodeNap) {
            syncAutoNap(startMillis)
        }
    }

    private fun syncAutoNap(startMillis: Long) {
        val settings = fragment.mainActivity.standaloneSettings
        bindings.sleepNapChip.isChecked = ModelValidators.resolveAutoNap(
            startMillis,
            settings.napStartMin,
            settings.napStartMax
        )
    }

    override fun reset() {
        userOverrodeNap = false
        syncAutoNap(System.currentTimeMillis())
        super.reset()
    }

    override suspend fun createEntryRange(startServer: Date, endServer: Date): TimeEntry {
        return fragment.mainActivity.client.v2client.createEntry(
            SleepEntry::class,
            SleepEntry(
                id = 0,
                childId = childId,
                start = startServer,
                end = endServer,
                _notes = bindings.noteEditor.text.toString(),
                nap = bindings.sleepNapChip.isChecked,
                tags = tagSelector.selectedTags,
            )
        )
    }
}

class TummyTimeLoggingController(
    fragment: BaseFragment,
    childId: Int,
    timerControl: TimerControlInterface
) : GenericLoggingController(fragment, childId, timerControl, TummyTimeEntry::class) {
    override fun postInit() {
        super.postInit()
        bindings.milestoneEditor.visibility = View.VISIBLE
    }

    override suspend fun createEntryRange(startServer: Date, endServer: Date): TimeEntry {
        return fragment.mainActivity.client.v2client.createEntry(
            TummyTimeEntry::class,
            TummyTimeEntry(
                id = 0,
                childId = childId,
                start = startServer,
                end = endServer,
                _notes = bindings.milestoneEditor.text.toString(),
                extraNotes = bindings.noteEditor.text.toString(),
                tags = tagSelector.selectedTags,
            )
        )
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class FeedingRecord(
    @JsonProperty("amount") val amount: Double?,
    @JsonProperty("note") val note: String,
    @JsonProperty("feeding_type") val feedingType: FeedingTypeEnum?,
    @JsonProperty("feeding_method") val feedingMethod: FeedingMethodEnum?,
    @JsonProperty("tags") val tags: List<String> = emptyList(),
)

class FeedingLoggingController(
    fragment: BaseFragment,
    childId: Int,
    timerControl: TimerControlInterface
) : GenericLoggingController(fragment, childId, timerControl, FeedingEntry::class) {
    val feedingBinding = FeedingLoggingEntryBinding.inflate(fragment.layoutInflater)

    override val uiCurrentTimerTime = feedingBinding.currentTimerTime
    override val uiNoteEditor = feedingBinding.noteEditor
    override val uiTimerMode: IncludeTimerModeControlsBinding
        get() = feedingBinding.timerModeSection
    override val saveButton: View = feedingBinding.sendButton
    override val controlsView: View = feedingBinding.root

    private var assignedMethodButtons: List<FeedingMethodEnum> = emptyList()
    private var selectedType: FeedingTypeEnum? = null
    private var selectedMethod: FeedingMethodEnum? = null

    override fun postInit() {
        super.postInit()

        feedingBinding.amountNumberPicker.stepSize = 10.0
        feedingBinding.amountNumberPicker.unitSuffix = fragment.getString(R.string.unit_ml)
        populateVolumePresetChips(
            fragment.requireContext(),
            feedingBinding.feedingAmountPresets,
            feedingBinding.amountNumberPicker
        )

        populateButtonList(
            textArray = fragment.resources.getTextArray(R.array.feedingTypes),
            buttons = feedingBinding.feedingTypeButtons,
            spinner = feedingBinding.feedingTypeSpinner,
            callback = object : ButtonListCallback {
                override fun onSelectionChanged(i: Int) {
                    selectedType = FeedingTypeEnumValues[i]!!
                    selectedMethod = null
                    setupFeedingMethodButtons(FeedingTypeEnumValues[i]!!)
                    feedingBinding.feedingMethodButtons.visibility = View.VISIBLE
                    feedingBinding.feedingMethodSpinner.visibility = View.GONE
                    updateVisuals()
                }
            }
        )
        feedingBinding.feedingTypeSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val newType = FeedingTypeEnumValues[position]!!
                    if (newType == selectedType) return
                    selectedType = newType
                    selectedMethod = null
                    setupFeedingMethodButtons(FeedingTypeEnumValues[position]!!)
                    feedingBinding.feedingMethodButtons.visibility = View.VISIBLE
                    feedingBinding.feedingMethodSpinner.visibility = View.GONE
                    updateVisuals()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    feedingBinding.feedingMethodSpinner.visibility = View.GONE
                    feedingBinding.feedingMethodButtons.visibility = View.GONE
                }
            }

        feedingBinding.amountNumberPicker.value = null

        fragment.mainActivity.storage.child<FeedingRecord>(childId, "feeding")?.let {
            feedingBinding.amountNumberPicker.value = it.amount
            feedingBinding.noteEditor.setText(it.note)
            tagSelector.selectedTags = it.tags

            selectedType = null
            selectedMethod = null

            it.feedingType?.let { ft ->
                try {
                    feedingBinding.feedingTypeSpinner.setSelection(ft.value)
                    selectedType = ft
                    feedingBinding.feedingTypeButtons.visibility = View.GONE
                    feedingBinding.feedingTypeSpinner.visibility = View.VISIBLE
                    setupFeedingMethodButtons(ft)
                } catch (_: NoSuchElementException) {
                }
            }
            it.feedingMethod?.let { fm ->
                if (selectedType != null) {
                    try {
                        val idx = assignedMethodButtons.indexOf(fm)
                        if (idx >= 0) {
                            feedingBinding.feedingMethodSpinner.setSelection(idx)
                            selectedMethod = fm
                            feedingBinding.feedingMethodButtons.visibility = View.GONE
                            feedingBinding.feedingMethodSpinner.visibility = View.VISIBLE
                        }
                    } catch (_: NoSuchElementException) {
                    }
                }
            }
        }

        updateVisuals()
    }

    override fun storeStateForSuspend() {
        val fr = FeedingRecord(
            feedingBinding.amountNumberPicker.value,
            feedingBinding.noteEditor.text.toString(),
            selectedType,
            selectedMethod,
            tagSelector.selectedTags,
        )
        fragment.mainActivity.storage.child(childId, "feeding", fr)
    }

    override fun reset() {
        feedingBinding.amountNumberPicker.value = null
        feedingBinding.noteEditor.setText("")
        selectedType = null
        selectedMethod = null
        tagSelector.selectedTags = emptyList()
        switchToTimerModeSilently()
        storeStateForSuspend()
    }

    override fun updateVisuals() {
        super.updateVisuals()

        val currentType = selectedType
        if (currentType == null) {
            feedingBinding.feedingTypeButtons.visibility = View.VISIBLE
            feedingBinding.feedingTypeSpinner.visibility = View.GONE
            feedingBinding.feedingMethodButtons.visibility = View.GONE
            feedingBinding.feedingMethodSpinner.visibility = View.GONE
        } else if (selectedMethod == null) {
            setupFeedingMethodButtons(currentType)
            feedingBinding.feedingTypeButtons.visibility = View.GONE
            feedingBinding.feedingTypeSpinner.visibility = View.VISIBLE
            feedingBinding.feedingMethodButtons.visibility = View.VISIBLE
            feedingBinding.feedingMethodSpinner.visibility = View.GONE
        } else {
            feedingBinding.feedingTypeButtons.visibility = View.GONE
            feedingBinding.feedingTypeSpinner.visibility = View.VISIBLE
            feedingBinding.feedingMethodButtons.visibility = View.GONE
            feedingBinding.feedingMethodSpinner.visibility = View.VISIBLE
        }
        feedingBinding.feedingIconImage.setImageResource(
            feedingImageResourceFor(currentType, selectedMethod)
        )

        if (feedingBinding.feedingTypeSpinner.isVisible && feedingBinding.feedingMethodSpinner.isVisible) {
            saveButton.visibility = View.VISIBLE
        } else {
            saveButton.visibility = View.GONE
        }
    }

    fun feedingImageResourceFor(
        selectedType: FeedingTypeEnum?,
        selectedMethod: FeedingMethodEnum?,
    ) = when (selectedType) {
        FeedingTypeEnum.BREAST_MILK -> {
            when (selectedMethod) {
                null -> R.drawable.pkg_breast
                FeedingMethodEnum.LEFT_BREAST -> R.drawable.pkg_breast_left
                FeedingMethodEnum.RIGHT_BREAST -> R.drawable.pkg_breast_right
                FeedingMethodEnum.BOTH_BREASTS -> R.drawable.pkg_breast
                FeedingMethodEnum.BOTTLE -> R.drawable.pkg_bottle
                FeedingMethodEnum.PARENT_FED -> R.drawable.pkg_bottle
                FeedingMethodEnum.SELF_FED -> R.drawable.pkg_bottle
            }
        }
        FeedingTypeEnum.SOLID_FOOD -> R.drawable.pkg_solid_food
        FeedingTypeEnum.FORMULA -> R.drawable.pkg_bottle
        FeedingTypeEnum.FORTIFIED_BREAST_MILK -> R.drawable.pkg_bottle
        null -> R.drawable.pkg_bottle
    }

    override suspend fun createEntryRange(startServer: Date, endServer: Date): TimeEntry {
        return fragment.mainActivity.client.v2client.createEntry(
            FeedingEntry::class,
            FeedingEntry(
                id = 0,
                childId = childId,
                start = startServer,
                end = endServer,
                feedingType = selectedType!!,
                feedingMethod = selectedMethod!!,
                amount = feedingBinding.amountNumberPicker.value,
                _notes = feedingBinding.noteEditor.text.toString(),
                tags = tagSelector.selectedTags,
            )
        )
    }

    private fun populateButtonList(
        textArray: Array<CharSequence>,
        buttons: ViewGroup,
        spinner: Spinner,
        callback: ButtonListCallback
    ) {
        val ctx = fragment.requireContext()
        val marginPx = (3 * ctx.resources.displayMetrics.density).toInt()
        spinner.visibility = View.GONE
        buttons.visibility = View.VISIBLE
        buttons.removeAllViewsInLayout()
        for (i in textArray.indices) {
            val button = MaterialButton(
                ctx,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = textArray[i]
                isAllCaps = false
                textSize = 12.5f
                cornerRadius = (10 * ctx.resources.displayMetrics.density).toInt()
                setOnClickListener {
                    spinner.setSelection(i)
                    spinner.visibility = View.VISIBLE
                    buttons.visibility = View.GONE
                    callback.onSelectionChanged(i)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    setMargins(marginPx, marginPx / 2, marginPx, marginPx / 2)
                }
            }
            buttons.addView(button)
        }
    }

    private fun setupFeedingMethodButtons(type: FeedingTypeEnum) {
        assignedMethodButtons = when (type) {
            FeedingTypeEnum.BREAST_MILK -> listOf(
                FeedingMethodEnum.LEFT_BREAST,
                FeedingMethodEnum.RIGHT_BREAST,
                FeedingMethodEnum.BOTH_BREASTS,
                FeedingMethodEnum.BOTTLE,
                FeedingMethodEnum.PARENT_FED,
                FeedingMethodEnum.SELF_FED,
            )
            else -> listOf(
                FeedingMethodEnum.BOTTLE,
                FeedingMethodEnum.PARENT_FED,
                FeedingMethodEnum.SELF_FED,
            )
        }

        val orgItems: Array<CharSequence> = fragment.resources.getTextArray(R.array.feedingMethods)
        val textItems: MutableList<CharSequence> = ArrayList(10)
        for (i in assignedMethodButtons.indices) {
            textItems.add(orgItems[assignedMethodButtons[i].value])
        }
        feedingBinding.feedingMethodSpinner.adapter = ArrayAdapter(
            fragment.requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            textItems
        )
        populateButtonList(
            textItems.toTypedArray(),
            feedingBinding.feedingMethodButtons,
            feedingBinding.feedingMethodSpinner,
            object : ButtonListCallback {
                override fun onSelectionChanged(i: Int) {
                    selectedMethod = assignedMethodButtons[i]
                    updateVisuals()
                }
            }
        )
        feedingBinding.feedingMethodSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    selectedMethod = assignedMethodButtons[position]
                    updateVisuals()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    selectedMethod = null
                    updateVisuals()
                }
            }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class PumpingRecord(
    @JsonProperty("amount") val amount: Double,
    @JsonProperty("note") val note: String,
    @JsonProperty("tags") val tags: List<String> = emptyList(),
)

class PumpingLoggingController(
    fragment: BaseFragment,
    childId: Int,
    timerControl: TimerControlInterface
) : GenericLoggingController(fragment, childId, timerControl, PumpingEntry::class) {
    val pumpingBinding = PumpingLoggingEntryBinding.inflate(fragment.layoutInflater)

    override val uiCurrentTimerTime = pumpingBinding.currentTimerTime
    override val uiNoteEditor = pumpingBinding.noteEditor
    override val uiTimerMode: IncludeTimerModeControlsBinding
        get() = pumpingBinding.timerModeSection
    override val saveButton: View = pumpingBinding.sendButton
    override val controlsView: View = pumpingBinding.root

    val amountNumberPicker: HorizontalDecIncEditor = pumpingBinding.amountNumberPicker

    override fun postInit() {
        super.postInit()

        amountNumberPicker.allowNull = false
        amountNumberPicker.stepSize = 10.0
        amountNumberPicker.unitSuffix = fragment.getString(R.string.unit_ml)
        amountNumberPicker.value = 0.0

        populateVolumePresetChips(
            fragment.requireContext(),
            pumpingBinding.pumpingAmountPresets,
            amountNumberPicker
        )

        fragment.mainActivity.storage.child<PumpingRecord>(childId, "pumping")?.let {
            amountNumberPicker.value = it.amount
            uiNoteEditor.setText(it.note)
            tagSelector.selectedTags = it.tags
        }

        updateVisuals()
    }

    override fun storeStateForSuspend() {
        val pr = PumpingRecord(
            pumpingBinding.amountNumberPicker.value ?: 0.0,
            pumpingBinding.noteEditor.text.toString(),
            tagSelector.selectedTags,
        )
        fragment.mainActivity.storage.child(childId, "pumping", pr)
    }

    override fun reset() {
        pumpingBinding.amountNumberPicker.value = null
        pumpingBinding.noteEditor.setText("")
        tagSelector.selectedTags = emptyList()
        switchToTimerModeSilently()
        storeStateForSuspend()
    }

    override suspend fun createEntryRange(startServer: Date, endServer: Date): TimeEntry {
        return fragment.mainActivity.client.v2client.createEntry(
            PumpingEntry::class,
            PumpingEntry(
                id = 0,
                childId = childId,
                _start = startServer,
                _end = endServer,
                amount = amountNumberPicker.value ?: 0.0,
                _notes = uiNoteEditor.text.toString(),
                _legacyTime = startServer,
                tags = tagSelector.selectedTags,
            )
        )
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LoggingButtonControllerStoreState(
    @JsonProperty("open_state") val openState: Array<String>,
)

class LoggingButtonController(
    val fragment: BaseFragment,
    val bindings: BabyManagerBinding,
    val controlsInterface: FragmentCallbacks,
    val child: Child,
    val timerControl: TimerControlInterface,
) : TimersUpdatedCallback {
    val logicMap = mapOf(
        BabyBuddyClient.EVENTS.CHANGE to SwitchButtonLogic(
            bindings.diaperDisabledButton, bindings.diaperEnabledButton, false
        ),
        BabyBuddyClient.EVENTS.NOTE to SwitchButtonLogic(
            bindings.notesDisabledButton, bindings.notesEnabledButton, false
        ),
        BabyBuddyClient.ACTIVITIES.SLEEP to SwitchButtonLogic(
            bindings.sleepDisabledButton, bindings.sleepEnabledButton, false
        ),
        BabyBuddyClient.ACTIVITIES.FEEDING to SwitchButtonLogic(
            bindings.feedingDisabledButton, bindings.feedingEnabledButton, false
        ),
        BabyBuddyClient.ACTIVITIES.TUMMY_TIME to SwitchButtonLogic(
            bindings.tummyTimeDisabledButton, bindings.tummyTimeEnabledButton, false
        ),
        BabyBuddyClient.ACTIVITIES.PUMPING to SwitchButtonLogic(
            bindings.pumpingDisabledButton, bindings.pumpingEnabledButton, false
        ),
        BabyBuddyClient.EVENTS.MEDICATION to SwitchButtonLogic(
            bindings.medicationDisabledButton, bindings.medicationEnabledButton, false
        ),
        BabyBuddyClient.EVENTS.TEMPERATURE to SwitchButtonLogic(
            bindings.temperatureDisabledButton, bindings.temperatureEnabledButton, false
        ),
        BabyBuddyClient.EVENTS.WEIGHT to SwitchButtonLogic(
            bindings.weightDisabledButton, bindings.weightEnabledButton, false
        ),
        BabyBuddyClient.EVENTS.HEIGHT to SwitchButtonLogic(
            bindings.heightDisabledButton, bindings.heightEnabledButton, false
        ),
        BabyBuddyClient.EVENTS.HEAD_CIRCUMFERENCE to SwitchButtonLogic(
            bindings.headCircumferenceDisabledButton, bindings.headCircumferenceEnabledButton, false
        ),
        BabyBuddyClient.EVENTS.BMI to SwitchButtonLogic(
            bindings.bmiDisabledButton, bindings.bmiEnabledButton, false
        ),
    )

    val loggingControllers: Map<String, LoggingControls> = mapOf(
        BabyBuddyClient.EVENTS.CHANGE to DiaperLoggingController(fragment, child.id),
        BabyBuddyClient.EVENTS.NOTE to NotesLoggingController(fragment, child.id),
        BabyBuddyClient.EVENTS.MEDICATION to MedicationLoggingController(fragment, child.id),
        BabyBuddyClient.ACTIVITIES.SLEEP to SleepLoggingController(
            fragment, child.id, timerControl
        ),
        BabyBuddyClient.ACTIVITIES.TUMMY_TIME to TummyTimeLoggingController(
            fragment, child.id, timerControl
        ),
        BabyBuddyClient.ACTIVITIES.FEEDING to FeedingLoggingController(
            fragment, child.id, timerControl
        ),
        BabyBuddyClient.ACTIVITIES.PUMPING to PumpingLoggingController(
            fragment, child.id, timerControl
        ),
        BabyBuddyClient.EVENTS.TEMPERATURE to MeasurementLoggingController(
            fragment, child.id, BabyBuddyClient.EVENTS.TEMPERATURE
        ),
        BabyBuddyClient.EVENTS.WEIGHT to MeasurementLoggingController(
            fragment, child.id, BabyBuddyClient.EVENTS.WEIGHT
        ),
        BabyBuddyClient.EVENTS.HEIGHT to MeasurementLoggingController(
            fragment, child.id, BabyBuddyClient.EVENTS.HEIGHT
        ),
        BabyBuddyClient.EVENTS.HEAD_CIRCUMFERENCE to MeasurementLoggingController(
            fragment, child.id, BabyBuddyClient.EVENTS.HEAD_CIRCUMFERENCE
        ),
        BabyBuddyClient.EVENTS.BMI to MeasurementLoggingController(
            fragment, child.id, BabyBuddyClient.EVENTS.BMI
        ),
    )

    private var timerHandler: Handler? = Handler(fragment.mainActivity.mainLooper)
    private var cachedTimers = emptyArray<Timer>()
    private val timerModificationsBlocker = ConcurrentEventBlocker()

    init {
        loggingControllers.forEach { (activity, controller) ->
            controller.postInit()

            if (controller is GenericLoggingController) {
                controller.onModeChangedByUser = { manual ->
                    handleControllerModeSwitch(activity, controller, manual)
                }
            }

            logicMap[activity]?.addStateListener { state, userInduced ->
                fragment.mainActivity.scope.launch {
                    timerModificationsBlocker.wait()
                    if (state) {
                        startTimerFromSwitch(controller, userInduced, activity)
                    } else {
                        stopTimerFromSwitch(controller, userInduced, activity)
                    }
                }
            }
            controller.saveButton.setOnClickListener {
                fragment.mainActivity.scope.launch {
                    timerModificationsBlocker.wait()
                    runSave(activity, controller)
                }
            }

            timerControl.registerTimersUpdatedCallback(this)
        }

        fragment.mainActivity.storage.child<LoggingButtonControllerStoreState>(
            child.id, "loggingstate"
        )?.let {
            for ((k, logic) in logicMap.entries) {
                if (k !in BabyBuddyClient.EVENTS.ALL) continue
                if (k in it.openState) {
                    logic.state = true
                    loggingControllers[k]?.let { ctrl ->
                        controlsInterface.insertControls(ctrl.controlsView)
                    }
                }
            }
        }

        timerHandler()
    }

    private fun handleControllerModeSwitch(
        activity: String,
        controller: GenericLoggingController,
        manual: Boolean
    ) {
        val matchingTimer = cachedTimers.firstOrNull { it.name == activity } ?: return
        fragment.mainActivity.scope.launch {
            try {
                timerModificationsBlocker.register {
                    if (manual && matchingTimer.active) {
                        AsyncPromise.call<Any, TranslatedException> { promise ->
                            timerControl.stopTimer(matchingTimer, promise)
                        }
                        matchingTimer.active = false
                    } else if (!manual && !matchingTimer.active) {
                        val started = AsyncPromise.call<Timer, TranslatedException> { promise ->
                            timerControl.startTimer(matchingTimer, promise)
                        }
                        controller.updateTimer(started)
                    }
                }
            } catch (_: AsyncPromiseFailure) {
            }
        }
    }

    private fun startTimerFromSwitch(
        controller: LoggingControls,
        userInduced: Boolean,
        activity: String
    ) {
        controlsInterface.insertControls(controller.controlsView)
        val isManual = (controller as? GenericLoggingController)?.isManualMode == true
        if (userInduced && (controller is TimerBase) && !isManual) {
            cachedTimers.firstOrNull { it.name == activity }?.let {
                fragment.mainActivity.scope.launch {
                    try {
                        timerModificationsBlocker.register {
                            val newTimer =
                                AsyncPromise.call<Timer, TranslatedException> { promise ->
                                    timerControl.startTimer(it, promise)
                                }
                            controller.updateTimer(newTimer)
                        }
                    } catch (e: AsyncPromiseFailure) {
                        (e.value as? TranslatedException)?.let {
                            fragment.showError(
                                true,
                                R.string.activity_store_failure_message,
                                it.message
                            )
                        }
                    }
                }
            }
        }
        controller.postStart()
    }

    private suspend fun stopTimerFromSwitch(
        controller: LoggingControls,
        userInduced: Boolean,
        activity: String
    ) {
        timerModificationsBlocker.register {
            val timer = cachedTimers.firstOrNull { it.name == activity }
            val isManual = (controller as? GenericLoggingController)?.isManualMode == true
            if (AsyncPromise.call<Boolean, TranslatedException> { promise ->
                    var defaultSucceed = true
                    timer?.let { t ->
                        if (t.active && userInduced && !isManual) {
                            val timeMs = nowServer().time - t.start.time
                            if (timeMs > 10000) {
                                defaultSucceed = false

                                val message = Phrase.from(
                                    fragment.requireContext(),
                                    R.string.cancel_timer_warning_message
                                )
                                    .put("activity", fragment.translateActivityName(activity))
                                    .format().toString()

                                fragment.showQuestion(
                                    true,
                                    fragment.getString(R.string.cancel_timer_warning_title),
                                    message,
                                    fragment.getString(R.string.cancel_timer_warning_stop),
                                    fragment.getString(R.string.cancel_timer_warning_keep),
                                    object : DialogCallback {
                                        override fun call(b: Boolean) {
                                            promise.succeeded(b)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    if (defaultSucceed) {
                        promise.succeeded(true)
                    }
                }) {
                (controller as? GenericLoggingController)?.switchToTimerModeSilently()
                controlsInterface.removeControls(controller.controlsView)
                if (userInduced && (controller is TimerBase)) {
                    timer?.takeIf { it.active }?.let {
                        fragment.mainActivity.scope.launch {
                            try {
                                controller.updateTimer(null)
                                AsyncPromise.call<Any, TranslatedException> { promise ->
                                    timerControl.stopTimer(it, promise)
                                }
                                controller.updateTimer(null)
                            } catch (e: AsyncPromiseFailure) {
                                (e.value as? TranslatedException)?.let {
                                    fragment.showError(
                                        true,
                                        R.string.activity_store_failure_message,
                                        it.message
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                logicMap[activity]?.state = true
            }
        }
    }

    private fun timerHandler() {
        timerHandler?.let {
            it.postDelayed(Runnable { timerHandler() }, 500)
            for (c in loggingControllers.values) {
                c.updateVisuals()
            }
        }
    }

    suspend fun runSave(activity: String, controller: LoggingControls) {
        timerModificationsBlocker.wait()
        timerModificationsBlocker.register {
            try {
                val te = controller.save()
                logicMap[activity]?.state = false
                controlsInterface.removeControls(controller.controlsView)
                controller.reset()
                storeStateForSuspend()
                controlsInterface.updateTimeline(te)
            } catch (e: ValidationException) {
                fragment.showError(
                    true,
                    R.string.activity_store_failure_message,
                    e.message
                )
            } catch (e: RequestCodeFailure) {
                fragment.showError(
                    true,
                    R.string.activity_store_failure_message,
                    Phrase.from(
                        fragment.requireContext(),
                        R.string.activity_store_failure_server_error
                    )
                        .put(
                            "message",
                            fragment.getString(R.string.activity_store_failure_server_error_general)
                        )
                        .put("server_message", e.jsonErrorMessages().joinToString(", "))
                        .format().toString()
                )
            } catch (e: IOException) {
                fragment.showError(
                    true,
                    R.string.activity_store_failure_message,
                    R.string.activity_store_failure_server_error_generic_ioerror
                )
            }
        }
    }

    fun storeStateForSuspend() {
        val openState = mutableListOf<String>()
        for ((name, controller) in loggingControllers) {
            controller.storeStateForSuspend()
            if (name in BabyBuddyClient.EVENTS.ALL) {
                if (logicMap[name]?.state == true) {
                    openState.add(name)
                }
            }
        }
        fragment.mainActivity.storage.child(
            child.id,
            "loggingstate",
            LoggingButtonControllerStoreState(openState.toTypedArray())
        )
    }

    fun destroy() {
        storeStateForSuspend()
        for (controller in loggingControllers.values) {
            controlsInterface.removeControls(controller.controlsView)
        }
        for (logic in logicMap.values) {
            logic.destroy()
        }
        timerControl.unregisterTimersUpdatedCallback(this)
        timerHandler = null
    }

    override fun newTimerListLoaded(timers: Array<Timer>) {
        cachedTimers = timers
        if (timerModificationsBlocker.isBlocked) return

        val toDisable = loggingControllers
            .filter { (_, ctrl) ->
                ctrl is TimerBase && !((ctrl as? GenericLoggingController)?.isManualMode == true)
            }
            .map { it.key }
            .toMutableList()

        for (timer in timers) {
            if (!timer.active) continue
            loggingControllers[timer.name]?.let { controller ->
                if (controller is TimerBase) {
                    toDisable.remove(timer.name)

                    controller.updateTimer(timer)
                    controller.updateVisuals()
                    controlsInterface.insertControls(controller.controlsView)
                }
            }
            logicMap[timer.name]?.let { logic ->
                logic.state = true
            }
        }
        for (name in toDisable) {
            logicMap[name]?.state = false
            loggingControllers[name]?.let {
                controlsInterface.removeControls(it.controlsView)
            }
        }
    }
}
