package com.smsgateway

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Wrapper around SmsManager — per-backend config aware.
 * // ponytail: no multi-SIM UI, no Room — single default SmsManager is enough for v1
 */
object SmsSender {
    private const val TAG = "SmsGateway"
    private const val ACTION_SENT = "com.smsgateway.SMS_SENT"
    private const val ACTION_DELIVERED = "com.smsgateway.SMS_DELIVERED"

    fun send(context: Context, msg: SmsMessage, config: BackendConfig? = null) {
        val appContext = context.applicationContext
        // resolve api for status callbacks: if config provided use that, else fallback to first backend / legacy
        val statusApi: SmsApi? = try {
            when {
                config != null -> ApiClient.forConfig(config)
                else -> {
                    val prefs = Prefs.getInstance(appContext)
                    val enabled = prefs.getEnabledBackends()
                    if (enabled.isNotEmpty()) ApiClient.forConfig(enabled.first()) else ApiClient.getApi(appContext)
                }
            }
        } catch (_: Exception) { null }

        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appContext.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val sentIntent = PendingIntent.getBroadcast(
                appContext,
                msg.id.hashCode(),
                Intent("$ACTION_SENT.${msg.id}").apply { putExtra("msg_id", msg.id) },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val deliveredIntent = PendingIntent.getBroadcast(
                appContext,
                msg.id.hashCode() + 1,
                Intent("$ACTION_DELIVERED.${msg.id}").apply { putExtra("msg_id", msg.id) },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val sentReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val id = intent?.getStringExtra("msg_id") ?: msg.id
                    val status: String
                    val error: String?
                    when (resultCode) {
                        Activity.RESULT_OK -> {
                            status = "sent"; error = null
                            LogStore.add("✓ Sent to ${msg.to} [$id] via ${config?.name ?: "gateway"}")
                            Prefs.getInstance(appContext).incrementSent()
                            Log.d(TAG, "SMS sent OK id=$id to=${msg.to}")
                        }
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> { status = "failed"; error = "RESULT_ERROR_GENERIC_FAILURE"; Prefs.getInstance(appContext).incrementFailed(); LogStore.add("✗ Failed GENERIC to ${msg.to}") }
                        SmsManager.RESULT_ERROR_NO_SERVICE -> { status = "failed"; error = "RESULT_ERROR_NO_SERVICE"; Prefs.getInstance(appContext).incrementFailed(); LogStore.add("✗ No service to ${msg.to}") }
                        SmsManager.RESULT_ERROR_NULL_PDU -> { status = "failed"; error = "RESULT_ERROR_NULL_PDU"; Prefs.getInstance(appContext).incrementFailed() }
                        SmsManager.RESULT_ERROR_RADIO_OFF -> { status = "failed"; error = "RESULT_ERROR_RADIO_OFF"; Prefs.getInstance(appContext).incrementFailed() }
                        else -> { status = "failed"; error = "UNKNOWN_$resultCode"; Prefs.getInstance(appContext).incrementFailed() }
                    }
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val api = statusApi ?: run {
                                val p = Prefs.getInstance(appContext)
                                val eb = p.getEnabledBackends()
                                if (eb.isNotEmpty() && config != null) ApiClient.forConfig(config) else null
                            }
                            api?.postStatus(StatusRequest(id, status, error))
                            Log.d(TAG, "POST status $status for $id via ${config?.name}")
                        } catch (e: Exception) {
                            Log.e(TAG, "postStatus failed id=$id: ${e.message}")
                            LogStore.add("! postStatus failed $id: ${e.message}")
                        }
                    }
                    try { appContext.unregisterReceiver(this) } catch (_: Exception) {}
                }
            }
            val deliveredReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val id = intent?.getStringExtra("msg_id") ?: msg.id
                    if (resultCode == Activity.RESULT_OK) {
                        Log.d(TAG, "SMS delivered id=$id")
                        LogStore.add("✓ Delivered to ${msg.to}")
                        CoroutineScope(Dispatchers.IO).launch {
                            try { statusApi?.postStatus(StatusRequest(id, "delivered", null)) } catch (e: Exception) { Log.e(TAG, "delivered postStatus failed: ${e.message}") }
                        }
                    }
                    try { appContext.unregisterReceiver(this) } catch (_: Exception) {}
                }
            }

            val sentFilter = IntentFilter("$ACTION_SENT.${msg.id}")
            val deliveredFilter = IntentFilter("$ACTION_DELIVERED.${msg.id}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(sentReceiver, sentFilter, Context.RECEIVER_NOT_EXPORTED)
                appContext.registerReceiver(deliveredReceiver, deliveredFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(sentReceiver, sentFilter)
                appContext.registerReceiver(deliveredReceiver, deliveredFilter)
            }

            val parts = smsManager.divideMessage(msg.message)
            if (parts.size > 1) {
                val sentIntents = ArrayList<PendingIntent>(parts.size).apply { for (i in parts.indices) add(if (i == 0) sentIntent else sentIntent) }
                val deliveredIntents = ArrayList<PendingIntent>(parts.size).apply { for (i in parts.indices) add(if (i == 0) deliveredIntent else deliveredIntent) }
                smsManager.sendMultipartTextMessage(msg.to, null, parts, sentIntents, deliveredIntents)
                Log.d(TAG, "sendMultipart to=${msg.to} parts=${parts.size} id=${msg.id} via ${config?.name}")
            } else {
                smsManager.sendTextMessage(msg.to, null, msg.message, sentIntent, deliveredIntent)
                Log.d(TAG, "sendText to=${msg.to} id=${msg.id} via ${config?.name}")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException sending SMS: ${e.message}")
            LogStore.add("✗ SecurityException: ${e.message}")
            CoroutineScope(Dispatchers.IO).launch {
                try { statusApi?.postStatus(StatusRequest(msg.id, "failed", "SecurityException: ${e.message}")) } catch (_: Exception) {}
            }
            Prefs.getInstance(appContext).incrementFailed()
        } catch (e: Exception) {
            Log.e(TAG, "Exception sending SMS: ${e.message}", e)
            LogStore.add("✗ Exception: ${e.message}")
            CoroutineScope(Dispatchers.IO).launch {
                try { statusApi?.postStatus(StatusRequest(msg.id, "failed", e.message)) } catch (_: Exception) {}
            }
            Prefs.getInstance(appContext).incrementFailed()
        }
    }
}

/**
 * In-memory log store — last 50 entries for RecyclerView
 * // ponytail: no Room DB for v1 — backend is source of truth, in-memory is enough
 */
object LogStore {
    private val logs = mutableListOf<String>()
    private val listeners = mutableListOf<() -> Unit>()

    @Synchronized
    fun add(entry: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        logs.add(0, "[$ts] $entry")
        if (logs.size > 50) logs.removeAt(logs.size - 1)
        listeners.forEach { it.invoke() }
        Log.d("SmsGateway", entry)
    }

    @Synchronized
    fun getAll(): List<String> = logs.toList()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    @Synchronized
    fun clear() { logs.clear(); listeners.forEach { it.invoke() } }

    @Synchronized
    fun clearMatching(predicate: (String) -> Boolean) {
        logs.removeAll { predicate(it) }
        listeners.forEach { it.invoke() }
    }

    @Synchronized
    fun removeLogsContaining(query: String) {
        if (query.isBlank()) return
        logs.removeAll { it.contains(query, ignoreCase = true) }
        listeners.forEach { it.invoke() }
    }
}
