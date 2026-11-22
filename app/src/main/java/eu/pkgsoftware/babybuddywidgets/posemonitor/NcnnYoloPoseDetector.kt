package eu.pkgsoftware.babybuddywidgets.posemonitor

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Decoded single-person COCO 17-keypoint detection in normalized [0..1] ROI coordinates.
 */
data class YoloPoseDetection(
    val box: NormalizedRect,
    val boxScore: Float,
    val keypoints: PoseKeypoints
)

/**
 * Native JNI bridge to `libbabybuddy_ncnn_pose.so`
 * (Tencent ncnn 20261020: Zero-Spin OpenMP + Zero-Bitmap YUV420 Gather + Vulkan PipelineCache).
 */
object NcnnYoloPoseNative {
    private const val TAG = "NcnnYoloPoseNative"

    val isAvailable: Boolean = try {
        System.loadLibrary("ncnn")
        System.loadLibrary("babybuddy_ncnn_pose")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "Native ncnn library load skipped/unavailable in current runtime: ${t.message}")
        false
    }

    external fun nativeLoadModel(
        paramPath: String,
        binPath: String,
        cacheDirPath: String,
        targetSize: Int,
        preferVulkan: Boolean,
        numThreads: Int
    ): Boolean

    external fun nativeGetActiveHardwareLabel(): String

    external fun nativeHasVulkanGpu(): Boolean

    external fun nativeProbeVulkanAsync(): Boolean

    external fun nativePrepareYuv420(
        yBuf: ByteBuffer,
        yRowStride: Int,
        uBuf: ByteBuffer,
        vBuf: ByteBuffer,
        uvRowStride: Int,
        uvPixelStride: Int,
        srcW: Int,
        srcH: Int,
        rotationDeg: Int,
        isFrontMirror: Boolean,
        zoomRatio: Float,
        panX: Float,
        panY: Float
    ): Boolean

    external fun nativeRenderYuvRoiToBitmap(
        yBuf: ByteBuffer,
        yRowStride: Int,
        uBuf: ByteBuffer,
        vBuf: ByteBuffer,
        uvRowStride: Int,
        uvPixelStride: Int,
        srcW: Int,
        srcH: Int,
        rotationDeg: Int,
        isFrontMirror: Boolean,
        zoomRatio: Float,
        panX: Float,
        panY: Float,
        dstBitmap: Bitmap
    ): Boolean

    external fun nativeRunPrepared(
        out56: FloatArray,
        boxConfThresh: Float,
        iouThresh: Float
    ): Boolean

    external fun nativeDetectBitmapRoi(
        bitmap: Bitmap,
        roiLeft: Int,
        roiTop: Int,
        roiWidth: Int,
        roiHeight: Int,
        boxConfThresh: Float,
        iouThresh: Float
    ): FloatArray?

    external fun nativeRelease()
}

/**
 * Zero-GC Tencent ncnn YOLO Pose Detector:
 * - CameraX path uses direct C++ YUV_420_888 plane sampling (`0` Bitmaps, `0` ByteArrays, `0` FloatArrays allocated per frame!)
 * - Keeps `mmap` weights & Vulkan `PipelineCache` warm across dialog sessions for 0ms startup.
 */
class NcnnYoloPoseDetector(private val appContext: Context) : Closeable {

    private val reusableOut56 = FloatArray(56)

    fun ensureModelReady(config: PoseDetectionConfig): Pair<Boolean, String> {
        if (!NcnnYoloPoseNative.isAvailable) {
            return false to "ncnn 原生库未就绪"
        }
        val requestedMode = config.engineMode
        var activeMode = requestedMode
        var pair = YoloModelManager.resolveLocalModelPair(appContext, requestedMode)
        var fallbackTag = ""

        if (pair == null && requestedMode != PoseEngineMode.YOLO26N_NCNN_320) {
            pair = YoloModelManager.resolveLocalModelPair(appContext, PoseEngineMode.YOLO26N_NCNN_320)
            activeMode = PoseEngineMode.YOLO26N_NCNN_320
            fallbackTag = " [待下载·暂用极速320]"
        }
        if (pair == null) {
            return false to "${requestedMode.shortTitle} (模型未就绪)"
        }

        val cacheDir = YoloModelManager.modelsDir(appContext).absolutePath
        val loaded = NcnnYoloPoseNative.nativeLoadModel(
            paramPath = pair.paramFile.absolutePath,
            binPath = pair.binFile.absolutePath,
            cacheDirPath = cacheDir,
            targetSize = pair.inputSize,
            preferVulkan = config.preferVulkanGpu,
            numThreads = 0
        )
        if (!loaded) {
            return false to "${activeMode.shortTitle} (ncnn加载失败)"
        }
        val hwLabel = NcnnYoloPoseNative.nativeGetActiveHardwareLabel()
        return true to "${activeMode.shortTitle} · $hwLabel$fallbackTag"
    }

    fun prepareYuv420(
        imageProxy: ImageProxy,
        isFront: Boolean,
        zoomRatio: Float,
        panX: Float,
        panY: Float
    ): Boolean {
        val planes = imageProxy.planes
        if (planes.size < 3) return false
        val rot = ((imageProxy.imageInfo.rotationDegrees % 360) + 360) % 360
        return NcnnYoloPoseNative.nativePrepareYuv420(
            yBuf = planes[0].buffer,
            yRowStride = planes[0].rowStride,
            uBuf = planes[1].buffer,
            vBuf = planes[2].buffer,
            uvRowStride = planes[1].rowStride,
            uvPixelStride = planes[1].pixelStride,
            srcW = imageProxy.width,
            srcH = imageProxy.height,
            rotationDeg = rot,
            isFrontMirror = isFront,
            zoomRatio = zoomRatio,
            panX = panX,
            panY = panY
        )
    }

    fun renderYuvRoiToBitmap(
        imageProxy: ImageProxy,
        isFront: Boolean,
        zoomRatio: Float,
        panX: Float,
        panY: Float,
        dstBitmap: Bitmap
    ): Boolean {
        if (!NcnnYoloPoseNative.isAvailable) return false
        val planes = imageProxy.planes
        if (planes.size < 3) return false
        val rot = ((imageProxy.imageInfo.rotationDegrees % 360) + 360) % 360
        return NcnnYoloPoseNative.nativeRenderYuvRoiToBitmap(
            yBuf = planes[0].buffer,
            yRowStride = planes[0].rowStride,
            uBuf = planes[1].buffer,
            vBuf = planes[2].buffer,
            uvRowStride = planes[1].rowStride,
            uvPixelStride = planes[1].pixelStride,
            srcW = imageProxy.width,
            srcH = imageProxy.height,
            rotationDeg = rot,
            isFrontMirror = isFront,
            zoomRatio = zoomRatio,
            panX = panX,
            panY = panY,
            dstBitmap = dstBitmap
        )
    }

    fun detectPrepared(
        config: PoseDetectionConfig,
        modelBanner: String,
        tStartNs: Long
    ): Triple<YoloPoseDetection?, String, Long> {
        val boxThresh = (config.minLandmarkConfidence * 0.62f).coerceIn(0.20f, 0.72f)
        val ok = NcnnYoloPoseNative.nativeRunPrepared(
            out56 = reusableOut56,
            boxConfThresh = boxThresh,
            iouThresh = 0.45f
        )
        val inferMs = ((System.nanoTime() - tStartNs) / 1_000_000L).coerceAtLeast(1L)
        if (!ok) {
            return Triple(null, modelBanner, inferMs)
        }
        val det = YoloPoseOutputParser.fromCompact56Array(reusableOut56)
        return Triple(det, modelBanner, inferMs)
    }

    fun detectBitmap(
        bitmap: Bitmap,
        config: PoseDetectionConfig,
        roiRect: IntCropRect? = null
    ): Triple<YoloPoseDetection?, String, Long> {
        val (ready, modelBanner) = ensureModelReady(config)
        if (!ready) {
            return Triple(null, modelBanner, 0L)
        }

        val boxThresh = (config.minLandmarkConfidence * 0.62f).coerceIn(0.20f, 0.72f)
        val rLeft = roiRect?.left ?: 0
        val rTop = roiRect?.top ?: 0
        val rW = roiRect?.width ?: bitmap.width
        val rH = roiRect?.height ?: bitmap.height

        val t0 = System.nanoTime()
        val raw = NcnnYoloPoseNative.nativeDetectBitmapRoi(
            bitmap = bitmap,
            roiLeft = rLeft,
            roiTop = rTop,
            roiWidth = rW,
            roiHeight = rH,
            boxConfThresh = boxThresh,
            iouThresh = 0.45f
        )
        val inferMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)

        if (raw == null || raw.size < 56) {
            return Triple(null, modelBanner, inferMs)
        }

        val det = YoloPoseOutputParser.fromCompact56Array(raw)
        return Triple(det, modelBanner, inferMs)
    }

    override fun close() {
        // Keep mmap-loaded ncnn::Net & compiled Vulkan PipelineCache warm in native memory
        // so reopening BabyPoseMonitorDialog takes 0ms with zero shader recompilation!
    }
}

/**
 * Pure-JVM YOLO Pose tensor & compact-array parser (shared with unit tests).
 */
object YoloPoseOutputParser {

    fun fromCompact56Array(arr: FloatArray): YoloPoseDetection? {
        if (arr.size < 56) return null
        val box = NormalizedRect(
            left = arr[0].coerceIn(0f, 1f),
            top = arr[1].coerceIn(0f, 1f),
            right = arr[2].coerceIn(0f, 1f),
            bottom = arr[3].coerceIn(0f, 1f)
        )
        val boxScore = arr[4].coerceIn(0f, 1f)
        fun pt(idx: Int): NormalizedPoint = NormalizedPoint(
            x = arr[5 + idx * 3 + 0].coerceIn(0f, 1f),
            y = arr[5 + idx * 3 + 1].coerceIn(0f, 1f),
            confidence = arr[5 + idx * 3 + 2].coerceIn(0f, 1f),
            z = 0f
        )
        return YoloPoseDetection(
            box = box,
            boxScore = boxScore,
            keypoints = PoseKeypoints(
                nose = pt(0),
                leftEye = pt(1),
                rightEye = pt(2),
                leftEar = pt(3),
                rightEar = pt(4),
                leftShoulder = pt(5),
                rightShoulder = pt(6),
                leftElbow = pt(7),
                rightElbow = pt(8),
                leftWrist = pt(9),
                rightWrist = pt(10),
                leftHip = pt(11),
                rightHip = pt(12),
                leftKnee = pt(13),
                rightKnee = pt(14),
                leftAnkle = pt(15),
                rightAnkle = pt(16)
            )
        )
    }

    private data class RawCandidate(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val score: Float,
        val kpts: FloatArray
    )

    fun decodeBestPerson(
        outBuffer: FloatBuffer,
        shape: LongArray,
        inputSize: Int,
        padX: Float,
        padY: Float,
        scaledW: Float,
        scaledH: Float,
        boxConfThreshold: Float = 0.25f,
        iouThreshold: Float = 0.45f
    ): YoloPoseDetection? {
        if (shape.size != 3) return null
        val dim1 = shape[1].toInt()
        val dim2 = shape[2].toInt()
        outBuffer.rewind()

        val candidates = ArrayList<RawCandidate>(16)

        when {
            dim2 == 57 -> {
                for (i in 0 until dim1) {
                    val base = i * 57
                    val score = outBuffer.get(base + 4)
                    val clsId = outBuffer.get(base + 5).roundToInt()
                    if (clsId != 0 || score < boxConfThreshold) continue
                    val x1 = outBuffer.get(base + 0)
                    val y1 = outBuffer.get(base + 1)
                    val x2 = outBuffer.get(base + 2)
                    val y2 = outBuffer.get(base + 3)
                    if (x2 <= x1 + 2f || y2 <= y1 + 2f) continue
                    val kp = FloatArray(51)
                    for (k in 0 until 51) kp[k] = outBuffer.get(base + 6 + k)
                    candidates.add(RawCandidate(x1, y1, x2, y2, score, kp))
                }
            }
            dim1 == 56 && dim2 > 56 -> {
                val m = dim2
                val scoreOffset = 4 * m
                val rawList = ArrayList<RawCandidate>(32)
                for (j in 0 until m) {
                    val score = outBuffer.get(scoreOffset + j)
                    if (score < boxConfThreshold) continue
                    val cx = outBuffer.get(0 * m + j)
                    val cy = outBuffer.get(1 * m + j)
                    val bw = outBuffer.get(2 * m + j)
                    val bh = outBuffer.get(3 * m + j)
                    if (bw <= 2f || bh <= 2f) continue
                    val kp = FloatArray(51)
                    for (k in 0 until 51) kp[k] = outBuffer.get((5 + k) * m + j)
                    rawList.add(RawCandidate(cx - bw * 0.5f, cy - bh * 0.5f, cx + bw * 0.5f, cy + bh * 0.5f, score, kp))
                }
                candidates.addAll(nms(rawList, iouThreshold))
            }
        }

        if (candidates.isEmpty()) return null
        val halfIn = inputSize * 0.5f
        val best = candidates.maxByOrNull { c ->
            val cx = (c.x1 + c.x2) * 0.5f
            val cy = (c.y1 + c.y2) * 0.5f
            val centerDist = hypot((cx - halfIn) / halfIn, (cy - halfIn) / halfIn).coerceIn(0f, 1.4f)
            val areaFrac = (((c.x2 - c.x1) * (c.y2 - c.y1)) / (inputSize * inputSize).toFloat()).coerceIn(0f, 1f).coerceAtLeast(0.01f)
            c.score * (1.0f - 0.22f * centerDist) * (0.75f + 0.25f * sqrt(areaFrac))
        } ?: return null

        val safeW = scaledW.coerceAtLeast(1f)
        val safeH = scaledH.coerceAtLeast(1f)
        val compact = FloatArray(56)
        compact[0] = ((best.x1 - padX) / safeW).coerceIn(0f, 1f)
        compact[1] = ((best.y1 - padY) / safeH).coerceIn(0f, 1f)
        compact[2] = ((best.x2 - padX) / safeW).coerceIn(0f, 1f)
        compact[3] = ((best.y2 - padY) / safeH).coerceIn(0f, 1f)
        compact[4] = best.score.coerceIn(0f, 1f)
        for (k in 0 until 17) {
            compact[5 + k * 3 + 0] = ((best.kpts[k * 3 + 0] - padX) / safeW).coerceIn(0f, 1f)
            compact[5 + k * 3 + 1] = ((best.kpts[k * 3 + 1] - padY) / safeH).coerceIn(0f, 1f)
            compact[5 + k * 3 + 2] = best.kpts[k * 3 + 2].coerceIn(0f, 1f)
        }
        return fromCompact56Array(compact)
    }

    private fun nms(boxes: List<RawCandidate>, iouThreshold: Float): List<RawCandidate> {
        if (boxes.size <= 1) return boxes
        val sorted = boxes.sortedByDescending { it.score }
        val kept = ArrayList<RawCandidate>(min(8, sorted.size))
        for (cand in sorted) {
            if (kept.none { iou(cand, it) > iouThreshold }) {
                kept.add(cand)
                if (kept.size >= 8) break
            }
        }
        return kept
    }

    private fun iou(a: RawCandidate, b: RawCandidate): Float {
        val ix1 = max(a.x1, b.x1)
        val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2)
        val iy2 = min(a.y2, b.y2)
        val inter = (ix2 - ix1).coerceAtLeast(0f) * (iy2 - iy1).coerceAtLeast(0f)
        if (inter <= 0f) return 0f
        return inter / ((a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter + 1e-5f)
    }
}
