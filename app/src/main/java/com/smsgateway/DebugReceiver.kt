package com.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("SmsGateway", "DebugReceiver ${intent.action}")
        when (intent.action) {
            "com.smsgateway.ADD_DUMMY" -> {
                val prefs = Prefs.getInstance(context)
                prefs.saveBackends(emptyList())
                prefs.addBackend(BackendConfig(name = "Backend 1", baseUrl = "https://backend1.example.com", token = "dummy_token_backend1_12345", enabled = true))
                prefs.addBackend(BackendConfig(name = "Backend 2", baseUrl = "https://backend2.example.com", token = "dummy_token_backend2_67890", enabled = true))
                LogStore.add("Added dummy gateways Backend 1/Backend 2 via DebugReceiver")
                Log.d("SmsGateway", "Added dummy gateways")
            }
            "com.smsgateway.CLEAR_GATEWAYS" -> {
                Prefs.getInstance(context).saveBackends(emptyList())
                LogStore.add("Cleared gateways via DebugReceiver")
            }
            "com.smsgateway.START_SERVICE" -> {
                try {
                    SmsForegroundService.start(context)
                    LogStore.add("Service start triggered via DebugReceiver")
                } catch (e: Exception) {
                    Log.e("SmsGateway", "start failed ${e.message}")
                }
            }
        }
    }
}
