package eu.pkgsoftware.babybuddywidgets.babyflow

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import eu.pkgsoftware.babybuddywidgets.BaseFragment
import eu.pkgsoftware.babybuddywidgets.R
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class InventoryExpenseController(
    private val fragment: BaseFragment,
    private val store: BabyFlowLocalStore
) {
    private val ctx = fragment.requireContext()
    private val moneyFmt = DecimalFormat("0.##")
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private var currentChild: Child? = null
    private var selectedCategory: String = "diaper" // "formula", "diaper", "supplement", "other"
    private var showUsedUp: Boolean = false

    val rootView: View

    private val headerTitleTv: TextView
    private val totalSpendBigTv: TextView
    private val totalFamilySpendTv: TextView
    private val formulaSumTv: TextView
    private val diaperSumTv: TextView
    private val supplementSumTv: TextView
    private val otherSumTv: TextView
    private val catTabsContainer: LinearLayout
    private val toggleUsedUpTv: TextView
    private val itemsContainer: LinearLayout
    private val expensesListContainer: LinearLayout

    private fun Int.dp(): Int = (this * fragment.resources.displayMetrics.density).roundToInt()

    init {
        val outerScroll = ScrollView(ctx).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.surface_background))
            setPadding(16.dp(), 10.dp(), 16.dp(), 108.dp())
        }

        val mainCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Top Bar: 库存与花费 + Purple Add Button
        val topBar = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12.dp() }
        }
        headerTitleTv = TextView(ctx).apply {
            text = ctx.getString(R.string.bf_inv_title)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        topBar.addView(headerTitleTv)

        val addFab = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_bf_plus)
            setColorFilter(ContextCompat.getColor(ctx, R.color.white))
            setBackgroundResource(R.drawable.bg_bf_purple_pill)
            val s = 34.dp()
            setPadding(7.dp(), 7.dp(), 7.dp(), 7.dp())
            layoutParams = FrameLayout.LayoutParams(s, s, Gravity.END or Gravity.CENTER_VERTICAL)
            setOnClickListener { showAddDialog() }
        }
        topBar.addView(addFab)
        mainCol.addView(topBar)

        // Ledger Summary Card ("花费账本")
        val ledgerCard = MaterialCardView(ctx).apply {
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
        val ledgerBody = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
        }
        ledgerBody.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.bf_inv_ledger_title)
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
        })

        val totalsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dp()
                bottomMargin = 12.dp()
            }
        }
        val leftTotalCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = ctx.getString(R.string.bf_inv_current_baby)
                textSize = 11.5f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_muted_warm))
            })
        }
        totalSpendBigTv = TextView(ctx).apply {
            text = "¥0"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
        }
        leftTotalCol.addView(totalSpendBigTv)
        totalsRow.addView(leftTotalCol)

        val rightTotalCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(TextView(ctx).apply {
                text = ctx.getString(R.string.bf_inv_total_sum)
                textSize = 11.5f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_muted_warm))
            })
        }
        totalFamilySpendTv = TextView(ctx).apply {
            text = "¥0"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.bf_caramel_primary))
        }
        rightTotalCol.addView(totalFamilySpendTv)
        totalsRow.addView(rightTotalCol)
        ledgerBody.addView(totalsRow)

        // 2x2 Breakdown mini cells
        formulaSumTv = TextView(ctx)
        diaperSumTv = TextView(ctx)
        supplementSumTv = TextView(ctx)
        otherSumTv = TextView(ctx)

        val gridRow1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        gridRow1.addView(makeBreakdownBox(ctx.getString(R.string.bf_inv_cat_formula), formulaSumTv))
        gridRow1.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(8.dp(), 1) })
        gridRow1.addView(makeBreakdownBox(ctx.getString(R.string.bf_cat_diaper), diaperSumTv))
        ledgerBody.addView(gridRow1)

        val gridRow2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8.dp() }
        }
        gridRow2.addView(makeBreakdownBox(ctx.getString(R.string.bf_cat_supplement), supplementSumTv))
        gridRow2.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(8.dp(), 1) })
        gridRow2.addView(makeBreakdownBox(ctx.getString(R.string.bf_inv_cat_other), otherSumTv))
        ledgerBody.addView(gridRow2)

        ledgerCard.addView(ledgerBody)
        mainCol.addView(ledgerCard)

        // Category Segmented Bar: 奶粉 | 纸尿裤 | 补剂 | 其他
        catTabsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_bf_segment_track)
            setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp() }
        }
        mainCol.addView(catTabsContainer)

        // Show Used Up Toggle Pill
        val filterActionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp() }
        }
        toggleUsedUpTv = TextView(ctx).apply {
            text = ctx.getString(R.string.bf_inv_show_used_up)
            textSize = 12f
            setBackgroundResource(R.drawable.bg_bf_top_capsule)
            setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
            setOnClickListener {
                showUsedUp = !showUsedUp
                render()
            }
        }
        filterActionRow.addView(toggleUsedUpTv)
        mainCol.addView(filterActionRow)

        // Inventory Items Container
        itemsContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        mainCol.addView(itemsContainer)

        // Expense Details Card ("花费明细")
        mainCol.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.bf_inv_expense_details)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
            setPadding(4.dp(), 12.dp(), 4.dp(), 8.dp())
        })

        val expCard = MaterialCardView(ctx).apply {
            radius = 20.dp().toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_surface))
            strokeColor = ContextCompat.getColor(ctx, R.color.card_stroke)
            strokeWidth = 1.dp()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        expensesListContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp(), 10.dp(), 14.dp(), 10.dp())
        }
        expCard.addView(expensesListContainer)
        mainCol.addView(expCard)

        outerScroll.addView(mainCol)
        rootView = outerScroll
    }

    private fun makeBreakdownBox(label: String, valueTv: TextView): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_bf_overview_cell)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.surface_background)
            )
            setPadding(12.dp(), 9.dp(), 12.dp(), 9.dp())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = label
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            valueTv.apply {
                text = "¥0"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
            }
            addView(valueTv)
        }
    }

    fun setChild(child: Child?) {
        currentChild = child
        render()
    }

    fun render() {
        val childId = currentChild?.id ?: 0
        val inventory = store.getInventory(childId)
        val expenses = store.getExpenses(childId)

        val sumFormula = expenses.filter { it.category == "formula" }.sumOf { it.amountYuan }
        val sumDiaper = expenses.filter { it.category == "diaper" }.sumOf { it.amountYuan }
        val sumSupp = expenses.filter { it.category == "supplement" }.sumOf { it.amountYuan }
        val sumOther = expenses.filter { it.category == "other" }.sumOf { it.amountYuan }
        val total = sumFormula + sumDiaper + sumSupp + sumOther

        totalSpendBigTv.text = "¥${moneyFmt.format(total)}"
        totalFamilySpendTv.text = "¥${moneyFmt.format(total)}"
        formulaSumTv.text = "¥${moneyFmt.format(sumFormula)}"
        diaperSumTv.text = "¥${moneyFmt.format(sumDiaper)}"
        supplementSumTv.text = "¥${moneyFmt.format(sumSupp)}"
        otherSumTv.text = "¥${moneyFmt.format(sumOther)}"

        // Category tabs
        catTabsContainer.removeAllViews()
        val cats = listOf(
            "formula" to ctx.getString(R.string.bf_inv_cat_formula),
            "diaper" to ctx.getString(R.string.bf_cat_diaper),
            "supplement" to ctx.getString(R.string.bf_cat_supplement),
            "other" to ctx.getString(R.string.bf_inv_cat_other)
        )
        for ((key, title) in cats) {
            val isSel = (selectedCategory == key)
            val tab = TextView(ctx).apply {
                text = title
                gravity = Gravity.CENTER
                textSize = 13f
                setTypeface(typeface, if (isSel) Typeface.BOLD else Typeface.NORMAL)
                setPadding(0, 8.dp(), 0, 8.dp())
                if (isSel) setBackgroundResource(R.drawable.bg_bf_segment_selected)
                setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (isSel) R.color.text_primary_warm else R.color.text_secondary_warm
                    )
                )
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    selectedCategory = key
                    render()
                }
            }
            catTabsContainer.addView(tab)
        }

        toggleUsedUpTv.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (showUsedUp) R.color.bf_purple_primary else R.color.text_secondary_warm
            )
        )

        // Render Inventory Cards
        itemsContainer.removeAllViews()
        val filteredInv = inventory.filter {
            it.category == selectedCategory && (showUsedUp || it.remainingUnits > 0)
        }
        if (filteredInv.isEmpty()) {
            itemsContainer.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.bf_inv_empty_category)
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_muted_warm))
                setPadding(12.dp(), 22.dp(), 12.dp(), 22.dp())
            })
        } else {
            for (item in filteredInv) {
                itemsContainer.addView(buildInventoryItemCard(childId, item))
            }
        }

        // Render Expense List
        expensesListContainer.removeAllViews()
        if (expenses.isEmpty()) {
            expensesListContainer.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.stats_no_data)
                gravity = Gravity.CENTER
                textSize = 12.5f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_muted_warm))
                setPadding(8.dp(), 16.dp(), 8.dp(), 16.dp())
            })
        } else {
            for (e in expenses.take(15)) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(2.dp(), 8.dp(), 2.dp(), 8.dp())
                }
                val iconRes = when (e.category) {
                    "formula" -> R.drawable.ic_bf_3d_bottle
                    "diaper" -> R.drawable.ic_bf_3d_diaper
                    "supplement" -> R.drawable.ic_bf_3d_supplement
                    else -> R.drawable.ic_bf_3d_care
                }
                row.addView(ImageView(ctx).apply {
                    setImageResource(iconRes)
                    layoutParams = LinearLayout.LayoutParams(32.dp(), 32.dp()).apply {
                        marginEnd = 10.dp()
                    }
                })
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(ctx).apply {
                        text = e.title
                        textSize = 13.5f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
                    })
                    addView(TextView(ctx).apply {
                        val sub = if (e.subtitle.isNotBlank()) " · ${e.subtitle}" else ""
                        text = dateFmt.format(Date(e.timestampMs)) + sub
                        textSize = 11f
                        setTextColor(ContextCompat.getColor(ctx, R.color.text_muted_warm))
                    })
                }
                row.addView(col)
                row.addView(TextView(ctx).apply {
                    text = "¥${moneyFmt.format(e.amountYuan)}"
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
                })
                expensesListContainer.addView(row)
            }
        }
    }

    private fun buildInventoryItemCard(childId: Int, item: InventoryItem): View {
        val card = MaterialCardView(ctx).apply {
            radius = 20.dp().toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_surface))
            strokeColor = ContextCompat.getColor(ctx, R.color.card_stroke)
            strokeWidth = 1.dp()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp() }
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp(), 14.dp(), 14.dp(), 14.dp())
        }

        // Header Row with 3D Icon + Title + Spec
        val headRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconRes = when (item.category) {
            "formula" -> R.drawable.ic_bf_3d_bottle
            "diaper" -> R.drawable.ic_bf_3d_diaper
            "supplement" -> R.drawable.ic_bf_3d_supplement
            else -> R.drawable.ic_bf_3d_care
        }
        headRow.addView(ImageView(ctx).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(38.dp(), 38.dp()).apply { marginEnd = 10.dp() }
        })
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = item.name
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
            })
            if (item.specText.isNotBlank()) {
                addView(TextView(ctx).apply {
                    text = item.specText
                    textSize = 11.5f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_muted_warm))
                })
            }
        }
        headRow.addView(titleCol)
        headRow.addView(TextView(ctx).apply {
            text = "¥${moneyFmt.format(item.priceYuan)}"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.bf_caramel_primary))
        })
        body.addView(headRow)

        // Status + Progress bar
        val pct = ((item.remainingUnits.toFloat() / item.totalUnits.coerceAtLeast(1)) * 100f).roundToInt().coerceIn(0, 100)
        body.addView(TextView(ctx).apply {
            text = if (item.remainingUnits > 0) {
                "● ${ctx.getString(R.string.bf_inv_consuming)} · ${ctx.getString(R.string.bf_inv_remaining_prefix)} ${item.remainingUnits} / ${item.totalUnits}"
            } else {
                "○ ${ctx.getString(R.string.bf_inv_used_up)}"
            }
            textSize = 11.5f
            setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (item.remainingUnits > 0) R.color.bf_mint_accent else R.color.text_muted_warm
                )
            )
            setPadding(0, 8.dp(), 0, 4.dp())
        })

        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = pct
            progressTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.bf_mint_accent)
            )
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.bf_segment_track)
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8.dp()).apply {
                bottomMargin = 10.dp()
            }
        }
        body.addView(bar)

        // 3 Pill Buttons: -1消耗 | 补货 | 标记用完 (replicates image_5)
        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        btnRow.addView(makeActionPill(
            text = "🛒 ${ctx.getString(R.string.bf_inv_btn_restock)}",
            bgColorRes = R.color.bf_cell_feeding,
            fgColorRes = R.color.bf_caramel_primary
        ) {
            store.restockInventoryItem(childId, item.id)
            render()
        })

        btnRow.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(8.dp(), 1) })

        btnRow.addView(makeActionPill(
            text = "-1 ${ctx.getString(R.string.bf_inv_btn_use_one)}",
            bgColorRes = R.color.bf_timeline_pill_bg,
            fgColorRes = R.color.bf_purple_primary
        ) {
            if (item.remainingUnits > 0) {
                val list = store.getInventory(childId).toMutableList()
                val idx = list.indexOfFirst { it.id == item.id }
                if (idx >= 0) {
                    list[idx].remainingUnits = (list[idx].remainingUnits - 1).coerceAtLeast(0)
                    store.saveInventory(childId, list)
                    render()
                }
            }
        })

        btnRow.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(8.dp(), 1) })

        btnRow.addView(makeActionPill(
            text = "⊘ ${ctx.getString(R.string.bf_inv_btn_mark_empty)}",
            bgColorRes = R.color.danger_soft_bg,
            fgColorRes = R.color.danger_soft_fg
        ) {
            store.markInventoryUsedUp(childId, item.id)
            render()
        })

        body.addView(btnRow)
        card.addView(body)
        return card
    }

    private fun makeActionPill(
        text: String,
        bgColorRes: Int,
        fgColorRes: Int,
        onClick: () -> Unit
    ): View {
        return TextView(ctx).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_bf_overview_cell)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(ctx, bgColorRes)
            )
            setTextColor(ContextCompat.getColor(ctx, fgColorRes))
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    private fun showAddDialog() {
        val childId = currentChild?.id ?: 0
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 12.dp(), 20.dp(), 4.dp())
        }
        val catSpinner = Spinner(ctx)
        val catLabels = arrayOf(
            ctx.getString(R.string.bf_inv_cat_formula),
            ctx.getString(R.string.bf_cat_diaper),
            ctx.getString(R.string.bf_cat_supplement),
            ctx.getString(R.string.bf_inv_cat_other)
        )
        val catKeys = arrayOf("formula", "diaper", "supplement", "other")
        catSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, catLabels)
        catSpinner.setSelection(catKeys.indexOf(selectedCategory).coerceAtLeast(0))

        val nameEd = EditText(ctx).apply { hint = ctx.getString(R.string.bf_inv_add_name_hint) }
        val unitsEd = EditText(ctx).apply {
            hint = ctx.getString(R.string.bf_inv_add_qty_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val priceEd = EditText(ctx).apply {
            hint = ctx.getString(R.string.bf_inv_add_price_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val specEd = EditText(ctx).apply { hint = ctx.getString(R.string.bf_inv_add_spec_hint) }

        form.addView(catSpinner)
        form.addView(nameEd)
        form.addView(unitsEd)
        form.addView(priceEd)
        form.addView(specEd)

        AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.bf_inv_add_dialog_title))
            .setView(form)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val name = nameEd.text.toString().trim()
                if (name.isNotEmpty()) {
                    val cat = catKeys[catSpinner.selectedItemPosition]
                    val qty = unitsEd.text.toString().toIntOrNull() ?: 1
                    val price = priceEd.text.toString().toDoubleOrNull() ?: 0.0
                    val spec = specEd.text.toString().trim()
                    selectedCategory = cat
                    store.addInventoryAndExpense(
                        childId,
                        InventoryItem(
                            name = name,
                            category = cat,
                            specText = spec,
                            totalUnits = qty.coerceAtLeast(1),
                            remainingUnits = qty.coerceAtLeast(1),
                            priceYuan = price
                        ),
                        recordExpense = true
                    )
                    render()
                }
            }
            .setNegativeButton(R.string.history_delete_question_cancel_button, null)
            .show()
    }
}
