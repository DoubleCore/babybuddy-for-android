package eu.pkgsoftware.babybuddywidgets.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.math.max

/**
 * Manages app-private image attachments for child avatars (`avatars/`) and note photos (`notes/`).
 * Stores portable relative paths (e.g. `avatars/abc.jpg`, `notes/xyz.jpg`) in SQLite so ZIP backups
 * restore cleanly across different devices.
 */
class LocalMediaStorage(val mediaRootDir: File) {

    private var appContext: Context? = null

    val avatarsDir: File = File(mediaRootDir, SUBDIR_AVATARS).apply { mkdirs() }
    val notesDir: File = File(mediaRootDir, SUBDIR_NOTES).apply { mkdirs() }

    constructor(context: Context) : this(File(context.applicationContext.filesDir, "media")) {
        this.appContext = context.applicationContext
    }

    fun resolveFile(storedPath: String?): File? {
        if (storedPath.isNullOrBlank()) return null
        val trimmed = storedPath.trim()
        val candidate = if (File(trimmed).isAbsolute) {
            File(trimmed)
        } else {
            File(mediaRootDir, trimmed)
        }
        return candidate.takeIf { it.exists() && it.isFile }
    }

    fun resolveMediaFile(storedPath: String?): File? = resolveFile(storedPath)

    fun importImageFromUri(sourceUri: Uri, category: String = "note"): String? {
        val ctx = appContext ?: return null
        return if (category.lowercase().startsWith("avatar")) {
            importAvatarFromUri(ctx, sourceUri)
        } else {
            importNoteImageFromUri(ctx, sourceUri)
        }
    }

    fun importAvatarFromUri(context: Context, sourceUri: Uri): String? {
        return importAndCompressFromUri(context, sourceUri, SUBDIR_AVATARS, maxDimension = 512, jpegQuality = 86)
    }

    fun importNoteImageFromUri(context: Context, sourceUri: Uri): String? {
        return importAndCompressFromUri(context, sourceUri, SUBDIR_NOTES, maxDimension = 1600, jpegQuality = 85)
    }

    fun writeRawMediaBytes(subdir: String, fileName: String, bytes: ByteArray): String {
        val safeSubdir = if (subdir == SUBDIR_AVATARS) SUBDIR_AVATARS else SUBDIR_NOTES
        val safeName = File(fileName).name.ifEmpty { "${UUID.randomUUID()}.jpg" }
        val targetDir = File(mediaRootDir, safeSubdir).apply { mkdirs() }
        val targetFile = File(targetDir, safeName)
        FileOutputStream(targetFile).use { it.write(bytes) }
        return "$safeSubdir/$safeName"
    }

    fun writeRawRelativeFile(relativePath: String, bytes: ByteArray): String {
        val parts = relativePath.trim().replace('\\', '/').trimStart('/').split("/", limit = 2)
        return if (parts.size == 2) {
            writeRawMediaBytes(parts[0], parts[1], bytes)
        } else {
            writeRawMediaBytes(SUBDIR_NOTES, parts.firstOrNull().orEmpty(), bytes)
        }
    }

    fun clearAllMedia() {
        cleanupOrphanedMedia(emptySet())
    }

    fun deleteMediaIfOwned(storedPath: String?) {
        val file = resolveFile(storedPath) ?: return
        try {
            val canonicalRoot = mediaRootDir.canonicalPath
            val canonicalTarget = file.canonicalPath
            if (canonicalTarget.startsWith(canonicalRoot)) {
                file.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun cleanupOrphanedMedia(referencedRelativePaths: Set<String>) {
        val normalizedKeep = referencedRelativePaths.mapNotNull { raw ->
            val clean = raw.trim().replace('\\', '/')
            if (clean.isEmpty()) null else clean
        }.toSet()

        for (sub in listOf(SUBDIR_AVATARS, SUBDIR_NOTES)) {
            val dir = File(mediaRootDir, sub)
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile) continue
                val rel = "$sub/${f.name}"
                if (rel !in normalizedKeep && f.absolutePath !in normalizedKeep) {
                    f.delete()
                }
            }
        }
    }

    fun listAllRelativeMediaFiles(): List<Pair<String, File>> {
        val result = mutableListOf<Pair<String, File>>()
        for (sub in listOf(SUBDIR_AVATARS, SUBDIR_NOTES)) {
            val dir = File(mediaRootDir, sub)
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (f.isFile) {
                    result.add("$sub/${f.name}" to f)
                }
            }
        }
        return result
    }

    private fun importAndCompressFromUri(
        context: Context,
        sourceUri: Uri,
        subdir: String,
        maxDimension: Int,
        jpegQuality: Int,
    ): String? {
        val tempFile = File.createTempFile("import_img_", ".tmp", mediaRootDir.apply { mkdirs() })
        try {
            val resolver = context.contentResolver
            resolver.openInputStream(sourceUri)?.use { input ->
                copyStream(input, tempFile)
            } ?: return null

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tempFile.absolutePath, boundsOpts)
            val srcW = boundsOpts.outWidth
            val srcH = boundsOpts.outHeight
            if (srcW <= 0 || srcH <= 0) {
                // Fallback: store raw bytes if BitmapFactory is unavailable (e.g. unit test dummy image)
                val outName = "${UUID.randomUUID()}.jpg"
                val outFile = File(File(mediaRootDir, subdir).apply { mkdirs() }, outName)
                tempFile.copyTo(outFile, overwrite = true)
                return "$subdir/$outName"
            }

            var inSampleSize = 1
            while (max(srcW / inSampleSize, srcH / inSampleSize) > maxDimension * 2) {
                inSampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            val decoded = BitmapFactory.decodeFile(tempFile.absolutePath, decodeOpts) ?: return null
            val rotated = applyExifOrientation(tempFile, decoded)
            val scaled = scaleDownIfNeeded(rotated, maxDimension)

            val outName = "${UUID.randomUUID()}.jpg"
            val outDir = File(mediaRootDir, subdir).apply { mkdirs() }
            val outFile = File(outDir, outName)
            FileOutputStream(outFile).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, fos)
            }
            if (scaled !== rotated) scaled.recycle()
            if (rotated !== decoded) rotated.recycle()
            decoded.recycle()

            return "$subdir/$outName"
        } finally {
            tempFile.delete()
        }
    }

    private fun copyStream(input: InputStream, dest: File) {
        FileOutputStream(dest).use { out ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                out.write(buf, 0, r)
            }
        }
    }

    private fun applyExifOrientation(file: File, bitmap: Bitmap): Bitmap {
        return try {
            val exif = FileInputStream(file).use { ExifInterface(it) }
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                else -> return bitmap
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: Exception) {
            bitmap
        }
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val longest = max(w, h)
        if (longest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / longest.toFloat()
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, nw, nh, true)
    }

    companion object {
        const val SUBDIR_AVATARS = "avatars"
        const val SUBDIR_NOTES = "notes"
    }
}
