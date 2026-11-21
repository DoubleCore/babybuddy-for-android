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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
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
import kotlin.math.roundToInt

/**
 * Minimalist full-screen camera console for baby posture monitoring:
 * - Camera-first dark viewfinder with pure 2-finger pinch-to-zoom (1.0x - 8.0x) and pan
 * - Ultra-concise live status & pose pill
 * - Advanced thresholds, watch LAN access, and test triggers neatly tucked into dialogs
 */
class BabyPoseMonitorDialog : DialogFragment(), PoseMonitorHub.Listener {

    companion object {
        private const val TAG = "BabyPoseMonitorDlg"
        private const val PREFS_NAME = "baby_pose_monitor_prefs"
        private const val KEY_ZOOM = "zoom_ratio"
        private const val KEY_THRESHOLD = "change_threshold"
        private const val KEY_WINDOW_SEC = "window_sec"
        private const val KEY_ANGLE_THRESH_DEG = "angle_thresh_deg"
        private const val KEY_IP_CAM_URL = "ip_cam_url"
        private const val KEY_WEBHOOK_URL = "watch_webhook_url"
        private const val KEY_SYNC_SYS_CLOCK = "sync_sys_clock"
        private const val ARG_BABY_NAME = "arg_baby_name"

        private val BG_DARK = Color.parseColor("#0D1117")
        private val VIEWPORT_BG = Color.parseColor("#05070B")
        private val PILL_BG = Color.parseColor("#18202C")
        private val PILL_STROKE = Color.parseColor("#2A3548")
        private val GLASS_BG = Color.argb(165, 13, 17, 23)
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
    private var lastAnalyzedAtMs: Long = 0L

    // Minimal main screen views
    private lateinit var statusDotView: View
    private lateinit var statusPillTv: TextView
    private lateinit var netCamConfigBox: LinearLayout
    private lateinit var ipCamEdit: EditText
    private lateinit var zoomedFeedImageView: ImageView
    private lateinit var overlayView: PoseOverlayAndZoomView
    private lateinit var cameraHintTv: TextView
    private lateinit var poseBadgeTv: TextView
    private lateinit var zoomBadgeTv: TextView
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
        PoseMonitorHub.wakeTracker.changeThreshold = prefs.getInt(KEY_THRESHOLD, 5).coerceIn(2, 12)
        PoseMonitorHub.wakeTracker.windowMs = prefs.getInt(KEY_WINDOW_SEC, 30).coerceIn(10, 120) * 1000L
        PoseMonitorHub.wakeTracker.angleThresholdDeg = prefs.getFloat(KEY_ANGLE_THRESH_DEG, 25f).coerceIn(12f, 60f)
        PoseMonitorHub.ipCameraStreamUrl = prefs.getString(KEY_IP_CAM_URL, "http://192.168.1.88:81/stream") ?: ""
        PoseMonitorHub.watchWebhookUrl = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        PoseMonitorHub.syncSystemAlarmApp = prefs.getBoolean(KEY_SYNC_SYS_CLOCK, false)

        analysisExecutor = Executors.newSingleThreadExecutor()
        poseDetector = MlKitBabyPoseDetector()
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

        // 1. Minimal Top Bar: [Status Pill]  ...  [Close]
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12.dp() }
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

        // 3. Hero Camera Viewport (Pure 2-finger pinch-to-zoom + pan)
        val viewportFrame = FrameLayout(ctx).apply {
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
        }

        zoomedFeedImageView = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
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
            setZoomAndPanState(PoseMonitorHub.currentZoomRatio, PoseMonitorHub.panX, PoseMonitorHub.panY)
            onZoomPanChanged = { z, px, py ->
                applyZoomAndPan(z, px, py, prefs)
            }
        }

        cameraHintTv = TextView(ctx).apply {
            text = "正在开启相机..."
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
            background = makePillDrawable(GLASS_BG, Color.argb(60, 255, 255, 255), 999f)
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
            background = makePillDrawable(GLASS_BG, Color.argb(60, 255, 255, 255), 999f)
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

        viewportFrame.addView(zoomedFeedImageView)
        viewportFrame.addView(overlayView)
        viewportFrame.addView(cameraHintTv)
        viewportFrame.addView(poseBadgeTv)
        viewportFrame.addView(zoomBadgeTv)
        root.addView(viewportFrame)

        // 4. Contextual Alarm Bar (only shown when alarm is ringing)
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

        // 5. Minimal Bottom Dock: [镜头] [手表] [设置]
        val bottomDock = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12.dp() }
        }

        lensBtn = makeDockPill(ctx, sourceMode.shortLabel).apply {
            setOnClickListener {
                sourceMode = when (sourceMode) {
                    CameraSourceMode.LOCAL_BACK -> CameraSourceMode.LOCAL_FRONT
                    CameraSourceMode.LOCAL_FRONT -> CameraSourceMode.NETWORK_STREAM
                    CameraSourceMode.NETWORK_STREAM -> CameraSourceMode.LOCAL_BACK
                }
                text = sourceMode.shortLabel
                netCamConfigBox.visibility = if (sourceMode == CameraSourceMode.NETWORK_STREAM) View.VISIBLE else View.GONE
                poseDetector?.reset()
                startSelectedCameraSource()
            }
        }

        val watchBtn = makeDockPill(ctx, "手表").apply {
            setOnClickListener { showWatchDialog(ctx, prefs) }
        }

        val settingsBtn = makeDockPill(ctx, "设置").apply {
            setOnClickListener { showSettingsDialog(ctx, prefs) }
        }

        bottomDock.addView(lensBtn, LinearLayout.LayoutParams(0, 44.dp(), 1f).apply { marginEnd = 8.dp() })
        bottomDock.addView(watchBtn, LinearLayout.LayoutParams(0, 44.dp(), 1f).apply { marginEnd = 8.dp() })
        bottomDock.addView(settingsBtn, LinearLayout.LayoutParams(0, 44.dp(), 1f))
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
                    processIncomingBitmap(bmp, recycleSource = true)
                },
                onStatusMessage = { msg ->
                    mainHandler.post {
                        if (!isAdded) return@post
                        if (zoomedFeedImageView.drawable == null) {
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

                val exec = analysisExecutor ?: return@addListener
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(exec) { imageProxy ->
                    handleCameraXFrame(imageProxy, isFront = (lensFacing == CameraSelector.LENS_FACING_FRONT))
                }

                boundCamera = provider.bindToLifecycle(viewLifecycleOwner, selector, analysis)
                cameraHintTv.visibility = View.GONE
            } catch (t: Throwable) {
                Log.e(TAG, "CameraX bind failed", t)
                cameraHintTv.visibility = View.VISIBLE
                cameraHintTv.text = "相机未就绪"
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun handleCameraXFrame(imageProxy: ImageProxy, isFront: Boolean) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastAnalyzedAtMs < 120L) {
                return
            }
            lastAnalyzedAtMs = now

            val rawBitmap = imageProxy.toBitmap()
            val rot = imageProxy.imageInfo.rotationDegrees
            val uprightBitmap = if (rot != 0 || isFront) {
                val m = Matrix()
                if (rot != 0) m.postRotate(rot.toFloat())
                if (isFront) m.postScale(-1f, 1f)
                val transformed = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, m, true)
                if (transformed !== rawBitmap) rawBitmap.recycle()
                transformed
            } else {
                rawBitmap
            }
            processIncomingBitmap(uprightBitmap, recycleSource = true)
        } catch (t: Throwable) {
            Log.d(TAG, "Frame analysis skipped: ${t.message}")
        } finally {
            imageProxy.close()
        }
    }

    private fun processIncomingBitmap(uprightBitmap: Bitmap, recycleSource: Boolean) {
        val zoom = PoseMonitorHub.currentZoomRatio
        val px = PoseMonitorHub.panX
        val py = PoseMonitorHub.panY

        val roiBitmap = ZoomRoiCropper.cropAndScaleBitmap(
            source = uprightBitmap,
            zoomRatio = zoom,
            panX = px,
            panY = py,
            targetMaxDim = 540
        )
        if (recycleSource && roiBitmap !== uprightBitmap) {
            uprightBitmap.recycle()
        }

        try {
            val baos = ByteArrayOutputStream(24 * 1024)
            roiBitmap.compress(Bitmap.CompressFormat.JPEG, 68, baos)
            PoseMonitorHub.latestJpegSnapshot = baos.toByteArray()
        } catch (_: Throwable) {
        }

        val detector = poseDetector ?: return
        val frameResult = detector.analyzeRoiBitmap(roiBitmap)
        val decision = PoseMonitorHub.wakeTracker.onFrame(frameResult)

        if (decision.justTriggeredWake && isAdded) {
            val appCtx = context?.applicationContext
            if (appCtx != null) {
                BabyWakeAlarmController.triggerWakeAlarm(
                    context = appCtx,
                    babyName = PoseMonitorHub.babyName,
                    snapshot = decision,
                    syncSystemClock = PoseMonitorHub.syncSystemAlarmApp,
                    webhookUrl = PoseMonitorHub.watchWebhookUrl
                )
            }
        }

        mainHandler.post {
            if (!isAdded) {
                roiBitmap.recycle()
                return@post
            }
            cameraHintTv.visibility = View.GONE
            zoomedFeedImageView.setImageBitmap(roiBitmap)
            overlayView.updateDetection(frameResult, decision)
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

        overlayView.setZoomAndPanState(clamped, PoseMonitorHub.panX, PoseMonitorHub.panY)
        zoomBadgeTv.text = formatZoomBadge(clamped)
        zoomBadgeTv.visibility = if (clamped > 1.05f) View.VISIBLE else View.GONE
    }

    private fun formatZoomBadge(zoom: Float): String = "${"%.1f".format(Locale.US, zoom)}x"

    private fun shortPoseText(label: BabyPoseLabel): String = when (label) {
        BabyPoseLabel.NONE -> "检测中"
        BabyPoseLabel.SUPINE_CALM -> "仰卧"
        BabyPoseLabel.SIDE_LEFT -> "左侧卧"
        BabyPoseLabel.SIDE_RIGHT -> "右侧卧"
        BabyPoseLabel.PRONE_HEAD_UP -> "趴卧"
        BabyPoseLabel.LIMB_ACTIVE -> "体动"
        BabyPoseLabel.SITTING_AWAKE -> "坐起"
    }

    private fun refreshTelemetryUi(@Suppress("UNUSED_PARAMETER") result: PoseFrameResult?, snap: WakeDecisionSnapshot) {
        val poseShort = shortPoseText(snap.currentPose)
        poseBadgeTv.text = if (snap.currentPose == BabyPoseLabel.NONE) {
            poseShort
        } else {
            "$poseShort · ${snap.changeCount}/${snap.changeThreshold}"
        }

        alarmActionBar.visibility = if (snap.isAlarmRinging) View.VISIBLE else View.GONE

        when {
            snap.isAlarmRinging -> {
                statusDotView.background = makeCircleDrawable(ACCENT_ROSE)
                statusPillTv.setTextColor(Color.parseColor("#FDA4AF"))
                statusPillTv.text = "醒来"
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
            else -> {
                statusDotView.background = makeCircleDrawable(ACCENT_MINT)
                statusPillTv.setTextColor(TEXT_LIGHT)
                statusPillTv.text = "安睡"
            }
        }
    }

    private fun showSettingsDialog(ctx: Context, prefs: SharedPreferences) {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 12.dp(), 20.dp(), 8.dp())
        }

        // Angle threshold
        container.addView(makeDialogLabel(ctx, "角度灵敏度"))
        val angleGroup = ChipGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val curAngle = PoseMonitorHub.wakeTracker.angleThresholdDeg.roundToInt()
        listOf(15, 25, 35, 45).forEach { deg ->
            angleGroup.addView(Chip(ctx).apply {
                text = "≥${deg}°"
                isCheckable = true
                isChecked = (curAngle == deg)
                setOnClickListener {
                    PoseMonitorHub.wakeTracker.angleThresholdDeg = deg.toFloat()
                    prefs.edit().putFloat(KEY_ANGLE_THRESH_DEG, deg.toFloat()).apply()
                    refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
                }
            })
        }
        container.addView(angleGroup)

        // Change count threshold
        val countRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10.dp(), 0, 6.dp())
        }
        countRow.addView(makeDialogLabel(ctx, "唤醒次数").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val countTv = TextView(ctx).apply {
            text = "${PoseMonitorHub.wakeTracker.changeThreshold}"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(14.dp(), 0, 14.dp(), 0)
        }
        countRow.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "−"
            minimumWidth = 0
            minWidth = 38.dp()
            setPadding(8.dp(), 2.dp(), 8.dp(), 2.dp())
            setOnClickListener {
                val next = (PoseMonitorHub.wakeTracker.changeThreshold - 1).coerceIn(2, 12)
                PoseMonitorHub.wakeTracker.changeThreshold = next
                prefs.edit().putInt(KEY_THRESHOLD, next).apply()
                countTv.text = "$next"
                refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
            }
        })
        countRow.addView(countTv)
        countRow.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "+"
            minimumWidth = 0
            minWidth = 38.dp()
            setPadding(8.dp(), 2.dp(), 8.dp(), 2.dp())
            setOnClickListener {
                val next = (PoseMonitorHub.wakeTracker.changeThreshold + 1).coerceIn(2, 12)
                PoseMonitorHub.wakeTracker.changeThreshold = next
                prefs.edit().putInt(KEY_THRESHOLD, next).apply()
                countTv.text = "$next"
                refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
            }
        })
        container.addView(countRow)

        // Window duration
        container.addView(makeDialogLabel(ctx, "检测窗口"))
        val winGroup = ChipGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val curWin = (PoseMonitorHub.wakeTracker.windowMs / 1000L).toInt()
        listOf(15, 30, 60).forEach { sec ->
            winGroup.addView(Chip(ctx).apply {
                text = "${sec}s"
                isCheckable = true
                isChecked = (curWin == sec)
                setOnClickListener {
                    PoseMonitorHub.wakeTracker.windowMs = sec * 1000L
                    prefs.edit().putInt(KEY_WINDOW_SEC, sec).apply()
                    refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
                }
            })
        }
        container.addView(winGroup)

        // System clock switch
        val sysSwitch = SwitchMaterial(ctx).apply {
            text = "同步系统时钟闹钟"
            textSize = 13f
            isChecked = PoseMonitorHub.syncSystemAlarmApp
            setPadding(0, 8.dp(), 0, 8.dp())
            setOnCheckedChangeListener { _, checked ->
                PoseMonitorHub.syncSystemAlarmApp = checked
                prefs.edit().putBoolean(KEY_SYNC_SYS_CLOCK, checked).apply()
            }
        }
        container.addView(sysSwitch)

        // Quick test actions
        val testRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6.dp(), 0, 0)
        }
        testRow.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "模拟一次"
            textSize = 12f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8.dp()
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
            text = "测试闹钟"
            textSize = 12f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                PoseMonitorHub.triggerTestAlarm(ctx)
                refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
            }
        })
        container.addView(testRow)

        AlertDialog.Builder(ctx)
            .setTitle("检测设置")
            .setView(container)
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
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT_LIGHT)
            gravity = Gravity.CENTER
            background = makePillDrawable(PILL_BG, PILL_STROKE, 14.dp().toFloat())
        }
    }

    private fun makeDialogLabel(ctx: Context, label: String): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 6.dp(), 0, 4.dp())
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
