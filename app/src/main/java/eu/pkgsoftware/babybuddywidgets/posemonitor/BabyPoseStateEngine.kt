package eu.pkgsoftware.babybuddywidgets.posemonitor

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class BabyPoseLabel(val code: String, val zhLabel: String, val enLabel: String) {
    NONE("none", "未检测到婴儿", "No Baby Detected"),
    SUPINE_CALM("supine_calm", "仰卧安睡", "Supine Calm"),
    SIDE_LEFT("side_left", "左侧翻身", "Left Side Roll"),
    SIDE_RIGHT("side_right", "右侧翻身", "Right Side Roll"),
    PRONE_HEAD_UP("prone_head_up", "趴卧/抬头", "Prone / Head Up"),
    LIMB_ACTIVE("limb_active", "四肢挥动/踢腿", "Active Limbs / Kicking"),
    SITTING_AWAKE("sitting_awake", "撑起/坐立", "Pushing Up / Sitting");

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

data class PoseKeypoints(
    val nose: NormalizedPoint?,
    val leftShoulder: NormalizedPoint?,
    val rightShoulder: NormalizedPoint?,
    val leftWrist: NormalizedPoint?,
    val rightWrist: NormalizedPoint?,
    val leftHip: NormalizedPoint?,
    val rightHip: NormalizedPoint?,
    val leftAnkle: NormalizedPoint?,
    val rightAnkle: NormalizedPoint?
) {
    fun allPoints(): List<NormalizedPoint> = listOfNotNull(
        nose, leftShoulder, rightShoulder, leftWrist, rightWrist,
        leftHip, rightHip, leftAnkle, rightAnkle
    )
}

data class PoseFrameResult(
    val targetDetected: Boolean,
    val targetBox: NormalizedRect?,
    val poseLabel: BabyPoseLabel,
    val keypoints: PoseKeypoints?,
    val motionScore: Float,
    val confidence: Float,
    val poseVector: FloatArray,
    val sourceTag: String
)

data class WakeDecisionSnapshot(
    val currentPose: BabyPoseLabel,
    val targetDetected: Boolean,
    val changeCount: Int,
    val changeThreshold: Int,
    val windowSec: Int,
    val motionScore: Float,
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

    /**
     * Computes pixel crop rect for a given zoom ratio [1.0..8.0] and normalized pan [-1.0..1.0].
     * Zooming in crops a smaller region around the panned center so distant babies are magnified
     * both visually and for downstream object/pose detection.
     */
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
 * Pure-Kotlin lightweight object + posture analyzer operating on a downsampled pixel grid.
 * Ensures baby target detection and posture transition analysis always work even when baby is
 * swaddled/blanketed, far away (after ROI zoom), or streamed via MJPEG without GMS.
 */
class LightweightVisionPoseAnalyzer(
    private val gridWidth: Int = 96,
    private val gridHeight: Int = 96
) {
    private var prevLuma: FloatArray? = null
    private var bgLuma: FloatArray? = null
    private var smoothedBox: NormalizedRect? = null

    fun reset() {
        prevLuma = null
        bgLuma = null
        smoothedBox = null
    }

    fun analyzeGrid(argbPixels: IntArray, width: Int = gridWidth, height: Int = gridHeight): PoseFrameResult {
        val total = width * height
        if (argbPixels.size < total || width < 8 || height < 8) {
            return PoseFrameResult(
                targetDetected = false,
                targetBox = null,
                poseLabel = BabyPoseLabel.NONE,
                keypoints = null,
                motionScore = 0f,
                confidence = 0f,
                poseVector = FloatArray(6),
                sourceTag = "vision-empty"
            )
        }

        val currLuma = FloatArray(total)
        val warmth = FloatArray(total)
        var meanLuma = 0f

        for (i in 0 until total) {
            val px = argbPixels[i]
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            currLuma[i] = y
            meanLuma += y
            // Skin / warm fabric / infant face saliency cue
            val skinCue = if (r > g && g > b * 0.85f && (r - b) > 0.06f) {
                ((r - b) * 1.4f + (r - g) * 0.6f).coerceIn(0f, 1f)
            } else {
                0f
            }
            warmth[i] = skinCue
        }
        meanLuma /= total.toFloat()

        val prev = prevLuma
        val bg = bgLuma ?: currLuma.copyOf().also { bgLuma = it }

        var globalDiffSum = 0f
        var activeMotionCells = 0
        var salientWeightSum = 0f
        var sumX = 0f
        var sumY = 0f
        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0

        val motionMap = FloatArray(total)
        for (y in 1 until height - 1) {
            val rowOffset = y * width
            for (x in 1 until width - 1) {
                val idx = rowOffset + x
                val luma = currLuma[idx]
                val frameDiff = if (prev != null) abs(luma - prev[idx]) else 0f
                val bgDiff = abs(luma - bg[idx])
                bg[idx] = bg[idx] * 0.96f + luma * 0.04f

                // Local contrast for subject presence in crib center
                val localEdge = (abs(luma - currLuma[idx - 1]) + abs(luma - currLuma[idx - width])) * 0.5f
                val centerBias = 1f - 0.35f * hypot(
                    (x.toFloat() / width - 0.5f) * 1.4f,
                    (y.toFloat() / height - 0.5f) * 1.4f
                ).coerceIn(0f, 1f)

                motionMap[idx] = frameDiff
                globalDiffSum += frameDiff
                if (frameDiff > 0.045f) {
                    activeMotionCells++
                }

                val saliency = (frameDiff * 3.2f + bgDiff * 1.2f + warmth[idx] * 0.85f + localEdge * 0.9f) * centerBias
                if (saliency > 0.20f) {
                    salientWeightSum += saliency
                    sumX += x * saliency
                    sumY += y * saliency
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        prevLuma = currLuma

        val motionRatio = activeMotionCells.toFloat() / total.toFloat()
        val avgDiff = globalDiffSum / total.toFloat()
        val motionScore = (motionRatio * 3.6f + avgDiff * 4.5f).coerceIn(0f, 1f)

        val hasSalientSubject = salientWeightSum > (total * 0.008f) && maxX > minX + 3 && maxY > minY + 3
        if (!hasSalientSubject && smoothedBox == null) {
            return PoseFrameResult(
                targetDetected = false,
                targetBox = null,
                poseLabel = BabyPoseLabel.NONE,
                keypoints = null,
                motionScore = motionScore,
                confidence = 0.15f,
                poseVector = FloatArray(6),
                sourceTag = "vision-none"
            )
        }

        val rawBox = if (hasSalientSubject) {
            val cx = (sumX / salientWeightSum) / width.toFloat()
            val cy = (sumY / salientWeightSum) / height.toFloat()
            val rawLeft = (minX.toFloat() / width).coerceIn(0.04f, 0.85f)
            val rawRight = (maxX.toFloat() / width).coerceIn(rawLeft + 0.12f, 0.96f)
            val rawTop = (minY.toFloat() / height).coerceIn(0.04f, 0.85f)
            val rawBottom = (maxY.toFloat() / height).coerceIn(rawTop + 0.14f, 0.96f)
            // Blend tight centroid window with extrema box to avoid full-frame noise
            val halfW = ((rawRight - rawLeft) * 0.5f).coerceIn(0.12f, 0.42f)
            val halfH = ((rawBottom - rawTop) * 0.5f).coerceIn(0.14f, 0.44f)
            NormalizedRect(
                left = (cx - halfW).coerceIn(0.03f, 0.82f),
                top = (cy - halfH).coerceIn(0.03f, 0.82f),
                right = (cx + halfW).coerceIn(0.18f, 0.97f),
                bottom = (cy + halfH).coerceIn(0.18f, 0.97f)
            )
        } else {
            smoothedBox!!
        }

        val box = smoothedBox?.let { prevBox ->
            NormalizedRect(
                left = prevBox.left * 0.55f + rawBox.left * 0.45f,
                top = prevBox.top * 0.55f + rawBox.top * 0.45f,
                right = prevBox.right * 0.55f + rawBox.right * 0.45f,
                bottom = prevBox.bottom * 0.55f + rawBox.bottom * 0.45f
            )
        } ?: rawBox
        smoothedBox = box

        // Compute quadrant motion & mass asymmetry inside the detected baby box
        val bx0 = (box.left * width).toInt().coerceIn(1, width - 2)
        val bx1 = (box.right * width).toInt().coerceIn(bx0 + 1, width - 1)
        val by0 = (box.top * height).toInt().coerceIn(1, height - 2)
        val by1 = (box.bottom * height).toInt().coerceIn(by0 + 1, height - 1)
        val midX = (bx0 + bx1) / 2
        val midY = (by0 + by1) / 2

        var upperMotion = 0f
        var lowerMotion = 0f
        var leftMass = 0f
        var rightMass = 0f
        var headWarmthX = 0f
        var headWarmthWeight = 0f
        var boxCount = 0

        for (y in by0..by1) {
            val ro = y * width
            for (x in bx0..bx1) {
                val idx = ro + x
                val m = motionMap[idx]
                val w = warmth[idx] + abs(currLuma[idx] - meanLuma)
                if (y <= midY) {
                    upperMotion += m
                    if (warmth[idx] > 0.05f) {
                        headWarmthX += (x - bx0).toFloat() / max(1, bx1 - bx0) * warmth[idx]
                        headWarmthWeight += warmth[idx]
                    }
                } else {
                    lowerMotion += m
                }
                if (x <= midX) leftMass += w else rightMass += w
                boxCount++
            }
        }

        val normFactor = max(1, boxCount / 2).toFloat()
        val upperEnergy = (upperMotion / normFactor * 8f).coerceIn(0f, 1f)
        val lowerEnergy = (lowerMotion / normFactor * 8f).coerceIn(0f, 1f)
        val lrAsymmetry = ((rightMass - leftMass) / max(0.001f, leftMass + rightMass)).coerceIn(-1f, 1f)
        val headRelX = if (headWarmthWeight > 0.05f) (headWarmthX / headWarmthWeight) else 0.5f
        val aspect = box.width / max(0.05f, box.height)

        val poseLabel = when {
            motionScore >= 0.24f || upperEnergy >= 0.30f || lowerEnergy >= 0.30f -> BabyPoseLabel.LIMB_ACTIVE
            aspect > 1.32f && upperEnergy > 0.14f -> BabyPoseLabel.PRONE_HEAD_UP
            lrAsymmetry < -0.15f || headRelX < 0.36f -> BabyPoseLabel.SIDE_LEFT
            lrAsymmetry > 0.15f || headRelX > 0.64f -> BabyPoseLabel.SIDE_RIGHT
            else -> BabyPoseLabel.SUPINE_CALM
        }

        // Synthesize approximate anatomical landmarks inside box for visual overlay feedback
        val cx = box.centerX
        val topY = box.top
        val h = box.height
        val w = box.width
        val rollShift = lrAsymmetry * w * 0.16f
        val armLift = if (poseLabel == BabyPoseLabel.LIMB_ACTIVE) -h * 0.14f else h * 0.08f
        val kickSpread = if (poseLabel == BabyPoseLabel.LIMB_ACTIVE) w * 0.28f else w * 0.16f

        val approxKeypoints = PoseKeypoints(
            nose = NormalizedPoint((cx + rollShift).coerceIn(0f, 1f), (topY + h * 0.18f).coerceIn(0f, 1f), 0.65f),
            leftShoulder = NormalizedPoint((cx - w * 0.18f + rollShift * 0.5f).coerceIn(0f, 1f), (topY + h * 0.34f).coerceIn(0f, 1f), 0.6f),
            rightShoulder = NormalizedPoint((cx + w * 0.18f + rollShift * 0.5f).coerceIn(0f, 1f), (topY + h * 0.34f).coerceIn(0f, 1f), 0.6f),
            leftWrist = NormalizedPoint((cx - w * 0.30f).coerceIn(0f, 1f), (topY + h * 0.42f + armLift).coerceIn(0f, 1f), 0.55f),
            rightWrist = NormalizedPoint((cx + w * 0.30f).coerceIn(0f, 1f), (topY + h * 0.42f + armLift).coerceIn(0f, 1f), 0.55f),
            leftHip = NormalizedPoint((cx - w * 0.14f).coerceIn(0f, 1f), (topY + h * 0.64f).coerceIn(0f, 1f), 0.6f),
            rightHip = NormalizedPoint((cx + w * 0.14f).coerceIn(0f, 1f), (topY + h * 0.64f).coerceIn(0f, 1f), 0.6f),
            leftAnkle = NormalizedPoint((cx - kickSpread).coerceIn(0f, 1f), (topY + h * 0.88f).coerceIn(0f, 1f), 0.55f),
            rightAnkle = NormalizedPoint((cx + kickSpread).coerceIn(0f, 1f), (topY + h * 0.88f).coerceIn(0f, 1f), 0.55f)
        )

        val vector = floatArrayOf(
            box.centerX,
            box.centerY,
            lrAsymmetry,
            upperEnergy,
            lowerEnergy,
            aspect.coerceIn(0.3f, 2.5f) * 0.4f
        )

        return PoseFrameResult(
            targetDetected = true,
            targetBox = box,
            poseLabel = poseLabel,
            keypoints = approxKeypoints,
            motionScore = motionScore,
            confidence = (0.55f + min(0.40f, salientWeightSum / (total * 0.08f))).coerceIn(0f, 0.95f),
            poseVector = vector,
            sourceTag = "vision-roi"
        )
    }
}

/**
 * Classifies baby pose from 33-point ML Kit skeletal keypoints + object bounding box,
 * and fuses with LightweightVisionPoseAnalyzer when keypoints are occluded by blankets.
 */
object MlKitPoseClassifier {
    fun classifyFromKeypoints(
        keypoints: PoseKeypoints,
        objectBox: NormalizedRect?,
        prevKeypoints: PoseKeypoints?,
        fallbackResult: PoseFrameResult
    ): PoseFrameResult {
        val validPoints = keypoints.allPoints().filter { it.confidence >= 0.38f }
        if (validPoints.size < 4) {
            // If ML Kit object detector found a box, refine fallback's targetBox
            return if (objectBox != null) {
                fallbackResult.copy(
                    targetDetected = true,
                    targetBox = objectBox,
                    confidence = max(fallbackResult.confidence, 0.72f),
                    sourceTag = "mlkit-obj+vision"
                )
            } else {
                fallbackResult
            }
        }

        val minX = (validPoints.minOf { it.x } - 0.06f).coerceIn(0.01f, 0.90f)
        val maxX = (validPoints.maxOf { it.x } + 0.06f).coerceIn(minX + 0.08f, 0.99f)
        val minY = (validPoints.minOf { it.y } - 0.06f).coerceIn(0.01f, 0.90f)
        val maxY = (validPoints.maxOf { it.y } + 0.06f).coerceIn(minY + 0.08f, 0.99f)
        val derivedBox = objectBox ?: NormalizedRect(minX, minY, maxX, maxY)

        val ls = keypoints.leftShoulder
        val rs = keypoints.rightShoulder
        val lh = keypoints.leftHip
        val rh = keypoints.rightHip
        val lw = keypoints.leftWrist
        val rw = keypoints.rightWrist
        val la = keypoints.leftAnkle
        val ra = keypoints.rightAnkle
        val nose = keypoints.nose

        // Compute limb displacement velocity relative to previous frame
        var keypointDelta = 0f
        var deltaCount = 0
        if (prevKeypoints != null) {
            val pairs = listOf(
                nose to prevKeypoints.nose,
                lw to prevKeypoints.leftWrist,
                rw to prevKeypoints.rightWrist,
                la to prevKeypoints.leftAnkle,
                ra to prevKeypoints.rightAnkle,
                ls to prevKeypoints.leftShoulder,
                rs to prevKeypoints.rightShoulder
            )
            for ((curr, old) in pairs) {
                if (curr != null && old != null && curr.confidence >= 0.35f && old.confidence >= 0.35f) {
                    keypointDelta += hypot(curr.x - old.x, curr.y - old.y)
                    deltaCount++
                }
            }
        }
        val avgKpMove = if (deltaCount > 0) keypointDelta / deltaCount else 0f
        val fusedMotion = max(fallbackResult.motionScore, (avgKpMove * 5.2f).coerceIn(0f, 1f))

        val shoulderMidX = if (ls != null && rs != null) (ls.x + rs.x) * 0.5f else derivedBox.centerX
        val shoulderMidY = if (ls != null && rs != null) (ls.y + rs.y) * 0.5f else derivedBox.top + derivedBox.height * 0.32f
        val hipMidY = if (lh != null && rh != null) (lh.y + rh.y) * 0.5f else derivedBox.top + derivedBox.height * 0.65f
        val shoulderSpan = if (ls != null && rs != null) abs(rs.x - ls.x) else derivedBox.width * 0.4f
        val torsoHeight = abs(hipMidY - shoulderMidY).coerceAtLeast(0.05f)

        val handsRaised = (lw != null && lw.confidence >= 0.4f && lw.y < shoulderMidY - 0.03f) ||
            (rw != null && rw.confidence >= 0.4f && rw.y < shoulderMidY - 0.03f)
        val legsKicking = la != null && ra != null && abs(la.y - ra.y) > torsoHeight * 0.45f
        val noseOffset = if (nose != null) (nose.x - shoulderMidX) / max(0.06f, shoulderSpan) else 0f
        val narrowShoulders = shoulderSpan < torsoHeight * 0.48f

        val poseLabel = when {
            fusedMotion >= 0.22f || (handsRaised && avgKpMove >= 0.025f) || legsKicking -> BabyPoseLabel.LIMB_ACTIVE
            nose != null && ls != null && rs != null && nose.y < shoulderMidY - torsoHeight * 0.65f && torsoHeight > 0.22f && derivedBox.height > derivedBox.width * 1.35f && handsRaised -> BabyPoseLabel.SITTING_AWAKE
            ls != null && rs != null && ls.x > rs.x + 0.02f -> BabyPoseLabel.PRONE_HEAD_UP
            narrowShoulders && noseOffset < -0.22f -> BabyPoseLabel.SIDE_LEFT
            narrowShoulders && noseOffset > 0.22f -> BabyPoseLabel.SIDE_RIGHT
            noseOffset < -0.38f -> BabyPoseLabel.SIDE_LEFT
            noseOffset > 0.38f -> BabyPoseLabel.SIDE_RIGHT
            else -> BabyPoseLabel.SUPINE_CALM
        }

        val vector = floatArrayOf(
            shoulderMidX,
            shoulderMidY,
            noseOffset.coerceIn(-1f, 1f),
            if (handsRaised) 0.8f else 0.1f,
            fusedMotion,
            (shoulderSpan / torsoHeight).coerceIn(0f, 2f) * 0.5f
        )

        val avgConf = validPoints.map { it.confidence }.average().toFloat()
        return PoseFrameResult(
            targetDetected = true,
            targetBox = derivedBox,
            poseLabel = poseLabel,
            keypoints = keypoints,
            motionScore = fusedMotion,
            confidence = avgConf.coerceIn(0.45f, 0.99f),
            poseVector = vector,
            sourceTag = "mlkit-pose"
        )
    }
}

/**
 * Sliding-window multi-pose-change detector:
 * When the baby changes posture (or exhibits strong limb movement bursts) >= [changeThreshold] times
 * within [windowMs], determines the baby is waking up and triggers the immediate alarm + watch alert.
 */
class BabyWakeUpTracker(
    var changeThreshold: Int = DEFAULT_CHANGE_THRESHOLD,
    var windowMs: Long = DEFAULT_WINDOW_MS,
    var minChangeIntervalMs: Long = DEFAULT_MIN_CHANGE_INTERVAL_MS,
    var sensitivity: Float = 0.55f // 0.0 (low) .. 1.0 (high)
) {
    companion object {
        const val DEFAULT_CHANGE_THRESHOLD = 5
        const val DEFAULT_WINDOW_MS = 30_000L
        const val DEFAULT_MIN_CHANGE_INTERVAL_MS = 1_500L
        const val DEFAULT_SNOOZE_MS = 3 * 60_000L
    }

    private val changeTimestamps = ArrayDeque<Long>()
    private val recentPoseLabels = ArrayDeque<String>()
    private var lastStablePose: BabyPoseLabel = BabyPoseLabel.NONE
    private var lastReferenceVector: FloatArray? = null
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
    var latestMotionScore: Float = 0f
        private set

    @Synchronized
    fun reset(nowMs: Long = System.currentTimeMillis()) {
        changeTimestamps.clear()
        recentPoseLabels.clear()
        lastStablePose = BabyPoseLabel.NONE
        lastReferenceVector = null
        lastChangeRecordedMs = 0L
        isAlarmRinging = false
        snoozeUntilMs = 0L
    }

    @Synchronized
    fun dismissAlarm(nowMs: Long = System.currentTimeMillis()) {
        isAlarmRinging = false
        changeTimestamps.clear()
        // Brief 5-second grace period after manual dismiss
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
        }
        isAlarmRinging = true
        lastWakeTimestampMs = nowMs
        pushHistory("测试唤醒触发")
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
        val synthetic = PoseFrameResult(
            targetDetected = true,
            targetBox = NormalizedRect(0.24f, 0.20f, 0.76f, 0.84f),
            poseLabel = nextPose,
            keypoints = null,
            motionScore = 0.38f,
            confidence = 0.92f,
            poseVector = floatArrayOf(0.5f, 0.5f, nextIdx * 0.25f, 0.5f, 0.4f, 0.5f),
            sourceTag = "sim"
        )
        // Bypass debounce interval during explicit manual step simulation
        lastChangeRecordedMs = min(lastChangeRecordedMs, nowMs - minChangeIntervalMs - 10L)
        return onFrame(synthetic, nowMs)
    }

    @Synchronized
    fun onFrame(result: PoseFrameResult, nowMs: Long = System.currentTimeMillis()): WakeDecisionSnapshot {
        pruneExpired(nowMs)
        latestTargetDetected = result.targetDetected
        latestMotionScore = result.motionScore

        if (!result.targetDetected || result.poseLabel == BabyPoseLabel.NONE) {
            latestPose = BabyPoseLabel.NONE
            return buildSnapshot(nowMs, justTriggered = false)
        }

        latestPose = result.poseLabel
        val elapsedSinceChange = nowMs - lastChangeRecordedMs

        val vectorDelta = lastReferenceVector?.let { prevVec ->
            vectorDistance(prevVec, result.poseVector)
        } ?: 0f

        val motionThreshold = (0.34f - sensitivity * 0.16f).coerceIn(0.14f, 0.36f)
        val vectorThreshold = (0.42f - sensitivity * 0.20f).coerceIn(0.18f, 0.44f)

        val hasLabelTransition = lastStablePose != BabyPoseLabel.NONE && result.poseLabel != lastStablePose
        val hasStrongPostureBurst = result.motionScore >= motionThreshold || vectorDelta >= vectorThreshold

        if (lastStablePose == BabyPoseLabel.NONE) {
            lastStablePose = result.poseLabel
            lastReferenceVector = result.poseVector.copyOf()
        } else if (elapsedSinceChange >= minChangeIntervalMs && (hasLabelTransition || hasStrongPostureBurst)) {
            changeTimestamps.addLast(nowMs)
            lastChangeRecordedMs = nowMs
            val tag = if (hasLabelTransition) {
                "${lastStablePose.zhLabel} → ${result.poseLabel.zhLabel}"
            } else {
                "${result.poseLabel.zhLabel} (体动)"
            }
            pushHistory(tag)
            lastStablePose = result.poseLabel
            lastReferenceVector = result.poseVector.copyOf()
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

    private fun vectorDistance(a: FloatArray, b: FloatArray): Float {
        val n = min(a.size, b.size)
        if (n == 0) return 0f
        var sumSq = 0f
        for (i in 0 until n) {
            val d = a[i] - b[i]
            sumSq += d * d
        }
        return sqrt(sumSq / n.toFloat())
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
            changeCount = changeTimestamps.size,
            changeThreshold = changeThreshold,
            windowSec = (windowMs / 1000L).toInt(),
            motionScore = latestMotionScore,
            justTriggeredWake = justTriggered,
            isAlarmRinging = isAlarmRinging,
            snoozedRemainingSec = snoozedSec,
            lastWakeTimestampMs = lastWakeTimestampMs,
            recentPoseHistory = recentPoseLabels.toList()
        )
    }
}
