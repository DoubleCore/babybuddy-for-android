package eu.pkgsoftware.babybuddywidgets.offline

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Device-local storage foundation for the offline edition.
 *
 * This class performs no network requests. Record payloads are JSON so that
 * individual care-record models can be migrated incrementally.
 *
 * Not wired to the existing API-based screens yet.
 */
class OfflineDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE children (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE care_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                child_id INTEGER NOT NULL,
                record_type TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                recorded_at INTEGER NOT NULL,
                FOREIGN KEY(child_id) REFERENCES children(id) ON DELETE CASCADE
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_care_records_child_time ON care_records(child_id, recorded_at)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No destructive migration: future schema changes must preserve user data.
        if (oldVersion != newVersion) {
            throw IllegalStateException("Offline database migration required: $oldVersion -> $newVersion")
        }
    }

    fun addChild(name: String): Long {
        require(name.isNotBlank()) { "Child name cannot be blank" }
        val values = ContentValues().apply {
            put("name", name.trim())
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("children", null, values)
    }

    fun addRecord(childId: Long, type: String, payloadJson: String, recordedAt: Long): Long {
        require(childId > 0 && type.isNotBlank() && payloadJson.isNotBlank())
        val values = ContentValues().apply {
            put("child_id", childId)
            put("record_type", type)
            put("payload_json", payloadJson)
            put("recorded_at", recordedAt)
        }
        return writableDatabase.insertOrThrow("care_records", null, values)
    }

    fun deleteRecord(recordId: Long): Boolean =
        writableDatabase.delete("care_records", "id = ?", arrayOf(recordId.toString())) > 0

    fun readRecords(childId: Long): List<LocalCareRecord> {
        val records = mutableListOf<LocalCareRecord>()
        readableDatabase.query(
            "care_records",
            arrayOf("id", "child_id", "record_type", "payload_json", "recorded_at"),
            "child_id = ?",
            arrayOf(childId.toString()),
            null, null, "recorded_at DESC, id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                records.add(
                    LocalCareRecord(
                        id = cursor.getLong(0),
                        childId = cursor.getLong(1),
                        type = cursor.getString(2),
                        payloadJson = cursor.getString(3),
                        recordedAt = cursor.getLong(4)
                    )
                )
            }
        }
        return records
    }

    companion object {
        const val DATABASE_NAME = "babybuddy_local.db"
        private const val DATABASE_VERSION = 1
    }
}

data class LocalCareRecord(
    val id: Long,
    val childId: Long,
    val type: String,
    val payloadJson: String,
    val recordedAt: Long
)
