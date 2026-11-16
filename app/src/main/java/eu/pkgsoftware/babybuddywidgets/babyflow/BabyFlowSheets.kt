package eu.pkgsoftware.babybuddywidgets.babyflow

import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.squareup.phrase.Phrase
import eu.pkgsoftware.babybuddywidgets.BaseFragment
import eu.pkgsoftware.babybuddywidgets.R
import eu.pkgsoftware.babybuddywidgets.history.BabySummaryHelper
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.ChangeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.FeedingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.NoteEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.PumpingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.SleepEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TemperatureEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TummyTimeEntry
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object BabyFlowSheets {

    private fun Int.dp(fragment: BaseFragment): Int =
        (this * fragment.resources.displayMetrics.density).roundToInt()

    private fun makeFullSheet(fragment: BaseFragment): Pair<BottomSheetDialog, LinearLayout> {
        val ctx = fragment.requireContext()
        val dialog = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.surface_background))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (fragment.resources.displayMetrics.heightPixels * 0.90f).toInt()
            )
            setPadding(16.dp(fragment), 14.dp(fragment), 16.dp(fragment), 20.dp(fragment))
        }
        dialog.setContentView(root)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return Pair(dialog, root)
    }

    private fun addTopBar(
        fragment: BaseFragment,
        parent: LinearLayout,
        title: String,
        onClose: () -> Unit,
        rightActionView: View? = null
    ) {
        val ctx = fragment.requireContext()
        val row = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12.dp(fragment) }
        }

        val closeBtn = TextView(ctx).apply {
            text = ctx.getString(R.string.bf_action_close)
            setTextColor(ContextCompat.getColor(ctx, R.color.bf_purple_primary))
            textSize = 13.5f
            setBackgroundResource(R.drawable.bg_bf_top_capsule)
            setPadding(14.dp(fragment), 7.dp(fragment), 14.dp(fragment), 7.dp(fragment))
            setOnClickListener { onClose() }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            )
        }
        row.addView(closeBtn)

        val titleTv = TextView(ctx).apply {
            text = title
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        row.addView(titleTv)

        if (rightActionView != null) {
            rightActionView.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            )
            row.addView(rightActionView)
        }
        parent.addView(row)
    }

    // ===================== 1. MULTI-LANE TIMELINE SHEET (image_7) =====================

    fun showDailyTimelineSheet(
        fragment: BaseFragment,
        entries: List<TimeEntry>,
        initialDate: LocalDate = LocalDate.now()
    ) {
        val ctx = fragment.requireContext()
        val (dialog, root) = makeFullSheet(fragment)
        var selectedDate = initialDate

        val dateLabel = TextView(ctx).apply {
            text = selectedDate.toString()
            setTextColor(ContextCompat.getColor(ctx, R.color.bf_purple_primary))
            textSize = 12.5f
            setBackgroundResource(R.drawable.bg_bf_timeline_pill)
            setPadding(12.dp(fragment), 6.dp(fragment), 12.dp(fragment), 6.dp(fragment))
        }

        addTopBar(
            fragment = fragment,
            parent = root,
            title = ctx.getString(R.string.bf_daily_timeline_title),
            onClose = { dialog.dismiss() },
            rightActionView = dateLabel
        )

        val daySwitchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp(fragment) }
        }

        val timelineView = MultiLaneTimelineView(ctx)

        fun refreshDay() {
            dateLabel.text = selectedDate.toString()
            timelineView.setDayEntries(entries, selectedDate)
        }

        val prevBtn = TextView(ctx).apply {
            text = "‹ " + ctx.getString(R.string.bf_prev_day)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
            textSize = 13f
            setPadding(14.dp(fragment), 6.dp(fragment), 14.dp(fragment), 6.dp(fragment))
            setOnClickListener {
                selectedDate = selectedDate.minusDays(1)
                refreshDay()
            }
        }
        val todayBtn = TextView(ctx).apply {
            text = ctx.getString(R.string.timeline_date_today)
            setTextColor(ContextCompat.getColor(ctx, R.color.bf_purple_primary))
            setTypeface(typeface, Typeface.BOLD)
            textSize = 13f
            setPadding(16.dp(fragment), 6.dp(fragment), 16.dp(fragment), 6.dp(fragment))
            setOnClickListener {
                selectedDate = LocalDate.now()
                refreshDay()
            }
        }
        val nextBtn = TextView(ctx).apply {
            text = ctx.getString(R.string.bf_next_day) + " ›"
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
            textSize = 13f
            setPadding(14.dp(fragment), 6.dp(fragment), 14.dp(fragment), 6.dp(fragment))
            setOnClickListener {
                selectedDate = selectedDate.plusDays(1)
                refreshDay()
            }
        }
        daySwitchRow.addView(prevBtn)
        daySwitchRow.addView(todayBtn)
        daySwitchRow.addView(nextBtn)
        root.addView(daySwitchRow)

        val card = MaterialCardView(ctx).apply {
            radius = 22.dp(fragment).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_surface))
            strokeColor = ContextCompat.getColor(ctx, R.color.card_stroke)
            strokeWidth = 1.dp(fragment)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setPadding(6.dp(fragment), 10.dp(fragment), 6.dp(fragment), 16.dp(fragment))
        }
        scroll.addView(timelineView)
        card.addView(scroll)
        root.addView(card)

        refreshDay()
        dialog.show()
    }

    // ===================== 2. VACCINE PLAN & LIBRARY SHEET (image_4) =====================

    fun showVaccineSheet(
        fragment: BaseFragment,
        child: Child?,
        store: BabyFlowLocalStore
    ) {
        val ctx = fragment.requireContext()
        val childId = child?.id ?: 0
        val birthLocal = child?.birthData?.let {
            Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault()).toLocalDate()
        } ?: LocalDate.now().minusMonths(3)

        val (dialog, root) = makeFullSheet(fragment)

        var showLibraryTab = false
        var statusFilter = 0 // 0=All, 1=Pending, 2=Completed

        val contentContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val addBtn = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_bf_plus)
            setBackgroundResource(R.drawable.bg_bf_circle_btn)
            val s = 36.dp(fragment)
            layoutParams = ViewGroup.LayoutParams(s, s)
            setPadding(8.dp(fragment), 8.dp(fragment), 8.dp(fragment), 8.dp(fragment))
        }

        addTopBar(
            fragment = fragment,
            parent = root,
            title = ctx.getString(R.string.bf_vaccine_title),
            onClose = { dialog.dismiss() },
            rightActionView = addBtn
        )

        // Segmented toggle: 接种记录 | 疫苗库
        val segRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_bf_segment_track)
            setPadding(4.dp(fragment), 4.dp(fragment), 4.dp(fragment), 4.dp(fragment))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12.dp(fragment) }
        }
        val tabPlan = TextView(ctx).apply {
            text = ctx.getString(R.string.bf_vaccine_tab_plan)
            gravity = Gravity.CENTER
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 9.dp(fragment), 0, 9.dp(fragment))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tabLib = TextView(ctx).apply {
            text = ctx.getString(R.string.bf_vaccine_tab_library)
            gravity = Gravity.CENTER
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 9.dp(fragment), 0, 9.dp(fragment))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        segRow.addView(tabPlan)
        segRow.addView(tabLib)
        root.addView(segRow)

        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        scroll.addView(contentContainer)
        root.addView(scroll)

        fun render() {
            if (showLibraryTab) {
                tabLib.setBackgroundResource(R.drawable.bg_bf_purple_pill)
                tabLib.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                tabPlan.background = null
                tabPlan.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
            } else {
                tabPlan.setBackgroundResource(R.drawable.bg_bf_purple_pill)
                tabPlan.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                tabLib.background = null
                tabLib.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
            }

            contentContainer.removeAllViews()
            val vaccines = store.getVaccines(childId)
            val pending = vaccines.filter { it.completedDate == null }
            val done = vaccines.filter { it.completedDate != null }

            // Summary Banner Card ("小宝 的疫苗清单 · 下一针...")
            val banner = MaterialCardView(ctx).apply {
                radius = 18.dp(fragment).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_surface))
                strokeColor = ContextCompat.getColor(ctx, R.color.card_stroke)
                strokeWidth = 1.dp(fragment)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10.dp(fragment) }
            }
            val bannerRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14.dp(fragment), 12.dp(fragment), 14.dp(fragment), 12.dp(fragment))
            }
            val iconBox = FrameLayout(ctx).apply {
                val s = 40.dp(fragment)
                layoutParams = LinearLayout.LayoutParams(s, s)
                setBackgroundResource(R.drawable.bg_bf_timeline_pill)
                setPadding(8.dp(fragment), 8.dp(fragment), 8.dp(fragment), 8.dp(fragment))
                addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_bf_syringe) })
            }
            bannerRow.addView(iconBox)

            val nextVaccine = pending.minByOrNull { it.recommendedMonthAge }
            val nextText = if (nextVaccine != null) {
                val due = BabyFlowLocalStore.computeDueDate(birthLocal, nextVaccine.recommendedMonthAge)
                val days = BabyFlowLocalStore.daysUntil(due).coerceAtLeast(0)
                "${ctx.getString(R.string.bf_vaccine_next_prefix)}${nextVaccine.name} · $due (${days}${ctx.getString(R.string.bf_days_unit)})"
            } else {
                ctx.getString(R.string.bf_vaccine_all_done)
            }

            val bannerCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 12.dp(fragment)
                }
                addView(TextView(ctx).apply {
                    val babyName = child?.firstName?.ifBlank { ctx.getString(R.string.bf_default_baby_name) }
                        ?: ctx.getString(R.string.bf_default_baby_name)
                    text = "$babyName · ${ctx.getString(R.string.bf_vaccine_checklist)}"
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
                    textSize = 14.5f
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(ctx).apply {
                    text = nextText
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
                    textSize = 12f
                })
            }
            bannerRow.addView(bannerCol)
            banner.addView(bannerRow)
            contentContainer.addView(banner)

            if (!showLibraryTab) {
                // Status Filter Pills: 进行中 | 已完成 | 全部
                val filterRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 10.dp(fragment) }
                }
                val filters = listOf(
                    Triple(1, ctx.getString(R.string.bf_vaccine_in_progress), pending.size),
                    Triple(2, ctx.getString(R.string.bf_vaccine_completed), done.size),
                    Triple(0, ctx.getString(R.string.filter_all), vaccines.size)
                )
                for ((code, label, count) in filters) {
                    val selected = (statusFilter == code)
                    val pill = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(8.dp(fragment), 8.dp(fragment), 8.dp(fragment), 8.dp(fragment))
                        setBackgroundResource(
                            if (selected) R.drawable.bg_bf_caramel_pill else R.drawable.bg_bf_top_capsule
                        )
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = 6.dp(fragment)
                        }
                        setOnClickListener {
                            statusFilter = code
                            render()
                        }
                        addView(TextView(ctx).apply {
                            text = label
                            textSize = 12f
                            setTextColor(
                                ContextCompat.getColor(
                                    ctx,
                                    if (selected) R.color.white else R.color.text_secondary_warm
                                )
                            )
                        })
                        addView(TextView(ctx).apply {
                            text = count.toString()
                            textSize = 13.5f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(
                                ContextCompat.getColor(
                                    ctx,
                                    if (selected) R.color.white else R.color.text_primary_warm
                                )
                            )
                        })
                    }
                    filterRow.addView(pill)
                }
                contentContainer.addView(filterRow)
            }

            val shown = when {
                showLibraryTab -> vaccines
                statusFilter == 1 -> pending
                statusFilter == 2 -> done
                else -> vaccines
            }

            for (v in shown) {
                val vCard = MaterialCardView(ctx).apply {
                    radius = 16.dp(fragment).toFloat()
                    cardElevation = 0f
                    setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_surface))
                    strokeColor = ContextCompat.getColor(ctx, R.color.card_stroke)
                    strokeWidth = 1.dp(fragment)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 8.dp(fragment) }
                }
                val vBody = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(14.dp(fragment), 12.dp(fragment), 14.dp(fragment), 12.dp(fragment))
                }
                val leftCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val titleLine = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                titleLine.addView(TextView(ctx).apply {
                    text = "${v.name} (${v.doseNumber}/${v.totalDoses})"
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
                    textSize = 14.5f
                    setTypeface(typeface, Typeface.BOLD)
                })
                titleLine.addView(TextView(ctx).apply {
                    text = if (v.isMandatory) ctx.getString(R.string.bf_vaccine_mandatory) else ctx.getString(R.string.bf_vaccine_optional)
                    setTextColor(
                        ContextCompat.getColor(
                            ctx,
                            if (v.isMandatory) R.color.bf_rose_accent else R.color.bf_purple_primary
                        )
                    )
                    textSize = 10.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(8.dp(fragment), 2.dp(fragment), 0, 2.dp(fragment))
                })
                leftCol.addView(titleLine)

                leftCol.addView(TextView(ctx).apply {
                    text = "${v.disease} · ${v.priceText} · ${v.recommendedMonthAge}${ctx.getString(R.string.bf_month_age_unit)}"
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
                    textSize = 12f
                })

                val due = BabyFlowLocalStore.computeDueDate(birthLocal, v.recommendedMonthAge)
                leftCol.addView(TextView(ctx).apply {
                    text = if (v.completedDate != null) {
                        "${ctx.getString(R.string.bf_vaccine_done_at)} ${v.completedDate}"
                    } else {
                        "${ctx.getString(R.string.bf_vaccine_plan_date)} $due · ${v.note}"
                    }
                    setTextColor(
                        ContextCompat.getColor(
                            ctx,
                            if (v.completedDate != null) R.color.bf_mint_accent else R.color.bf_purple_primary
                        )
                    )
                    textSize = 11.5f
                })

                vBody.addView(leftCol)

                val actionChip = TextView(ctx).apply {
                    val isDone = v.completedDate != null
                    text = if (isDone) "✓ ${ctx.getString(R.string.bf_vaccine_completed)}" else ctx.getString(R.string.bf_vaccine_mark_done)
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(12.dp(fragment), 7.dp(fragment), 12.dp(fragment), 7.dp(fragment))
                    setBackgroundResource(
                        if (isDone) R.drawable.bg_bf_overview_cell else R.drawable.bg_bf_timeline_pill
                    )
                    setTextColor(
                        ContextCompat.getColor(
                            ctx,
                            if (isDone) R.color.bf_mint_accent else R.color.bf_purple_primary
                        )
                    )
                    setOnClickListener {
                        if (isDone) {
                            store.toggleVaccineCompleted(childId, v.id, null)
                        } else {
                            store.toggleVaccineCompleted(childId, v.id, LocalDate.now().toString())
                        }
                        render()
                    }
                }
                vBody.addView(actionChip)
                vCard.addView(vBody)
                contentContainer.addView(vCard)
            }
        }

        tabPlan.setOnClickListener {
            showLibraryTab = false
            render()
        }
        tabLib.setOnClickListener {
            showLibraryTab = true
            render()
        }
        addBtn.setOnClickListener {
            val inputLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20.dp(fragment), 12.dp(fragment), 20.dp(fragment), 4.dp(fragment))
            }
            val nameEd = EditText(ctx).apply { hint = ctx.getString(R.string.bf_vaccine_custom_name_hint) }
            val monthEd = EditText(ctx).apply {
                hint = ctx.getString(R.string.bf_vaccine_custom_month_hint)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            val priceEd = EditText(ctx).apply { hint = ctx.getString(R.string.bf_vaccine_custom_price_hint) }
            inputLayout.addView(nameEd)
            inputLayout.addView(monthEd)
            inputLayout.addView(priceEd)

            AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.bf_vaccine_add_custom))
                .setView(inputLayout)
                .setPositiveButton(R.string.dialog_ok) { _, _ ->
                    val name = nameEd.text.toString().trim()
                    if (name.isNotEmpty()) {
                        val m = monthEd.text.toString().toIntOrNull() ?: 6
                        val p = priceEd.text.toString().trim().ifBlank { ctx.getString(R.string.bf_vaccine_self_paid) }
                        store.addCustomVaccine(
                            childId,
                            VaccineItem(
                                id = "custom_${System.currentTimeMillis()}",
                                name = name,
                                disease = ctx.getString(R.string.bf_vaccine_custom_tag),
                                isMandatory = false,
                                isFree = false,
                                priceText = p,
                                doseNumber = 1,
                                totalDoses = 1,
                                recommendedMonthAge = m,
                                note = ctx.getString(R.string.bf_vaccine_custom_tag)
                            )
                        )
                        render()
                    }
                }
                .setNegativeButton(R.string.history_delete_question_cancel_button, null)
                .show()
        }

        statusFilter = 1
        render()
        dialog.show()
    }

    // ===================== 3. MEDICATION & HEALTH LOG SHEET (top-left medkit) =====================

    fun showMedicationSheet(
        fragment: BaseFragment,
        child: Child?,
        store: BabyFlowLocalStore
    ) {
        val ctx = fragment.requireContext()
        val childId = child?.id ?: 0
        val (dialog, root) = makeFullSheet(fragment)

        val addBtn = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_bf_plus)
            setBackgroundResource(R.drawable.bg_bf_circle_btn)
            val s = 36.dp(fragment)
            layoutParams = ViewGroup.LayoutParams(s, s)
            setPadding(8.dp(fragment), 8.dp(fragment), 8.dp(fragment), 8.dp(fragment))
        }

        addTopBar(
            fragment = fragment,
            parent = root,
            title = ctx.getString(R.string.bf_med_sheet_title),
            onClose = { dialog.dismiss() },
            rightActionView = addBtn
        )

        val listContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(listContainer)
        }
        root.addView(scroll)

        fun render() {
            listContainer.removeAllViews()
            val meds = store.getMedications(childId)
            if (meds.isEmpty()) {
                listContainer.addView(TextView(ctx).apply {
                    text = ctx.getString(R.string.bf_med_empty_hint)
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_muted_warm))
                    textSize = 13.5f
                    setPadding(16.dp(fragment), 48.dp(fragment), 16.dp(fragment), 24.dp(fragment))
                })
                return
            }
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            for (m in meds) {
                val card = MaterialCardView(ctx).apply {
                    radius = 16.dp(fragment).toFloat()
                    cardElevation = 0f
                    setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.bf_cell_supplement))
                    strokeWidth = 0
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 8.dp(fragment) }
                }
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(14.dp(fragment), 12.dp(fragment), 14.dp(fragment), 12.dp(fragment))
                }
                col.addView(TextView(ctx).apply {
                    text = "${m.name} · ${m.dosage}"
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
                    textSize = 14.5f
                    setTypeface(typeface, Typeface.BOLD)
                })
                col.addView(TextView(ctx).apply {
                    text = fmt.format(Date(m.timestampMs)) + if (m.note.isNotBlank()) " · ${m.note}" else ""
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
                    textSize = 12f
                })
                card.addView(col)
                listContainer.addView(card)
            }
        }

        addBtn.setOnClickListener {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20.dp(fragment), 10.dp(fragment), 20.dp(fragment), 4.dp(fragment))
            }
            val nameEd = EditText(ctx).apply { hint = ctx.getString(R.string.bf_med_name_hint) }
            val doseEd = EditText(ctx).apply { hint = ctx.getString(R.string.bf_med_dose_hint) }
            val noteEd = EditText(ctx).apply { hint = ctx.getString(R.string.activity_notes_hint) }
            box.addView(nameEd)
            box.addView(doseEd)
            box.addView(noteEd)
            AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.bf_med_add_title))
                .setView(box)
                .setPositiveButton(R.string.dialog_ok) { _, _ ->
                    val name = nameEd.text.toString().trim()
                    if (name.isNotEmpty()) {
                        store.addMedication(
                            childId,
                            MedicationRecord(
                                name = name,
                                dosage = doseEd.text.toString().trim().ifBlank { "1 次" },
                                note = noteEd.text.toString().trim(),
                                dateStr = LocalDate.now().toString()
                            )
                        )
                        render()
                    }
                }
                .setNegativeButton(R.string.history_delete_question_cancel_button, null)
                .show()
        }

        render()
        dialog.show()
    }

    // ===================== 4. CALENDAR RETROSPECT & SEARCH SHEET (image_6) =====================

    fun showCalendarAndSearchSheet(
        fragment: BaseFragment,
        child: Child?,
        entries: List<TimeEntry>,
        store: BabyFlowLocalStore
    ) {
        val ctx = fragment.requireContext()
        val childId = child?.id ?: 0
        val (dialog, root) = makeFullSheet(fragment)

        var currentMonth = YearMonth.now()
        var selectedDay = LocalDate.now()
        var searchQuery = ""

        addTopBar(
            fragment = fragment,
            parent = root,
            title = ctx.getString(R.string.bf_calendar_title),
            onClose = { dialog.dismiss() }
        )

        // Search input pill bar
        val searchInput = EditText(ctx).apply {
            hint = ctx.getString(R.string.bf_search_hint)
            textSize = 13.5f
            setSingleLine(true)
            setBackgroundResource(R.drawable.bg_bf_top_capsule)
            setPadding(14.dp(fragment), 10.dp(fragment), 14.dp(fragment), 10.dp(fragment))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp(fragment) }
        }
        root.addView(searchInput)

        val bodyContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(bodyContainer)
        }
        root.addView(scroll)

        fun render() {
            bodyContainer.removeAllViews()
            val zone = ZoneId.systemDefault()

            if (searchQuery.isNotBlank()) {
                val q = searchQuery.lowercase(Locale.getDefault())
                val matched = entries.filter { e ->
                    val typeStr = fragment.translateActivityName(e.appType).lowercase(Locale.getDefault())
                    val notesStr = e.notes.lowercase(Locale.getDefault())
                    typeStr.contains(q) || notesStr.contains(q)
                }
                bodyContainer.addView(TextView(ctx).apply {
                    text = "${ctx.getString(R.string.bf_search_results)} (${matched.size})"
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
                    textSize = 12.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(4.dp(fragment), 4.dp(fragment), 4.dp(fragment), 8.dp(fragment))
                })
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                for (e in matched.take(40)) {
                    val card = MaterialCardView(ctx).apply {
                        radius = 14.dp(fragment).toFloat()
                        cardElevation = 0f
                        setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_surface))
                        strokeColor = ContextCompat.getColor(ctx, R.color.card_stroke)
                        strokeWidth = 1.dp(fragment)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = 6.dp(fragment) }
                    }
                    val col = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(12.dp(fragment), 10.dp(fragment), 12.dp(fragment), 10.dp(fragment))
                    }
                    col.addView(TextView(ctx).apply {
                        text = fragment.translateActivityName(e.appType)
                        setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    col.addView(TextView(ctx).apply {
                        val tStr = fmt.format(BabySummaryHelper.entryClientStart(e))
                        text = if (e.notes.isNotBlank()) "$tStr · ${e.notes}" else tStr
                        setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
                        textSize = 12f
                    })
                    card.addView(col)
                    bodyContainer.addView(card)
                }
                return
            }

            // Month switcher header
            val monthBar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8.dp(fragment) }
            }
            monthBar.addView(TextView(ctx).apply {
                text = "‹"
                textSize = 20f
                setPadding(12.dp(fragment), 2.dp(fragment), 12.dp(fragment), 2.dp(fragment))
                setOnClickListener {
                    currentMonth = currentMonth.minusMonths(1)
                    selectedDay = currentMonth.atDay(1)
                    render()
                }
            })
            monthBar.addView(TextView(ctx).apply {
                text = "${currentMonth.year} - ${currentMonth.monthValue}"
                gravity = Gravity.CENTER
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            monthBar.addView(TextView(ctx).apply {
                text = "›"
                textSize = 20f
                setPadding(12.dp(fragment), 2.dp(fragment), 12.dp(fragment), 2.dp(fragment))
                setOnClickListener {
                    currentMonth = currentMonth.plusMonths(1)
                    selectedDay = currentMonth.atDay(1)
                    render()
                }
            })
            bodyContainer.addView(monthBar)

            // Calendar Grid Card
            val calCard = MaterialCardView(ctx).apply {
                radius = 18.dp(fragment).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_surface))
                strokeColor = ContextCompat.getColor(ctx, R.color.card_stroke)
                strokeWidth = 1.dp(fragment)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 12.dp(fragment) }
            }
            val calGrid = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8.dp(fragment), 10.dp(fragment), 8.dp(fragment), 10.dp(fragment))
            }

            // Dates with activity dots
            val activeDates = entries.map {
                BabySummaryHelper.toLocalDate(BabySummaryHelper.entryClientStart(it), zone)
            }.toSet()

            val firstOfMonth = currentMonth.atDay(1)
            val offset = firstOfMonth.dayOfWeek.value % 7 // Sunday = 0
            val daysInMonth = currentMonth.lengthOfMonth()
            var cursor = 1 - offset

            while (cursor <= daysInMonth) {
                val weekRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                for (dow in 0 until 7) {
                    val dayNum = cursor
                    val cell = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(2.dp(fragment), 6.dp(fragment), 2.dp(fragment), 6.dp(fragment))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    if (dayNum in 1..daysInMonth) {
                        val dateObj = currentMonth.atDay(dayNum)
                        val isSelected = (dateObj == selectedDay)
                        if (isSelected) {
                            cell.setBackgroundResource(R.drawable.bg_bf_purple_pill)
                        }
                        cell.addView(TextView(ctx).apply {
                            text = dayNum.toString()
                            textSize = 13f
                            setTypeface(typeface, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                            setTextColor(
                                ContextCompat.getColor(
                                    ctx,
                                    if (isSelected) R.color.white else R.color.text_primary_warm
                                )
                            )
                        })
                        val hasData = dateObj in activeDates
                        cell.addView(TextView(ctx).apply {
                            text = if (hasData) "••" else " "
                            textSize = 8f
                            setTextColor(
                                ContextCompat.getColor(
                                    ctx,
                                    if (isSelected) R.color.white else R.color.bf_caramel_primary
                                )
                            )
                        })
                        cell.setOnClickListener {
                            selectedDay = dateObj
                            render()
                        }
                    }
                    weekRow.addView(cell)
                    cursor++
                }
                calGrid.addView(weekRow)
            }
            calCard.addView(calGrid)
            bodyContainer.addView(calCard)

            // Vaccine / Medication banners for selectedDay
            val dateStr = selectedDay.toString()
            val vaxOnDay = store.getVaccines(childId).count { it.completedDate == dateStr }
            val medOnDay = store.getMedications(childId).count { it.dateStr == dateStr }

            if (vaxOnDay > 0) {
                bodyContainer.addView(TextView(ctx).apply {
                    text = "💉 ${ctx.getString(R.string.bf_cal_has_vaccine)} ($vaxOnDay)"
                    setTextColor(ContextCompat.getColor(ctx, R.color.bf_purple_primary))
                    setTypeface(typeface, Typeface.BOLD)
                    setBackgroundResource(R.drawable.bg_bf_timeline_pill)
                    setPadding(14.dp(fragment), 10.dp(fragment), 14.dp(fragment), 10.dp(fragment))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 8.dp(fragment) }
                })
            }
            if (medOnDay > 0) {
                bodyContainer.addView(TextView(ctx).apply {
                    text = "🧰 ${ctx.getString(R.string.bf_cal_has_med)} ($medOnDay)"
                    setTextColor(ContextCompat.getColor(ctx, R.color.bf_rose_accent))
                    setTypeface(typeface, Typeface.BOLD)
                    setBackgroundResource(R.drawable.bg_bf_overview_cell)
                    setPadding(14.dp(fragment), 10.dp(fragment), 14.dp(fragment), 10.dp(fragment))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 8.dp(fragment) }
                })
            }

            // Selected Day 6-cell Summary Card (replicates image_6 bottom card)
            val endOfSelectedDay = Date(
                selectedDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1000L
            )
            val daySummary = BabySummaryHelper.computeTodaySummary(entries, nowClientTime = endOfSelectedDay, zoneId = zone)

            val summaryCard = MaterialCardView(ctx).apply {
                radius = 18.dp(fragment).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_surface))
                strokeColor = ContextCompat.getColor(ctx, R.color.card_stroke)
                strokeWidth = 1.dp(fragment)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val sumCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12.dp(fragment), 12.dp(fragment), 12.dp(fragment), 12.dp(fragment))
            }
            sumCol.addView(TextView(ctx).apply {
                text = "$dateStr · ${ctx.getString(R.string.summary_card_title)}"
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(2.dp(fragment), 0, 2.dp(fragment), 8.dp(fragment))
            })

            val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val sleepH = daySummary.todaySleepMinutes / 60
            val sleepM = daySummary.todaySleepMinutes % 60
            row1.addView(makeMiniStatCell(fragment, ctx.getString(R.string.tile_feeding),
                "${daySummary.todayFeedingCount}${ctx.getString(R.string.bf_times_short)}",
                "${daySummary.todayFeedingAmountMl.roundToInt()} ml",
                R.color.bf_cell_feeding, R.color.feeding_accent))
            row1.addView(makeMiniStatCell(fragment, ctx.getString(R.string.tile_sleep),
                "${sleepH}h ${sleepM}m",
                ctx.getString(R.string.tile_sleep),
                R.color.bf_cell_sleep, R.color.sleep_accent))
            row1.addView(makeMiniStatCell(fragment, ctx.getString(R.string.bf_cat_diaper),
                "${daySummary.todayDiaperTotal}${ctx.getString(R.string.bf_times_short)}",
                ctx.getString(R.string.bf_cat_diaper),
                R.color.bf_cell_diaper, R.color.diaper_accent))
            sumCol.addView(row1)

            val row2 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 6.dp(fragment) }
            }
            row2.addView(makeMiniStatCell(fragment, ctx.getString(R.string.bf_cat_supplement),
                "${daySummary.todayPumpingCount}${ctx.getString(R.string.bf_times_short)}",
                ctx.getString(R.string.bf_cat_supplement),
                R.color.bf_cell_supplement, R.color.pumping_accent))
            row2.addView(makeMiniStatCell(fragment, ctx.getString(R.string.bf_cat_care),
                "${daySummary.todayCareCount}${ctx.getString(R.string.bf_times_short)}",
                ctx.getString(R.string.bf_cat_care),
                R.color.bf_cell_care, R.color.notes_accent))
            row2.addView(makeMiniStatCell(fragment, ctx.getString(R.string.bf_cat_edu),
                "${daySummary.todayTummyMinutes}${ctx.getString(R.string.bf_min_short)}",
                ctx.getString(R.string.bf_cat_edu),
                R.color.bf_cell_edu, R.color.tummy_time_accent))
            sumCol.addView(row2)

            val openDayTimeline = TextView(ctx).apply {
                text = "${ctx.getString(R.string.bf_view_day_timeline)} ›"
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(ctx, R.color.bf_purple_primary))
                setTypeface(typeface, Typeface.BOLD)
                textSize = 13f
                setBackgroundResource(R.drawable.bg_bf_timeline_pill)
                setPadding(12.dp(fragment), 9.dp(fragment), 12.dp(fragment), 9.dp(fragment))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 10.dp(fragment) }
                setOnClickListener {
                    dialog.dismiss()
                    showDailyTimelineSheet(fragment, entries, selectedDay)
                }
            }
            sumCol.addView(openDayTimeline)

            summaryCard.addView(sumCol)
            bodyContainer.addView(summaryCard)
        }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                render()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        render()
        dialog.show()
    }

    private fun makeMiniStatCell(
        fragment: BaseFragment,
        label: String,
        value: String,
        sub: String,
        bgColorRes: Int,
        accentColorRes: Int
    ): View {
        val ctx = fragment.requireContext()
        return MaterialCardView(ctx).apply {
            radius = 14.dp(fragment).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(ContextCompat.getColor(ctx, bgColorRes))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 4.dp(fragment)
                marginStart = 4.dp(fragment)
            }
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10.dp(fragment), 8.dp(fragment), 10.dp(fragment), 8.dp(fragment))
                addView(TextView(ctx).apply {
                    text = label
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(ctx, accentColorRes))
                })
                addView(TextView(ctx).apply {
                    text = value
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(ctx, accentColorRes))
                })
                addView(TextView(ctx).apply {
                    text = sub
                    textSize = 10f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_muted_warm))
                })
            })
        }
    }
}
