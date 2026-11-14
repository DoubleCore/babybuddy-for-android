package eu.pkgsoftware.babybuddywidgets

import android.util.Log
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.squareup.phrase.Phrase
import eu.pkgsoftware.babybuddywidgets.activitycomponents.TimerControl
import eu.pkgsoftware.babybuddywidgets.databinding.BabyManagerBinding
import eu.pkgsoftware.babybuddywidgets.debugging.GlobalDebugObject
import eu.pkgsoftware.babybuddywidgets.history.BabySummaryHelper
import eu.pkgsoftware.babybuddywidgets.history.ChildEventHistoryLoader
import eu.pkgsoftware.babybuddywidgets.history.ShowErrorPill
import eu.pkgsoftware.babybuddywidgets.history.TimelineFilter
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient
import eu.pkgsoftware.babybuddywidgets.networking.RequestCodeFailure
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.InterruptedException
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.PaginatedResult
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Timer
import eu.pkgsoftware.babybuddywidgets.timers.FragmentCallbacks
import eu.pkgsoftware.babybuddywidgets.timers.LoggingButtonController
import eu.pkgsoftware.babybuddywidgets.timers.TimerControlInterface
import eu.pkgsoftware.babybuddywidgets.timers.TimersUpdatedCallback
import eu.pkgsoftware.babybuddywidgets.timers.TranslatedException
import eu.pkgsoftware.babybuddywidgets.utils.Promise
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

class BabyLayoutHolder(
    private val baseFragment: BaseFragment,
    private val binding: BabyManagerBinding
) : RecyclerView.ViewHolder(
    binding.root
), TimerControlInterface {
    private val client: BabyBuddyClient = baseFragment.mainActivity.client

    var child: Child? = null

    private var childHistoryLoader: ChildEventHistoryLoader? = null

    private var cachedTimers: Array<BabyBuddyClient.Timer>? = null
    private val updateTimersCallbacks: MutableList<TimersUpdatedCallback> = ArrayList(10)

    private var pendingTimerModificationCalls = 0
    private var deselected = true
    private var timerPollJob: kotlinx.coroutines.Job? = null

    private var loggingButtonController: LoggingButtonController? = null

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
                R.id.chipFilterGrowth -> TimelineFilter.GROWTH_HEALTH
                R.id.chipFilterNotes -> TimelineFilter.NOTES
                else -> TimelineFilter.ALL
            }
            childHistoryLoader?.setFilter(filter)
        }
    }

    private fun renderTodaySummary(entries: List<TimeEntry>) {
        if (baseFragment.isDetached || baseFragment.context == null) return
        val res = baseFragment.resources
        val summary = BabySummaryHelper.computeTodaySummary(entries)

        binding.lastFeedingTimeAgo.text =
            BabySummaryHelper.formatTimeAgo(res, summary.lastFeedingClientTime)
        if (summary.lastFeeding != null) {
            val detail = BabySummaryHelper.formatFeedingMethodDetail(res, summary.lastFeeding)
            binding.lastFeedingDetail.text = detail
            binding.lastFeedingDetail.visibility = if (detail.isNotBlank()) View.VISIBLE else View.GONE
        } else {
            binding.lastFeedingDetail.visibility = View.GONE
        }

        binding.lastSleepTimeAgo.text =
            BabySummaryHelper.formatTimeAgo(res, summary.lastSleepClientTime)
        binding.lastDiaperTimeAgo.text =
            BabySummaryHelper.formatTimeAgo(res, summary.lastDiaperClientTime)

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
        binding.todaySleepSummaryText.text = Phrase.from(res, R.string.summary_today_sleep_value)
            .put("hours", sleepH)
            .put("minutes", sleepM)
            .format()

        binding.todayDiaperSummaryText.text = Phrase.from(res, R.string.summary_today_diaper_value)
            .put("total", summary.todayDiaperTotal)
            .put("wet", summary.todayDiaperWet)
            .put("solid", summary.todayDiaperSolid)
            .format()
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

        child?.let { child ->
            deselected = false

            timerPollJob?.let {
                if (childChanged) {
                    it.cancel()
                }
            }
            if (!(timerPollJob?.isActive ?: false)) {
                timerPollJob = baseFragment.mainActivity.scope.launch {
                    updateTimerLoop(child)
                }
            }

            binding.chipFilterAll.isChecked = true
            renderTodaySummary(emptyList())

            childHistoryLoader = ChildEventHistoryLoader(
                fragment = baseFragment,
                container = binding.innerTimeline,
                childId = child.id,
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
                        (baseFragment as? LoggedInFragment)?.notifyChildDataChanged(child.id)
                        if (childHistoryLoader != null) {
                            if (newEntry != null) {
                                childHistoryLoader!!.addEntryToTop(newEntry)
                            }
                            childHistoryLoader!!.forceRefresh()
                        }
                    }
                },
                child,
                this
            )
        }
    }

    suspend fun updateTimerLoop(childArg: Child?) {
        if (childArg == null) {
            return
        }
        var tick = 0
        while (!deselected) {
            var newTimersResult: PaginatedResult<Timer>? = null
            try {
                newTimersResult = client.v2client.getEntries(Timer::class, childId = childArg.id)
            } catch (e: InterruptedException) {
                GlobalDebugObject.log("BabyBuddyHolder timer retrieval interrupted")
            } catch (e: RequestCodeFailure) {
                GlobalDebugObject.log("BabyBuddyHolder timer retrieval failed with code ${e.code}")
            } catch (e: HttpException) {
                GlobalDebugObject.log("BabyBuddyHolder timer retrieval failed with HTTP exception ${e.message}")
            } catch (e: IOException) {
                GlobalDebugObject.log("BabyBuddyHolder timer retrieval failed with IO exception: ${e.message}")
            }

            val disconnectInterface = baseFragment.disconnectInterface
            if (newTimersResult != null) {
                val oldTimers = mutableListOf<BabyBuddyClient.Timer>()
                for (t in newTimersResult.entries) {
                    val oldTimer = BabyBuddyClient.Timer()
                    oldTimer.id = t.id
                    oldTimer.child_id = t.childId
                    oldTimer.name = t.name
                    oldTimer.start = t.start
                    oldTimer.active = true
                    oldTimer.user_id = t.userId
                    oldTimers.add(oldTimer)
                }
                disconnectInterface.setDisconnected("child list", false)
                updateTimerList(oldTimers.toTypedArray())
            } else {
                disconnectInterface.setDisconnected("child list", true)
            }

            // Refresh "time ago" labels every 30s even if no new entry arrived
            tick++
            if (tick % 30 == 0) {
                childHistoryLoader?.let { renderTodaySummary(it.currentEntriesSnapshot()) }
            }

            kotlinx.coroutines.delay(1000.milliseconds)
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
            child?.let { child ->
                if (t.child_id != child.id) {
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
        closeButtonController()
        resetChildHistoryLoader()
        deselected = true
    }

    fun close() {
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
        child?.let { child ->
            baseFragment.mainActivity.getChildTimerControl(child.id).createNewTimer(
                timer, UpdateBufferingPromise(cb)
            )
        }
    }

    override fun startTimer(
        timer: BabyBuddyClient.Timer,
        cb: Promise<BabyBuddyClient.Timer, TranslatedException>
    ) {
        child?.let { child ->
            baseFragment.mainActivity.getChildTimerControl(child.id).startTimer(
                timer, UpdateBufferingPromise(cb)
            )
        }
    }

    override fun stopTimer(timer: BabyBuddyClient.Timer, cb: Promise<Any, TranslatedException>) {
        child?.let { child ->
            baseFragment.mainActivity.getChildTimerControl(child.id).stopTimer(
                timer, UpdateBufferingPromise(cb)
            )
        }
    }

    override fun registerTimersUpdatedCallback(callback: TimersUpdatedCallback) {
        child?.let { child ->
            if (updateTimersCallbacks.contains(callback)) {
                return
            }
            updateTimersCallbacks.add(callback)

            baseFragment.mainActivity.getChildTimerControl(child.id).registerTimersUpdatedCallback(
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
        child?.let { child ->
            val wrapped =
                baseFragment.mainActivity.getChildTimerControl(child.id).wrap as TimerControl
            if (cachedTimers != null) {
                wrapped.callTimerUpdateCallback(cachedTimers!!)
            }
        }
    }

    override fun getNotes(timer: BabyBuddyClient.Timer): CredStore.Notes {
        return child?.let { child ->
            return@let baseFragment.mainActivity.getChildTimerControl(child.id).getNotes(timer)
        } ?: CredStore.Notes("", false)
    }

    override fun setNotes(timer: BabyBuddyClient.Timer, notes: CredStore.Notes?) {
        child?.let { child ->
            baseFragment.mainActivity.getChildTimerControl(child.id).setNotes(timer, notes)
        }
    }
}
