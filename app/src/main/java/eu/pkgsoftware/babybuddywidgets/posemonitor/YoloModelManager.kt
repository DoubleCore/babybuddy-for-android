package eu.pkgsoftware.babybuddywidgets.posemonitor

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Manages bundled offline YOLO Pose ONNX models (`assets/models/yolo26n-pose.onnx`)
 * and on-demand cloud-downloaded YOLO Pose models (`yolo11s-pose.onnx`, `yolo11m-pose.onnx`, or custom URL)
 * with automatic domestic mirror fallback (`hf-mirror.com`) and atomic file verification.
 */
object YoloModelManager {
    private const val TAG = "YoloModelManager"
    const val BUNDLED_ASSET_PATH = "models/yolo26n-pose.onnx"
    private const val MODELS_DIR_NAME = "yolo_models"
    private const val MIN_VALID_ONNX_BYTES = 512L * 1024L

    data class CloudModelSpec(
        val fileName: String,
        val approxSizeMb: Float,
        val candidateUrls: List<String>
    )

    private val CLOUD_SPECS = mapOf(
        PoseEngineMode.YOLO11S_CLOUD to CloudModelSpec(
            fileName = "yolo11s-pose.onnx",
            approxSizeMb = 38.1f,
            candidateUrls = listOf(
                "https://hf-mirror.com/unileon-robotics/YOLO11-ONNX/resolve/main/yolo11s-pose.onnx",
                "https://huggingface.co/unileon-robotics/YOLO11-ONNX/resolve/main/yolo11s-pose.onnx"
            )
        ),
        PoseEngineMode.YOLO11M_CLOUD to CloudModelSpec(
            fileName = "yolo11m-pose.onnx",
            approxSizeMb = 80.0f,
            candidateUrls = listOf(
                "https://hf-mirror.com/unileon-robotics/YOLO11-ONNX/resolve/main/yolo11m-pose.onnx",
                "https://huggingface.co/unileon-robotics/YOLO11-ONNX/resolve/main/yolo11m-pose.onnx"
            )
        )
    )

    fun modelsDir(context: Context): File {
        val dir = File(context.filesDir, MODELS_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun targetModelFile(context: Context, mode: PoseEngineMode): File {
        val name = when (mode) {
            PoseEngineMode.YOLO26N_BUNDLED -> "yolo26n-pose.onnx"
            PoseEngineMode.YOLO11S_CLOUD -> "yolo11s-pose.onnx"
            PoseEngineMode.YOLO11M_CLOUD -> "yolo11m-pose.onnx"
            PoseEngineMode.YOLO_CUSTOM_CLOUD -> "custom-yolo-pose.onnx"
            else -> "yolo26n-pose.onnx"
        }
        return File(modelsDir(context), name)
    }

    /**
     * Ensures the requested YOLO `.onnx` file is ready on local filesystem and returns it,
     * or returns null if a cloud-only model has not been downloaded yet.
     */
    @Synchronized
    fun resolveLocalModelFile(context: Context, mode: PoseEngineMode): File? {
        if (!mode.isYolo) return null
        val target = targetModelFile(context, mode)
        if (mode == PoseEngineMode.YOLO26N_BUNDLED) {
            if (target.exists() && target.length() >= MIN_VALID_ONNX_BYTES) {
                return target
            }
            return try {
                val tmp = File(target.parentFile, "${target.name}.tmp")
                context.assets.open(BUNDLED_ASSET_PATH).use { input ->
                    FileOutputStream(tmp).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
                if (tmp.length() >= MIN_VALID_ONNX_BYTES) {
                    if (target.exists()) target.delete()
                    tmp.renameTo(target)
                    target
                } else {
                    tmp.delete()
                    null
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to unpack bundled $BUNDLED_ASSET_PATH", t)
                null
            }
        }

        return if (target.exists() && target.length() >= MIN_VALID_ONNX_BYTES) target else null
    }

    fun isModelReady(context: Context, mode: PoseEngineMode): Boolean {
        if (!mode.isYolo) return true
        if (mode == PoseEngineMode.YOLO26N_BUNDLED) {
            val f = targetModelFile(context, mode)
            if (f.exists() && f.length() >= MIN_VALID_ONNX_BYTES) return true
            return try {
                context.assets.open(BUNDLED_ASSET_PATH).use { true }
            } catch (_: Throwable) {
                false
            }
        }
        val f = targetModelFile(context, mode)
        return f.exists() && f.length() >= MIN_VALID_ONNX_BYTES
    }

    fun modelStatusText(context: Context, mode: PoseEngineMode): String {
        if (!mode.isYolo) return "内置 Google ML Kit 引擎 · 已就绪"
        if (mode == PoseEngineMode.YOLO26N_BUNDLED) {
            val f = targetModelFile(context, mode)
            val mb = if (f.exists()) f.length() / (1024f * 1024f) else 11.6f
            return "✅ 本地内置已就绪 (${"%.1f".format(Locale.US, mb)} MB · 纯离线免下载)"
        }
        val f = targetModelFile(context, mode)
        if (f.exists() && f.length() >= MIN_VALID_ONNX_BYTES) {
            val mb = f.length() / (1024f * 1024f)
            return "✅ 云端模型已缓存到本地 (${"%.1f".format(Locale.US, mb)} MB)"
        }
        val spec = CLOUD_SPECS[mode]
        return if (spec != null) {
            "☁️ 尚未下载 (约 ${"%.0f".format(Locale.US, spec.approxSizeMb)} MB · 点击下方一键云端拉取)"
        } else {
            "☁️ 尚未下载自定义 .onnx 模型 · 请输入 URL 后点击下载"
        }
    }

    fun deleteDownloadedModel(context: Context, mode: PoseEngineMode): Boolean {
        if (mode == PoseEngineMode.YOLO26N_BUNDLED || !mode.isYolo) return false
        val f = targetModelFile(context, mode)
        return f.exists() && f.delete()
    }

    /**
     * Downloads a cloud YOLO `.onnx` model on the calling background thread,
     * following cross-domain HTTP 301/302/307/308 redirects and falling back across mirrors.
     */
    fun downloadCloudModelBlocking(
        context: Context,
        mode: PoseEngineMode,
        customUrl: String = "",
        onProgress: (percent: Int, downloadedMb: Float, totalMb: Float, sourceHost: String) -> Unit
    ): Result<File> {
        val urls: List<String> = when (mode) {
            PoseEngineMode.YOLO_CUSTOM_CLOUD -> {
                val trimmed = customUrl.trim()
                if (trimmed.isEmpty() || !(trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
                    return Result.failure(IllegalArgumentException("请输入合法的 http/https .onnx 下载链接"))
                }
                listOf(trimmed)
            }
            else -> CLOUD_SPECS[mode]?.candidateUrls
                ?: return Result.failure(IllegalArgumentException("该模型为本地内置，无需下载"))
        }

        val dstFile = targetModelFile(context, mode)
        val partFile = File(dstFile.parentFile, "${dstFile.name}.part")
        var lastError: Throwable? = null

        for (rawUrl in urls) {
            try {
                if (partFile.exists()) partFile.delete()
                val hostLabel = try { URL(rawUrl).host } catch (_: Throwable) { "cloud" }
                onProgress(0, 0f, 0f, hostLabel)

                downloadWithRedirects(rawUrl, partFile) { readBytes, totalBytes ->
                    val dlMb = readBytes / (1024f * 1024f)
                    val totMb = if (totalBytes > 0) totalBytes / (1024f * 1024f) else 0f
                    val pct = if (totalBytes > 0) ((readBytes * 100L) / totalBytes).toInt().coerceIn(0, 99) else -1
                    onProgress(pct, dlMb, totMb, hostLabel)
                }

                if (partFile.length() < MIN_VALID_ONNX_BYTES) {
                    throw IllegalStateException("下载文件过小 (${partFile.length()} B)，非有效 ONNX 权重")
                }

                if (dstFile.exists()) dstFile.delete()
                if (!partFile.renameTo(dstFile)) {
                    partFile.copyTo(dstFile, overwrite = true)
                    partFile.delete()
                }
                val finalMb = dstFile.length() / (1024f * 1024f)
                onProgress(100, finalMb, finalMb, hostLabel)
                return Result.success(dstFile)
            } catch (t: Throwable) {
                Log.w(TAG, "Mirror download failed ($rawUrl): ${t.message}")
                lastError = t
                if (partFile.exists()) partFile.delete()
            }
        }

        return Result.failure(lastError ?: IllegalStateException("所有云端镜像下载失败"))
    }

    private fun downloadWithRedirects(
        initialUrl: String,
        outFile: File,
        onBytes: (read: Long, total: Long) -> Unit
    ) {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects <= 8) {
            val urlObj = URL(currentUrl)
            val conn = (urlObj.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 12_000
                readTimeout = 25_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "BabyBuddy-PoseMonitor/3.0")
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IllegalStateException("重定向缺少 Location 头 ($code)")
                currentUrl = URL(urlObj, loc).toString()
                redirects++
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IllegalStateException("HTTP $code (${urlObj.host})")
            }

            val total = conn.contentLengthLong
            BufferedInputStream(conn.inputStream, 64 * 1024).use { input ->
                FileOutputStream(outFile).use { output ->
                    val buf = ByteArray(32 * 1024)
                    var readTotal = 0L
                    var lastNotifyMs = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        readTotal += n
                        val now = System.currentTimeMillis()
                        if (now - lastNotifyMs >= 120L) {
                            lastNotifyMs = now
                            onBytes(readTotal, total)
                        }
                    }
                    output.flush()
                    onBytes(readTotal, total)
                }
            }
            conn.disconnect()
            return
        }
        throw IllegalStateException("重定向次数过多 (>8)")
    }
}
