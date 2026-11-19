package eu.pkgsoftware.babybuddywidgets.backup

import android.content.Context
import android.net.Uri
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingMethodEnum
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingTypeEnum
import eu.pkgsoftware.babybuddywidgets.Constants.MedicationDosageUnitEnum
import eu.pkgsoftware.babybuddywidgets.local.AppDataRecord
import eu.pkgsoftware.babybuddywidgets.local.LocalBabyBuddyRepository
import eu.pkgsoftware.babybuddywidgets.local.LocalMediaStorage
import eu.pkgsoftware.babybuddywidgets.local.ModelValidators
import eu.pkgsoftware.babybuddywidgets.local.StandaloneSettingsSnapshot
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient.ACTIVITIES
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient.EVENTS
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.BmiEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.ChangeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.FeedingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeadCircumferenceEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeightEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.MedicationEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.NoteEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.PumpingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.SleepEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TagEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TemperatureEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Timer
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TummyTimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.WeightEntry
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full-fidelity local backup, restore, and migration engine supporting:
 * 1. `.zip` full archive (`backup.json` + `media/avatars/` + `media/notes/`)
 * 2. `.json` structured snapshot (replace all OR deduplicated merge)
 * 3. `.csv` export & import (both unified 12-model CSV and upstream Baby Buddy server per-model CSV exports)
 */
class BackupImportExportManager(private val repository: LocalBabyBuddyRepository) {

    enum class ImportMode {
        REPLACE_ALL,
        MERGE_DEDUPLICATE,
    }

    data class ImportSummary(
        val childrenCount: Int,
        val entriesImported: Int,
        val tagsImported: Int,
        val mediaFilesRestored: Int = 0,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BackupPayload(
        val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
        val appVersion: String = "3.0.0-standalone",
        val exportedAtMillis: Long = System.currentTimeMillis(),
        val settings: StandaloneSettingsSnapshot = StandaloneSettingsSnapshot(),
        val children: List<Child> = emptyList(),
        val tags: List<TagEntry> = emptyList(),
        val timers: List<Timer> = emptyList(),
        val appData: List<AppDataRecord> = emptyList(),
        val feedings: List<FeedingEntry> = emptyList(),
        val sleepRecords: List<SleepEntry> = emptyList(),
        val diaperChanges: List<ChangeEntry> = emptyList(),
        val tummyTimes: List<TummyTimeEntry> = emptyList(),
        val pumpings: List<PumpingEntry> = emptyList(),
        val medications: List<MedicationEntry> = emptyList(),
        val notes: List<NoteEntry> = emptyList(),
        val temperatures: List<TemperatureEntry> = emptyList(),
        val weights: List<WeightEntry> = emptyList(),
        val heights: List<HeightEntry> = emptyList(),
        val headCircumferences: List<HeadCircumferenceEntry> = emptyList(),
        val bmis: List<BmiEntry> = emptyList(),
    )

    private val mapper: ObjectMapper = ObjectMapper().apply {
        enable(SerializationFeature.INDENT_OUTPUT)
    }

    fun buildBackupPayload(): BackupPayload {
        return BackupPayload(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            exportedAtMillis = System.currentTimeMillis(),
            settings = repository.settings.exportSnapshot(),
            children = repository.listChildren().sortedBy { it.id },
            tags = repository.listTags().sortedBy { it.id },
            timers = repository.listTimers(),
            appData = repository.listAllAppData(),
            feedings = repository.getEntries(FeedingEntry::class, limit = 0).entries,
            sleepRecords = repository.getEntries(SleepEntry::class, limit = 0).entries,
            diaperChanges = repository.getEntries(ChangeEntry::class, limit = 0).entries,
            tummyTimes = repository.getEntries(TummyTimeEntry::class, limit = 0).entries,
            pumpings = repository.getEntries(PumpingEntry::class, limit = 0).entries,
            medications = repository.getEntries(MedicationEntry::class, limit = 0).entries,
            notes = repository.getEntries(NoteEntry::class, limit = 0).entries,
            temperatures = repository.getEntries(TemperatureEntry::class, limit = 0).entries,
            weights = repository.getEntries(WeightEntry::class, limit = 0).entries,
            heights = repository.getEntries(HeightEntry::class, limit = 0).entries,
            headCircumferences = repository.getEntries(HeadCircumferenceEntry::class, limit = 0).entries,
            bmis = repository.getEntries(BmiEntry::class, limit = 0).entries,
        )
    }

    // =========================================================================
    // JSON Export & Import
    // =========================================================================

    fun exportJson(output: OutputStream) {
        val payload = buildBackupPayload()
        val jsonBytes = mapper.writeValueAsBytes(payload)
        output.write(jsonBytes)
        output.flush()
    }

    fun importJson(input: InputStream, mode: ImportMode = ImportMode.REPLACE_ALL): ImportSummary {
        val payload = mapper.readValue(input, BackupPayload::class.java)
        return applyPayload(payload, mode, emptyMap())
    }

    // =========================================================================
    // ZIP Full Backup & Restore (DB + Media Images)
    // =========================================================================

    fun exportZip(output: OutputStream) {
        val payload = buildBackupPayload()
        val jsonBytes = mapper.writeValueAsBytes(payload)
        ZipOutputStream(output).use { zos ->
            val jsonEntry = ZipEntry(ZIP_JSON_ENTRY_NAME)
            zos.putNextEntry(jsonEntry)
            zos.write(jsonBytes)
            zos.closeEntry()

            for ((relPath, file) in repository.mediaStorage.listAllRelativeMediaFiles()) {
                if (!file.isFile) continue
                val cleanEntryName = "media/${relPath.trimStart('/').replace('\\', '/')}"
                zos.putNextEntry(ZipEntry(cleanEntryName))
                file.inputStream().use { fis -> fis.copyTo(zos) }
                zos.closeEntry()
            }
            zos.finish()
        }
    }

    fun importZip(input: InputStream, mode: ImportMode = ImportMode.REPLACE_ALL): ImportSummary {
        var jsonBytes: ByteArray? = null
        val mediaBytesByRelPath = LinkedHashMap<String, ByteArray>()

        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val rawName = entry.name.replace('\\', '/').trimStart('/')
                // Prevent Zip-Slip path traversal
                if (!entry.isDirectory && !rawName.contains("..")) {
                    val bytes = readBoundedEntryBytes(zis, MAX_SINGLE_ENTRY_BYTES)
                    if (rawName == ZIP_JSON_ENTRY_NAME || rawName.endsWith("/$ZIP_JSON_ENTRY_NAME")) {
                        jsonBytes = bytes
                    } else if (rawName.startsWith("media/")) {
                        val rel = rawName.removePrefix("media/")
                        if (rel.startsWith("${LocalMediaStorage.SUBDIR_AVATARS}/") ||
                            rel.startsWith("${LocalMediaStorage.SUBDIR_NOTES}/")
                        ) {
                            mediaBytesByRelPath[rel] = bytes
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val validJson = jsonBytes
            ?: throw IllegalArgumentException("Invalid backup archive: missing $ZIP_JSON_ENTRY_NAME")
        val payload = mapper.readValue(validJson, BackupPayload::class.java)
        return applyPayload(payload, mode, mediaBytesByRelPath)
    }

    private fun readBoundedEntryBytes(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val r = input.read(buf)
            if (r <= 0) break
            total += r
            if (total > maxBytes) {
                throw IllegalArgumentException("Backup entry exceeds safety size limit ($maxBytes bytes)")
            }
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    private fun applyPayload(
        payload: BackupPayload,
        mode: ImportMode,
        mediaBytesByRelPath: Map<String, ByteArray>,
    ): ImportSummary {
        var restoredMediaCount = 0
        var importedEntriesCount = 0
        var importedTagsCount = 0
        var finalChildrenCount = 0

        if (mode == ImportMode.REPLACE_ALL) {
            repository.clearAllData(deleteMediaFiles = true)
        }

        // Write media files before inserting DB rows so avatar/note relative paths resolve immediately
        for ((relPath, bytes) in mediaBytesByRelPath) {
            val parts = relPath.split("/", limit = 2)
            if (parts.size == 2) {
                repository.mediaStorage.writeRawMediaBytes(parts[0], parts[1], bytes)
                restoredMediaCount++
            }
        }

        repository.runInTransaction {
            if (mode == ImportMode.REPLACE_ALL) {
                repository.settings.importSnapshot(payload.settings)
            }

            // 1. Tags
            val existingTagsBySlug = repository.listTags().associateBy { it.slug }.toMutableMap()
            for (tag in payload.tags) {
                val cleanName = tag.name.trim()
                if (cleanName.isEmpty()) continue
                val existing = existingTagsBySlug[tag.slug]
                    ?: existingTagsBySlug.values.firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
                if (existing == null) {
                    val created = repository.createTag(cleanName, tag.color)
                    val updated = repository.updateTag(created.id, cleanName, tag.color)
                    existingTagsBySlug[updated.slug] = updated
                    importedTagsCount++
                } else if (mode == ImportMode.REPLACE_ALL) {
                    val updated = repository.updateTag(existing.id, cleanName, tag.color)
                    existingTagsBySlug[updated.slug] = updated
                }
            }

            // 2. Children (map payload childId -> local childId)
            val childIdMap = HashMap<Int, Int>()
            val existingChildren = repository.listChildren().toMutableList()
            for (c in payload.children) {
                val matched = if (mode == ImportMode.MERGE_DEDUPLICATE) {
                    existingChildren.firstOrNull {
                        it.slug.equals(c.slug, ignoreCase = true) ||
                            (it.firstName.equals(c.firstName, ignoreCase = true) &&
                                it.lastName.equals(c.lastName, ignoreCase = true) &&
                                ModelValidators.formatIsoDate(it.birthData) == ModelValidators.formatIsoDate(c.birthData))
                    }
                } else null

                if (matched != null) {
                    childIdMap[c.id] = matched.id
                } else {
                    val created = repository.createChild(
                        firstName = c.firstName,
                        lastName = c.lastName,
                        birthDate = c.birthData,
                        birthTime = c.birthTime,
                        dueDate = c.dueDate,
                        sex = c.sex,
                        picturePath = c.picture,
                        explicitSlug = c.slug,
                    )
                    existingChildren.add(created)
                    childIdMap[c.id] = created.id
                }
            }
            finalChildrenCount = existingChildren.size

            // Helper to resolve target childId
            fun resolveTargetChildId(rawChildId: Int): Int? {
                childIdMap[rawChildId]?.let { return it }
                if (existingChildren.any { it.id == rawChildId }) return rawChildId
                return existingChildren.firstOrNull()?.id
            }

            // Build deduplication signatures for MERGE_DEDUPLICATE
            val existingSignatures: MutableSet<String> = if (mode == ImportMode.MERGE_DEDUPLICATE) {
                repository.getAllTimelineEntries(limit = 0).mapTo(HashSet()) { entrySignature(it) }
            } else {
                HashSet()
            }

            fun importEntryIfNeeded(retargeted: TimeEntry) {
                val sig = entrySignature(retargeted)
                if (mode == ImportMode.MERGE_DEDUPLICATE && !existingSignatures.add(sig)) {
                    return
                }
                repository.createEntry(retargeted)
                importedEntriesCount++
            }

            payload.feedings.forEach { e ->
                resolveTargetChildId(e.childId)?.let { cid -> importEntryIfNeeded(e.copy(id = 0, childId = cid)) }
            }
            payload.sleepRecords.forEach { e ->
                resolveTargetChildId(e.childId)?.let { cid -> importEntryIfNeeded(e.copy(id = 0, childId = cid)) }
            }
            payload.diaperChanges.forEach { e ->
                resolveTargetChildId(e.childId)?.let { cid -> importEntryIfNeeded(e.copy(id = 0, childId = cid)) }
            }
            payload.tummyTimes.forEach { e ->
                resolveTargetChildId(e.childId)?.let { cid -> importEntryIfNeeded(e.copy(id = 0, childId = cid)) }
            }
            payload.pumpings.forEach { e ->
                resolveTargetChildId(e.childId)?.let { cid -> importEntryIfNeeded(e.copy(id = 0, childId = cid)) }
            }
            payload.medications.forEach { e ->
                resolveTargetChildId(e.childId)?.let { cid -> importEntryIfNeeded(e.copy(id = 0, childId = cid)) }
            }
            payload.notes.forEach { e ->
                resolveTargetChildId(e.childId)?.let { cid -> importEntryIfNeeded(e.copy(id = 0, childId = cid)) }
            }
            payload.temperatures.forEach { e ->
                resolveTargetChildId(e.childId)?.let { cid -> importEntryIfNeeded(e.copy(id = 0, childId = cid)) }
            }
            payload.weights.forEach { e ->
                resolveTargetChildId(e.childId)?.let { cid -> importEntryIfNeeded(e.copy(id = 0, childId = cid)) }
            }
            payload.heights.forEach { e ->
                resolveTargetChildId(e.childId)?.let { cid -> importEntryIfNeeded(e.copy(id = 0, childId = cid)) }
            }
            payload.headCircumferences.forEach { e ->
                resolveTargetChildId(e.childId)?.let { cid -> importEntryIfNeeded(e.copy(id = 0, childId = cid)) }
            }
            payload.bmis.forEach { e ->
                resolveTargetChildId(e.childId)?.let { cid -> importEntryIfNeeded(e.copy(id = 0, childId = cid)) }
            }

            if (mode == ImportMode.REPLACE_ALL) {
                for (t in payload.timers) {
                    val targetCid = if (t.childId > 0) resolveTargetChildId(t.childId) else null
                    repository.createTimer(targetCid, t.name, t.start.time)
                }
            }

            for (ad in payload.appData) {
                val targetCid = ad.childId?.let { resolveTargetChildId(it) }
                repository.setAppData(targetCid, ad.app, ad.key, ad.valueJson)
            }
        }

        return ImportSummary(
            childrenCount = finalChildrenCount,
            entriesImported = importedEntriesCount,
            tagsImported = importedTagsCount,
            mediaFilesRestored = restoredMediaCount,
        )
    }

    private fun entrySignature(entry: TimeEntry): String {
        val extra = when (entry) {
            is FeedingEntry -> "${entry.feedingType.post_name}|${entry.feedingMethod.post_name}|${entry.amount}"
            is SleepEntry -> "${entry.isNap}"
            is ChangeEntry -> "${entry.wet}|${entry.solid}|${entry.color}|${entry.amount}"
            is TummyTimeEntry -> "${entry.milestone}|${entry.extraNotes}"
            is PumpingEntry -> "${entry.amount}"
            is MedicationEntry -> "${entry.name.lowercase(Locale.ROOT)}|${entry.dosage}|${entry.dosageUnit.postName}"
            is NoteEntry -> entry.notes
            is TemperatureEntry -> "${entry.temperature}"
            is WeightEntry -> "${entry.weight}"
            is HeightEntry -> "${entry.height}"
            is HeadCircumferenceEntry -> "${entry.head_circumference}"
            is BmiEntry -> "${entry.bmi}"
            else -> entry.notes
        }
        val timeKey = when (entry) {
            is WeightEntry, is HeightEntry, is HeadCircumferenceEntry, is BmiEntry ->
                ModelValidators.formatIsoDate(entry.start)
            else ->
                "${entry.start.time / 1000L}|${entry.end.time / 1000L}"
        }
        return "${entry.childId}|${entry.appType}|$timeKey|$extra"
    }

    // =========================================================================
    // CSV Export & Import (Unified 12-Model + Upstream Baby Buddy Server CSVs)
    // =========================================================================

    fun exportCsv(output: OutputStream, childFilterId: Int? = null) {
        val childrenMap = repository.listChildren().associateBy { it.id }
        val entries = repository.getAllTimelineEntries(childId = childFilterId, limit = 0)
            .sortedBy { it.start.time }

        val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
        writer.write(CSV_COLUMNS.joinToString(","))
        writer.write("\n")

        val isoOffsetFmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())

        for (e in entries) {
            val c = childrenMap[e.childId]
            val row = LinkedHashMap<String, String>()
            row["entry_type"] = e.appType
            row["child_slug"] = c?.slug.orEmpty()
            row["child_first_name"] = c?.firstName.orEmpty()
            row["child_last_name"] = c?.lastName.orEmpty()
            row["child_birth_date"] = c?.birthData?.let { ModelValidators.formatIsoDate(it) }.orEmpty()
            row["child_sex"] = c?.sex.orEmpty()
            row["tags"] = e.tags.joinToString(";")

            when (e) {
                is FeedingEntry -> {
                    row["start"] = isoOffsetFmt.format(Instant.ofEpochMilli(e.start.time))
                    row["end"] = isoOffsetFmt.format(Instant.ofEpochMilli(e.end.time))
                    row["type"] = e.feedingType.post_name
                    row["method"] = e.feedingMethod.post_name
                    row["amount"] = e.amount?.toString().orEmpty()
                    row["notes"] = e.notes
                }
                is SleepEntry -> {
                    row["start"] = isoOffsetFmt.format(Instant.ofEpochMilli(e.start.time))
                    row["end"] = isoOffsetFmt.format(Instant.ofEpochMilli(e.end.time))
                    row["nap"] = e.isNap.toString()
                    row["notes"] = e.notes
                }
                is ChangeEntry -> {
                    row["time"] = isoOffsetFmt.format(Instant.ofEpochMilli(e.start.time))
                    row["wet"] = e.wet.toString()
                    row["solid"] = e.solid.toString()
                    row["color"] = e.color
                    row["amount"] = e.amount?.toString().orEmpty()
                    row["notes"] = e.notes
                }
                is TummyTimeEntry -> {
                    row["start"] = isoOffsetFmt.format(Instant.ofEpochMilli(e.start.time))
                    row["end"] = isoOffsetFmt.format(Instant.ofEpochMilli(e.end.time))
                    row["milestone"] = e.milestone
                    row["notes"] = e.extraNotes.orEmpty()
                }
                is PumpingEntry -> {
                    row["start"] = isoOffsetFmt.format(Instant.ofEpochMilli(e.start.time))
                    row["end"] = isoOffsetFmt.format(Instant.ofEpochMilli(e.end.time))
                    row["amount"] = e.amount.toString()
                    row["notes"] = e.notes
                }
                is MedicationEntry -> {
                    row["time"] = isoOffsetFmt.format(Instant.ofEpochMilli(e.start.time))
                    row["name"] = e.name
                    row["dosage"] = e.dosage.toString()
                    row["dosage_unit"] = e.dosageUnit.postName
                    row["next_dose_interval_minutes"] = e.nextDoseIntervalMinutes?.toString().orEmpty()
                    row["notes"] = e.notes
                }
                is NoteEntry -> {
                    row["time"] = isoOffsetFmt.format(Instant.ofEpochMilli(e.start.time))
                    row["note"] = e.notes
                }
                is TemperatureEntry -> {
                    row["time"] = isoOffsetFmt.format(Instant.ofEpochMilli(e.start.time))
                    row["temperature"] = e.temperature.toString()
                    row["notes"] = e.notes
                }
                is WeightEntry -> {
                    row["date"] = ModelValidators.formatIsoDate(e.start)
                    row["weight"] = e.weight.toString()
                    row["notes"] = e.notes
                }
                is HeightEntry -> {
                    row["date"] = ModelValidators.formatIsoDate(e.start)
                    row["height"] = e.height.toString()
                    row["notes"] = e.notes
                }
                is HeadCircumferenceEntry -> {
                    row["date"] = ModelValidators.formatIsoDate(e.start)
                    row["head_circumference"] = e.head_circumference.toString()
                    row["notes"] = e.notes
                }
                is BmiEntry -> {
                    row["date"] = ModelValidators.formatIsoDate(e.start)
                    row["bmi"] = e.bmi.toString()
                    row["notes"] = e.notes
                }
            }

            val line = CSV_COLUMNS.joinToString(",") { col -> escapeCsvField(row[col].orEmpty()) }
            writer.write(line)
            writer.write("\n")
        }
        writer.flush()
    }

    fun importCsv(
        input: InputStream,
        defaultChildId: Int? = null,
        mode: ImportMode = ImportMode.MERGE_DEDUPLICATE,
    ): ImportSummary {
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        val records = parseCsvRecords(reader.readText())
        if (records.size < 2) {
            return ImportSummary(
                childrenCount = repository.listChildren().size,
                entriesImported = 0,
                tagsImported = 0,
            )
        }

        val headers = records.first().mapIndexed { idx, h ->
            val cleaned = if (idx == 0) h.removePrefix("\uFEFF").trim() else h.trim()
            cleaned.lowercase(Locale.ROOT)
        }

        if (mode == ImportMode.REPLACE_ALL) {
            repository.clearAllData(deleteMediaFiles = true)
        }

        var importedCount = 0
        val existingSignatures: MutableSet<String> = if (mode == ImportMode.MERGE_DEDUPLICATE) {
            repository.getAllTimelineEntries(limit = 0).mapTo(HashSet()) { entrySignature(it) }
        } else {
            HashSet()
        }

        repository.runInTransaction {
            val childrenCache = repository.listChildren().toMutableList()

            fun resolveCsvChild(rowMap: Map<String, String>): Child {
                val slug = (rowMap["child_slug"] ?: rowMap["child"]).orEmpty().trim()
                val firstName = rowMap["child_first_name"].orEmpty().trim()
                val lastName = rowMap["child_last_name"].orEmpty().trim()
                val birthIso = rowMap["child_birth_date"].orEmpty().trim()
                val sex = rowMap["child_sex"]?.trim()?.takeIf { it.isNotEmpty() }

                if (slug.isNotEmpty()) {
                    val byId = slug.toIntOrNull()?.let { idNum -> childrenCache.firstOrNull { it.id == idNum } }
                    if (byId != null) return byId
                    val bySlug = childrenCache.firstOrNull {
                        it.slug.equals(slug, ignoreCase = true) ||
                            it.fullName.equals(slug, ignoreCase = true) ||
                            it.firstName.equals(slug, ignoreCase = true)
                    }
                    if (bySlug != null) return bySlug
                }

                if (firstName.isNotEmpty()) {
                    val byName = childrenCache.firstOrNull {
                        it.firstName.equals(firstName, ignoreCase = true) &&
                            it.lastName.equals(lastName, ignoreCase = true)
                    }
                    if (byName != null) return byName
                    val bDate = if (birthIso.isNotEmpty()) ModelValidators.parseIsoDateAtNoon(birthIso) else Date()
                    val created = repository.createChild(
                        firstName = firstName,
                        lastName = lastName,
                        birthDate = bDate,
                        sex = sex,
                        explicitSlug = slug.takeIf { it.isNotEmpty() && it.toIntOrNull() == null },
                    )
                    childrenCache.add(created)
                    return created
                }

                if (defaultChildId != null) {
                    childrenCache.firstOrNull { it.id == defaultChildId }?.let { return it }
                }
                if (childrenCache.isNotEmpty()) return childrenCache.first()

                val fallbackName = slug.takeIf { it.isNotEmpty() && it.toIntOrNull() == null } ?: "Baby"
                val created = repository.createChild(firstName = fallbackName, birthDate = Date())
                childrenCache.add(created)
                return created
            }

            for (rIdx in 1 until records.size) {
                val cols = records[rIdx]
                if (cols.all { it.isBlank() }) continue
                val rowMap = HashMap<String, String>(headers.size)
                for (i in headers.indices) {
                    if (i < cols.size) rowMap[headers[i]] = cols[i].trim()
                }

                val targetChild = resolveCsvChild(rowMap)
                val entry = parseCsvRowToEntry(rowMap, targetChild.id) ?: continue
                val sig = entrySignature(entry)
                if (mode == ImportMode.MERGE_DEDUPLICATE && !existingSignatures.add(sig)) {
                    continue
                }
                repository.createEntry(entry)
                importedCount++
            }
        }

        return ImportSummary(
            childrenCount = repository.listChildren().size,
            entriesImported = importedCount,
            tagsImported = repository.listTags().size,
        )
    }

    private fun parseCsvRowToEntry(rowMap: Map<String, String>, childId: Int): TimeEntry? {
        val rawType = rowMap["entry_type"].orEmpty().trim()
        val inferredType = if (rawType.isNotEmpty()) {
            LocalBabyBuddyRepository.normalizeEntryType(rawType)
        } else {
            inferCsvEntryType(rowMap) ?: return null
        }

        val tags = rowMap["tags"].orEmpty()
            .split(';', ',')
            .map { it.trim().trim('"', '\'', '[', ']') }
            .filter { it.isNotEmpty() }

        val notes = rowMap["notes"].orEmpty()

        return when (inferredType) {
            ACTIVITIES.FEEDING -> {
                val s = parseFlexibleDateTime(rowMap["start"] ?: rowMap["time"]) ?: return null
                val e = parseFlexibleDateTime(rowMap["end"]) ?: s
                val fType = parseFeedingType(rowMap["type"].orEmpty())
                val fMethod = parseFeedingMethod(rowMap["method"].orEmpty())
                val amt = rowMap["amount"]?.toDoubleOrNull()
                FeedingEntry(0, childId, s, if (e.before(s)) s else e, notes, fType, fMethod, amt, tags)
            }
            ACTIVITIES.SLEEP -> {
                val s = parseFlexibleDateTime(rowMap["start"] ?: rowMap["time"]) ?: return null
                val e = parseFlexibleDateTime(rowMap["end"]) ?: s
                val nap = parseNullableBoolean(rowMap["nap"])
                SleepEntry(0, childId, s, if (e.before(s)) s else e, notes, nap, tags)
            }
            EVENTS.CHANGE -> {
                val t = parseFlexibleDateTime(rowMap["time"] ?: rowMap["start"]) ?: return null
                val wet = parseNullableBoolean(rowMap["wet"]) ?: false
                val solid = parseNullableBoolean(rowMap["solid"]) ?: false
                val color = rowMap["color"].orEmpty().lowercase(Locale.ROOT)
                val amt = rowMap["amount"]?.toDoubleOrNull()
                ChangeEntry(0, childId, t, notes, wet, solid, color, amt, tags)
            }
            ACTIVITIES.TUMMY_TIME -> {
                val s = parseFlexibleDateTime(rowMap["start"] ?: rowMap["time"]) ?: return null
                val e = parseFlexibleDateTime(rowMap["end"]) ?: s
                val milestone = rowMap["milestone"].orEmpty()
                TummyTimeEntry(0, childId, s, if (e.before(s)) s else e, milestone, notes.ifEmpty { null }, tags)
            }
            ACTIVITIES.PUMPING -> {
                val s = parseFlexibleDateTime(rowMap["start"] ?: rowMap["time"]) ?: return null
                val e = parseFlexibleDateTime(rowMap["end"]) ?: s
                val amt = rowMap["amount"]?.toDoubleOrNull() ?: 0.0
                PumpingEntry(0, childId, s, if (e.before(s)) s else e, notes, amt, null, tags)
            }
            EVENTS.MEDICATION -> {
                val t = parseFlexibleDateTime(rowMap["time"] ?: rowMap["start"]) ?: return null
                val medName = rowMap["name"].orEmpty().ifBlank { return null }
                val dosage = rowMap["dosage"]?.toDoubleOrNull() ?: return null
                val unit = MedicationDosageUnitEnum.fromPostName(rowMap["dosage_unit"])
                val interval = rowMap["next_dose_interval_minutes"]?.toIntOrNull()
                MedicationEntry(0, childId, t, medName, dosage, unit, interval, notes, tags)
            }
            EVENTS.NOTE -> {
                val t = parseFlexibleDateTime(rowMap["time"] ?: rowMap["start"]) ?: return null
                val text = rowMap["note"]?.takeIf { it.isNotEmpty() } ?: notes
                NoteEntry(0, childId, t, text, null, tags)
            }
            EVENTS.TEMPERATURE -> {
                val t = parseFlexibleDateTime(rowMap["time"] ?: rowMap["start"]) ?: return null
                val temp = rowMap["temperature"]?.toDoubleOrNull() ?: return null
                TemperatureEntry(0, childId, t, notes, temp, tags)
            }
            EVENTS.WEIGHT -> {
                val d = parseFlexibleDateOnly(rowMap["date"] ?: rowMap["start"] ?: rowMap["time"]) ?: return null
                val w = rowMap["weight"]?.toDoubleOrNull() ?: return null
                WeightEntry(0, childId, d, notes, w, tags)
            }
            EVENTS.HEIGHT -> {
                val d = parseFlexibleDateOnly(rowMap["date"] ?: rowMap["start"] ?: rowMap["time"]) ?: return null
                val h = rowMap["height"]?.toDoubleOrNull() ?: return null
                HeightEntry(0, childId, d, notes, h, tags)
            }
            EVENTS.HEAD_CIRCUMFERENCE -> {
                val d = parseFlexibleDateOnly(rowMap["date"] ?: rowMap["start"] ?: rowMap["time"]) ?: return null
                val hc = rowMap["head_circumference"]?.toDoubleOrNull() ?: return null
                HeadCircumferenceEntry(0, childId, d, notes, hc, tags)
            }
            EVENTS.BMI -> {
                val d = parseFlexibleDateOnly(rowMap["date"] ?: rowMap["start"] ?: rowMap["time"]) ?: return null
                val b = rowMap["bmi"]?.toDoubleOrNull() ?: return null
                BmiEntry(0, childId, d, notes, b, tags)
            }
            else -> null
        }
    }

    private fun inferCsvEntryType(rowMap: Map<String, String>): String? {
        return when {
            !rowMap["dosage"].isNullOrBlank() -> EVENTS.MEDICATION
            !rowMap["weight"].isNullOrBlank() -> EVENTS.WEIGHT
            !rowMap["height"].isNullOrBlank() -> EVENTS.HEIGHT
            !rowMap["head_circumference"].isNullOrBlank() -> EVENTS.HEAD_CIRCUMFERENCE
            !rowMap["bmi"].isNullOrBlank() -> EVENTS.BMI
            !rowMap["temperature"].isNullOrBlank() -> EVENTS.TEMPERATURE
            !rowMap["wet"].isNullOrBlank() || !rowMap["solid"].isNullOrBlank() -> EVENTS.CHANGE
            !rowMap["method"].isNullOrBlank() || !rowMap["type"].isNullOrBlank() -> ACTIVITIES.FEEDING
            !rowMap["nap"].isNullOrBlank() -> ACTIVITIES.SLEEP
            rowMap.containsKey("milestone") && (!rowMap["milestone"].isNullOrBlank() || !rowMap["start"].isNullOrBlank()) ->
                ACTIVITIES.TUMMY_TIME
            !rowMap["amount"].isNullOrBlank() && !rowMap["start"].isNullOrBlank() -> ACTIVITIES.PUMPING
            !rowMap["note"].isNullOrBlank() -> EVENTS.NOTE
            else -> null
        }
    }

    private fun parseFeedingType(raw: String): FeedingTypeEnum {
        val clean = raw.trim().lowercase(Locale.ROOT)
        return FeedingTypeEnum.entries.firstOrNull { it.post_name.lowercase(Locale.ROOT) == clean }
            ?: FeedingTypeEnum.BREAST_MILK
    }

    private fun parseFeedingMethod(raw: String): FeedingMethodEnum {
        val clean = raw.trim().lowercase(Locale.ROOT)
        return FeedingMethodEnum.entries.firstOrNull { it.post_name.lowercase(Locale.ROOT) == clean }
            ?: FeedingMethodEnum.BOTTLE
    }

    private fun parseNullableBoolean(raw: String?): Boolean? {
        if (raw.isNullOrBlank()) return null
        return when (raw.trim().lowercase(Locale.ROOT)) {
            "true", "1", "yes", "y" -> true
            "false", "0", "no", "n" -> false
            else -> null
        }
    }

    private fun parseFlexibleDateTime(raw: String?): Date? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        s.toLongOrNull()?.let { return Date(it) }
        try {
            return Date(OffsetDateTime.parse(s).toInstant().toEpochMilli())
        } catch (_: Exception) {
        }
        try {
            return Date(Instant.parse(s).toEpochMilli())
        } catch (_: Exception) {
        }
        val normalized = s.replace(' ', 'T')
        try {
            val ldt = LocalDateTime.parse(normalized)
            return Date(ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        } catch (_: Exception) {
        }
        return parseFlexibleDateOnly(s)
    }

    private fun parseFlexibleDateOnly(raw: String?): Date? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().take(10)
        return try {
            val ld = LocalDate.parse(s)
            ModelValidators.parseIsoDateAtNoon(ld.toString())
        } catch (_: Exception) {
            null
        }
    }

    private fun escapeCsvField(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        if (!needsQuotes) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private fun parseCsvRecords(content: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < content.length) {
            val ch = content[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < content.length && content[i + 1] == '"') {
                        field.append('"')
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    ',' -> {
                        currentRow.add(field.toString())
                        field.setLength(0)
                    }
                    '\r' -> {
                        currentRow.add(field.toString())
                        field.setLength(0)
                        records.add(currentRow)
                        currentRow = mutableListOf()
                        if (i + 1 < content.length && content[i + 1] == '\n') i++
                    }
                    '\n' -> {
                        currentRow.add(field.toString())
                        field.setLength(0)
                        records.add(currentRow)
                        currentRow = mutableListOf()
                    }
                    else -> field.append(ch)
                }
            }
            i++
        }
        if (field.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(field.toString())
            records.add(currentRow)
        }
        return records
    }

    // =========================================================================
    // Android Uri Convenience Helpers + Shareable Cache File Export
    // =========================================================================

    fun exportZipToUri(context: Context, destUri: Uri) {
        context.contentResolver.openOutputStream(destUri)?.use { exportZip(it) }
            ?: throw IllegalStateException("Cannot open output stream for $destUri")
    }

    fun importZipFromUri(context: Context, srcUri: Uri, mode: ImportMode): ImportSummary {
        return context.contentResolver.openInputStream(srcUri)?.use { importZip(it, mode) }
            ?: throw IllegalStateException("Cannot open input stream for $srcUri")
    }

    fun exportJsonToUri(context: Context, destUri: Uri) {
        context.contentResolver.openOutputStream(destUri)?.use { exportJson(it) }
            ?: throw IllegalStateException("Cannot open output stream for $destUri")
    }

    fun importJsonFromUri(context: Context, srcUri: Uri, mode: ImportMode): ImportSummary {
        return context.contentResolver.openInputStream(srcUri)?.use { importJson(it, mode) }
            ?: throw IllegalStateException("Cannot open input stream for $srcUri")
    }

    fun exportCsvToUri(context: Context, destUri: Uri, childFilterId: Int? = null) {
        context.contentResolver.openOutputStream(destUri)?.use { exportCsv(it, childFilterId) }
            ?: throw IllegalStateException("Cannot open output stream for $destUri")
    }

    fun importCsvFromUri(context: Context, srcUri: Uri, defaultChildId: Int?, mode: ImportMode): ImportSummary {
        return context.contentResolver.openInputStream(srcUri)?.use { importCsv(it, defaultChildId, mode) }
            ?: throw IllegalStateException("Cannot open input stream for $srcUri")
    }

    fun writeShareableBackupZip(context: Context): File {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = LocalDate.now().toString()
        val outFile = File(exportDir, "babybuddy-backup-$stamp.zip")
        outFile.outputStream().use { exportZip(it) }
        return outFile
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val ZIP_JSON_ENTRY_NAME = "backup.json"
        private const val MAX_SINGLE_ENTRY_BYTES = 50 * 1024 * 1024 // 50 MB per zip entry safety cap

        val CSV_COLUMNS = listOf(
            "entry_type",
            "child_slug",
            "child_first_name",
            "child_last_name",
            "child_birth_date",
            "child_sex",
            "start",
            "end",
            "time",
            "date",
            "type",
            "method",
            "amount",
            "nap",
            "wet",
            "solid",
            "color",
            "milestone",
            "name",
            "dosage",
            "dosage_unit",
            "next_dose_interval_minutes",
            "temperature",
            "weight",
            "height",
            "head_circumference",
            "bmi",
            "note",
            "notes",
            "tags",
        )
    }
}
