package eu.pkgsoftware.babybuddywidgets.posemonitor

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Manages Tencent ncnn FP16 YOLO Pose model pairs (`.param` + `.bin`):
 * - Bundled offline models: `yolo26n_pose_320` (5.6MB, 1.9 GFLOPs) & `yolo26n_pose_480` (5.7MB, 4.3 GFLOPs)
 * - Cloud downloadable S-tier model: `yolo11s_pose_480_ncnn.zip` (17.6MB, 9.9M params) with domestic mirror fallback
 * - Custom `.zip` (containing `.param` & `.bin`) download for user-finetuned infant models
 */
object YoloModelManager {
    private const val TAG = "YoloModelManager"
    private const val MODELS_DIR_NAME = "ncnn_models_v2"
    private const val MIN_VALID_PARAM_BYTES = 2_048L
    private const val MIN_VALID_BIN_BYTES = 512L * 1024L

    data class NcnnModelPair(
        val paramFile: File,
        val binFile: File,
        val inputSize: Int
    )

    private val YOLO11S_CLOUD_URLS = listOf(
        "https://ghfast.top/https://github.com/DoubleCore/babybuddy-for-android/releases/download/v3.2.0/yolo11s_pose_480_ncnn.zip",
        "https://gh-proxy.com/https://github.com/DoubleCore/babybuddy-for-android/releases/download/v3.2.0/yolo11s_pose_480_ncnn.zip",
        "https://github.com/DoubleCore/babybuddy-for-android/releases/download/v3.2.0/yolo11s_pose_480_ncnn.zip"
    )

    fun modelsDir(context: Context): File {
        val dir = File(context.filesDir, MODELS_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun prefixForMode(mode: PoseEngineMode): String = when (mode) {
        PoseEngineMode.YOLO26N_NCNN_320 -> "yolo26n_pose_320"
        PoseEngineMode.YOLO26N_NCNN_480 -> "yolo26n_pose_480"
        PoseEngineMode.YOLO11S_NCNN_CLOUD -> "yolo11s_pose_480"
        PoseEngineMode.YOLO_CUSTOM_NCNN -> "custom_yolo_pose_480"
        else -> "yolo26n_pose_320"
    }

    fun targetPairFiles(context: Context, mode: PoseEngineMode): Pair<File, File> {
        val dir = modelsDir(context)
        val pfx = prefixForMode(mode)
        return File(dir, "$pfx.param") to File(dir, "$pfx.bin")
    }

    private fun isValidPair(paramFile: File, binFile: File): Boolean {
        return paramFile.exists() && paramFile.length() >= MIN_VALID_PARAM_BYTES &&
            binFile.exists() && binFile.length() >= MIN_VALID_BIN_BYTES
    }

    @Synchronized
    fun resolveLocalModelPair(context: Context, mode: PoseEngineMode): NcnnModelPair? {
        if (!mode.isYolo) return null
        val (paramFile, binFile) = targetPairFiles(context, mode)

        if (mode == PoseEngineMode.YOLO26N_NCNN_320 || mode == PoseEngineMode.YOLO26N_NCNN_480) {
            if (isValidPair(paramFile, binFile)) {
                return NcnnModelPair(paramFile, binFile, mode.targetInputDim)
            }
            val pfx = prefixForMode(mode)
            return try {
                copyAssetAtomically(context, "models/$pfx.param", paramFile)
                copyAssetAtomically(context, "models/$pfx.bin", binFile)
                if (isValidPair(paramFile, binFile)) {
                    NcnnModelPair(paramFile, binFile, mode.targetInputDim)
                } else {
                    null
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to unpack bundled ncnn asset $pfx", t)
                null
            }
        }

        return if (isValidPair(paramFile, binFile)) {
            NcnnModelPair(paramFile, binFile, mode.targetInputDim)
        } else {
            null
        }
    }

    private fun copyAssetAtomically(context: Context, assetPath: String, dst: File) {
        val tmp = File(dst.parentFile, "${dst.name}.tmp")
        context.assets.open(assetPath).use { input ->
            FileOutputStream(tmp).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) {
            tmp.copyTo(dst, overwrite = true)
            tmp.delete()
        }
    }

    fun isModelReady(context: Context, mode: PoseEngineMode): Boolean {
        if (!mode.isYolo) return true
        if (mode == PoseEngineMode.YOLO26N_NCNN_320 || mode == PoseEngineMode.YOLO26N_NCNN_480) {
            return true
        }
        val (p, b) = targetPairFiles(context, mode)
        return isValidPair(p, b)
    }

    fun modelStatusText(context: Context, mode: PoseEngineMode): String {
        if (!mode.isYolo) return "内置 Google ML Kit 对照引擎 · 已就绪"
        val (p, b) = targetPairFiles(context, mode)
        if (mode == PoseEngineMode.YOLO26N_NCNN_320 || mode == PoseEngineMode.YOLO26N_NCNN_480) {
            val mb = if (b.exists()) (p.length() + b.length()) / (1024f * 1024f) else 5.66f
            return "⚡ 本地内置 ncnn FP16 已就绪 (${"%.1f".format(Locale.US, mb)} MB · Vulkan/NEON 免下载)"
        }
        if (isValidPair(p, b)) {
            val mb = (p.length() + b.length()) / (1024f * 1024f)
            return "✅ 云端 ncnn FP16 已缓存本地 (${"%.1f".format(Locale.US, mb)} MB)"
        }
        return if (mode == PoseEngineMode.YOLO11S_NCNN_CLOUD) {
            "☁️ 尚未下载 (约 17.6 MB · 点击下方一键云端拉取，未下载前自动用内置 YOLO26n 极速版)"
        } else {
            "☁️ 尚未下载自定义 ncnn .zip 包 · 请输入含 .param/.bin 的 zip 链接后下载"
        }
    }

    fun deleteDownloadedModel(context: Context, mode: PoseEngineMode): Boolean {
        if (mode == PoseEngineMode.YOLO26N_NCNN_320 || mode == PoseEngineMode.YOLO26N_NCNN_480 || !mode.isYolo) {
            return false
        }
        val (p, b) = targetPairFiles(context, mode)
        var deleted = false
        if (p.exists() && p.delete()) deleted = true
        if (b.exists() && b.delete()) deleted = true
        return deleted
    }

    fun downloadCloudModelBlocking(
        context: Context,
        mode: PoseEngineMode,
        customUrl: String = "",
        onProgress: (percent: Int, downloadedMb: Float, totalMb: Float, sourceHost: String) -> Unit
    ): Result<NcnnModelPair> {
        val urls: List<String> = when (mode) {
            PoseEngineMode.YOLO_CUSTOM_NCNN -> {
                val trimmed = customUrl.trim()
                if (trimmed.isEmpty() || !(trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
                    return Result.failure(IllegalArgumentException("请输入合法的 http/https .zip 下载链接"))
                }
                listOf(trimmed)
            }
            PoseEngineMode.YOLO11S_NCNN_CLOUD -> YOLO11S_CLOUD_URLS
            else -> return Result.failure(IllegalArgumentException("该模型已本地内置，无需下载"))
        }

        val (dstParam, dstBin) = targetPairFiles(context, mode)
        val zipTmp = File(modelsDir(context), "${prefixForMode(mode)}.download.zip")
        var lastErr: Throwable? = null

        for (rawUrl in urls) {
            try {
                if (zipTmp.exists()) zipTmp.delete()
                val hostLabel = try { URL(rawUrl).host } catch (_: Throwable) { "cloud" }
                onProgress(0, 0f, 0f, hostLabel)

                downloadWithRedirects(rawUrl, zipTmp) { readBytes, totalBytes ->
                    val dlMb = readBytes / (1024f * 1024f)
                    val totMb = if (totalBytes > 0) totalBytes / (1024f * 1024f) else 0f
                    val pct = if (totalBytes > 0) ((readBytes * 100L) / totalBytes).toInt().coerceIn(0, 98) else -1
                    onProgress(pct, dlMb, totMb, hostLabel)
                }

                extractParamAndBinFromZip(zipTmp, dstParam, dstBin)
                zipTmp.delete()

                if (!isValidPair(dstParam, dstBin)) {
                    throw IllegalStateException("压缩包内未找到有效的 .param 与 .bin 模型文件")
                }
                val finalMb = (dstParam.length() + dstBin.length()) / (1024f * 1024f)
                onProgress(100, finalMb, finalMb, hostLabel)
                return Result.success(NcnnModelPair(dstParam, dstBin, mode.targetInputDim))
            } catch (t: Throwable) {
                Log.w(TAG, "Cloud ncnn zip download failed ($rawUrl): ${t.message}")
                lastErr = t
                if (zipTmp.exists()) zipTmp.delete()
            }
        }

        return Result.failure(lastErr ?: IllegalStateException("所有云端镜像下载失败"))
    }

    private fun extractParamAndBinFromZip(zipFile: File, outParam: File, outBin: File) {
        val tmpParam = File(outParam.parentFile, "${outParam.name}.part")
        val tmpBin = File(outBin.parentFile, "${outBin.name}.part")
        var foundParam = false
        var foundBin = false

        ZipFile(zipFile).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val lower = entry.name.lowercase(Locale.US)
                when {
                    lower.endsWith(".param") && !lower.endsWith(".pnnx.param") -> {
                        zf.getInputStream(entry).use { inp ->
                            FileOutputStream(tmpParam).use { out -> inp.copyTo(out, 32 * 1024) }
                        }
                        foundParam = true
                    }
                    lower.endsWith(".bin") && !lower.endsWith(".pnnx.bin") -> {
                        zf.getInputStream(entry).use { inp ->
                            FileOutputStream(tmpBin).use { out -> inp.copyTo(out, 64 * 1024) }
                        }
                        foundBin = true
                    }
                }
            }
        }

        if (!foundParam || !foundBin) {
            tmpParam.delete()
            tmpBin.delete()
            throw IllegalStateException("ZIP 中缺少 .ncnn.param 或 .ncnn.bin")
        }
        if (outParam.exists()) outParam.delete()
        if (outBin.exists()) outBin.delete()
        tmpParam.renameTo(outParam)
        tmpBin.renameTo(outBin)
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
                connectTimeout = 10_000
                readTimeout = 25_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "BabyBuddy-NcnnPose/3.2")
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IllegalStateException("Redirect missing Location ($code)")
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
        throw IllegalStateException("Too many redirects (>8)")
    }
}
