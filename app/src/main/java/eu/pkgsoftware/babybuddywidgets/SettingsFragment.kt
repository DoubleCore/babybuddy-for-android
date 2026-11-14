package eu.pkgsoftware.babybuddywidgets

import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat

class SettingsFragment : PreferenceFragmentCompat() {

    val changeListener =
        OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "setting_dark_light_mode") {
                mainActivity.applyLightDarkMode()
            }
        }

    val mainActivity: MainActivity
        get() = activity as MainActivity

    private fun currentLanguagePrefValue(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) {
            return "system"
        }
        val tag = locales.toLanguageTags().lowercase()
        return when {
            tag.startsWith("zh") -> "zh-CN"
            tag.startsWith("de") -> "de-DE"
            tag.startsWith("nl") -> "nl"
            tag.startsWith("en") -> "en"
            else -> "system"
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferences?.let { p ->
            if (p.getString("setting_dark_light_mode", "") == "") {
                p.edit().putString("setting_dark_light_mode", "system").commit()
            }
        }

        setPreferencesFromResource(R.xml.app_settings, rootKey)

        findPreference<ListPreference>("setting_language")?.let { langPref ->
            langPref.value = currentLanguagePrefValue()
            langPref.setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as? String ?: "system"
                val localeList = if (selected == "system" || selected.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(selected)
                }
                AppCompatDelegate.setApplicationLocales(localeList)
                true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mainActivity.setTitle(getString(R.string.action_settings))
        mainActivity.enableBackNavigationButton(true)
        findPreference<ListPreference>("setting_language")?.value = currentLanguagePrefValue()

        preferenceManager?.sharedPreferences?.let { p ->
            p.registerOnSharedPreferenceChangeListener(changeListener)
        }
    }

    override fun onPause() {
        super.onPause()
        preferenceManager?.sharedPreferences?.let { p ->
            p.unregisterOnSharedPreferenceChangeListener(changeListener)
        }
    }
}
