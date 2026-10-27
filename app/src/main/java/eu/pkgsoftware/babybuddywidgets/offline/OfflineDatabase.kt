package eu.pkgsoftware.babybuddywidgets.offline

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * All user records for the offline edition live in this app-private SQLite database.
 * No network client is used. Schema upgrades deliberately never drop user data.
 */
class OfflineDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE children (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )""")
        db.execSQL("""CREATE TABLE care_records (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            child_id INTEGER NOT NULL,
            record_type TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            recorded_at INTEGER NOT NULL,
            FOREIGN KEY(child_id) REFERENCES children(id) ON DELETE CASCADE
        )""")
        db.execSQL("CREATE INDEX idx_care_records_child_time ON care_records(child_id, recorded_at)")
        createTimerTable(db)
    }

    private fun createTimerTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS active_timers (
            child_id INTEGER NOT NULL,
            record_type TEXT NOT NULL,
            started_at INTEGER NOT NULL,
            PRIMARY KEY (child_id, record_type),
            FOREIGN KEY(child_id) REFERENCES children(id) ON DELETE CASCADE
        )""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createTimerTable(db)
    }

    fun readChildren(): List<LocalChild> {
        val result = mutableListOf<LocalChild>()
        readableDatabase.query("children", arrayOf("id", "name", "created_at"), null, null,
            null, null, "id ASC").use { c ->
            while (c.moveToNext()) result.add(LocalChild(c.getLong(0), c.getString(1), c.getLong(2)))
        }
        return result
    }

    fun addChild(name: String): Long {
        require(name.isNotBlank()) { "宝宝姓名不能为空" }
        val values = ContentValues().apply {
            put("name", name.trim())
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("children", null, values)
    }

    fun renameChild(id: Long, name: String) {
        require(name.isNotBlank())
        val values = ContentValues().apply { put("name", name.trim()) }
        require(writableDatabase.update("children", values, "id = ?", arrayOf(id.toString())) == 1)
    }

    fun deleteChild(id: Long) {
        writableDatabase.delete("children", "id = ?", arrayOf(id.toString()))
    }

    fun addRecord(childId: Long, type: String, payloadJson: String, recordedAt: Long): Long {
        require(childId > 0 && type.isNotBlank())
        JSONObject(payloadJson) // validate before writing
        val values = ContentValues().apply {
            put("child_id", childId)
            put("record_type", type)
            put("payload_json", payloadJson)
            put("recorded_at", recordedAt)
        }
        return writableDatabase.insertOrThrow("care_records", null, values)
    }

    fun updateRecord(id: Long, payloadJson: String, recordedAt: Long): Boolean {
        JSONObject(payloadJson)
        val values = ContentValues().apply {
            put("payload_json", payloadJson)
            put("recorded_at", recordedAt)
        }
        return writableDatabase.update("care_records", values, "id = ?",
            arrayOf(id.toString())) == 1
    }

    fun deleteRecord(recordId: Long): Boolean =
        writableDatabase.delete("care_records", "id = ?", arrayOf(recordId.toString())) > 0

    fun readRecords(childId: Long): List<LocalCareRecord> {
        val records = mutableListOf<LocalCareRecord>()
        readableDatabase.query("care_records",
            arrayOf("id", "child_id", "record_type", "payload_json", "recorded_at"),
            "child_id = ?", arrayOf(childId.toString()), null, null,
            "recorded_at DESC, id DESC").use { c ->
            while (c.moveToNext()) records.add(LocalCareRecord(
                c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4)
            ))
        }
        return records
    }

    fun startTimer(childId: Long, type: String, at: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply {
            put("child_id", childId)
            put("record_type", type)
            put("started_at", at)
        }
        writableDatabase.insertWithOnConflict("active_timers", null, values,
            SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun stopTimer(childId: Long, type: String) {
        writableDatabase.delete("active_timers", "child_id = ? AND record_type = ?",
            arrayOf(childId.toString(), type))
    }

    fun readTimers(childId: Long): List<LocalTimer> {
        val result = mutableListOf<LocalTimer>()
        readableDatabase.query("active_timers", arrayOf("child_id", "record_type", "started_at"),
            "child_id = ?", arrayOf(childId.toString()), null, null, "started_at ASC").use { c ->
            while (c.moveToNext()) result.add(LocalTimer(c.getLong(0), c.getString(1), c.getLong(2)))
        }
        return result
    }

    /** Portable, user-initiated JSON export. Does not upload to any server. */
    fun exportJson(): String {
        val root = JSONObject().put("format", "babybuddy-offline").put("version", 1)
        val children = JSONArray()
        readChildren().forEach { child ->
            children.put(JSONObject().put("id", child.id).put("name", child.name)
                .put("created_at", child.createdAt))
        }
        val records = JSONArray()
        readableDatabase.query("care_records",
            arrayOf("id", "child_id", "record_type", "payload_json", "recorded_at"),
            null, null, null, null, "id ASC").use { c ->
            while (c.moveToNext()) records.put(JSONObject().put("id", c.getLong(0))
                .put("child_id", c.getLong(1)).put("record_type", c.getString(2))
                .put("payload", JSONObject(c.getString(3)))
                .put("recorded_at", c.getLong(4)))
        }
        val timers = JSONArray()
        readableDatabase.query("active_timers",
            arrayOf("child_id", "record_type", "started_at"), null, null,
            null, null, null).use { c ->
            while (c.moveToNext()) timers.put(JSONObject().put("child_id", c.getLong(0))
                .put("record_type", c.getString(1)).put("started_at", c.getLong(2)))
        }
        return root.put("children", children).put("records", records)
            .put("timers", timers).toString(2)
    }

    /**
     * Replace data only after explicit UI confirmation.
     * All inserts are transactional; corrupt files leave existing data unchanged.
     */
    fun importJson(text: String) {
        val root = JSONObject(text)
        require(root.getString("format") == "babybuddy-offline" && root.getInt("version") == 1) {
            "不是受支持的本地备份文件"
        }
        val children = root.getJSONArray("children")
        val records = root.getJSONArray("records")
        val timers = root.optJSONArray("timers") ?: JSONArray()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("active_timers", null, null)
            db.delete("care_records", null, null)
            db.delete("children", null, null)
            val idMap = mutableMapOf<Long, Long>()
            for (i in 0 until children.length()) {
                val child = children.getJSONObject(i)
                val value = ContentValues().apply {
                    put("name", child.getString("name"))
                    put("created_at", child.getLong("created_at"))
                }
                val newId = db.insertOrThrow("children", null, value)
                idMap[child.getLong("id")] = newId
            }
            for (i in 0 until records.length()) {
                val item = records.getJSONObject(i)
                val childId = requireNotNull(idMap[item.getLong("child_id")]) {
                    "备份数据中的宝宝档案不存在"
                }
                val value = ContentValues().apply {
                    put("child_id", childId)
                    put("record_type", item.getString("record_type"))
                    put("payload_json", item.getJSONObject("payload").toString())
                    put("recorded_at", item.getLong("recorded_at"))
                }
                db.insertOrThrow("care_records", null, value)
            }
            for (i in 0 until timers.length()) {
                val item = timers.getJSONObject(i)
                val childId = requireNotNull(idMap[item.getLong("child_id")])
                val value = ContentValues().apply {
                    put("child_id", childId)
                    put("record_type", item.getString("record_type"))
                    put("started_at", item.getLong("started_at"))
                }
                db.insertOrThrow("active_timers", null, value)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val DATABASE_NAME = "babybuddy_local.db"
        private const val DATABASE_VERSION = 2
    }
}

data class LocalChild(val id: Long, val name: String, val createdAt: Long)
data class LocalTimer(val childId: Long, val type: String, val startedAt: Long)
data class LocalCareRecord(
    val id: Long,
    val childId: Long,
    val type: String,
    val payloadJson: String,
    val recordedAt: Long
)
