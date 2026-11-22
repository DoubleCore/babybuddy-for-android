package eu.pkgsoftware.babybuddywidgets.posemonitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import eu.pkgsoftware.babybuddywidgets.R
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ultra-smooth 60 FPS GPU Camera Preview + Tencent ncnn (Vulkan GPU FP16 / ARMv8.2 NEON FP16) Baby Pose Console:
 * - Decouples 60 FPS hardware PreviewView completely from background AI inference (zero camera lag!)
 * - Single-pass ROI sensor crop + native C++ ncnn Vulkan compute shader acceleration (~6-18ms/frame)
 * - Full runtime model manager (bundled 320/480 FP16 + cloud S-tier download) & continuous threshold sliders
 */
class BabyPoseMonitorDialog : DialogFragment(), PoseMonitorHub.Listener {

    companion object {
        private const val TAG = "BabyPoseMonitorDlg"
        private const val PREFS_NAME = "baby_pose_monitor_prefs"
        private const val KEY_ZOOM = "zoom_ratio"
        private const val KEY_ENGINE_MODE = "engine_mode_v2"
        private const val KEY_PREFER_VULKAN = "prefer_vulkan_gpu"
        private const val KEY_MIN_CONF = "min_landmark_conf"
        private const val KEY_ANGLE_THRESH_DEG = "angle_thresh_deg"
        private const val KEY_ROLL_THRESH_DEG = "roll_thresh_deg"
        private const val KEY_LIMB_DELTA_DEG = "limb_delta_deg"
        private const val KEY_SMOOTHING = "smoothing_strength"
        private const val KEY_CONFIRM_FRAMES = "confirm_frames"
        private const val KEY_THRESHOLD = "change_threshold"
        private const val KEY_WINDOW_SEC = "window_sec"
        private const val KEY_MIN_INTERVAL_MS = "min_interval_ms"
        private const val KEY_CONTOUR_FALLBACK = "enable_contour_fallback"
        private const val KEY_BASELINE_ROLL_DEG = "baseline_roll_offset_deg"
        private const val KEY_SHOW_HUD = "show_telemetry_hud"
        private const val KEY_CUSTOM_YOLO_URL = "custom_yolo_url"
        private const val KEY_IP_CAM_URL = "ip_cam_url"
        private const val KEY_WEBHOOK_URL = "watch_webhook_url"
        private const val KEY_SYNC_SYS_CLOCK = "sync_sys_clock"
        private const val ARG_BABY_NAME = "arg_baby_name"

        private val BG_DARK = Color.parseColor("#0D1117")
        private val VIEWPORT_BG = Color.parseColor("#05070B")
        private val PILL_BG = Color.parseColor("#18202C")
        private val PILL_STROKE = Color.parseColor("#2A3548")
        private val GLASS_BG = Color.argb(182, 13, 17, 23)
        private val TEXT_LIGHT = Color.parseColor("#F1F5F9")
        private val TEXT_MUTED = Color.parseColor("#94A3B8")
        private val ACCENT_MINT = Color.parseColor("#34D399")
        private val ACCENT_AMBER = Color.parseColor("#FBBF24")
        private val ACCENT_ROSE = Color.parseColor("#F43F5E")
        private val ACCENT_BLUE = Color.parseColor("#60A5FA")

        fun show(fm: FragmentManager, babyName: String?) {
            val existing = fm.findFragmentByTag(TAG) as? BabyPoseMonitorDialog
            if (existing != null && existing.isAdded) return
            val dlg = BabyPoseMonitorDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_BABY_NAME, babyName?.takeIf { it.isNotBlank() } ?: "宝宝")
                }
            }
            dlg.show(fm, TAG)
        }
    }

    private enum class CameraSourceMode(val shortLabel: String) {
        LOCAL_BACK("后置"),
        LOCAL_FRONT("前置"),
        NETWORK_STREAM("IP")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var analysisExecutor: ExecutorService? = null
    private var poseDetector: MlKitBabyPoseDetector? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var netCamGrabber: NetworkCameraFrameGrabber? = null

    private var sourceMode: CameraSourceMode = CameraSourceMode.LOCAL_BACK
    private val isAnalyzingFrame = AtomicBoolean(false)
    private var lastSnapshotEncodedAtMs: Long = 0L
    private var latestRawUnadjustedRollDeg: Float? = null
    private var uprightFrameW: Int = 720
    private var uprightFrameH: Int = 960

    // Main screen views
    private lateinit var statusDotView: View
    private lateinit var statusPillTv: TextView
    private lateinit var netCamConfigBox: LinearLayout
    private lateinit var ipCamEdit: EditText
    private lateinit var viewportFrame: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var netStreamImageView: ImageView
    private lateinit var overlayView: PoseOverlayAndZoomView
    private lateinit var cameraHintTv: TextView
    private lateinit var poseBadgeTv: TextView
    private lateinit var zoomBadgeTv: TextView
    private lateinit var telemetryHudBox: LinearLayout
    private lateinit var hudModelLineTv: TextView
    private lateinit var hudAnglesLineTv: TextView
    private lateinit var calibrateBtn: TextView
    private lateinit var alarmActionBar: LinearLayout
    private lateinit var lensBtn: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantedMap ->
        val camGranted = grantedMap[Manifest.permission.CAMERA] == true || hasCameraPermission()
        if (camGranted && sourceMode != CameraSourceMode.NETWORK_STREAM) {
            startSelectedCameraSource()
        } else if (!camGranted && sourceMode != CameraSourceMode.NETWORK_STREAM) {
            cameraHintTv.visibility = View.VISIBLE
            cameraHintTv.text = "点击授权相机"
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).roundToInt()

    private fun loadConfigFromPrefs(prefs: SharedPreferences): PoseDetectionConfig {
        return PoseDetectionConfig(
            engineMode = PoseEngineMode.fromCode(prefs.getString(KEY_ENGINE_MODE, PoseEngineMode.YOLO26N_NCNN_320.code)),
            preferVulkanGpu = prefs.getBoolean(KEY_PREFER_VULKAN, true),
            minLandmarkConfidence = prefs.getFloat(KEY_MIN_CONF, 0.45f).coerceIn(0.20f, 0.85f),
            angleThresholdDeg = prefs.getFloat(KEY_ANGLE_THRESH_DEG, 22f).coerceIn(8f, 60f),
            rollThresholdDeg = prefs.getFloat(KEY_ROLL_THRESH_DEG, 28f).coerceIn(15f, 55f),
            limbDeltaThresholdDeg = prefs.getFloat(KEY_LIMB_DELTA_DEG, 26f).coerceIn(12f, 55f),
            smoothingStrength = prefs.getFloat(KEY_SMOOTHING, 0.55f).coerceIn(0f, 0.90f),
            confirmFrames = prefs.getInt(KEY_CONFIRM_FRAMES, 2).coerceIn(1, 5),
            changeThreshold = prefs.getInt(KEY_THRESHOLD, 4).coerceIn(1, 12),
            windowSec = prefs.getInt(KEY_WINDOW_SEC, 30).coerceIn(10, 120),
            minChangeIntervalMs = prefs.getLong(KEY_MIN_INTERVAL_MS, 1_500L).coerceIn(500L, 5_000L),
            enableContourFallback = prefs.getBoolean(KEY_CONTOUR_FALLBACK, false),
            baselineRollOffsetDeg = prefs.getFloat(KEY_BASELINE_ROLL_DEG, 0f).coerceIn(-65f, 65f),
            showTelemetryHud = prefs.getBoolean(KEY_SHOW_HUD, true),
            customYoloUrl = prefs.getString(KEY_CUSTOM_YOLO_URL, "") ?: ""
        )
    }

    private fun saveConfigToPrefs(prefs: SharedPreferences, cfg: PoseDetectionConfig) {
        prefs.edit()
            .putString(KEY_ENGINE_MODE, cfg.engineMode.code)
            .putBoolean(KEY_PREFER_VULKAN, cfg.preferVulkanGpu)
            .putFloat(KEY_MIN_CONF, cfg.minLandmarkConfidence)
            .putFloat(KEY_ANGLE_THRESH_DEG, cfg.angleThresholdDeg)
            .putFloat(KEY_ROLL_THRESH_DEG, cfg.rollThresholdDeg)
            .putFloat(KEY_LIMB_DELTA_DEG, cfg.limbDeltaThresholdDeg)
            .putFloat(KEY_SMOOTHING, cfg.smoothingStrength)
            .putInt(KEY_CONFIRM_FRAMES, cfg.confirmFrames)
            .putInt(KEY_THRESHOLD, cfg.changeThreshold)
            .putInt(KEY_WINDOW_SEC, cfg.windowSec)
            .putLong(KEY_MIN_INTERVAL_MS, cfg.minChangeIntervalMs)
            .putBoolean(KEY_CONTOUR_FALLBACK, cfg.enableContourFallback)
            .putFloat(KEY_BASELINE_ROLL_DEG, cfg.baselineRollOffsetDeg)
            .putBoolean(KEY_SHOW_HUD, cfg.showTelemetryHud)
            .putString(KEY_CUSTOM_YOLO_URL, cfg.customYoloUrl)
            .apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_BabyBuddyWidgets)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            window?.let { w ->
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                w.setBackgroundDrawable(ColorDrawable(BG_DARK))
                @Suppress("DEPRECATION")
                w.statusBarColor = BG_DARK
                @Suppress("DEPRECATION")
                w.navigationBarColor = BG_DARK
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val babyName = arguments?.getString(ARG_BABY_NAME) ?: "宝宝"
        PoseMonitorHub.babyName = babyName
        PoseMonitorHub.currentZoomRatio = prefs.getFloat(KEY_ZOOM, 1.0f).coerceIn(1.0f, 8.0f)
        val loadedCfg = loadConfigFromPrefs(prefs)
        PoseMonitorHub.updateDetectionConfig(loadedCfg)
        PoseMonitorHub.ipCameraStreamUrl = prefs.getString(KEY_IP_CAM_URL, "http://192.168.1.88:81/stream") ?: ""
        PoseMonitorHub.watchWebhookUrl = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        PoseMonitorHub.syncSystemAlarmApp = prefs.getBoolean(KEY_SYNC_SYS_CLOCK, false)

        analysisExecutor = Executors.newSingleThreadExecutor()
        poseDetector = MlKitBabyPoseDetector(ctx)
        PoseMonitorHub.addListener(this)
        BabyWatchServer.start(ctx, BabyWatchServer.DEFAULT_PORT)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG_DARK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(16.dp(), 12.dp(), 16.dp(), 16.dp())
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                16.dp() + bars.left,
                12.dp() + bars.top,
                16.dp() + bars.right,
                16.dp() + bars.bottom
            )
            insets
        }

        // 1. Top Bar: [Status Pill]  ... [Zero Calibrate] [Close]
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp() }
        }

        val statusPill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makePillDrawable(PILL_BG, PILL_STROKE, 999f)
            setPadding(12.dp(), 7.dp(), 14.dp(), 7.dp())
        }
        statusDotView = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(8.dp(), 8.dp()).apply { marginEnd = 8.dp() }
            background = makeCircleDrawable(ACCENT_MINT)
        }
        statusPillTv = TextView(ctx).apply {
            text = "安睡"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT_LIGHT)
        }
        statusPill.addView(statusDotView)
        statusPill.addView(statusPillTv)

        val spacer = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        calibrateBtn = TextView(ctx).apply {
            text = formatCalibrateBadge(loadedCfg.baselineRollOffsetDeg)
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ACCENT_BLUE)
            background = makePillDrawable(PILL_BG, PILL_STROKE, 999f)
            setPadding(11.dp(), 7.dp(), 11.dp(), 7.dp())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8.dp() }
            setOnClickListener { calibrateOrResetRollZero(ctx, prefs) }
        }

        val closeBtn = TextView(ctx).apply {
            text = "✕"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(TEXT_LIGHT)
            background = makePillDrawable(PILL_BG, PILL_STROKE, 999f)
            layoutParams = LinearLayout.LayoutParams(36.dp(), 36.dp())
            setOnClickListener { dismissAllowingStateLoss() }
        }

        topBar.addView(statusPill)
        topBar.addView(spacer)
        topBar.addView(calibrateBtn)
        topBar.addView(closeBtn)
        root.addView(topBar)

        // 2. Compact IP Camera URL Input (only visible in IP mode)
        netCamConfigBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10.dp() }
        }
        ipCamEdit = EditText(ctx).apply {
            hint = "http://192.168.x.x:81/stream"
            setHintTextColor(TEXT_MUTED)
            setTextColor(TEXT_LIGHT)
            setText(PoseMonitorHub.ipCameraStreamUrl)
            textSize = 13f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = makePillDrawable(PILL_BG, PILL_STROKE, 14.dp().toFloat())
            setPadding(12.dp(), 9.dp(), 12.dp(), 9.dp())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8.dp()
            }
        }
        val connectBtn = TextView(ctx).apply {
            text = "连接"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = makePillDrawable(Color.parseColor("#2563EB"), Color.TRANSPARENT, 14.dp().toFloat())
            setPadding(16.dp(), 9.dp(), 16.dp(), 9.dp())
            setOnClickListener {
                val url = ipCamEdit.text?.toString()?.trim().orEmpty()
                PoseMonitorHub.ipCameraStreamUrl = url
                prefs.edit().putString(KEY_IP_CAM_URL, url).apply()
                startSelectedCameraSource()
            }
        }
        netCamConfigBox.addView(ipCamEdit)
        netCamConfigBox.addView(connectBtn)
        root.addView(netCamConfigBox)

        // 3. 60 FPS Hardware GPU Camera Viewport + Zero-Lag Pinch-to-Zoom + Skeleton Overlay
        viewportFrame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            background = GradientDrawable().apply {
                cornerRadius = 24.dp().toFloat()
                setColor(VIEWPORT_BG)
                setStroke(1.dp(), Color.parseColor("#1E293B"))
            }
            clipToOutline = true
            clipChildren = true
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updatePreviewGpuTransform()
            }
        }

        // Hardware TextureView-backed PreviewView for locked 60 FPS live preview & instant GPU zoom/pan
        previewView = PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        netStreamImageView = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        overlayView = PoseOverlayAndZoomView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            showJointAngleLabels = loadedCfg.showTelemetryHud
            setZoomAndPanState(PoseMonitorHub.currentZoomRatio, PoseMonitorHub.panX, PoseMonitorHub.panY)
            onZoomPanChanged = { z, px, py ->
                applyZoomAndPan(z, px, py, prefs)
            }
        }

        cameraHintTv = TextView(ctx).apply {
            text = "正在开启 60FPS 相机与 ncnn Vulkan 加速..."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(TEXT_MUTED)
            setPadding(24.dp(), 16.dp(), 24.dp(), 16.dp())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        poseBadgeTv = TextView(ctx).apply {
            text = "检测中"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT_LIGHT)
            background = makePillDrawable(GLASS_BG, Color.argb(65, 255, 255, 255), 999f)
            setPadding(11.dp(), 5.dp(), 11.dp(), 5.dp())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                marginStart = 12.dp()
                topMargin = 12.dp()
            }
        }

        zoomBadgeTv = TextView(ctx).apply {
            text = formatZoomBadge(PoseMonitorHub.currentZoomRatio)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT_LIGHT)
            background = makePillDrawable(GLASS_BG, Color.argb(65, 255, 255, 255), 999f)
            setPadding(10.dp(), 5.dp(), 10.dp(), 5.dp())
            visibility = if (PoseMonitorHub.currentZoomRatio > 1.05f) View.VISIBLE else View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                marginEnd = 12.dp()
                topMargin = 12.dp()
            }
            setOnClickListener {
                applyZoomAndPan(1.0f, 0f, 0f, prefs)
            }
        }

        telemetryHudBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = makePillDrawable(GLASS_BG, Color.argb(55, 148, 163, 184), 14.dp().toFloat())
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
            visibility = if (loadedCfg.showTelemetryHud) View.VISIBLE else View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                marginStart = 10.dp()
                marginEnd = 10.dp()
                bottomMargin = 10.dp()
            }
            setOnClickListener { showSettingsDialog(ctx, prefs) }
        }
        hudModelLineTv = TextView(ctx).apply {
            text = "引擎: ${loadedCfg.engineMode.modelDisplayName}"
            textSize = 10.5f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#34D399"))
            maxLines = 1
        }
        hudAnglesLineTv = TextView(ctx).apply {
            text = "等待骨骼关键点... (点击此处切换 Vulkan/模型/阈值)"
            textSize = 10.5f
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextColor(Color.parseColor("#CBD5E1"))
            maxLines = 2
            setPadding(0, 2.dp(), 0, 0)
        }
        telemetryHudBox.addView(hudModelLineTv)
        telemetryHudBox.addView(hudAnglesLineTv)

        viewportFrame.addView(previewView)
        viewportFrame.addView(netStreamImageView)
        viewportFrame.addView(overlayView)
        viewportFrame.addView(cameraHintTv)
        viewportFrame.addView(poseBadgeTv)
        viewportFrame.addView(zoomBadgeTv)
        viewportFrame.addView(telemetryHudBox)
        root.addView(viewportFrame)

        // 4. Contextual Alarm Bar
        alarmActionBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12.dp() }
        }
        val dismissBtn = TextView(ctx).apply {
            text = "关闭闹钟"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = makePillDrawable(ACCENT_ROSE, Color.TRANSPARENT, 16.dp().toFloat())
            setPadding(16.dp(), 13.dp(), 16.dp(), 13.dp())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f).apply {
                marginEnd = 8.dp()
            }
            setOnClickListener {
                PoseMonitorHub.dismissAlarm(ctx)
                refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
            }
        }
        val snoozeBtn = TextView(ctx).apply {
            text = "贪睡 3m"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT_LIGHT)
            gravity = Gravity.CENTER
            background = makePillDrawable(PILL_BG, PILL_STROKE, 16.dp().toFloat())
            setPadding(14.dp(), 13.dp(), 14.dp(), 13.dp())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                PoseMonitorHub.snoozeAlarm(ctx)
                refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
            }
        }
        alarmActionBar.addView(dismissBtn)
        alarmActionBar.addView(snoozeBtn)
        root.addView(alarmActionBar)

        // 5. Bottom Dock: [镜头] [手表联动] [加速与阈值]
        val bottomDock = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12.dp() }
        }

        lensBtn = makeDockPill(ctx, "镜头:${sourceMode.shortLabel}").apply {
            setOnClickListener {
                sourceMode = when (sourceMode) {
                    CameraSourceMode.LOCAL_BACK -> CameraSourceMode.LOCAL_FRONT
                    CameraSourceMode.LOCAL_FRONT -> CameraSourceMode.NETWORK_STREAM
                    CameraSourceMode.NETWORK_STREAM -> CameraSourceMode.LOCAL_BACK
                }
                text = "镜头:${sourceMode.shortLabel}"
                netCamConfigBox.visibility = if (sourceMode == CameraSourceMode.NETWORK_STREAM) View.VISIBLE else View.GONE
                poseDetector?.reset()
                startSelectedCameraSource()
            }
        }

        val watchBtn = makeDockPill(ctx, "手表联动").apply {
            setOnClickListener { showWatchDialog(ctx, prefs) }
        }

        val settingsBtn = makeDockPill(ctx, "⚡加速与阈值").apply {
            setOnClickListener { showSettingsDialog(ctx, prefs) }
        }

        bottomDock.addView(lensBtn, LinearLayout.LayoutParams(0, 44.dp(), 1f).apply { marginEnd = 8.dp() })
        bottomDock.addView(watchBtn, LinearLayout.LayoutParams(0, 44.dp(), 1f).apply { marginEnd = 8.dp() })
        bottomDock.addView(settingsBtn, LinearLayout.LayoutParams(0, 44.dp(), 1.2f))
        root.addView(bottomDock)

        return root
    }

    override fun onResume() {
        super.onResume()
        PoseMonitorHub.isMonitoring = true
        refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
        requestPermissionsAndStartCamera()
    }

    override fun onPause() {
        super.onPause()
        PoseMonitorHub.isMonitoring = false
        stopAllCameraSources()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        PoseMonitorHub.removeListener(this)
        stopAllCameraSources()
        poseDetector?.close()
        poseDetector = null
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
    }

    override fun onAlarmDismissedRemotely() {
        mainHandler.post {
            if (!isAdded) return@post
            refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
        }
    }

    override fun onAlarmSnoozedRemotely(durationMs: Long) {
        mainHandler.post {
            if (!isAdded) return@post
            refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
        }
    }

    override fun onAlarmTestRequestedRemotely() {
        mainHandler.post {
            if (!isAdded) return@post
            refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
        }
    }

    override fun onRemoteZoomRequested(newZoom: Float) {
        mainHandler.post {
            if (!isAdded) return@post
            val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            applyZoomAndPan(newZoom, PoseMonitorHub.panX, PoseMonitorHub.panY, prefs)
        }
    }

    private fun updatePreviewGpuTransform() {
        val vw = viewportFrame.width.toFloat()
        val vh = viewportFrame.height.toFloat()
        if (vw <= 4f || vh <= 4f) return

        val z = PoseMonitorHub.currentZoomRatio.coerceIn(1f, 8f)
        val px = PoseMonitorHub.panX.coerceIn(-1f, 1f)
        val py = PoseMonitorHub.panY.coerceIn(-1f, 1f)

        val fitScale = min(vw / uprightFrameW.toFloat().coerceAtLeast(1f), vh / uprightFrameH.toFloat().coerceAtLeast(1f))
        val drawW = uprightFrameW * fitScale
        val drawH = uprightFrameH * fitScale

        previewView.pivotX = vw * 0.5f
        previewView.pivotY = vh * 0.5f
        previewView.scaleX = z
        previewView.scaleY = z
        previewView.translationX = -px * drawW * 0.5f * (z - 1f)
        previewView.translationY = -py * drawH * 0.5f * (z - 1f)
    }

    private fun formatCalibrateBadge(offsetDeg: Float): String {
        return if (kotlin.math.abs(offsetDeg) >= 1f) {
            "🎯 已校准(${offsetDeg.roundToInt()}°)"
        } else {
            "🎯 零点校准"
        }
    }

    private fun calibrateOrResetRollZero(ctx: Context, prefs: SharedPreferences) {
        val curCfg = PoseMonitorHub.detectionConfig
        if (kotlin.math.abs(curCfg.baselineRollOffsetDeg) >= 1f) {
            val resetCfg = curCfg.copy(baselineRollOffsetDeg = 0f)
            PoseMonitorHub.updateDetectionConfig(resetCfg)
            saveConfigToPrefs(prefs, resetCfg)
            calibrateBtn.text = formatCalibrateBadge(0f)
            Toast.makeText(ctx, "已重置侧翻零点校准为 0°", Toast.LENGTH_SHORT).show()
            return
        }
        val rawRoll = latestRawUnadjustedRollDeg
        if (rawRoll == null) {
            Toast.makeText(ctx, "请先让画面检出婴儿骨架后再点击校准", Toast.LENGTH_SHORT).show()
            return
        }
        val clampedOffset = rawRoll.coerceIn(-65f, 65f)
        val nextCfg = curCfg.copy(baselineRollOffsetDeg = clampedOffset)
        PoseMonitorHub.updateDetectionConfig(nextCfg)
        PoseMonitorHub.wakeTracker.reset()
        saveConfigToPrefs(prefs, nextCfg)
        calibrateBtn.text = formatCalibrateBadge(clampedOffset)
        Toast.makeText(ctx, "已标定当前机位为平躺零点 (${clampedOffset.roundToInt()}°)，再次点击可清除", Toast.LENGTH_SHORT).show()
    }

    private fun hasCameraPermission(): Boolean {
        val ctx = context ?: return false
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionsAndStartCamera() {
        val permsToAsk = ArrayList<String>()
        if (!hasCameraPermission()) {
            permsToAsk.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val ctx = requireContext()
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permsToAsk.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permsToAsk.isNotEmpty() && sourceMode != CameraSourceMode.NETWORK_STREAM) {
            permissionLauncher.launch(permsToAsk.toTypedArray())
        } else {
            startSelectedCameraSource()
        }
    }

    private fun stopAllCameraSources() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Throwable) {
        }
        boundCamera = null
        netCamGrabber?.stop()
        netCamGrabber = null
    }

    private fun startSelectedCameraSource() {
        if (!isAdded) return
        stopAllCameraSources()

        if (sourceMode == CameraSourceMode.NETWORK_STREAM) {
            previewView.visibility = View.GONE
            netStreamImageView.visibility = View.VISIBLE
            val url = PoseMonitorHub.ipCameraStreamUrl
            if (url.isBlank()) {
                cameraHintTv.visibility = View.VISIBLE
                cameraHintTv.text = "请输入 IP 流地址"
                return
            }
            cameraHintTv.visibility = View.VISIBLE
            cameraHintTv.text = "连接中..."
            val grabber = NetworkCameraFrameGrabber(
                onBitmapFrame = { bmp ->
                    processNetworkStreamBitmap(bmp)
                },
                onStatusMessage = { msg ->
                    mainHandler.post {
                        if (!isAdded) return@post
                        if (netStreamImageView.drawable == null) {
                            cameraHintTv.visibility = View.VISIBLE
                            cameraHintTv.text = msg
                        }
                    }
                }
            )
            netCamGrabber = grabber
            grabber.start(url)
            return
        }

        previewView.visibility = View.VISIBLE
        netStreamImageView.visibility = View.GONE

        if (!hasCameraPermission()) {
            cameraHintTv.visibility = View.VISIBLE
            cameraHintTv.text = "点击授权相机"
            cameraHintTv.setOnClickListener {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            }
            return
        }

        val ctx = requireContext()
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            if (!isAdded) return@addListener
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                provider.unbindAll()

                val lensFacing = if (sourceMode == CameraSourceMode.LOCAL_FRONT) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
                val selector = if (provider.hasCamera(CameraSelector.Builder().requireLensFacing(lensFacing).build())) {
                    CameraSelector.Builder().requireLensFacing(lensFacing).build()
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                // 1. Hardware 60 FPS GPU Preview use case
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // 2. Lightweight async ImageAnalysis stream (960x720 -> fast YUV conversion + crisp ROI)
                val exec = analysisExecutor ?: return@addListener
                val resSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(960, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(exec) { imageProxy ->
                    handleCameraXFrameFast(imageProxy, isFront = (lensFacing == CameraSelector.LENS_FACING_FRONT))
                }

                boundCamera = provider.bindToLifecycle(viewLifecycleOwner, selector, preview, analysis)
                cameraHintTv.visibility = View.GONE
                updatePreviewGpuTransform()
            } catch (t: Throwable) {
                Log.e(TAG, "CameraX bind failed", t)
                cameraHintTv.visibility = View.VISIBLE
                cameraHintTv.text = "相机未就绪"
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    /**
     * Single-pass sensor ROI extraction -> C++ ncnn Vulkan inference.
     * Never blocks the 60 FPS PreviewView!
     */
    private fun handleCameraXFrameFast(imageProxy: ImageProxy, isFront: Boolean) {
        if (!isAnalyzingFrame.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        try {
            val rawBitmap = imageProxy.toBitmap()
            val rot = ((imageProxy.imageInfo.rotationDegrees % 360) + 360) % 360
            imageProxy.close()

            val rawW = rawBitmap.width
            val rawH = rawBitmap.height
            val upW = if (rot == 90 || rot == 270) rawH else rawW
            val upH = if (rot == 90 || rot == 270) rawW else rawH

            val cfg = PoseMonitorHub.detectionConfig
            val zoom = PoseMonitorHub.currentZoomRatio
            val panX = PoseMonitorHub.panX
            val panY = PoseMonitorHub.panY

            // Compute desired ROI in upright post-mirror coordinates
            val upCrop = ZoomRoiCropper.computeCropRect(upW, upH, zoom, panX, panY)
            val u0 = upCrop.left.toFloat() / upW.toFloat()
            val v0 = upCrop.top.toFloat() / upH.toFloat()
            val u1 = (upCrop.left + upCrop.width).toFloat() / upW.toFloat()
            val v1 = (upCrop.top + upCrop.height).toFloat() / upH.toFloat()

            // Undo horizontal mirror (`isFront`) and rotation (`rot`) to find exact sub-rect in `rawBitmap`
            val mu0 = if (isFront) (1f - u1) else u0
            val mu1 = if (isFront) (1f - u0) else u1

            val (rx0, ry0, rx1, ry1) = when (rot) {
                90 -> floatArrayOf(v0, 1f - mu1, v1, 1f - mu0)
                180 -> floatArrayOf(1f - mu1, 1f - v1, 1f - mu0, 1f - v0)
                270 -> floatArrayOf(1f - v1, mu0, 1f - v0, mu1)
                else -> floatArrayOf(mu0, v0, mu1, v1)
            }

            val srcLeft = (rx0 * rawW).roundToInt().coerceIn(0, rawW - 8)
            val srcTop = (ry0 * rawH).roundToInt().coerceIn(0, rawH - 8)
            val srcW = ((rx1 - rx0) * rawW).roundToInt().coerceIn(8, rawW - srcLeft)
            val srcH = ((ry1 - ry0) * rawH).roundToInt().coerceIn(8, rawH - srcTop)

            // Single-pass crop + rotate + mirror + pre-scale to model input dim!
            val targetMax = cfg.engineMode.targetInputDim.coerceAtLeast(320)
            val preScale = min(1f, targetMax.toFloat() / max(upCrop.width, upCrop.height).toFloat())
            val m = Matrix()
            if (rot != 0) m.postRotate(rot.toFloat())
            if (isFront) m.postScale(-1f, 1f)
            if (preScale < 0.99f) m.postScale(preScale, preScale)

            val roiBitmap = Bitmap.createBitmap(rawBitmap, srcLeft, srcTop, srcW, srcH, m, true)
            if (roiBitmap !== rawBitmap) {
                rawBitmap.recycle()
            }

            runInferenceOnRoiBitmap(roiBitmap, upW, upH, isNetworkMode = false)
        } catch (t: Throwable) {
            Log.d(TAG, "Fast frame analysis skipped: ${t.message}")
            try {
                imageProxy.close()
            } catch (_: Throwable) {
            }
        } finally {
            isAnalyzingFrame.set(false)
        }
    }

    private fun processNetworkStreamBitmap(uprightBitmap: Bitmap) {
        if (!isAnalyzingFrame.compareAndSet(false, true)) {
            uprightBitmap.recycle()
            return
        }
        try {
            val cfg = PoseMonitorHub.detectionConfig
            val upW = uprightBitmap.width
            val upH = uprightBitmap.height
            val roiBitmap = ZoomRoiCropper.cropAndScaleBitmap(
                source = uprightBitmap,
                zoomRatio = PoseMonitorHub.currentZoomRatio,
                panX = PoseMonitorHub.panX,
                panY = PoseMonitorHub.panY,
                targetMaxDim = cfg.engineMode.targetInputDim.coerceAtLeast(320)
            )
            if (roiBitmap !== uprightBitmap) {
                uprightBitmap.recycle()
            }
            runInferenceOnRoiBitmap(roiBitmap, upW, upH, isNetworkMode = true)
        } finally {
            isAnalyzingFrame.set(false)
        }
    }

    private fun runInferenceOnRoiBitmap(
        roiBitmap: Bitmap,
        fullUprightW: Int,
        fullUprightH: Int,
        isNetworkMode: Boolean
    ) {
        val cfg = PoseMonitorHub.detectionConfig
        val now = System.currentTimeMillis()

        // Lazy JPEG snapshot encoding: ONLY encode when a smartwatch actively polled in the last 8s!
        if (now - BabyWatchServer.lastClientPollAtMs < 8_000L && now - lastSnapshotEncodedAtMs >= 500L) {
            lastSnapshotEncodedAtMs = now
            try {
                val baos = ByteArrayOutputStream(20 * 1024)
                roiBitmap.compress(Bitmap.CompressFormat.JPEG, 65, baos)
                PoseMonitorHub.latestJpegSnapshot = baos.toByteArray()
            } catch (_: Throwable) {
            }
        }

        val detector = poseDetector
        if (detector == null) {
            roiBitmap.recycle()
            return
        }

        val frameResult = detector.analyzeRoiBitmap(roiBitmap, cfg)
        if (frameResult.targetDetected && frameResult.angleProfile.bodyRollDeg != null) {
            latestRawUnadjustedRollDeg = frameResult.angleProfile.bodyRollDeg + cfg.baselineRollOffsetDeg
        }
        val decision = PoseMonitorHub.wakeTracker.onFrame(frameResult)

        if (decision.justTriggeredWake && isAdded) {
            context?.applicationContext?.let { appCtx ->
                BabyWakeAlarmController.triggerWakeAlarm(
                    context = appCtx,
                    babyName = PoseMonitorHub.babyName,
                    snapshot = decision,
                    syncSystemClock = PoseMonitorHub.syncSystemAlarmApp,
                    webhookUrl = PoseMonitorHub.watchWebhookUrl
                )
            }
        }

        if (!isNetworkMode) {
            // Local camera uses 60 FPS PreviewView, so recycle temporary analysis ROI immediately!
            roiBitmap.recycle()
        }

        mainHandler.post {
            if (!isAdded) {
                if (isNetworkMode) roiBitmap.recycle()
                return@post
            }
            if (uprightFrameW != fullUprightW || uprightFrameH != fullUprightH) {
                uprightFrameW = fullUprightW
                uprightFrameH = fullUprightH
                updatePreviewGpuTransform()
            }
            cameraHintTv.visibility = View.GONE
            if (isNetworkMode) {
                netStreamImageView.setImageBitmap(roiBitmap)
            }
            overlayView.showJointAngleLabels = PoseMonitorHub.detectionConfig.showTelemetryHud
            overlayView.updateDetection(frameResult, decision, fullUprightW, fullUprightH)
            refreshTelemetryUi(frameResult, decision)
        }
    }

    private fun applyZoomAndPan(
        zoomRatio: Float,
        panX: Float,
        panY: Float,
        prefs: SharedPreferences
    ) {
        val clamped = ZoomRoiCropper.clampZoom(zoomRatio)
        PoseMonitorHub.currentZoomRatio = clamped
        PoseMonitorHub.panX = panX.coerceIn(-1f, 1f)
        PoseMonitorHub.panY = panY.coerceIn(-1f, 1f)
        prefs.edit().putFloat(KEY_ZOOM, clamped).apply()

        updatePreviewGpuTransform()
        overlayView.setZoomAndPanState(clamped, PoseMonitorHub.panX, PoseMonitorHub.panY)
        zoomBadgeTv.text = formatZoomBadge(clamped)
        zoomBadgeTv.visibility = if (clamped > 1.05f) View.VISIBLE else View.GONE
    }

    private fun formatZoomBadge(zoom: Float): String = "${"%.1f".format(Locale.US, zoom)}x"

    private fun shortPoseText(label: BabyPoseLabel): String = when (label) {
        BabyPoseLabel.NONE -> "未检出骨架"
        BabyPoseLabel.SUPINE_CALM -> "仰卧平放"
        BabyPoseLabel.SIDE_LEFT -> "左侧卧"
        BabyPoseLabel.SIDE_RIGHT -> "右侧卧"
        BabyPoseLabel.PRONE_HEAD_UP -> "趴卧/俯睡"
        BabyPoseLabel.LIMB_ACTIVE -> "屈臂/蹬腿"
        BabyPoseLabel.SITTING_AWAKE -> "撑起/坐立"
    }

    private fun refreshTelemetryUi(result: PoseFrameResult?, snap: WakeDecisionSnapshot) {
        val cfg = PoseMonitorHub.detectionConfig
        val poseShort = shortPoseText(snap.currentPose)
        val confPct = (snap.confidence * 100f).roundToInt()

        poseBadgeTv.text = if (!snap.targetDetected || snap.currentPose == BabyPoseLabel.NONE) {
            "$poseShort · 阈值≥${(cfg.minLandmarkConfidence * 100).roundToInt()}%"
        } else {
            "$poseShort (${confPct}%) · ${snap.changeCount}/${snap.changeThreshold}"
        }

        telemetryHudBox.visibility = if (cfg.showTelemetryHud) View.VISIBLE else View.GONE
        if (cfg.showTelemetryHud) {
            val modelTag = snap.modelName.ifBlank { cfg.engineMode.modelDisplayName }
            val fpsEst = if (snap.inferenceTimeMs > 0) (1000L / snap.inferenceTimeMs.coerceAtLeast(1L)).coerceAtMost(120L) else 0L
            val latencyStr = if (snap.inferenceTimeMs > 0) " · ${snap.inferenceTimeMs}ms (~${fpsEst}FPS)" else ""
            hudModelLineTv.text = "⚡ $modelTag$latencyStr"

            hudAnglesLineTv.text = if (snap.targetDetected) {
                "Δ角 ${snap.lastAngleDeltaDeg.roundToInt()}°/阈值${snap.angleThresholdDeg.roundToInt()}° | ${snap.angleProfile.summaryText()}"
            } else {
                "画面 60FPS 流畅运行中 · 未检出婴儿骨骼 · 点击可调灵敏度或切换模型"
            }
        }

        calibrateBtn.text = formatCalibrateBadge(cfg.baselineRollOffsetDeg)
        alarmActionBar.visibility = if (snap.isAlarmRinging) View.VISIBLE else View.GONE

        when {
            snap.isAlarmRinging -> {
                statusDotView.background = makeCircleDrawable(ACCENT_ROSE)
                statusPillTv.setTextColor(Color.parseColor("#FDA4AF"))
                statusPillTv.text = "醒来告警"
            }
            snap.snoozedRemainingSec > 0 -> {
                statusDotView.background = makeCircleDrawable(ACCENT_BLUE)
                statusPillTv.setTextColor(Color.parseColor("#93C5FD"))
                statusPillTv.text = "静音 ${snap.snoozedRemainingSec}s"
            }
            snap.changeCount >= (snap.changeThreshold + 1) / 2 -> {
                statusDotView.background = makeCircleDrawable(ACCENT_AMBER)
                statusPillTv.setTextColor(Color.parseColor("#FDE68A"))
                statusPillTv.text = "体动 ${snap.changeCount}/${snap.changeThreshold}"
            }
            snap.targetDetected -> {
                statusDotView.background = makeCircleDrawable(ACCENT_MINT)
                statusPillTv.setTextColor(TEXT_LIGHT)
                statusPillTv.text = "安睡监控"
            }
            else -> {
                statusDotView.background = makeCircleDrawable(TEXT_MUTED)
                statusPillTv.setTextColor(TEXT_MUTED)
                statusPillTv.text = "待检出目标"
            }
        }
    }

    private fun showSettingsDialog(ctx: Context, prefs: SharedPreferences) {
        var workingCfg = PoseMonitorHub.detectionConfig

        val scroll = ScrollView(ctx)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 10.dp(), 20.dp(), 12.dp())
        }
        scroll.addView(container)

        // 1. Hardware Acceleration & Model Section
        container.addView(makeDialogLabel(ctx, "1. 腾讯 ncnn 硬件加速引擎与模型管理"))

        val vulkanSupported = NcnnYoloPoseNative.isAvailable && NcnnYoloPoseNative.nativeHasVulkanGpu()
        val vulkanSwitch = SwitchMaterial(ctx).apply {
            text = if (vulkanSupported) {
                "⚡ 启用 Vulkan GPU 显卡计算着色器加速 (FP16 极速)"
            } else {
                "⚡ 启用 ARMv8.2 NEON FP16 大核多线程加速 (当前设备无 Vulkan)"
            }
            textSize = 12.5f
            isChecked = workingCfg.preferVulkanGpu
            setPadding(0, 2.dp(), 0, 4.dp())
            setOnCheckedChangeListener { _, checked ->
                workingCfg = workingCfg.copy(preferVulkanGpu = checked)
                PoseMonitorHub.updateDetectionConfig(workingCfg)
                poseDetector?.reset()
                saveConfigToPrefs(prefs, workingCfg)
                refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
            }
        }
        container.addView(vulkanSwitch)

        val modelDescTv = TextView(ctx).apply {
            text = workingCfg.engineMode.description
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 2.dp(), 0, 4.dp())
        }
        val modelStatusTv = TextView(ctx).apply {
            text = YoloModelManager.modelStatusText(ctx, workingCfg.engineMode)
            textSize = 11.5f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#059669"))
            setPadding(0, 0, 0, 6.dp())
        }
        val customUrlEdit = EditText(ctx).apply {
            hint = "输入含 .ncnn.param/.bin 的 .zip 下载链接"
            setText(workingCfg.customYoloUrl)
            textSize = 12f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            visibility = if (workingCfg.engineMode == PoseEngineMode.YOLO_CUSTOM_NCNN) View.VISIBLE else View.GONE
        }
        val dlProgressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            setPadding(0, 4.dp(), 0, 4.dp())
        }
        val cloudBtnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 2.dp(), 0, 8.dp())
        }
        val downloadModelBtn = MaterialButton(ctx).apply {
            text = "⬇️ 一键云端下载 ncnn 模型"
            textSize = 11.5f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply {
                marginEnd = 6.dp()
            }
        }
        val deleteModelBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "清理缓存"
            textSize = 11.5f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f)
        }
        cloudBtnRow.addView(downloadModelBtn)
        cloudBtnRow.addView(deleteModelBtn)

        fun refreshCloudModelWidgets() {
            val m = workingCfg.engineMode
            modelDescTv.text = m.description
            modelStatusTv.text = YoloModelManager.modelStatusText(ctx, m)
            customUrlEdit.visibility = if (m == PoseEngineMode.YOLO_CUSTOM_NCNN) View.VISIBLE else View.GONE
            val isCloudMode = m == PoseEngineMode.YOLO11S_NCNN_CLOUD || m == PoseEngineMode.YOLO_CUSTOM_NCNN
            cloudBtnRow.visibility = if (isCloudMode) View.VISIBLE else View.GONE
            if (isCloudMode) {
                val ready = YoloModelManager.isModelReady(ctx, m)
                downloadModelBtn.text = if (ready) "🔄 重新拉取更新" else "⬇️ 一键云端下载模型"
                deleteModelBtn.isEnabled = ready
            }
        }

        downloadModelBtn.setOnClickListener {
            val targetMode = workingCfg.engineMode
            val customUrl = customUrlEdit.text?.toString()?.trim().orEmpty()
            if (targetMode == PoseEngineMode.YOLO_CUSTOM_NCNN) {
                workingCfg = workingCfg.copy(customYoloUrl = customUrl)
                PoseMonitorHub.updateDetectionConfig(workingCfg)
                saveConfigToPrefs(prefs, workingCfg)
            }
            downloadModelBtn.isEnabled = false
            deleteModelBtn.isEnabled = false
            dlProgressBar.visibility = View.VISIBLE
            dlProgressBar.isIndeterminate = true

            val appCtx = ctx.applicationContext
            Thread {
                val res = YoloModelManager.downloadCloudModelBlocking(
                    context = appCtx,
                    mode = targetMode,
                    customUrl = customUrl
                ) { pct, dlMb, totMb, host ->
                    mainHandler.post {
                        if (!isAdded) return@post
                        if (pct >= 0) {
                            dlProgressBar.isIndeterminate = false
                            dlProgressBar.progress = pct
                            modelStatusTv.text = "⬇️ 正在从 $host 下载: $pct% (${"%.1f".format(Locale.US, dlMb)}/${"%.1f".format(Locale.US, totMb)} MB)"
                        } else {
                            dlProgressBar.isIndeterminate = true
                            modelStatusTv.text = "⬇️ 正在从 $host 下载 (${"%.1f".format(Locale.US, dlMb)} MB)..."
                        }
                    }
                }
                mainHandler.post {
                    if (!isAdded) return@post
                    dlProgressBar.visibility = View.GONE
                    downloadModelBtn.isEnabled = true
                    res.onSuccess {
                        poseDetector?.reset()
                        refreshCloudModelWidgets()
                        refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
                        Toast.makeText(ctx, "ncnn FP16 模型下载解压完成，已自动热切换！", Toast.LENGTH_SHORT).show()
                    }.onFailure { err ->
                        modelStatusTv.text = "❌ 下载失败: ${err.message} (自动使用内置 YOLO26n 极速版)"
                        deleteModelBtn.isEnabled = YoloModelManager.isModelReady(ctx, targetMode)
                    }
                }
            }.start()
        }

        deleteModelBtn.setOnClickListener {
            if (YoloModelManager.deleteDownloadedModel(ctx, workingCfg.engineMode)) {
                poseDetector?.reset()
                refreshCloudModelWidgets()
                Toast.makeText(ctx, "已清理该云端模型缓存", Toast.LENGTH_SHORT).show()
            }
        }

        val engineGroup = ChipGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }

        lateinit var refreshAllControls: () -> Unit

        val modeChips = LinkedHashMap<PoseEngineMode, Chip>()
        PoseEngineMode.entries.forEach { mode ->
            val chip = Chip(ctx).apply {
                text = mode.shortTitle
                isCheckable = true
                isChecked = (workingCfg.engineMode == mode)
                setOnClickListener {
                    val urlText = customUrlEdit.text?.toString()?.trim().orEmpty()
                    workingCfg = workingCfg.copy(engineMode = mode, customYoloUrl = urlText)
                    refreshCloudModelWidgets()
                    PoseMonitorHub.updateDetectionConfig(workingCfg)
                    poseDetector?.reset()
                    saveConfigToPrefs(prefs, workingCfg)
                    refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
                }
            }
            modeChips[mode] = chip
            engineGroup.addView(chip)
        }
        container.addView(engineGroup)
        container.addView(modelDescTv)
        container.addView(modelStatusTv)
        container.addView(customUrlEdit)
        container.addView(dlProgressBar)
        container.addView(cloudBtnRow)
        refreshCloudModelWidgets()

        // 2. Quick Sensitivity Presets
        container.addView(makeDialogLabel(ctx, "2. 一键灵敏度预设"))
        val presetRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 2.dp(), 0, 8.dp())
        }
        fun applyPreset(preset: PoseDetectionConfig, toastMsg: String) {
            workingCfg = preset.copy(
                engineMode = workingCfg.engineMode,
                preferVulkanGpu = workingCfg.preferVulkanGpu,
                baselineRollOffsetDeg = workingCfg.baselineRollOffsetDeg,
                showTelemetryHud = workingCfg.showTelemetryHud,
                enableContourFallback = workingCfg.enableContourFallback,
                customYoloUrl = workingCfg.customYoloUrl
            )
            PoseMonitorHub.updateDetectionConfig(workingCfg)
            saveConfigToPrefs(prefs, workingCfg)
            refreshAllControls()
            refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
            Toast.makeText(ctx, toastMsg, Toast.LENGTH_SHORT).show()
        }
        listOf(
            Triple("高灵敏", PoseDetectionConfig.PRESET_SENSITIVE, "已应用「高灵敏」阈值"),
            Triple("标准推荐", PoseDetectionConfig.PRESET_BALANCED, "已恢复「标准均衡」推荐阈值"),
            Triple("严苛防误报", PoseDetectionConfig.PRESET_STRICT, "已应用「严苛防误报」阈值")
        ).forEachIndexed { idx, (title, preset, msg) ->
            presetRow.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = title
                textSize = 11.5f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx < 2) marginEnd = 6.dp()
                }
                setOnClickListener { applyPreset(preset, msg) }
            })
        }
        container.addView(presetRow)

        fun addSliderControl(
            title: String,
            minVal: Int,
            maxVal: Int,
            initialVal: () -> Int,
            formatter: (Int) -> String,
            onChanged: (Int) -> Unit
        ): () -> Unit {
            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6.dp(), 0, 2.dp())
            }
            val labelTv = TextView(ctx).apply {
                text = title
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val valTv = TextView(ctx).apply {
                text = formatter(initialVal())
                textSize = 12.5f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(Color.parseColor("#2563EB"))
            }
            header.addView(labelTv)
            header.addView(valTv)
            container.addView(header)

            val seek = SeekBar(ctx).apply {
                max = maxVal - minVal
                progress = (initialVal() - minVal).coerceIn(0, maxVal - minVal)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val v = minVal + progress
                        valTv.text = formatter(v)
                        if (fromUser) {
                            onChanged(v)
                            PoseMonitorHub.updateDetectionConfig(workingCfg)
                            saveConfigToPrefs(prefs, workingCfg)
                            refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            container.addView(seek)
            return {
                val cur = initialVal()
                valTv.text = formatter(cur)
                seek.progress = (cur - minVal).coerceIn(0, maxVal - minVal)
            }
        }

        container.addView(makeDialogLabel(ctx, "3. 核心检测与报警阈值微调"))

        val syncMinConf = addSliderControl(
            title = "关键点最低置信度 (防被子误检)",
            minVal = 20, maxVal = 85,
            initialVal = { (workingCfg.minLandmarkConfidence * 100f).roundToInt() },
            formatter = { "$it%" },
            onChanged = { workingCfg = workingCfg.copy(minLandmarkConfidence = it / 100f) }
        )

        val syncAngleThresh = addSliderControl(
            title = "体动计数最小关节角度变化",
            minVal = 8, maxVal = 60,
            initialVal = { workingCfg.angleThresholdDeg.roundToInt() },
            formatter = { "≥${it}°" },
            onChanged = { workingCfg = workingCfg.copy(angleThresholdDeg = it.toFloat()) }
        )

        val syncRollThresh = addSliderControl(
            title = "左右翻身/侧卧判定角阈值",
            minVal = 15, maxVal = 55,
            initialVal = { workingCfg.rollThresholdDeg.roundToInt() },
            formatter = { "±${it}°" },
            onChanged = { workingCfg = workingCfg.copy(rollThresholdDeg = it.toFloat()) }
        )

        val syncSmooth = addSliderControl(
            title = "关键点自适应防抖滤波强度",
            minVal = 0, maxVal = 90,
            initialVal = { (workingCfg.smoothingStrength * 100f).roundToInt() },
            formatter = { "$it%" },
            onChanged = { workingCfg = workingCfg.copy(smoothingStrength = it / 100f) }
        )

        val syncConfirm = addSliderControl(
            title = "姿态切换确认帧数 (防单帧闪烁)",
            minVal = 1, maxVal = 5,
            initialVal = { workingCfg.confirmFrames },
            formatter = { "${it}帧" },
            onChanged = { workingCfg = workingCfg.copy(confirmFrames = it) }
        )

        val syncCount = addSliderControl(
            title = "触发唤醒闹钟所需体动次数",
            minVal = 1, maxVal = 12,
            initialVal = { workingCfg.changeThreshold },
            formatter = { "${it}次" },
            onChanged = { workingCfg = workingCfg.copy(changeThreshold = it) }
        )

        val syncWindow = addSliderControl(
            title = "累计体动统计时间窗口",
            minVal = 10, maxVal = 120,
            initialVal = { workingCfg.windowSec },
            formatter = { "${it}秒" },
            onChanged = { workingCfg = workingCfg.copy(windowSec = it) }
        )

        val syncCooldown = addSliderControl(
            title = "两次体动最小冷却间隔",
            minVal = 5, maxVal = 50,
            initialVal = { (workingCfg.minChangeIntervalMs / 100L).toInt() },
            formatter = { "${"%.1f".format(Locale.US, it / 10f)}秒" },
            onChanged = { workingCfg = workingCfg.copy(minChangeIntervalMs = it * 100L) }
        )

        val hudSwitch = SwitchMaterial(ctx).apply {
            text = "取景器显示实时 GPU 耗时与关节角遥测 HUD"
            textSize = 12.5f
            isChecked = workingCfg.showTelemetryHud
            setPadding(0, 8.dp(), 0, 4.dp())
            setOnCheckedChangeListener { _, checked ->
                workingCfg = workingCfg.copy(showTelemetryHud = checked)
                PoseMonitorHub.updateDetectionConfig(workingCfg)
                overlayView.showJointAngleLabels = checked
                overlayView.invalidate()
                saveConfigToPrefs(prefs, workingCfg)
                refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
            }
        }
        container.addView(hudSwitch)

        val fallbackSwitch = SwitchMaterial(ctx).apply {
            text = "无骨架时启用色块轮廓兜底 (建议关闭)"
            textSize = 12f
            isChecked = workingCfg.enableContourFallback
            setPadding(0, 4.dp(), 0, 4.dp())
            setOnCheckedChangeListener { _, checked ->
                workingCfg = workingCfg.copy(enableContourFallback = checked)
                PoseMonitorHub.updateDetectionConfig(workingCfg)
                saveConfigToPrefs(prefs, workingCfg)
                refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
            }
        }
        container.addView(fallbackSwitch)

        val sysSwitch = SwitchMaterial(ctx).apply {
            text = "触发报警时同步写入系统时钟闹钟"
            textSize = 12.5f
            isChecked = PoseMonitorHub.syncSystemAlarmApp
            setPadding(0, 4.dp(), 0, 8.dp())
            setOnCheckedChangeListener { _, checked ->
                PoseMonitorHub.syncSystemAlarmApp = checked
                prefs.edit().putBoolean(KEY_SYNC_SYS_CLOCK, checked).apply()
            }
        }
        container.addView(sysSwitch)

        refreshAllControls = {
            modeChips.forEach { (m, ch) -> ch.isChecked = (workingCfg.engineMode == m) }
            vulkanSwitch.isChecked = workingCfg.preferVulkanGpu
            refreshCloudModelWidgets()
            syncMinConf()
            syncAngleThresh()
            syncRollThresh()
            syncSmooth()
            syncConfirm()
            syncCount()
            syncWindow()
            syncCooldown()
            hudSwitch.isChecked = workingCfg.showTelemetryHud
            fallbackSwitch.isChecked = workingCfg.enableContourFallback
        }

        val testRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6.dp(), 0, 0)
        }
        testRow.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "模拟动作"
            textSize = 11.5f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 6.dp()
            }
            setOnClickListener {
                val snap = PoseMonitorHub.wakeTracker.simulateSinglePoseStep()
                if (snap.justTriggeredWake) {
                    BabyWakeAlarmController.triggerWakeAlarm(
                        ctx,
                        PoseMonitorHub.babyName,
                        snap,
                        PoseMonitorHub.syncSystemAlarmApp,
                        PoseMonitorHub.watchWebhookUrl
                    )
                }
                refreshTelemetryUi(null, snap)
            }
        })
        testRow.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "清零计数"
            textSize = 11.5f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 6.dp()
            }
            setOnClickListener {
                PoseMonitorHub.wakeTracker.reset()
                poseDetector?.reset()
                refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
                Toast.makeText(ctx, "已重置骨骼平滑器与计数", Toast.LENGTH_SHORT).show()
            }
        })
        testRow.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "测试闹钟"
            textSize = 11.5f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                PoseMonitorHub.triggerTestAlarm(ctx)
                refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
            }
        })
        container.addView(testRow)

        AlertDialog.Builder(ctx)
            .setTitle("⚡ ncnn 加速与阈值调优")
            .setView(scroll)
            .setPositiveButton("完成", null)
            .show()
    }

    private fun showWatchDialog(ctx: Context, prefs: SharedPreferences) {
        val urls = BabyWatchServer.resolveLocalLanUrls()
        val primaryUrl = urls.firstOrNull() ?: "http://127.0.0.1:${BabyWatchServer.boundPort}/watch"

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 12.dp(), 20.dp(), 8.dp())
        }

        container.addView(TextView(ctx).apply {
            text = urls.joinToString("\n")
            textSize = 13f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, 4.dp(), 0, 12.dp())
        })

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 10.dp())
        }
        btnRow.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "复制地址"
            textSize = 12.5f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8.dp()
            }
            setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("BabyWatchURL", primaryUrl))
                Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
            }
        })
        btnRow.addView(MaterialButton(ctx).apply {
            text = "预览表盘"
            textSize = 12.5f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showInAppWatchPreviewDialog(ctx) }
        })
        container.addView(btnRow)

        val webhookEdit = EditText(ctx).apply {
            hint = "Webhook URL (可选)"
            setText(PoseMonitorHub.watchWebhookUrl)
            textSize = 12.5f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(webhookEdit)

        AlertDialog.Builder(ctx)
            .setTitle("手表联动")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val url = webhookEdit.text?.toString()?.trim().orEmpty()
                PoseMonitorHub.watchWebhookUrl = url
                prefs.edit().putString(KEY_WEBHOOK_URL, url).apply()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showInAppWatchPreviewDialog(ctx: Context) {
        val port = BabyWatchServer.boundPort
        val localUrl = "http://127.0.0.1:$port/watch"
        val webView = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            setBackgroundColor(Color.parseColor("#090B10"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                380.dp()
            )
            loadUrl(localUrl)
        }
        AlertDialog.Builder(ctx)
            .setTitle("手表预览")
            .setView(webView)
            .setPositiveButton("关闭") { d, _ ->
                webView.destroy()
                d.dismiss()
            }
            .show()
    }

    private fun makeDockPill(ctx: Context, label: String): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT_LIGHT)
            gravity = Gravity.CENTER
            background = makePillDrawable(PILL_BG, PILL_STROKE, 14.dp().toFloat())
        }
    }

    private fun makeDialogLabel(ctx: Context, label: String): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 8.dp(), 0, 4.dp())
        }
    }

    private fun makePillDrawable(fillColor: Int, strokeColor: Int, radiusPx: Float): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(fillColor)
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(1.dp(), strokeColor)
            }
        }
    }

    private fun makeCircleDrawable(fillColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
        }
    }
}
