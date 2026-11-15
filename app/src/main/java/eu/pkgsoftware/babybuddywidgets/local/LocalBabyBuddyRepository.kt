package eu.pkgsoftware.babybuddywidgets.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import eu.pkgsoftware.babybuddywidgets.AnyDateTimeDeserializer
import eu.pkgsoftware.babybuddywidgets.Constants
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient.ACTIVITIES
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient.EVENTS
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.PaginatedResult
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.BmiEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.ChangeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.FeedingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeadCircumferenceEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeightEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.IncorrectApiConfiguration
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.MedicationEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.NoteEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Profile
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.PumpingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.SleepEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TagEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TemperatureEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Timer
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TummyTimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.UserSubstruct
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.WeightEntry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass

@JsonIgnoreProperties(ignoreUnknown = true)
data class AppDataRecord(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("child_id") val childId: Int? = null,
    @JsonProperty("app") val app: String,
    @JsonProperty("key") val key: String,
    @JsonProperty("value_json") val valueJson: String,
    @JsonProperty("created_at") val createdAt: Long = System.currentTimeMillis(),
    @JsonProperty("updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

data class TagWithCount(
    val tag: TagEntry,
    val usageCount: Int,
)

/**
 * Thread-safe local SQLite repository implementing 100% of Baby Buddy's server models,
 * validation, tag relationships, timers, filtering, and reactive change notifications.
 */
class LocalBabyBuddyRepository(
    val database: LocalBabyBuddyDatabase,
    val mediaStorage: LocalMediaStorage,
    val settings: NapAndAppSettings,
) {
    fun interface ChangeListener {
        fun onRepositoryChanged(version: Long)
    }

    private val lock = Any()
    private val versionCounter = AtomicLong(1L)
    private val _changes = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 32)
    val changes: SharedFlow<Long> = _changes.asSharedFlow()
    private val listeners = CopyOnWriteArraySet<ChangeListener>()

    init {
        _changes.tryEmit(versionCounter.get())
    }

    fun addChangeListener(listener: ChangeListener) {
        listeners.add(listener)
    }

    fun removeChangeListener(listener: ChangeListener) {
        listeners.remove(listener)
    }

    fun notifyChanged() {
        val next = versionCounter.incrementAndGet()
        _changes.tryEmit(next)
        for (l in listeners) {
            try {
                l.onRepositoryChanged(next)
            } catch (_: Exception) {
            }
        }
    }

    fun getLocalProfile(): Profile {
        return Profile(
            user = UserSubstruct(
                id = 1,
                username = "local",
                firstName = "Baby",
                lastName = "Buddy",
                email = "",
                isStaff = true,
            ),
            language = Locale.getDefault().toLanguageTag(),
            timezone = TimeZone.getDefault().id,
            apiKey = "standalone-local",
        )
    }

    // =========================================================================
    // Children CRUD
    // =========================================================================

    fun listChildren(idFilter: Int? = null, slugFilter: String? = null): List<Child> = synchronized(lock) {
        val db = database.readableDatabase
        val whereParts = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (idFilter != null) {
            whereParts.add("id = ?")
            args.add(idFilter.toString())
        }
        if (!slugFilter.isNullOrBlank()) {
            whereParts.add("slug = ?")
            args.add(slugFilter)
        }
        val whereClause = if (whereParts.isEmpty()) null else whereParts.joinToString(" AND ")
        val out = mutableListOf<Child>()
        db.query(
            "children",
            null,
            whereClause,
            if (args.isEmpty()) null else args.toTypedArray(),
            null,
            null,
            "last_name COLLATE NOCASE ASC, first_name COLLATE NOCASE ASC, id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(readChildRow(c))
            }
        }
        out
    }

    fun getChildById(id: Int): Child? = listChildren(idFilter = id).firstOrNull()

    fun createChild(
        firstName: String,
        lastName: String = "",
        birthDate: Date,
        birthTime: String? = null,
        dueDate: Date? = null,
        sex: String? = null,
        picturePath: String? = null,
        explicitSlug: String? = null,
    ): Child {
        val created = synchronized(lock) {
            val db = database.writableDatabase
            val cleanFirst = firstName.trim().ifEmpty { throw ValidationException("First name is required.") }
            val cleanLast = lastName.trim()
            val existingSlugs = queryAllSlugs(db, "children")
            val rawSlugSource = explicitSlug?.trim()?.takeIf { it.isNotEmpty() } ?: "$cleanFirst $cleanLast".trim()
            val slug = ModelValidators.slugifyUnique(rawSlugSource, existingSlugs, fallbackPrefix = "baby")

            val values = ContentValues().apply {
                put("first_name", cleanFirst)
                put("last_name", cleanLast)
                put("birth_date", ModelValidators.formatIsoDate(birthDate))
                put("birth_time", birthTime?.trim()?.takeIf { it.isNotEmpty() })
                put("due_date", dueDate?.let { ModelValidators.formatIsoDate(it) })
                put("sex", sex?.trim()?.takeIf { it.isNotEmpty() })
                put("slug", slug)
                put("picture_path", picturePath?.trim()?.takeIf { it.isNotEmpty() })
                put("created_at", System.currentTimeMillis())
            }
            val newId = db.insertOrThrow("children", null, values).toInt()
            readChildByIdInternal(db, newId)!!
        }
        notifyChanged()
        return created
    }

    fun updateChild(
        id: Int,
        firstName: String,
        lastName: String = "",
        birthDate: Date,
        birthTime: String? = null,
        dueDate: Date? = null,
        sex: String? = null,
        picturePath: String? = null,
    ): Child {
        val updated = synchronized(lock) {
            val db = database.writableDatabase
            val existing = readChildByIdInternal(db, id)
                ?: throw IllegalArgumentException("Child #$id not found")
            val cleanFirst = firstName.trim().ifEmpty { throw ValidationException("First name is required.") }
            val cleanLast = lastName.trim()
            val newPicture = picturePath?.trim()?.takeIf { it.isNotEmpty() }
            if (existing.picture != null && existing.picture != newPicture) {
                mediaStorage.deleteMediaIfOwned(existing.picture)
            }

            val otherSlugs = queryAllSlugs(db, "children", excludeId = id)
            val desiredBase = "$cleanFirst $cleanLast".trim()
            val updatedSlug = if (existing.firstName == cleanFirst && existing.lastName == cleanLast) {
                existing.slug
            } else {
                ModelValidators.slugifyUnique(desiredBase, otherSlugs, fallbackPrefix = "baby")
            }

            val values = ContentValues().apply {
                put("first_name", cleanFirst)
                put("last_name", cleanLast)
                put("birth_date", ModelValidators.formatIsoDate(birthDate))
                put("birth_time", birthTime?.trim()?.takeIf { it.isNotEmpty() })
                put("due_date", dueDate?.let { ModelValidators.formatIsoDate(it) })
                put("sex", sex?.trim()?.takeIf { it.isNotEmpty() })
                put("slug", updatedSlug)
                put("picture_path", newPicture)
            }
            db.update("children", values, "id = ?", arrayOf(id.toString()))
            readChildByIdInternal(db, id)!!
        }
        notifyChanged()
        return updated
    }

    fun deleteChild(id: Int) {
        synchronized(lock) {
            val db = database.writableDatabase
            val existing = readChildByIdInternal(db, id) ?: return
            db.beginTransaction()
            try {
                // Collect note image paths for this child before deleting rows
                val noteImages = mutableListOf<String>()
                db.rawQuery("SELECT image_path FROM notes WHERE child_id = ? AND image_path IS NOT NULL", arrayOf(id.toString())).use { c ->
                    while (c.moveToNext()) {
                        c.getString(0)?.takeIf { it.isNotBlank() }?.let { noteImages.add(it) }
                    }
                }
                // Clean entry_tags belonging to this child's entries
                for ((entryType, tableName) in ENTRY_TYPE_TO_TABLE) {
                    db.execSQL(
                        "DELETE FROM entry_tags WHERE entry_type = ? AND entry_id IN (SELECT id FROM $tableName WHERE child_id = ?)",
                        arrayOf(entryType, id.toString())
                    )
                }
                db.delete("children", "id = ?", arrayOf(id.toString()))
                db.setTransactionSuccessful()

                existing.picture?.let { mediaStorage.deleteMediaIfOwned(it) }
                noteImages.forEach { mediaStorage.deleteMediaIfOwned(it) }
            } finally {
                db.endTransaction()
            }
        }
        notifyChanged()
    }

    private fun readChildByIdInternal(db: SQLiteDatabase, id: Int): Child? {
        db.query("children", null, "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
            return if (c.moveToFirst()) readChildRow(c) else null
        }
    }

    private fun readChildRow(c: Cursor): Child {
        val id = c.getInt(c.getColumnIndexOrThrow("id"))
        val firstName = c.getString(c.getColumnIndexOrThrow("first_name")) ?: ""
        val lastName = c.getString(c.getColumnIndexOrThrow("last_name")) ?: ""
        val birthIso = c.getString(c.getColumnIndexOrThrow("birth_date")) ?: "2025-01-01"
        val birthTime = c.getStringOrNull("birth_time")
        val dueIso = c.getStringOrNull("due_date")
        val sex = c.getStringOrNull("sex")
        val slug = c.getString(c.getColumnIndexOrThrow("slug")) ?: "baby-$id"
        val picture = c.getStringOrNull("picture_path")

        return Child(
            id = id,
            firstName = firstName,
            lastName = lastName,
            birthData = ModelValidators.parseIsoDateAtNoon(birthIso),
            slug = slug,
            picture = picture,
            birthTime = birthTime,
            dueDate = dueIso?.let { ModelValidators.parseIsoDateAtNoon(it) },
            sex = sex,
        )
    }

    // =========================================================================
    // Tags CRUD
    // =========================================================================

    fun listTags(): List<TagEntry> = synchronized(lock) {
        val db = database.readableDatabase
        val out = mutableListOf<TagEntry>()
        db.query("tags", null, null, null, null, null, "last_used DESC, name COLLATE NOCASE ASC").use { c ->
            while (c.moveToNext()) {
                out.add(readTagRow(c))
            }
        }
        out
    }

    fun listTagsWithCounts(): List<TagWithCount> = synchronized(lock) {
        val db = database.readableDatabase
        val counts = mutableMapOf<Int, Int>()
        db.rawQuery("SELECT tag_id, COUNT(*) FROM entry_tags GROUP BY tag_id", null).use { c ->
            while (c.moveToNext()) {
                counts[c.getInt(0)] = c.getInt(1)
            }
        }
        listTags().map { tag -> TagWithCount(tag, counts[tag.id] ?: 0) }
    }

    fun getTagColorMap(): Map<String, TagEntry> = synchronized(lock) {
        val map = mutableMapOf<String, TagEntry>()
        for (t in listTags()) {
            map[t.name] = t
            map[t.slug] = t
        }
        map
    }

    fun createTag(name: String, color: String? = null): TagEntry {
        val created = synchronized(lock) {
            val db = database.writableDatabase
            getOrCreateTagInternal(db, name, color)
        }
        notifyChanged()
        return created
    }

    fun updateTag(id: Int, newName: String, newColor: String): TagEntry {
        val updated = synchronized(lock) {
            val db = database.writableDatabase
            val cleanName = newName.trim().ifEmpty { throw ValidationException("Tag name is required.") }
            val cleanColor = normalizeHexColor(newColor)
            val otherSlugs = queryAllSlugs(db, "tags", excludeId = id)
            val slug = ModelValidators.slugifyUnique(cleanName, otherSlugs, fallbackPrefix = "tag")

            val values = ContentValues().apply {
                put("name", cleanName)
                put("slug", slug)
                put("color", cleanColor)
                put("last_used", System.currentTimeMillis())
            }
            db.update("tags", values, "id = ?", arrayOf(id.toString()))
            db.query("tags", null, "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
                if (!c.moveToFirst()) throw IllegalArgumentException("Tag #$id not found")
                readTagRow(c)
            }
        }
        notifyChanged()
        return updated
    }

    fun deleteTag(id: Int) {
        synchronized(lock) {
            val db = database.writableDatabase
            db.delete("tags", "id = ?", arrayOf(id.toString()))
        }
        notifyChanged()
    }

    private fun getOrCreateTagInternal(db: SQLiteDatabase, rawName: String, preferredColor: String? = null): TagEntry {
        val cleanName = rawName.trim()
        db.query("tags", null, "name = ? OR slug = ?", arrayOf(cleanName, cleanName), null, null, null).use { c ->
            if (c.moveToFirst()) {
                val existing = readTagRow(c)
                val now = System.currentTimeMillis()
                db.update(
                    "tags",
                    ContentValues().apply { put("last_used", now) },
                    "id = ?",
                    arrayOf(existing.id.toString())
                )
                return existing.copy(lastUsed = Date(now))
            }
        }
        val existingSlugs = queryAllSlugs(db, "tags")
        val slug = ModelValidators.slugifyUnique(cleanName, existingSlugs, fallbackPrefix = "tag")
        val color = preferredColor?.let { normalizeHexColor(it) }
            ?: TagEntry.DEFAULT_COLORS[(existingSlugs.size) % TagEntry.DEFAULT_COLORS.size]
        val now = System.currentTimeMillis()
        val id = db.insertOrThrow(
            "tags",
            null,
            ContentValues().apply {
                put("slug", slug)
                put("name", cleanName)
                put("color", color)
                put("last_used", now)
            }
        ).toInt()
        return TagEntry(id = id, slug = slug, name = cleanName, color = color, lastUsed = Date(now))
    }

    private fun readTagRow(c: Cursor): TagEntry {
        return TagEntry(
            id = c.getInt(c.getColumnIndexOrThrow("id")),
            slug = c.getString(c.getColumnIndexOrThrow("slug")) ?: "",
            name = c.getString(c.getColumnIndexOrThrow("name")) ?: "",
            color = c.getString(c.getColumnIndexOrThrow("color")) ?: TagEntry.DEFAULT_COLORS[0],
            lastUsed = Date(c.getLong(c.getColumnIndexOrThrow("last_used"))),
        )
    }

    private fun normalizeHexColor(raw: String): String {
        val trimmed = raw.trim()
        val hex = trimmed.removePrefix("#")
        return if (hex.length == 6 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "#${hex.uppercase(Locale.US)}"
        } else {
            TagEntry.DEFAULT_COLORS[0]
        }
    }

    private fun setTagsForEntryInternal(db: SQLiteDatabase, entryType: String, entryId: Int, tagNames: List<String>) {
        db.delete("entry_tags", "entry_type = ? AND entry_id = ?", arrayOf(entryType, entryId.toString()))
        val distinct = tagNames.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }
        for (name in distinct) {
            val tag = getOrCreateTagInternal(db, name)
            db.insertWithOnConflict(
                "entry_tags",
                null,
                ContentValues().apply {
                    put("entry_type", entryType)
                    put("entry_id", entryId)
                    put("tag_id", tag.id)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
        }
    }

    private fun loadTagsForEntriesInternal(db: SQLiteDatabase, entryType: String, entryIds: Collection<Int>): Map<Int, List<String>> {
        if (entryIds.isEmpty()) return emptyMap()
        val placeholders = entryIds.joinToString(",") { "?" }
        val args = ArrayList<String>(entryIds.size + 1).apply {
            add(entryType)
            entryIds.forEach { add(it.toString()) }
        }
        val map = mutableMapOf<Int, MutableList<String>>()
        db.rawQuery(
            """
            SELECT et.entry_id, t.name
            FROM entry_tags et
            INNER JOIN tags t ON t.id = et.tag_id
            WHERE et.entry_type = ? AND et.entry_id IN ($placeholders)
            ORDER BY t.name COLLATE NOCASE ASC
            """.trimIndent(),
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val eId = c.getInt(0)
                val tName = c.getString(1) ?: continue
                map.getOrPut(eId) { mutableListOf() }.add(tName)
            }
        }
        return map
    }

    // =========================================================================
    // Persistent Timers CRUD
    // =========================================================================

    fun listTimers(childId: Int? = null): List<Timer> = synchronized(lock) {
        val db = database.readableDatabase
        val where = if (childId != null) "(child_id = ? OR child_id IS NULL)" else null
        val args = if (childId != null) arrayOf(childId.toString()) else null
        val out = mutableListOf<Timer>()
        db.query("timers", null, where, args, null, null, "id ASC").use { c ->
            while (c.moveToNext()) {
                out.add(readTimerRow(c))
            }
        }
        out
    }

    fun createTimer(childId: Int?, name: String?, startMillis: Long = System.currentTimeMillis()): Timer {
        val created = synchronized(lock) {
            val db = database.writableDatabase
            val cleanName = name?.trim()?.takeIf { it.isNotEmpty() }
            val bbAppSuffix = cleanName?.let { Regex("-bbapp:([0-9]+)$", RegexOption.IGNORE_CASE).find(it)?.value }
            if (childId != null && cleanName != null && (cleanName in ACTIVITIES.ALL || bbAppSuffix != null)) {
                val queryWhere = if (bbAppSuffix != null) {
                    "child_id = ? AND (LOWER(name) LIKE ? OR name = ?)"
                } else {
                    "child_id = ? AND name = ?"
                }
                val queryArgs = if (bbAppSuffix != null) {
                    arrayOf(childId.toString(), "%${bbAppSuffix.lowercase(Locale.ROOT)}", cleanName)
                } else {
                    arrayOf(childId.toString(), cleanName)
                }
                db.query(
                    "timers",
                    null,
                    queryWhere,
                    queryArgs,
                    null,
                    null,
                    "id ASC"
                ).use { existingCursor ->
                    if (existingCursor.moveToFirst()) {
                        val existing = readTimerRow(existingCursor)
                        return@synchronized existing
                    }
                }
            }
            val now = System.currentTimeMillis()
            val id = db.insertOrThrow(
                "timers",
                null,
                ContentValues().apply {
                    if (childId != null && childId > 0) put("child_id", childId) else putNull("child_id")
                    put("name", cleanName)
                    put("start_millis", startMillis)
                    put("created_at", now)
                }
            ).toInt()
            readTimerByIdInternal(db, id)!!
        }
        notifyChanged()
        return created
    }

    fun restartTimer(timerId: Int, newStartMillis: Long = System.currentTimeMillis()): Timer {
        return updateTimerStart(timerId, newStartMillis)
    }

    fun updateTimerStart(timerId: Int, newStartMillis: Long): Timer {
        val updated = synchronized(lock) {
            val db = database.writableDatabase
            db.update(
                "timers",
                ContentValues().apply { put("start_millis", newStartMillis) },
                "id = ?",
                arrayOf(timerId.toString())
            )
            readTimerByIdInternal(db, timerId) ?: throw IllegalArgumentException("Timer #$timerId not found")
        }
        notifyChanged()
        return updated
    }

    fun updateTimerName(timerId: Int, newName: String?): Timer {
        val updated = synchronized(lock) {
            val db = database.writableDatabase
            db.update(
                "timers",
                ContentValues().apply { put("name", newName?.trim()?.takeIf { it.isNotEmpty() }) },
                "id = ?",
                arrayOf(timerId.toString())
            )
            readTimerByIdInternal(db, timerId) ?: throw IllegalArgumentException("Timer #$timerId not found")
        }
        notifyChanged()
        return updated
    }

    fun deleteTimer(timerId: Int): Boolean {
        val deleted = synchronized(lock) {
            val db = database.writableDatabase
            db.delete("timers", "id = ?", arrayOf(timerId.toString())) > 0
        }
        if (deleted) notifyChanged()
        return deleted
    }

    private fun readTimerByIdInternal(db: SQLiteDatabase, timerId: Int): Timer? {
        db.query("timers", null, "id = ?", arrayOf(timerId.toString()), null, null, null).use { c ->
            return if (c.moveToFirst()) readTimerRow(c) else null
        }
    }

    private fun readTimerRow(c: Cursor): Timer {
        val id = c.getInt(c.getColumnIndexOrThrow("id"))
        val childIdx = c.getColumnIndexOrThrow("child_id")
        val childId = if (c.isNull(childIdx)) 0 else c.getInt(childIdx)
        val name = c.getStringOrNull("name") ?: "Quick timer #$id"
        val startMillis = c.getLong(c.getColumnIndexOrThrow("start_millis"))
        val elapsedSec = ((System.currentTimeMillis() - startMillis).coerceAtLeast(0L)) / 1000L
        val hh = elapsedSec / 3600L
        val mm = (elapsedSec % 3600L) / 60L
        val ss = elapsedSec % 60L
        return Timer(
            id = id,
            childId = childId,
            name = name,
            start = Date(startMillis),
            duration = "%02d:%02d:%02d".format(hh, mm, ss),
            userId = 1,
        )
    }

    // =========================================================================
    // Generic Paginated Query & CRUD across all 12 TimeEntry Models
    // =========================================================================

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getEntries(
        itemClass: KClass<T>,
        offset: Int = 0,
        limit: Int = 100,
        childId: Int? = null,
        extraArgs: Map<String, String> = emptyMap(),
    ): PaginatedResult<T> {
        val effectiveChildId = childId
            ?: extraArgs["child"]?.toIntOrNull()
            ?: extraArgs["id"]?.toIntOrNull()

        val allItems: List<T> = when (itemClass) {
            Child::class -> listChildren(
                idFilter = extraArgs["id"]?.toIntOrNull() ?: childId,
                slugFilter = extraArgs["slug"]
            ) as List<T>
            Timer::class -> listTimers(effectiveChildId) as List<T>
            TagEntry::class -> listTags() as List<T>
            SleepEntry::class -> queryTimeEntries(ACTIVITIES.SLEEP, effectiveChildId, extraArgs) as List<T>
            FeedingEntry::class -> queryTimeEntries(ACTIVITIES.FEEDING, effectiveChildId, extraArgs) as List<T>
            TummyTimeEntry::class -> queryTimeEntries(ACTIVITIES.TUMMY_TIME, effectiveChildId, extraArgs) as List<T>
            PumpingEntry::class -> queryTimeEntries(ACTIVITIES.PUMPING, effectiveChildId, extraArgs) as List<T>
            ChangeEntry::class -> queryTimeEntries(EVENTS.CHANGE, effectiveChildId, extraArgs) as List<T>
            NoteEntry::class -> queryTimeEntries(EVENTS.NOTE, effectiveChildId, extraArgs) as List<T>
            MedicationEntry::class -> queryTimeEntries(EVENTS.MEDICATION, effectiveChildId, extraArgs) as List<T>
            TemperatureEntry::class -> queryTimeEntries(EVENTS.TEMPERATURE, effectiveChildId, extraArgs) as List<T>
            WeightEntry::class -> queryTimeEntries(EVENTS.WEIGHT, effectiveChildId, extraArgs) as List<T>
            HeightEntry::class -> queryTimeEntries(EVENTS.HEIGHT, effectiveChildId, extraArgs) as List<T>
            HeadCircumferenceEntry::class -> queryTimeEntries(EVENTS.HEAD_CIRCUMFERENCE, effectiveChildId, extraArgs) as List<T>
            BmiEntry::class -> queryTimeEntries(EVENTS.BMI, effectiveChildId, extraArgs) as List<T>
            else -> throw IncorrectApiConfiguration("Unsupported model class: ${itemClass.qualifiedName}")
        }

        val total = allItems.size
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = if (limit <= 0) total else limit
        val slice = if (safeOffset >= total) {
            emptyList()
        } else {
            allItems.subList(safeOffset, minOf(total, safeOffset + safeLimit))
        }
        return PaginatedResult(entries = slice, offset = safeOffset, totalCount = total)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : TimeEntry> createEntry(item: T): T {
        val created = synchronized(lock) {
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                val res = insertOrUpdateTimeEntryInternal(db, item, isUpdate = false) as T
                db.setTransactionSuccessful()
                res
            } finally {
                db.endTransaction()
            }
        }
        notifyChanged()
        return created
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : TimeEntry> updateEntry(item: T): T {
        if (item.id <= 0) throw IllegalArgumentException("Cannot update entry without a valid id")
        val updated = synchronized(lock) {
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                val res = insertOrUpdateTimeEntryInternal(db, item, isUpdate = true) as T
                db.setTransactionSuccessful()
                res
            } finally {
                db.endTransaction()
            }
        }
        notifyChanged()
        return updated
    }

    fun deleteEntry(item: TimeEntry): Boolean {
        return deleteEntryByTypeAndId(item.appType, item.id)
    }

    fun deleteEntryByTypeAndId(appTypeOrApiPath: String, id: Int): Boolean {
        val canonicalType = normalizeEntryType(appTypeOrApiPath)
        val table = ENTRY_TYPE_TO_TABLE[canonicalType]
            ?: throw IncorrectApiConfiguration("Unknown entry type: $appTypeOrApiPath")
        val deleted = synchronized(lock) {
            val db = database.writableDatabase
            var noteImageToDelete: String? = null
            if (canonicalType == EVENTS.NOTE) {
                db.rawQuery("SELECT image_path FROM notes WHERE id = ?", arrayOf(id.toString())).use { c ->
                    if (c.moveToFirst()) {
                        noteImageToDelete = c.getString(0)
                    }
                }
            }
            db.beginTransaction()
            try {
                db.delete("entry_tags", "entry_type = ? AND entry_id = ?", arrayOf(canonicalType, id.toString()))
                val rows = db.delete(table, "id = ?", arrayOf(id.toString()))
                db.setTransactionSuccessful()
                if (rows > 0 && !noteImageToDelete.isNullOrBlank()) {
                    mediaStorage.deleteMediaIfOwned(noteImageToDelete)
                }
                rows > 0
            } finally {
                db.endTransaction()
            }
        }
        if (deleted) notifyChanged()
        return deleted
    }

    fun getEntryByTypeAndId(appTypeOrApiPath: String, id: Int): TimeEntry? = synchronized(lock) {
        val canonicalType = normalizeEntryType(appTypeOrApiPath)
        queryTimeEntriesInternal(database.readableDatabase, canonicalType, childId = null, extraArgs = mapOf("entry_id" to id.toString()))
            .firstOrNull()
    }

    /**
     * Unified timeline query across all 12 models for a child, sorted newest-first,
     * supporting activity category filter, tag filter, and keyword search.
     */
    fun getUnifiedTimeline(
        childId: Int,
        typesFilter: Set<String>? = null,
        tagFilter: String? = null,
        searchQuery: String? = null,
        limit: Int = 250,
    ): List<TimeEntry> = synchronized(lock) {
        val db = database.readableDatabase
        val extra = mutableMapOf<String, String>()
        if (!tagFilter.isNullOrBlank()) extra["tag"] = tagFilter.trim()
        if (!searchQuery.isNullOrBlank()) extra["search"] = searchQuery.trim()

        val targetTypes = typesFilter?.takeIf { it.isNotEmpty() } ?: ENTRY_TYPE_TO_TABLE.keys
        val merged = ArrayList<TimeEntry>()
        for (t in targetTypes) {
            merged.addAll(queryTimeEntriesInternal(db, t, childId, extra))
        }
        merged.sortWith(
            compareByDescending<TimeEntry> { it.end.time }
                .thenByDescending { it.start.time }
                .thenByDescending { it.id }
        )
        if (limit > 0 && merged.size > limit) merged.subList(0, limit) else merged
    }

    /**
     * Returns all timeline entries across all 12 models (optionally scoped to [childId]),
     * sorted newest-first, for statistics aggregation and full CSV export.
     */
    fun getAllTimelineEntries(
        childId: Int? = null,
        limit: Int = 0,
    ): List<TimeEntry> = synchronized(lock) {
        val db = database.readableDatabase
        val merged = ArrayList<TimeEntry>()
        for (t in ENTRY_TYPE_TO_TABLE.keys) {
            merged.addAll(queryTimeEntriesInternal(db, t, childId, emptyMap()))
        }
        merged.sortWith(
            compareByDescending<TimeEntry> { it.end.time }
                .thenByDescending { it.start.time }
                .thenByDescending { it.id }
        )
        if (limit > 0 && merged.size > limit) merged.subList(0, limit) else merged
    }

    /**
     * Returns distinct historical medication names for quick autocomplete chips.
     */
    fun getRecentMedicationNames(childId: Int? = null, limit: Int = 12): List<String> = synchronized(lock) {
        val db = database.readableDatabase
        val out = mutableListOf<String>()
        val sql = if (childId != null) {
            "SELECT name, MAX(time_millis) AS latest FROM medications WHERE child_id = ? GROUP BY name COLLATE NOCASE ORDER BY latest DESC LIMIT ?"
        } else {
            "SELECT name, MAX(time_millis) AS latest FROM medications GROUP BY name COLLATE NOCASE ORDER BY latest DESC LIMIT ?"
        }
        val args = if (childId != null) arrayOf(childId.toString(), limit.toString()) else arrayOf(limit.toString())
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0)?.trim()
                if (!n.isNullOrEmpty()) out.add(n)
            }
        }
        out
    }

    /**
     * Finds the most recent Weight (kg) and Height (cm) for a child to enable 1-tap BMI calculation.
     */
    fun getLatestWeightAndHeight(childId: Int): Pair<WeightEntry?, HeightEntry?> = synchronized(lock) {
        val db = database.readableDatabase
        val latestWeight = queryTimeEntriesInternal(db, EVENTS.WEIGHT, childId, emptyMap()).firstOrNull() as? WeightEntry
        val latestHeight = queryTimeEntriesInternal(db, EVENTS.HEIGHT, childId, emptyMap()).firstOrNull() as? HeightEntry
        latestWeight to latestHeight
    }

    /**
     * Finds overlapping timed entries (`feedings`, `sleep_records`, `tummy_times`, `pumpings`)
     * for the same child and activity type, matching upstream `validate_overlapping_period`.
     */
    fun findIntersectingTimedEntries(
        appType: String,
        childId: Int,
        startMillis: Long,
        endMillis: Long,
        excludeId: Int = 0,
    ): List<TimeEntry> = synchronized(lock) {
        if (appType !in ACTIVITIES.ALL || endMillis <= startMillis) return emptyList()
        val entries = queryTimeEntriesInternal(database.readableDatabase, appType, childId, emptyMap())
        entries.filter { existing ->
            existing.id != excludeId && existing.start.time < endMillis && existing.end.time > startMillis
        }
    }

    private fun insertOrUpdateTimeEntryInternal(
        db: SQLiteDatabase,
        item: TimeEntry,
        isUpdate: Boolean,
    ): TimeEntry {
        val canonicalType = item.appType
        val table = ENTRY_TYPE_TO_TABLE[canonicalType]
            ?: throw IncorrectApiConfiguration("Unsupported entry type: $canonicalType")

        // Optionally trim overlapping entries if user enabled autoTrimOverlappingPeriods
        if (canonicalType in ACTIVITIES.ALL) {
            ModelValidators.validateDuration(item.start.time, item.end.time)
            if (settings.autoTrimOverlappingPeriods) {
                trimOverlapsInTableInternal(db, table, item.childId, item.start.time, item.end.time, if (isUpdate) item.id else 0)
            }
        }

        val entryId: Int = when (item) {
            is FeedingEntry -> {
                val cv = ContentValues().apply {
                    put("child_id", item.childId)
                    put("start_millis", item.start.time)
                    put("end_millis", item.end.time.coerceAtLeast(item.start.time))
                    put("type", item.feedingType.post_name)
                    put("method", item.feedingMethod.post_name)
                    if (item.amount != null) put("amount", item.amount) else putNull("amount")
                    put("notes", item.notes)
                }
                writeRow(db, table, cv, item.id, isUpdate)
            }
            is SleepEntry -> {
                val resolvedNap = item.nap ?: ModelValidators.resolveAutoNap(
                    item.start.time,
                    settings.napStartMin,
                    settings.napStartMax
                )
                val cv = ContentValues().apply {
                    put("child_id", item.childId)
                    put("start_millis", item.start.time)
                    put("end_millis", item.end.time.coerceAtLeast(item.start.time))
                    put("nap", if (resolvedNap) 1 else 0)
                    put("notes", item.notes)
                }
                writeRow(db, table, cv, item.id, isUpdate)
            }
            is ChangeEntry -> {
                val cv = ContentValues().apply {
                    put("child_id", item.childId)
                    put("time_millis", item.start.time)
                    put("wet", if (item.wet) 1 else 0)
                    put("solid", if (item.solid) 1 else 0)
                    put("color", item.color)
                    if (item.amount != null) put("amount", item.amount) else putNull("amount")
                    put("notes", item.notes)
                }
                writeRow(db, table, cv, item.id, isUpdate)
            }
            is TummyTimeEntry -> {
                val cv = ContentValues().apply {
                    put("child_id", item.childId)
                    put("start_millis", item.start.time)
                    put("end_millis", item.end.time.coerceAtLeast(item.start.time))
                    put("milestone", item.milestone)
                    put("notes", item.extraNotes ?: "")
                }
                writeRow(db, table, cv, item.id, isUpdate)
            }
            is PumpingEntry -> {
                val cv = ContentValues().apply {
                    put("child_id", item.childId)
                    put("start_millis", item.start.time)
                    put("end_millis", item.end.time.coerceAtLeast(item.start.time))
                    put("amount", item.amount)
                    put("notes", item.notes)
                }
                writeRow(db, table, cv, item.id, isUpdate)
            }
            is MedicationEntry -> {
                val cleanName = item.name.trim().ifEmpty { throw ValidationException("Medication name is required.") }
                if (item.dosage <= 0.0) throw ValidationException("Medication dosage must be positive.")
                val cv = ContentValues().apply {
                    put("child_id", item.childId)
                    put("time_millis", item.start.time)
                    put("name", cleanName)
                    put("dosage", item.dosage)
                    put("dosage_unit", item.dosageUnit.post_name)
                    if (item.nextDoseIntervalMinutes != null && item.nextDoseIntervalMinutes > 0) {
                        put("next_dose_interval_minutes", item.nextDoseIntervalMinutes)
                    } else {
                        putNull("next_dose_interval_minutes")
                    }
                    put("notes", item.notes)
                }
                writeRow(db, table, cv, item.id, isUpdate)
            }
            is NoteEntry -> {
                if (isUpdate) {
                    db.rawQuery("SELECT image_path FROM notes WHERE id = ?", arrayOf(item.id.toString())).use { c ->
                        if (c.moveToFirst()) {
                            val oldImg = c.getString(0)
                            if (!oldImg.isNullOrBlank() && oldImg != item.imagePath) {
                                mediaStorage.deleteMediaIfOwned(oldImg)
                            }
                        }
                    }
                }
                val cv = ContentValues().apply {
                    put("child_id", item.childId)
                    put("time_millis", item.start.time)
                    put("note", item.notes)
                    put("image_path", item.imagePath?.trim()?.takeIf { it.isNotEmpty() })
                }
                writeRow(db, table, cv, item.id, isUpdate)
            }
            is TemperatureEntry -> {
                val cv = ContentValues().apply {
                    put("child_id", item.childId)
                    put("time_millis", item.start.time)
                    put("temperature", item.temperature)
                    put("notes", item.notes)
                }
                writeRow(db, table, cv, item.id, isUpdate)
            }
            is WeightEntry -> {
                val cv = ContentValues().apply {
                    put("child_id", item.childId)
                    put("date_iso", ModelValidators.formatIsoDate(item.start))
                    put("weight", item.weight)
                    put("notes", item.notes)
                }
                writeRow(db, table, cv, item.id, isUpdate)
            }
            is HeightEntry -> {
                val cv = ContentValues().apply {
                    put("child_id", item.childId)
                    put("date_iso", ModelValidators.formatIsoDate(item.start))
                    put("height", item.height)
                    put("notes", item.notes)
                }
                writeRow(db, table, cv, item.id, isUpdate)
            }
            is HeadCircumferenceEntry -> {
                val cv = ContentValues().apply {
                    put("child_id", item.childId)
                    put("date_iso", ModelValidators.formatIsoDate(item.start))
                    put("head_circumference", item.head_circumference)
                    put("notes", item.notes)
                }
                writeRow(db, table, cv, item.id, isUpdate)
            }
            is BmiEntry -> {
                val cv = ContentValues().apply {
                    put("child_id", item.childId)
                    put("date_iso", ModelValidators.formatIsoDate(item.start))
                    put("bmi", item.bmi)
                    put("notes", item.notes)
                }
                writeRow(db, table, cv, item.id, isUpdate)
            }
            else -> throw IncorrectApiConfiguration("Unsupported TimeEntry implementation: ${item::class.qualifiedName}")
        }

        setTagsForEntryInternal(db, canonicalType, entryId, item.tags)
        return queryTimeEntriesInternal(db, canonicalType, null, mapOf("entry_id" to entryId.toString())).first()
    }

    private fun writeRow(
        db: SQLiteDatabase,
        table: String,
        cv: ContentValues,
        existingId: Int,
        isUpdate: Boolean,
    ): Int {
        return if (isUpdate && existingId > 0) {
            db.update(table, cv, "id = ?", arrayOf(existingId.toString()))
            existingId
        } else {
            if (existingId > 0) {
                cv.put("id", existingId)
            }
            db.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_REPLACE).toInt()
        }
    }

    private fun trimOverlapsInTableInternal(
        db: SQLiteDatabase,
        table: String,
        childId: Int,
        newStart: Long,
        newEnd: Long,
        excludeId: Int,
    ) {
        if (newEnd <= newStart) return
        db.rawQuery(
            "SELECT id, start_millis, end_millis FROM $table WHERE child_id = ? AND id != ? AND start_millis < ? AND end_millis > ?",
            arrayOf(childId.toString(), excludeId.toString(), newEnd.toString(), newStart.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getInt(0)
                val oldStart = c.getLong(1)
                val oldEnd = c.getLong(2)
                if (oldStart < newStart && oldEnd <= newEnd) {
                    db.update(
                        table,
                        ContentValues().apply { put("end_millis", newStart) },
                        "id = ?",
                        arrayOf(id.toString())
                    )
                } else if (oldStart >= newStart && oldEnd > newEnd) {
                    db.update(
                        table,
                        ContentValues().apply { put("start_millis", newEnd) },
                        "id = ?",
                        arrayOf(id.toString())
                    )
                }
            }
        }
    }

    private fun queryTimeEntries(
        entryType: String,
        childId: Int?,
        extraArgs: Map<String, String>,
    ): List<TimeEntry> = synchronized(lock) {
        queryTimeEntriesInternal(database.readableDatabase, entryType, childId, extraArgs)
    }

    private fun queryTimeEntriesInternal(
        db: SQLiteDatabase,
        entryType: String,
        childId: Int?,
        extraArgs: Map<String, String>,
    ): List<TimeEntry> {
        val table = ENTRY_TYPE_TO_TABLE[entryType]
            ?: throw IncorrectApiConfiguration("Unknown entryType $entryType")

        val isDateOnly = entryType in DATE_ONLY_TYPES
        val isDuration = entryType in ACTIVITIES.ALL
        val orderBy = when {
            isDateOnly -> "date_iso DESC, id DESC"
            isDuration -> "end_millis DESC, start_millis DESC, id DESC"
            else -> "time_millis DESC, id DESC"
        }

        val whereParts = mutableListOf<String>()
        val sqlArgs = mutableListOf<String>()
        if (childId != null) {
            whereParts.add("child_id = ?")
            sqlArgs.add(childId.toString())
        }
        extraArgs["entry_id"]?.toIntOrNull()?.let { idVal ->
            whereParts.add("id = ?")
            sqlArgs.add(idVal.toString())
        }

        val rawRows = mutableListOf<Pair<Int, (List<String>) -> TimeEntry>>()
        db.query(
            table,
            null,
            if (whereParts.isEmpty()) null else whereParts.joinToString(" AND "),
            if (sqlArgs.isEmpty()) null else sqlArgs.toTypedArray(),
            null,
            null,
            orderBy
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getInt(c.getColumnIndexOrThrow("id"))
                val cId = c.getInt(c.getColumnIndexOrThrow("child_id"))
                val builder: (List<String>) -> TimeEntry = when (entryType) {
                    ACTIVITIES.FEEDING -> {
                        val start = Date(c.getLong(c.getColumnIndexOrThrow("start_millis")))
                        val end = Date(c.getLong(c.getColumnIndexOrThrow("end_millis")))
                        val fType = Constants.FeedingTypeEnum.byPostNameOrNull(c.getStringOrNull("type"))
                            ?: Constants.FeedingTypeEnum.BREAST_MILK
                        val fMethod = Constants.FeedingMethodEnum.byPostNameOrNull(c.getStringOrNull("method"))
                            ?: Constants.FeedingMethodEnum.BOTTLE
                        val amount = c.getDoubleOrNull("amount")
                        val notes = c.getStringOrNull("notes") ?: ""
                        ({ tags ->
                            FeedingEntry(
                                id = id,
                                childId = cId,
                                start = start,
                                end = end,
                                _notes = notes,
                                feedingType = fType,
                                feedingMethod = fMethod,
                                amount = amount,
                                tags = tags,
                            )
                        })
                    }
                    ACTIVITIES.SLEEP -> {
                        val start = Date(c.getLong(c.getColumnIndexOrThrow("start_millis")))
                        val end = Date(c.getLong(c.getColumnIndexOrThrow("end_millis")))
                        val nap = c.getInt(c.getColumnIndexOrThrow("nap")) != 0
                        val notes = c.getStringOrNull("notes") ?: ""
                        ({ tags ->
                            SleepEntry(
                                id = id,
                                childId = cId,
                                start = start,
                                end = end,
                                _notes = notes,
                                nap = nap,
                                tags = tags,
                            )
                        })
                    }
                    EVENTS.CHANGE -> {
                        val time = Date(c.getLong(c.getColumnIndexOrThrow("time_millis")))
                        val wet = c.getInt(c.getColumnIndexOrThrow("wet")) != 0
                        val solid = c.getInt(c.getColumnIndexOrThrow("solid")) != 0
                        val color = c.getStringOrNull("color") ?: ""
                        val amount = c.getDoubleOrNull("amount")
                        val notes = c.getStringOrNull("notes") ?: ""
                        ({ tags ->
                            ChangeEntry(
                                id = id,
                                childId = cId,
                                start = time,
                                _notes = notes,
                                wet = wet,
                                solid = solid,
                                color = color,
                                amount = amount,
                                tags = tags,
                            )
                        })
                    }
                    ACTIVITIES.TUMMY_TIME -> {
                        val start = Date(c.getLong(c.getColumnIndexOrThrow("start_millis")))
                        val end = Date(c.getLong(c.getColumnIndexOrThrow("end_millis")))
                        val milestone = c.getStringOrNull("milestone") ?: ""
                        val notes = c.getStringOrNull("notes") ?: ""
                        ({ tags ->
                            TummyTimeEntry(
                                id = id,
                                childId = cId,
                                start = start,
                                end = end,
                                _notes = milestone,
                                extraNotes = notes,
                                tags = tags,
                            )
                        })
                    }
                    ACTIVITIES.PUMPING -> {
                        val start = Date(c.getLong(c.getColumnIndexOrThrow("start_millis")))
                        val end = Date(c.getLong(c.getColumnIndexOrThrow("end_millis")))
                        val amount = c.getDouble(c.getColumnIndexOrThrow("amount"))
                        val notes = c.getStringOrNull("notes") ?: ""
                        ({ tags ->
                            PumpingEntry(
                                id = id,
                                childId = cId,
                                _start = start,
                                _end = end,
                                _notes = notes,
                                amount = amount,
                                tags = tags,
                            )
                        })
                    }
                    EVENTS.MEDICATION -> {
                        val time = Date(c.getLong(c.getColumnIndexOrThrow("time_millis")))
                        val name = c.getStringOrNull("name") ?: ""
                        val dosage = c.getDouble(c.getColumnIndexOrThrow("dosage"))
                        val unit = Constants.MedicationDosageUnitEnum.byPostName(c.getStringOrNull("dosage_unit") ?: "ml")
                        val interval = c.getIntOrNull("next_dose_interval_minutes")
                        val notes = c.getStringOrNull("notes") ?: ""
                        ({ tags ->
                            MedicationEntry(
                                id = id,
                                childId = cId,
                                start = time,
                                name = name,
                                dosage = dosage,
                                dosageUnit = unit,
                                nextDoseIntervalMinutes = interval,
                                _notes = notes,
                                tags = tags,
                            )
                        })
                    }
                    EVENTS.NOTE -> {
                        val time = Date(c.getLong(c.getColumnIndexOrThrow("time_millis")))
                        val note = c.getStringOrNull("note") ?: ""
                        val imagePath = c.getStringOrNull("image_path")
                        ({ tags ->
                            NoteEntry(
                                id = id,
                                childId = cId,
                                start = time,
                                _notes = note,
                                imagePath = imagePath,
                                tags = tags,
                            )
                        })
                    }
                    EVENTS.TEMPERATURE -> {
                        val time = Date(c.getLong(c.getColumnIndexOrThrow("time_millis")))
                        val temp = c.getDouble(c.getColumnIndexOrThrow("temperature"))
                        val notes = c.getStringOrNull("notes") ?: ""
                        ({ tags ->
                            TemperatureEntry(
                                id = id,
                                childId = cId,
                                start = time,
                                _notes = notes,
                                temperature = temp,
                                tags = tags,
                            )
                        })
                    }
                    EVENTS.WEIGHT -> {
                        val date = ModelValidators.parseIsoDateAtNoon(c.getStringOrNull("date_iso") ?: "2025-01-01")
                        val weight = c.getDouble(c.getColumnIndexOrThrow("weight"))
                        val notes = c.getStringOrNull("notes") ?: ""
                        ({ tags ->
                            WeightEntry(
                                id = id,
                                childId = cId,
                                start = date,
                                _notes = notes,
                                weight = weight,
                                tags = tags,
                            )
                        })
                    }
                    EVENTS.HEIGHT -> {
                        val date = ModelValidators.parseIsoDateAtNoon(c.getStringOrNull("date_iso") ?: "2025-01-01")
                        val height = c.getDouble(c.getColumnIndexOrThrow("height"))
                        val notes = c.getStringOrNull("notes") ?: ""
                        ({ tags ->
                            HeightEntry(
                                id = id,
                                childId = cId,
                                start = date,
                                _notes = notes,
                                height = height,
                                tags = tags,
                            )
                        })
                    }
                    EVENTS.HEAD_CIRCUMFERENCE -> {
                        val date = ModelValidators.parseIsoDateAtNoon(c.getStringOrNull("date_iso") ?: "2025-01-01")
                        val hc = c.getDouble(c.getColumnIndexOrThrow("head_circumference"))
                        val notes = c.getStringOrNull("notes") ?: ""
                        ({ tags ->
                            HeadCircumferenceEntry(
                                id = id,
                                childId = cId,
                                start = date,
                                _notes = notes,
                                head_circumference = hc,
                                tags = tags,
                            )
                        })
                    }
                    EVENTS.BMI -> {
                        val date = ModelValidators.parseIsoDateAtNoon(c.getStringOrNull("date_iso") ?: "2025-01-01")
                        val bmi = c.getDouble(c.getColumnIndexOrThrow("bmi"))
                        val notes = c.getStringOrNull("notes") ?: ""
                        ({ tags ->
                            BmiEntry(
                                id = id,
                                childId = cId,
                                start = date,
                                _notes = notes,
                                bmi = bmi,
                                tags = tags,
                            )
                        })
                    }
                    else -> throw IncorrectApiConfiguration("Unexpected entryType $entryType")
                }
                rawRows.add(id to builder)
            }
        }

        val tagsMap = loadTagsForEntriesInternal(db, entryType, rawRows.map { it.first })
        var entries = rawRows.map { (id, builder) -> builder(tagsMap[id] ?: emptyList()) }

        // Apply optional date/time range, tag, and keyword filters from extraArgs
        val minBound = parseOptionalDateBound(
            extraArgs["start_min"] ?: extraArgs["time_min"] ?: extraArgs["date_min"]
        )
        val maxBound = parseOptionalDateBound(
            extraArgs["end_max"] ?: extraArgs["start_max"] ?: extraArgs["time_max"] ?: extraArgs["date_max"],
            endOfDayIfDateOnly = true
        )
        if (minBound != null) {
            entries = entries.filter { it.end.time >= minBound }
        }
        if (maxBound != null) {
            entries = entries.filter { it.start.time <= maxBound }
        }

        val tagFilter = (extraArgs["tag"] ?: extraArgs["tags"])?.trim()?.takeIf { it.isNotEmpty() }
        if (tagFilter != null) {
            val tagColorMap = getTagColorMapInternal(db)
            val resolvedName = tagColorMap[tagFilter]?.name ?: tagFilter
            entries = entries.filter { entry ->
                entry.tags.any { t ->
                    t.equals(tagFilter, ignoreCase = true) || t.equals(resolvedName, ignoreCase = true)
                }
            }
        }

        val search = extraArgs["search"]?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        if (search != null) {
            entries = entries.filter { entry -> matchesSearchQuery(entry, search) }
        }

        return entries
    }

    private fun getTagColorMapInternal(db: SQLiteDatabase): Map<String, TagEntry> {
        val map = mutableMapOf<String, TagEntry>()
        db.query("tags", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                val t = readTagRow(c)
                map[t.name] = t
                map[t.slug] = t
            }
        }
        return map
    }

    private fun matchesSearchQuery(entry: TimeEntry, queryLower: String): Boolean {
        if (entry.notes.lowercase(Locale.ROOT).contains(queryLower)) return true
        if (entry.tags.any { it.lowercase(Locale.ROOT).contains(queryLower) }) return true
        return when (entry) {
            is FeedingEntry -> entry.feedingType.post_name.contains(queryLower) ||
                entry.feedingMethod.post_name.contains(queryLower)
            is ChangeEntry -> entry.color.lowercase(Locale.ROOT).contains(queryLower)
            is TummyTimeEntry -> entry.milestone.lowercase(Locale.ROOT).contains(queryLower) ||
                (entry.extraNotes?.lowercase(Locale.ROOT)?.contains(queryLower) == true)
            is MedicationEntry -> entry.name.lowercase(Locale.ROOT).contains(queryLower) ||
                entry.dosageUnit.post_name.contains(queryLower)
            else -> false
        }
    }

    private fun parseOptionalDateBound(raw: String?, endOfDayIfDateOnly: Boolean = false): Long? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.length == 10 && trimmed[4] == '-' && trimmed[7] == '-') {
            val noon = ModelValidators.parseIsoDateAtNoon(trimmed)
            return if (endOfDayIfDateOnly) noon.time + 12L * 3600_000L - 1L else noon.time - 12L * 3600_000L
        }
        val isoPatterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
        )
        for (fmtStr in isoPatterns) {
            try {
                val sdf = SimpleDateFormat(fmtStr, Locale.ENGLISH)
                return sdf.parse(trimmed)?.time
            } catch (_: Exception) {
            }
        }
        return null
    }

    // =========================================================================
    // AppData key-value extension table (Baby Buddy server `AppData` parity)
    // =========================================================================

    fun setAppData(childId: Int?, app: String, key: String, valueJson: String) {
        synchronized(lock) {
            val db = database.writableDatabase
            val now = System.currentTimeMillis()
            db.insertWithOnConflict(
                "app_data",
                null,
                ContentValues().apply {
                    if (childId != null) put("child_id", childId) else putNull("child_id")
                    put("app", app)
                    put("key", key)
                    put("value_json", valueJson)
                    put("created_at", now)
                    put("updated_at", now)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
        notifyChanged()
    }

    fun getAppData(childId: Int?, app: String, key: String): String? = synchronized(lock) {
        val db = database.readableDatabase
        val where = if (childId != null) "child_id = ? AND app = ? AND key = ?" else "child_id IS NULL AND app = ? AND key = ?"
        val args = if (childId != null) arrayOf(childId.toString(), app, key) else arrayOf(app, key)
        db.query("app_data", arrayOf("value_json"), where, args, null, null, null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun listAllAppData(): List<AppDataRecord> = synchronized(lock) {
        val out = mutableListOf<AppDataRecord>()
        database.readableDatabase.query("app_data", null, null, null, null, null, "id ASC").use { c ->
            while (c.moveToNext()) {
                out.add(
                    AppDataRecord(
                        id = c.getInt(c.getColumnIndexOrThrow("id")),
                        childId = c.getIntOrNull("child_id"),
                        app = c.getString(c.getColumnIndexOrThrow("app")) ?: "",
                        key = c.getString(c.getColumnIndexOrThrow("key")) ?: "",
                        valueJson = c.getString(c.getColumnIndexOrThrow("value_json")) ?: "{}",
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
                    )
                )
            }
        }
        out
    }

    // =========================================================================
    // Convenience Domain Queries (Medication History & Auto-BMI)
    // =========================================================================

    fun listDistinctMedicationNames(childId: Int? = null, limit: Int = 12): List<String> = synchronized(lock) {
        val db = database.readableDatabase
        val sql = if (childId != null) {
            "SELECT name, MAX(time_millis) AS latest FROM medications WHERE child_id = ? GROUP BY name ORDER BY latest DESC LIMIT ?"
        } else {
            "SELECT name, MAX(time_millis) AS latest FROM medications GROUP BY name ORDER BY latest DESC LIMIT ?"
        }
        val args = if (childId != null) arrayOf(childId.toString(), limit.toString()) else arrayOf(limit.toString())
        val names = mutableListOf<String>()
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0)?.trim()
                if (!n.isNullOrEmpty()) names.add(n)
            }
        }
        names
    }

    data class SuggestedBmi(
        val weightKg: Double,
        val heightCm: Double,
        val bmi: Double,
    )

    fun computeSuggestedBmi(childId: Int): SuggestedBmi? = synchronized(lock) {
        val db = database.readableDatabase
        var latestWeight: Double? = null
        var latestHeight: Double? = null
        db.rawQuery(
            "SELECT weight FROM weights WHERE child_id = ? ORDER BY date_iso DESC, id DESC LIMIT 1",
            arrayOf(childId.toString())
        ).use { c ->
            if (c.moveToFirst()) latestWeight = c.getDouble(0)
        }
        db.rawQuery(
            "SELECT height FROM heights WHERE child_id = ? ORDER BY date_iso DESC, id DESC LIMIT 1",
            arrayOf(childId.toString())
        ).use { c ->
            if (c.moveToFirst()) latestHeight = c.getDouble(0)
        }
        val w = latestWeight ?: return@synchronized null
        val h = latestHeight ?: return@synchronized null
        val bmi = ModelValidators.calculateBmi(w, h) ?: return@synchronized null
        SuggestedBmi(weightKg = w, heightCm = h, bmi = bmi)
    }

    // =========================================================================
    // Bulk Clear & Transaction Helpers for Backup/Restore
    // =========================================================================

    fun clearAllData(deleteMediaFiles: Boolean = true) {
        synchronized(lock) {
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                db.delete("entry_tags", null, null)
                db.delete("timers", null, null)
                for (table in ENTRY_TYPE_TO_TABLE.values) {
                    db.delete(table, null, null)
                }
                db.delete("app_data", null, null)
                db.delete("tags", null, null)
                db.delete("children", null, null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (deleteMediaFiles) {
                mediaStorage.cleanupOrphanedMedia(emptySet())
            }
        }
        notifyChanged()
    }

    fun <R> runInTransaction(block: () -> R): R = synchronized(lock) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val res = block()
            db.setTransactionSuccessful()
            res
        } finally {
            db.endTransaction()
        }
    }

    private fun queryAllSlugs(db: SQLiteDatabase, table: String, excludeId: Int? = null): Set<String> {
        val out = mutableSetOf<String>()
        val where = if (excludeId != null) "id != ?" else null
        val args = if (excludeId != null) arrayOf(excludeId.toString()) else null
        db.query(table, arrayOf("slug"), where, args, null, null, null).use { c ->
            while (c.moveToNext()) {
                c.getString(0)?.let { out.add(it) }
            }
        }
        return out
    }

    companion object {
        val ENTRY_TYPE_TO_TABLE = linkedMapOf(
            ACTIVITIES.FEEDING to "feedings",
            ACTIVITIES.SLEEP to "sleep_records",
            EVENTS.CHANGE to "diaper_changes",
            ACTIVITIES.TUMMY_TIME to "tummy_times",
            ACTIVITIES.PUMPING to "pumpings",
            EVENTS.MEDICATION to "medications",
            EVENTS.NOTE to "notes",
            EVENTS.TEMPERATURE to "temperatures",
            EVENTS.WEIGHT to "weights",
            EVENTS.HEIGHT to "heights",
            EVENTS.HEAD_CIRCUMFERENCE to "head_circumferences",
            EVENTS.BMI to "bmis",
        )

        val DATE_ONLY_TYPES = setOf(
            EVENTS.WEIGHT,
            EVENTS.HEIGHT,
            EVENTS.HEAD_CIRCUMFERENCE,
            EVENTS.BMI,
        )

        fun normalizeEntryType(raw: String): String {
            return when (raw.trim().trim('/')) {
                "sleep" -> ACTIVITIES.SLEEP
                "feedings", "feeding" -> ACTIVITIES.FEEDING
                "tummy-times", "tummy-time" -> ACTIVITIES.TUMMY_TIME
                "pumping" -> ACTIVITIES.PUMPING
                "changes", "diaperchange" -> EVENTS.CHANGE
                "notes", "note" -> EVENTS.NOTE
                "medications", "medication" -> EVENTS.MEDICATION
                "temperature", "temperatures" -> EVENTS.TEMPERATURE
                "weight", "weights" -> EVENTS.WEIGHT
                "height", "heights" -> EVENTS.HEIGHT
                "head-circumference", "head_circumference" -> EVENTS.HEAD_CIRCUMFERENCE
                "bmi", "bmis" -> EVENTS.BMI
                else -> raw.trim().trim('/')
            }
        }

        @Volatile
        private var instance: LocalBabyBuddyRepository? = null

        @JvmStatic
        fun getInstance(context: Context): LocalBabyBuddyRepository {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val appCtx = context.applicationContext
                    LocalBabyBuddyRepository(
                        database = LocalBabyBuddyDatabase(appCtx),
                        mediaStorage = LocalMediaStorage(appCtx),
                        settings = NapAndAppSettings.fromContext(appCtx),
                    ).also { instance = it }
                }
            }
        }

        @JvmStatic
        fun setTestInstance(repo: LocalBabyBuddyRepository?) {
            instance = repo
        }
    }
}

private fun Cursor.getStringOrNull(column: String): String? {
    val idx = getColumnIndex(column)
    return if (idx < 0 || isNull(idx)) null else getString(idx)
}

private fun Cursor.getDoubleOrNull(column: String): Double? {
    val idx = getColumnIndex(column)
    return if (idx < 0 || isNull(idx)) null else getDouble(idx)
}

private fun Cursor.getIntOrNull(column: String): Int? {
    val idx = getColumnIndex(column)
    return if (idx < 0 || isNull(idx)) null else getInt(idx)
}
