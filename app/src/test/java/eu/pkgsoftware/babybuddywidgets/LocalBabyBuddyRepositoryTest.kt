package eu.pkgsoftware.babybuddywidgets

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingMethodEnum
import eu.pkgsoftware.babybuddywidgets.Constants.FeedingTypeEnum
import eu.pkgsoftware.babybuddywidgets.Constants.MedicationDosageUnitEnum
import eu.pkgsoftware.babybuddywidgets.local.LocalBabyBuddyDatabase
import eu.pkgsoftware.babybuddywidgets.local.LocalBabyBuddyRepository
import eu.pkgsoftware.babybuddywidgets.local.LocalMediaStorage
import eu.pkgsoftware.babybuddywidgets.local.ModelValidators
import eu.pkgsoftware.babybuddywidgets.local.NapAndAppSettings
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient.ACTIVITIES
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
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TemperatureEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TummyTimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.WeightEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalBabyBuddyRepositoryTest {
    private lateinit var context: Context
    private lateinit var db: LocalBabyBuddyDatabase
    private lateinit var repo: LocalBabyBuddyRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = LocalBabyBuddyDatabase(context, databaseName = "test_babybuddy_${System.nanoTime()}.db")
        val media = LocalMediaStorage(context)
        val settings = NapAndAppSettings(
            context.getSharedPreferences("test_prefs_${System.nanoTime()}", Context.MODE_PRIVATE)
        )
        repo = LocalBabyBuddyRepository(db, media, settings)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun multiChildAndTagAndTimerCrud() {
        val birth = ModelValidators.parseIsoDateAtNoon("2026-06-01")
        val due = ModelValidators.parseIsoDateAtNoon("2026-06-25")

        val babyA = repo.createChild(
            firstName = "安安",
            lastName = "林",
            birthDate = birth,
            birthTime = "08:30",
            dueDate = due,
            sex = Child.SEX_GIRL
        )
        val babyB = repo.createChild(
            firstName = "乐乐",
            lastName = "林",
            birthDate = birth,
            sex = Child.SEX_BOY
        )

        assertEquals(2, repo.listChildren().size)
        assertEquals("08:30", babyA.birthTime)
        assertTrue(babyA.hasCorrectedPrematureAge)
        assertEquals(Child.SEX_GIRL, babyA.sex)

        val updatedA = repo.updateChild(
            id = babyA.id,
            firstName = "安安",
            lastName = "陈",
            birthDate = birth,
            birthTime = "09:15",
            dueDate = due,
            sex = Child.SEX_GIRL
        )
        assertEquals("09:15", updatedA.birthTime)
        assertEquals("陈", updatedA.lastName)

        // Tags CRUD
        val tag = repo.createTag("辅食初尝", "#469E70")
        assertEquals("#469E70", tag.color)
        val renamedTag = repo.updateTag(tag.id, "辅食观察", "#5B7FD1")
        assertEquals("辅食观察", renamedTag.name)
        assertEquals("#5B7FD1", renamedTag.color)

        // Persistent Timers CRUD
        val t0 = System.currentTimeMillis() - 600_000L
        val sleepTimer = repo.createTimer(babyA.id, ACTIVITIES.SLEEP, t0)
        // Idempotent for active activity timer
        val duplicateLookup = repo.createTimer(babyA.id, ACTIVITIES.SLEEP, t0 + 1000L)
        assertEquals(sleepTimer.id, duplicateLookup.id)

        // Standalone quick timer
        val quickTimer = repo.createTimer(babyA.id, "Morning Bath", t0)
        assertEquals(2, repo.listTimers(babyA.id).size)
        val shifted = repo.updateTimerStart(quickTimer.id, t0 - 300_000L)
        assertEquals(t0 - 300_000L, shifted.start.time)
        assertTrue(repo.deleteTimer(quickTimer.id))
        assertEquals(1, repo.listTimers(babyA.id).size)

        // Cascading child delete
        repo.deleteChild(babyB.id)
        assertEquals(1, repo.listChildren().size)
    }

    @Test
    fun allTwelveTimeEntryModelsCreateUpdateSearchFilterAndDelete() {
        val child = repo.createChild("诺诺", "", ModelValidators.parseIsoDateAtNoon("2026-07-10"), sex = Child.SEX_BOY)
        val baseMs = ModelValidators.parseIsoDateAtNoon("2026-11-10").time

        val feeding = repo.createEntry(
            FeedingEntry(0, child.id, Date(baseMs), Date(baseMs + 900_000L), "Good appetite", FeedingTypeEnum.BREAST_MILK, FeedingMethodEnum.LEFT_BREAST, 100.0, listOf("morning"))
        )
        val sleep = repo.createEntry(
            SleepEntry(0, child.id, Date(baseMs + 1_800_000L), Date(baseMs + 5_400_000L), "Quiet nap", nap = null, tags = listOf("nap-tag"))
        )
        assertTrue("12:30 start should auto-resolve to daytime nap", sleep.isNap)

        val diaper = repo.createEntry(
            ChangeEntry(0, child.id, Date(baseMs + 6_000_000L), "Gray check", wet = true, solid = true, color = "gray", amount = 35.0, tags = listOf("stool-check"))
        )
        val tummy = repo.createEntry(
            TummyTimeEntry(0, child.id, Date(baseMs + 7_000_000L), Date(baseMs + 7_900_000L), "Lifted head 90 deg", "Happy mood", listOf("milestone"))
        )
        val pumping = repo.createEntry(
            PumpingEntry(0, child.id, Date(baseMs + 8_000_000L), Date(baseMs + 9_200_000L), "Electric pump", 135.0, tags = listOf("stash"))
        )
        val medication = repo.createEntry(
            MedicationEntry(0, child.id, Date(baseMs + 9_500_000L), "Ibuprofen", 2.5, MedicationDosageUnitEnum.ML, 480, "Post vaccine", listOf("fever"))
        )
        val note = repo.createEntry(
            NoteEntry(0, child.id, Date(baseMs + 10_000_000L), "First giggle recorded!", imagePath = "notes/sample.jpg", tags = listOf("memory"))
        )
        val temp = repo.createEntry(
            TemperatureEntry(0, child.id, Date(baseMs + 10_500_000L), "Axillary", 37.1, listOf("fever"))
        )
        val weight = repo.createEntry(
            WeightEntry(0, child.id, Date(baseMs), "Clinic scale", 7.2, listOf("checkup"))
        )
        val height = repo.createEntry(
            HeightEntry(0, child.id, Date(baseMs), "Recumbent", 66.0, listOf("checkup"))
        )
        val hc = repo.createEntry(
            HeadCircumferenceEntry(0, child.id, Date(baseMs), "Tape", 42.3, listOf("checkup"))
        )
        val suggestedBmi = ModelValidators.calculateBmi(weight.weight, height.height)!!
        val bmi = repo.createEntry(
            BmiEntry(0, child.id, Date(baseMs), "Auto BMI", suggestedBmi, listOf("checkup"))
        )

        // All 12 entries present in unified timeline
        val allTimeline = repo.getUnifiedTimeline(child.id, limit = 100)
        assertEquals(12, allTimeline.size)

        // Latest weight & height lookup for auto-BMI
        val (latestW, latestH) = repo.getLatestWeightAndHeight(child.id)
        assertEquals(7.2, latestW!!.weight, 1e-4)
        assertEquals(66.0, latestH!!.height, 1e-4)

        // Recent medication autocomplete
        assertEquals(listOf("Ibuprofen"), repo.getRecentMedicationNames(child.id))

        // Tag filtering ("fever" matches medication + temperature = 2)
        val feverEntries = repo.getUnifiedTimeline(child.id, tagFilter = "fever")
        assertEquals(2, feverEntries.size)

        // Keyword search across milestone, note, and medication name
        assertEquals(1, repo.getUnifiedTimeline(child.id, searchQuery = "Lifted head").size)
        assertEquals(1, repo.getUnifiedTimeline(child.id, searchQuery = "Ibuprofen").size)
        assertEquals(1, repo.getUnifiedTimeline(child.id, searchQuery = "giggle").size)

        // In-place update test (Feeding amount + Diaper color + Tummy milestone + Note)
        val editedFeeding = repo.updateEntry(feeding.copy(amount = 145.0, _notes = "Finished bottle"))
        assertEquals(145.0, editedFeeding.amount!!, 1e-4)
        assertEquals("Finished bottle", editedFeeding.notes)

        val editedDiaper = repo.updateEntry(diaper.copy(color = "yellow"))
        assertEquals("yellow", editedDiaper.color)

        val editedTummy = repo.updateEntry(tummy.copy(_notes = "Rolled over!", extraNotes = "Clapped"))
        assertEquals("Rolled over!", editedTummy.milestone)
        assertEquals("Clapped", editedTummy.extraNotes)

        assertEquals("notes/sample.jpg", (repo.getEntryByTypeAndId(note.appType, note.id) as NoteEntry).imagePath)

        // Overlap detection & auto-trimming test
        val overlaps = repo.findIntersectingTimedEntries(
            ACTIVITIES.SLEEP,
            child.id,
            sleep.start.time + 600_000L,
            sleep.end.time + 600_000L
        )
        assertEquals(1, overlaps.size)

        repo.settings.autoTrimOverlappingPeriods = true
        repo.createEntry(
            SleepEntry(0, child.id, Date(sleep.end.time - 900_000L), Date(sleep.end.time + 900_000L), "Adjacent sleep", nap = true)
        )
        val trimmedOriginal = repo.getEntryByTypeAndId(ACTIVITIES.SLEEP, sleep.id) as SleepEntry
        assertEquals(sleep.end.time - 900_000L, trimmedOriginal.end.time)

        // Delete verification
        assertTrue(repo.deleteEntry(hc))
        assertTrue(repo.deleteEntry(bmi))
        assertNull(repo.getEntryByTypeAndId(hc.appType, hc.id))
        assertEquals(11, repo.getUnifiedTimeline(child.id, limit = 100).size) // 12 + 1 new sleep - 2 deleted = 11
    }
}
