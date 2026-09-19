package com.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("SmsGateway", "BootReceiver action=$action")
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val prefs = Prefs.getInstance(context)
            if (prefs.isConfigured()) {
                Log.d("SmsGateway", "Re-starting service after boot")
                LogStore.add("Device reboot — restarting service")
                try {
                    val svc = Intent(context, SmsForegroundService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(svc)
                    } else {
                        context.startService(svc)
                    }
                } catch (e: Exception) {
                    Log.e("SmsGateway", "Boot start failed: ${e.message}", e)
                }
            } else {
                Log.d("SmsGateway", "Not configured — skip boot start")
            }
        }
    }
}
