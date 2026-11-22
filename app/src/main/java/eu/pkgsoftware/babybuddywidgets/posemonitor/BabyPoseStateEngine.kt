package eu.pkgsoftware.babybuddywidgets.posemonitor

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class BabyPoseLabel(val code: String, val zhLabel: String, val enLabel: String) {
    NONE("none", "未检测到婴儿姿态", "No Baby Pose Detected"),
    SUPINE_CALM("supine_calm", "仰卧平放", "Supine Relaxed"),
    SIDE_LEFT("side_left", "左侧卧/翻身", "Left Side Roll"),
    SIDE_RIGHT("side_right", "右侧卧/翻身", "Right Side Roll"),
    PRONE_HEAD_UP("prone_head_up", "趴卧/俯睡", "Prone / Forearm Prop"),
    LIMB_ACTIVE("limb_active", "屈臂挥动/蹬腿", "Arm Bend / Leg Kick"),
    SITTING_AWAKE("sitting_awake", "屈髋撑起/坐立", "Hip-Flexed Sitting Up");

    fun displayLabel(isZh: Boolean = true): String = if (isZh) zhLabel else enLabel
}

/**
 * Selectable on-device pose estimation model & inference strategy.
 */
enum class PoseEngineMode(
    val code: String,
    val shortTitle: String,
    val modelDisplayName: String,
    val description: String,
    val targetInputDim: Int,
    val isYolo: Boolean
) {
    YOLO26N_NCNN_320(
        code = "yolo26n_ncnn_320",
        shortTitle = "YOLO26n 极速(推荐)",
        modelDisplayName = "YOLO26n-pose 320 (Tencent ncnn FP16 · 本地内置)",
        description = "内置腾讯 ncnn C++ 硬件加速引擎 + FP16 量化 YOLO26n-pose (仅 1.9 GFLOPs, 5.6MB 免下载)：开启 Vulkan GPU 仅需 ~6-15ms，极致丝滑不发热",
        targetInputDim = 320,
        isYolo = true
    ),
    YOLO26N_NCNN_480(
        code = "yolo26n_ncnn_480",
        shortTitle = "YOLO26n 超清480",
        modelDisplayName = "YOLO26n-pose 480 (Tencent ncnn FP16 · 本地内置)",
        description = "内置 480×480 高分辨率 FP16 权重 (5.7MB 免下载)，适合 1x 全景远距离婴儿床小目标看护 (~14-25ms)",
        targetInputDim = 480,
        isYolo = true
    ),
    YOLO11S_NCNN_CLOUD(
        code = "yolo11s_ncnn_cloud",
        shortTitle = "YOLO11s 云端高精",
        modelDisplayName = "YOLO11s-pose 480 (Tencent ncnn FP16 · 云端 17.6MB)",
        description = "中大型 9.9M 参数 FP16 模型 (COCO mAP50=86.3%)，支持 App 内一键拉取缓存并通过 ncnn Vulkan GPU 加速运行",
        targetInputDim = 480,
        isYolo = true
    ),
    YOLO_CUSTOM_NCNN(
        code = "yolo_custom_ncnn",
        shortTitle = "自定义 ncnn(.zip)",
        modelDisplayName = "自定义婴儿微调 YOLO Pose (ncnn FP16 .zip)",
        description = "支持填入包含 model.ncnn.param 与 model.ncnn.bin 的自定义微调模型 .zip 下载链接",
        targetInputDim = 480,
        isYolo = true
    ),
    ACCURATE_SINGLE(
        code = "accurate_single",
        shortTitle = "BlazePose 高精",
        modelDisplayName = "Google ML Kit BlazePose Accurate (33点3D对照)",
        description = "Google 官方 33 点模型对照档位",
        targetInputDim = 640,
        isYolo = false
    ),
    FAST_STREAM(
        code = "fast_stream",
        shortTitle = "BlazePose 轻量",
        modelDisplayName = "Google ML Kit BlazePose Lite (省电对照)",
        description = "Google 轻量模型对照档位",
        targetInputDim = 480,
        isYolo = false
    );

    companion object {
        fun fromCode(code: String?): PoseEngineMode =
            entries.find { it.code == code } ?: YOLO26N_NCNN_320
    }
}

/**
 * User-tunable pose detection & wake-up alarm thresholds.
 */
data class PoseDetectionConfig(
    val engineMode: PoseEngineMode = PoseEngineMode.YOLO26N_NCNN_320,
    val preferVulkanGpu: Boolean = true,
    val minLandmarkConfidence: Float = 0.45f,
    val angleThresholdDeg: Float = 22f,
    val rollThresholdDeg: Float = 28f,
    val limbDeltaThresholdDeg: Float = 26f,
    val smoothingStrength: Float = 0.55f,
    val confirmFrames: Int = 2,
    val changeThreshold: Int = 4,
    val windowSec: Int = 30,
    val minChangeIntervalMs: Long = 1_500L,
    val enableContourFallback: Boolean = false,
    val baselineRollOffsetDeg: Float = 0f,
    val showTelemetryHud: Boolean = true,
    val customYoloUrl: String = ""
) {
    companion object {
        val PRESET_SENSITIVE = PoseDetectionConfig(
            minLandmarkConfidence = 0.35f,
            angleThresholdDeg = 15f,
            rollThresholdDeg = 22f,
            limbDeltaThresholdDeg = 20f,
            smoothingStrength = 0.40f,
            confirmFrames = 1,
            changeThreshold = 3,
            windowSec = 30,
            minChangeIntervalMs = 1_200L
        )

        val PRESET_BALANCED = PoseDetectionConfig()

        val PRESET_STRICT = PoseDetectionConfig(
            minLandmarkConfidence = 0.60f,
            angleThresholdDeg = 32f,
            rollThresholdDeg = 35f,
            limbDeltaThresholdDeg = 34f,
            smoothingStrength = 0.70f,
            confirmFrames = 3,
            changeThreshold = 6,
            windowSec = 30,
            minChangeIntervalMs = 2_000L
        )
    }
}

data class NormalizedPoint(
    val x: Float,
    val y: Float,
    val confidence: Float = 1f,
    val z: Float = 0f
)

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
}

data class IntCropRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

/**
 * 17-point infant anatomical skeleton (with 3D depth z and facial/ear landmarks for blanket/upper-body mode).
 */
data class PoseKeypoints(
    val nose: NormalizedPoint?,
    val leftShoulder: NormalizedPoint?,
    val rightShoulder: NormalizedPoint?,
    val leftElbow: NormalizedPoint? = null,
    val rightElbow: NormalizedPoint? = null,
    val leftWrist: NormalizedPoint?,
    val rightWrist: NormalizedPoint?,
    val leftHip: NormalizedPoint?,
    val rightHip: NormalizedPoint?,
    val leftKnee: NormalizedPoint? = null,
    val rightKnee: NormalizedPoint? = null,
    val leftAnkle: NormalizedPoint?,
    val rightAnkle: NormalizedPoint?,
    val leftEye: NormalizedPoint? = null,
    val rightEye: NormalizedPoint? = null,
    val leftEar: NormalizedPoint? = null,
    val rightEar: NormalizedPoint? = null
) {
    fun allPoints(): List<NormalizedPoint> = listOfNotNull(
        nose, leftEye, rightEye, leftEar, rightEar,
        leftShoulder, rightShoulder,
        leftElbow, rightElbow,
        leftWrist, rightWrist,
        leftHip, rightHip,
        leftKnee, rightKnee,
        leftAnkle, rightAnkle
    )

    fun coreBodyPoints(): List<NormalizedPoint> = listOfNotNull(
        nose,
        leftShoulder, rightShoulder,
        leftElbow, rightElbow,
        leftWrist, rightWrist,
        leftHip, rightHip,
        leftKnee, rightKnee,
        leftAnkle, rightAnkle
    )

    fun headReferencePoint(minConf: Float): NormalizedPoint? {
        val pts = listOfNotNull(nose, leftEye, rightEye, leftEar, rightEar)
            .filter { it.confidence >= minConf }
        if (pts.isEmpty()) return null
        val sumW = pts.sumOf { it.confidence.toDouble() }.toFloat().coerceAtLeast(1e-4f)
        val wx = pts.sumOf { (it.x * it.confidence).toDouble() }.toFloat() / sumW
        val wy = pts.sumOf { (it.y * it.confidence).toDouble() }.toFloat() / sumW
        val wz = pts.sumOf { (it.z * it.confidence).toDouble() }.toFloat() / sumW
        val maxC = pts.maxOf { it.confidence }
        return NormalizedPoint(wx, wy, maxC, wz)
    }
}

/**
 * Biomechanical joint-angle profile (in degrees) representing the infant's body pose.
 * Null values indicate a joint whose landmarks were not visible with sufficient confidence.
 */
data class PoseAngleProfile(
    val leftElbowDeg: Float?,       // ∠左肩-左肘-左腕 (胳膊弯曲角: 170°伸直, 65°弯曲)
    val rightElbowDeg: Float?,      // ∠右肩-右肘-右腕 (胳膊弯曲角)
    val leftShoulderDeg: Float?,    // ∠左髋-左肩-左肘 (大臂外展/上举角: 25°贴身, 140°上举)
    val rightShoulderDeg: Float?,   // ∠右髋-右肩-右肘 (大臂外展/上举角)
    val leftKneeDeg: Float?,        // ∠左髋-左膝-左踝 (腿部弯曲/蹬腿角)
    val rightKneeDeg: Float?,       // ∠右髋-右膝-右踝 (腿部弯曲/蹬腿角)
    val leftHipDeg: Float?,         // ∠左肩-左髋-左膝 (髋部屈曲角: 170°平躺, 90°坐起/蜷腿)
    val rightHipDeg: Float?,        // ∠右肩-右髋-右膝 (髋部屈曲角)
    val torsoTiltDeg: Float?,       // 躯干轴线相对画面竖直方向倾角 (0°..180°)
    val bodyRollDeg: Float?,        // 躯干左右侧翻等效角 (-90°左侧卧 .. 0°仰卧 .. +90°右侧卧)
    val torsoChirality: Float? = null // 旋转不变躯干正反手性 (+1 面向镜头仰卧, -1 背向镜头俯卧趴睡)
) {
    fun validJointCount(): Int = listOfNotNull(
        leftElbowDeg, rightElbowDeg,
        leftShoulderDeg, rightShoulderDeg,
        leftKneeDeg, rightKneeDeg,
        leftHipDeg, rightHipDeg,
        bodyRollDeg
    ).size

    fun summaryText(): String {
        fun fmt(v: Float?): String = v?.roundToInt()?.let { "${it}°" } ?: "--"
        return "肘弯 L${fmt(leftElbowDeg)}/R${fmt(rightElbowDeg)} · " +
            "抬臂 L${fmt(leftShoulderDeg)}/R${fmt(rightShoulderDeg)} · " +
            "膝弯 L${fmt(leftKneeDeg)}/R${fmt(rightKneeDeg)} · " +
            "侧翻 ${fmt(bodyRollDeg)}"
    }

    companion object {
        val EMPTY = PoseAngleProfile(
            leftElbowDeg = null,
            rightElbowDeg = null,
            leftShoulderDeg = null,
            rightShoulderDeg = null,
            leftKneeDeg = null,
            rightKneeDeg = null,
            leftHipDeg = null,
            rightHipDeg = null,
            torsoTiltDeg = null,
            bodyRollDeg = null,
            torsoChirality = null
        )

        fun presetFor(pose: BabyPoseLabel): PoseAngleProfile = when (pose) {
            BabyPoseLabel.NONE -> EMPTY
            BabyPoseLabel.SUPINE_CALM -> PoseAngleProfile(
                leftElbowDeg = 135f, rightElbowDeg = 138f,
                leftShoulderDeg = 42f, rightShoulderDeg = 40f,
                leftKneeDeg = 142f, rightKneeDeg = 145f,
                leftHipDeg = 162f, rightHipDeg = 164f,
                torsoTiltDeg = 4f, bodyRollDeg = 0f, torsoChirality = 0.95f
            )
            BabyPoseLabel.SIDE_LEFT -> PoseAngleProfile(
                leftElbowDeg = 105f, rightElbowDeg = 72f,
                leftShoulderDeg = 55f, rightShoulderDeg = 68f,
                leftKneeDeg = 118f, rightKneeDeg = 96f,
                leftHipDeg = 132f, rightHipDeg = 124f,
                torsoTiltDeg = 14f, bodyRollDeg = -52f, torsoChirality = 0.45f
            )
            BabyPoseLabel.SIDE_RIGHT -> PoseAngleProfile(
                leftElbowDeg = 74f, rightElbowDeg = 108f,
                leftShoulderDeg = 66f, rightShoulderDeg = 52f,
                leftKneeDeg = 98f, rightKneeDeg = 120f,
                leftHipDeg = 125f, rightHipDeg = 134f,
                torsoTiltDeg = 15f, bodyRollDeg = 52f, torsoChirality = 0.45f
            )
            BabyPoseLabel.PRONE_HEAD_UP -> PoseAngleProfile(
                leftElbowDeg = 66f, rightElbowDeg = 68f,
                leftShoulderDeg = 86f, rightShoulderDeg = 84f,
                leftKneeDeg = 154f, rightKneeDeg = 150f,
                leftHipDeg = 158f, rightHipDeg = 156f,
                torsoTiltDeg = 22f, bodyRollDeg = 8f, torsoChirality = -0.85f
            )
            BabyPoseLabel.LIMB_ACTIVE -> PoseAngleProfile(
                leftElbowDeg = 54f, rightElbowDeg = 162f,
                leftShoulderDeg = 142f, rightShoulderDeg = 98f,
                leftKneeDeg = 72f, rightKneeDeg = 168f,
                leftHipDeg = 102f, rightHipDeg = 165f,
                torsoTiltDeg = 12f, bodyRollDeg = -10f, torsoChirality = 0.88f
            )
            BabyPoseLabel.SITTING_AWAKE -> PoseAngleProfile(
                leftElbowDeg = 156f, rightElbowDeg = 158f,
                leftShoulderDeg = 36f, rightShoulderDeg = 38f,
                leftKneeDeg = 108f, rightKneeDeg = 110f,
                leftHipDeg = 86f, rightHipDeg = 88f,
                torsoTiltDeg = 8f, bodyRollDeg = 0f, torsoChirality = 0.92f
            )
        }
    }
}

/**
 * Summary of angular difference between two [PoseAngleProfile] snapshots.
 */
data class AngleDeltaSummary(
    val maxDeltaDeg: Float,
    val weightedMeanDeltaDeg: Float,
    val dominantJointName: String,
    val comparedJointCount: Int
)

object PoseGeometryMath {
    /**
     * Computes interior joint angle ∠ABC at [vertex] in degrees [0..180].
     * Incorporates damped 3D depth `z` (when present) to mitigate perspective foreshortening
     * when limbs point partially toward/away from the camera.
     */
    fun jointAngleDeg(
        a: NormalizedPoint?,
        vertex: NormalizedPoint?,
        c: NormalizedPoint?,
        minConfidence: Float = 0.35f,
        depthScale: Float = 0.35f
    ): Float? {
        if (a == null || vertex == null || c == null) return null
        if (a.confidence < minConfidence || vertex.confidence < minConfidence || c.confidence < minConfidence) {
            return null
        }
        val v1x = a.x - vertex.x
        val v1y = a.y - vertex.y
        val v1z = (a.z - vertex.z) * depthScale
        val v2x = c.x - vertex.x
        val v2y = c.y - vertex.y
        val v2z = (c.z - vertex.z) * depthScale

        val len1 = sqrt(v1x * v1x + v1y * v1y + v1z * v1z)
        val len2 = sqrt(v2x * v2x + v2y * v2y + v2z * v2z)
        if (len1 < 1e-4f || len2 < 1e-4f) return null
        val dot = v1x * v2x + v1y * v2y + v1z * v2z
        val cosTheta = (dot / (len1 * len2)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosTheta.toDouble())).toFloat()
    }

    /**
     * Computes full 360°-rotation-invariant [PoseAngleProfile] from skeletal landmarks.
     * Supports both full-body visibility and blanket/sleep-sack upper-body-only visibility.
     */
    fun computeAngles(
        kp: PoseKeypoints,
        minConf: Float = 0.45f,
        baselineRollOffsetDeg: Float = 0f
    ): PoseAngleProfile {
        val lElbow = jointAngleDeg(kp.leftShoulder, kp.leftElbow, kp.leftWrist, minConf)
        val rElbow = jointAngleDeg(kp.rightShoulder, kp.rightElbow, kp.rightWrist, minConf)

        val ls = kp.leftShoulder?.takeIf { it.confidence >= minConf }
        val rs = kp.rightShoulder?.takeIf { it.confidence >= minConf }
        val lh = kp.leftHip?.takeIf { it.confidence >= minConf }
        val rh = kp.rightHip?.takeIf { it.confidence >= minConf }
        val headRef = kp.headReferencePoint(minConf)

        // Establish body-downward axis T (pointing from head/shoulders toward hips/feet)
        var sMidX = 0f
        var sMidY = 0f
        var sMidZ = 0f
        var hasShoulders = false
        if (ls != null && rs != null) {
            sMidX = (ls.x + rs.x) * 0.5f
            sMidY = (ls.y + rs.y) * 0.5f
            sMidZ = (ls.z + rs.z) * 0.5f
            hasShoulders = true
        }

        var torsoVecX = 0f
        var torsoVecY = 0f
        var torsoLen = 0f
        var hasHips = false
        if (hasShoulders && (lh != null || rh != null)) {
            val hMidX = if (lh != null && rh != null) (lh.x + rh.x) * 0.5f else (lh ?: rh)!!.x
            val hMidY = if (lh != null && rh != null) (lh.y + rh.y) * 0.5f else (lh ?: rh)!!.y
            torsoVecX = hMidX - sMidX
            torsoVecY = hMidY - sMidY
            torsoLen = hypot(torsoVecX, torsoVecY)
            hasHips = torsoLen >= 0.035f
        }
        if (!hasHips && hasShoulders && headRef != null) {
            // Blanket / Sleep-Sack Upper-Body Fallback: infer torso downward axis from head -> shoulderMid
            val hx = sMidX - headRef.x
            val hy = sMidY - headRef.y
            val hLen = hypot(hx, hy)
            if (hLen >= 0.025f) {
                torsoVecX = hx * 2.0f
                torsoVecY = hy * 2.0f
                torsoLen = hLen * 2.0f
            }
        }

        // Virtual hip anchors when lower body is under a blanket so shoulder abduction angles still work!
        val virtualLeftHip = lh ?: if (ls != null && torsoLen >= 0.05f) {
            NormalizedPoint(ls.x + torsoVecX, ls.y + torsoVecY, ls.confidence, ls.z)
        } else null
        val virtualRightHip = rh ?: if (rs != null && torsoLen >= 0.05f) {
            NormalizedPoint(rs.x + torsoVecX, rs.y + torsoVecY, rs.confidence, rs.z)
        } else null

        val lArmEnd = kp.leftElbow?.takeIf { it.confidence >= minConf } ?: kp.leftWrist
        val rArmEnd = kp.rightElbow?.takeIf { it.confidence >= minConf } ?: kp.rightWrist
        val lShoulder = jointAngleDeg(virtualLeftHip, kp.leftShoulder, lArmEnd, minConf)
        val rShoulder = jointAngleDeg(virtualRightHip, kp.rightShoulder, rArmEnd, minConf)

        val lKnee = jointAngleDeg(kp.leftHip, kp.leftKnee, kp.leftAnkle, minConf)
        val rKnee = jointAngleDeg(kp.rightHip, kp.rightKnee, kp.rightAnkle, minConf)

        val lLegEnd = kp.leftKnee?.takeIf { it.confidence >= minConf } ?: kp.leftAnkle
        val rLegEnd = kp.rightKnee?.takeIf { it.confidence >= minConf } ?: kp.rightAnkle
        val lHip = jointAngleDeg(kp.leftShoulder, kp.leftHip, lLegEnd, minConf)
        val rHip = jointAngleDeg(kp.rightShoulder, kp.rightHip, rLegEnd, minConf)

        var torsoTilt: Float? = null
        var bodyRoll: Float? = null
        var chirality: Float? = null

        if (ls != null && rs != null && torsoLen >= 0.04f) {
            val tx = torsoVecX / torsoLen
            val ty = torsoVecY / torsoLen

            // 1. Torso tilt relative to image vertical (0° = aligned vertically)
            torsoTilt = Math.toDegrees(atan2(abs(torsoVecX).toDouble(), abs(torsoVecY).toDouble())).toFloat()

            // 2. Euclidean 2D shoulder span (rotation-invariant across 0..360° crib orientations!)
            val sx = ls.x - rs.x
            val sy = ls.y - rs.y
            val shoulderSpan2D = hypot(sx, sy)

            // 3. Rotation-invariant signed cross-product chirality:
            // In image coords (+x right, +y down), when facing camera (Supine), anatomical LEFT_SHOULDER
            // lies along body-leftward normal (ty, -tx), so S x T = sx*ty - sy*tx > 0.
            // When facing away from camera (Prone / on stomach), S x T < 0.
            if (shoulderSpan2D >= 0.015f) {
                chirality = ((sx * ty - sy * tx) / shoulderSpan2D).coerceIn(-1f, 1f)
            }

            // 4. Multi-cue 3D + 2D Body Roll (-90° Left Side .. 0° Supine .. +90° Right Side)
            val expectedSpan = (torsoLen * 0.68f).coerceAtLeast(0.06f)
            val foreshorten = (1f - (shoulderSpan2D / expectedSpan).coerceIn(0f, 1f)).coerceIn(0f, 1f)

            // Unit vector pointing toward subject's anatomical LEFT in 2D image plane (for supine orientation)
            val chiralitySign = if ((chirality ?: 1f) >= 0f) 1f else -1f
            val uAnatLeftX = ty * chiralitySign
            val uAnatLeftY = -tx * chiralitySign

            // Cue A: Nose / face lateral projection relative to shoulder midpoint
            val nose = kp.nose?.takeIf { it.confidence >= minConf }
            val noseLatRatio = if (nose != null) {
                val ndx = nose.x - sMidX
                val ndy = nose.y - sMidY
                ((ndx * uAnatLeftX + ndy * uAnatLeftY) / max(0.045f, shoulderSpan2D)).coerceIn(-1.25f, 1.25f)
            } else {
                0f
            }

            // Cue B: 3D depth differential (negative z = closer to camera; rolling left brings right shoulder closer -> ls.z > rs.z)
            val dz = ls.z - rs.z
            val has3DDepth = abs(ls.z) > 1e-4f || abs(rs.z) > 1e-4f
            val depthRollDeg = if (has3DDepth) {
                Math.toDegrees(atan2(dz.toDouble(), max(0.05f, shoulderSpan2D).toDouble())).toFloat().coerceIn(-75f, 75f)
            } else {
                0f
            }

            // Cue C: Ear visibility asymmetry (rolling onto left side exposes right ear to overhead camera)
            val lEarConf = kp.leftEar?.confidence ?: 0f
            val rEarConf = kp.rightEar?.confidence ?: 0f
            val earLeftBias = if (max(lEarConf, rEarConf) >= minConf) {
                (rEarConf - lEarConf).coerceIn(-1f, 1f)
            } else {
                0f
            }

            // Positive `leftRollScore` means rolled onto anatomical LEFT side -> maps to negative `bodyRollDeg`
            val dirComposite = noseLatRatio * 0.55f + (depthRollDeg / 55f) * 0.65f + earLeftBias * 0.40f
            val rollSign = when {
                abs(dirComposite) >= 0.08f -> if (dirComposite > 0f) -1f else 1f
                noseLatRatio != 0f -> if (noseLatRatio > 0f) -1f else 1f
                else -> 1f
            }

            val geomMagnitude = (foreshorten * 62f + abs(noseLatRatio) * 30f + abs(earLeftBias) * 16f).coerceIn(0f, 85f)
            val fusedMagnitude = if (has3DDepth) {
                (geomMagnitude * 0.58f + abs(depthRollDeg) * 0.42f).coerceIn(0f, 88f)
            } else {
                geomMagnitude
            }

            val uncalibratedRoll = if (foreshorten < 0.18f && abs(noseLatRatio) < 0.16f && abs(depthRollDeg) < 12f) {
                // Near-flat supine zone: smooth linear response without jump discontinuity
                (-noseLatRatio * 24f - depthRollDeg * 0.35f).coerceIn(-20f, 20f)
            } else {
                rollSign * fusedMagnitude
            }

            bodyRoll = (uncalibratedRoll - baselineRollOffsetDeg).coerceIn(-90f, 90f)
        }

        return PoseAngleProfile(
            leftElbowDeg = lElbow,
            rightElbowDeg = rElbow,
            leftShoulderDeg = lShoulder,
            rightShoulderDeg = rShoulder,
            leftKneeDeg = lKnee,
            rightKneeDeg = rKnee,
            leftHipDeg = lHip,
            rightHipDeg = rHip,
            torsoTiltDeg = torsoTilt,
            bodyRollDeg = bodyRoll,
            torsoChirality = chirality
        )
    }

    /**
     * Compares two [PoseAngleProfile]s and returns the maximum single-joint angle change
     * and weighted mean joint angle change (ignoring pixel translations/camera jitter!).
     */
    fun computeAngleDelta(prev: PoseAngleProfile?, curr: PoseAngleProfile?): AngleDeltaSummary {
        if (prev == null || curr == null) {
            return AngleDeltaSummary(0f, 0f, "", 0)
        }

        data class JointPair(val name: String, val a: Float?, val b: Float?, val weight: Float)

        val pairs = listOf(
            JointPair("左肘弯曲角", prev.leftElbowDeg, curr.leftElbowDeg, 1.25f),
            JointPair("右肘弯曲角", prev.rightElbowDeg, curr.rightElbowDeg, 1.25f),
            JointPair("左肩抬臂角", prev.leftShoulderDeg, curr.leftShoulderDeg, 1.15f),
            JointPair("右肩抬臂角", prev.rightShoulderDeg, curr.rightShoulderDeg, 1.15f),
            JointPair("左膝屈伸角", prev.leftKneeDeg, curr.leftKneeDeg, 1.15f),
            JointPair("右膝屈伸角", prev.rightKneeDeg, curr.rightKneeDeg, 1.15f),
            JointPair("左髋折角", prev.leftHipDeg, curr.leftHipDeg, 1.0f),
            JointPair("右髋折角", prev.rightHipDeg, curr.rightHipDeg, 1.0f),
            JointPair("躯干侧翻角", prev.bodyRollDeg, curr.bodyRollDeg, 1.2f),
            JointPair("躯干倾角", prev.torsoTiltDeg, curr.torsoTiltDeg, 0.85f)
        )

        var maxDelta = 0f
        var dominantName = ""
        var weightedSum = 0f
        var weightTotal = 0f
        var count = 0

        for (p in pairs) {
            if (p.a != null && p.b != null) {
                val diff = abs(p.b - p.a)
                if (diff > maxDelta) {
                    maxDelta = diff
                    dominantName = p.name
                }
                weightedSum += diff * p.weight
                weightTotal += p.weight
                count++
            }
        }

        val mean = if (weightTotal > 0f) weightedSum / weightTotal else 0f
        return AngleDeltaSummary(
            maxDeltaDeg = maxDelta,
            weightedMeanDeltaDeg = mean,
            dominantJointName = dominantName,
            comparedJointCount = count
        )
    }

    /**
     * Velocity-adaptive One-Euro style keypoint filter:
     * Heavily suppresses sub-pixel/1-3px ML landmark jitter when the baby is still,
     * while immediately opening bandwidth (`alpha -> 0.92`) during fast kicks or arm waves.
     */
    fun smoothKeypoints(
        prev: PoseKeypoints?,
        curr: PoseKeypoints,
        alpha: Float = 0.65f,
        adaptive: Boolean = true
    ): PoseKeypoints {
        if (prev == null) return curr
        val baseMinAlpha = alpha.coerceIn(0.12f, 1.0f)

        fun blend(oldPt: NormalizedPoint?, newPt: NormalizedPoint?): NormalizedPoint? {
            if (newPt == null) return null
            if (oldPt == null || oldPt.confidence < 0.20f) return newPt
            val effAlpha = if (adaptive) {
                val speed = hypot(newPt.x - oldPt.x, newPt.y - oldPt.y)
                val dynamicBoost = ((speed - 0.006f).coerceAtLeast(0f) / 0.038f).coerceIn(0f, 1f)
                val rawA = (baseMinAlpha * 0.55f) + dynamicBoost * (0.94f - baseMinAlpha * 0.55f)
                (rawA * (0.65f + 0.35f * newPt.confidence)).coerceIn(0.15f, 0.96f)
            } else {
                baseMinAlpha
            }
            return NormalizedPoint(
                x = oldPt.x * (1f - effAlpha) + newPt.x * effAlpha,
                y = oldPt.y * (1f - effAlpha) + newPt.y * effAlpha,
                confidence = newPt.confidence,
                z = oldPt.z * (1f - effAlpha) + newPt.z * effAlpha
            )
        }

        return PoseKeypoints(
            nose = blend(prev.nose, curr.nose),
            leftShoulder = blend(prev.leftShoulder, curr.leftShoulder),
            rightShoulder = blend(prev.rightShoulder, curr.rightShoulder),
            leftElbow = blend(prev.leftElbow, curr.leftElbow),
            rightElbow = blend(prev.rightElbow, curr.rightElbow),
            leftWrist = blend(prev.leftWrist, curr.leftWrist),
            rightWrist = blend(prev.rightWrist, curr.rightWrist),
            leftHip = blend(prev.leftHip, curr.leftHip),
            rightHip = blend(prev.rightHip, curr.rightHip),
            leftKnee = blend(prev.leftKnee, curr.leftKnee),
            rightKnee = blend(prev.rightKnee, curr.rightKnee),
            leftAnkle = blend(prev.leftAnkle, curr.leftAnkle),
            rightAnkle = blend(prev.rightAnkle, curr.rightAnkle),
            leftEye = blend(prev.leftEye, curr.leftEye),
            rightEye = blend(prev.rightEye, curr.rightEye),
            leftEar = blend(prev.leftEar, curr.leftEar),
            rightEar = blend(prev.rightEar, curr.rightEar)
        )
    }
}

data class PoseFrameResult(
    val targetDetected: Boolean,
    val targetBox: NormalizedRect?,
    val poseLabel: BabyPoseLabel,
    val keypoints: PoseKeypoints?,
    val angleProfile: PoseAngleProfile,
    val maxAngleDeltaDeg: Float,
    val confidence: Float,
    val sourceTag: String,
    val modelName: String = "",
    val inferenceTimeMs: Long = 0L,
    val landmarkCount: Int = 0
)

data class WakeDecisionSnapshot(
    val currentPose: BabyPoseLabel,
    val targetDetected: Boolean,
    val angleProfile: PoseAngleProfile,
    val lastAngleDeltaDeg: Float,
    val angleThresholdDeg: Float,
    val changeCount: Int,
    val changeThreshold: Int,
    val windowSec: Int,
    val justTriggeredWake: Boolean,
    val isAlarmRinging: Boolean,
    val snoozedRemainingSec: Int,
    val lastWakeTimestampMs: Long,
    val recentPoseHistory: List<String>,
    val confidence: Float = 0f,
    val modelName: String = "",
    val sourceTag: String = "",
    val inferenceTimeMs: Long = 0L,
    val landmarkCount: Int = 0,
    val minConfidenceThreshold: Float = 0.45f,
    val rollThresholdDeg: Float = 28f,
    val baselineRollOffsetDeg: Float = 0f
)

object ZoomRoiCropper {
    const val MIN_ZOOM = 1.0f
    const val MAX_ZOOM = 8.0f

    fun clampZoom(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    fun computeCropRect(
        srcWidth: Int,
        srcHeight: Int,
        zoomRatio: Float,
        panX: Float = 0f,
        panY: Float = 0f
    ): IntCropRect {
        if (srcWidth <= 1 || srcHeight <= 1) {
            return IntCropRect(0, 0, max(1, srcWidth), max(1, srcHeight))
        }
        val z = clampZoom(zoomRatio)
        if (z <= 1.001f) {
            return IntCropRect(0, 0, srcWidth, srcHeight)
        }
        val cropW = (srcWidth / z).roundToInt().coerceIn(16.coerceAtMost(srcWidth), srcWidth)
        val cropH = (srcHeight / z).roundToInt().coerceIn(16.coerceAtMost(srcHeight), srcHeight)

        val maxOffsetX = (srcWidth - cropW) * 0.5f
        val maxOffsetY = (srcHeight - cropH) * 0.5f
        val cx = srcWidth * 0.5f + panX.coerceIn(-1f, 1f) * maxOffsetX
        val cy = srcHeight * 0.5f + panY.coerceIn(-1f, 1f) * maxOffsetY

        val left = (cx - cropW * 0.5f).roundToInt().coerceIn(0, srcWidth - cropW)
        val top = (cy - cropH * 0.5f).roundToInt().coerceIn(0, srcHeight - cropH)
        return IntCropRect(left, top, cropW, cropH)
    }

    fun cropAndScaleBitmap(
        source: Bitmap,
        zoomRatio: Float,
        panX: Float,
        panY: Float,
        targetMaxDim: Int = 720
    ): Bitmap {
        val rect = computeCropRect(source.width, source.height, zoomRatio, panX, panY)
        val cropped = if (rect.left == 0 && rect.top == 0 && rect.width == source.width && rect.height == source.height) {
            source
        } else {
            Bitmap.createBitmap(source, rect.left, rect.top, rect.width, rect.height)
        }
        val maxSide = max(cropped.width, cropped.height).coerceAtLeast(1)
        if (maxSide <= targetMaxDim && cropped !== source) {
            return cropped
        }
        val scale = targetMaxDim.toFloat() / maxSide.toFloat()
        val outW = (cropped.width * scale).roundToInt().coerceAtLeast(32)
        val outH = (cropped.height * scale).roundToInt().coerceAtLeast(32)
        val scaled = Bitmap.createScaledBitmap(cropped, outW, outH, true)
        if (cropped !== source && cropped !== scaled) {
            cropped.recycle()
        }
        return scaled
    }
}

/**
 * Optional Pure-Kotlin contour & principal-axis geometric fallback analyzer.
 * Disabled by default in [PoseDetectionConfig] so warm-colored blankets or wooden cribs
 * never generate hallucinated poses unless the user explicitly enables fallback mode.
 */
class LightweightVisionPoseAnalyzer(
    private val gridWidth: Int = 96,
    private val gridHeight: Int = 96
) {
    private var smoothedBox: NormalizedRect? = null
    private var prevProfile: PoseAngleProfile? = null

    fun reset() {
        smoothedBox = null
        prevProfile = null
    }

    fun analyzeGrid(
        argbPixels: IntArray,
        width: Int = gridWidth,
        height: Int = gridHeight,
        config: PoseDetectionConfig = PoseDetectionConfig.PRESET_BALANCED
    ): PoseFrameResult {
        val total = width * height
        if (argbPixels.size < total || width < 8 || height < 8) {
            return emptyResult()
        }

        val luma = FloatArray(total)
        val warmth = FloatArray(total)
        var meanLuma = 0f

        for (i in 0 until total) {
            val px = argbPixels[i]
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            luma[i] = y
            meanLuma += y
            warmth[i] = if (r > g && g > b * 0.85f && (r - b) > 0.08f) {
                ((r - b) * 1.4f + (r - g) * 0.6f).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
        meanLuma /= total.toFloat()

        var weightSum = 0f
        var sumX = 0f
        var sumY = 0f
        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0

        for (y in 1 until height - 1) {
            val ro = y * width
            for (x in 1 until width - 1) {
                val idx = ro + x
                val edge = (abs(luma[idx] - luma[idx - 1]) + abs(luma[idx] - luma[idx - width])) * 0.5f
                val contrast = abs(luma[idx] - meanLuma)
                val centerBias = 1f - 0.30f * hypot(
                    (x.toFloat() / width - 0.5f) * 1.3f,
                    (y.toFloat() / height - 0.5f) * 1.3f
                ).coerceIn(0f, 1f)

                val sal = if (contrast < 0.035f && edge < 0.025f) {
                    0f
                } else {
                    (warmth[idx] * 1.35f + edge * 0.95f + contrast * 0.45f) * centerBias
                }
                if (sal > 0.24f) {
                    weightSum += sal
                    sumX += x * sal
                    sumY += y * sal
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        // Reject both empty frames and uniform whole-frame warm blankets (> 85% coverage)
        val boxAreaRatio = if (maxX > minX && maxY > minY) {
            ((maxX - minX) * (maxY - minY)).toFloat() / total.toFloat()
        } else 0f
        val hasSubject = weightSum > (total * 0.012f) && maxX > minX + 4 && maxY > minY + 4 && boxAreaRatio < 0.92f
        if (!hasSubject) {
            return emptyResult()
        }

        val meanX = sumX / weightSum
        val meanY = sumY / weightSum

        var covXX = 0f
        var covYY = 0f
        var covXY = 0f
        var leftUpperSpan = 0f
        var rightUpperSpan = 0f
        var leftLowerSpan = 0f
        var rightLowerSpan = 0f
        var leftW = 0.001f
        var rightW = 0.001f

        for (y in minY..maxY) {
            val ro = y * width
            for (x in minX..maxX) {
                val idx = ro + x
                val w = warmth[idx] * 1.4f + abs(luma[idx] - meanLuma) * 0.4f
                if (w < 0.12f) continue
                val dx = x - meanX
                val dy = y - meanY
                covXX += w * dx * dx
                covYY += w * dy * dy
                covXY += w * dx * dy

                if (x < meanX) leftW += w else rightW += w
                if (y <= meanY) {
                    if (x < meanX) leftUpperSpan = max(leftUpperSpan, abs(dx))
                    else rightUpperSpan = max(rightUpperSpan, abs(dx))
                } else {
                    if (x < meanX) leftLowerSpan = max(leftLowerSpan, abs(dx))
                    else rightLowerSpan = max(rightLowerSpan, abs(dx))
                }
            }
        }

        val principalRad = 0.5 * atan2((2f * covXY).toDouble(), (covYY - covXX).toDouble())
        val torsoTiltDeg = abs(Math.toDegrees(principalRad)).toFloat().coerceIn(0f, 90f)

        val halfBoxW = max(4f, (maxX - minX) * 0.5f)
        val lrAsym = ((rightW - leftW) / (leftW + rightW)).coerceIn(-1f, 1f)
        val bodyRollDeg = ((lrAsym * 75f) - config.baselineRollOffsetDeg).coerceIn(-80f, 80f)

        val lArmSpreadRatio = (leftUpperSpan / halfBoxW).coerceIn(0.2f, 1.4f)
        val rArmSpreadRatio = (rightUpperSpan / halfBoxW).coerceIn(0.2f, 1.4f)
        val lLegSpreadRatio = (leftLowerSpan / halfBoxW).coerceIn(0.2f, 1.4f)
        val rLegSpreadRatio = (rightLowerSpan / halfBoxW).coerceIn(0.2f, 1.4f)

        val profile = PoseAngleProfile(
            leftElbowDeg = (165f - abs(lArmSpreadRatio - 0.65f) * 95f).coerceIn(55f, 170f),
            rightElbowDeg = (165f - abs(rArmSpreadRatio - 0.65f) * 95f).coerceIn(55f, 170f),
            leftShoulderDeg = (25f + lArmSpreadRatio * 75f).coerceIn(20f, 155f),
            rightShoulderDeg = (25f + rArmSpreadRatio * 75f).coerceIn(20f, 155f),
            leftKneeDeg = (165f - lLegSpreadRatio * 55f).coerceIn(70f, 170f),
            rightKneeDeg = (165f - rLegSpreadRatio * 55f).coerceIn(70f, 170f),
            leftHipDeg = 150f,
            rightHipDeg = 150f,
            torsoTiltDeg = torsoTiltDeg,
            bodyRollDeg = bodyRollDeg,
            torsoChirality = null
        )

        val deltaSummary = PoseGeometryMath.computeAngleDelta(prevProfile, profile)
        prevProfile = profile

        val rawBox = NormalizedRect(
            left = (minX.toFloat() / width).coerceIn(0.03f, 0.85f),
            top = (minY.toFloat() / height).coerceIn(0.03f, 0.85f),
            right = (maxX.toFloat() / width).coerceIn(0.15f, 0.97f),
            bottom = (maxY.toFloat() / height).coerceIn(0.15f, 0.97f)
        )
        val box = smoothedBox?.let { p ->
            NormalizedRect(
                left = p.left * 0.6f + rawBox.left * 0.4f,
                top = p.top * 0.6f + rawBox.top * 0.4f,
                right = p.right * 0.6f + rawBox.right * 0.4f,
                bottom = p.bottom * 0.6f + rawBox.bottom * 0.4f
            )
        } ?: rawBox
        smoothedBox = box

        val poseLabel = MlKitPoseClassifier.classifyFromAngleProfile(
            angles = profile,
            keypoints = null,
            prevProfileDelta = deltaSummary,
            config = config
        )

        return PoseFrameResult(
            targetDetected = true,
            targetBox = box,
            poseLabel = poseLabel,
            keypoints = null,
            angleProfile = profile,
            maxAngleDeltaDeg = deltaSummary.maxDeltaDeg,
            confidence = 0.48f,
            sourceTag = "contour-fallback",
            modelName = "几何轮廓兜底 (非骨架模型)",
            landmarkCount = 0
        )
    }

    private fun emptyResult() = PoseFrameResult(
        targetDetected = false,
        targetBox = null,
        poseLabel = BabyPoseLabel.NONE,
        keypoints = null,
        angleProfile = PoseAngleProfile.EMPTY,
        maxAngleDeltaDeg = 0f,
        confidence = 0f,
        sourceTag = "none",
        modelName = "",
        landmarkCount = 0
    )
}

/**
 * 3D/2D Rotation-Invariant Biomechanical Infant Posture Classifier.
 */
object MlKitPoseClassifier {

    fun classifyFromKeypoints(
        rawKeypoints: PoseKeypoints,
        objectBox: NormalizedRect?,
        prevSmoothedKeypoints: PoseKeypoints?,
        prevAngleProfile: PoseAngleProfile?,
        fallbackResult: PoseFrameResult,
        config: PoseDetectionConfig = PoseDetectionConfig.PRESET_BALANCED,
        modelName: String = config.engineMode.modelDisplayName,
        inferenceTimeMs: Long = 0L
    ): Pair<PoseFrameResult, PoseKeypoints?> {
        val minConf = config.minLandmarkConfidence.coerceIn(0.15f, 0.90f)
        val baseAlpha = (1f - config.smoothingStrength.coerceIn(0f, 0.90f) * 0.72f).coerceIn(0.20f, 1.0f)

        val smoothedKp = PoseGeometryMath.smoothKeypoints(
            prev = prevSmoothedKeypoints,
            curr = rawKeypoints,
            alpha = baseAlpha,
            adaptive = true
        )
        val validPoints = smoothedKp.allPoints().filter { it.confidence >= minConf }
        val angleProfile = PoseGeometryMath.computeAngles(
            kp = smoothedKp,
            minConf = minConf,
            baselineRollOffsetDeg = config.baselineRollOffsetDeg
        )

        val hasShoulders = smoothedKp.leftShoulder?.let { it.confidence >= minConf } == true &&
            smoothedKp.rightShoulder?.let { it.confidence >= minConf } == true
        val hasUpperBodyLock = hasShoulders && smoothedKp.headReferencePoint(minConf) != null &&
            angleProfile.bodyRollDeg != null

        val hasFullPoseLock = validPoints.size >= 4 && angleProfile.validJointCount() >= 2

        if (!hasFullPoseLock && !hasUpperBodyLock) {
            if (config.enableContourFallback && fallbackResult.targetDetected) {
                val mergedBox = objectBox ?: fallbackResult.targetBox
                return fallbackResult.copy(
                    targetBox = mergedBox,
                    inferenceTimeMs = inferenceTimeMs
                ) to prevSmoothedKeypoints
            }
            val emptyRes = PoseFrameResult(
                targetDetected = false,
                targetBox = objectBox,
                poseLabel = BabyPoseLabel.NONE,
                keypoints = null,
                angleProfile = PoseAngleProfile.EMPTY,
                maxAngleDeltaDeg = 0f,
                confidence = 0f,
                sourceTag = "no-skeleton",
                modelName = modelName,
                inferenceTimeMs = inferenceTimeMs,
                landmarkCount = validPoints.size
            )
            return emptyRes to prevSmoothedKeypoints
        }

        val minX = (validPoints.minOf { it.x } - 0.06f).coerceIn(0.01f, 0.90f)
        val maxX = (validPoints.maxOf { it.x } + 0.06f).coerceIn(minX + 0.08f, 0.99f)
        val minY = (validPoints.minOf { it.y } - 0.06f).coerceIn(0.01f, 0.90f)
        val maxY = (validPoints.maxOf { it.y } + 0.06f).coerceIn(minY + 0.08f, 0.99f)
        val skeletonBox = NormalizedRect(minX, minY, maxX, maxY)
        // Prefer tight skeleton box over overly coarse ML Kit generic object box when available
        val derivedBox = if (validPoints.size >= 5) skeletonBox else (objectBox ?: skeletonBox)

        val deltaSummary = PoseGeometryMath.computeAngleDelta(prevAngleProfile, angleProfile)
        val poseLabel = classifyFromAngleProfile(angleProfile, smoothedKp, deltaSummary, config)
        val avgConf = validPoints.map { it.confidence }.average().toFloat().coerceIn(0.20f, 0.99f)
        val tag = if (hasFullPoseLock) "mlkit-3d-pose" else "mlkit-upper-pose"

        val res = PoseFrameResult(
            targetDetected = true,
            targetBox = derivedBox,
            poseLabel = poseLabel,
            keypoints = smoothedKp,
            angleProfile = angleProfile,
            maxAngleDeltaDeg = deltaSummary.maxDeltaDeg,
            confidence = avgConf,
            sourceTag = tag,
            modelName = modelName,
            inferenceTimeMs = inferenceTimeMs,
            landmarkCount = validPoints.size
        )
        return res to smoothedKp
    }

    /**
     * Rotation-invariant biomechanical rule engine mapping [PoseAngleProfile] -> [BabyPoseLabel].
     */
    fun classifyFromAngleProfile(
        angles: PoseAngleProfile,
        keypoints: PoseKeypoints?,
        prevProfileDelta: AngleDeltaSummary,
        config: PoseDetectionConfig = PoseDetectionConfig.PRESET_BALANCED
    ): BabyPoseLabel {
        val minConf = config.minLandmarkConfidence.coerceIn(0.15f, 0.90f)
        val rollThresh = config.rollThresholdDeg.coerceIn(15f, 55f)
        val limbJumpThresh = config.limbDeltaThresholdDeg.coerceIn(12f, 55f)

        val lElbow = angles.leftElbowDeg
        val rElbow = angles.rightElbowDeg
        val lShoulder = angles.leftShoulderDeg
        val rShoulder = angles.rightShoulderDeg
        val lKnee = angles.leftKneeDeg
        val rKnee = angles.rightKneeDeg
        val lHip = angles.leftHipDeg
        val rHip = angles.rightHipDeg
        val roll = angles.bodyRollDeg ?: 0f
        val chirality = angles.torsoChirality

        // 1. Check Prone / Stomach Sleeping (趴卧/俯睡):
        //    a) Rotation-invariant negative shoulder-torso cross-product chirality (< -0.24) means back of shoulders faces camera!
        //    b) OR symmetric acute forearm propping with face occluded/turned down.
        val backFacingCamera = chirality != null && chirality <= -0.24f
        val noseConf = keypoints?.nose?.confidence ?: 0f
        val eyesConf = max(keypoints?.leftEye?.confidence ?: 0f, keypoints?.rightEye?.confidence ?: 0f)
        val faceOccluded = keypoints != null && noseConf < minConf * 0.75f && eyesConf < minConf * 0.75f
        val bothElbowsPropped = lElbow != null && rElbow != null &&
            lElbow in 42f..96f && rElbow in 42f..96f &&
            (lShoulder ?: 80f) in 62f..112f && (rShoulder ?: 80f) in 62f..112f &&
            abs(lElbow - rElbow) < 18f &&
            (chirality == null || chirality < 0.25f || faceOccluded)

        if (backFacingCamera || bothElbowsPropped) {
            return BabyPoseLabel.PRONE_HEAD_UP
        }

        // 2. Check Sitting / Pushing Up: acute/right hip flexion angle (~55°..108°) + supporting arms + upright torso
        val avgHip = listOfNotNull(lHip, rHip).takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val armsSupporting = (lElbow ?: 150f) > 132f && (rElbow ?: 150f) > 132f &&
            (lShoulder ?: 40f) < 62f && (rShoulder ?: 40f) < 62f
        val ls = keypoints?.leftShoulder
        val rs = keypoints?.rightShoulder
        val headAboveShoulders = if (keypoints?.nose != null && ls != null && rs != null) {
            keypoints.nose.y < min(ls.y, rs.y) - 0.04f
        } else {
            true
        }
        if (avgHip != null && avgHip in 55f..108f && armsSupporting && headAboveShoulders && abs(roll) < rollThresh) {
            return BabyPoseLabel.SITTING_AWAKE
        }

        // 3. Check Limb Active (Arm Bend / Raising or Leg Kick):
        val armHighAndBent = (lShoulder != null && lShoulder >= 115f && (lElbow ?: 180f) <= 95f) ||
            (rShoulder != null && rShoulder >= 115f && (rElbow ?: 180f) <= 95f)
        val strongElbowAsymmetry = lElbow != null && rElbow != null && abs(lElbow - rElbow) >= 52f &&
            ((lShoulder ?: 0f) >= 85f || (rShoulder ?: 0f) >= 85f)
        val differentialLegKick = lKnee != null && rKnee != null && abs(lKnee - rKnee) >= 42f
        val largeJointRotation = prevProfileDelta.maxDeltaDeg >= limbJumpThresh &&
            prevProfileDelta.weightedMeanDeltaDeg >= (limbJumpThresh * 0.48f).coerceAtLeast(11f)

        if (armHighAndBent || strongElbowAsymmetry || differentialLegKick || largeJointRotation) {
            return BabyPoseLabel.LIMB_ACTIVE
        }

        // 4. Check Left / Right Side Roll via calibrated body roll angle
        if (roll <= -rollThresh) {
            return BabyPoseLabel.SIDE_LEFT
        }
        if (roll >= rollThresh) {
            return BabyPoseLabel.SIDE_RIGHT
        }

        // 5. Default relaxed lying posture
        return BabyPoseLabel.SUPINE_CALM
    }
}

/**
 * Angle-change-driven Wake-Up Tracker with multi-frame hysteresis confirmation:
 * Suppresses 1-frame classification glitches while counting genuine large biomechanical joint movements.
 */
class BabyWakeUpTracker(
    var changeThreshold: Int = DEFAULT_CHANGE_THRESHOLD,
    var windowMs: Long = DEFAULT_WINDOW_MS,
    var minChangeIntervalMs: Long = DEFAULT_MIN_CHANGE_INTERVAL_MS,
    var angleThresholdDeg: Float = DEFAULT_ANGLE_THRESHOLD_DEG,
    var confirmFrames: Int = DEFAULT_CONFIRM_FRAMES
) {
    companion object {
        const val DEFAULT_CHANGE_THRESHOLD = 4
        const val DEFAULT_WINDOW_MS = 30_000L
        const val DEFAULT_MIN_CHANGE_INTERVAL_MS = 1_500L
        const val DEFAULT_ANGLE_THRESHOLD_DEG = 22f
        const val DEFAULT_CONFIRM_FRAMES = 2
        const val DEFAULT_SNOOZE_MS = 3 * 60_000L
    }

    private val changeTimestamps = ArrayDeque<Long>()
    private val recentPoseLabels = ArrayDeque<String>()
    private var anchorPose: BabyPoseLabel = BabyPoseLabel.NONE
    private var anchorAngles: PoseAngleProfile? = null
    private var lastChangeRecordedMs: Long = 0L

    // Multi-frame temporal confirmation state
    private var candidatePose: BabyPoseLabel = BabyPoseLabel.NONE
    private var candidateStreak: Int = 0
    private var missingFrameStreak: Int = 0

    var isAlarmRinging: Boolean = false
        private set
    var snoozeUntilMs: Long = 0L
        private set
    var lastWakeTimestampMs: Long = 0L
        private set
    var latestPose: BabyPoseLabel = BabyPoseLabel.NONE
        private set
    var latestTargetDetected: Boolean = false
        private set
    var latestAngleProfile: PoseAngleProfile = PoseAngleProfile.EMPTY
        private set
    var latestAngleDeltaDeg: Float = 0f
        private set
    var latestConfidence: Float = 0f
        private set
    var latestModelName: String = ""
        private set
    var latestSourceTag: String = ""
        private set
    var latestInferenceTimeMs: Long = 0L
        private set
    var latestLandmarkCount: Int = 0
        private set
    var minConfidenceThreshold: Float = 0.45f
    var rollThresholdDeg: Float = 28f
    var baselineRollOffsetDeg: Float = 0f

    @Synchronized
    fun applyConfig(config: PoseDetectionConfig) {
        changeThreshold = config.changeThreshold.coerceIn(1, 15)
        windowMs = config.windowSec.coerceIn(10, 180) * 1000L
        minChangeIntervalMs = config.minChangeIntervalMs.coerceIn(400L, 8_000L)
        angleThresholdDeg = config.angleThresholdDeg.coerceIn(8f, 60f)
        confirmFrames = config.confirmFrames.coerceIn(1, 6)
        minConfidenceThreshold = config.minLandmarkConfidence
        rollThresholdDeg = config.rollThresholdDeg
        baselineRollOffsetDeg = config.baselineRollOffsetDeg
        latestModelName = config.engineMode.modelDisplayName
    }

    @Synchronized
    fun reset() {
        changeTimestamps.clear()
        recentPoseLabels.clear()
        anchorPose = BabyPoseLabel.NONE
        anchorAngles = null
        lastChangeRecordedMs = 0L
        candidatePose = BabyPoseLabel.NONE
        candidateStreak = 0
        missingFrameStreak = 0
        isAlarmRinging = false
        snoozeUntilMs = 0L
        latestPose = BabyPoseLabel.NONE
        latestTargetDetected = false
        latestAngleProfile = PoseAngleProfile.EMPTY
        latestAngleDeltaDeg = 0f
        latestConfidence = 0f
        latestLandmarkCount = 0
    }

    @Synchronized
    fun dismissAlarm(nowMs: Long = System.currentTimeMillis()) {
        isAlarmRinging = false
        changeTimestamps.clear()
        snoozeUntilMs = max(snoozeUntilMs, nowMs + 5_000L)
    }

    @Synchronized
    fun snoozeAlarm(durationMs: Long = DEFAULT_SNOOZE_MS, nowMs: Long = System.currentTimeMillis()) {
        isAlarmRinging = false
        changeTimestamps.clear()
        snoozeUntilMs = nowMs + durationMs
    }

    @Synchronized
    fun forceTriggerAlarmForTest(nowMs: Long = System.currentTimeMillis()): WakeDecisionSnapshot {
        snoozeUntilMs = 0L
        changeTimestamps.clear()
        for (i in 0 until changeThreshold) {
            changeTimestamps.addLast(nowMs - (changeThreshold - 1 - i) * 500L)
        }
        if (latestPose == BabyPoseLabel.NONE) {
            latestPose = BabyPoseLabel.LIMB_ACTIVE
            latestTargetDetected = true
            latestAngleProfile = PoseAngleProfile.presetFor(BabyPoseLabel.LIMB_ACTIVE)
            latestAngleDeltaDeg = 68f
            latestConfidence = 0.95f
        }
        isAlarmRinging = true
        lastWakeTimestampMs = nowMs
        pushHistory("测试唤醒触发 (Δ68°)")
        return buildSnapshot(nowMs, justTriggered = true)
    }

    @Synchronized
    fun simulateSinglePoseStep(nowMs: Long = System.currentTimeMillis()): WakeDecisionSnapshot {
        val cycle = listOf(
            BabyPoseLabel.SUPINE_CALM,
            BabyPoseLabel.SIDE_LEFT,
            BabyPoseLabel.LIMB_ACTIVE,
            BabyPoseLabel.SIDE_RIGHT,
            BabyPoseLabel.PRONE_HEAD_UP,
            BabyPoseLabel.SITTING_AWAKE
        )
        val nextIdx = (cycle.indexOf(latestPose) + 1) % cycle.size
        val nextPose = cycle[nextIdx]
        val nextAngles = PoseAngleProfile.presetFor(nextPose)
        val delta = PoseGeometryMath.computeAngleDelta(anchorAngles, nextAngles)
        val synthetic = PoseFrameResult(
            targetDetected = true,
            targetBox = NormalizedRect(0.24f, 0.18f, 0.76f, 0.84f),
            poseLabel = nextPose,
            keypoints = null,
            angleProfile = nextAngles,
            maxAngleDeltaDeg = delta.maxDeltaDeg,
            confidence = 0.95f,
            sourceTag = "sim-angle",
            modelName = latestModelName.ifBlank { "预设模拟序列" },
            landmarkCount = 13
        )
        // Simulation steps bypass multi-frame streak wait so 1 tap = 1 immediate posture step
        val savedConfirm = confirmFrames
        confirmFrames = 1
        lastChangeRecordedMs = min(lastChangeRecordedMs, nowMs - minChangeIntervalMs - 10L)
        val snap = onFrame(synthetic, nowMs)
        confirmFrames = savedConfirm
        return snap
    }

    @Synchronized
    fun onFrame(result: PoseFrameResult, nowMs: Long = System.currentTimeMillis()): WakeDecisionSnapshot {
        pruneExpired(nowMs)
        if (result.modelName.isNotBlank()) latestModelName = result.modelName
        latestSourceTag = result.sourceTag
        latestInferenceTimeMs = result.inferenceTimeMs
        latestLandmarkCount = result.landmarkCount

        val hasUsableAngles = result.angleProfile.validJointCount() >= 2 || result.angleProfile.bodyRollDeg != null
        if (!result.targetDetected || result.poseLabel == BabyPoseLabel.NONE || !hasUsableAngles) {
            missingFrameStreak++
            if (missingFrameStreak >= 3) {
                latestTargetDetected = false
                latestPose = BabyPoseLabel.NONE
                latestConfidence = 0f
                candidatePose = BabyPoseLabel.NONE
                candidateStreak = 0
            }
            return buildSnapshot(nowMs, justTriggered = false)
        }

        missingFrameStreak = 0
        latestTargetDetected = true
        latestConfidence = result.confidence
        latestAngleProfile = result.angleProfile

        // Multi-frame temporal confirmation for discrete pose label stability
        val reqConfirm = if (result.sourceTag == "test-angle" || result.sourceTag == "sim-angle") {
            1
        } else {
            confirmFrames.coerceIn(1, 6)
        }

        if (result.poseLabel == candidatePose) {
            candidateStreak++
        } else {
            candidatePose = result.poseLabel
            candidateStreak = 1
        }

        val effectivePose = if (latestPose == BabyPoseLabel.NONE || candidateStreak >= reqConfirm) {
            result.poseLabel
        } else {
            latestPose
        }
        latestPose = effectivePose

        val prevAnchor = anchorAngles
        if (anchorPose == BabyPoseLabel.NONE || prevAnchor == null) {
            anchorPose = effectivePose
            anchorAngles = result.angleProfile
            latestAngleDeltaDeg = 0f
            return buildSnapshot(nowMs, justTriggered = false)
        }

        // Compare current joint angles against the last stable anchor pose's joint angles
        val deltaVsAnchor = PoseGeometryMath.computeAngleDelta(prevAnchor, result.angleProfile)
        latestAngleDeltaDeg = deltaVsAnchor.maxDeltaDeg

        val elapsedSinceChange = nowMs - lastChangeRecordedMs
        val minSingleJointDeg = angleThresholdDeg.coerceIn(8f, 60f)
        val minWeightedMeanDeg = (minSingleJointDeg * 0.58f).coerceAtLeast(6f)

        val hasEnoughComparedJoints = deltaVsAnchor.comparedJointCount >= 2 ||
            (deltaVsAnchor.comparedJointCount == 1 && result.angleProfile.bodyRollDeg != null)

        val hasLargeJointAngleChange = hasEnoughComparedJoints && (
            deltaVsAnchor.maxDeltaDeg >= minSingleJointDeg ||
                deltaVsAnchor.weightedMeanDeltaDeg >= minWeightedMeanDeg
            )
        val hasVerifiedPoseSwitch = effectivePose != anchorPose &&
            deltaVsAnchor.maxDeltaDeg >= (minSingleJointDeg * 0.65f).coerceAtLeast(10f)

        if (candidateStreak >= reqConfirm &&
            elapsedSinceChange >= minChangeIntervalMs &&
            (hasLargeJointAngleChange || hasVerifiedPoseSwitch)
        ) {
            changeTimestamps.addLast(nowMs)
            lastChangeRecordedMs = nowMs

            val degInt = deltaVsAnchor.maxDeltaDeg.roundToInt()
            val jointInfo = if (deltaVsAnchor.dominantJointName.isNotBlank()) {
                "${deltaVsAnchor.dominantJointName} Δ${degInt}°"
            } else {
                "Δ${degInt}°"
            }
            val historyEntry = if (effectivePose != anchorPose) {
                "${anchorPose.zhLabel}→${effectivePose.zhLabel} ($jointInfo)"
            } else {
                "${effectivePose.zhLabel} ($jointInfo)"
            }
            pushHistory(historyEntry)

            anchorPose = effectivePose
            anchorAngles = result.angleProfile
        }

        val isSnoozed = nowMs < snoozeUntilMs
        var justTriggered = false
        if (!isSnoozed && !isAlarmRinging && changeTimestamps.size >= changeThreshold) {
            isAlarmRinging = true
            justTriggered = true
            lastWakeTimestampMs = nowMs
        }

        return buildSnapshot(nowMs, justTriggered)
    }

    @Synchronized
    fun currentSnapshot(nowMs: Long = System.currentTimeMillis()): WakeDecisionSnapshot {
        pruneExpired(nowMs)
        return buildSnapshot(nowMs, justTriggered = false)
    }

    private fun pruneExpired(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (changeTimestamps.isNotEmpty() && changeTimestamps.first() < cutoff) {
            changeTimestamps.removeFirst()
        }
    }

    private fun pushHistory(entry: String) {
        recentPoseLabels.addFirst(entry)
        while (recentPoseLabels.size > 6) {
            recentPoseLabels.removeLast()
        }
    }

    private fun buildSnapshot(nowMs: Long, justTriggered: Boolean): WakeDecisionSnapshot {
        val snoozedSec = if (nowMs < snoozeUntilMs) {
            ((snoozeUntilMs - nowMs + 999L) / 1000L).toInt()
        } else {
            0
        }
        return WakeDecisionSnapshot(
            currentPose = latestPose,
            targetDetected = latestTargetDetected,
            angleProfile = latestAngleProfile,
            lastAngleDeltaDeg = latestAngleDeltaDeg,
            angleThresholdDeg = angleThresholdDeg,
            changeCount = changeTimestamps.size,
            changeThreshold = changeThreshold,
            windowSec = (windowMs / 1000L).toInt(),
            justTriggeredWake = justTriggered,
            isAlarmRinging = isAlarmRinging,
            snoozedRemainingSec = snoozedSec,
            lastWakeTimestampMs = lastWakeTimestampMs,
            recentPoseHistory = recentPoseLabels.toList(),
            confidence = latestConfidence,
            modelName = latestModelName,
            sourceTag = latestSourceTag,
            inferenceTimeMs = latestInferenceTimeMs,
            landmarkCount = latestLandmarkCount,
            minConfidenceThreshold = minConfidenceThreshold,
            rollThresholdDeg = rollThresholdDeg,
            baselineRollOffsetDeg = baselineRollOffsetDeg
        )
    }
}
