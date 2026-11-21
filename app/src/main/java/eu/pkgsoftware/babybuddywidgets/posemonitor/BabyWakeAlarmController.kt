package eu.pkgsoftware.babybuddywidgets.posemonitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import eu.pkgsoftware.babybuddywidgets.R
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Shared state hub linking the Camera Pose Monitor UI, Alarm Controller,
 * BroadcastReceiver (notification/WearOS actions), and LAN Watch HTTP Server.
 */
object PoseMonitorHub {
    interface Listener {
        fun onAlarmDismissedRemotely() {}
        fun onAlarmSnoozedRemotely(durationMs: Long) {}
        fun onAlarmTestRequestedRemotely() {}
        fun onRemoteZoomRequested(newZoom: Float) {}
    }

    val wakeTracker = BabyWakeUpTracker()
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    var babyName: String = "宝宝"

    @Volatile
    var isMonitoring: Boolean = false

    @Volatile
    var currentZoomRatio: Float = 1.8f

    @Volatile
    var panX: Float = 0f

    @Volatile
    var panY: Float = 0f

    @Volatile
    var syncSystemAlarmApp: Boolean = false

    @Volatile
    var watchWebhookUrl: String = ""

    @Volatile
    var ipCameraStreamUrl: String = ""

    @Volatile
    var latestJpegSnapshot: ByteArray? = null

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun dismissAlarm(context: Context?) {
        wakeTracker.dismissAlarm()
        context?.let { BabyWakeAlarmController.stopAlarm(it) }
        for (l in listeners) {
            l.onAlarmDismissedRemotely()
        }
    }

    fun snoozeAlarm(context: Context?, durationMs: Long = BabyWakeUpTracker.DEFAULT_SNOOZE_MS) {
        wakeTracker.snoozeAlarm(durationMs)
        context?.let { BabyWakeAlarmController.stopAlarm(it) }
        for (l in listeners) {
            l.onAlarmSnoozedRemotely(durationMs)
        }
    }

    fun requestRemoteZoom(newZoom: Float) {
        val clamped = ZoomRoiCropper.clampZoom(newZoom)
        currentZoomRatio = clamped
        for (l in listeners) {
            l.onRemoteZoomRequested(clamped)
        }
    }

    fun triggerTestAlarm(context: Context) {
        val snap = wakeTracker.forceTriggerAlarmForTest()
        BabyWakeAlarmController.triggerWakeAlarm(
            context = context,
            babyName = babyName,
            snapshot = snap,
            syncSystemClock = syncSystemAlarmApp,
            webhookUrl = watchWebhookUrl
        )
        for (l in listeners) {
            l.onAlarmTestRequestedRemotely()
        }
    }
}

object BabyWakeAlarmController {
    private const val TAG = "BabyWakeAlarmCtrl"
    const val CHANNEL_ID = "babybuddy_pose_wake_alarm_ch"
    const val NOTIFICATION_ID = 8801

    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var toneLoopRunnable: Runnable? = null
    private val webhookExecutor = Executors.newSingleThreadExecutor()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.pose_alarm_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.pose_alarm_channel_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 700)
                setSound(alarmUri, attrs)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            mgr.createNotificationChannel(channel)
        }
    }

    @Synchronized
    fun triggerWakeAlarm(
        context: Context,
        babyName: String,
        snapshot: WakeDecisionSnapshot,
        syncSystemClock: Boolean = false,
        webhookUrl: String = ""
    ) {
        val appCtx = context.applicationContext
        ensureChannel(appCtx)
        startRingingAndVibration(appCtx)
        postAlarmAndWearableNotification(appCtx, babyName, snapshot)

        if (syncSystemClock) {
            launchSystemClockAlarmIfAvailable(appCtx, babyName)
        }
        if (webhookUrl.isNotBlank()) {
            pushWatchWebhookAsync(webhookUrl, babyName, snapshot)
        }
    }

    @Synchronized
    fun stopAlarm(context: Context) {
        val appCtx = context.applicationContext
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        } catch (_: Throwable) {
        }
        mediaPlayer = null

        toneLoopRunnable?.let { mainHandler.removeCallbacks(it) }
        toneLoopRunnable = null
        try {
            toneGenerator?.release()
        } catch (_: Throwable) {
        }
        toneGenerator = null

        try {
            val vibrator = resolveVibrator(appCtx)
            vibrator?.cancel()
        } catch (_: Throwable) {
        }

        try {
            NotificationManagerCompat.from(appCtx).cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {
        }
    }

    private fun startRingingAndVibration(context: Context) {
        stopAlarm(context)

        // 1. Start continuous alarm stream ringtone
        var startedMedia = false
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (uri != null) {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setDataSource(context, uri)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                mediaPlayer = mp
                startedMedia = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaPlayer alarm URI failed, falling back to ToneGenerator", t)
        }

        if (!startedMedia) {
            try {
                val tg = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                toneGenerator = tg
                val loop = object : Runnable {
                    override fun run() {
                        if (!PoseMonitorHub.wakeTracker.isAlarmRinging) return
                        try {
                            tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
                        } catch (_: Throwable) {
                        }
                        mainHandler.postDelayed(this, 1000L)
                    }
                }
                toneLoopRunnable = loop
                mainHandler.post(loop)
            } catch (t: Throwable) {
                Log.w(TAG, "ToneGenerator fallback failed", t)
            }
        }

        // 2. Start rhythmic alarm vibration
        try {
            val vibrator = resolveVibrator(context)
            val pattern = longArrayOf(0, 550, 280, 550, 280, 750)
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, 0)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Vibration failed", t)
        }
    }

    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun postAlarmAndWearableNotification(
        context: Context,
        babyName: String,
        snapshot: WakeDecisionSnapshot
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val dismissIntent = Intent(context, BabyWakeAlarmReceiver::class.java).apply {
            action = BabyWakeAlarmReceiver.ACTION_DISMISS_ALARM
        }
        val dismissPi = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID + 1,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(context, BabyWakeAlarmReceiver::class.java).apply {
            action = BabyWakeAlarmReceiver.ACTION_SNOOZE_ALARM
        }
        val snoozePi = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID + 2,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "${babyName}可能醒了"
        val body = "${snapshot.currentPose.zhLabel} · 点击关闭闹钟"

        val dismissAction = NotificationCompat.Action.Builder(
            R.drawable.ic_check_save,
            context.getString(R.string.pose_alarm_action_dismiss),
            dismissPi
        ).build()

        val snoozeAction = NotificationCompat.Action.Builder(
            R.drawable.ic_schedule_clock,
            context.getString(R.string.pose_alarm_action_snooze),
            snoozePi
        ).build()

        // WearableExtender ensures paired Wear OS / Bluetooth smartwatches surface native wrist controls
        val wearableExtender = NotificationCompat.WearableExtender()
            .addAction(dismissAction)
            .addAction(snoozeAction)

        // Single tap on the notification directly dismisses the alarm & notification without opening any page
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_schedule_clock)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(dismissPi)
            .setDeleteIntent(dismissPi)
            .addAction(snoozeAction)
            .extend(wearableExtender)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (t: SecurityException) {
            Log.w(TAG, "Notification permission denied", t)
        }
    }

    private fun launchSystemClockAlarmIfAvailable(context: Context, babyName: String) {
        try {
            val cal = Calendar.getInstance().apply {
                add(Calendar.MINUTE, 1)
            }
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(AlarmClock.EXTRA_HOUR, cal.get(Calendar.HOUR_OF_DAY))
                putExtra(AlarmClock.EXTRA_MINUTES, cal.get(Calendar.MINUTE))
                putExtra(AlarmClock.EXTRA_MESSAGE, "👶 ${babyName}即将醒来（姿态唤醒）")
                putExtra(AlarmClock.EXTRA_VIBRATE, true)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "System AlarmClock intent unavailable: ${t.message}")
        }
    }

    private fun pushWatchWebhookAsync(
        webhookUrl: String,
        babyName: String,
        snapshot: WakeDecisionSnapshot
    ) {
        webhookExecutor.execute {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(webhookUrl.trim())
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 4000
                    readTimeout = 4000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
                val safeName = babyName.replace("\"", "\\\"")
                val payload = """
                    {
                      "event": "baby_waking_up",
                      "title": "🚨 ${safeName}即将醒来",
                      "message": "检测到连续 ${snapshot.changeCount} 次姿态变化 (${snapshot.currentPose.zhLabel})",
                      "babyName": "$safeName",
                      "poseCode": "${snapshot.currentPose.code}",
                      "poseLabel": "${snapshot.currentPose.zhLabel}",
                      "changeCount": ${snapshot.changeCount},
                      "threshold": ${snapshot.changeThreshold},
                      "timestamp": ${System.currentTimeMillis()}
                    }
                """.trimIndent()
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                    writer.flush()
                }
                conn.responseCode
            } catch (t: Throwable) {
                Log.w(TAG, "Watch webhook push failed: ${t.message}")
            } finally {
                conn?.disconnect()
            }
        }
    }
}
