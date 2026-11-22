package eu.pkgsoftware.babybuddywidgets.posemonitor

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Multi-backend Infant Pose Detector:
 * - Primary SOTA Backend: Ultralytics YOLO26n-pose (bundled offline) & YOLO11s/11m/Custom ONNX (cloud on-demand) via [OnnxYoloPoseDetector]
 * - Comparison Backend: Google ML Kit BlazePose Accurate (33-point 3D) & Lite
 */
class MlKitBabyPoseDetector(context: Context? = null) : Closeable {
    companion object {
        private const val TAG = "MlKitBabyPoseDetector"
        private const val GRID_SIZE = 96
    }

    private val appContext: Context? = context?.applicationContext
    private val yoloDetector: NcnnYoloPoseDetector? = appContext?.let { NcnnYoloPoseDetector(it) }
    private val visionAnalyzer = LightweightVisionPoseAnalyzer(GRID_SIZE, GRID_SIZE)
    private val gridPixels = IntArray(GRID_SIZE * GRID_SIZE)
    private var prevSmoothedKeypoints: PoseKeypoints? = null
    private var prevAngleProfile: PoseAngleProfile? = null

    private var activeMlKitMode: PoseEngineMode? = null
    private var poseDetector: PoseDetector? = null
    private var mlKitAvailable: Boolean = true
    private var modelWarmedUp: Boolean = false

    @Synchronized
    private fun getOrCreateMlKitDetector(mode: PoseEngineMode): PoseDetector? {
        if (!mlKitAvailable) return null
        if (poseDetector != null && activeMlKitMode == mode) {
            return poseDetector
        }
        try {
            poseDetector?.close()
        } catch (_: Throwable) {
        }
        poseDetector = null
        modelWarmedUp = false

        return try {
            val client = when (mode) {
                PoseEngineMode.ACCURATE_SINGLE -> {
                    val opts = AccuratePoseDetectorOptions.Builder()
                        .setDetectorMode(AccuratePoseDetectorOptions.SINGLE_IMAGE_MODE)
                        .setPreferredHardwareConfigs(AccuratePoseDetectorOptions.CPU_GPU)
                    PoseDetection.getClient(opts.build())
                }
                else -> {
                    val opts = PoseDetectorOptions.Builder()
                        .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                        .setPreferredHardwareConfigs(PoseDetectorOptions.CPU_GPU)
                        .build()
                    PoseDetection.getClient(opts)
                }
            }
            activeMlKitMode = mode
            poseDetector = client
            client
        } catch (t: Throwable) {
            Log.w(TAG, "ML Kit PoseDetector ($mode) init failed: ${t.message}", t)
            mlKitAvailable = false
            null
        }
    }

    @Synchronized
    fun reset() {
        visionAnalyzer.reset()
        prevSmoothedKeypoints = null
        prevAngleProfile = null
    }

    /**
     * Analyzes an already ROI-cropped/zoomed [roiBitmap] using [config] (YOLO ONNX or ML Kit BlazePose).
     * Must be called from a background thread.
     */
    fun analyzeRoiBitmap(
        roiBitmap: Bitmap,
        config: PoseDetectionConfig = PoseDetectionConfig.PRESET_BALANCED,
        roiRect: IntCropRect? = null
    ): PoseFrameResult {
        val fallbackContourResult = if (config.enableContourFallback) {
            val scaledGrid = Bitmap.createScaledBitmap(roiBitmap, GRID_SIZE, GRID_SIZE, true)
            scaledGrid.getPixels(gridPixels, 0, GRID_SIZE, 0, 0, GRID_SIZE, GRID_SIZE)
            if (scaledGrid !== roiBitmap) {
                scaledGrid.recycle()
            }
            visionAnalyzer.analyzeGrid(gridPixels, GRID_SIZE, GRID_SIZE, config)
        } else {
            PoseFrameResult(
                targetDetected = false,
                targetBox = null,
                poseLabel = BabyPoseLabel.NONE,
                keypoints = null,
                angleProfile = PoseAngleProfile.EMPTY,
                maxAngleDeltaDeg = 0f,
                confidence = 0f,
                sourceTag = "none",
                modelName = config.engineMode.modelDisplayName
            )
        }

        // Branch 1: Tencent ncnn Vulkan GPU / ARM82 FP16 YOLO Pose Engine
        if (config.engineMode.isYolo && yoloDetector != null) {
            val (yoloDet, activeModelLabel, inferMs) = yoloDetector.detectBitmap(roiBitmap, config, roiRect)
            if (yoloDet != null) {
                val (classified, nextSmoothed) = MlKitPoseClassifier.classifyFromKeypoints(
                    rawKeypoints = yoloDet.keypoints,
                    objectBox = yoloDet.box,
                    prevSmoothedKeypoints = prevSmoothedKeypoints,
                    prevAngleProfile = prevAngleProfile,
                    fallbackResult = fallbackContourResult,
                    config = config,
                    modelName = activeModelLabel,
                    inferenceTimeMs = inferMs
                )
                prevSmoothedKeypoints = nextSmoothed
                if (classified.targetDetected &&
                    (classified.angleProfile.validJointCount() >= 2 || classified.angleProfile.bodyRollDeg != null)
                ) {
                    prevAngleProfile = classified.angleProfile
                }
                return classified.copy(
                    targetBox = yoloDet.box,
                    sourceTag = if (classified.targetDetected) "yolo-pose" else classified.sourceTag
                )
            }
            return fallbackContourResult.copy(
                modelName = if (fallbackContourResult.targetDetected) fallbackContourResult.modelName else activeModelLabel,
                inferenceTimeMs = inferMs
            )
        }

        // Branch 2: Google ML Kit BlazePose Comparison Engine
        val pDetector = getOrCreateMlKitDetector(config.engineMode) ?: return fallbackContourResult

        val w = roiBitmap.width.toFloat().coerceAtLeast(1f)
        val h = roiBitmap.height.toFloat().coerceAtLeast(1f)
        val depthNormScale = max(w, h)

        val tStartNs = System.nanoTime()
        return try {
            val inputImage = InputImage.fromBitmap(roiBitmap, 0)
            val timeoutMs = if (modelWarmedUp) 900L else 2200L
            val pose: Pose? = Tasks.await(pDetector.process(inputImage), timeoutMs, TimeUnit.MILLISECONDS)
            modelWarmedUp = true
            val inferMs = ((System.nanoTime() - tStartNs) / 1_000_000L).coerceAtLeast(1L)

            if (pose != null && pose.allPoseLandmarks.isNotEmpty()) {
                val rawKp = PoseKeypoints(
                    nose = pose.getPoseLandmark(PoseLandmark.NOSE)?.toNorm3D(w, h, depthNormScale),
                    leftEye = pose.getPoseLandmark(PoseLandmark.LEFT_EYE)?.toNorm3D(w, h, depthNormScale),
                    rightEye = pose.getPoseLandmark(PoseLandmark.RIGHT_EYE)?.toNorm3D(w, h, depthNormScale),
                    leftEar = pose.getPoseLandmark(PoseLandmark.LEFT_EAR)?.toNorm3D(w, h, depthNormScale),
                    rightEar = pose.getPoseLandmark(PoseLandmark.RIGHT_EAR)?.toNorm3D(w, h, depthNormScale),
                    leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)?.toNorm3D(w, h, depthNormScale),
                    rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)?.toNorm3D(w, h, depthNormScale),
                    leftElbow = pose.getPoseLandmark(PoseLandmark.LEFT_ELBOW)?.toNorm3D(w, h, depthNormScale),
                    rightElbow = pose.getPoseLandmark(PoseLandmark.RIGHT_ELBOW)?.toNorm3D(w, h, depthNormScale),
                    leftWrist = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)?.toNorm3D(w, h, depthNormScale),
                    rightWrist = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)?.toNorm3D(w, h, depthNormScale),
                    leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)?.toNorm3D(w, h, depthNormScale),
                    rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)?.toNorm3D(w, h, depthNormScale),
                    leftKnee = pose.getPoseLandmark(PoseLandmark.LEFT_KNEE)?.toNorm3D(w, h, depthNormScale),
                    rightKnee = pose.getPoseLandmark(PoseLandmark.RIGHT_KNEE)?.toNorm3D(w, h, depthNormScale),
                    leftAnkle = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)?.toNorm3D(w, h, depthNormScale),
                    rightAnkle = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)?.toNorm3D(w, h, depthNormScale)
                )
                val (classified, nextSmoothed) = MlKitPoseClassifier.classifyFromKeypoints(
                    rawKeypoints = rawKp,
                    objectBox = null,
                    prevSmoothedKeypoints = prevSmoothedKeypoints,
                    prevAngleProfile = prevAngleProfile,
                    fallbackResult = fallbackContourResult,
                    config = config,
                    modelName = config.engineMode.modelDisplayName,
                    inferenceTimeMs = inferMs
                )
                prevSmoothedKeypoints = nextSmoothed
                if (classified.targetDetected &&
                    (classified.angleProfile.validJointCount() >= 2 || classified.angleProfile.bodyRollDeg != null)
                ) {
                    prevAngleProfile = classified.angleProfile
                }
                classified
            } else {
                fallbackContourResult.copy(
                    modelName = if (fallbackContourResult.targetDetected) {
                        fallbackContourResult.modelName
                    } else {
                        config.engineMode.modelDisplayName
                    },
                    inferenceTimeMs = inferMs
                )
            }
        } catch (t: Throwable) {
            val inferMs = ((System.nanoTime() - tStartNs) / 1_000_000L).coerceAtLeast(1L)
            Log.w(TAG, "ML Kit pose frame error (${config.engineMode.code}, ${inferMs}ms): ${t.message}")
            fallbackContourResult.copy(inferenceTimeMs = inferMs)
        }
    }

    private fun PoseLandmark.toNorm3D(w: Float, h: Float, depthScale: Float): NormalizedPoint {
        val p3 = position3D
        return NormalizedPoint(
            x = (p3.x / w).coerceIn(0f, 1f),
            y = (p3.y / h).coerceIn(0f, 1f),
            confidence = inFrameLikelihood.coerceIn(0f, 1f),
            z = (p3.z / depthScale).coerceIn(-1.5f, 1.5f)
        )
    }

    @Synchronized
    override fun close() {
        try {
            yoloDetector?.close()
        } catch (_: Throwable) {
        }
        try {
            poseDetector?.close()
        } catch (_: Throwable) {
        }
        poseDetector = null
        activeMlKitMode = null
    }
}
