package eu.pkgsoftware.babybuddywidgets.posemonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BabyWakeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_DISMISS_ALARM -> {
                PoseMonitorHub.dismissAlarm(context)
            }
            ACTION_SNOOZE_ALARM -> {
                PoseMonitorHub.snoozeAlarm(context, BabyWakeUpTracker.DEFAULT_SNOOZE_MS)
            }
            ACTION_ALARM_CLOCK_FIRED -> {
                // Backup trigger when AlarmManager.AlarmClockInfo fires
                if (PoseMonitorHub.wakeTracker.isAlarmRinging) {
                    // Already ringing via immediate alarm controller
                    return
                }
            }
        }
    }

    companion object {
        const val ACTION_DISMISS_ALARM = "eu.pkgsoftware.babybuddywidgets.posemonitor.ACTION_DISMISS_ALARM"
        const val ACTION_SNOOZE_ALARM = "eu.pkgsoftware.babybuddywidgets.posemonitor.ACTION_SNOOZE_ALARM"
        const val ACTION_ALARM_CLOCK_FIRED = "eu.pkgsoftware.babybuddywidgets.posemonitor.ACTION_ALARM_CLOCK_FIRED"
        const val EXTRA_OPEN_POSE_MONITOR = "extra_open_pose_monitor"
    }
}
