package eu.pkgsoftware.babybuddywidgets

import android.app.TimePickerDialog
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import eu.pkgsoftware.babybuddywidgets.backup.BackupCenterBottomSheet
import eu.pkgsoftware.babybuddywidgets.children.ChildManagerBottomSheet
import eu.pkgsoftware.babybuddywidgets.children.ChildProfileDialog
import eu.pkgsoftware.babybuddywidgets.local.NapAndAppSettings
import eu.pkgsoftware.babybuddywidgets.tags.TagManagerDialog

class SettingsFragment : PreferenceFragmentCompat() {

    val changeListener =
        OnSharedPreferenceChangeListener { _, key ->
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

        bindTimeWindowPreference(NapAndAppSettings.KEY_NAP_START_MIN) {
            mainActivity.standaloneSettings.napStartMin
        }
        bindTimeWindowPreference(NapAndAppSettings.KEY_NAP_START_MAX) {
            mainActivity.standaloneSettings.napStartMax
        }

        findPreference<Preference>("action_open_backup_center")?.setOnPreferenceClickListener {
            BackupCenterBottomSheet.show(mainActivity)
            true
        }

        findPreference<Preference>("action_manage_children")?.setOnPreferenceClickListener {
            val children = mainActivity.repository.listChildren()
            val selectedSlug = mainActivity.storage.login<String>("selected-child-slug")
            if (children.isEmpty()) {
                ChildProfileDialog.show(mainActivity)
            } else {
                ChildManagerBottomSheet.show(mainActivity, selectedSlug) { chosen ->
                    mainActivity.storage.login("selected-child-slug", chosen.slug)
                }
            }
            true
        }

        findPreference<Preference>("action_manage_tags")?.setOnPreferenceClickListener {
            TagManagerDialog.showManager(mainActivity)
            true
        }

        findPreference<Preference>("action_clear_all_local_data")?.setOnPreferenceClickListener {
            confirmClearAllLocalData()
            true
        }
    }

    private fun bindTimeWindowPreference(prefKey: String, getter: () -> String) {
        val pref = findPreference<Preference>(prefKey) ?: return
        pref.summary = getter()
        pref.setOnPreferenceClickListener {
            val ctx = context ?: return@setOnPreferenceClickListener true
            val current = getter()
            val parts = current.split(":")
            val initH = parts.getOrNull(0)?.toIntOrNull() ?: 6
            val initM = parts.getOrNull(1)?.toIntOrNull() ?: 0
            TimePickerDialog(
                ctx,
                { _, hourOfDay, minute ->
                    val formatted = "%02d:%02d".format(hourOfDay, minute)
                    if (prefKey == NapAndAppSettings.KEY_NAP_START_MIN) {
                        mainActivity.standaloneSettings.napStartMin = formatted
                    } else {
                        mainActivity.standaloneSettings.napStartMax = formatted
                    }
                    pref.summary = formatted
                },
                initH,
                initM,
                DateFormat.is24HourFormat(ctx)
            ).show()
            true
        }
    }

    private fun confirmClearAllLocalData() {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.setting_clear_all_data)
            .setMessage(R.string.setting_clear_all_data_confirm_1)
            .setPositiveButton(R.string.history_delete_question_delete_button) { _, _ ->
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.setting_clear_all_data)
                    .setMessage(R.string.setting_clear_all_data_confirm_2)
                    .setPositiveButton(R.string.history_delete_question_delete_button) { _, _ ->
                        mainActivity.repository.clearAllData(deleteMediaFiles = true)
                        Toast.makeText(ctx, R.string.setting_clear_all_data_done, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.history_delete_question_cancel_button, null)
                    .show()
            }
            .setNegativeButton(R.string.history_delete_question_cancel_button, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        mainActivity.setTitle(getString(R.string.action_settings))
        mainActivity.enableBackNavigationButton(true)
        findPreference<ListPreference>("setting_language")?.value = currentLanguagePrefValue()
        findPreference<Preference>(NapAndAppSettings.KEY_NAP_START_MIN)?.summary =
            mainActivity.standaloneSettings.napStartMin
        findPreference<Preference>(NapAndAppSettings.KEY_NAP_START_MAX)?.summary =
            mainActivity.standaloneSettings.napStartMax

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
