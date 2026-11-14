package eu.pkgsoftware.babybuddywidgets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import com.google.android.material.chip.Chip
import eu.pkgsoftware.babybuddywidgets.UpdateNotifications.Companion.showUpdateNotice
import eu.pkgsoftware.babybuddywidgets.databinding.LoggedInFragmentBinding
import eu.pkgsoftware.babybuddywidgets.history.BabySummaryHelper
import eu.pkgsoftware.babybuddywidgets.logic.ChildrenStateTracker
import eu.pkgsoftware.babybuddywidgets.logic.RequestScheduler
import eu.pkgsoftware.babybuddywidgets.login.LoggedInMenu
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child
import eu.pkgsoftware.babybuddywidgets.stats.BabyStatsController
import eu.pkgsoftware.babybuddywidgets.tutorial.TutorialManagement
import java.util.Collections
import kotlin.math.max

class LoggedInFragment : BaseFragment() {
    private lateinit var binding: LoggedInFragmentBinding
    private lateinit var menu: LoggedInMenu

    private val emptyBabyPagerAdapter = EmptyBabyPagerAdapter()
    private var babyAdapter: BabyPagerAdapter? = null
    private var statsController: BabyStatsController? = null

    private lateinit var requestScheduler: RequestScheduler
    private var stateTracker: ChildrenStateTracker? = null

    private var selectedChildSlug = ""
    private var children = listOf<Child>()
    private var selectedNavTabId: Int = R.id.nav_record
    private var suppressChipCallback: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setRetainInstance(true)
        selectedChildSlug = mainActivity.storage.login<String>("selected-child-slug") ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        menu = LoggedInMenu(this)

        binding = LoggedInFragmentBinding.inflate(inflater, container, false)
        binding.babyViewPagerSwitcher.registerOnPageChangeCallback(object :
            OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                var pos = position
                if (pos >= children.size) {
                    pos = children.size - 1
                }
                if (pos < 0) {
                    pos = 0
                }
                if (children.isNotEmpty()) {
                    selectChild(children[pos])
                }

                super.onPageSelected(pos)
            }
        })

        binding.babyViewPagerSwitcher.adapter = emptyBabyPagerAdapter

        val sc = BabyStatsController(this)
        statsController = sc
        binding.statsContainer.removeAllViews()
        binding.statsContainer.addView(sc.rootView)

        binding.bottomNavigation.selectedItemId = selectedNavTabId
        applyTabVisibility(selectedNavTabId)
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            selectedNavTabId = item.itemId
            applyTabVisibility(item.itemId)
            true
        }

        return binding.root
    }

    private fun applyTabVisibility(tabId: Int) {
        val showStats = (tabId == R.id.nav_stats)
        binding.babyViewPagerSwitcher.visibility = if (showStats) View.GONE else View.VISIBLE
        binding.statsContainer.visibility = if (showStats) View.VISIBLE else View.GONE
        statsController?.setChild(selectedChild())
        statsController?.setVisibleTab(showStats)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requestScheduler = RequestScheduler(disconnectInterface)
        stateTracker = ChildrenStateTracker(
            mainActivity.client.v2client, mainActivity.storage, requestScheduler, this.disconnectInterface
        )
        stateTracker!!.addChildListener({ childrenArr: Array<Child> ->
            this.children = Collections.unmodifiableList(childrenArr.toList())
            rebuildChildSwitcherChips()

            if (this.children.isNotEmpty()) {
                var matched: Child? = null
                for (c in this.children) {
                    if (c.slug == selectedChildSlug) {
                        matched = c
                        break
                    }
                }
                selectChild(matched ?: this.children[0])
            } else {
                updateTitle()
            }
        }, true)
    }

    private fun rebuildChildSwitcherChips() {
        val chipGroup = binding.childSwitcherChips
        chipGroup.removeAllViews()
        if (children.size <= 1) {
            binding.childSwitcherScroll.visibility = View.GONE
            return
        }
        binding.childSwitcherScroll.visibility = View.VISIBLE
        val ctx = context ?: return
        suppressChipCallback = true
        for (child in children) {
            val chip = Chip(ctx).apply {
                id = View.generateViewId()
                text = child.firstName.ifBlank { "${child.firstName} ${child.lastName}".trim() }
                isCheckable = true
                isChecked = (child.slug == selectedChildSlug)
                setOnClickListener {
                    if (!suppressChipCallback) {
                        selectChild(child)
                    }
                }
                tag = child.slug
            }
            chipGroup.addView(chip)
        }
        suppressChipCallback = false
    }

    override fun setupTutorialMessages(m: TutorialManagement) {
    }

    override fun onResume() {
        super.onResume()

        requestScheduler.startScheduler()

        mainActivity.addMenuProvider(menu)

        if (babyAdapter == null) {
            babyAdapter = run {
                val bpa = BabyPagerAdapter(stateTracker!!)
                bpa.postInit(this)
                binding.babyViewPagerSwitcher.adapter = bpa
                bpa
            }
        }

        val childSlug = selectedChildSlug
        val childIndex = Child.childIndexBySlug(children.toTypedArray(), childSlug)
        val clampedIndex = max(0, childIndex)
        binding.babyViewPagerSwitcher.setCurrentItem(clampedIndex, false)
        if (childIndex >= 0) {
            babyAdapter?.activeViewChanged(children[clampedIndex])
        }

        rebuildChildSwitcherChips()
        updateTitle()
        applyTabVisibility(selectedNavTabId)

        showUpdateNotice(this)
    }

    override fun onPause() {
        super.onPause()

        requestScheduler.stopScheduler()

        mainActivity.removeMenuProvider(menu)
        mainActivity.invalidateOptionsMenu()

        progressDialog.hide()
        mainActivity.credStore.storePrefs()

        statsController?.close()
        binding.babyViewPagerSwitcher.adapter = emptyBabyPagerAdapter
        closeAdapter()
    }

    override fun onDetach() {
        super.onDetach()
        stateTracker = null
    }

    override fun onDestroy() {
        super.onDestroy()
        statsController?.close()
        statsController = null
        stateTracker?.destroy()
    }

    private fun closeAdapter() {
        babyAdapter?.close()
        babyAdapter = null
    }

    fun notifyChildDataChanged(childId: Int) {
        statsController?.invalidateCacheForChild(childId)
    }

    private fun selectedChild(): Child? {
        val childCount = children.size
        if (childCount == 0) return null
        val bySlug = Child.childIndexBySlug(children.toTypedArray(), selectedChildSlug)
        if (bySlug in 0 until childCount) {
            return children[bySlug]
        }
        val pagerIdx = binding.babyViewPagerSwitcher.currentItem
        if (pagerIdx in 0 until childCount) {
            return children[pagerIdx]
        }
        return children[0]
    }

    private fun selectChild(c: Child) {
        val childIndex = Child.childIndexBySlug(children.toTypedArray(), c.slug)
        mainActivity.storage.login<String>("selected-child-slug", c.slug)
        selectedChildSlug = c.slug

        babyAdapter?.activeViewChanged(c)
        if (childIndex >= 0 && childIndex != binding.babyViewPagerSwitcher.currentItem) {
            binding.babyViewPagerSwitcher.setCurrentItem(max(0, childIndex), true)
        }

        // Sync multi-child switcher chips
        suppressChipCallback = true
        for (i in 0 until binding.childSwitcherChips.childCount) {
            val chip = binding.childSwitcherChips.getChildAt(i) as? Chip ?: continue
            chip.isChecked = (chip.tag == c.slug)
        }
        suppressChipCallback = false

        statsController?.setChild(c)
        updateTitle()
    }

    private fun updateTitle() {
        val child = selectedChild()
        if (child == null) {
            val noChildStr = getString(R.string.logged_in_no_children_found)
            mainActivity.setTitle(noChildStr)
            binding.babyProfileName.text = noChildStr
            binding.babyProfileAgeBadge.visibility = View.GONE
        } else {
            val fullName = "${child.firstName} ${child.lastName}".trim()
            mainActivity.setTitle(fullName)
            binding.babyProfileName.text = fullName
            binding.babyProfileAgeBadge.text = BabySummaryHelper.formatBabyAge(resources, child.birthData)
            binding.babyProfileAgeBadge.visibility = View.VISIBLE
        }
    }
}
