package eu.pkgsoftware.babybuddywidgets.stats

import android.content.Context
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child
import java.io.File
import java.io.InputStream
import java.util.Date
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Built-in WHO Child Growth Standards (0–60 months, Boys & Girls) LMS calculator.
 * Supports P3 / P15 / P50 / P85 / P97 reference curves, exact Z-scores & percentiles,
 * and prematurity corrected age based on [Child.dueDate].
 */
object WhoGrowthStandards {
    const val WHO_DAYS_PER_MONTH = 30.4375
    const val MAX_SUPPORTED_MONTHS = 60.0

    // WHO standard Z-scores for 3rd, 15th, 50th, 85th, and 97th percentiles
    private const val Z_P3 = -1.88079
    private const val Z_P15 = -1.03643
    private const val Z_P50 = 0.0
    private const val Z_P85 = 1.03643
    private const val Z_P97 = 1.88079

    enum class GrowthMetric(val jsonKey: String) {
        WEIGHT("weight"),
        HEIGHT("height"),
        HEAD_CIRCUMFERENCE("head_circumference"),
        BMI("bmi"),
    }

    data class LmsRow(
        val month: Double,
        val l: Double,
        val m: Double,
        val s: Double,
    )

    data class PercentileBand(
        val ageMonths: Double,
        val p3: Float,
        val p15: Float,
        val p50: Float,
        val p85: Float,
        val p97: Float,
    )

    data class MeasurementEvaluation(
        val ageMonths: Double,
        val zScore: Double,
        val percentile: Double,
        val usedCorrectedAge: Boolean,
        val usedNeutralSexAverage: Boolean,
    )

    @Volatile
    private var loadedTables: Map<GrowthMetric, Pair<List<LmsRow>, List<LmsRow>>>? = null

    fun ensureLoaded(context: Context? = null) {
        if (loadedTables != null) return
        synchronized(this) {
            if (loadedTables != null) return
            val stream: InputStream? = try {
                context?.assets?.open("who_growth_percentiles.json")
            } catch (_: Exception) {
                null
            } ?: openFallbackAssetStream()

            if (stream != null) {
                stream.use { loadedTables = parseJsonTables(it) }
            }
        }
    }

    private fun openFallbackAssetStream(): InputStream? {
        val candidates = listOf(
            File("src/main/assets/who_growth_percentiles.json"),
            File("app/src/main/assets/who_growth_percentiles.json"),
            File("../app/src/main/assets/who_growth_percentiles.json"),
        )
        for (f in candidates) {
            if (f.isFile) return f.inputStream()
        }
        return WhoGrowthStandards::class.java.classLoader?.getResourceAsStream("who_growth_percentiles.json")
    }

    private fun parseJsonTables(input: InputStream): Map<GrowthMetric, Pair<List<LmsRow>, List<LmsRow>>> {
        val mapper = ObjectMapper()
        val root = mapper.readTree(input)
        val metricsNode = root.path("metrics")
        val map = LinkedHashMap<GrowthMetric, Pair<List<LmsRow>, List<LmsRow>>>()
        for (metric in GrowthMetric.entries) {
            val mNode = metricsNode.path(metric.jsonKey)
            val boys = parseSexArray(mNode.path("boy"))
            val girls = parseSexArray(mNode.path("girl"))
            map[metric] = boys to girls
        }
        return map
    }

    private fun parseSexArray(arr: JsonNode): List<LmsRow> {
        if (!arr.isArray) return emptyList()
        val out = ArrayList<LmsRow>(arr.size())
        for (node in arr) {
            out.add(
                LmsRow(
                    month = node.path("m").asDouble(),
                    l = node.path("L").asDouble(),
                    m = node.path("M").asDouble(),
                    s = node.path("S").asDouble(),
                )
            )
        }
        return out.sortedBy { it.month }
    }

    /**
     * Computes the baby's age in WHO months at [measurementTimeMillis], applying
     * [Child.dueDate] prematurity correction up to 24 months chronological age.
     */
    fun computeEffectiveAgeMonths(
        child: Child,
        measurementTimeMillis: Long,
        allowPrematureCorrection: Boolean = true,
    ): Pair<Double, Boolean> {
        val chronoDays = (measurementTimeMillis - child.birthData.time).toDouble() / 86_400_000.0
        val chronoMonths = chronoDays / WHO_DAYS_PER_MONTH
        if (allowPrematureCorrection && child.hasCorrectedPrematureAge && child.dueDate != null && chronoMonths <= 24.0) {
            val correctedDays = (measurementTimeMillis - child.dueDate.time).toDouble() / 86_400_000.0
            val correctedMonths = (correctedDays / WHO_DAYS_PER_MONTH).coerceAtLeast(0.0)
            return correctedMonths to true
        }
        return chronoMonths.coerceAtLeast(0.0) to false
    }

    fun interpolateLms(
        metric: GrowthMetric,
        sex: String?,
        ageMonths: Double,
        context: Context? = null,
    ): Pair<LmsRow, Boolean>? {
        if (ageMonths < 0.0 || ageMonths > MAX_SUPPORTED_MONTHS) return null
        ensureLoaded(context)
        val tables = loadedTables ?: return null
        val (boyRows, girlRows) = tables[metric] ?: return null
        if (boyRows.isEmpty() || girlRows.isEmpty()) return null

        return when (sex?.lowercase()) {
            Child.SEX_BOY -> interpolateSeries(boyRows, ageMonths) to false
            Child.SEX_GIRL -> interpolateSeries(girlRows, ageMonths) to false
            else -> {
                val b = interpolateSeries(boyRows, ageMonths)
                val g = interpolateSeries(girlRows, ageMonths)
                LmsRow(
                    month = ageMonths,
                    l = (b.l + g.l) * 0.5,
                    m = (b.m + g.m) * 0.5,
                    s = (b.s + g.s) * 0.5,
                ) to true
            }
        }
    }

    private fun interpolateSeries(rows: List<LmsRow>, ageMonths: Double): LmsRow {
        if (ageMonths <= rows.first().month) return rows.first().copy(month = ageMonths)
        if (ageMonths >= rows.last().month) return rows.last().copy(month = ageMonths)
        for (i in 0 until rows.size - 1) {
            val left = rows[i]
            val right = rows[i + 1]
            if (ageMonths in left.month..right.month) {
                val span = right.month - left.month
                val t = if (span <= 1e-9) 0.0 else (ageMonths - left.month) / span
                return LmsRow(
                    month = ageMonths,
                    l = left.l + (right.l - left.l) * t,
                    m = left.m + (right.m - left.m) * t,
                    s = left.s + (right.s - left.s) * t,
                )
            }
        }
        return rows.last()
    }

    fun valueForZ(lms: LmsRow, z: Double): Double {
        return if (abs(lms.l) < 1e-6) {
            lms.m * exp(lms.s * z)
        } else {
            val base = (1.0 + lms.l * lms.s * z).coerceAtLeast(1e-6)
            lms.m * base.pow(1.0 / lms.l)
        }
    }

    fun zScoreForValue(lms: LmsRow, value: Double): Double {
        if (value <= 0.0 || lms.m <= 0.0 || lms.s <= 0.0) return 0.0
        val rawZ = if (abs(lms.l) < 1e-6) {
            ln(value / lms.m) / lms.s
        } else {
            ((value / lms.m).pow(lms.l) - 1.0) / (lms.l * lms.s)
        }
        // Apply WHO extreme Z-score linear tail adjustment for |Z| > 3
        if (rawZ > 3.0) {
            val sd3Pos = valueForZ(lms, 3.0)
            val sd23Pos = sd3Pos - valueForZ(lms, 2.0)
            if (sd23Pos > 1e-6) return 3.0 + (value - sd3Pos) / sd23Pos
        } else if (rawZ < -3.0) {
            val sd3Neg = valueForZ(lms, -3.0)
            val sd23Neg = valueForZ(lms, -2.0) - sd3Neg
            if (sd23Neg > 1e-6) return -3.0 + (value - sd3Neg) / sd23Neg
        }
        return rawZ
    }

    fun percentileBandAtAge(
        metric: GrowthMetric,
        sex: String?,
        ageMonths: Double,
        context: Context? = null,
    ): PercentileBand? {
        val (lms, _) = interpolateLms(metric, sex, ageMonths, context) ?: return null
        return PercentileBand(
            ageMonths = ageMonths,
            p3 = valueForZ(lms, Z_P3).toFloat(),
            p15 = valueForZ(lms, Z_P15).toFloat(),
            p50 = valueForZ(lms, Z_P50).toFloat(),
            p85 = valueForZ(lms, Z_P85).toFloat(),
            p97 = valueForZ(lms, Z_P97).toFloat(),
        )
    }

    fun evaluateMeasurement(
        child: Child,
        metric: GrowthMetric,
        measurementDate: Date,
        value: Double,
        context: Context? = null,
    ): MeasurementEvaluation? {
        val (ageMonths, usedCorrected) = computeEffectiveAgeMonths(child, measurementDate.time)
        if (ageMonths > MAX_SUPPORTED_MONTHS) return null
        val (lms, neutralSex) = interpolateLms(metric, child.sex, ageMonths, context) ?: return null
        val z = zScoreForValue(lms, value)
        val pct = (normalCdf(z) * 100.0).coerceIn(0.1, 99.9)
        return MeasurementEvaluation(
            ageMonths = ageMonths,
            zScore = z,
            percentile = pct,
            usedCorrectedAge = usedCorrected,
            usedNeutralSexAverage = neutralSex,
        )
    }

    /**
     * Standard normal cumulative distribution function Φ(z).
     */
    fun normalCdf(z: Double): Double {
        val clamped = z.coerceIn(-8.0, 8.0)
        return 0.5 * (1.0 + erf(clamped / sqrt(2.0)))
    }

    private fun erf(x: Double): Double {
        val sign = if (x < 0) -1.0 else 1.0
        val ax = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-ax * ax)
        return sign * y
    }
}
