package eu.pkgsoftware.babybuddywidgets.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Pure local SQLite relational schema for standalone Baby Buddy.
 * Enforces `PRAGMA foreign_keys = ON` with `ON DELETE CASCADE` and composite indexes
 * across all 12 core record models, children, tags, timers, and app_data.
 */
class LocalBabyBuddyDatabase(
    context: Context,
    databaseName: String? = DATABASE_NAME,
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        if (!db.isReadOnly && databaseName != null) {
            db.enableWriteAheadLogging()
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE children (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                first_name TEXT NOT NULL,
                last_name TEXT NOT NULL DEFAULT '',
                birth_date TEXT NOT NULL,
                birth_time TEXT,
                due_date TEXT,
                sex TEXT,
                slug TEXT NOT NULL UNIQUE,
                picture_path TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE tags (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                slug TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                color TEXT NOT NULL DEFAULT '#E07A5F',
                last_used INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE entry_tags (
                entry_type TEXT NOT NULL,
                entry_id INTEGER NOT NULL,
                tag_id INTEGER NOT NULL,
                PRIMARY KEY (entry_type, entry_id, tag_id),
                FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE timers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                child_id INTEGER,
                name TEXT,
                start_millis INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (child_id) REFERENCES children(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE feedings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                child_id INTEGER NOT NULL,
                start_millis INTEGER NOT NULL,
                end_millis INTEGER NOT NULL,
                type TEXT NOT NULL,
                method TEXT NOT NULL,
                amount REAL,
                notes TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (child_id) REFERENCES children(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE sleep_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                child_id INTEGER NOT NULL,
                start_millis INTEGER NOT NULL,
                end_millis INTEGER NOT NULL,
                nap INTEGER NOT NULL DEFAULT 0,
                notes TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (child_id) REFERENCES children(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE diaper_changes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                child_id INTEGER NOT NULL,
                time_millis INTEGER NOT NULL,
                wet INTEGER NOT NULL DEFAULT 0,
                solid INTEGER NOT NULL DEFAULT 0,
                color TEXT NOT NULL DEFAULT '',
                amount REAL,
                notes TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (child_id) REFERENCES children(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE tummy_times (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                child_id INTEGER NOT NULL,
                start_millis INTEGER NOT NULL,
                end_millis INTEGER NOT NULL,
                milestone TEXT NOT NULL DEFAULT '',
                notes TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (child_id) REFERENCES children(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE pumpings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                child_id INTEGER NOT NULL,
                start_millis INTEGER NOT NULL,
                end_millis INTEGER NOT NULL,
                amount REAL NOT NULL DEFAULT 0,
                notes TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (child_id) REFERENCES children(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE medications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                child_id INTEGER NOT NULL,
                time_millis INTEGER NOT NULL,
                name TEXT NOT NULL,
                dosage REAL NOT NULL,
                dosage_unit TEXT NOT NULL DEFAULT 'ml',
                next_dose_interval_minutes INTEGER,
                notes TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (child_id) REFERENCES children(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                child_id INTEGER NOT NULL,
                time_millis INTEGER NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                image_path TEXT,
                FOREIGN KEY (child_id) REFERENCES children(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE temperatures (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                child_id INTEGER NOT NULL,
                time_millis INTEGER NOT NULL,
                temperature REAL NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (child_id) REFERENCES children(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE weights (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                child_id INTEGER NOT NULL,
                date_iso TEXT NOT NULL,
                weight REAL NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (child_id) REFERENCES children(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE heights (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                child_id INTEGER NOT NULL,
                date_iso TEXT NOT NULL,
                height REAL NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (child_id) REFERENCES children(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE head_circumferences (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                child_id INTEGER NOT NULL,
                date_iso TEXT NOT NULL,
                head_circumference REAL NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (child_id) REFERENCES children(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE bmis (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                child_id INTEGER NOT NULL,
                date_iso TEXT NOT NULL,
                bmi REAL NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (child_id) REFERENCES children(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE app_data (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                child_id INTEGER,
                app TEXT NOT NULL,
                key TEXT NOT NULL,
                value_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE (child_id, app, key),
                FOREIGN KEY (child_id) REFERENCES children(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        createIndexes(db)
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entry_tags_lookup ON entry_tags(entry_type, entry_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entry_tags_tag ON entry_tags(tag_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_timers_child ON timers(child_id, id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_feedings_child_end ON feedings(child_id, end_millis DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sleep_child_end ON sleep_records(child_id, end_millis DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_changes_child_time ON diaper_changes(child_id, time_millis DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tummy_child_end ON tummy_times(child_id, end_millis DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pumping_child_end ON pumpings(child_id, end_millis DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_medications_child_time ON medications(child_id, time_millis DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_child_time ON notes(child_id, time_millis DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_temperatures_child_time ON temperatures(child_id, time_millis DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_weights_child_date ON weights(child_id, date_iso DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_heights_child_date ON heights(child_id, date_iso DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_hc_child_date ON head_circumferences(child_id, date_iso DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bmis_child_date ON bmis(child_id, date_iso DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Non-destructive additive schema migrations will be placed here as DATABASE_VERSION evolves.
        if (oldVersion < 1) {
            onCreate(db)
        }
    }

    companion object {
        const val DATABASE_NAME = "babybuddy_standalone.db"
        const val DATABASE_VERSION = 1
    }
}
