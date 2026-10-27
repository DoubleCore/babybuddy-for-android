package eu.pkgsoftware.babybuddywidgets.offline

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Standalone offline-only interface: no login, URL, API client or network monitor.
 * The legacy server-backed screens remain in the codebase but are not launched.
 */
class OfflineMainActivity : AppCompatActivity() {
    private lateinit var db: OfflineDatabase
    private var activeChildId: Long = -1L
    private var filter: String? = null

    private val types = listOf(
        "feeding" to "喂养", "diaper" to "尿布", "sleep" to "睡眠",
        "pumping" to "吸奶", "tummy" to "趴卧时间", "solid" to "辅食",
        "bath" to "洗澡", "medication" to "用药", "weight" to "体重",
        "height" to "身高", "head" to "头围", "temperature" to "体温",
        "note" to "备注"
    )
    private val timedTypes = listOf("feeding", "sleep", "pumping", "tummy")

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(db.exportJson().toByteArray(Charsets.UTF_8))
            } ?: error("无法打开输出文件")
        }.onSuccess { notice("备份已保存到所选文件") }
            .onFailure { showError("导出失败", it) }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                it.readText()
            } ?: error("无法读取文件")
        }.onSuccess { content ->
            AlertDialog.Builder(this)
                .setTitle("确认恢复备份")
                .setMessage("这会覆盖当前手机里的全部宝宝档案、护理记录和计时器。建议先导出当前数据。是否继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("覆盖并恢复") { _, _ ->
                    runCatching { db.importJson(content) }
                        .onSuccess {
                            activeChildId = -1L
                            refresh()
                            notice("本地备份已恢复")
                        }
                        .onFailure { showError("恢复失败；原数据未被覆盖", it) }
                }.show()
        }.onFailure { showError("读取备份失败", it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = OfflineDatabase(this)
        activeChildId = getPreferences(MODE_PRIVATE).getLong("active_child_id", -1L)
        refresh()
    }

    override fun onDestroy() {
        db.close()
        super.onDestroy()
    }

    private fun notice(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun showError(title: String, error: Throwable) {
        AlertDialog.Builder(this).setTitle(title)
            .setMessage(error.message ?: "操作失败").setPositiveButton("确定", null).show()
    }

    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()

    private fun stack(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(24))
    }

    private fun heading(parent: LinearLayout, text: String, large: Boolean = false) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = if (large) 23f else 19f
            setPadding(0, dp(15), 0, dp(9))
        })
    }

    private fun note(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        })
    }

    private fun button(parent: LinearLayout, text: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { action() }
        })
    }

    private fun format(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))

    private fun typeName(type: String) = types.firstOrNull { it.first == type }?.second ?: type

    private fun refresh() {
        val children = db.readChildren()
        if (children.none { it.id == activeChildId }) {
            activeChildId = children.firstOrNull()?.id ?: -1L
        }
        getPreferences(MODE_PRIVATE).edit().putLong("active_child_id", activeChildId).apply()

        val scroller = ScrollView(this)
        val page = stack()
        scroller.addView(page)
        setContentView(scroller)

        heading(page, "Baby Buddy · 完全本地版", true)
        note(page, "无需账号和服务器。全部记录保存在本机 SQLite；请定期导出备份。")
        heading(page, "宝宝档案")
        if (children.isEmpty()) {
            note(page, "尚无宝宝档案，请先添加一个宝宝。")
        } else {
            val current = children.first { it.id == activeChildId }
            button(page, "当前宝宝：${current.name}（点击切换）") {
                val names = children.map { it.name }.toTypedArray()
                AlertDialog.Builder(this).setTitle("选择宝宝")
                    .setItems(names) { _, index ->
                        activeChildId = children[index].id
                        filter = null
                        refresh()
                    }.show()
            }
            button(page, "修改“${current.name}”的姓名") { editChild(current) }
        }
        button(page, "添加宝宝") { editChild(null) }
        if (activeChildId != -1L) {
            button(page, "删除当前宝宝及其全部记录") { confirmDeleteChild() }
        }

        if (activeChildId != -1L) {
            val records = db.readRecords(activeChildId)
            heading(page, "快速记录")
            note(page, "点击项目即可记下一次护理活动；时间可以修改。")
            types.forEach { (id, name) ->
                button(page, "+ $name") { editRecord(id, null) }
            }

            heading(page, "持续计时")
            note(page, "计时器存入数据库；退出应用或重启手机后仍保留开始时间。")
            val timers = db.readTimers(activeChildId)
            timedTypes.forEach { type ->
                val running = timers.firstOrNull { it.type == type }
                if (running == null) {
                    button(page, "开始${typeName(type)}计时") {
                        runCatching { db.startTimer(activeChildId, type) }
                            .onSuccess { refresh() }
                            .onFailure { showError("无法启动计时", it) }
                    }
                } else {
                    val elapsedMin = ((System.currentTimeMillis() - running.startedAt) / 60000L)
                        .coerceAtLeast(0L)
                    button(page, "结束${typeName(type)}计时（已进行 ${elapsedMin} 分钟）") {
                        finishTimer(running)
                    }
                }
            }

            heading(page, "本地统计")
            val dayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val today = records.filter { it.recordedAt >= dayStart }
            note(page, "今天共 ${today.size} 条记录： " +
                types.mapNotNull { (id, name) ->
                    val n = today.count { it.type == id }
                    if (n > 0) "$name $n" else null
                }.joinToString(" · "))
            val weekStart = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            note(page, "最近 7 天共 ${records.count { it.recordedAt >= weekStart }} 条记录；总计 ${records.size} 条。")

            heading(page, "历史记录")
            button(page, "筛选：${filter?.let { typeName(it) } ?: "全部"}") {
                val names = arrayOf("全部") + types.map { it.second }
                AlertDialog.Builder(this).setTitle("按类别筛选")
                    .setItems(names) { _, index ->
                        filter = if (index == 0) null else types[index - 1].first
                        refresh()
                    }.show()
            }
            val visible = records.filter { filter == null || it.type == filter }
            if (visible.isEmpty()) note(page, "暂无记录")
            visible.forEach { record ->
                button(page, "${typeName(record.type)} · ${format(record.recordedAt)}\n${summarize(record)}") {
                    AlertDialog.Builder(this)
                        .setTitle(typeName(record.type))
                        .setItems(arrayOf("查看 / 编辑", "删除这条记录")) { _, which ->
                            if (which == 0) editRecord(record.type, record)
                            else confirmDeleteRecord(record)
                        }.show()
                }
            }
        }

        heading(page, "数据管理")
        button(page, "导出本地 JSON 备份") {
            exportLauncher.launch("babybuddy-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.json")
        }
        button(page, "从 JSON 备份恢复") { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
        note(page, "恢复备份会覆盖本机数据。旧版 Baby Buddy 服务器中的数据不会自动迁移；请勿在迁移完成前删除服务器数据。")
    }

    private fun editChild(child: LocalChild?) {
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "宝宝姓名"
            setText(child?.name ?: "")
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        AlertDialog.Builder(this).setTitle(if (child == null) "添加宝宝" else "修改姓名")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                runCatching {
                    if (child == null) db.addChild(input.text.toString())
                    else db.renameChild(child.id, input.text.toString())
                }.onSuccess { refresh() }.onFailure { showError("保存失败", it) }
            }.show()
    }

    private fun confirmDeleteChild() {
        AlertDialog.Builder(this).setTitle("永久删除当前宝宝？")
            .setMessage("此操作将一起删除该宝宝的全部护理记录和计时器，不能撤销。建议先导出备份。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认删除") { _, _ ->
                db.deleteChild(activeChildId)
                activeChildId = -1L
                refresh()
            }.show()
    }

    private fun confirmDeleteRecord(record: LocalCareRecord) {
        AlertDialog.Builder(this).setTitle("删除记录")
            .setMessage("确认删除 ${typeName(record.type)}（${format(record.recordedAt)}）？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                runCatching { db.deleteRecord(record.id) }
                    .onSuccess { refresh() }
                    .onFailure { showError("删除失败", it) }
            }.show()
    }

    private fun fields(type: String): List<Pair<String, String>> = when (type) {
        "feeding" -> listOf("kind" to "奶/配方奶/母乳", "method" to "喂养方式", "amount_ml" to "奶量（毫升）", "duration_min" to "时长（分钟）")
        "diaper" -> listOf("kind" to "尿/便/混合", "color" to "颜色", "amount" to "量")
        "sleep" -> listOf("duration_min" to "时长（分钟）")
        "pumping" -> listOf("amount_ml" to "吸奶量（毫升）", "duration_min" to "时长（分钟）")
        "tummy" -> listOf("duration_min" to "时长（分钟）")
        "solid" -> listOf("food" to "食物", "amount_g" to "食量（克）")
        "bath" -> listOf("kind" to "洗澡方式")
        "medication" -> listOf("medicine" to "药品名称", "dose" to "剂量")
        "weight" -> listOf("value" to "体重（千克）")
        "height" -> listOf("value" to "身高（厘米）")
        "head" -> listOf("value" to "头围（厘米）")
        "temperature" -> listOf("value" to "体温（摄氏度）")
        else -> emptyList()
    }

    private fun summarize(record: LocalCareRecord): String = runCatching {
        val obj = JSONObject(record.payloadJson)
        val values = fields(record.type).mapNotNull { (key, label) ->
            obj.optString(key).takeIf { it.isNotBlank() }?.let { "$label：$it" }
        }.toMutableList()
        obj.optString("notes").takeIf { it.isNotBlank() }?.let { values.add("备注：$it") }
        values.joinToString(" · ").ifBlank { "无附加说明" }.take(180)
    }.getOrDefault("记录数据暂不可读")

    private fun finishTimer(timer: LocalTimer) {
        val now = System.currentTimeMillis()
        val minutes = ((now - timer.startedAt) / 60000L).coerceAtLeast(0L)
        AlertDialog.Builder(this).setTitle("结束${typeName(timer.type)}计时")
            .setMessage("从 ${format(timer.startedAt)} 开始，已进行 $minutes 分钟。")
            .setNeutralButton("继续计时", null)
            .setNegativeButton("放弃计时") { _, _ ->
                db.stopTimer(timer.childId, timer.type)
                refresh()
            }
            .setPositiveButton("保存为记录") { _, _ ->
                val data = JSONObject()
                    .put("duration_min", minutes.toString())
                    .put("end_at", now)
                    .put("notes", "")
                runCatching {
                    db.addRecord(timer.childId, timer.type, data.toString(), timer.startedAt)
                    db.stopTimer(timer.childId, timer.type)
                }.onSuccess { refresh() }
                    .onFailure { showError("保存计时失败", it) }
            }.show()
    }

    private fun editRecord(type: String, old: LocalCareRecord?) {
        val original = runCatching {
            if (old == null) JSONObject() else JSONObject(old.payloadJson)
        }.getOrDefault(JSONObject())
        var at = old?.recordedAt ?: System.currentTimeMillis()

        val scroller = ScrollView(this)
        val form = stack()
        scroller.addView(form)

        val dateButton = Button(this).apply {
            isAllCaps = false
            text = "记录时间：${format(at)}"
        }
        form.addView(dateButton)
        fun redrawDate() { dateButton.text = "记录时间：${format(at)}" }
        dateButton.setOnClickListener {
            val initial = Calendar.getInstance().apply { timeInMillis = at }
            DatePickerDialog(this, { _, year, month, day ->
                val selected = Calendar.getInstance().apply {
                    timeInMillis = at
                    set(year, month, day)
                }
                TimePickerDialog(this, { _, hour, minute ->
                    selected.set(Calendar.HOUR_OF_DAY, hour)
                    selected.set(Calendar.MINUTE, minute)
                    selected.set(Calendar.SECOND, 0)
                    at = selected.timeInMillis
                    redrawDate()
                }, initial.get(Calendar.HOUR_OF_DAY),
                    initial.get(Calendar.MINUTE), true).show()
            }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH),
                initial.get(Calendar.DAY_OF_MONTH)).show()
        }

        val inputs = mutableMapOf<String, EditText>()
        fields(type).forEach { (key, label) ->
            form.addView(TextView(this).apply { text = label; textSize = 15f })
            val input = EditText(this).apply {
                hint = label
                setSingleLine(true)
                setText(original.optString(key))
            }
            form.addView(input)
            inputs[key] = input
        }
        form.addView(TextView(this).apply { text = "备注"; textSize = 15f })
        val notes = EditText(this).apply {
            hint = "可选备注"
            minLines = 2
            setText(original.optString("notes"))
        }
        form.addView(notes)

        AlertDialog.Builder(this).setTitle(if (old == null) "新增${typeName(type)}" else "编辑${typeName(type)}")
            .setView(scroller)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                runCatching {
                    val payload = JSONObject(original.toString())
                    inputs.forEach { (key, input) -> payload.put(key, input.text.toString().trim()) }
                    payload.put("notes", notes.text.toString())
                    if (old == null) {
                        db.addRecord(activeChildId, type, payload.toString(), at)
                    } else {
                        check(db.updateRecord(old.id, payload.toString(), at)) { "记录不存在" }
                    }
                }.onSuccess { refresh() }
                    .onFailure { showError("记录保存失败", it) }
            }.show()
    }
}
