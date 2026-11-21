package eu.pkgsoftware.babybuddywidgets.posemonitor

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Lightweight embedded LAN HTTP server enabling smartwatches (Wear OS, Apple Watch WebKit,
 * Xiaomi/Huawei watch browsers, or custom networked watch hardware) to monitor baby pose state,
 * view zoomed crib snapshots, receive wake-up alerts, and dismiss/snooze the alarm remotely.
 */
object BabyWatchServer {
    private const val TAG = "BabyWatchServer"
    const val DEFAULT_PORT = 8765

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var boundPort: Int = DEFAULT_PORT
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptExecutor: ExecutorService? = null
    private var workerPool: ExecutorService? = null
    private var appContext: Context? = null

    @Synchronized
    fun start(context: Context, port: Int = DEFAULT_PORT): Boolean {
        if (isRunning && serverSocket?.isClosed == false) {
            return true
        }
        stop()
        appContext = context.applicationContext
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("0.0.0.0", port))
            serverSocket = ss
            boundPort = ss.localPort
            isRunning = true

            val pool = Executors.newCachedThreadPool()
            workerPool = pool
            val acceptor = Executors.newSingleThreadExecutor()
            acceptExecutor = acceptor

            acceptor.execute {
                while (isRunning && !ss.isClosed) {
                    try {
                        val client = ss.accept()
                        pool.execute { handleClient(client) }
                    } catch (t: Throwable) {
                        if (isRunning) {
                            Log.d(TAG, "Accept interrupted: ${t.message}")
                        }
                    }
                }
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to bind watch server on port $port", t)
            isRunning = false
            false
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        acceptExecutor?.shutdownNow()
        acceptExecutor = null
        workerPool?.shutdownNow()
        workerPool = null
    }

    fun resolveLocalLanUrls(port: Int = boundPort): List<String> {
        val urls = ArrayList<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in interfaces.toList()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        urls.add("http://$ip:$port/watch")
                    }
                }
            }
        } catch (_: Throwable) {
        }
        if (urls.isEmpty()) {
            urls.add("http://127.0.0.1:$port/watch")
        }
        return urls
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0)?.uppercase() ?: "GET"
            val rawTarget = parts.getOrNull(1) ?: "/"

            // Drain headers
            var contentLength = 0
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            if (contentLength in 1..4096) {
                val buf = CharArray(contentLength)
                reader.read(buf, 0, contentLength)
            }

            val path = rawTarget.substringBefore("?")
            val query = rawTarget.substringAfter("?", "")
            val out = BufferedOutputStream(s.getOutputStream())

            if (method == "OPTIONS") {
                writeTextResponse(out, 200, "OK", "text/plain", "")
                return
            }

            when {
                path == "/" || path == "/watch" -> {
                    writeTextResponse(out, 200, "OK", "text/html; charset=utf-8", buildWatchFaceHtml())
                }
                path == "/api/status" -> {
                    writeTextResponse(out, 200, "OK", "application/json; charset=utf-8", buildStatusJson())
                }
                path == "/api/snapshot.jpg" -> {
                    val bytes = PoseMonitorHub.latestJpegSnapshot
                    if (bytes != null && bytes.isNotEmpty()) {
                        writeBinaryResponse(out, 200, "OK", "image/jpeg", bytes)
                    } else {
                        writeTextResponse(out, 404, "Not Found", "text/plain; charset=utf-8", "No camera frame yet")
                    }
                }
                path == "/api/alarm/dismiss" -> {
                    PoseMonitorHub.dismissAlarm(appContext)
                    writeTextResponse(out, 200, "OK", "application/json; charset=utf-8", buildStatusJson())
                }
                path == "/api/alarm/snooze" -> {
                    PoseMonitorHub.snoozeAlarm(appContext, BabyWakeUpTracker.DEFAULT_SNOOZE_MS)
                    writeTextResponse(out, 200, "OK", "application/json; charset=utf-8", buildStatusJson())
                }
                path == "/api/alarm/test" -> {
                    appContext?.let { PoseMonitorHub.triggerTestAlarm(it) }
                    writeTextResponse(out, 200, "OK", "application/json; charset=utf-8", buildStatusJson())
                }
                path == "/api/zoom" -> {
                    val ratioParam = parseQueryParam(query, "ratio")?.toFloatOrNull()
                    val deltaParam = parseQueryParam(query, "delta")?.toFloatOrNull()
                    val nextZoom = when {
                        ratioParam != null -> ratioParam
                        deltaParam != null -> PoseMonitorHub.currentZoomRatio + deltaParam
                        else -> PoseMonitorHub.currentZoomRatio
                    }
                    PoseMonitorHub.requestRemoteZoom(nextZoom)
                    writeTextResponse(out, 200, "OK", "application/json; charset=utf-8", buildStatusJson())
                }
                else -> {
                    writeTextResponse(out, 404, "Not Found", "application/json; charset=utf-8", """{"error":"not_found"}""")
                }
            }
        }
    }

    private fun parseQueryParam(query: String, key: String): String? {
        if (query.isBlank()) return null
        for (pair in query.split("&")) {
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == key) {
                return try {
                    URLDecoder.decode(kv[1], "UTF-8")
                } catch (_: Throwable) {
                    kv[1]
                }
            }
        }
        return null
    }

    fun buildStatusJson(): String {
        val snap = PoseMonitorHub.wakeTracker.currentSnapshot()
        val safeBaby = PoseMonitorHub.babyName.replace("\"", "\\\"")
        val safeAngles = snap.angleProfile.summaryText().replace("\"", "\\\"")
        val historyJson = snap.recentPoseHistory.joinToString(",") {
            "\"${it.replace("\"", "\\\"")}\""
        }
        fun numOrNull(v: Float?): String = v?.let { "%.1f".format(java.util.Locale.US, it) } ?: "null"
        return """
            {
              "babyName": "$safeBaby",
              "monitoring": ${PoseMonitorHub.isMonitoring},
              "targetDetected": ${snap.targetDetected},
              "poseCode": "${snap.currentPose.code}",
              "poseLabel": "${snap.currentPose.zhLabel}",
              "anglesSummary": "$safeAngles",
              "lastAngleDeltaDeg": ${"%.1f".format(java.util.Locale.US, snap.lastAngleDeltaDeg)},
              "angleThresholdDeg": ${"%.1f".format(java.util.Locale.US, snap.angleThresholdDeg)},
              "leftElbowDeg": ${numOrNull(snap.angleProfile.leftElbowDeg)},
              "rightElbowDeg": ${numOrNull(snap.angleProfile.rightElbowDeg)},
              "leftKneeDeg": ${numOrNull(snap.angleProfile.leftKneeDeg)},
              "rightKneeDeg": ${numOrNull(snap.angleProfile.rightKneeDeg)},
              "bodyRollDeg": ${numOrNull(snap.angleProfile.bodyRollDeg)},
              "changeCount": ${snap.changeCount},
              "threshold": ${snap.changeThreshold},
              "windowSec": ${snap.windowSec},
              "zoomRatio": ${"%.1f".format(java.util.Locale.US, PoseMonitorHub.currentZoomRatio)},
              "alarmRinging": ${snap.isAlarmRinging},
              "snoozedRemainingSec": ${snap.snoozedRemainingSec},
              "lastWakeTimestampMs": ${snap.lastWakeTimestampMs},
              "hasSnapshot": ${PoseMonitorHub.latestJpegSnapshot != null},
              "recentHistory": [$historyJson]
            }
        """.trimIndent()
    }

    private fun writeTextResponse(
        out: BufferedOutputStream,
        code: Int,
        reason: String,
        contentType: String,
        body: String
    ) {
        writeBinaryResponse(out, code, reason, contentType, body.toByteArray(Charsets.UTF_8))
    }

    private fun writeBinaryResponse(
        out: BufferedOutputStream,
        code: Int,
        reason: String,
        contentType: String,
        bytes: ByteArray
    ) {
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    /**
     * Standalone responsive Smartwatch UI (supports round 360x360/466x466 Wear OS & square Apple/Xiaomi Watch screens).
     */
    fun buildWatchFaceHtml(): String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
  <title>手表端看护 · Baby Watch</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; -webkit-tap-highlight-color: transparent; }
    body {
      background: #090B10;
      color: #F4F6FB;
      font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Noto Sans SC", sans-serif;
      min-height: 100dvh;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 8px;
    }
    .watch-dial {
      width: min(96vw, 340px);
      min-height: min(96vw, 340px);
      border-radius: 44px;
      background: radial-gradient(circle at 50% 25%, #1A2030 0%, #0E111A 85%);
      border: 3px solid #253047;
      padding: 16px 14px;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: space-between;
      text-align: center;
      box-shadow: 0 0 28px rgba(0, 0, 0, 0.7);
      transition: border-color 0.25s, box-shadow 0.25s;
    }
    .watch-dial.calm { border-color: #2EC4B6; box-shadow: 0 0 22px rgba(46, 196, 182, 0.22); }
    .watch-dial.active { border-color: #FF9F1C; box-shadow: 0 0 24px rgba(255, 159, 28, 0.30); }
    .watch-dial.alarm {
      border-color: #FF3B5C;
      animation: pulseAlarm 0.85s infinite alternate;
    }
    @keyframes pulseAlarm {
      from { box-shadow: 0 0 16px rgba(255, 59, 92, 0.45); background: #260B12; }
      to { box-shadow: 0 0 42px rgba(255, 59, 92, 0.95); background: #3D0E1A; }
    }
    .top-pill {
      font-size: 11px;
      color: #9BA8C2;
      background: rgba(255,255,255,0.07);
      padding: 3px 10px;
      border-radius: 999px;
    }
    .status-title {
      font-size: 20px;
      font-weight: 800;
      margin-top: 6px;
      letter-spacing: 0.5px;
    }
    .pose-badge {
      margin-top: 4px;
      font-size: 13px;
      font-weight: 600;
      color: #7CE8DF;
    }
    .counter-ring {
      margin: 8px 0;
      font-size: 13px;
      color: #CBD5E1;
      background: rgba(255,255,255,0.06);
      padding: 6px 14px;
      border-radius: 14px;
      width: 100%;
    }
    .counter-num { font-size: 18px; font-weight: 800; color: #FFD166; }
    .snap-box {
      width: 100%;
      max-height: 120px;
      border-radius: 14px;
      overflow: hidden;
      margin: 4px 0;
      display: none;
      border: 1px solid rgba(255,255,255,0.15);
      position: relative;
    }
    .snap-box img { width: 100%; height: 116px; object-fit: cover; display: block; }
    .btn-row {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 7px;
      width: 100%;
      margin-top: 6px;
    }
    button {
      border: none;
      border-radius: 999px;
      padding: 10px 8px;
      font-size: 12.5px;
      font-weight: 700;
      cursor: pointer;
      color: #FFF;
      transition: transform 0.1s;
    }
    button:active { transform: scale(0.95); }
    .btn-dismiss { background: #FF3B5C; grid-column: span 2; font-size: 14px; padding: 11px; }
    .btn-snooze { background: #3B82F6; }
    .btn-cam { background: #334155; }
    .zoom-bar {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      width: 100%;
      margin-top: 6px;
    }
    .zoom-btn {
      background: rgba(255,255,255,0.1);
      padding: 5px 10px;
      font-size: 11.5px;
    }
  </style>
</head>
<body>
  <div class="watch-dial calm" id="dial">
    <div class="top-pill" id="babyHeader">⌚ 婴儿看护手表端</div>
    <div>
      <div class="status-title" id="mainStatus">安睡监控中</div>
      <div class="pose-badge" id="poseText">姿态：等待画面...</div>
    </div>

    <div class="snap-box" id="snapBox">
      <img id="snapImg" alt="Zoomed Baby Feed" />
    </div>

    <div class="counter-ring">
      近期姿态变化：<span class="counter-num" id="changeCounter">0 / 5</span>
      <div style="font-size:10.5px;color:#94A3B8;margin-top:2px;" id="subInfo">画面放大 1.8x</div>
    </div>

    <div class="btn-row">
      <button class="btn-dismiss" id="btnDismiss" onclick="postAction('/api/alarm/dismiss')">🔕 关闭闹钟 / 我知道了</button>
      <button class="btn-snooze" onclick="postAction('/api/alarm/snooze')">💤 贪睡 3m</button>
      <button class="btn-cam" onclick="toggleSnap()" id="btnCam">📷 放大画面</button>
    </div>

    <div class="zoom-bar">
      <button class="zoom-btn" onclick="adjustZoom(-0.5)">🔍 -</button>
      <button class="zoom-btn" onclick="setZoom(2.0)">2x</button>
      <button class="zoom-btn" onclick="setZoom(4.0)">4x</button>
      <button class="zoom-btn" onclick="adjustZoom(0.5)">🔍 +</button>
    </div>
  </div>

  <script>
    let showSnap = false;
    let audioCtx = null;
    let lastBeepAt = 0;

    function beepWatch() {
      const now = Date.now();
      if (now - lastBeepAt < 1200) return;
      lastBeepAt = now;
      try {
        if (navigator.vibrate) navigator.vibrate([350, 150, 350]);
        if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
        if (audioCtx.state === 'suspended') audioCtx.resume();
        const osc = audioCtx.createOscillator();
        const gain = audioCtx.createGain();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(880, audioCtx.currentTime);
        gain.gain.setValueAtTime(0.25, audioCtx.currentTime);
        osc.connect(gain);
        gain.connect(audioCtx.destination);
        osc.start();
        osc.stop(audioCtx.currentTime + 0.35);
      } catch (e) {}
    }

    function toggleSnap() {
      showSnap = !showSnap;
      document.getElementById('snapBox').style.display = showSnap ? 'block' : 'none';
      if (showSnap) refreshSnap();
    }

    function refreshSnap() {
      if (!showSnap) return;
      document.getElementById('snapImg').src = '/api/snapshot.jpg?t=' + Date.now();
    }

    async function postAction(url) {
      try {
        if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
        const res = await fetch(url, { method: 'POST' });
        const data = await res.json();
        render(data);
      } catch (e) {}
    }

    function adjustZoom(delta) { postAction('/api/zoom?delta=' + delta); }
    function setZoom(r) { postAction('/api/zoom?ratio=' + r); }

    function render(d) {
      const dial = document.getElementById('dial');
      document.getElementById('babyHeader').textContent = '⌚ ' + (d.babyName || '宝宝') + (d.monitoring ? ' · 在线' : ' · 待机');
      document.getElementById('poseText').textContent = '当前姿态：' + d.poseLabel + ' (Δ' + Math.round(d.lastAngleDeltaDeg || 0) + '°)';
      document.getElementById('changeCounter').textContent = d.changeCount + ' / ' + d.threshold + ' 次';
      const snoozeTxt = d.snoozedRemainingSec > 0 ? (' · 贪睡 ' + d.snoozedRemainingSec + 's') : '';
      const angleTxt = d.anglesSummary ? ('\n' + d.anglesSummary) : '';
      document.getElementById('subInfo').textContent = '角度阈值 ≥' + Math.round(d.angleThresholdDeg || 25) + '° · 放大 ' + Number(d.zoomRatio).toFixed(1) + 'x' + snoozeTxt;

      dial.classList.remove('calm', 'active', 'alarm');
      if (d.alarmRinging) {
        dial.classList.add('alarm');
        document.getElementById('mainStatus').textContent = '🚨 宝宝要醒了！';
        beepWatch();
      } else if (d.changeCount >= Math.max(2, Math.ceil(d.threshold * 0.5))) {
        dial.classList.add('active');
        document.getElementById('mainStatus').textContent = '⚠️ 体动增多';
      } else {
        dial.classList.add('calm');
        document.getElementById('mainStatus').textContent = d.snoozedRemainingSec > 0 ? '💤 贪睡暂停中' : '🌙 安睡监控中';
      }
      if (showSnap && d.hasSnapshot) refreshSnap();
    }

    async function poll() {
      try {
        const res = await fetch('/api/status');
        const data = await res.json();
        render(data);
      } catch (e) {
        document.getElementById('mainStatus').textContent = '等待主机连接...';
      }
    }
    setInterval(poll, 1000);
    poll();
  </script>
</body>
</html>
    """.trimIndent()
}
