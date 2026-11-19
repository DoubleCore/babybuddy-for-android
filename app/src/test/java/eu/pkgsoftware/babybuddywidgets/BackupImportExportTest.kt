package eu.pkgsoftware.babybuddywidgets

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingMethodEnum
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingTypeEnum
import eu.pkgsoftware.babybuddywidgets.Constants.MedicationDosageUnitEnum
import eu.pkgsoftware.babybuddywidgets.backup.BackupImportExportManager
import eu.pkgsoftware.babybuddywidgets.backup.BackupImportExportManager.ImportMode
import eu.pkgsoftware.babybuddywidgets.local.LocalBabyBuddyDatabase
import eu.pkgsoftware.babybuddywidgets.local.LocalBabyBuddyRepository
import eu.pkgsoftware.babybuddywidgets.local.LocalMediaStorage
import eu.pkgsoftware.babybuddywidgets.local.ModelValidators
import eu.pkgsoftware.babybuddywidgets.local.NapAndAppSettings
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.ChangeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.Child
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.FeedingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.MedicationEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.NoteEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.SleepEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.WeightEntry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupImportExportTest {
    private lateinit var context: Context
    private lateinit var db: LocalBabyBuddyDatabase
    private lateinit var mediaStorage: LocalMediaStorage
    private lateinit var repo: LocalBabyBuddyRepository
    private lateinit var backupManager: BackupImportExportManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = LocalBabyBuddyDatabase(context, databaseName = "test_backup_${System.nanoTime()}.db")
        mediaStorage = LocalMediaStorage(context)
        mediaStorage.clearAllMedia()
        val settings = NapAndAppSettings(
            context.getSharedPreferences("test_backup_prefs_${System.nanoTime()}", Context.MODE_PRIVATE)
        )
        repo = LocalBabyBuddyRepository(db, mediaStorage, settings)
        backupManager = BackupImportExportManager(repo)
    }

    @After
    fun tearDown() {
        mediaStorage.clearAllMedia()
        db.close()
    }

    @Test
    fun zipFullBackupAndRestoreWithMediaAndZipSlipProtection() {
        // Create media files inside avatars/ and notes/
        val avatarBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val noteBytes = byteArrayOf(9, 8, 7, 6, 5, 4)
        mediaStorage.writeRawRelativeFile("avatars/baby_avatar.jpg", avatarBytes)
        mediaStorage.writeRawRelativeFile("notes/rash_photo.jpg", noteBytes)

        val baby = repo.createChild(
            firstName = "团团",
            lastName = "王",
            birthDate = ModelValidators.parseIsoDateAtNoon("2026-04-12"),
            dueDate = ModelValidators.parseIsoDateAtNoon("2026-05-01"),
            sex = Child.SEX_BOY,
            picturePath = "avatars/baby_avatar.jpg"
        )
        repo.settings.napStartMin = "07:00"
        repo.settings.napStartMax = "17:30"

        val baseMs = ModelValidators.parseIsoDateAtNoon("2026-11-12").time
        repo.createEntry(FeedingEntry(0, baby.id, Date(baseMs), Date(baseMs + 600_000L), "Bottle milk", FeedingTypeEnum.FORMULA, FeedingMethodEnum.BOTTLE, 120.0, listOf("routine")))
        repo.createEntry(MedicationEntry(0, baby.id, Date(baseMs + 1_200_000L), "Vitamin AD", 1.0, MedicationDosageUnitEnum.TABLETS, 1440, "Daily", listOf("vitamin")))
        repo.createEntry(NoteEntry(0, baby.id, Date(baseMs + 1_800_000L), "Skin check", imagePath = "notes/rash_photo.jpg", tags = listOf("photo")))

        val zipOut = ByteArrayOutputStream()
        backupManager.exportZip(zipOut)
        val zipBytes = zipOut.toByteArray()
        assertTrue(zipBytes.isNotEmpty())

        // Wipe everything locally (DB + media + reset settings)
        repo.clearAllData(deleteMediaFiles = true)
        repo.settings.napStartMin = "06:00"
        assertEquals(0, repo.listChildren().size)
        assertNull(mediaStorage.resolveMediaFile("avatars/baby_avatar.jpg"))

        // Restore from ZIP
        val summary = backupManager.importZip(ByteArrayInputStream(zipBytes), ImportMode.REPLACE_ALL)
        assertEquals(1, summary.childrenCount)
        assertEquals(3, summary.entriesImported)
        assertEquals(2, summary.mediaFilesRestored)
        assertEquals("07:00", repo.settings.napStartMin)

        val restoredAvatar = mediaStorage.resolveMediaFile("avatars/baby_avatar.jpg")!!
        val restoredNote = mediaStorage.resolveMediaFile("notes/rash_photo.jpg")!!
        assertTrue(restoredAvatar.exists())
        assertTrue(restoredNote.exists())
        assertArrayEquals(avatarBytes, restoredAvatar.readBytes())
        assertArrayEquals(noteBytes, restoredNote.readBytes())

        // Verify Zip-Slip traversal entry ("../../evil.txt") is ignored safely
        val maliciousZip = ByteArrayOutputStream()
        ZipOutputStream(maliciousZip).use { zos ->
            zos.putNextEntry(ZipEntry("../../evil_outside.txt"))
            zos.write("malicious".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("backup.json"))
            val emptyJson = ByteArrayOutputStream().also { backupManager.exportJson(it) }.toByteArray()
            zos.write(emptyJson)
            zos.closeEntry()
        }
        backupManager.importZip(ByteArrayInputStream(maliciousZip.toByteArray()), ImportMode.MERGE_DEDUPLICATE)
        assertFalse(File(context.filesDir.parentFile, "evil_outside.txt").exists())
    }

    @Test
    fun jsonDeduplicatedMergeAndCsvRoundTripPlusServerCsvAutoDetection() {
        val baby = repo.createChild("果果", "", ModelValidators.parseIsoDateAtNoon("2026-08-01"), sex = Child.SEX_GIRL)
        val t0 = 1_762_920_000_000L // Exact second-aligned timestamp for CSV round-trip
        repo.createEntry(SleepEntry(0, baby.id, Date(t0), Date(t0 + 3_600_000L), "Afternoon nap", nap = true, tags = listOf("nap")))
        repo.createEntry(ChangeEntry(0, baby.id, Date(t0 + 4_000_000L), "Clean", wet = true, solid = true, color = "orange", amount = 25.0))
        repo.createEntry(WeightEntry(0, baby.id, Date(t0), "Home scale", 6.85))

        // 1. JSON export + MERGE_DEDUPLICATE idempotency
        val jsonOut = ByteArrayOutputStream()
        backupManager.exportJson(jsonOut)
        val mergeResult = backupManager.importJson(ByteArrayInputStream(jsonOut.toByteArray()), ImportMode.MERGE_DEDUPLICATE)
        assertEquals("Re-importing identical JSON in MERGE mode must skip duplicates", 0, mergeResult.entriesImported)
        assertEquals(3, repo.getUnifiedTimeline(baby.id, limit = 100).size)

        // 2. Unified CSV export -> wipe -> import round-trip
        val csvOut = ByteArrayOutputStream()
        backupManager.exportCsv(csvOut)
        val csvText = csvOut.toString(StandardCharsets.UTF_8.name())
        assertTrue(csvText.contains("entry_type,child_slug"))

        val replaceCsvSummary = backupManager.importCsv(
            ByteArrayInputStream(csvText.toByteArray(StandardCharsets.UTF_8)),
            mode = ImportMode.REPLACE_ALL
        )
        assertEquals(1, replaceCsvSummary.childrenCount)
        assertEquals(3, replaceCsvSummary.entriesImported)

        // 3. Upstream Baby Buddy Server CSV format auto-detection (no entry_type column!)
        val restoredBaby = repo.listChildren().first()
        val upstreamFeedingCsv = """
            id,child,start,end,duration,type,method,amount,notes,tags
            101,${restoredBaby.slug},2026-11-14T08:00:00+08:00,2026-11-14T08:18:00+08:00,00:18:00,breast milk,both breasts,115.0,Imported from Django server,"server-import;morning"
        """.trimIndent()

        val upstreamImport = backupManager.importCsv(
            ByteArrayInputStream(upstreamFeedingCsv.toByteArray(StandardCharsets.UTF_8)),
            defaultChildId = restoredBaby.id,
            mode = ImportMode.MERGE_DEDUPLICATE
        )
        assertEquals(1, upstreamImport.entriesImported)
        val feedings = repo.getEntries(FeedingEntry::class, childId = restoredBaby.id).entries
        assertEquals(1, feedings.size)
        assertEquals(FeedingMethodEnum.BOTH_BREASTS, feedings[0].feedingMethod)
        assertEquals(115.0, feedings[0].amount!!, 1e-4)
        assertTrue(feedings[0].tags.contains("server-import"))
    }
}
