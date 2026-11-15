package eu.pkgsoftware.babybuddywidgets

import android.app.TimePickerDialog
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateFormat as AndroidDateFormat
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.squareup.phrase.Phrase
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingMethodEnum
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingTypeEnum
import eu.pkgsoftware.babybuddywidgets.activitycomponents.TimerControl
import eu.pkgsoftware.babybuddywidgets.databinding.BabyManagerBinding
import eu.pkgsoftware.babybuddywidgets.debugging.GlobalDebugObject
import eu.pkgsoftware.babybuddywidgets.history.BabySummaryHelper
import eu.pkgsoftware.babybuddywidgets.history.ChildEventHistoryLoader
import eu.pkgsoftware.babybuddywidgets.history.EntryEditBottomSheet
import eu.pkgsoftware.babybuddywidgets.history.ShowErrorPill
import eu.pkgsoftware.babybuddywidgets.history.TimelineFilter
import eu.pkgsoftware.babybuddywidgets.local.LocalBabyBuddyRepository
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.FeedingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.PumpingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.SleepEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Timer
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TummyTimeEntry
import eu.pkgsoftware.babybuddywidgets.tags.TagManagerDialog
import eu.pkgsoftware.babybuddywidgets.timers.FragmentCallbacks
import eu.pkgsoftware.babybuddywidgets.timers.LoggingButtonController
import eu.pkgsoftware.babybuddywidgets.timers.TimerControlInterface
import eu.pkgsoftware.babybuddywidgets.timers.TimersUpdatedCallback
import eu.pkgsoftware.babybuddywidgets.timers.TranslatedException
import eu.pkgsoftware.babybuddywidgets.utils.Promise
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

class BabyLayoutHolder(
    private val baseFragment: BaseFragment,
    private val binding: BabyManagerBinding
) : RecyclerView.ViewHolder(
    binding.root
), TimerControlInterface {
    private val repository: LocalBabyBuddyRepository = baseFragment.mainActivity.repository
    private val mainHandler = Handler(Looper.getMainLooper())

    var child: Child? = null

    private var childHistoryLoader: ChildEventHistoryLoader? = null

    private var cachedTimers: Array<BabyBuddyClient.Timer>? = null
    private val updateTimersCallbacks: MutableList<TimersUpdatedCallback> = ArrayList(10)

    private var pendingTimerModificationCalls = 0
    private var deselected = true
    private var timerPollJob: Job? = null
    private var activeTagFilterSlug: String? = null
    private var lastRenderedStandaloneTimerIds: List<Int> = emptyList()
    private val quickTimerChipMap = LinkedHashMap<Int, Chip>()

    private var loggingButtonController: LoggingButtonController? = null

    private val repoChangeListener = LocalBabyBuddyRepository.ChangeListener {
        mainHandler.post {
            if (deselected || baseFragment.isDetached || baseFragment.context == null) return@post
            val currentChild = child ?: return@post
            refreshTimersOnce(currentChild)
        }
    }

    companion object {
        private val CONTROLLER_TIMER_REGEX =
            Regex("^(feeding|sleep|tummy-time|pumping)-bbapp:\\d+$", RegexOption.IGNORE_CASE)
    }

    init {
        binding.mainScrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            childHistoryLoader?.updateTop()
        }

        binding.babySwipeRefresh.setColorSchemeResources(
            R.color.primary_color,
            R.color.teal_200
        )
        binding.babySwipeRefresh.setOnRefreshListener {
            childHistoryLoader?.forceRefresh() ?: run {
                binding.babySwipeRefresh.isRefreshing = false
            }
        }

        binding.timelineFilterChips.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val filter = when (checkedId) {
                R.id.chipFilterFeeding -> TimelineFilter.FEEDING
                R.id.chipFilterSleep -> TimelineFilter.SLEEP
                R.id.chipFilterDiaper -> TimelineFilter.DIAPER
                R.id.chipFilterPumping -> TimelineFilter.PUMPING
                R.id.chipFilterTummy -> TimelineFilter.TUMMY_TIME
                R.id.chipFilterMedication -> TimelineFilter.MEDICATION
                R.id.chipFilterGrowth -> TimelineFilter.GROWTH_HEALTH
                R.id.chipFilterNotes -> TimelineFilter.NOTES
                else -> TimelineFilter.ALL
            }
            childHistoryLoader?.setFilter(filter)
        }

        binding.timelineSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                childHistoryLoader?.setSearchQuery(s?.toString().orEmpty())
            }
        })

        binding.timelineTagFilterButton.setOnClickListener {
            showTagFilterDialog()
        }
    }

    private fun showTagFilterDialog() {
        val ctx = baseFragment.context ?: return
        val tags = repository.listTags()
        val options = ArrayList<String>(tags.size + 2)
        options.add(ctx.getString(R.string.tag_filter_all))
        tags.forEach { options.add("#${it.name}") }
        options.add(ctx.getString(R.string.tag_manager_title))

        AlertDialog.Builder(ctx)
            .setTitle(R.string.tag_manager_title)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        activeTagFilterSlug = null
                        binding.timelineTagFilterButton.setText(R.string.tag_filter_all)
                        childHistoryLoader?.setTagFilter(null)
                    }
                    options.lastIndex -> {
                        TagManagerDialog.showManager(baseFragment.mainActivity) {
                            // Verify active filter still exists
                            val currentTags = repository.listTags()
                            if (activeTagFilterSlug != null && currentTags.none { it.slug == activeTagFilterSlug }) {
                                activeTagFilterSlug = null
                                binding.timelineTagFilterButton.setText(R.string.tag_filter_all)
                                childHistoryLoader?.setTagFilter(null)
                            } else {
                                childHistoryLoader?.forceRefresh()
                            }
                        }
                    }
                    else -> {
                        val chosen = tags[which - 1]
                        activeTagFilterSlug = chosen.slug
                        binding.timelineTagFilterButton.text = "#${chosen.name}"
                        childHistoryLoader?.setTagFilter(chosen.slug)
                    }
                }
            }
            .show()
    }

    private fun formatShortMinutes(minutes: Long): String {
        val res = baseFragment.resources
        return when {
            minutes < 1L -> res.getString(R.string.summary_time_just_now)
            minutes < 60L -> Phrase.from(res, R.string.summary_time_ago_m)
                .put("minutes", minutes.toInt())
                .format()
                .toString()
                .substringBefore(" ")
                .ifBlank { "${minutes}m" }
            else -> "${minutes / 60L}h ${minutes % 60L}m"
        }
    }

    private fun formatRecentBreastSidesHint(feedings: List<FeedingEntry>): String {
        val sides = feedings.mapNotNull {
            when (it.feedingMethod) {
                FeedingMethodEnum.LEFT_BREAST -> "L"
                FeedingMethodEnum.RIGHT_BREAST -> "R"
                FeedingMethodEnum.BOTH_BREASTS -> "L+R"
                else -> null
            }
        }
        return if (sides.size >= 2) sides.reversed().joinToString(" → ") else ""
    }

    private fun renderTodaySummary(entries: List<TimeEntry>) {
        if (baseFragment.isDetached || baseFragment.context == null) return
        val res = baseFragment.resources
        val summary = BabySummaryHelper.computeTodaySummary(entries, convertServerOffset = false)
        val hideEmpty = baseFragment.mainActivity.standaloneSettings.dashboardHideEmpty

        // Hide empty top blocks only if at least one block has data (when dashboardHideEmpty is enabled)
        val anyLastPresent = summary.lastFeeding != null || summary.lastSleep != null || summary.lastDiaper != null
        binding.lastFeedingBlock.visibility =
            if (hideEmpty && anyLastPresent && summary.lastFeeding == null) View.GONE else View.VISIBLE
        binding.lastSleepBlock.visibility =
            if (hideEmpty && anyLastPresent && summary.lastSleep == null) View.GONE else View.VISIBLE
        binding.lastDiaperBlock.visibility =
            if (hideEmpty && anyLastPresent && summary.lastDiaper == null) View.GONE else View.VISIBLE

        binding.lastFeedingTimeAgo.text =
            BabySummaryHelper.formatTimeAgo(res, summary.lastFeedingClientTime)
        if (summary.lastFeeding != null) {
            val detail = BabySummaryHelper.formatFeedingMethodDetail(res, summary.lastFeeding)
            val sidesHint = formatRecentBreastSidesHint(summary.recentFeedings)
            val combined = listOf(detail, sidesHint).filter { it.isNotBlank() }.joinToString(" · ")
            binding.lastFeedingDetail.text = combined
            binding.lastFeedingDetail.visibility = if (combined.isNotBlank()) View.VISIBLE else View.GONE
        } else {
            binding.lastFeedingDetail.visibility = View.GONE
        }

        binding.lastSleepTimeAgo.text =
            BabySummaryHelper.formatTimeAgo(res, summary.lastSleepClientTime)
        if (summary.awakeMinutes != null && summary.awakeMinutes >= 0L) {
            val awakeTimeStr = "${summary.awakeMinutes / 60L}h ${summary.awakeMinutes % 60L}m"
            binding.lastSleepDetail.text = Phrase.from(res, R.string.summary_awake_for_pattern)
                .put("time", awakeTimeStr)
                .format()
            binding.lastSleepDetail.visibility = View.VISIBLE
        } else {
            binding.lastSleepDetail.visibility = View.GONE
        }

        binding.lastDiaperTimeAgo.text =
            BabySummaryHelper.formatTimeAgo(res, summary.lastDiaperClientTime)
        if (summary.lastDiaper != null) {
            val d = summary.lastDiaper
            val parts = mutableListOf<String>()
            if (d.wet) parts.add(res.getString(R.string.diaper_wet_label))
            if (d.solid) parts.add(res.getString(R.string.diaper_solid_label))
            if (!d.wet && !d.solid) parts.add(res.getString(R.string.diaper_empty_label))
            binding.lastDiaperDetail.text = parts.joinToString(" + ")
            binding.lastDiaperDetail.visibility = View.VISIBLE
        } else {
            binding.lastDiaperDetail.visibility = View.GONE
        }

        binding.todayFeedingSummaryText.text = if (summary.todayFeedingAmountMl > 0.0) {
            Phrase.from(res, R.string.summary_today_feeding_value)
                .put("count", summary.todayFeedingCount)
                .put("amount", summary.todayFeedingAmountMl.roundToInt())
                .format()
        } else {
            Phrase.from(res, R.string.summary_today_feeding_count_only)
                .put("count", summary.todayFeedingCount)
                .format()
        }

        val sleepH = summary.todaySleepMinutes / 60
        val sleepM = summary.todaySleepMinutes % 60
        binding.todaySleepSummaryText.text = if (summary.todayNapMinutes > 0) {
            Phrase.from(res, R.string.summary_sleep_nap_night_pattern)
                .put("hours", sleepH)
                .put("minutes", sleepM)
                .put("nap_m", summary.todayNapMinutes)
                .format()
        } else {
            Phrase.from(res, R.string.summary_today_sleep_value)
                .put("hours", sleepH)
                .put("minutes", sleepM)
                .format()
        }

        binding.todayDiaperSummaryText.text = Phrase.from(res, R.string.summary_today_diaper_value)
            .put("total", summary.todayDiaperTotal)
            .put("wet", summary.todayDiaperWet)
            .put("solid", summary.todayDiaperSolid)
            .format()

        // Extra row: Pumping + Tummy Time + Next Medication Dose Pill
        val showPumpingTummy = (!hideEmpty && (summary.todayPumpingCount > 0 || summary.todayTummyCount > 0)) ||
            summary.todayPumpingAmountMl > 0.0 || summary.todayTummyMinutes > 0
        if (showPumpingTummy) {
            binding.todayPumpingTummyText.text = Phrase.from(res, R.string.summary_pumping_tummy_pattern)
                .put("pumping_ml", summary.todayPumpingAmountMl.roundToInt())
                .put("tummy_m", summary.todayTummyMinutes)
                .format()
            binding.todayPumpingTummyText.visibility = View.VISIBLE
        } else {
            binding.todayPumpingTummyText.visibility = View.GONE
        }

        val medReminder = summary.activeMedicationReminder
        if (medReminder != null && medReminder.nextDoseTime != null) {
            val nowMs = System.currentTimeMillis()
            if (medReminder.isNextDoseReady(nowMs)) {
                binding.nextMedicationBadge.text =
                    "${medReminder.name}: ${res.getString(R.string.medication_next_dose_ready)}"
            } else {
                val remMs = medReminder.remainingMillisUntilNextDose(nowMs) ?: 0L
                val totalMin = (remMs / 60_000L).coerceAtLeast(1L)
                val remText = if (totalMin >= 60L) {
                    "${totalMin / 60L}h ${totalMin % 60L}m"
                } else {
                    "${totalMin}m"
                }
                binding.nextMedicationBadge.text = Phrase.from(res, R.string.medication_next_dose_in_pattern)
                    .put("name", medReminder.name)
                    .put("time", remText)
                    .format()
            }
            binding.nextMedicationBadge.visibility = View.VISIBLE
        } else {
            binding.nextMedicationBadge.visibility = View.GONE
        }

        binding.todayExtraSummaryRow.visibility =
            if (binding.todayPumpingTummyText.visibility == View.VISIBLE ||
                binding.nextMedicationBadge.visibility == View.VISIBLE
            ) View.VISIBLE else View.GONE
    }

    private fun renderQuickTimersStrip(timers: List<Timer>) {
        val ctx = baseFragment.context ?: return
        val activeChild = child ?: return
        val standalone = timers.filter { !CONTROLLER_TIMER_REGEX.matches(it.name) && it.name !in BabyBuddyClient.ACTIVITIES.ALL }
        val ids = standalone.map { it.id }

        if (ids != lastRenderedStandaloneTimerIds || binding.quickTimersChipGroup.childCount == 0) {
            lastRenderedStandaloneTimerIds = ids
            quickTimerChipMap.clear()
            binding.quickTimersChipGroup.removeAllViews()

            for (t in standalone) {
                val chip = Chip(ctx).apply {
                    isCheckable = false
                    setChipBackgroundColorResource(R.color.card_surface_elevated)
                    setTextColor(ContextCompat.getColor(ctx, R.color.primary_color))
                    textSize = 12f
                    setOnClickListener {
                        showQuickTimerActionsDialog(activeChild, t)
                    }
                }
                quickTimerChipMap[t.id] = chip
                binding.quickTimersChipGroup.addView(chip)
            }

            val addChip = Chip(ctx).apply {
                text = ctx.getString(R.string.quick_timer_add_chip)
                isCheckable = false
                setChipBackgroundColorResource(R.color.card_surface)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
                textSize = 12f
                setOnClickListener {
                    showCreateQuickTimerDialog(activeChild)
                }
            }
            binding.quickTimersChipGroup.addView(addChip)
        }

        val nowMs = System.currentTimeMillis()
        for (t in standalone) {
            val chip = quickTimerChipMap[t.id] ?: continue
            val elapsedSec = ((nowMs - t.start.time).coerceAtLeast(0L)) / 1000L
            val hh = elapsedSec / 3600L
            val mm = (elapsedSec % 3600L) / 60L
            val ss = elapsedSec % 60L
            val durStr = if (hh > 0L) {
                String.format(Locale.US, "%d:%02d:%02d", hh, mm, ss)
            } else {
                String.format(Locale.US, "%02d:%02d", mm, ss)
            }
            chip.text = "⏱ ${t.name} · $durStr"
        }
    }

    private fun showCreateQuickTimerDialog(activeChild: Child) {
        val ctx = baseFragment.context ?: return
        val density = ctx.resources.displayMetrics.density
        val pad = (18 * density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val nameInput = EditText(ctx).apply {
            hint = ctx.getString(R.string.quick_timer_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setBackgroundResource(R.drawable.bg_soft_pill)
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }
        container.addView(nameInput)

        AlertDialog.Builder(ctx)
            .setTitle(R.string.quick_timer_add_chip)
            .setView(container)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val customName = nameInput.text.toString().trim().ifEmpty {
                    ctx.getString(R.string.quick_timer_name_hint)
                }
                repository.createTimer(activeChild.id, customName)
                refreshTimersOnce(activeChild)
            }
            .setNegativeButton(R.string.history_delete_question_cancel_button, null)
            .show()
    }

    private fun showQuickTimerActionsDialog(activeChild: Child, timerSnap: Timer) {
        val ctx = baseFragment.context ?: return
        // Re-fetch freshest timer start time in case it was adjusted earlier
        val freshTimer = repository.listTimers(activeChild.id).firstOrNull { it.id == timerSnap.id } ?: timerSnap
        val res = ctx.resources
        val items = arrayOf(
            "✓ ${res.getString(R.string.tile_sleep)}",
            "✓ ${res.getString(R.string.tile_tummy_time)}",
            "✓ ${res.getString(R.string.tile_pumping)}",
            "✓ ${res.getString(R.string.tile_feeding)}",
            res.getString(R.string.timer_adjust_minus_5m),
            res.getString(R.string.timer_adjust_minus_15m),
            res.getString(R.string.timer_adjust_pick_time),
            res.getString(R.string.timer_adjust_restart_now),
            res.getString(R.string.quick_timer_stop_delete),
        )
        AlertDialog.Builder(ctx)
            .setTitle("${freshTimer.name} (${freshTimer.duration})")
            .setItems(items) { _, which ->
                val endNow = Date()
                val safeStart = if (freshTimer.start.before(endNow)) freshTimer.start else Date(endNow.time - 60_000L)
                when (which) {
                    0 -> {
                        val saved = repository.createEntry(
                            SleepEntry(
                                id = 0,
                                childId = activeChild.id,
                                start = safeStart,
                                end = endNow,
                                _notes = freshTimer.name,
                                nap = null,
                            )
                        )
                        repository.deleteTimer(freshTimer.id)
                        onExternalEntryMutated(activeChild.id, saved)
                    }
                    1 -> {
                        val saved = repository.createEntry(
                            TummyTimeEntry(
                                id = 0,
                                childId = activeChild.id,
                                start = safeStart,
                                end = endNow,
                                _notes = freshTimer.name,
                            )
                        )
                        repository.deleteTimer(freshTimer.id)
                        onExternalEntryMutated(activeChild.id, saved)
                    }
                    2 -> {
                        val saved = repository.createEntry(
                            PumpingEntry(
                                id = 0,
                                childId = activeChild.id,
                                _start = safeStart,
                                _end = endNow,
                                _notes = freshTimer.name,
                                amount = 0.0,
                            )
                        )
                        repository.deleteTimer(freshTimer.id)
                        onExternalEntryMutated(activeChild.id, saved)
                    }
                    3 -> {
                        val saved = repository.createEntry(
                            FeedingEntry(
                                id = 0,
                                childId = activeChild.id,
                                start = safeStart,
                                end = endNow,
                                _notes = freshTimer.name,
                                feedingType = FeedingTypeEnum.BREAST_MILK,
                                feedingMethod = FeedingMethodEnum.LEFT_BREAST,
                                amount = null,
                            )
                        )
                        repository.deleteTimer(freshTimer.id)
                        onExternalEntryMutated(activeChild.id, saved)
                    }
                    4 -> {
                        repository.updateTimerStart(freshTimer.id, freshTimer.start.time - 5L * 60_000L)
                        refreshTimersOnce(activeChild)
                    }
                    5 -> {
                        repository.updateTimerStart(freshTimer.id, freshTimer.start.time - 15L * 60_000L)
                        refreshTimersOnce(activeChild)
                    }
                    6 -> {
                        val cal = Calendar.getInstance().apply { time = freshTimer.start }
                        TimePickerDialog(
                            ctx,
                            { _, hourOfDay, minute ->
                                val picked = Calendar.getInstance().apply {
                                    time = freshTimer.start
                                    set(Calendar.HOUR_OF_DAY, hourOfDay)
                                    set(Calendar.MINUTE, minute)
                                    set(Calendar.SECOND, 0)
                                }
                                if (picked.timeInMillis > System.currentTimeMillis()) {
                                    picked.add(Calendar.DAY_OF_YEAR, -1)
                                }
                                repository.updateTimerStart(freshTimer.id, picked.timeInMillis)
                                refreshTimersOnce(activeChild)
                            },
                            cal.get(Calendar.HOUR_OF_DAY),
                            cal.get(Calendar.MINUTE),
                            AndroidDateFormat.is24HourFormat(ctx)
                        ).show()
                    }
                    7 -> {
                        repository.restartTimer(freshTimer.id)
                        refreshTimersOnce(activeChild)
                    }
                    8 -> {
                        repository.deleteTimer(freshTimer.id)
                        refreshTimersOnce(activeChild)
                    }
                }
            }
            .show()
    }

    private fun onExternalEntryMutated(childId: Int, createdEntry: TimeEntry? = null) {
        (baseFragment as? LoggedInFragment)?.notifyChildDataChanged(childId)
        childHistoryLoader?.let { loader ->
            if (createdEntry != null) {
                loader.addEntryToTop(createdEntry)
            }
            loader.forceRefresh()
        }
        child?.let { refreshTimersOnce(it) }
    }

    fun updateChild(c: Child) {
        if (c == child && !deselected) {
            return
        }

        Log.d(
            "BabyLayoutHolder",
            "Selecting view for child ${c.slug} (current child: ${child?.slug})"
        )

        var childChanged = false
        if (child !== c) {
            childChanged = true
            close()
            this.child = c
        }

        resetChildHistoryLoader()
        closeButtonController()

        child?.let { activeChild ->
            deselected = false
            repository.addChangeListener(repoChangeListener)

            timerPollJob?.let {
                if (childChanged) {
                    it.cancel()
                }
            }
            if (!(timerPollJob?.isActive ?: false)) {
                timerPollJob = baseFragment.mainActivity.scope.launch {
                    updateTimerLoop(activeChild)
                }
            }

            binding.chipFilterAll.isChecked = true
            activeTagFilterSlug = null
            binding.timelineTagFilterButton.setText(R.string.tag_filter_all)
            if (!binding.timelineSearchInput.text.isNullOrEmpty()) {
                binding.timelineSearchInput.setText("")
            }
            lastRenderedStandaloneTimerIds = emptyList()
            renderTodaySummary(emptyList())

            childHistoryLoader = ChildEventHistoryLoader(
                fragment = baseFragment,
                container = binding.innerTimeline,
                childId = activeChild.id,
                visibilityCheck = VisibilityCheck(binding.mainScrollView),
                progressBar = binding.timelineProgressSpinner,
                errorPill = object : ShowErrorPill {
                    override fun showErrorPill(entryType: String, exception: Exception?) {
                        val tActivity = baseFragment.translateActivityName(entryType)
                        val msg = Phrase.from(
                            baseFragment.resources,
                            R.string.history_loading_timeline_entry_failed
                        )
                            .put("activity", tActivity)
                            .format().toString()
                        baseFragment.mainActivity.binding.globalErrorBubble.flashMessage(msg)
                    }
                },
                emptyHintView = binding.timelineEmptyHint,
                onEntriesUpdated = { entries ->
                    binding.babySwipeRefresh.isRefreshing = false
                    renderTodaySummary(entries)
                },
                onEditEntryRequested = { entry ->
                    EntryEditBottomSheet.show(baseFragment, entry) {
                        (baseFragment as? LoggedInFragment)?.notifyChildDataChanged(activeChild.id)
                        childHistoryLoader?.forceRefresh()
                    }
                }
            )

            loggingButtonController = LoggingButtonController(
                baseFragment,
                binding,
                object : FragmentCallbacks {
                    override fun insertControls(view: View) {
                        if (view.parent != null) {
                            return
                        }
                        binding.loggingEditors.addView(view)
                    }

                    override fun removeControls(view: View) {
                        if (view.parent == null) {
                            return
                        }
                        binding.loggingEditors.removeView(view)
                    }

                    override fun updateTimeline(newEntry: TimeEntry?) {
                        onExternalEntryMutated(activeChild.id, newEntry)
                    }
                },
                activeChild,
                this
            )
        }
    }

    private fun refreshTimersOnce(childArg: Child) {
        try {
            val localTimers = repository.listTimers(childArg.id)
            val oldTimers = ArrayList<BabyBuddyClient.Timer>(localTimers.size)
            for (t in localTimers) {
                val oldTimer = BabyBuddyClient.Timer()
                oldTimer.id = t.id
                oldTimer.child_id = if (t.childId == 0) childArg.id else t.childId
                oldTimer.name = t.name
                oldTimer.start = t.start
                oldTimer.active = true
                oldTimer.user_id = t.userId
                oldTimers.add(oldTimer)
            }
            baseFragment.disconnectInterface.setDisconnected("child list", false)
            updateTimerList(oldTimers.toTypedArray())
            renderQuickTimersStrip(localTimers)
        } catch (e: Exception) {
            GlobalDebugObject.log("BabyLayoutHolder local timer refresh error: ${e.message}")
        }
    }

    suspend fun updateTimerLoop(childArg: Child?) {
        if (childArg == null) {
            return
        }
        var tick = 0
        while (!deselected) {
            refreshTimersOnce(childArg)

            // Refresh "time ago" and medication countdown labels every 15s
            tick++
            if (tick % 15 == 0) {
                childHistoryLoader?.let { renderTodaySummary(it.currentEntriesSnapshot()) }
            }

            delay(1000.milliseconds)
        }
    }

    fun updateTimerList(timers: Array<BabyBuddyClient.Timer>) {
        if (pendingTimerModificationCalls > 0) {
            return
        }

        if (child == null) {
            cachedTimers = arrayOf()
            callTimerUpdateCallback()
            return
        }

        for (t in timers) {
            child?.let { activeChild ->
                if (t.child_id != activeChild.id && t.child_id != 0) {
                    return
                }
            } ?: return
        }

        cachedTimers = timers
        callTimerUpdateCallback()
    }

    private fun resetChildHistoryLoader() {
        childHistoryLoader?.close()
        childHistoryLoader = null
    }

    private fun closeButtonController() {
        loggingButtonController?.let {
            it.storeStateForSuspend()
            it.destroy()
            loggingButtonController = null
        }
    }

    fun onViewDeselected() {
        Log.d(
            "BabyLayoutHolder",
            "Deselected view for child " + child?.slug
        )
        repository.removeChangeListener(repoChangeListener)
        closeButtonController()
        resetChildHistoryLoader()
        deselected = true
    }

    fun close() {
        repository.removeChangeListener(repoChangeListener)
        closeButtonController()
        resetChildHistoryLoader()
        timerPollJob?.cancel()
        deselected = true
        child = null
        cachedTimers = null
    }

    private inner class UpdateBufferingPromise<A, B>(private val promise: Promise<A, B>) :
        Promise<A, B> {
        init {
            pendingTimerModificationCalls++
        }

        override fun succeeded(a: A) {
            pendingTimerModificationCalls--
            promise.succeeded(a)
            child?.let { refreshTimersOnce(it) }
        }

        override fun failed(b: B) {
            pendingTimerModificationCalls--
            promise.failed(b)
        }
    }

    override fun createNewTimer(
        timer: BabyBuddyClient.Timer,
        cb: Promise<BabyBuddyClient.Timer, TranslatedException>
    ) {
        child?.let { activeChild ->
            baseFragment.mainActivity.getChildTimerControl(activeChild.id).createNewTimer(
                timer, UpdateBufferingPromise(cb)
            )
        }
    }

    override fun startTimer(
        timer: BabyBuddyClient.Timer,
        cb: Promise<BabyBuddyClient.Timer, TranslatedException>
    ) {
        child?.let { activeChild ->
            baseFragment.mainActivity.getChildTimerControl(activeChild.id).startTimer(
                timer, UpdateBufferingPromise(cb)
            )
        }
    }

    override fun stopTimer(timer: BabyBuddyClient.Timer, cb: Promise<Any, TranslatedException>) {
        child?.let { activeChild ->
            baseFragment.mainActivity.getChildTimerControl(activeChild.id).stopTimer(
                timer, UpdateBufferingPromise(cb)
            )
        }
    }

    override fun registerTimersUpdatedCallback(callback: TimersUpdatedCallback) {
        child?.let { activeChild ->
            if (updateTimersCallbacks.contains(callback)) {
                return
            }
            updateTimersCallbacks.add(callback)

            baseFragment.mainActivity.getChildTimerControl(activeChild.id).registerTimersUpdatedCallback(
                object : TimersUpdatedCallback {
                    override fun newTimerListLoaded(timers: Array<BabyBuddyClient.Timer>) {
                        for (c in updateTimersCallbacks) {
                            c.newTimerListLoaded(timers)
                        }
                    }
                }
            )

            callTimerUpdateCallback()
        }
    }

    override fun unregisterTimersUpdatedCallback(callback: TimersUpdatedCallback) {
        if (!updateTimersCallbacks.contains(callback)) {
            return
        }
        updateTimersCallbacks.remove(callback)
    }

    private fun callTimerUpdateCallback() {
        child?.let { activeChild ->
            val wrapped =
                baseFragment.mainActivity.getChildTimerControl(activeChild.id).wrap as TimerControl
            if (cachedTimers != null) {
                wrapped.callTimerUpdateCallback(cachedTimers!!)
            }
        }
    }

    override fun getNotes(timer: BabyBuddyClient.Timer): CredStore.Notes {
        return child?.let { activeChild ->
            return@let baseFragment.mainActivity.getChildTimerControl(activeChild.id).getNotes(timer)
        } ?: CredStore.Notes("", false)
    }

    override fun setNotes(timer: BabyBuddyClient.Timer, notes: CredStore.Notes?) {
        child?.let { activeChild ->
            baseFragment.mainActivity.getChildTimerControl(activeChild.id).setNotes(timer, notes)
        }
    }
}
