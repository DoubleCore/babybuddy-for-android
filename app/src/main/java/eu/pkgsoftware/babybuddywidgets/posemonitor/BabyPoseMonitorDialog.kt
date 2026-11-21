package eu.pkgsoftware.babybuddywidgets.posemonitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
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
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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
 * Full-screen experimental console for:
 * 1) Baby Object Detection + Pose Analysis on local CameraX or LAN IP/MJPEG camera
 * 2) 1.0x - 8.0x magnification + pan ROI for distant cribs
 * 3) Multi-pose-change wake-up detection -> Priority 1: Fast Alarm, Priority 2: Smartwatch access
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

    private enum class CameraSourceMode {
        LOCAL_BACK, LOCAL_FRONT, NETWORK_STREAM
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var analysisExecutor: ExecutorService? = null
    private var poseDetector: MlKitBabyPoseDetector? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var netCamGrabber: NetworkCameraFrameGrabber? = null

    private var sourceMode: CameraSourceMode = CameraSourceMode.LOCAL_BACK
    private var lastAnalyzedAtMs: Long = 0L

    // Views
    private lateinit var zoomedFeedImageView: ImageView
    private lateinit var overlayView: PoseOverlayAndZoomView
    private lateinit var cameraHintTv: TextView
    private lateinit var zoomValueTv: TextView
    private lateinit var zoomSeekBar: SeekBar
    private lateinit var netCamConfigBox: LinearLayout
    private lateinit var ipCamEdit: EditText

    private lateinit var statusBannerCard: MaterialCardView
    private lateinit var statusTitleTv: TextView
    private lateinit var statusSubTv: TextView
    private lateinit var currentPoseTv: TextView
    private lateinit var changeCountTv: TextView
    private lateinit var motionScoreTv: TextView
    private lateinit var historyTv: TextView
    private lateinit var thresholdValueTv: TextView
    private lateinit var dismissAlarmBtn: MaterialButton
    private lateinit var watchUrlTv: TextView
    private lateinit var webhookEdit: EditText

    private var suppressSeekCallback = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantedMap ->
        val camGranted = grantedMap[Manifest.permission.CAMERA] == true || hasCameraPermission()
        if (camGranted && sourceMode != CameraSourceMode.NETWORK_STREAM) {
            startSelectedCameraSource()
        } else if (!camGranted && sourceMode != CameraSourceMode.NETWORK_STREAM) {
            cameraHintTv.visibility = View.VISIBLE
            cameraHintTv.text = "未授予摄像头权限，可点击授权或切换至「局域网摄像头」"
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_BabyBuddyWidgets)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
        PoseMonitorHub.currentZoomRatio = prefs.getFloat(KEY_ZOOM, 2.0f).coerceIn(1.0f, 8.0f)
        PoseMonitorHub.wakeTracker.changeThreshold = prefs.getInt(KEY_THRESHOLD, 5).coerceIn(2, 12)
        PoseMonitorHub.wakeTracker.windowMs = prefs.getInt(KEY_WINDOW_SEC, 30).coerceIn(10, 120) * 1000L
        PoseMonitorHub.wakeTracker.angleThresholdDeg = prefs.getFloat(KEY_ANGLE_THRESH_DEG, 25f).coerceIn(12f, 60f)
        PoseMonitorHub.ipCameraStreamUrl = prefs.getString(KEY_IP_CAM_URL, "http://192.168.1.88:81/stream") ?: ""
        PoseMonitorHub.watchWebhookUrl = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        PoseMonitorHub.syncSystemAlarmApp = prefs.getBoolean(KEY_SYNC_SYS_CLOCK, false)

        analysisExecutor = Executors.newSingleThreadExecutor()
        poseDetector = MlKitBabyPoseDetector()
        PoseMonitorHub.addListener(this)

        // Start LAN Watch Server automatically for seamless watch networking
        BabyWatchServer.start(ctx, BabyWatchServer.DEFAULT_PORT)

        val rootScroll = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.surface_background))
            setPadding(14.dp(), 12.dp(), 14.dp(), 32.dp())
        }

        val rootCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Top Header Bar
        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(2.dp(), 4.dp(), 2.dp(), 10.dp())
        }
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = "🧪 婴儿姿态看护 · 待测实验台"
                textSize = 17.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
            })
            addView(TextView(ctx).apply {
                text = "照护对象：$babyName · 目标检测 + 姿态频变唤醒 + 闹钟 & 手表联动"
                textSize = 11.5f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
            })
        }
        topRow.addView(titleCol)
        topRow.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "收起"
            textSize = 12f
            isAllCaps = false
            minimumWidth = 0
            minWidth = 0
            setPadding(14.dp(), 6.dp(), 14.dp(), 6.dp())
            setOnClickListener { dismissAllowingStateLoss() }
        })
        rootCol.addView(topRow)

        // 2. Live Status Banner Card
        statusBannerCard = makeCard(ctx).apply {
            setCardBackgroundColor(Color.parseColor("#112A28"))
        }
        val bannerInner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
        }
        statusTitleTv = TextView(ctx).apply {
            text = "🌙 姿态监测待命 · 安睡看护中"
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#6EE7B7"))
        }
        statusSubTv = TextView(ctx).apply {
            text = "当检测到婴儿姿态连续变化达到阈值时，将立即拉响闹钟并推送至手表"
            textSize = 11.5f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 3.dp(), 0, 0)
        }
        bannerInner.addView(statusTitleTv)
        bannerInner.addView(statusSubTv)
        statusBannerCard.addView(bannerInner)
        rootCol.addView(statusBannerCard)

        // 3. Camera Feed + 1.0x~8.0x Zoom & Pan Card
        val camCard = makeCard(ctx)
        val camCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
        }

        // Camera source chips
        val sourceScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val sourceChipGroup = ChipGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val chipBack = Chip(ctx).apply {
            id = View.generateViewId()
            text = "📱 本机后置"
            isCheckable = true
            isChecked = true
        }
        val chipFront = Chip(ctx).apply {
            id = View.generateViewId()
            text = "🤳 本机前置"
            isCheckable = true
        }
        val chipNet = Chip(ctx).apply {
            id = View.generateViewId()
            text = "🌐 局域网摄像头 (MJPEG/IP)"
            isCheckable = true
        }
        sourceChipGroup.addView(chipBack)
        sourceChipGroup.addView(chipFront)
        sourceChipGroup.addView(chipNet)
        sourceScroll.addView(sourceChipGroup)
        camCol.addView(sourceScroll)

        // Network camera URL box (shown when NETWORK_STREAM selected)
        netCamConfigBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, 6.dp(), 0, 6.dp())
        }
        ipCamEdit = EditText(ctx).apply {
            hint = "http://192.168.x.x:81/stream 或 /shot.jpg"
            setText(PoseMonitorHub.ipCameraStreamUrl)
            textSize = 12.5f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnConnectNetCam = MaterialButton(ctx).apply {
            text = "连接流"
            textSize = 12f
            isAllCaps = false
            setOnClickListener {
                val url = ipCamEdit.text?.toString()?.trim().orEmpty()
                PoseMonitorHub.ipCameraStreamUrl = url
                prefs.edit().putString(KEY_IP_CAM_URL, url).apply()
                startSelectedCameraSource()
            }
        }
        netCamConfigBox.addView(ipCamEdit)
        netCamConfigBox.addView(btnConnectNetCam)
        camCol.addView(netCamConfigBox)

        sourceChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val cid = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            sourceMode = when (cid) {
                chipFront.id -> CameraSourceMode.LOCAL_FRONT
                chipNet.id -> CameraSourceMode.NETWORK_STREAM
                else -> CameraSourceMode.LOCAL_BACK
            }
            netCamConfigBox.visibility = if (sourceMode == CameraSourceMode.NETWORK_STREAM) View.VISIBLE else View.GONE
            poseDetector?.reset()
            startSelectedCameraSource()
        }

        // Camera Viewport Frame (displays magnified ROI + PoseOverlayAndZoomView)
        val viewportFrame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                250.dp()
            ).apply { topMargin = 8.dp() }
            background = GradientDrawable().apply {
                cornerRadius = 16.dp().toFloat()
                setColor(Color.parseColor("#0B101B"))
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
                applyZoomAndPan(z, px, py, updateSeekBar = true, prefs = prefs)
            }
        }
        cameraHintTv = TextView(ctx).apply {
            text = "正在初始化摄像头画面..."
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(20.dp(), 20.dp(), 20.dp(), 20.dp())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        viewportFrame.addView(zoomedFeedImageView)
        viewportFrame.addView(overlayView)
        viewportFrame.addView(cameraHintTv)
        camCol.addView(viewportFrame)

        // Zoom Controls (1.0x - 8.0x continuous SeekBar + Quick Presets)
        val zoomHeaderRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(2.dp(), 10.dp(), 2.dp(), 2.dp())
        }
        zoomValueTv = TextView(ctx).apply {
            text = "🔍 远距画面放大：${"%.1f".format(Locale.US, PoseMonitorHub.currentZoomRatio)}x"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        zoomHeaderRow.addView(zoomValueTv)
        zoomHeaderRow.addView(TextView(ctx).apply {
            text = "支持双指捏合 / 单指拖移定位"
            textSize = 11f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_muted_warm))
        })
        camCol.addView(zoomHeaderRow)

        zoomSeekBar = SeekBar(ctx).apply {
            max = 70 // 0 -> 1.0x, 70 -> 8.0x (0.1x steps)
            progress = ((PoseMonitorHub.currentZoomRatio - 1.0f) * 10f).roundToInt().coerceIn(0, 70)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (suppressSeekCallback || !fromUser) return
                    val z = 1.0f + progress / 10f
                    val px = if (z <= 1.02f) 0f else PoseMonitorHub.panX
                    val py = if (z <= 1.02f) 0f else PoseMonitorHub.panY
                    applyZoomAndPan(z, px, py, updateSeekBar = false, prefs = prefs)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        camCol.addView(zoomSeekBar)

        val zoomPresetsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(1.0f to "1x全景", 2.0f to "2x近景", 3.5f to "3.5x特写", 5.0f to "5x远距", 8.0f to "8x超远").forEach { (ratio, label) ->
            zoomPresetsRow.addView(Chip(ctx).apply {
                text = label
                textSize = 11f
                setOnClickListener {
                    val resetPan = ratio <= 1.05f
                    applyZoomAndPan(
                        ratio,
                        if (resetPan) 0f else PoseMonitorHub.panX,
                        if (resetPan) 0f else PoseMonitorHub.panY,
                        updateSeekBar = true,
                        prefs = prefs
                    )
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 5.dp() }
            })
        }
        camCol.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(zoomPresetsRow)
        })

        camCard.addView(camCol)
        rootCol.addView(camCard)

        // 4. Pose Telemetry & Multi-Change Threshold Card
        val poseCard = makeCard(ctx)
        val poseCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
        }
        poseCol.addView(makeSectionTitle(ctx, "🤸 姿态分析与苏醒判定参数"))

        currentPoseTv = TextView(ctx).apply {
            text = "当前识别姿态：等待目标..."
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
            setPadding(0, 6.dp(), 0, 2.dp())
        }
        changeCountTv = TextView(ctx).apply {
            text = "窗口内姿态变化：0 / ${PoseMonitorHub.wakeTracker.changeThreshold} 次"
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.bf_purple_primary))
        }
        motionScoreTv = TextView(ctx).apply {
            text = "关节角度：肘弯 --/-- · 抬臂 --/-- · 膝弯 --/-- · 侧翻 --"
            textSize = 11.8f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
            setPadding(0, 3.dp(), 0, 4.dp())
        }
        historyTv = TextView(ctx).apply {
            text = "最近姿态角度轨迹：暂无大幅关节角度变化"
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_muted_warm))
        }
        poseCol.addView(currentPoseTv)
        poseCol.addView(changeCountTv)
        poseCol.addView(motionScoreTv)
        poseCol.addView(historyTv)

        // Joint Angle Delta Threshold selector (ignores pixel jitter; triggers only on >= Δθ° joint bend/roll)
        val angleThreshRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8.dp(), 0, 2.dp())
        }
        angleThreshRow.addView(TextView(ctx).apply {
            text = "关节角度跳变判定："
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
        })
        val angleGroup = ChipGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val curAngleDeg = PoseMonitorHub.wakeTracker.angleThresholdDeg.roundToInt()
        listOf(15 to "≥15°灵敏", 25 to "≥25°标准", 35 to "≥35°防抖", 45 to "≥45°大幅").forEach { (deg, lbl) ->
            angleGroup.addView(Chip(ctx).apply {
                text = lbl
                textSize = 11f
                isCheckable = true
                isChecked = (curAngleDeg == deg)
                setOnClickListener {
                    PoseMonitorHub.wakeTracker.angleThresholdDeg = deg.toFloat()
                    prefs.edit().putFloat(KEY_ANGLE_THRESH_DEG, deg.toFloat()).apply()
                    refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
                }
            })
        }
        angleThreshRow.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(angleGroup)
        })
        poseCol.addView(angleThreshRow)

        // Threshold adjuster row
        val threshRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10.dp(), 0, 4.dp())
        }
        threshRow.addView(TextView(ctx).apply {
            text = "判定苏醒变化次数阈值："
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val btnMinus = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "−"
            minimumWidth = 0
            minWidth = 36.dp()
            setPadding(8.dp(), 2.dp(), 8.dp(), 2.dp())
            setOnClickListener {
                val next = (PoseMonitorHub.wakeTracker.changeThreshold - 1).coerceIn(2, 12)
                PoseMonitorHub.wakeTracker.changeThreshold = next
                prefs.edit().putInt(KEY_THRESHOLD, next).apply()
                refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
            }
        }
        thresholdValueTv = TextView(ctx).apply {
            text = "${PoseMonitorHub.wakeTracker.changeThreshold} 次"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(12.dp(), 0, 12.dp(), 0)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
        }
        val btnPlus = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "+"
            minimumWidth = 0
            minWidth = 36.dp()
            setPadding(8.dp(), 2.dp(), 8.dp(), 2.dp())
            setOnClickListener {
                val next = (PoseMonitorHub.wakeTracker.changeThreshold + 1).coerceIn(2, 12)
                PoseMonitorHub.wakeTracker.changeThreshold = next
                prefs.edit().putInt(KEY_THRESHOLD, next).apply()
                refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
            }
        }
        threshRow.addView(btnMinus)
        threshRow.addView(thresholdValueTv)
        threshRow.addView(btnPlus)
        poseCol.addView(threshRow)

        // Window duration chips
        val winRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        winRow.addView(TextView(ctx).apply {
            text = "检测时间窗口："
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
        })
        val winGroup = ChipGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val curWinSec = (PoseMonitorHub.wakeTracker.windowMs / 1000L).toInt()
        listOf(15, 30, 60).forEach { sec ->
            winGroup.addView(Chip(ctx).apply {
                text = "${sec}秒"
                isCheckable = true
                isChecked = (curWinSec == sec)
                setOnClickListener {
                    PoseMonitorHub.wakeTracker.windowMs = sec * 1000L
                    prefs.edit().putInt(KEY_WINDOW_SEC, sec).apply()
                    refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
                }
            })
        }
        winRow.addView(winGroup)
        poseCol.addView(winRow)

        poseCard.addView(poseCol)
        rootCol.addView(poseCard)

        // 5. Priority 1: Fast Alarm Control Card
        val alarmCard = makeCard(ctx)
        val alarmCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
        }
        alarmCol.addView(makeSectionTitle(ctx, "① 唤醒闹钟联动（第一响应）"))

        val alarmBtnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dp(), 0, 4.dp())
        }
        dismissAlarmBtn = MaterialButton(ctx).apply {
            text = "🔕 关闭响铃闹钟"
            isAllCaps = false
            setBackgroundColor(Color.parseColor("#E11D48"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 6.dp()
            }
            setOnClickListener {
                PoseMonitorHub.dismissAlarm(ctx)
                refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
            }
        }
        val snoozeBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "💤 贪睡 3 分钟"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                PoseMonitorHub.snoozeAlarm(ctx)
                refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
            }
        }
        alarmBtnRow.addView(dismissAlarmBtn)
        alarmBtnRow.addView(snoozeBtn)
        alarmCol.addView(alarmBtnRow)

        val simRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 2.dp(), 0, 6.dp())
        }
        simRow.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "➕ 模拟 1 次姿态变化"
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
        simRow.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "🔔 直接测试闹钟+手表"
            textSize = 11.5f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                PoseMonitorHub.triggerTestAlarm(ctx)
                refreshTelemetryUi(null, PoseMonitorHub.wakeTracker.currentSnapshot())
            }
        })
        alarmCol.addView(simRow)

        val sysAlarmSwitch = SwitchMaterial(ctx).apply {
            text = "触发时同步调起系统时钟 App 闹钟 (ACTION_SET_ALARM)"
            textSize = 12f
            isChecked = PoseMonitorHub.syncSystemAlarmApp
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
            setOnCheckedChangeListener { _, checked ->
                PoseMonitorHub.syncSystemAlarmApp = checked
                prefs.edit().putBoolean(KEY_SYNC_SYS_CLOCK, checked).apply()
            }
        }
        alarmCol.addView(sysAlarmSwitch)
        alarmCard.addView(alarmCol)
        rootCol.addView(alarmCard)

        // 6. Priority 2: Smartwatch Access & Device Networking Card
        val watchCard = makeCard(ctx)
        val watchCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
        }
        watchCol.addView(makeSectionTitle(ctx, "② 手表形式访问与设备联网（第二响应）"))

        val lanUrls = BabyWatchServer.resolveLocalLanUrls()
        watchUrlTv = TextView(ctx).apply {
            text = "⌚ 手表局域网表盘地址：\n${lanUrls.joinToString("\n")}"
            textSize = 12.5f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.bf_purple_primary))
            setPadding(0, 6.dp(), 0, 6.dp())
        }
        watchCol.addView(watchUrlTv)

        watchCol.addView(TextView(ctx).apply {
            text = "• 方式 A（局域网手表表盘）：手表浏览器或调试端访问上方 /watch 地址，支持圆表盘报警、关闭闹钟、远程放大与画面快照\n" +
                "• 方式 B（蓝牙智能手表联动）：已内置 WearableExtender 强提醒，配对手表自动震动并显示「关闭闹钟 / 贪睡」\n" +
                "• 方式 C（硬件 JSON 接口）：GET /api/status · GET /api/snapshot.jpg · POST /api/alarm/dismiss"
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary_warm))
        })

        val watchBtnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dp(), 0, 6.dp())
        }
        watchBtnRow.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "📋 复制手表地址"
            textSize = 12f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 6.dp()
            }
            setOnClickListener {
                val primaryUrl = BabyWatchServer.resolveLocalLanUrls().firstOrNull() ?: "http://127.0.0.1:8765/watch"
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("BabyWatchURL", primaryUrl))
                Toast.makeText(ctx, "已复制: $primaryUrl", Toast.LENGTH_SHORT).show()
            }
        })
        watchBtnRow.addView(MaterialButton(ctx).apply {
            text = "⌚ 预览手表端界面"
            textSize = 12f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                showInAppWatchPreviewDialog(ctx)
            }
        })
        watchCol.addView(watchBtnRow)

        // Optional Webhook URL for external Watch / IoT push
        val webhookRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        webhookEdit = EditText(ctx).apply {
            hint = "可选：手表/网关 Webhook 推送 URL (http://...)"
            setText(PoseMonitorHub.watchWebhookUrl)
            textSize = 12f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        webhookRow.addView(webhookEdit)
        webhookRow.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "保存"
            textSize = 11.5f
            isAllCaps = false
            minimumWidth = 0
            minWidth = 0
            setOnClickListener {
                val url = webhookEdit.text?.toString()?.trim().orEmpty()
                PoseMonitorHub.watchWebhookUrl = url
                prefs.edit().putString(KEY_WEBHOOK_URL, url).apply()
                Toast.makeText(ctx, "手表推送地址已保存", Toast.LENGTH_SHORT).show()
            }
        })
        watchCol.addView(webhookRow)

        watchCard.addView(watchCol)
        rootCol.addView(watchCard)

        rootScroll.addView(rootCol)
        return rootScroll
    }

    override fun onResume() {
        super.onResume()
        PoseMonitorHub.isMonitoring = true
        refreshWatchUrls()
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
            applyZoomAndPan(newZoom, PoseMonitorHub.panX, PoseMonitorHub.panY, updateSeekBar = true, prefs = prefs)
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
                cameraHintTv.text = "请输入局域网摄像头流地址并点击「连接流」"
                return
            }
            cameraHintTv.visibility = View.VISIBLE
            cameraHintTv.text = "正在连接局域网摄像头..."
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
            cameraHintTv.text = "需要相机权限以开启婴儿姿态检测（点击此处授权）"
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
                cameraHintTv.text = "本机摄像头未就绪 (${t.message ?: "无相机硬件"})，可切换至「局域网摄像头」或使用下方模拟测试"
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun handleCameraXFrame(imageProxy: ImageProxy, isFront: Boolean) {
        try {
            val now = System.currentTimeMillis()
            // Throttle analysis & display to ~8 FPS for smooth low-power operation
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

        // Compress zoomed frame for Smartwatch /api/snapshot.jpg
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
        updateSeekBar: Boolean,
        prefs: android.content.SharedPreferences
    ) {
        val clamped = ZoomRoiCropper.clampZoom(zoomRatio)
        PoseMonitorHub.currentZoomRatio = clamped
        PoseMonitorHub.panX = panX.coerceIn(-1f, 1f)
        PoseMonitorHub.panY = panY.coerceIn(-1f, 1f)
        prefs.edit().putFloat(KEY_ZOOM, clamped).apply()

        zoomValueTv.text = "🔍 远距画面放大：${"%.1f".format(Locale.US, clamped)}x"
        overlayView.setZoomAndPanState(clamped, PoseMonitorHub.panX, PoseMonitorHub.panY)

        if (updateSeekBar) {
            suppressSeekCallback = true
            zoomSeekBar.progress = ((clamped - 1.0f) * 10f).roundToInt().coerceIn(0, 70)
            suppressSeekCallback = false
        }
    }

    private fun refreshWatchUrls() {
        val urls = BabyWatchServer.resolveLocalLanUrls()
        watchUrlTv.text = "⌚ 手表局域网表盘地址：\n${urls.joinToString("\n")}"
    }

    private fun refreshTelemetryUi(result: PoseFrameResult?, snap: WakeDecisionSnapshot) {
        val srcSuffix = result?.sourceTag?.let { " [$it]" } ?: ""
        val deltaInt = snap.lastAngleDeltaDeg.roundToInt()
        val threshInt = snap.angleThresholdDeg.roundToInt()
        currentPoseTv.text = "当前识别姿态：${snap.currentPose.zhLabel}$srcSuffix"
        changeCountTv.text = "窗口内大幅角度变化：${snap.changeCount} / ${snap.changeThreshold} 次（近 ${snap.windowSec}s · 距锚点 Δ${deltaInt}° / 阈值≥${threshInt}°）"
        motionScoreTv.text = "实时关节角度：${snap.angleProfile.summaryText()}"
        thresholdValueTv.text = "${snap.changeThreshold} 次"

        historyTv.text = if (snap.recentPoseHistory.isEmpty()) {
            "最近姿态角度轨迹：关节角度稳定（忽略像素微晃）"
        } else {
            "最近姿态角度轨迹：${snap.recentPoseHistory.joinToString(" · ")}"
        }

        if (snap.isAlarmRinging) {
            statusBannerCard.setCardBackgroundColor(Color.parseColor("#4C0519"))
            statusTitleTv.setTextColor(Color.parseColor("#FDA4AF"))
            statusTitleTv.text = "🚨 判定婴儿即将醒来！已拉响闹钟并推送手表"
            statusSubTv.text = "姿态已在 ${snap.windowSec}s 内变化 ${snap.changeCount} 次，可在下方或手表端关闭闹钟"
            dismissAlarmBtn.isEnabled = true
            dismissAlarmBtn.alpha = 1.0f
        } else if (snap.snoozedRemainingSec > 0) {
            statusBannerCard.setCardBackgroundColor(Color.parseColor("#1E293B"))
            statusTitleTv.setTextColor(Color.parseColor("#93C5FD"))
            statusTitleTv.text = "💤 贪睡静音中（剩余 ${snap.snoozedRemainingSec} 秒）"
            statusSubTv.text = "贪睡结束后将自动恢复姿态频变监测"
            dismissAlarmBtn.alpha = 0.65f
        } else if (snap.changeCount >= (snap.changeThreshold + 1) / 2) {
            statusBannerCard.setCardBackgroundColor(Color.parseColor("#3B2506"))
            statusTitleTv.setTextColor(Color.parseColor("#FCD34D"))
            statusTitleTv.text = "⚠️ 婴儿体动增多（${snap.changeCount}/${snap.changeThreshold} 次）"
            statusSubTv.text = "正在持续追踪姿态变化，达到 ${snap.changeThreshold} 次将立即唤醒闹钟"
            dismissAlarmBtn.alpha = 0.65f
        } else {
            statusBannerCard.setCardBackgroundColor(Color.parseColor("#112A28"))
            statusTitleTv.setTextColor(Color.parseColor("#6EE7B7"))
            statusTitleTv.text = "🌙 姿态平稳 · 安睡看护中"
            statusSubTv.text = "实时检测画面目标与姿态，支持 1x–8x 放大看清远处婴儿床"
            dismissAlarmBtn.alpha = 0.65f
        }
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
                420.dp()
            )
            loadUrl(localUrl)
        }
        AlertDialog.Builder(ctx)
            .setTitle("⌚ 手表端访问实时预览")
            .setView(webView)
            .setPositiveButton("关闭预览") { d, _ ->
                webView.destroy()
                d.dismiss()
            }
            .show()
    }

    private fun makeCard(ctx: Context): MaterialCardView {
        return MaterialCardView(ctx).apply {
            radius = 20.dp().toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.card_surface))
            strokeColor = ContextCompat.getColor(ctx, R.color.card_stroke)
            strokeWidth = 1.dp()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12.dp() }
        }
    }

    private fun makeSectionTitle(ctx: Context, text: String): TextView {
        return TextView(ctx).apply {
            this.text = text
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary_warm))
        }
    }
}
