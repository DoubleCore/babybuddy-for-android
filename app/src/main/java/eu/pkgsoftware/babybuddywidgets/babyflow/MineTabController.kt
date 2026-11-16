package eu.pkgsoftware.babybuddywidgets.babyflow

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.navigation.Navigation
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import eu.pkgsoftware.babybuddywidgets.BaseFragment
import eu.pkgsoftware.babybuddywidgets.R
import eu.pkgsoftware.babybuddywidgets.debugging.GlobalDebugObject
import eu.pkgsoftware.babybuddywidgets.history.BabySummaryHelper
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child
import kotlin.math.roundToInt

class MineTabController(
    private val fragment: BaseFragment,
    private val store: BabyFlowLocalStore,
    private val onSelectChild: (Child) -> Unit
) {
    private val ctx = fragment.requireContext()
    val rootView: View

    private val babyNameTv: TextView
    private val babyAgeTv: TextView
    private val serverUrlTv: TextView
    private val childChipGroup: ChipGroup
    private val childSwitcherSection: LinearLayout

    private var children: List<Child> = emptyList()
    private var selectedChild: Child? = null

    private fun Int.dp(): Int = (this * fragment.resources.displayMetrics.density).roundToInt()

    init {
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.surface_background))
            setPadding(16.dp(), 10.dp(), 16.dp(), 108.dp())
        }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Top Title
        col.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.bf_nav_mine)
            gravity = Gravity.CENTER
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
            setPadding(0, 2.dp(), 0, 14.dp())
        })

        // Baby & Server Status Card
        val profileCard = MaterialCardView(ctx).apply {
            radius = 22.dp().toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_surface))
            strokeColor = ContextCompat.getColor(ctx, R.color.card_stroke)
            strokeWidth = 1.dp()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14.dp() }
        }
        val profileInner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
        }
        val profileRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        profileRow.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_bf_baby_avatar)
            layoutParams = LinearLayout.LayoutParams(54.dp(), 54.dp()).apply { marginEnd = 12.dp() }
        })
        val pTextCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        babyNameTv = TextView(ctx).apply {
            text = ctx.getString(R.string.logged_in_no_children_found)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
        }
        babyAgeTv = TextView(ctx).apply {
            text = ""
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
        }
        serverUrlTv = TextView(ctx).apply {
            val url = fragment.mainActivity.credStore.serverUrl ?: ""
            text = "☁ $url"
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.bf_purple_primary))
            setPadding(0, 3.dp(), 0, 0)
        }
        pTextCol.addView(babyNameTv)
        pTextCol.addView(babyAgeTv)
        pTextCol.addView(serverUrlTv)
        profileRow.addView(pTextCol)
        profileInner.addView(profileRow)

        childSwitcherSection = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 12.dp(), 0, 0)
            addView(TextView(ctx).apply {
                text = ctx.getString(R.string.bf_mine_switch_child)
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_muted_warm))
            })
        }
        childChipGroup = ChipGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        childSwitcherSection.addView(childChipGroup)
        profileInner.addView(childSwitcherSection)

        profileCard.addView(profileInner)
        col.addView(profileCard)

        // Quick Feature Cards (Vaccine Plan + Medication Log)
        val quickCard = MaterialCardView(ctx).apply {
            radius = 22.dp().toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_surface))
            strokeColor = ContextCompat.getColor(ctx, R.color.card_stroke)
            strokeWidth = 1.dp()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14.dp() }
        }
        val quickCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp(), 6.dp(), 8.dp(), 6.dp())
        }
        quickCol.addView(makeMenuRow(
            iconRes = R.drawable.ic_bf_syringe,
            badgeBgRes = R.color.bf_cell_sleep,
            title = ctx.getString(R.string.bf_vaccine_title),
            subtitle = ctx.getString(R.string.bf_vaccine_checklist)
        ) {
            BabyFlowSheets.showVaccineSheet(fragment, selectedChild, store)
        })
        quickCol.addView(makeDivider())
        quickCol.addView(makeMenuRow(
            iconRes = R.drawable.ic_bf_medkit,
            badgeBgRes = R.color.bf_cell_supplement,
            title = ctx.getString(R.string.bf_med_sheet_title),
            subtitle = ctx.getString(R.string.bf_mine_med_subtitle)
        ) {
            BabyFlowSheets.showMedicationSheet(fragment, selectedChild, store)
        })
        quickCard.addView(quickCol)
        col.addView(quickCard)

        // App Settings, Help, About, Contact, Logout Card
        val sysCard = MaterialCardView(ctx).apply {
            radius = 22.dp().toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_surface))
            strokeColor = ContextCompat.getColor(ctx, R.color.card_stroke)
            strokeWidth = 1.dp()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14.dp() }
        }
        val sysCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp(), 6.dp(), 8.dp(), 6.dp())
        }

        sysCol.addView(makeMenuRow(
            iconRes = R.drawable.ic_bf_3d_care,
            badgeBgRes = R.color.bf_cell_care,
            title = ctx.getString(R.string.action_settings),
            subtitle = "${ctx.getString(R.string.setting_language)} · ${ctx.getString(R.string.setting_dark_light_mode)}"
        ) {
            fragment.view?.let { Navigation.findNavController(it).navigate(R.id.action_global_settingsFragment) }
        })
        sysCol.addView(makeDivider())

        sysCol.addView(makeMenuRow(
            iconRes = R.drawable.ic_bf_3d_edu,
            badgeBgRes = R.color.bf_cell_edu,
            title = ctx.getString(R.string.showHelpMenuButtonTitle),
            subtitle = ctx.getString(R.string.help_title)
        ) {
            fragment.view?.let { Navigation.findNavController(it).navigate(R.id.global_showHelp) }
        })
        sysCol.addView(makeDivider())

        sysCol.addView(makeMenuRow(
            iconRes = R.drawable.ic_bf_sparkles,
            badgeBgRes = R.color.bf_cell_feeding,
            title = ctx.getString(R.string.about_page_title),
            subtitle = ctx.getString(R.string.send_message_send_title)
        ) {
            fragment.view?.let { Navigation.findNavController(it).navigate(R.id.global_aboutFragment) }
        })

        if (GlobalDebugObject.ENABLED) {
            sysCol.addView(makeDivider())
            sysCol.addView(makeMenuRow(
                iconRes = R.drawable.ic_bf_search,
                badgeBgRes = R.color.bf_cell_sleep,
                title = ctx.getString(R.string.export_debug_logs_menu_item),
                subtitle = ""
            ) {
                fragment.view?.let { Navigation.findNavController(it).navigate(R.id.action_global_debugLogDisplay) }
            })
        }

        sysCard.addView(sysCol)
        col.addView(sysCard)

        // Logout Pill Button
        val logoutBtn = TextView(ctx).apply {
            text = ctx.getString(R.string.logoutMenu)
            gravity = Gravity.CENTER
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_bf_overview_cell)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.danger_soft_bg)
            )
            setTextColor(ContextCompat.getColor(ctx, R.color.danger_soft_fg))
            setPadding(16.dp(), 13.dp(), 16.dp(), 13.dp())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val v = fragment.view ?: return@setOnClickListener
                fragment.mainActivity.logout()
                Navigation.findNavController(v).navigate(R.id.logoutOperation)
            }
        }
        col.addView(logoutBtn)

        scroll.addView(col)
        rootView = scroll
    }

    fun updateChildren(allChildren: List<Child>, active: Child?) {
        this.children = allChildren
        this.selectedChild = active

        serverUrlTv.text = "☁ ${fragment.mainActivity.credStore.serverUrl ?: ""}"
        if (active == null) {
            babyNameTv.text = ctx.getString(R.string.logged_in_no_children_found)
            babyAgeTv.text = ""
        } else {
            babyNameTv.text = "${active.firstName} ${active.lastName}".trim()
            babyAgeTv.text = BabySummaryHelper.formatBabyAge(fragment.resources, active.birthData)
        }

        childChipGroup.removeAllViews()
        if (allChildren.size <= 1) {
            childSwitcherSection.visibility = View.GONE
        } else {
            childSwitcherSection.visibility = View.VISIBLE
            for (c in allChildren) {
                val chip = Chip(ctx).apply {
                    text = c.firstName.ifBlank { "${c.firstName} ${c.lastName}".trim() }
                    isCheckable = true
                    isChecked = (c.slug == active?.slug)
                    setOnClickListener { onSelectChild(c) }
                }
                childChipGroup.addView(chip)
            }
        }
    }

    private fun makeDivider(): View {
        return View(ctx).apply {
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.card_stroke))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = 52.dp()
                marginEnd = 10.dp()
            }
        }
    }

    private fun makeMenuRow(
        iconRes: Int,
        badgeBgRes: Int,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp(), 12.dp(), 10.dp(), 12.dp())
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(FrameLayout(ctx).apply {
                val s = 38.dp()
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = 12.dp() }
                setBackgroundResource(R.drawable.bg_bf_overview_cell)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, badgeBgRes)
                )
                setPadding(7.dp(), 7.dp(), 7.dp(), 7.dp())
                addView(ImageView(ctx).apply { setImageResource(iconRes) })
            })

            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = title
                    textSize = 14.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
                })
                if (subtitle.isNotBlank()) {
                    addView(TextView(ctx).apply {
                        text = subtitle
                        textSize = 11.5f
                        setTextColor(ContextCompat.getColor(ctx, R.color.text_muted_warm))
                    })
                }
            })

            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_bf_chevron_right)
                layoutParams = LinearLayout.LayoutParams(18.dp(), 18.dp())
            })
        }
    }
}
