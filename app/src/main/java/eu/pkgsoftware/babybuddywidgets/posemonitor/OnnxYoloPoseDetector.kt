package eu.pkgsoftware.babybuddywidgets.posemonitor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import java.io.Closeable
import java.io.File
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
 * Pure-JVM zero-copy YOLO Pose tensor parser supporting:
 * 1. YOLO26-pose End-to-End NMS-Free `[1, 300, 57]` (RLE keypoint distribution head)
 * 2. YOLO11-pose / YOLOv8-pose Anchor-Free `[1, 56, 8400]` (and transposed `[1, 8400, 56]`) with IoU NMS
 */
object YoloPoseOutputParser {

    private data class RawCandidate(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val score: Float,
        val kpts: FloatArray // length 51: 17 * (x, y, conf) in model input pixel coords
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
            // Case 1: End-to-End NMS-Free YOLO26-pose -> [1, N, 57]
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
                    for (k in 0 until 51) {
                        kp[k] = outBuffer.get(base + 6 + k)
                    }
                    candidates.add(RawCandidate(x1, y1, x2, y2, score, kp))
                }
            }

            // Case 2: Classic Anchor-Free YOLO11-pose / YOLOv8-pose -> [1, 56, M]
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
                    for (k in 0 until 51) {
                        kp[k] = outBuffer.get((5 + k) * m + j)
                    }
                    rawList.add(
                        RawCandidate(
                            x1 = cx - bw * 0.5f,
                            y1 = cy - bh * 0.5f,
                            x2 = cx + bw * 0.5f,
                            y2 = cy + bh * 0.5f,
                            score = score,
                            kpts = kp
                        )
                    )
                }
                candidates.addAll(nms(rawList, iouThreshold))
            }

            // Case 3: Transposed Anchor-Free -> [1, M, 56]
            dim2 == 56 && dim1 > 56 -> {
                val rawList = ArrayList<RawCandidate>(32)
                for (j in 0 until dim1) {
                    val base = j * 56
                    val score = outBuffer.get(base + 4)
                    if (score < boxConfThreshold) continue
                    val cx = outBuffer.get(base + 0)
                    val cy = outBuffer.get(base + 1)
                    val bw = outBuffer.get(base + 2)
                    val bh = outBuffer.get(base + 3)
                    if (bw <= 2f || bh <= 2f) continue
                    val kp = FloatArray(51)
                    for (k in 0 until 51) {
                        kp[k] = outBuffer.get(base + 5 + k)
                    }
                    rawList.add(
                        RawCandidate(
                            x1 = cx - bw * 0.5f,
                            y1 = cy - bh * 0.5f,
                            x2 = cx + bw * 0.5f,
                            y2 = cy + bh * 0.5f,
                            score = score,
                            kpts = kp
                        )
                    )
                }
                candidates.addAll(nms(rawList, iouThreshold))
            }
        }

        if (candidates.isEmpty()) return null

        // Select the primary crib subject: weight confidence + central ROI proximity + reasonable subject scale
        val halfIn = inputSize * 0.5f
        val best = candidates.maxByOrNull { c ->
            val cx = (c.x1 + c.x2) * 0.5f
            val cy = (c.y1 + c.y2) * 0.5f
            val centerDist = hypot((cx - halfIn) / halfIn, (cy - halfIn) / halfIn).coerceIn(0f, 1.4f)
            val centerWeight = 1.0f - 0.22f * centerDist
            val areaFrac = (((c.x2 - c.x1) * (c.y2 - c.y1)) / (inputSize * inputSize).toFloat()).coerceIn(0.01f, 1f)
            c.score * centerWeight * (0.75f + 0.25f * sqrt(areaFrac))
        } ?: return null

        val safeW = scaledW.coerceAtLeast(1f)
        val safeH = scaledH.coerceAtLeast(1f)

        fun unpadX(px: Float): Float = ((px - padX) / safeW).coerceIn(0f, 1f)
        fun unpadY(py: Float): Float = ((py - padY) / safeH).coerceIn(0f, 1f)

        fun kpAt(idx: Int): NormalizedPoint {
            val rawX = best.kpts[idx * 3]
            val rawY = best.kpts[idx * 3 + 1]
            val rawC = best.kpts[idx * 3 + 2].coerceIn(0f, 1f)
            return NormalizedPoint(
                x = unpadX(rawX),
                y = unpadY(rawY),
                confidence = rawC,
                z = 0f
            )
        }

        val box = NormalizedRect(
            left = unpadX(best.x1),
            top = unpadY(best.y1),
            right = unpadX(best.x2),
            bottom = unpadY(best.y2)
        )

        // COCO 17 Keypoint Standard Mapping:
        // 0:nose, 1:left_eye, 2:right_eye, 3:left_ear, 4:right_ear,
        // 5:left_shoulder, 6:right_shoulder, 7:left_elbow, 8:right_elbow,
        // 9:left_wrist, 10:right_wrist, 11:left_hip, 12:right_hip,
        // 13:left_knee, 14:right_knee, 15:left_ankle, 16:right_ankle
        val keypoints = PoseKeypoints(
            nose = kpAt(0),
            leftEye = kpAt(1),
            rightEye = kpAt(2),
            leftEar = kpAt(3),
            rightEar = kpAt(4),
            leftShoulder = kpAt(5),
            rightShoulder = kpAt(6),
            leftElbow = kpAt(7),
            rightElbow = kpAt(8),
            leftWrist = kpAt(9),
            rightWrist = kpAt(10),
            leftHip = kpAt(11),
            rightHip = kpAt(12),
            leftKnee = kpAt(13),
            rightKnee = kpAt(14),
            leftAnkle = kpAt(15),
            rightAnkle = kpAt(16)
        )

        return YoloPoseDetection(
            box = box,
            boxScore = best.score.coerceIn(0f, 1f),
            keypoints = keypoints
        )
    }

    private fun nms(boxes: List<RawCandidate>, iouThreshold: Float): List<RawCandidate> {
        if (boxes.size <= 1) return boxes
        val sorted = boxes.sortedByDescending { it.score }
        val kept = ArrayList<RawCandidate>(min(12, sorted.size))
        for (cand in sorted) {
            var suppressed = false
            for (prev in kept) {
                if (iou(cand, prev) > iouThreshold) {
                    suppressed = true
                    break
                }
            }
            if (!suppressed) {
                kept.add(cand)
                if (kept.size >= 12) break
            }
        }
        return kept
    }

    private fun iou(a: RawCandidate, b: RawCandidate): Float {
        val ix1 = max(a.x1, b.x1)
        val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2)
        val iy2 = min(a.y2, b.y2)
        val iw = (ix2 - ix1).coerceAtLeast(0f)
        val ih = (iy2 - iy1).coerceAtLeast(0f)
        val inter = iw * ih
        if (inter <= 0f) return 0f
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        return inter / (areaA + areaB - inter + 1e-5f)
    }
}

/**
 * Android ONNX Runtime (`onnxruntime-android` + XNNPACK SIMD) YOLO Pose Detector.
 * Supports seamless hot-swapping between bundled `yolo26n-pose.onnx` and cloud-downloaded `yolo11s/11m/custom` models.
 */
class OnnxYoloPoseDetector(private val appContext: Context) : Closeable {
    companion object {
        private const val TAG = "OnnxYoloPoseDetector"
        private const val DEFAULT_INPUT_SIZE = 640
    }

    private val ortEnv: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var ortSession: OrtSession? = null
    private var loadedModelPath: String? = null
    private var loadedFileModifiedMs: Long = -1L
    private var inputName: String = "images"
    private var inputSize: Int = DEFAULT_INPUT_SIZE

    // Reusable pre-allocated buffers for zero per-frame GC pressure
    private var letterboxBitmap: Bitmap? = null
    private var letterboxCanvas: Canvas? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstRect = RectF()
    private var argbPixels: IntArray = IntArray(0)
    private var inputFloatBuffer: FloatBuffer? = null

    @Synchronized
    private fun ensureSession(modelFile: File): OrtSession? {
        val absPath = modelFile.absolutePath
        val lastMod = modelFile.lastModified()
        if (ortSession != null && loadedModelPath == absPath && loadedFileModifiedMs == lastMod) {
            return ortSession
        }

        closeSessionOnly()

        return try {
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                setIntraOpNumThreads(threads)
                try {
                    addXnnpack(emptyMap())
                } catch (_: Throwable) {
                    // Fallback cleanly to multi-threaded ORT CPU kernel if XNNPACK EP is unavailable
                }
            }
            val session = ortEnv.createSession(absPath, opts)
            val firstInput = session.inputInfo.entries.firstOrNull()
            inputName = firstInput?.key ?: "images"
            val tensorShape = (firstInput?.value?.info as? TensorInfo)?.shape
            val detectedDim = tensorShape?.getOrNull(2)?.toInt()?.takeIf { it in 192..1280 } ?: DEFAULT_INPUT_SIZE
            inputSize = detectedDim

            ortSession = session
            loadedModelPath = absPath
            loadedFileModifiedMs = lastMod
            Log.i(TAG, "Loaded YOLO ONNX model: ${modelFile.name} (${inputSize}x${inputSize})")
            session
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize OrtSession for ${modelFile.name}: ${t.message}", t)
            null
        }
    }

    @Synchronized
    fun detectRoi(
        roiBitmap: Bitmap,
        config: PoseDetectionConfig
    ): Triple<YoloPoseDetection?, String, Long> {
        val requestedMode = config.engineMode
        var activeMode = requestedMode
        var modelFile = YoloModelManager.resolveLocalModelFile(appContext, requestedMode)

        // If user selected a cloud model that hasn't been downloaded yet, gracefully use bundled YOLO26n!
        var fallbackNote = ""
        if (modelFile == null && requestedMode != PoseEngineMode.YOLO26N_BUNDLED) {
            modelFile = YoloModelManager.resolveLocalModelFile(appContext, PoseEngineMode.YOLO26N_BUNDLED)
            activeMode = PoseEngineMode.YOLO26N_BUNDLED
            fallbackNote = " [待下载${requestedMode.shortTitle}·暂用内置YOLO26n]"
        }

        if (modelFile == null) {
            return Triple(null, "${requestedMode.modelDisplayName} (模型未就绪)", 0L)
        }

        val session = ensureSession(modelFile)
            ?: return Triple(null, "${activeMode.modelDisplayName} (加载失败)", 0L)

        val sz = inputSize
        if (letterboxBitmap == null || letterboxBitmap?.width != sz) {
            letterboxBitmap?.recycle()
            val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
            letterboxBitmap = bmp
            letterboxCanvas = Canvas(bmp)
            argbPixels = IntArray(sz * sz)
            inputFloatBuffer = FloatBuffer.allocate(3 * sz * sz)
        }

        val lbBmp = letterboxBitmap!!
        val lbCanvas = letterboxCanvas!!
        val floatBuf = inputFloatBuffer!!

        val tStartNs = System.nanoTime()

        // 1. Aspect-preserving Letterbox into sz x sz with standard YOLO gray (114, 114, 114)
        val srcW = roiBitmap.width.toFloat().coerceAtLeast(1f)
        val srcH = roiBitmap.height.toFloat().coerceAtLeast(1f)
        val scale = min(sz / srcW, sz / srcH)
        val newW = srcW * scale
        val newH = srcH * scale
        val padX = (sz - newW) * 0.5f
        val padY = (sz - newH) * 0.5f

        lbCanvas.drawColor(Color.rgb(114, 114, 114))
        dstRect.set(padX, padY, padX + newW, padY + newH)
        lbCanvas.drawBitmap(roiBitmap, null, dstRect, bitmapPaint)

        // 2. Pack planar NCHW float32 [1, 3, sz, sz] normalized to [0..1]
        val planeLen = sz * sz
        lbBmp.getPixels(argbPixels, 0, sz, 0, 0, sz, sz)
        val rawArr = floatBuf.array()
        val gOffset = planeLen
        val bOffset = planeLen * 2
        val inv255 = 1f / 255f

        for (i in 0 until planeLen) {
            val px = argbPixels[i]
            rawArr[i] = ((px shr 16) and 0xFF) * inv255
            rawArr[gOffset + i] = ((px shr 8) and 0xFF) * inv255
            rawArr[bOffset + i] = (px and 0xFF) * inv255
        }
        floatBuf.rewind()

        // 3. Run ONNX Runtime + XNNPACK inference & zero-copy FloatBuffer decode
        val boxThresh = (config.minLandmarkConfidence * 0.62f).coerceIn(0.20f, 0.72f)
        val inShape = longArrayOf(1L, 3L, sz.toLong(), sz.toLong())

        val detection = OnnxTensor.createTensor(ortEnv, floatBuf, inShape).use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { result ->
                val outTensor = result.get(0) as? OnnxTensor ?: return@use null
                val outShape = outTensor.info.shape
                YoloPoseOutputParser.decodeBestPerson(
                    outBuffer = outTensor.floatBuffer,
                    shape = outShape,
                    inputSize = sz,
                    padX = padX,
                    padY = padY,
                    scaledW = newW,
                    scaledH = newH,
                    boxConfThreshold = boxThresh
                )
            }
        }

        val inferMs = ((System.nanoTime() - tStartNs) / 1_000_000L).coerceAtLeast(1L)
        return Triple(detection, activeMode.modelDisplayName + fallbackNote, inferMs)
    }

    private fun closeSessionOnly() {
        try {
            ortSession?.close()
        } catch (_: Throwable) {
        }
        ortSession = null
        loadedModelPath = null
        loadedFileModifiedMs = -1L
    }

    @Synchronized
    override fun close() {
        closeSessionOnly()
        letterboxBitmap?.recycle()
        letterboxBitmap = null
        letterboxCanvas = null
    }
}
