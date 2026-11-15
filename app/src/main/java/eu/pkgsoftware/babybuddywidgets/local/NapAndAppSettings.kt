package eu.pkgsoftware.babybuddywidgets.local

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

data class StandaloneSettingsSnapshot(
    val napStartMin: String = NapAndAppSettings.DEFAULT_NAP_START_MIN,
    val napStartMax: String = NapAndAppSettings.DEFAULT_NAP_START_MAX,
    val dashboardHideEmpty: Boolean = false,
    val medicationNotificationsEnabled: Boolean = true,
    val autoTrimOverlappingPeriods: Boolean = false,
)

class NapAndAppSettings(private val prefs: SharedPreferences) {

    var napStartMin: String
        get() = normalizeHHmm(prefs.getString(KEY_NAP_START_MIN, DEFAULT_NAP_START_MIN), DEFAULT_NAP_START_MIN)
        set(value) {
            prefs.edit().putString(KEY_NAP_START_MIN, normalizeHHmm(value, DEFAULT_NAP_START_MIN)).apply()
        }

    var napStartMax: String
        get() = normalizeHHmm(prefs.getString(KEY_NAP_START_MAX, DEFAULT_NAP_START_MAX), DEFAULT_NAP_START_MAX)
        set(value) {
            prefs.edit().putString(KEY_NAP_START_MAX, normalizeHHmm(value, DEFAULT_NAP_START_MAX)).apply()
        }

    var dashboardHideEmpty: Boolean
        get() = prefs.getBoolean(KEY_DASHBOARD_HIDE_EMPTY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DASHBOARD_HIDE_EMPTY, value).apply()
        }

    var medicationNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEDICATION_NOTIFICATIONS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_MEDICATION_NOTIFICATIONS, value).apply()
        }

    var autoTrimOverlappingPeriods: Boolean
        get() = prefs.getBoolean(KEY_AUTO_TRIM_OVERLAPS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_TRIM_OVERLAPS, value).apply()
        }

    fun exportSnapshot(): StandaloneSettingsSnapshot = StandaloneSettingsSnapshot(
        napStartMin = napStartMin,
        napStartMax = napStartMax,
        dashboardHideEmpty = dashboardHideEmpty,
        medicationNotificationsEnabled = medicationNotificationsEnabled,
        autoTrimOverlappingPeriods = autoTrimOverlappingPeriods,
    )

    fun importSnapshot(snapshot: StandaloneSettingsSnapshot) {
        prefs.edit()
            .putString(KEY_NAP_START_MIN, normalizeHHmm(snapshot.napStartMin, DEFAULT_NAP_START_MIN))
            .putString(KEY_NAP_START_MAX, normalizeHHmm(snapshot.napStartMax, DEFAULT_NAP_START_MAX))
            .putBoolean(KEY_DASHBOARD_HIDE_EMPTY, snapshot.dashboardHideEmpty)
            .putBoolean(KEY_MEDICATION_NOTIFICATIONS, snapshot.medicationNotificationsEnabled)
            .putBoolean(KEY_AUTO_TRIM_OVERLAPS, snapshot.autoTrimOverlappingPeriods)
            .apply()
    }

    companion object {
        const val KEY_NAP_START_MIN = "standalone_nap_start_min"
        const val KEY_NAP_START_MAX = "standalone_nap_start_max"
        const val KEY_DASHBOARD_HIDE_EMPTY = "standalone_dashboard_hide_empty"
        const val KEY_MEDICATION_NOTIFICATIONS = "standalone_medication_notifications"
        const val KEY_AUTO_TRIM_OVERLAPS = "standalone_auto_trim_overlaps"

        const val DEFAULT_NAP_START_MIN = "06:00"
        const val DEFAULT_NAP_START_MAX = "18:00"

        fun fromContext(context: Context): NapAndAppSettings {
            return NapAndAppSettings(PreferenceManager.getDefaultSharedPreferences(context.applicationContext))
        }

        fun normalizeHHmm(raw: String?, fallback: String): String {
            if (raw.isNullOrBlank()) return fallback
            val parts = raw.trim().split(":")
            if (parts.size < 2) return fallback
            val h = parts[0].toIntOrNull() ?: return fallback
            val m = parts[1].toIntOrNull() ?: return fallback
            if (h !in 0..23 || m !in 0..59) return fallback
            return "%02d:%02d".format(h, m)
        }
    }
}
