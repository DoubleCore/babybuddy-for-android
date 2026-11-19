package eu.pkgsoftware.babybuddywidgets.history

import android.graphics.PointF
import android.graphics.Rect
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import eu.pkgsoftware.babybuddywidgets.BaseFragment
import eu.pkgsoftware.babybuddywidgets.R
import eu.pkgsoftware.babybuddywidgets.VisibilityCheck
import eu.pkgsoftware.babybuddywidgets.local.LocalBabyBuddyRepository
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient
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
import eu.pkgsoftware.babybuddywidgets.tutorial.Direction
import eu.pkgsoftware.babybuddywidgets.tutorial.Trackable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

val IMPLEMENTED_EVENT_CLASSES = listOf(
    FeedingEntry::class,
    SleepEntry::class,
    TummyTimeEntry::class,
    ChangeEntry::class,
    NoteEntry::class,
    PumpingEntry::class,
    MedicationEntry::class,
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
    MEDICATION,
    GROWTH_HEALTH,
    NOTES,
}

interface ShowErrorPill {
    fun showErrorPill(entryType: String, exception: Exception?)
}

/**
 * Pure local, event-driven timeline loader across all 12 Baby Buddy record types,
 * supporting category chips, colored tag filtering, keyword search, and progressive scroll loading.
 */
class ChildEventHistoryLoader(
    private val fragment: BaseFragment,
    private val container: LinearLayout,
    private val childId: Int,
    private val visibilityCheck: VisibilityCheck,
    private val progressBar: ProgressBar,
    private val errorPill: ShowErrorPill,
    private val emptyHintView: View? = null,
    private val onEntriesUpdated: ((List<TimeEntry>) -> Unit)? = null,
    private val onEditEntryRequested: ((TimeEntry) -> Unit)? = null,
) {
    private val scope = fragment.mainActivity.scope
    private val repository: LocalBabyBuddyRepository = fragment.mainActivity.repository

    private val currentList = mutableListOf<TimelineEntry>()
    private val removedViews = mutableListOf<TimelineEntry>()
    private var allChildEntries: List<TimeEntry> = emptyList()

    private var activeFilter: TimelineFilter = TimelineFilter.ALL
    private var activeTagFilter: String? = null
    private var activeSearchQuery: String = ""
    private var displayLimit: Int = 120
    private var loadJob: Job? = null
    private var tutorialMessageAdded = false
    private var closed = false

    private val repoChangeListener = LocalBabyBuddyRepository.ChangeListener {
        if (!closed) {
            scope.launch {
                reloadFromRepository()
            }
        }
    }

    init {
        repository.addChangeListener(repoChangeListener)
        forceRefresh()
    }

    fun setFilter(filter: TimelineFilter) {
        if (activeFilter == filter) return
        activeFilter = filter
        renderFilteredList()
    }

    fun setTagFilter(tagNameOrNull: String?) {
        val cleaned = tagNameOrNull?.trim()?.takeIf { it.isNotEmpty() }
        if (activeTagFilter == cleaned) return
        activeTagFilter = cleaned
        renderFilteredList()
    }

    fun getTagFilter(): String? = activeTagFilter

    fun setSearchQuery(query: String) {
        val cleaned = query.trim()
        if (activeSearchQuery == cleaned) return
        activeSearchQuery = cleaned
        renderFilteredList()
    }

    fun currentEntriesSnapshot(): List<TimeEntry> = allChildEntries

    private fun matchesCategoryFilter(entry: TimeEntry, filter: TimelineFilter): Boolean {
        return when (filter) {
            TimelineFilter.ALL -> true
            TimelineFilter.FEEDING -> entry.appType == BabyBuddyClient.ACTIVITIES.FEEDING
            TimelineFilter.SLEEP -> entry.appType == BabyBuddyClient.ACTIVITIES.SLEEP
            TimelineFilter.DIAPER -> entry.appType == BabyBuddyClient.EVENTS.CHANGE
            TimelineFilter.PUMPING -> entry.appType == BabyBuddyClient.ACTIVITIES.PUMPING
            TimelineFilter.TUMMY_TIME -> entry.appType == BabyBuddyClient.ACTIVITIES.TUMMY_TIME
            TimelineFilter.MEDICATION -> entry.appType == BabyBuddyClient.EVENTS.MEDICATION
            TimelineFilter.GROWTH_HEALTH -> BabySummaryHelper.isGrowthOrHealthType(entry.appType)
            TimelineFilter.NOTES -> entry.appType == BabyBuddyClient.EVENTS.NOTE
        }
    }

    private fun matchesTagAndSearch(entry: TimeEntry): Boolean {
        val tagFilter = activeTagFilter
        if (tagFilter != null) {
            val matchedTag = entry.tags.any { it.equals(tagFilter, ignoreCase = true) }
            if (!matchedTag) return false
        }
        val q = activeSearchQuery.lowercase()
        if (q.isNotEmpty()) {
            val inNotes = entry.notes.lowercase().contains(q)
            val inTags = entry.tags.any { it.lowercase().contains(q) }
            val inSpecific = when (entry) {
                is FeedingEntry -> entry.feedingType.post_name.contains(q) || entry.feedingMethod.post_name.contains(q)
                is ChangeEntry -> entry.color.lowercase().contains(q)
                is TummyTimeEntry -> entry.milestone.lowercase().contains(q) || (entry.extraNotes?.lowercase()?.contains(q) == true)
                is MedicationEntry -> entry.name.lowercase().contains(q) || entry.dosageUnit.post_name.contains(q)
                else -> false
            }
            if (!inNotes && !inTags && !inSpecific) return false
        }
        return true
    }

    private suspend fun reloadFromRepository() {
        if (closed || fragment.isDetached) return
        progressBar.visibility = View.VISIBLE
        val (loadedEntries, tagMap) = withContext(Dispatchers.IO) {
            val entries = repository.getUnifiedTimeline(
                childId = childId,
                limit = 0 // Load full child history for accurate today summary + instant local filtering
            )
            val tags = repository.getTagColorMap()
            entries to tags
        }
        if (closed || fragment.isDetached) return
        allChildEntries = loadedEntries
        progressBar.visibility = View.GONE
        onEntriesUpdated?.invoke(allChildEntries)
        renderFilteredList(tagMap)
    }

    private fun renderFilteredList(preloadedTagMap: Map<String, eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TagEntry>? = null) {
        if (closed || fragment.isDetached || fragment.context == null) return
        val tagMap = preloadedTagMap ?: repository.getTagColorMap()

        val filtered = allChildEntries.filter { e ->
            matchesCategoryFilter(e, activeFilter) && matchesTagAndSearch(e)
        }
        val shownSlice = if (filtered.size > displayLimit) filtered.subList(0, displayLimit) else filtered

        var prevDate: LocalDate? = null
        for (i in shownSlice.indices) {
            val e = shownSlice[i]
            val itemView = if (i < currentList.size) {
                currentList[i]
            } else {
                newTimelineEntry(e)
            }
            itemView.tagColorLookup = tagMap
            itemView.onEditClickListener = onEditEntryRequested
            itemView.entry = e

            val clientStart = BabySummaryHelper.entryClientStart(e)
            val entryLocalDate = BabySummaryHelper.toLocalDate(clientStart)
            if (entryLocalDate != prevDate) {
                itemView.setDateHeader(
                    BabySummaryHelper.formatDateSectionHeader(fragment.resources, clientStart)
                )
                prevDate = entryLocalDate
            } else {
                itemView.setDateHeader(null)
            }
            itemView.view.visibility = View.VISIBLE
        }

        while (currentList.size > shownSlice.size) {
            val removed = currentList.removeAt(currentList.size - 1)
            if (removedViews.size < 128) {
                removedViews.add(removed)
            }
            container.removeView(removed.view)
        }

        emptyHintView?.visibility = if (shownSlice.isEmpty() && progressBar.visibility == View.GONE) {
            View.VISIBLE
        } else {
            View.GONE
        }

        attemptAddingLongClickTutorialMessage()
    }

    private fun newTimelineEntry(e: TimeEntry?): TimelineEntry {
        val result = if (removedViews.isNotEmpty()) {
            removedViews.removeAt(removedViews.size - 1)
        } else {
            TimelineEntry(fragment, e)
        }
        result.setModifiedCallback {
            (fragment as? eu.pkgsoftware.babybuddywidgets.LoggedInFragment)?.notifyChildDataChanged(childId)
            forceRefresh()
        }
        container.addView(result.view)
        currentList.add(result)
        return result
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

    fun addEntryToTop(entry: TimeEntry) {
        forceRefresh()
    }

    fun updateTop() {
        if (closed) return
        // Progressively expand visible items when user scrolls near the bottom
        if (allChildEntries.size > displayLimit && currentList.isNotEmpty()) {
            val lastItem = currentList.last()
            if (visibilityCheck.checkPartiallyVisible(lastItem.view)) {
                displayLimit += 120
                renderFilteredList()
            }
        }
    }

    fun forceRefresh() {
        loadJob?.cancel()
        loadJob = scope.launch {
            reloadFromRepository()
        }
    }

    fun close() {
        closed = true
        repository.removeChangeListener(repoChangeListener)
        loadJob?.cancel()
        removedViews.clear()
        currentList.clear()
        container.removeAllViews()
        allChildEntries = emptyList()
        tutorialMessageAdded = false
    }
}
