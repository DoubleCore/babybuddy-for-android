package eu.pkgsoftware.babybuddywidgets.history

import android.graphics.PointF
import android.graphics.Rect
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import eu.pkgsoftware.babybuddywidgets.BaseFragment
import eu.pkgsoftware.babybuddywidgets.ConnectingDialogInterface
import eu.pkgsoftware.babybuddywidgets.R
import eu.pkgsoftware.babybuddywidgets.VisibilityCheck
import eu.pkgsoftware.babybuddywidgets.debugging.GlobalDebugObject
import eu.pkgsoftware.babybuddywidgets.logic.ContinuousListItem
import eu.pkgsoftware.babybuddywidgets.logic.EndAwareContinuousListIntegrator
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient
import eu.pkgsoftware.babybuddywidgets.networking.RequestCodeFailure
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.InterruptedException
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.exponentialBackoff
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.BmiEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.ChangeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.FeedingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeadCircumferenceEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeightEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.NoteEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.PumpingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.SleepEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TemperatureEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TummyTimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.WeightEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.classActivityName
import eu.pkgsoftware.babybuddywidgets.tutorial.Direction
import eu.pkgsoftware.babybuddywidgets.tutorial.Trackable
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.time.LocalDate
import kotlin.math.max
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds

val IMPLEMENTED_EVENT_CLASSES = listOf(
    FeedingEntry::class,
    SleepEntry::class,
    TummyTimeEntry::class,
    ChangeEntry::class,
    NoteEntry::class,
    PumpingEntry::class,
    TemperatureEntry::class,
    WeightEntry::class,
    HeightEntry::class,
    HeadCircumferenceEntry::class,
    BmiEntry::class,
)

enum class TimelineFilter {
    ALL,
    FEEDING,
    SLEEP,
    DIAPER,
    PUMPING,
    TUMMY_TIME,
    GROWTH_HEALTH,
    NOTES
}

interface ShowErrorPill {
    fun showErrorPill(entryType: String, exception: Exception?)
}

class ChildEventHistoryLoader(
    private val fragment: BaseFragment,
    private val container: LinearLayout,
    private val childId: Int,
    private val visibilityCheck: VisibilityCheck,
    private val progressBar: ProgressBar,
    private val errorPill: ShowErrorPill,
    private val emptyHintView: View? = null,
    private val onEntriesUpdated: ((List<TimeEntry>) -> Unit)? = null,
) {
    val HISTORY_ITEM_COUNT = max(25, 220 / IMPLEMENTED_EVENT_CLASSES.size)
    val POLL_INTERVAL = 5000

    private val activityCollectionGate = IMPLEMENTED_EVENT_CLASSES.toMutableList()
    private val scope = fragment.mainActivity.scope

    private val timeEntryLookup = mutableMapOf<ContinuousListItem, TimeEntry>()
    private val listIntegrator = EndAwareContinuousListIntegrator()
    private val currentList = mutableListOf<TimelineEntry>()
    private val removedViews = mutableListOf<TimelineEntry>()

    private var updateUiJob: Job? = null
    private var fetchJob: Job? = null

    private val queryOffsets = mutableMapOf<KClass<*>, Int>()
    private var tutorialMessageAdded = false
    private var activeFilter: TimelineFilter = TimelineFilter.ALL
    private var lastVisibleCount: Int = 0

    init {
        forceRefresh()
    }

    fun setFilter(filter: TimelineFilter) {
        if (activeFilter == filter) return
        activeFilter = filter
        applyFilterAndDateHeaders(lastVisibleCount)
    }

    fun currentEntriesSnapshot(): List<TimeEntry> {
        return timeEntryLookup.values.toList()
    }

    private fun matchesFilter(entry: TimeEntry?, filter: TimelineFilter): Boolean {
        if (entry == null) return false
        return when (filter) {
            TimelineFilter.ALL -> true
            TimelineFilter.FEEDING -> entry.appType == BabyBuddyClient.ACTIVITIES.FEEDING
            TimelineFilter.SLEEP -> entry.appType == BabyBuddyClient.ACTIVITIES.SLEEP
            TimelineFilter.DIAPER -> entry.appType == BabyBuddyClient.EVENTS.CHANGE
            TimelineFilter.PUMPING -> entry.appType == BabyBuddyClient.ACTIVITIES.PUMPING
            TimelineFilter.TUMMY_TIME -> entry.appType == BabyBuddyClient.ACTIVITIES.TUMMY_TIME
            TimelineFilter.GROWTH_HEALTH -> BabySummaryHelper.isGrowthOrHealthType(entry.appType)
            TimelineFilter.NOTES -> entry.appType == BabyBuddyClient.EVENTS.NOTE
        }
    }

    inner class BackoffConnectionInterface(val entryName: String) : ConnectingDialogInterface {
        private var retriesLeft = 3

        override fun interruptLoading(): Boolean {
            return retriesLeft <= 0
        }

        override fun showConnecting(currentTimeout: Long, error: Exception?) {
            retriesLeft--
            if (interruptLoading()) {
                errorPill.showErrorPill(entryName, error)
            }
        }

        override fun hideConnecting() {
        }
    }

    private fun startFetch() {
        val fetchJob = this.fetchJob
        if ((fetchJob == null) || (!fetchJob.isActive)) {
            this.fetchJob = scope.launch {
                IMPLEMENTED_EVENT_CLASSES.map {
                    async {
                        val activityName = classActivityName(it)
                        try {
                            val conInterface = BackoffConnectionInterface(activityName)
                            val mainActivity = fragment.mainActivity
                            val r = exponentialBackoff(conInterface) {
                                if (fragment.isDetached) {
                                    throw InterruptedException()
                                }
                                mainActivity.client.v2client.getEntries(
                                    it,
                                    offset = queryOffsets.getOrDefault(it, 0),
                                    limit = HISTORY_ITEM_COUNT,
                                    childId = childId,
                                )
                            }
                            addTimelineItems(r.offset, r.totalCount, it, r.entries)
                        } catch (e: InterruptedException) {
                            GlobalDebugObject.log("ChildEventHistoryLoader retrieval of ${it.simpleName} failed after retries")
                            addTimelineItems(0, 0, it, listOf())
                        } catch (e: RequestCodeFailure) {
                            GlobalDebugObject.log("ChildEventHistoryLoader retrieval of ${it.simpleName} failed with code ${e.code}")
                            errorPill.showErrorPill(activityName, e)
                            addTimelineItems(0, 0, it, listOf())
                        } catch (e: HttpException) {
                            GlobalDebugObject.log("ChildEventHistoryLoader retrieval of ${it.simpleName} failed with HTTP exception ${e.message}")
                            errorPill.showErrorPill(activityName, e)
                            addTimelineItems(0, 0, it, listOf())
                        } catch (e: IOException) {
                            GlobalDebugObject.log("ChildEventHistoryLoader retrieval of ${it.simpleName} failed with IO exception: ${e.message}")
                            errorPill.showErrorPill(activityName, e)
                            addTimelineItems(0, 0, it, listOf())
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun newTimelineEntry(e: TimeEntry?): TimelineEntry {
        val result = if (removedViews.size > 0) {
            removedViews.removeAt(removedViews.size - 1)
        } else {
            TimelineEntry(fragment, e)
        }
        result.entry = e
        result.setModifiedCallback {
            forceRefresh()
        }
        container.addView(result.view)
        currentList.add(result)
        return result
    }

    private fun timeEntryToContinuousListItem(e: TimeEntry): ContinuousListItem {
        val sortTimeMs = BabySummaryHelper.entryClientStart(e).time
        val result = ContinuousListItem(
            -sortTimeMs,
            e.appType,
            e.id.toString(),
        )
        timeEntryLookup[result] = e
        return result
    }

    private suspend fun addTimelineItems(
        offset: Int,
        totalCount: Int,
        type: KClass<*>,
        entries: List<TimeEntry>
    ) {
        activityCollectionGate.remove(type)

        listIntegrator.updateItemsWithCount(
            offset,
            totalCount,
            classActivityName(type),
            entries.map { timeEntryToContinuousListItem(it) }.toTypedArray()
        )

        queryOffsets[type] = listIntegrator.suggestClassQueryOffset(classActivityName(type))

        val updateUiJob = this.updateUiJob
        if ((updateUiJob == null) || (!updateUiJob.isActive)) {
            if (activityCollectionGate.isEmpty()) {
                this.updateUiJob = scope.launch {
                    updateUiJobImpl()
                }
            }
        }
    }

    private suspend fun updateUiJobImpl() {
        try {
            deferredUpdate()
        } finally {
            delay(POLL_INTERVAL.toLong().milliseconds)
            scope.launch {
                this@ChildEventHistoryLoader.updateUiJob?.join()
                startFetch()
            }
        }
    }

    private fun applyFilterAndDateHeaders(visibleCount: Int) {
        var prevDate: LocalDate? = null
        var shownCount = 0
        for (idx in currentList.indices) {
            val item = currentList[idx]
            val e = item.entry
            val shouldShow = (idx < visibleCount) && (e != null) && matchesFilter(e, activeFilter)
            if (shouldShow && e != null) {
                shownCount++
                val clientStart = BabySummaryHelper.entryClientStart(e)
                val entryLocalDate = BabySummaryHelper.toLocalDate(clientStart)
                if (entryLocalDate != prevDate) {
                    item.setDateHeader(
                        BabySummaryHelper.formatDateSectionHeader(fragment.resources, clientStart)
                    )
                    prevDate = entryLocalDate
                } else {
                    item.setDateHeader(null)
                }
                item.view.visibility = View.VISIBLE
            } else {
                item.setDateHeader(null)
                item.view.visibility = View.GONE
            }
        }
        emptyHintView?.visibility = if (shownCount == 0 && progressBar.visibility == View.GONE) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private suspend fun deferredUpdate() {
        val items = listIntegrator.items
        var i = 0
        val visibleCount = listIntegrator.computeValidCount()
        lastVisibleCount = visibleCount

        for (it in items) {
            val entry = timeEntryLookup[it]
            val listItem: TimelineEntry =
                if (i < currentList.size) {
                    currentList[i]
                } else {
                    newTimelineEntry(entry)
                }
            if (it.dirty) {
                listItem.entry = null
            } else {
                listItem.entry = entry
            }
            i++
            if (i % 50 == 0) {
                delay(50)
            }
            attemptAddingLongClickTutorialMessage()
        }
        progressBar.visibility = if (visibleCount == items.size) {
            View.GONE
        } else {
            View.VISIBLE
        }
        while (currentList.size > items.size) {
            val removed = currentList.removeAt(currentList.size - 1)
            if (removedViews.size < 128) {
                removedViews.add(removed)
            }
            container.removeView(removed.view)
        }
        applyFilterAndDateHeaders(visibleCount)
        onEntriesUpdated?.invoke(timeEntryLookup.values.toList())

        attemptAddingLongClickTutorialMessage()
        delay(500)
        updateTop()
    }

    private fun attemptAddingLongClickTutorialMessage() {
        if (tutorialMessageAdded) return
        if (container.childCount <= 0) return
        tutorialMessageAdded = true

        fragment.mainActivity.tutorialManagement.addItem(
            fragment.makeTutorialEntry(
                R.string.tutorial_long_click_notification,
                object : Trackable() {
                    override val orientation: Direction = Direction.DOWN
                    override val position: PointF?
                        get() {
                            val r = Rect()
                            container.getGlobalVisibleRect(r)
                            if (r.isEmpty) return null
                            return PointF((r.left + r.right) / 2f, r.top.toFloat())
                        }
                }
            )
        )
        fragment.mainActivity.tutorialManagement.updateArrows()
    }

    fun close() {
        updateUiJob?.cancel()
        fetchJob?.cancel()
        listIntegrator.clear()
        removedViews.clear()
        currentList.clear()
        container.removeAllViews()
        timeEntryLookup.clear()
        queryOffsets.clear()
        tutorialMessageAdded = false
    }

    fun addEntryToTop(entry: TimeEntry) {
        val cls = entry::class
        val activityName = classActivityName(cls)

        val existingForClass = listIntegrator.items.filter { it.className == activityName }.toMutableList()
        existingForClass.add(0, timeEntryToContinuousListItem(entry))

        listIntegrator.updateItemsWithCount(
            0, existingForClass.size, activityName, existingForClass.toTypedArray()
        )
        fragment.mainActivity.scope.launch {
            deferredUpdate()
        }
    }

    fun updateTop() {
        var i = 0
        val liItems = listIntegrator.items
        listIntegrator.top = null
        for (item in currentList) {
            if (i >= liItems.size) {
                break
            }
            if (item.view.visibility == View.VISIBLE && visibilityCheck.checkPartiallyVisible(item.view)) {
                listIntegrator.top = liItems[i]
                break
            }
            i++
        }
        for (cls in IMPLEMENTED_EVENT_CLASSES) {
            queryOffsets[cls] = listIntegrator.suggestClassQueryOffset(classActivityName(cls))
        }
        val visibleCount = listIntegrator.computeValidCount()
        if (i >= visibleCount - 20) {
            val numberItems = IMPLEMENTED_EVENT_CLASSES.sumOf {
                listIntegrator.getItemsCount(classActivityName(it))
            }
            if (numberItems > liItems.size) {
                if (fetchJob?.isActive != true) {
                    forceRefresh()
                }
            }
        }
    }

    fun forceRefresh() {
        updateUiJob?.cancel("forceRefresh()")
        fetchJob?.cancel("forceRefresh()")
        activityCollectionGate.clear()
        activityCollectionGate.addAll(IMPLEMENTED_EVENT_CLASSES)
        startFetch()
    }
}
