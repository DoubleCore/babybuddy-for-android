package eu.pkgsoftware.babybuddywidgets.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import eu.pkgsoftware.babybuddywidgets.databinding.HorizontalDecIncEditorBinding
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.max

class HorizontalDecIncEditor : LinearLayout {
    companion object {
        private val CLEAN_DECIMAL = DecimalFormat("0.##", DecimalFormatSymbols(Locale.US))

        fun stepValue(
            current: Double?,
            delta: Double,
            allowNull: Boolean,
            defaultBaseline: Double? = null
        ): Double? {
            val base = current ?: defaultBaseline ?: 0.0
            val next = BigDecimal.valueOf(base)
                .add(BigDecimal.valueOf(delta))
                .setScale(2, RoundingMode.HALF_UP)
                .toDouble()
            return if (next <= 0.0) {
                if (allowNull && next < 0.0) null else max(0.0, next)
            } else {
                next
            }
        }

        fun formatClean(v: Double): String {
            return CLEAN_DECIMAL.format(
                BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toDouble()
            )
        }
    }

    var stepSize: Double = 10.0
    var defaultBaseline: Double? = null

    var unitSuffix: String = ""
        set(value) {
            field = value
            binding.unitSuffixLabel.text = value
            binding.unitSuffixLabel.visibility = if (value.isNotBlank()) View.VISIBLE else View.GONE
        }

    var value: Double?
        get() {
            return binding.numberEditor.text.toString().replace(',', '.').toDoubleOrNull()
        }
        set(value) {
            val v = value.let {
                if ((it == null) || (it < 0.0)) {
                    if (allowNull) {
                        return@let null
                    } else {
                        return@let 0.0
                    }
                }
                it
            }
            if (v == null) {
                binding.numberEditor.setText("")
            } else {
                binding.numberEditor.setText(formatClean(v))
            }
        }

    var allowNull: Boolean = true
        set(value) {
            field = value
            if (!value && this.value == null) {
                this.value = 0.0
            }
        }

    val binding = HorizontalDecIncEditorBinding.inflate(
        LayoutInflater.from(context), this, true
    )

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    ) {
        init()
    }

    private fun init() {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        value = null

        binding.decButton.setOnClickListener {
            val cur = value
            if (cur == null && allowNull && defaultBaseline == null) {
                value = null
            } else {
                val stepped = stepValue(cur, -stepSize, allowNull, defaultBaseline)
                value = if (stepped != null && stepped == 0.0 && allowNull && (cur ?: 0.0) <= 0.0) {
                    null
                } else {
                    stepped
                }
            }
            binding.numberEditor.selectAll()
        }
        binding.incButton.setOnClickListener {
            value = stepValue(value, stepSize, allowNull, defaultBaseline)
            binding.numberEditor.selectAll()
        }
    }
}
