package com.smsgateway

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var tvBatteryStatus: TextView
    private lateinit var tvPollingInfo: TextView
    private lateinit var switchService: SwitchMaterial
    private lateinit var switchBattery: SwitchMaterial

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = Prefs.getInstance(requireContext())
        tvBatteryStatus = view.findViewById(R.id.tvBatteryStatus)
        tvPollingInfo = view.findViewById(R.id.tvPollingInfo)
        switchService = view.findViewById(R.id.switchService)
        switchBattery = view.findViewById(R.id.switchBattery)

        // Service toggle
        switchService.isChecked = SmsForegroundService.isRunning
        switchService.setOnCheckedChangeListener { _, isChecked ->
            // prevent recursion when we programmatically set
            if (isChecked == SmsForegroundService.isRunning) return@setOnCheckedChangeListener
            toggleService(isChecked)
        }

        // Battery toggle — checked = unrestricted
        updateBatterySwitch()
        switchBattery.setOnCheckedChangeListener { _, isChecked ->
            val pm = requireContext().getSystemService(PowerManager::class.java)
            val ignoring = pm?.isIgnoringBatteryOptimizations(requireContext().packageName) == true
            if (isChecked && !ignoring) {
                requestBatteryExemption()
            } else if (!isChecked && ignoring) {
                Toast.makeText(requireContext(), "Open system settings to re-enable optimization", Toast.LENGTH_LONG).show()
                // revert to checked since we can't re-enable programmatically
                switchBattery.isChecked = true
            }
        }

        // Theme / Appearance selector
        val rgTheme = view.findViewById<RadioGroup>(R.id.rgTheme)
        val rbThemeSystem = view.findViewById<MaterialRadioButton>(R.id.rbThemeSystem)
        val rbThemeLight = view.findViewById<MaterialRadioButton>(R.id.rbThemeLight)
        val rbThemeDark = view.findViewById<MaterialRadioButton>(R.id.rbThemeDark)

        when (Prefs.getThemeMode(requireContext())) {
            Prefs.THEME_LIGHT -> rbThemeLight.isChecked = true
            Prefs.THEME_DARK -> rbThemeDark.isChecked = true
            else -> rbThemeSystem.isChecked = true
        }

        rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.rbThemeLight -> Prefs.THEME_LIGHT
                R.id.rbThemeDark -> Prefs.THEME_DARK
                else -> Prefs.THEME_SYSTEM
            }
            if (newMode != Prefs.getThemeMode(requireContext())) {
                Prefs.setThemeMode(requireContext(), newMode)
                val nightMode = when (newMode) {
                    Prefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    Prefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                AppCompatDelegate.setDefaultNightMode(nightMode)
            }
        }

        // also keep legacy btnBattery if exists (gone)
        view.findViewById<MaterialButton>(R.id.btnBattery)?.setOnClickListener { requestBatteryExemption() }
        view.findViewById<MaterialButton>(R.id.btnClearLogs).setOnClickListener {
            LogStore.clear()
            LogStore.add("Logs cleared")
            Toast.makeText(requireContext(), "Logs cleared", Toast.LENGTH_SHORT).show()
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        // battery status
        val pm = requireContext().getSystemService(PowerManager::class.java)
        val ignoring = pm?.isIgnoringBatteryOptimizations(requireContext().packageName) == true
        tvBatteryStatus.text = if (ignoring) "Status: Unrestricted ✓" else "Status: Optimizing (may delay polling)"
        // avoid listener loop
        if (switchBattery.isChecked != ignoring) {
            switchBattery.setOnCheckedChangeListener(null)
            switchBattery.isChecked = ignoring
            switchBattery.setOnCheckedChangeListener { _, isChecked ->
                val ign2 = pm?.isIgnoringBatteryOptimizations(requireContext().packageName) == true
                if (isChecked && !ign2) requestBatteryExemption() else if (!isChecked && ign2) {
                    Toast.makeText(requireContext(), "Open system settings to re-enable optimization", Toast.LENGTH_LONG).show()
                    switchBattery.isChecked = true
                }
            }
        }

        // service status sync
        val running = SmsForegroundService.isRunning
        if (switchService.isChecked != running) {
            switchService.setOnCheckedChangeListener(null)
            switchService.isChecked = running
            switchService.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked != SmsForegroundService.isRunning) toggleService(isChecked)
            }
        }

        val enabled = prefs.getEnabledBackends().size
        tvPollingInfo.text = "Polls $enabled enabled gateway(s) every 15s. Failures isolated per backend."
        // stats hidden but keep for compat
        view?.findViewById<TextView>(R.id.tvStatsSummary)?.text = "Sent: ${prefs.sentCount} • Failed: ${prefs.failedCount} • Gateways: $enabled/${prefs.getBackends().size} active"
    }

    private fun toggleService(enable: Boolean) {
        if (enable) {
            if (!prefs.isConfigured()) {
                Toast.makeText(requireContext(), "Add & enable a gateway first", Toast.LENGTH_SHORT).show()
                switchService.isChecked = false
                (activity as? MainActivity)?.navigateToGateways()
                return
            }
            if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(requireContext(), getString(R.string.msg_grant_sms_first), Toast.LENGTH_LONG).show()
                (activity as? MainActivity)?.checkPermissions()
                switchService.isChecked = false
                return
            }
            SmsForegroundService.start(requireContext())
            LogStore.add("Service started")
            Toast.makeText(requireContext(), "Service starting…", Toast.LENGTH_SHORT).show()
        } else {
            SmsForegroundService.stop(requireContext())
            LogStore.add("Service stopped by user")
            Toast.makeText(requireContext(), "Service stopping…", Toast.LENGTH_SHORT).show()
        }
        view?.postDelayed({ refresh() }, 800)
    }

    private fun updateBatterySwitch() {
        val pm = requireContext().getSystemService(PowerManager::class.java)
        val ignoring = pm?.isIgnoringBatteryOptimizations(requireContext().packageName) == true
        switchBattery.isChecked = ignoring
    }

    private fun requestBatteryExemption() {
        val pm = requireContext().getSystemService(PowerManager::class.java)
        if (pm != null && pm.isIgnoringBatteryOptimizations(requireContext().packageName)) {
            Toast.makeText(requireContext(), "Already unrestricted", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.battery_title))
            .setMessage(getString(R.string.battery_msg))
            .setPositiveButton(getString(R.string.battery_allow)) { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${requireContext().packageName}")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {
                        Toast.makeText(requireContext(), "Open Settings > Battery > Unrestricted", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.battery_skip), null)
            .show()
    }
}
