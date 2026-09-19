package com.smsgateway

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
    override fun onCreate() {
        val mode = when (Prefs.getThemeMode(this)) {
            Prefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            Prefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
        super.onCreate()
        Log.d("SmsGateway", "App onCreate — sms gateway v1.0.0 multi-backend")
        // ponytail: auto-seed 2 dummy gateways for fresh installs to demo multi-backend (remove after verification if unwanted)
        try {
            val prefs = Prefs.getInstance(this)
            if (prefs.getBackends().isEmpty()) {
                // keep for demo; comment out to start empty
                // prefs.addBackend(BackendConfig(name = "ERP1", baseUrl = "https://erp1.example.com", token = "dummy_token_erp1_12345"))
                // prefs.addBackend(BackendConfig(name = "ERP2", baseUrl = "https://erp2.example.com", token = "dummy_token_erp2_67890"))
                Log.d("SmsGateway", "No gateways yet — ready to add via UI")
            }
        } catch (e: Exception) {
            Log.e("SmsGateway", "seed failed: ${e.message}")
        }
    }
}
