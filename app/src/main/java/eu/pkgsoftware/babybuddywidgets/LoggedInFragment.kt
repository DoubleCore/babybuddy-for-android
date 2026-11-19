package eu.pkgsoftware.babybuddywidgets

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import com.google.android.material.chip.Chip
import com.squareup.phrase.Phrase
import eu.pkgsoftware.babybuddywidgets.UpdateNotifications.Companion.showUpdateNotice
import eu.pkgsoftware.babybuddywidgets.babyflow.BabyFlowLocalStore
import eu.pkgsoftware.babybuddywidgets.babyflow.BabyFlowSheets
import eu.pkgsoftware.babybuddywidgets.babyflow.InventoryExpenseController
import eu.pkgsoftware.babybuddywidgets.babyflow.MineTabController
import eu.pkgsoftware.babybuddywidgets.backup.BackupCenterBottomSheet
import eu.pkgsoftware.babybuddywidgets.children.ChildManagerBottomSheet
import eu.pkgsoftware.babybuddywidgets.children.ChildProfileDialog
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
import kotlin.math.roundToInt

class LoggedInFragment : BaseFragment() {
    private lateinit var binding: LoggedInFragmentBinding
    private lateinit var menu: LoggedInMenu
    private lateinit var localStore: BabyFlowLocalStore

    private val emptyBabyPagerAdapter = EmptyBabyPagerAdapter()
    private var babyAdapter: BabyPagerAdapter? = null
    private var statsController: BabyStatsController? = null
    private var inventoryController: InventoryExpenseController? = null
    private var mineController: MineTabController? = null

    private lateinit var requestScheduler: RequestScheduler
    private var stateTracker: ChildrenStateTracker? = null

    private var selectedChildSlug = ""
    private var children = listOf<Child>()
    private var selectedTabIndex: Int = 0
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
        localStore = BabyFlowLocalStore(requireContext())

        binding = LoggedInFragmentBinding.inflate(inflater, container, false)

        // Apply system bar insets cleanly for edge-to-edge / gesture nav
        val baseDockMarginBottom = (14f * resources.displayMetrics.density).roundToInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.loggedInRoot) { v, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            val lp = binding.floatingBottomDock.layoutParams as? ConstraintLayout.LayoutParams
            if (lp != null) {
                lp.bottomMargin = baseDockMarginBottom + bars.bottom
                binding.floatingBottomDock.layoutParams = lp
            }
            windowInsets
        }

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

        // Profile header & onboarding actions (standalone offline child management + backup restore)
        binding.babyProfileHeaderClickArea.setOnClickListener {
            if (children.isEmpty()) {
                ChildProfileDialog.show(mainActivity) { created ->
                    selectChild(created)
                }
            } else {
                ChildManagerBottomSheet.show(mainActivity, selectedChildSlug) { chosen ->
                    selectChild(chosen)
                }
            }
        }
        binding.onboardingAddBabyButton.setOnClickListener {
            ChildProfileDialog.show(mainActivity) { created ->
                selectChild(created)
            }
        }
        binding.onboardingRestoreBackupButton.setOnClickListener {
            BackupCenterBottomSheet.show(mainActivity) {
                stateTracker?.forceNotifyListeners()
            }
        }

        // Tab 2: Growth & Stats
        val sc = BabyStatsController(this)
        statsController = sc
        binding.statsContainer.removeAllViews()
        binding.statsContainer.addView(sc.rootView)

        // Tab 3: Inventory & Expenses
        val ic = InventoryExpenseController(this, localStore)
        inventoryController = ic
        binding.inventoryContainer.removeAllViews()
        binding.inventoryContainer.addView(ic.rootView)

        // Tab 4: Mine
        val mc = MineTabController(this, localStore) { chosen ->
            selectChild(chosen)
        }
        mineController = mc
        binding.mineContainer.removeAllViews()
        binding.mineContainer.addView(mc.rootView)

        // Top Bar Actions (Vaccine, Medkit, Calendar+Search)
        binding.btnTopVaccine.setOnClickListener {
            BabyFlowSheets.showVaccineSheet(this, selectedChild(), localStore)
        }
        binding.btnTopMedkit.setOnClickListener {
            BabyFlowSheets.showMedicationSheet(this, selectedChild(), localStore) {
                selectedChild()?.let { c -> notifyChildDataChanged(c.id) }
                babyAdapter?.refreshActiveView()
            }
        }
        binding.btnTopCalendar.setOnClickListener {
            val child = selectedChild()
            val entries = if (child != null) {
                mainActivity.repository.getAllTimelineEntries(childId = child.id, limit = 0)
            } else {
                babyAdapter?.currentEntriesForChild(null).orEmpty()
            }
            BabyFlowSheets.showCalendarAndSearchSheet(this, child, entries, localStore)
        }

        // Floating Capsule Bottom Dock
        binding.dockTabToday.setOnClickListener { selectDockTab(0) }
        binding.dockTabGrowth.setOnClickListener { selectDockTab(1) }
        binding.dockTabInventory.setOnClickListener { selectDockTab(2) }
        binding.dockTabMine.setOnClickListener { selectDockTab(3) }

        applyTabVisibility(selectedTabIndex)

        return binding.root
    }

    private fun selectDockTab(index: Int) {
        selectedTabIndex = index
        applyTabVisibility(index)
    }

    private fun styleDockItem(
        tab: LinearLayout,
        icon: ImageView,
        label: TextView,
        selected: Boolean
    ) {
        val ctx = context ?: return
        if (selected) {
            tab.setBackgroundResource(R.drawable.bg_bf_nav_pill_selected)
            val activeColor = ContextCompat.getColor(ctx, R.color.text_primary_warm)
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(activeColor))
            label.setTextColor(activeColor)
            label.setTypeface(label.typeface, Typeface.BOLD)
        } else {
            tab.background = null
            val mutedColor = ContextCompat.getColor(ctx, R.color.text_secondary_warm)
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(mutedColor))
            label.setTextColor(mutedColor)
            label.setTypeface(null, Typeface.NORMAL)
        }
    }

    private fun applyTabVisibility(tabIndex: Int) {
        val hasChildren = children.isNotEmpty()
        val showToday = (tabIndex == 0)
        val showGrowth = (tabIndex == 1)
        val showInventory = (tabIndex == 2)
        val showMine = (tabIndex == 3)

        binding.todayTopBar.visibility = if (showToday) View.VISIBLE else View.GONE
        binding.babyProfileHeaderCard.visibility = if (showToday && hasChildren) View.VISIBLE else View.GONE
        binding.emptyChildrenOnboardingCard.visibility = if (showToday && !hasChildren) View.VISIBLE else View.GONE
        binding.babyViewPagerSwitcher.visibility = if (showToday && hasChildren) View.VISIBLE else View.GONE

        binding.statsContainer.visibility = if (showGrowth) View.VISIBLE else View.GONE
        binding.inventoryContainer.visibility = if (showInventory) View.VISIBLE else View.GONE
        binding.mineContainer.visibility = if (showMine) View.VISIBLE else View.GONE

        styleDockItem(binding.dockTabToday, binding.dockIconToday, binding.dockLabelToday, showToday)
        styleDockItem(binding.dockTabGrowth, binding.dockIconGrowth, binding.dockLabelGrowth, showGrowth)
        styleDockItem(binding.dockTabInventory, binding.dockIconInventory, binding.dockLabelInventory, showInventory)
        styleDockItem(binding.dockTabMine, binding.dockIconMine, binding.dockLabelMine, showMine)

        val current = selectedChild()
        statsController?.setChild(current)
        statsController?.setVisibleTab(showGrowth)
        if (showInventory) {
            inventoryController?.setChild(current)
        }
        if (showMine) {
            mineController?.updateChildren(children, current)
        }
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
                selectedChildSlug = ""
                updateTitle()
            }
            applyTabVisibility(selectedTabIndex)
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
                text = child.firstName.ifBlank { child.fullName }
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

        if (babyAdapter == null && stateTracker != null) {
            babyAdapter = run {
                val bpa = BabyPagerAdapter(stateTracker!!)
                bpa.postInit(this)
                binding.babyViewPagerSwitcher.adapter = bpa
                bpa
            }
        }

        stateTracker?.forceNotifyListeners()

        val childSlug = selectedChildSlug
        val childIndex = Child.childIndexBySlug(children.toTypedArray(), childSlug)
        val clampedIndex = max(0, childIndex)
        if (children.isNotEmpty()) {
            binding.babyViewPagerSwitcher.setCurrentItem(clampedIndex, false)
            if (childIndex >= 0) {
                babyAdapter?.activeViewChanged(children[clampedIndex])
            }
        }

        rebuildChildSwitcherChips()
        updateTitle()
        applyTabVisibility(selectedTabIndex)

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
        inventoryController = null
        mineController = null
        stateTracker?.destroy()
    }

    private fun closeAdapter() {
        babyAdapter?.close()
        babyAdapter = null
    }

    fun notifyChildDataChanged(childId: Int) {
        statsController?.invalidateCacheForChild(childId)
        inventoryController?.render()
    }

    fun refreshChildrenNow() {
        stateTracker?.forceNotifyListeners()
    }

    fun selectChildBySlugOrFirst(slug: String?) {
        val target = slug?.let { s -> children.firstOrNull { it.slug == s } } ?: children.firstOrNull()
        if (target != null) {
            selectChild(target)
        } else {
            updateTitle()
            applyTabVisibility(selectedTabIndex)
        }
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

    fun selectChild(c: Child) {
        val childIndex = Child.childIndexBySlug(children.toTypedArray(), c.slug)
        mainActivity.storage.login<String>("selected-child-slug", c.slug)
        selectedChildSlug = c.slug

        babyAdapter?.activeViewChanged(c)
        if (childIndex >= 0 && childIndex != binding.babyViewPagerSwitcher.currentItem) {
            binding.babyViewPagerSwitcher.setCurrentItem(max(0, childIndex), true)
        }

        suppressChipCallback = true
        for (i in 0 until binding.childSwitcherChips.childCount) {
            val chip = binding.childSwitcherChips.getChildAt(i) as? Chip ?: continue
            chip.isChecked = (chip.tag == c.slug)
        }
        suppressChipCallback = false

        statsController?.setChild(c)
        inventoryController?.setChild(c)
        mineController?.updateChildren(children, c)
        updateTitle()
    }

    private fun renderAvatar(child: Child?) {
        if (context == null) return
        val mediaFile = child?.picture?.let { mainActivity.repository.mediaStorage.resolveMediaFile(it) }
        val bmp = mediaFile?.let { BitmapFactory.decodeFile(it.absolutePath) }

        if (bmp != null) {
            binding.babyProfileAvatarFrame.setPadding(0, 0, 0, 0)
            binding.babyProfileAvatarFrame.clipToOutline = true
            ImageViewCompat.setImageTintList(binding.babyProfileAvatarImage, null)
            binding.babyProfileAvatarImage.scaleType = ImageView.ScaleType.CENTER_CROP
            binding.babyProfileAvatarImage.setImageBitmap(bmp)
        } else {
            binding.babyProfileAvatarFrame.setPadding(0, 0, 0, 0)
            binding.babyProfileAvatarFrame.clipToOutline = false
            ImageViewCompat.setImageTintList(binding.babyProfileAvatarImage, null)
            binding.babyProfileAvatarImage.scaleType = ImageView.ScaleType.FIT_CENTER
            binding.babyProfileAvatarImage.setImageResource(R.drawable.ic_bf_baby_avatar)
        }
    }

    private fun updateTitle() {
        binding.babyGreetingText.text = BabySummaryHelper.formatGreeting(resources)
        val child = selectedChild()
        renderAvatar(child)
        if (child == null) {
            val noChildStr = getString(R.string.logged_in_no_children_found)
            mainActivity.setTitle(noChildStr)
            binding.babyProfileName.text = noChildStr
            binding.babyProfileAgeBadge.visibility = View.GONE
            binding.babyProfileCorrectedAgeBadge.visibility = View.GONE
            binding.babyMilestoneBox.visibility = View.GONE
        } else {
            val fullName = child.fullName.ifBlank { "${child.firstName} ${child.lastName}".trim() }
            mainActivity.setTitle(fullName)
            binding.babyProfileName.text = fullName
            binding.babyProfileAgeBadge.text = BabySummaryHelper.formatBabyAge(resources, child.birthData)
            binding.babyProfileAgeBadge.visibility = View.VISIBLE

            if (child.hasCorrectedPrematureAge && child.dueDate != null) {
                val correctedStr = BabySummaryHelper.formatBabyAge(resources, child.dueDate)
                binding.babyProfileCorrectedAgeBadge.text =
                    Phrase.from(resources, R.string.child_profile_corrected_age_badge)
                        .put("age", correctedStr)
                        .format()
                binding.babyProfileCorrectedAgeBadge.visibility = View.VISIBLE
            } else {
                binding.babyProfileCorrectedAgeBadge.visibility = View.GONE
            }

            val (mTitle, mSub) = BabySummaryHelper.formatMilestoneBadge(resources, child.birthData)
            binding.babyMilestoneTitle.text = mTitle
            binding.babyMilestoneSub.text = mSub
            binding.babyMilestoneBox.visibility = View.VISIBLE
        }
        mineController?.updateChildren(children, child)
    }
}
