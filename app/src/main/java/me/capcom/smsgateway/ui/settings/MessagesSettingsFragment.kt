package me.capcom.smsgateway.ui.settings

import android.app.TimePickerDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import androidx.preference.DropDownPreference
import androidx.preference.EditTextPreference
import me.capcom.smsgateway.helpers.SubscriptionsHelper
import androidx.preference.Preference
import me.capcom.smsgateway.R

class MessagesSettingsFragment : BasePreferenceFragment() {

    override fun onResume() {
        super.onResume()

        onPreferenceChanged.onSharedPreferenceChanged(
            preferenceManager.sharedPreferences,
            "messages.limit_period"
        )
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(
            onPreferenceChanged
        )
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(
            onPreferenceChanged
        )

        super.onPause()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.messages_preferences, rootKey)
        bindSimChoices()
    }

    // [lyk] The active SIMs as "Carrier · number · SIM n"; shown only in the Fixed mode.
    private fun bindSimChoices() {
        val pref = findPreference<DropDownPreference>("messages.default_sim_slot") ?: return
        val sims = SubscriptionsHelper.getActiveSimCards(requireContext()).sortedBy { it.slotIndex }
        if (sims.isEmpty()) {
            pref.entries = arrayOf(getString(R.string.no_active_sim))
            pref.entryValues = arrayOf("")
            pref.isEnabled = false
        } else {
            pref.entries = sims.map { s ->
                listOfNotNull(s.carrierName, s.phoneNumber, "SIM ${s.simNumber}").joinToString(" · ")
            }.toTypedArray()
            pref.entryValues = sims.map { it.slotIndex.toString() }.toTypedArray()
            if (pref.value == null) pref.value = sims.first().slotIndex.toString()
        }
        pref.isVisible = preferenceManager.sharedPreferences?.getString("messages.sim_selection_mode", "OSDefault") == "Fixed"
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        when (preference.key) {
            "messages.limit_value",
            "messages.log_lifetime_days",
            "messages.send_interval_min",
            "messages.send_interval_max" -> {
                (preference as EditTextPreference).setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_NUMBER
                    it.setSelectAllOnFocus(true)
                    it.selectAll()
                }
            }

            "messages.work_hours_start", "messages.work_hours_end" -> {
                val pref = preference as EditTextPreference
                val currentText = pref.text ?: "09:00"
                val parts = currentText.split(":")
                val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 9
                val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0

                TimePickerDialog(
                    requireContext(),
                    { _, h, m ->
                        pref.text = String.format(java.util.Locale.US, "%02d:%02d", h, m)
                    },
                    hour,
                    minute,
                    true
                ).show()
                return
            }
        }

        super.onDisplayPreferenceDialog(preference)
    }

    private val onPreferenceChanged =
        SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key == "messages.sim_selection_mode") {
                findPreference<DropDownPreference>("messages.default_sim_slot")?.isVisible =
                    sharedPreferences?.getString(key, "OSDefault") == "Fixed"
            }
            if (key == "messages.limit_period") {
                findPreference<EditTextPreference>("messages.limit_value")?.isEnabled =
                    sharedPreferences?.getString(key, "Disabled") != "Disabled"
            }
        }
}