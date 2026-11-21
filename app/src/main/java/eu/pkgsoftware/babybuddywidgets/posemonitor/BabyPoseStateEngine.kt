package eu.pkgsoftware.babybuddywidgets.posemonitor

import android.graphics.Bitmap
import java.util.Locale
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
    PRONE_HEAD_UP("prone_head_up", "趴卧屈肘抬头", "Prone / Forearm Prop"),
    LIMB_ACTIVE("limb_active", "屈臂挥动/蹬腿", "Arm Bend / Leg Kick"),
    SITTING_AWAKE("sitting_awake", "屈髋撑起/坐立", "Hip-Flexed Sitting Up");

    fun displayLabel(isZh: Boolean = true): String = if (isZh) zhLabel else enLabel
}

data class NormalizedPoint(
    val x: Float,
    val y: Float,
    val confidence: Float = 1f
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
 * 13-point infant anatomical skeleton including elbows and knees for exact joint angle calculation.
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
    val rightAnkle: NormalizedPoint?
) {
    fun allPoints(): List<NormalizedPoint> = listOfNotNull(
        nose,
        leftShoulder, rightShoulder,
        leftElbow, rightElbow,
        leftWrist, rightWrist,
        leftHip, rightHip,
        leftKnee, rightKnee,
        leftAnkle, rightAnkle
    )
}

/**
 * Biomechanical joint-angle profile (in degrees) representing the infant's body pose.
 * Null values indicate a joint whose landmarks were not visible with sufficient confidence.
 */
data class PoseAngleProfile(
    val leftElbowDeg: Float?,      // ∠左肩-左肘-左腕 (胳膊弯曲角: 170°伸直, 70°弯曲)
    val rightElbowDeg: Float?,     // ∠右肩-右肘-右腕 (胳膊弯曲角)
    val leftShoulderDeg: Float?,   // ∠左髋-左肩-左肘 (大臂外展/上举角: 25°贴身, 140°上举)
    val rightShoulderDeg: Float?,  // ∠右髋-右肩-右肘 (大臂外展/上举角)
    val leftKneeDeg: Float?,       // ∠左髋-左膝-左踝 (腿部弯曲/蹬腿角)
    val rightKneeDeg: Float?,      // ∠右髋-右膝-右踝 (腿部弯曲/蹬腿角)
    val leftHipDeg: Float?,        // ∠左肩-左髋-左膝 (髋部屈曲角: 170°平躺, 90°坐起/蜷腿)
    val rightHipDeg: Float?,       // ∠右肩-右髋-右膝 (髋部屈曲角)
    val torsoTiltDeg: Float?,      // 躯干轴线相对画面竖直方向倾角 (0°..180°)
    val bodyRollDeg: Float?        // 躯干左右侧翻等效角 (-90°左侧卧 .. 0°仰卧 .. +90°右侧卧)
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
            bodyRollDeg = null
        )

        /**
         * Preset canonical angle templates for simulation & reference verification.
         */
        fun presetFor(pose: BabyPoseLabel): PoseAngleProfile = when (pose) {
            BabyPoseLabel.NONE -> EMPTY
            // 仰卧平放：双臂微曲自然放置，双腿微屈，侧翻角 ~0°
            BabyPoseLabel.SUPINE_CALM -> PoseAngleProfile(
                leftElbowDeg = 135f, rightElbowDeg = 138f,
                leftShoulderDeg = 42f, rightShoulderDeg = 40f,
                leftKneeDeg = 142f, rightKneeDeg = 145f,
                leftHipDeg = 162f, rightHipDeg = 164f,
                torsoTiltDeg = 4f, bodyRollDeg = 0f
            )
            // 左侧翻身：右臂屈肘跨前，侧翻等效角 -52°
            BabyPoseLabel.SIDE_LEFT -> PoseAngleProfile(
                leftElbowDeg = 105f, rightElbowDeg = 72f,
                leftShoulderDeg = 55f, rightShoulderDeg = 68f,
                leftKneeDeg = 118f, rightKneeDeg = 96f,
                leftHipDeg = 132f, rightHipDeg = 124f,
                torsoTiltDeg = 14f, bodyRollDeg = -52f
            )
            // 右侧翻身：左臂屈肘跨前，侧翻等效角 +52°
            BabyPoseLabel.SIDE_RIGHT -> PoseAngleProfile(
                leftElbowDeg = 74f, rightElbowDeg = 108f,
                leftShoulderDeg = 66f, rightShoulderDeg = 52f,
                leftKneeDeg = 98f, rightKneeDeg = 120f,
                leftHipDeg = 125f, rightHipDeg = 134f,
                torsoTiltDeg = 15f, bodyRollDeg = 52f
            )
            // 趴卧屈肘抬头：双肘紧弯撑床 (~68°)，大臂前撑 (~85°)
            BabyPoseLabel.PRONE_HEAD_UP -> PoseAngleProfile(
                leftElbowDeg = 66f, rightElbowDeg = 68f,
                leftShoulderDeg = 86f, rightShoulderDeg = 84f,
                leftKneeDeg = 154f, rightKneeDeg = 150f,
                leftHipDeg = 158f, rightHipDeg = 156f,
                torsoTiltDeg = 22f, bodyRollDeg = 8f
            )
            // 屈臂挥动/蹬腿：单侧或双侧胳膊急剧弯曲上举 + 双膝一屈一伸大幅蹬腿
            BabyPoseLabel.LIMB_ACTIVE -> PoseAngleProfile(
                leftElbowDeg = 54f, rightElbowDeg = 162f,
                leftShoulderDeg = 142f, rightShoulderDeg = 98f,
                leftKneeDeg = 72f, rightKneeDeg = 168f,
                leftHipDeg = 102f, rightHipDeg = 165f,
                torsoTiltDeg = 12f, bodyRollDeg = -10f
            )
            // 屈髋撑起/坐立：髋角明显折成约 88°，双臂向下撑直 (~158°)
            BabyPoseLabel.SITTING_AWAKE -> PoseAngleProfile(
                leftElbowDeg = 156f, rightElbowDeg = 158f,
                leftShoulderDeg = 36f, rightShoulderDeg = 38f,
                leftKneeDeg = 108f, rightKneeDeg = 110f,
                leftHipDeg = 86f, rightHipDeg = 88f,
                torsoTiltDeg = 8f, bodyRollDeg = 0f
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
     * Returns null if any of the 3 points is missing or below [minConfidence],
     * or if limb segments are degenerate.
     */
    fun jointAngleDeg(
        a: NormalizedPoint?,
        vertex: NormalizedPoint?,
        c: NormalizedPoint?,
        minConfidence: Float = 0.35f
    ): Float? {
        if (a == null || vertex == null || c == null) return null
        if (a.confidence < minConfidence || vertex.confidence < minConfidence || c.confidence < minConfidence) {
            return null
        }
        val v1x = a.x - vertex.x
        val v1y = a.y - vertex.y
        val v2x = c.x - vertex.x
        val v2y = c.y - vertex.y
        val len1 = hypot(v1x, v1y)
        val len2 = hypot(v2x, v2y)
        if (len1 < 1e-4f || len2 < 1e-4f) return null
        val cosTheta = ((v1x * v2x + v1y * v2y) / (len1 * len2)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosTheta.toDouble())).toFloat()
    }

    /**
     * Computes full [PoseAngleProfile] from 13 skeletal landmarks.
     */
    fun computeAngles(kp: PoseKeypoints, minConf: Float = 0.35f): PoseAngleProfile {
        val lElbow = jointAngleDeg(kp.leftShoulder, kp.leftElbow, kp.leftWrist, minConf)
        val rElbow = jointAngleDeg(kp.rightShoulder, kp.rightElbow, kp.rightWrist, minConf)

        // Use elbow if visible, otherwise wrist as arm vector endpoint for shoulder abduction angle
        val lArmEnd = kp.leftElbow?.takeIf { it.confidence >= minConf } ?: kp.leftWrist
        val rArmEnd = kp.rightElbow?.takeIf { it.confidence >= minConf } ?: kp.rightWrist
        val lShoulder = jointAngleDeg(kp.leftHip, kp.leftShoulder, lArmEnd, minConf)
        val rShoulder = jointAngleDeg(kp.rightHip, kp.rightShoulder, rArmEnd, minConf)

        val lKnee = jointAngleDeg(kp.leftHip, kp.leftKnee, kp.leftAnkle, minConf)
        val rKnee = jointAngleDeg(kp.rightHip, kp.rightKnee, kp.rightAnkle, minConf)

        val lLegEnd = kp.leftKnee?.takeIf { it.confidence >= minConf } ?: kp.leftAnkle
        val rLegEnd = kp.rightKnee?.takeIf { it.confidence >= minConf } ?: kp.rightAnkle
        val lHip = jointAngleDeg(kp.leftShoulder, kp.leftHip, lLegEnd, minConf)
        val rHip = jointAngleDeg(kp.rightShoulder, kp.rightHip, rLegEnd, minConf)

        val ls = kp.leftShoulder?.takeIf { it.confidence >= minConf }
        val rs = kp.rightShoulder?.takeIf { it.confidence >= minConf }
        val lh = kp.leftHip?.takeIf { it.confidence >= minConf }
        val rh = kp.rightHip?.takeIf { it.confidence >= minConf }

        var torsoTilt: Float? = null
        var bodyRoll: Float? = null

        if (ls != null && rs != null && lh != null && rh != null) {
            val sMidX = (ls.x + rs.x) * 0.5f
            val sMidY = (ls.y + rs.y) * 0.5f
            val hMidX = (lh.x + rh.x) * 0.5f
            val hMidY = (lh.y + rh.y) * 0.5f
            val dx = hMidX - sMidX
            val dy = hMidY - sMidY
            val torsoLen = hypot(dx, dy).coerceAtLeast(0.04f)

            // Angle relative to vertical (0° = upright/aligned vertically in frame)
            torsoTilt = Math.toDegrees(atan2(abs(dx).toDouble(), abs(dy).toDouble())).toFloat()

            // Estimate body roll (-90°..+90°) from shoulder width foreshortening + head/nose lateral offset
            val shoulderSpan = abs(rs.x - ls.x)
            val normalSpan = torsoLen * 0.72f
            val foreshorten = (1f - (shoulderSpan / normalSpan).coerceIn(0f, 1f)).coerceIn(0f, 1f)
            val nose = kp.nose?.takeIf { it.confidence >= minConf }
            val rawSign = when {
                nose != null && abs(nose.x - sMidX) > 0.015f -> if (nose.x < sMidX) -1f else 1f
                else -> if (sMidX < hMidX) -1f else 1f
            }
            val noseRatio = if (nose != null) {
                ((nose.x - sMidX) / max(0.05f, shoulderSpan)).coerceIn(-1.2f, 1.2f)
            } else {
                0f
            }
            val rollMagnitude = (foreshorten * 68f + abs(noseRatio) * 32f).coerceIn(0f, 85f)
            bodyRoll = if (abs(noseRatio) < 0.14f && foreshorten < 0.22f) {
                noseRatio * 35f
            } else {
                rawSign * rollMagnitude
            }
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
            bodyRollDeg = bodyRoll
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
            JointPair("躯干倾角", prev.torsoTiltDeg, curr.torsoTiltDeg, 0.9f)
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
     * Exponential moving average smoothing on keypoints to suppress 1-2px landmark jitter
     * while preserving genuine joint angle rotations.
     */
    fun smoothKeypoints(prev: PoseKeypoints?, curr: PoseKeypoints, alpha: Float = 0.65f): PoseKeypoints {
        if (prev == null) return curr
        fun blend(oldPt: NormalizedPoint?, newPt: NormalizedPoint?): NormalizedPoint? {
            if (newPt == null) return null
            if (oldPt == null || oldPt.confidence < 0.25f) return newPt
            return NormalizedPoint(
                x = oldPt.x * (1f - alpha) + newPt.x * alpha,
                y = oldPt.y * (1f - alpha) + newPt.y * alpha,
                confidence = newPt.confidence
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
            rightAnkle = blend(prev.rightAnkle, curr.rightAnkle)
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
    val sourceTag: String
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
    val recentPoseHistory: List<String>
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
        targetMaxDim: Int = 480
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
 * Pure-Kotlin contour & principal-axis geometric fallback analyzer (when ML Kit skeleton is unavailable).
 * Note: Does NOT use pixel flicker / luminance jitter to trigger active poses; instead estimates
 * subject principal axis orientation (torso tilt), upper/lower contour limb spread angles, and roll asymmetry.
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

    fun analyzeGrid(argbPixels: IntArray, width: Int = gridWidth, height: Int = gridHeight): PoseFrameResult {
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
            warmth[i] = if (r > g && g > b * 0.85f && (r - b) > 0.06f) {
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

                val sal = (warmth[idx] * 1.35f + edge * 0.95f + contrast * 0.45f) * centerBias
                if (sal > 0.22f) {
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

        val hasSubject = weightSum > (total * 0.010f) && maxX > minX + 4 && maxY > minY + 4
        if (!hasSubject) {
            return emptyResult()
        }

        val meanX = sumX / weightSum
        val meanY = sumY / weightSum

        // Second-order central moments (covariance) for true geometric principal axis angle!
        var covXX = 0f
        var covYY = 0f
        var covXY = 0f
        var leftUpperSpan = 0f
        var rightUpperSpan = 0f
        var leftLowerSpan = 0f
        var rightLowerSpan = 0f
        var upperW = 0.001f
        var lowerW = 0.001f
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
                    upperW += w
                    if (x < meanX) leftUpperSpan = max(leftUpperSpan, abs(dx))
                    else rightUpperSpan = max(rightUpperSpan, abs(dx))
                } else {
                    lowerW += w
                    if (x < meanX) leftLowerSpan = max(leftLowerSpan, abs(dx))
                    else rightLowerSpan = max(rightLowerSpan, abs(dx))
                }
            }
        }

        // Principal axis orientation relative to vertical (in degrees)
        val principalRad = 0.5 * atan2((2f * covXY).toDouble(), (covYY - covXX).toDouble())
        val torsoTiltDeg = abs(Math.toDegrees(principalRad)).toFloat().coerceIn(0f, 90f)

        val halfBoxW = max(4f, (maxX - minX) * 0.5f)
        val lrAsym = ((rightW - leftW) / (leftW + rightW)).coerceIn(-1f, 1f)
        val bodyRollDeg = (lrAsym * 75f).coerceIn(-80f, 80f)

        // Geometric limb spread angles estimated from contour span ratios (immune to brightness flicker)
        val lArmSpreadRatio = (leftUpperSpan / halfBoxW).coerceIn(0.2f, 1.4f)
        val rArmSpreadRatio = (rightUpperSpan / halfBoxW).coerceIn(0.2f, 1.4f)
        val lLegSpreadRatio = (leftLowerSpan / halfBoxW).coerceIn(0.2f, 1.4f)
        val rLegSpreadRatio = (rightLowerSpan / halfBoxW).coerceIn(0.2f, 1.4f)

        val estLeftShoulderDeg = (25f + lArmSpreadRatio * 75f).coerceIn(20f, 155f)
        val estRightShoulderDeg = (25f + rArmSpreadRatio * 75f).coerceIn(20f, 155f)
        val estLeftElbowDeg = (165f - abs(lArmSpreadRatio - 0.65f) * 95f).coerceIn(55f, 170f)
        val estRightElbowDeg = (165f - abs(rArmSpreadRatio - 0.65f) * 95f).coerceIn(55f, 170f)
        val estLeftKneeDeg = (165f - lLegSpreadRatio * 55f).coerceIn(70f, 170f)
        val estRightKneeDeg = (165f - rLegSpreadRatio * 55f).coerceIn(70f, 170f)

        val profile = PoseAngleProfile(
            leftElbowDeg = estLeftElbowDeg,
            rightElbowDeg = estRightElbowDeg,
            leftShoulderDeg = estLeftShoulderDeg,
            rightShoulderDeg = estRightShoulderDeg,
            leftKneeDeg = estLeftKneeDeg,
            rightKneeDeg = estRightKneeDeg,
            leftHipDeg = 150f,
            rightHipDeg = 150f,
            torsoTiltDeg = torsoTiltDeg,
            bodyRollDeg = bodyRollDeg
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

        val poseLabel = MlKitPoseClassifier.classifyFromAngleProfile(profile, keypoints = null, prevProfileDelta = deltaSummary)

        return PoseFrameResult(
            targetDetected = true,
            targetBox = box,
            poseLabel = poseLabel,
            keypoints = null,
            angleProfile = profile,
            maxAngleDeltaDeg = deltaSummary.maxDeltaDeg,
            confidence = 0.62f,
            sourceTag = "contour-angle"
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
        sourceTag = "none"
    )
}

/**
 * Angle-driven infant posture classifier:
 * Uses joint angles (elbow bend, shoulder abduction, knee flexion, hip flexion, torso roll/tilt)
 * rather than raw pixel motion jitter.
 */
object MlKitPoseClassifier {

    fun classifyFromKeypoints(
        rawKeypoints: PoseKeypoints,
        objectBox: NormalizedRect?,
        prevSmoothedKeypoints: PoseKeypoints?,
        prevAngleProfile: PoseAngleProfile?,
        fallbackResult: PoseFrameResult
    ): Pair<PoseFrameResult, PoseKeypoints?> {
        val smoothedKp = PoseGeometryMath.smoothKeypoints(prevSmoothedKeypoints, rawKeypoints, alpha = 0.65f)
        val validPoints = smoothedKp.allPoints().filter { it.confidence >= 0.35f }
        val angleProfile = PoseGeometryMath.computeAngles(smoothedKp, minConf = 0.35f)

        if (validPoints.size < 4 || angleProfile.validJointCount() < 2) {
            return if (objectBox != null && fallbackResult.targetDetected) {
                fallbackResult.copy(targetBox = objectBox, sourceTag = "mlkit-obj+contour") to prevSmoothedKeypoints
            } else {
                fallbackResult to prevSmoothedKeypoints
            }
        }

        val minX = (validPoints.minOf { it.x } - 0.06f).coerceIn(0.01f, 0.90f)
        val maxX = (validPoints.maxOf { it.x } + 0.06f).coerceIn(minX + 0.08f, 0.99f)
        val minY = (validPoints.minOf { it.y } - 0.06f).coerceIn(0.01f, 0.90f)
        val maxY = (validPoints.maxOf { it.y } + 0.06f).coerceIn(minY + 0.08f, 0.99f)
        val derivedBox = objectBox ?: NormalizedRect(minX, minY, maxX, maxY)

        val deltaSummary = PoseGeometryMath.computeAngleDelta(prevAngleProfile, angleProfile)
        val poseLabel = classifyFromAngleProfile(angleProfile, smoothedKp, deltaSummary)
        val avgConf = validPoints.map { it.confidence }.average().toFloat().coerceIn(0.45f, 0.99f)

        val res = PoseFrameResult(
            targetDetected = true,
            targetBox = derivedBox,
            poseLabel = poseLabel,
            keypoints = smoothedKp,
            angleProfile = angleProfile,
            maxAngleDeltaDeg = deltaSummary.maxDeltaDeg,
            confidence = avgConf,
            sourceTag = "mlkit-angle"
        )
        return res to smoothedKp
    }

    /**
     * Pure biomechanical angle rule engine mapping [PoseAngleProfile] -> [BabyPoseLabel].
     */
    fun classifyFromAngleProfile(
        angles: PoseAngleProfile,
        keypoints: PoseKeypoints?,
        prevProfileDelta: AngleDeltaSummary
    ): BabyPoseLabel {
        val lElbow = angles.leftElbowDeg
        val rElbow = angles.rightElbowDeg
        val lShoulder = angles.leftShoulderDeg
        val rShoulder = angles.rightShoulderDeg
        val lKnee = angles.leftKneeDeg
        val rKnee = angles.rightKneeDeg
        val lHip = angles.leftHipDeg
        val rHip = angles.rightHipDeg
        val roll = angles.bodyRollDeg ?: 0f

        // 1. Check Prone / Forearm Prop: inverted shoulder line OR both elbows tightly bent (~45°..105°) with moderate shoulder propping
        val ls = keypoints?.leftShoulder
        val rs = keypoints?.rightShoulder
        val shouldersInverted = ls != null && rs != null && ls.confidence >= 0.4f && rs.confidence >= 0.4f && ls.x > rs.x + 0.02f
        val bothElbowsPropped = lElbow != null && rElbow != null &&
            lElbow in 45f..100f && rElbow in 45f..100f &&
            (lShoulder ?: 80f) in 65f..110f && (rShoulder ?: 80f) in 65f..110f &&
            abs(lElbow - rElbow) < 18f
        if (shouldersInverted || bothElbowsPropped) {
            return BabyPoseLabel.PRONE_HEAD_UP
        }

        // 2. Check Sitting / Pushing Up: acute/right hip flexion angle (~60°..112°) + extended supporting arms or upright head
        val avgHip = listOfNotNull(lHip, rHip).takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val armsSupporting = (lElbow ?: 150f) > 135f && (rElbow ?: 150f) > 135f &&
            (lShoulder ?: 40f) < 60f && (rShoulder ?: 40f) < 60f
        val headAboveShoulders = if (keypoints?.nose != null && ls != null && rs != null) {
            keypoints.nose.y < min(ls.y, rs.y) - 0.05f
        } else {
            true
        }
        if (avgHip != null && avgHip in 55f..108f && armsSupporting && headAboveShoulders && abs(roll) < 28f) {
            return BabyPoseLabel.SITTING_AWAKE
        }

        // 3. Check Limb Active (Arm Bend / Raising or Leg Kick):
        // Triggered by strong joint angle asymmetry / high arm elevation + acute elbow bend / differential knee kick
        // OR large joint-angle jump (>= 28°) from previous pose!
        val armHighAndBent = (lShoulder != null && lShoulder >= 115f && (lElbow ?: 180f) <= 95f) ||
            (rShoulder != null && rShoulder >= 115f && (rElbow ?: 180f) <= 95f)
        val strongElbowAsymmetry = lElbow != null && rElbow != null && abs(lElbow - rElbow) >= 52f &&
            ((lShoulder ?: 0f) >= 85f || (rShoulder ?: 0f) >= 85f)
        val differentialLegKick = lKnee != null && rKnee != null && abs(lKnee - rKnee) >= 42f
        val largeJointRotation = prevProfileDelta.maxDeltaDeg >= 28f && prevProfileDelta.weightedMeanDeltaDeg >= 14f

        if (armHighAndBent || strongElbowAsymmetry || differentialLegKick || largeJointRotation) {
            return BabyPoseLabel.LIMB_ACTIVE
        }

        // 4. Check Left / Right Side Roll via body roll angle
        if (roll <= -28f) {
            return BabyPoseLabel.SIDE_LEFT
        }
        if (roll >= 28f) {
            return BabyPoseLabel.SIDE_RIGHT
        }

        // 5. Default relaxed lying posture
        return BabyPoseLabel.SUPINE_CALM
    }
}

/**
 * Angle-change-driven Wake-Up Tracker:
 * Counts a posture transition ONLY when body joint angles exhibit a substantial change
 * (max single-joint angle delta >= [angleThresholdDeg] or multi-joint weighted mean delta >= 0.62 * [angleThresholdDeg]),
 * completely ignoring camera shake, lighting flicker, or sub-threshold pixel motion!
 */
class BabyWakeUpTracker(
    var changeThreshold: Int = DEFAULT_CHANGE_THRESHOLD,
    var windowMs: Long = DEFAULT_WINDOW_MS,
    var minChangeIntervalMs: Long = DEFAULT_MIN_CHANGE_INTERVAL_MS,
    var angleThresholdDeg: Float = DEFAULT_ANGLE_THRESHOLD_DEG
) {
    companion object {
        const val DEFAULT_CHANGE_THRESHOLD = 5
        const val DEFAULT_WINDOW_MS = 30_000L
        const val DEFAULT_MIN_CHANGE_INTERVAL_MS = 1_500L
        const val DEFAULT_ANGLE_THRESHOLD_DEG = 25f
        const val DEFAULT_SNOOZE_MS = 3 * 60_000L
    }

    private val changeTimestamps = ArrayDeque<Long>()
    private val recentPoseLabels = ArrayDeque<String>()
    private var anchorPose: BabyPoseLabel = BabyPoseLabel.NONE
    private var anchorAngles: PoseAngleProfile? = null
    private var lastChangeRecordedMs: Long = 0L

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

    @Synchronized
    fun reset() {
        changeTimestamps.clear()
        recentPoseLabels.clear()
        anchorPose = BabyPoseLabel.NONE
        anchorAngles = null
        lastChangeRecordedMs = 0L
        isAlarmRinging = false
        snoozeUntilMs = 0L
        latestAngleProfile = PoseAngleProfile.EMPTY
        latestAngleDeltaDeg = 0f
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
            sourceTag = "sim-angle"
        )
        lastChangeRecordedMs = min(lastChangeRecordedMs, nowMs - minChangeIntervalMs - 10L)
        return onFrame(synthetic, nowMs)
    }

    @Synchronized
    fun onFrame(result: PoseFrameResult, nowMs: Long = System.currentTimeMillis()): WakeDecisionSnapshot {
        pruneExpired(nowMs)
        latestTargetDetected = result.targetDetected

        if (!result.targetDetected || result.poseLabel == BabyPoseLabel.NONE || result.angleProfile.validJointCount() < 2) {
            latestPose = BabyPoseLabel.NONE
            return buildSnapshot(nowMs, justTriggered = false)
        }

        latestPose = result.poseLabel
        latestAngleProfile = result.angleProfile

        val prevAnchor = anchorAngles
        if (anchorPose == BabyPoseLabel.NONE || prevAnchor == null) {
            anchorPose = result.poseLabel
            anchorAngles = result.angleProfile
            latestAngleDeltaDeg = 0f
            return buildSnapshot(nowMs, justTriggered = false)
        }

        // Compare current joint angles against the last stable anchor pose's joint angles!
        val deltaVsAnchor = PoseGeometryMath.computeAngleDelta(prevAnchor, result.angleProfile)
        latestAngleDeltaDeg = deltaVsAnchor.maxDeltaDeg

        val elapsedSinceChange = nowMs - lastChangeRecordedMs
        val minSingleJointDeg = angleThresholdDeg.coerceIn(12f, 60f)
        val minWeightedMeanDeg = (minSingleJointDeg * 0.58f).coerceAtLeast(10f)

        // Core rule: A pose change MUST involve genuine large joint-angle change!
        // Pure label boundary oscillation (< 60% of angleThresholdDeg) or pure pixel translation is ignored.
        val hasLargeJointAngleChange = deltaVsAnchor.comparedJointCount >= 2 && (
            deltaVsAnchor.maxDeltaDeg >= minSingleJointDeg ||
                deltaVsAnchor.weightedMeanDeltaDeg >= minWeightedMeanDeg
            )
        val hasVerifiedPoseSwitch = result.poseLabel != anchorPose &&
            deltaVsAnchor.maxDeltaDeg >= (minSingleJointDeg * 0.65f).coerceAtLeast(14f)

        if (elapsedSinceChange >= minChangeIntervalMs && (hasLargeJointAngleChange || hasVerifiedPoseSwitch)) {
            changeTimestamps.addLast(nowMs)
            lastChangeRecordedMs = nowMs

            val degInt = deltaVsAnchor.maxDeltaDeg.roundToInt()
            val jointInfo = if (deltaVsAnchor.dominantJointName.isNotBlank()) {
                "${deltaVsAnchor.dominantJointName} Δ${degInt}°"
            } else {
                "Δ${degInt}°"
            }
            val historyEntry = if (result.poseLabel != anchorPose) {
                "${anchorPose.zhLabel}→${result.poseLabel.zhLabel} ($jointInfo)"
            } else {
                "${result.poseLabel.zhLabel} ($jointInfo)"
            }
            pushHistory(historyEntry)

            // Update anchor to the new posture's angle profile so subsequent movements are measured from here
            anchorPose = result.poseLabel
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
            recentPoseHistory = recentPoseLabels.toList()
        )
    }
}
