package eu.pkgsoftware.babybuddywidgets.babyflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

data class VaccineItem(
    val id: String,
    val name: String,
    val disease: String,
    val isMandatory: Boolean,
    val isFree: Boolean,
    val priceText: String,
    val doseNumber: Int,
    val totalDoses: Int,
    val recommendedMonthAge: Int,
    val note: String,
    var completedDate: String? = null,
    var hospital: String = ""
)

data class InventoryItem(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var category: String, // "formula", "diaper", "supplement", "other"
    var specText: String, // e.g., "1片/次 · 90/包"
    var totalUnits: Int,
    var remainingUnits: Int,
    var priceYuan: Double,
    var createdAtMs: Long = System.currentTimeMillis()
)

data class ExpenseRecord(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val category: String, // "formula", "diaper", "supplement", "other"
    val amountYuan: Double,
    val timestampMs: Long = System.currentTimeMillis(),
    val subtitle: String = ""
)

data class GrowthMoment(
    val id: String = UUID.randomUUID().toString(),
    val tag: String, // e.g. "#大运动", "#体检", "#日常"
    val title: String,
    val content: String,
    val dateStr: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class MedicationRecord(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val dosage: String,
    val note: String,
    val dateStr: String,
    val timestampMs: Long = System.currentTimeMillis()
)

class BabyFlowLocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("babyflow_local_ext_v1", Context.MODE_PRIVATE)

    private fun key(childId: Int, suffix: String) = "child_${childId}_$suffix"

    // ===================== VACCINES =====================

    fun getVaccines(childId: Int): List<VaccineItem> {
        val raw = prefs.getString(key(childId, "vaccines"), null)
        if (raw.isNullOrBlank()) {
            val defaults = defaultVaccineLibrary()
            saveVaccines(childId, defaults)
            return defaults
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<VaccineItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    VaccineItem(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        disease = o.optString("disease"),
                        isMandatory = o.optBoolean("isMandatory", true),
                        isFree = o.optBoolean("isFree", true),
                        priceText = o.optString("priceText", ""),
                        doseNumber = o.optInt("doseNumber", 1),
                        totalDoses = o.optInt("totalDoses", 1),
                        recommendedMonthAge = o.optInt("recommendedMonthAge", 0),
                        note = o.optString("note", ""),
                        completedDate = o.optString("completedDate", "").ifBlank { null },
                        hospital = o.optString("hospital", "")
                    )
                )
            }
            if (list.isEmpty()) defaultVaccineLibrary() else list
        } catch (_: Exception) {
            defaultVaccineLibrary()
        }
    }

    fun saveVaccines(childId: Int, items: List<VaccineItem>) {
        val arr = JSONArray()
        for (v in items) {
            arr.put(
                JSONObject().apply {
                    put("id", v.id)
                    put("name", v.name)
                    put("disease", v.disease)
                    put("isMandatory", v.isMandatory)
                    put("isFree", v.isFree)
                    put("priceText", v.priceText)
                    put("doseNumber", v.doseNumber)
                    put("totalDoses", v.totalDoses)
                    put("recommendedMonthAge", v.recommendedMonthAge)
                    put("note", v.note)
                    put("completedDate", v.completedDate ?: "")
                    put("hospital", v.hospital)
                }
            )
        }
        prefs.edit().putString(key(childId, "vaccines"), arr.toString()).apply()
    }

    fun toggleVaccineCompleted(childId: Int, vaccineId: String, dateStr: String?, hospital: String = "") {
        val list = getVaccines(childId).toMutableList()
        val idx = list.indexOfFirst { it.id == vaccineId }
        if (idx >= 0) {
            list[idx].completedDate = dateStr
            if (hospital.isNotBlank()) {
                list[idx].hospital = hospital
            }
            saveVaccines(childId, list)
        }
    }

    fun addCustomVaccine(childId: Int, item: VaccineItem) {
        val list = getVaccines(childId).toMutableList()
        list.add(0, item)
        saveVaccines(childId, list)
    }

    // ===================== INVENTORY & EXPENSES =====================

    fun getInventory(childId: Int): List<InventoryItem> {
        val raw = prefs.getString(key(childId, "inventory"), null)
        if (raw.isNullOrBlank()) {
            val defaults = defaultStarterInventory()
            saveInventory(childId, defaults)
            // Seed starter expenses matching starter inventory if empty
            if (prefs.getString(key(childId, "expenses"), null).isNullOrBlank()) {
                val now = System.currentTimeMillis()
                saveExpenses(
                    childId,
                    listOf(
                        ExpenseRecord(
                            title = "爱他美 3段",
                            category = "formula",
                            amountYuan = 280.0,
                            timestampMs = now - 86_400_000L * 2,
                            subtitle = "初始库存"
                        ),
                        ExpenseRecord(
                            title = "帮宝适 M码",
                            category = "diaper",
                            amountYuan = 168.0,
                            timestampMs = now - 86_400_000L,
                            subtitle = "初始库存 · 90片/包"
                        ),
                        ExpenseRecord(
                            title = "童年阳光 维生素D3",
                            category = "supplement",
                            amountYuan = 98.0,
                            timestampMs = now - 36_000_000L,
                            subtitle = "日常补剂"
                        )
                    )
                )
            }
            return defaults
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<InventoryItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    InventoryItem(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        category = o.optString("category", "diaper"),
                        specText = o.optString("specText", ""),
                        totalUnits = o.optInt("totalUnits", 1),
                        remainingUnits = o.optInt("remainingUnits", 0),
                        priceYuan = o.optDouble("priceYuan", 0.0),
                        createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveInventory(childId: Int, items: List<InventoryItem>) {
        val arr = JSONArray()
        for (item in items) {
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("category", item.category)
                    put("specText", item.specText)
                    put("totalUnits", item.totalUnits)
                    put("remainingUnits", item.remainingUnits)
                    put("priceYuan", item.priceYuan)
                    put("createdAtMs", item.createdAtMs)
                }
            )
        }
        prefs.edit().putString(key(childId, "inventory"), arr.toString()).apply()
    }

    fun getExpenses(childId: Int): List<ExpenseRecord> {
        // Ensure starter seeding runs if untouched
        if (prefs.getString(key(childId, "inventory"), null) == null) {
            getInventory(childId)
        }
        val raw = prefs.getString(key(childId, "expenses"), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ExpenseRecord>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    ExpenseRecord(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        category = o.optString("category", "other"),
                        amountYuan = o.optDouble("amountYuan", 0.0),
                        timestampMs = o.optLong("timestampMs", System.currentTimeMillis()),
                        subtitle = o.optString("subtitle", "")
                    )
                )
            }
            list.sortedByDescending { it.timestampMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveExpenses(childId: Int, items: List<ExpenseRecord>) {
        val arr = JSONArray()
        for (e in items) {
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("title", e.title)
                    put("category", e.category)
                    put("amountYuan", e.amountYuan)
                    put("timestampMs", e.timestampMs)
                    put("subtitle", e.subtitle)
                }
            )
        }
        prefs.edit().putString(key(childId, "expenses"), arr.toString()).apply()
    }

    fun addInventoryAndExpense(childId: Int, item: InventoryItem, recordExpense: Boolean = true) {
        val inv = getInventory(childId).toMutableList()
        inv.add(0, item)
        saveInventory(childId, inv)
        if (recordExpense && item.priceYuan > 0.0) {
            val exps = getExpenses(childId).toMutableList()
            exps.add(
                0,
                ExpenseRecord(
                    title = item.name,
                    category = item.category,
                    amountYuan = item.priceYuan,
                    subtitle = item.specText
                )
            )
            saveExpenses(childId, exps)
        }
    }

    fun restockInventoryItem(childId: Int, itemId: String) {
        val inv = getInventory(childId).toMutableList()
        val idx = inv.indexOfFirst { it.id == itemId }
        if (idx >= 0) {
            val item = inv[idx]
            item.remainingUnits += item.totalUnits.coerceAtLeast(1)
            val newTotal = item.remainingUnits.coerceAtLeast(item.totalUnits)
            inv[idx] = item.copy(totalUnits = newTotal)
            saveInventory(childId, inv)
            if (item.priceYuan > 0.0) {
                val exps = getExpenses(childId).toMutableList()
                exps.add(
                    0,
                    ExpenseRecord(
                        title = "${item.name}（补货）",
                        category = item.category,
                        amountYuan = item.priceYuan,
                        subtitle = "补货入库"
                    )
                )
                saveExpenses(childId, exps)
            }
        }
    }

    fun markInventoryUsedUp(childId: Int, itemId: String) {
        val inv = getInventory(childId).toMutableList()
        val idx = inv.indexOfFirst { it.id == itemId }
        if (idx >= 0) {
            inv[idx].remainingUnits = 0
            saveInventory(childId, inv)
        }
    }

    fun consumeOneUnitByCategory(childId: Int, category: String) {
        val inv = getInventory(childId).toMutableList()
        val idx = inv.indexOfFirst { it.category == category && it.remainingUnits > 0 }
        if (idx >= 0) {
            inv[idx].remainingUnits = (inv[idx].remainingUnits - 1).coerceAtLeast(0)
            saveInventory(childId, inv)
        }
    }

    fun addStandaloneExpense(childId: Int, record: ExpenseRecord) {
        val exps = getExpenses(childId).toMutableList()
        exps.add(0, record)
        saveExpenses(childId, exps)
    }

    // ===================== GROWTH MOMENTS & MEDICATION =====================

    fun getGrowthMoments(childId: Int): List<GrowthMoment> {
        val raw = prefs.getString(key(childId, "moments"), null)
        if (raw.isNullOrBlank()) {
            val today = LocalDate.now().toString()
            val defaults = listOf(
                GrowthMoment(
                    tag = "#大运动",
                    title = "抬头更稳了",
                    content = "趴卧时能保持更久，脖颈力量明显进步，表情很开心。",
                    dateStr = today
                ),
                GrowthMoment(
                    tag = "#体检",
                    title = "儿保测量记录",
                    content = "身高体重头围稳步增长，发育曲线用统计页的 WHO 参考线展示。",
                    dateStr = LocalDate.now().minusDays(12).toString()
                )
            )
            saveGrowthMoments(childId, defaults)
            return defaults
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<GrowthMoment>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    GrowthMoment(
                        id = o.optString("id"),
                        tag = o.optString("tag", "#成长"),
                        title = o.optString("title"),
                        content = o.optString("content"),
                        dateStr = o.optString("dateStr"),
                        timestampMs = o.optLong("timestampMs", System.currentTimeMillis())
                    )
                )
            }
            list.sortedByDescending { it.timestampMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveGrowthMoments(childId: Int, moments: List<GrowthMoment>) {
        val arr = JSONArray()
        for (m in moments) {
            arr.put(
                JSONObject().apply {
                    put("id", m.id)
                    put("tag", m.tag)
                    put("title", m.title)
                    put("content", m.content)
                    put("dateStr", m.dateStr)
                    put("timestampMs", m.timestampMs)
                }
            )
        }
        prefs.edit().putString(key(childId, "moments"), arr.toString()).apply()
    }

    fun addGrowthMoment(childId: Int, moment: GrowthMoment) {
        val list = getGrowthMoments(childId).toMutableList()
        list.add(0, moment)
        saveGrowthMoments(childId, list)
    }

    fun getMedications(childId: Int): List<MedicationRecord> {
        val raw = prefs.getString(key(childId, "medications"), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<MedicationRecord>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    MedicationRecord(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        dosage = o.optString("dosage"),
                        note = o.optString("note"),
                        dateStr = o.optString("dateStr"),
                        timestampMs = o.optLong("timestampMs", System.currentTimeMillis())
                    )
                )
            }
            list.sortedByDescending { it.timestampMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addMedication(childId: Int, med: MedicationRecord) {
        val list = getMedications(childId).toMutableList()
        list.add(0, med)
        val arr = JSONArray()
        for (m in list) {
            arr.put(
                JSONObject().apply {
                    put("id", m.id)
                    put("name", m.name)
                    put("dosage", m.dosage)
                    put("note", m.note)
                    put("dateStr", m.dateStr)
                    put("timestampMs", m.timestampMs)
                }
            )
        }
        prefs.edit().putString(key(childId, "medications"), arr.toString()).apply()
    }

    companion object {
        fun computeDueDate(birthDate: LocalDate, recommendedMonthAge: Int): LocalDate {
            return birthDate.plusMonths(recommendedMonthAge.toLong())
        }

        fun daysUntil(dueDate: LocalDate, today: LocalDate = LocalDate.now()): Long {
            return ChronoUnit.DAYS.between(today, dueDate)
        }

        private fun defaultStarterInventory(): List<InventoryItem> {
            return listOf(
                InventoryItem(
                    name = "帮宝适 一级帮 M码",
                    category = "diaper",
                    specText = "1片/次 · 90片/包",
                    totalUnits = 90,
                    remainingUnits = 58,
                    priceYuan = 168.0
                ),
                InventoryItem(
                    name = "好奇皇家御裤 M码",
                    category = "diaper",
                    specText = "1片/次 · 68片/包",
                    totalUnits = 68,
                    remainingUnits = 24,
                    priceYuan = 166.0
                ),
                InventoryItem(
                    name = "爱他美 卓傲 3段",
                    category = "formula",
                    specText = "900g/罐 · 约 30 次",
                    totalUnits = 30,
                    remainingUnits = 19,
                    priceYuan = 280.0
                ),
                InventoryItem(
                    name = "童年阳光 维生素D3",
                    category = "supplement",
                    specText = "1粒/天 · 90粒/盒",
                    totalUnits = 90,
                    remainingUnits = 62,
                    priceYuan = 98.0
                )
            )
        }

        private fun defaultVaccineLibrary(): List<VaccineItem> {
            return listOf(
                VaccineItem("hep_b_1", "乙肝疫苗", "乙型病毒性肝炎", true, true, "免费", 1, 3, 0, "出生 24 小时内尽早接种第 1 针"),
                VaccineItem("bcg_1", "卡介苗", "结核病", true, true, "免费", 1, 1, 0, "出生时接种，预防儿童重症结核"),
                VaccineItem("hep_b_2", "乙肝疫苗", "乙型病毒性肝炎", true, true, "免费", 2, 3, 1, "满 1 月龄接种第 2 针"),
                VaccineItem("polio_1", "脊灰灭活疫苗", "脊髓灰质炎", true, true, "免费", 1, 4, 2, "2 月龄第 1 针，可与五联疫苗统筹安排"),
                VaccineItem("dtap_1", "百白破疫苗", "百日咳、白喉、破伤风", true, true, "免费", 1, 5, 3, "3 月龄基础免疫第 1 针"),
                VaccineItem("polio_2", "脊灰灭活疫苗", "脊髓灰质炎", true, true, "免费", 2, 4, 3, "3 月龄基础免疫第 2 针"),
                VaccineItem("rotavirus_1", "五价轮状病毒疫苗", "轮状病毒肠炎", false, false, "¥312/剂", 1, 3, 2, "自费推荐苗，6-12 周龄口服第 1 剂"),
                VaccineItem("pcv13_1", "13价肺炎球菌疫苗", "肺炎球菌性疾病", false, false, "¥718/针", 1, 4, 2, "自费推荐苗，预防侵袭性肺炎球菌感染"),
                VaccineItem("dtap_2", "百白破疫苗", "百日咳、白喉、破伤风", true, true, "免费", 2, 5, 4, "4 月龄基础免疫第 2 针"),
                VaccineItem("polio_3", "脊灰减毒活疫苗", "脊髓灰质炎", true, true, "免费", 3, 4, 4, "4 月龄第 3 剂"),
                VaccineItem("dtap_3", "百白破疫苗", "百日咳、白喉、破伤风", true, true, "免费", 3, 5, 6, "6 月龄基础免疫第 3 针"),
                VaccineItem("hep_b_3", "乙肝疫苗", "乙型病毒性肝炎", true, true, "免费", 3, 3, 6, "6 月龄完成全程第 3 针"),
                VaccineItem("men_a_1", "A群流脑多糖疫苗", "流行性脑脊髓膜炎", true, true, "免费", 1, 2, 6, "6 月龄第 1 针"),
                VaccineItem("mmr_1", "麻腮风疫苗", "麻疹、腮腺炎、风疹", true, true, "免费", 1, 2, 8, "8 月龄接种第 1 针，注意接种后轻微发热观察"),
                VaccineItem("je_1", "乙脑减毒活疫苗", "流行性乙型脑炎", true, true, "免费", 1, 2, 8, "8 月龄接种第 1 针"),
                VaccineItem("varicella_1", "水痘减毒活疫苗", "水痘", false, false, "¥181/针", 1, 2, 12, "12 月龄接种第 1 针，入园前建议完成 2 针"),
                VaccineItem("mmr_2", "麻腮风疫苗", "麻疹、腮腺炎、风疹", true, true, "免费", 2, 2, 18, "18 月龄加强接种第 2 针"),
                VaccineItem("dtap_4", "百白破疫苗", "百日咳、白喉、破伤风", true, true, "免费", 4, 5, 18, "18 月龄加强针"),
                VaccineItem("hep_a_1", "甲肝减毒活疫苗", "甲型病毒性肝炎", true, true, "免费", 1, 1, 18, "18 月龄接种"),
                VaccineItem("je_2", "乙脑减毒活疫苗", "流行性乙型脑炎", true, true, "免费", 2, 2, 24, "2 周岁加强接种第 2 针"),
                VaccineItem("men_ac_1", "A+C群流脑疫苗", "A群、C群流脑", true, true, "免费", 1, 2, 36, "3 周岁接种第 1 针"),
                VaccineItem("polio_4", "脊灰疫苗加强针", "脊髓灰质炎", true, true, "免费", 4, 4, 48, "4 周岁（48 月龄）加强免疫")
            )
        }
    }
}
