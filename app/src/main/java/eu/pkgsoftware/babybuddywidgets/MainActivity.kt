package eu.pkgsoftware.babybuddywidgets

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentContainerView
import androidx.navigation.findNavController
import androidx.preference.PreferenceManager
import com.squareup.phrase.Phrase
import eu.pkgsoftware.babybuddywidgets.activitycomponents.TimerControl
import eu.pkgsoftware.babybuddywidgets.compat.BabyBuddyV2TimerAdapter
import eu.pkgsoftware.babybuddywidgets.databinding.ActivityMainBinding
import eu.pkgsoftware.babybuddywidgets.local.LocalBabyBuddyRepository
import eu.pkgsoftware.babybuddywidgets.local.NapAndAppSettings
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient.Child
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient.GenericSubsetResponseHeader
import eu.pkgsoftware.babybuddywidgets.tutorial.TutorialAccess
import eu.pkgsoftware.babybuddywidgets.tutorial.TutorialManagement
import eu.pkgsoftware.babybuddywidgets.utils.AsyncClientRequest
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

interface StoreFunction<X> : BabyBuddyClient.RequestCallback<X> {
    fun store(timer: BabyBuddyClient.Timer, callback: BabyBuddyClient.RequestCallback<X>)
    fun name(): String
    fun stopTimer(timer: BabyBuddyClient.Timer)
    fun cancel()
}

enum class ConflictResolutionOptions {
    CANCEL, RESOLVE, STOP_TIMER
}

fun interface InputEventListener {
    fun inputEvent(event: InputEvent)
}

class MainActivity : AppCompatActivity() {
    private val timerControls = mutableMapOf<Int, BabyBuddyV2TimerAdapter>()

    private var pendingPhotoCallback: ((Uri?) -> Unit)? = null
    private var pendingExportDocCallback: ((Uri?) -> Unit)? = null
    private var pendingImportDocCallback: ((Uri?) -> Unit)? = null

    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val cb = pendingPhotoCallback
        pendingPhotoCallback = null
        cb?.invoke(uri)
    }

    private val createDocumentLauncher = registerForActivityResult(
        object : ActivityResultContract<Pair<String, String>, Uri?>() {
            override fun createIntent(context: Context, input: Pair<String, String>): Intent {
                return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = input.first
                    putExtra(Intent.EXTRA_TITLE, input.second)
                }
            }

            override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
                return if (resultCode == Activity.RESULT_OK) intent?.data else null
            }
        }
    ) { uri ->
        val cb = pendingExportDocCallback
        pendingExportDocCallback = null
        cb?.invoke(uri)
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val cb = pendingImportDocCallback
        pendingImportDocCallback = null
        cb?.invoke(uri)
    }

    fun pickPhoto(callback: (Uri?) -> Unit) {
        pendingPhotoCallback = callback
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    fun createExportDocument(mimeType: String, suggestedName: String, callback: (Uri?) -> Unit) {
        pendingExportDocCallback = callback
        createDocumentLauncher.launch(mimeType to suggestedName)
    }

    fun openImportDocument(mimeTypes: Array<String>, callback: (Uri?) -> Unit) {
        pendingImportDocCallback = callback
        openDocumentLauncher.launch(mimeTypes)
    }

    lateinit var binding: ActivityMainBinding

    val scope = MainScope()
    val inputEventListeners = mutableListOf<InputEventListener>()

    val repository: LocalBabyBuddyRepository
        get() = LocalBabyBuddyRepository.getInstance(applicationContext)

    val standaloneSettings: NapAndAppSettings
        get() = repository.settings

    internal var internalStorage: ActivityStore? = null
    val storage: ActivityStore
        get() {
            val current = internalStorage
            if (current != null) return current
            return ActivityStore(this).also { internalStorage = it }
        }

    internal var internalCredStore: CredStore? = null
    val credStore: CredStore
        get() {
            val current = internalCredStore
            if (current != null) return current
            return CredStore(applicationContext).also { internalCredStore = it }
        }

    internal var internalClient: BabyBuddyClient? = null
    val client: BabyBuddyClient
        get() {
            val current = internalClient
            if (current != null) return current
            return BabyBuddyClient(
                mainLooper,
                credStore,
                repository
            ).also { internalClient = it }
        }

    internal var internalTutorialAccess: TutorialAccess? = null
    val tutorialAccess: TutorialAccess
        get() {
            val current = internalTutorialAccess
            if (current != null) return current
            return TutorialAccess(this).also { internalTutorialAccess = it }
        }

    internal var internalTutorialManagement: TutorialManagement? = null
    val tutorialManagement: TutorialManagement
        get() {
            val current = internalTutorialManagement
            if (current != null) return current
            return TutorialManagement(credStore, tutorialAccess).also { internalTutorialManagement = it }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.enableEdgeToEdge(window)

        binding = ActivityMainBinding.inflate(layoutInflater).let {
            ViewCompat.setOnApplyWindowInsetsListener(it.root) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(systemBars.left, 0, systemBars.right, 0)
                binding.topSpacer.layoutParams.height = systemBars.top
                WindowInsetsCompat.CONSUMED
            }
            setContentView(it.root)
            setSupportActionBar(it.toolbar)
            it
        }
        enableBackNavigationButton(false)
        tutorialAccess
    }

    override fun onStart() {
        super.onStart()

        window.isNavigationBarContrastEnforced = true
        applyLightDarkMode()

        binding.root.let {
            val ncv = it.findViewById<FragmentContainerView>(R.id.nav_host_fragment_content_main)
            ncv.findNavController()
                .addOnDestinationChangedListener { _, destination, _ ->
                    enableBackNavigationButton(destination.id != R.id.loggedInFragment2)
                }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        internalClient?.cleanup()
    }

    @JvmField
    var selectedTimer: BabyBuddyClient.Timer? = null

    fun setTitle(title: String) {
        supportActionBar?.title = title
    }

    fun enableBackNavigationButton(b: Boolean) {
        supportActionBar?.setDisplayHomeAsUpEnabled(b)
        supportActionBar?.setDisplayShowHomeEnabled(b)
    }

    override fun onSupportNavigateUp(): Boolean {
        binding.root.let {
            val ncv = it.findViewById<FragmentContainerView>(R.id.nav_host_fragment_content_main)
            ncv.findNavController().navigateUp()
        }
        return false
    }

    private fun invokeInputEventListeners(e: InputEvent) {
        inputEventListeners.forEach {
            it.inputEvent(e)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { invokeInputEventListeners(it) }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        invokeInputEventListeners(event)
        return super.dispatchKeyEvent(event)
    }

    fun <X> storeActivity(
        timer: BabyBuddyClient.Timer,
        storeInterface: StoreFunction<X>
    ) {
        suspend fun trySave(): X {
            return AsyncClientRequest.call {
                storeInterface.store(timer, it)
            }
        }

        suspend fun listConflicts(): List<BabyBuddyClient.TimeEntry> {
            val childIdVal = timer.child_id ?: return emptyList()
            val jsonResponse = AsyncClientRequest.call<GenericSubsetResponseHeader<JSONArray>> {
                client.listGeneric(
                    storeInterface.name(),
                    0,
                    BabyBuddyClient.QueryValues()
                        .add("start_max", timer.computeCurrentServerEndTime(client))
                        .add("end_min", timer.start)
                        .add("limit", 50)
                        .add("child", childIdVal),
                    it
                )
            }
            val result = ArrayList<BabyBuddyClient.TimeEntry>()
            for (i in 0 until jsonResponse.payload.length()) {
                try {
                    val entry = BabyBuddyClient.TimeEntry.fromJsonObject(
                        jsonResponse.payload.getJSONObject(i), storeInterface.name()
                    )
                    result.add(entry)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Received unparsable json object", e)
                }
            }
            return result
        }

        suspend fun askForResolutionMethod(): ConflictResolutionOptions {
            return suspendCoroutine { continuation ->
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.conflicting_activity_title)
                    .setMessage(R.string.conflicting_activity_text)
                    .setCancelable(false)
                    .setPositiveButton(R.string.conflicting_activity_modify_option) { _, _ ->
                        continuation.resume(ConflictResolutionOptions.RESOLVE)
                    }
                    .setNeutralButton(R.string.conflicting_activity_cancel_option) { _, _ ->
                        continuation.resume(ConflictResolutionOptions.CANCEL)
                    }
                    .setNegativeButton(R.string.conflicting_activity_stop_timer_option) { _, _ ->
                        continuation.resume(ConflictResolutionOptions.STOP_TIMER)
                    }
                    .show()
            }
        }

        suspend fun patchEntry(
            e: BabyBuddyClient.TimeEntry,
            values: BabyBuddyClient.QueryValues
        ): BabyBuddyClient.TimeEntry {
            return AsyncClientRequest.call {
                client.updateTimelineEntry(e, values, it)
            }
        }

        suspend fun resolve(conflicts: List<BabyBuddyClient.TimeEntry>) {
            var anyException: Exception? = null
            for (c in conflicts) {
                val values = BabyBuddyClient.QueryValues()
                if (c.start < timer.start) {
                    values.add("start", c.start)
                } else {
                    values.add("start", timer.start)
                }
                values.add("end", timer.start)
                try {
                    patchEntry(c, values)
                } catch (e: Exception) {
                    anyException = e
                }
            }
            if (anyException != null) {
                throw anyException
            }
        }

        @Suppress("DEPRECATION")
        scope.launch {
            var readableActivityName = storeInterface.name()
            if (BabyBuddyClient.ACTIVITIES.index(readableActivityName) >= 0) {
                val i = BabyBuddyClient.ACTIVITIES.index(readableActivityName)
                readableActivityName = resources.getStringArray(R.array.timerTypeNames)[i]
            }

            val progressDialog = ProgressDialog(this@MainActivity)
            progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER)
            progressDialog.setMessage(
                Phrase.from(applicationContext, R.string.popup_message_storing)
                    .putOptional("activity", readableActivityName)
                    .format()
            )

            progressDialog.show()
            try {
                val conflicts = if (!standaloneSettings.autoTrimOverlappingPeriods) {
                    listConflicts()
                } else {
                    emptyList()
                }
                if (conflicts.isEmpty()) {
                    val result = trySave()
                    storeInterface.response(result)
                    progressDialog.cancel()
                    return@launch
                }

                progressDialog.hide()
                val resolution = askForResolutionMethod()
                progressDialog.show()
                when (resolution) {
                    ConflictResolutionOptions.STOP_TIMER -> {
                        progressDialog.cancel()
                        storeInterface.stopTimer(timer)
                    }
                    ConflictResolutionOptions.RESOLVE -> {
                        resolve(conflicts)
                        val saved = trySave()
                        storeInterface.response(saved)
                    }
                    ConflictResolutionOptions.CANCEL -> {
                        progressDialog.cancel()
                        storeInterface.cancel()
                    }
                }
            } catch (e: Exception) {
                storeInterface.error(e)
            } finally {
                progressDialog.cancel()
            }
        }
    }

    fun getChildTimerControl(child: Child): BabyBuddyV2TimerAdapter {
        return getChildTimerControl(child.id)
    }

    fun getChildTimerControl(childId: Int): BabyBuddyV2TimerAdapter {
        return timerControls.getOrPut(childId) {
            BabyBuddyV2TimerAdapter(
                childId,
                TimerControl(this, childId),
                resources,
                credStore,
            )
        }
    }

    fun resetTimerAdapters() {
        timerControls.clear()
    }

    fun applyLightDarkMode() {
        PreferenceManager.getDefaultSharedPreferences(this).let { p ->
            val mode = p.getString("setting_dark_light_mode", "system")
            var useLightStatusBar = true
            when (mode) {
                "system" -> {
                    delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    useLightStatusBar = when (resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) {
                        android.content.res.Configuration.UI_MODE_NIGHT_YES -> false
                        else -> true
                    }
                }
                "light" -> {
                    delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_NO
                    useLightStatusBar = true
                }
                "dark" -> {
                    delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
                    useLightStatusBar = false
                }
            }
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = useLightStatusBar
        }
    }
}
