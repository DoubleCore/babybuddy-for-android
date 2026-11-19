package eu.pkgsoftware.babybuddywidgets.local

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.squareup.phrase.Phrase
import eu.pkgsoftware.babybuddywidgets.MainActivity
import eu.pkgsoftware.babybuddywidgets.R
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.MedicationEntry

class MedicationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val settings = NapAndAppSettings.fromContext(context)
        if (!settings.medicationNotificationsEnabled) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        MedicationReminderScheduler.ensureChannel(context)
        val medName = intent.getStringExtra(EXTRA_MED_NAME) ?: return
        val childName = intent.getStringExtra(EXTRA_CHILD_NAME) ?: ""
        val dosageText = intent.getStringExtra(EXTRA_DOSAGE_TEXT) ?: ""
        val notificationId = intent.getIntExtra(EXTRA_MED_ID, 1001)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (childName.isNotBlank()) {
            "$childName · $medName"
        } else {
            medName
        }
        val body = Phrase.from(context, R.string.medication_notification_ready_body)
            .put("medication", dosageText.ifBlank { medName })
            .format()
            .toString()

        val notification = NotificationCompat.Builder(context, MedicationReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_medication)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val EXTRA_MED_ID = "extra_med_id"
        const val EXTRA_MED_NAME = "extra_med_name"
        const val EXTRA_CHILD_NAME = "extra_child_name"
        const val EXTRA_DOSAGE_TEXT = "extra_dosage_text"
    }
}

object MedicationReminderScheduler {
    const val CHANNEL_ID = "babybuddy_medication_reminders"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.medication_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.medication_notification_channel_desc)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    fun scheduleIfNeeded(context: Context, entry: MedicationEntry, childName: String) {
        val nextTime = entry.nextDoseTime ?: return
        if (nextTime.time <= System.currentTimeMillis()) return
        val settings = NapAndAppSettings.fromContext(context)
        if (!settings.medicationNotificationsEnabled) return

        ensureChannel(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, MedicationAlarmReceiver::class.java).apply {
            putExtra(MedicationAlarmReceiver.EXTRA_MED_ID, entry.id)
            putExtra(MedicationAlarmReceiver.EXTRA_MED_NAME, entry.name)
            putExtra(MedicationAlarmReceiver.EXTRA_CHILD_NAME, childName)
            putExtra(MedicationAlarmReceiver.EXTRA_DOSAGE_TEXT, "${entry.name} (${entry.dosage} ${entry.dosageUnit.post_name})")
        }
        val pi = PendingIntent.getBroadcast(
            context,
            entry.id + 50_000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTime.time, pi)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(context: Context, medicationId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, MedicationAlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            medicationId + 50_000,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        alarmManager.cancel(pi)
    }
}
