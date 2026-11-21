package eu.pkgsoftware.babybuddywidgets.posemonitor

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * Runs simple Object Detection + Pose Analysis on a zoomed/cropped ROI Bitmap.
 * Combines bundled ML Kit ObjectDetector + PoseDetector with [LightweightVisionPoseAnalyzer]
 * so distant or partially blanketed babies are still reliably tracked.
 */
class MlKitBabyPoseDetector : Closeable {
    companion object {
        private const val TAG = "MlKitBabyPoseDetector"
        private const val GRID_SIZE = 96
    }

    private val visionAnalyzer = LightweightVisionPoseAnalyzer(GRID_SIZE, GRID_SIZE)
    private val gridPixels = IntArray(GRID_SIZE * GRID_SIZE)
    private var prevMlKeypoints: PoseKeypoints? = null
    private var mlKitAvailable: Boolean = true

    private val poseDetector: PoseDetector? by lazy {
        try {
            val opts = PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
            PoseDetection.getClient(opts)
        } catch (t: Throwable) {
            Log.w(TAG, "ML Kit PoseDetector unavailable, falling back to vision engine", t)
            mlKitAvailable = false
            null
        }
    }

    private val objectDetector: ObjectDetector? by lazy {
        try {
            val opts = ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableClassification()
                .build()
            ObjectDetection.getClient(opts)
        } catch (t: Throwable) {
            Log.w(TAG, "ML Kit ObjectDetector unavailable, falling back to vision engine", t)
            null
        }
    }

    fun reset() {
        visionAnalyzer.reset()
        prevMlKeypoints = null
    }

    /**
     * Analyzes an already ROI-cropped/zoomed [roiBitmap].
     * Must be called from a background thread.
     */
    fun analyzeRoiBitmap(roiBitmap: Bitmap): PoseFrameResult {
        val scaledGrid = Bitmap.createScaledBitmap(roiBitmap, GRID_SIZE, GRID_SIZE, true)
        scaledGrid.getPixels(gridPixels, 0, GRID_SIZE, 0, 0, GRID_SIZE, GRID_SIZE)
        if (scaledGrid !== roiBitmap) {
            scaledGrid.recycle()
        }
        val visionResult = visionAnalyzer.analyzeGrid(gridPixels, GRID_SIZE, GRID_SIZE)

        if (!mlKitAvailable) {
            return visionResult
        }

        val w = roiBitmap.width.toFloat().coerceAtLeast(1f)
        val h = roiBitmap.height.toFloat().coerceAtLeast(1f)

        return try {
            val inputImage = InputImage.fromBitmap(roiBitmap, 0)
            val pDetector = poseDetector
            val oDetector = objectDetector

            val poseTask = pDetector?.process(inputImage)
            val objTask = oDetector?.process(inputImage)

            val pose: Pose? = poseTask?.let { Tasks.await(it, 280, TimeUnit.MILLISECONDS) }
            val objects = objTask?.let {
                try {
                    Tasks.await(it, 180, TimeUnit.MILLISECONDS)
                } catch (_: Throwable) {
                    null
                }
            }

            val bestObjBox: NormalizedRect? = objects
                ?.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?.boundingBox
                ?.let { rect ->
                    NormalizedRect(
                        left = (rect.left / w).coerceIn(0f, 1f),
                        top = (rect.top / h).coerceIn(0f, 1f),
                        right = (rect.right / w).coerceIn(0f, 1f),
                        bottom = (rect.bottom / h).coerceIn(0f, 1f)
                    )
                }

            if (pose != null && pose.allPoseLandmarks.isNotEmpty()) {
                val kp = PoseKeypoints(
                    nose = pose.getPoseLandmark(PoseLandmark.NOSE)?.toNorm(w, h),
                    leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)?.toNorm(w, h),
                    rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)?.toNorm(w, h),
                    leftWrist = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)?.toNorm(w, h),
                    rightWrist = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)?.toNorm(w, h),
                    leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)?.toNorm(w, h),
                    rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)?.toNorm(w, h),
                    leftAnkle = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)?.toNorm(w, h),
                    rightAnkle = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)?.toNorm(w, h)
                )
                val classified = MlKitPoseClassifier.classifyFromKeypoints(
                    keypoints = kp,
                    objectBox = bestObjBox,
                    prevKeypoints = prevMlKeypoints,
                    fallbackResult = visionResult
                )
                prevMlKeypoints = kp
                classified
            } else if (bestObjBox != null) {
                visionResult.copy(
                    targetDetected = true,
                    targetBox = bestObjBox,
                    sourceTag = "mlkit-obj+vision"
                )
            } else {
                visionResult
            }
        } catch (t: Throwable) {
            Log.d(TAG, "ML Kit frame fallback to lightweight vision: ${t.message}")
            visionResult
        }
    }

    private fun PoseLandmark.toNorm(w: Float, h: Float): NormalizedPoint {
        return NormalizedPoint(
            x = (position.x / w).coerceIn(0f, 1f),
            y = (position.y / h).coerceIn(0f, 1f),
            confidence = inFrameLikelihood.coerceIn(0f, 1f)
        )
    }

    override fun close() {
        try {
            poseDetector?.close()
        } catch (_: Throwable) {
        }
        try {
            objectDetector?.close()
        } catch (_: Throwable) {
        }
    }
}
