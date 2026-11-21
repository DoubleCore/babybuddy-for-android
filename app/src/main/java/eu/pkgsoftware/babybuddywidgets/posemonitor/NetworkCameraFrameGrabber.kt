package eu.pkgsoftware.babybuddywidgets.posemonitor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Connects to a LAN IP camera (ESP32-CAM MJPEG stream `multipart/x-mixed-replace`
 * or HTTP JPEG snapshot endpoint such as `/shot.jpg` or `/capture`) for networked device debugging.
 */
class NetworkCameraFrameGrabber(
    private val onBitmapFrame: (Bitmap) -> Unit,
    private val onStatusMessage: (String) -> Unit
) {
    companion object {
        private const val TAG = "NetCamGrabber"
    }

    @Volatile
    private var running: Boolean = false
    private var executor: ExecutorService? = null

    fun start(streamUrl: String) {
        stop()
        val cleanUrl = streamUrl.trim()
        if (cleanUrl.isEmpty()) return
        running = true
        val exec = Executors.newSingleThreadExecutor()
        executor = exec
        exec.execute {
            runLoop(cleanUrl)
        }
    }

    fun stop() {
        running = false
        executor?.shutdownNow()
        executor = null
    }

    private fun runLoop(streamUrl: String) {
        while (running) {
            var conn: HttpURLConnection? = null
            try {
                onStatusMessage("正在连接网络摄像头: $streamUrl")
                conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 8000
                    doInput = true
                    setRequestProperty("User-Agent", "BabyBuddyPoseMonitor/1.0")
                }
                conn.connect()
                val contentType = (conn.contentType ?: "").lowercase()
                val input = BufferedInputStream(conn.inputStream, 32 * 1024)

                if (contentType.contains("multipart") || contentType.contains("mjpeg")) {
                    onStatusMessage("已连接 MJPEG 视频流")
                    readMjpegStream(input)
                } else {
                    // Single JPEG snapshot endpoint: poll at ~4 fps
                    val bytes = input.readBytes()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        onStatusMessage("网络快照在线 (${bmp.width}x${bmp.height})")
                        onBitmapFrame(bmp)
                    }
                    conn.disconnect()
                    conn = null
                    Thread.sleep(260L)
                }
            } catch (t: Throwable) {
                if (running) {
                    Log.w(TAG, "Network camera error: ${t.message}")
                    onStatusMessage("摄像头重连中 (${t.message ?: "连接超时"})")
                    try {
                        Thread.sleep(1500L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    /**
     * Scans byte stream for JPEG SOI (0xFF 0xD8) and EOI (0xFF 0xD9) markers.
     */
    private fun readMjpegStream(input: BufferedInputStream) {
        val frameBuffer = ByteArrayOutputStream(128 * 1024)
        var insideJpeg = false
        var prevByte = -1
        var lastEmitMs = 0L

        while (running) {
            val b = input.read()
            if (b == -1) break

            if (!insideJpeg) {
                if (prevByte == 0xFF && b == 0xD8) {
                    insideJpeg = true
                    frameBuffer.reset()
                    frameBuffer.write(0xFF)
                    frameBuffer.write(0xD8)
                }
            } else {
                frameBuffer.write(b)
                if (frameBuffer.size() > 2 * 1024 * 1024) {
                    // Corrupted oversized frame guard
                    insideJpeg = false
                    frameBuffer.reset()
                } else if (prevByte == 0xFF && b == 0xD9) {
                    insideJpeg = false
                    val now = System.currentTimeMillis()
                    if (now - lastEmitMs >= 200L) {
                        lastEmitMs = now
                        val jpegBytes = frameBuffer.toByteArray()
                        val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                        if (bmp != null) {
                            onBitmapFrame(bmp)
                        }
                    }
                    frameBuffer.reset()
                }
            }
            prevByte = b
        }
    }
}
