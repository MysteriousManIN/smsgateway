package com.smsgateway

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wrapper around SmsManager — per-backend config aware.
 *
 * Delivery-correctness rules (see QA W1/W2/W8):
 * - One distinct PendingIntent per message PART (unique requestCode). Reusing
 *   the same intent for every part drops all but one carrier result.
 * - Part results are aggregated: a single postStatus per message (all parts
 *   OK -> "sent", else "failed" with the first error). Counters increment once.
 * - Receivers unregister only after ALL parts reported, or after a 90s
 *   timeout failsafe (which reports failed/TIMEOUT and cleans up).
 * - Already-acked message IDs are never re-sent: if postStatus() failed on a
 *   previous poll, the backend may still list the message — resending would
 *   duplicate the SMS on the handset. Status reports themselves are retried
 *   (3x) and then queued in the Prefs outbox, flushed on every poll.
 */
object SmsSender {
    private const val TAG = "SmsGateway"
    private const val ACTION_SENT = "com.smsgateway.SMS_SENT"
    private const val ACTION_DELIVERED = "com.smsgateway.SMS_DELIVERED"
    private const val TIMEOUT_MS = 90_000L
    private const val STATUS_RETRIES = 3

    private val senderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestCodeSeq = AtomicInteger(100_000)
    private val pendingSends = ConcurrentHashMap<String, PendingSend>()

    private class PendingSend(
        val remaining: AtomicInteger,
        val failed: AtomicBoolean = AtomicBoolean(false),
        @Volatile var firstError: String? = null,
        @Volatile var sentReceiver: BroadcastReceiver? = null,
        @Volatile var deliveredReceiver: BroadcastReceiver? = null,
        @Volatile var timeout: Runnable? = null,
        val deliveredReported: AtomicBoolean = AtomicBoolean(false)
    )

    /**
     * Uses the user-selected SIM (Settings) if set, else the system default SMS SIM.
     * Falls back to default if the SIM was removed or the id is invalid.
     */
    private fun pickSmsManager(context: Context): SmsManager {
        val subId = try { Prefs.getInstance(context).simSubscriptionId } catch (_: Exception) { -1 }
        if (subId >= 0) {
            try {
                return SmsManager.getSmsManagerForSubscriptionId(subId)
            } catch (_: Exception) {
                Log.w(TAG, "Selected SIM subId=$subId unavailable — using default")
                LogStore.add("! Selected SIM unavailable — sent via system default SIM")
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    private fun resolveApi(appContext: Context, config: BackendConfig?): SmsApi? = try {
        when {
            config != null -> ApiClient.forConfig(config)
            else -> {
                val prefs = Prefs.getInstance(appContext)
                val enabled = prefs.getEnabledBackends()
                if (enabled.isNotEmpty()) ApiClient.forConfig(enabled.first()) else ApiClient.getApi(appContext)
            }
        }
    } catch (_: Exception) { null }

    fun send(context: Context, msg: SmsMessage, config: BackendConfig? = null) {
        val appContext = context.applicationContext
        val prefs = Prefs.getInstance(appContext)
        // Never re-send an already-acked ID (backend may still list it when our
        // earlier postStatus failed). Duplicate SMS costs money and trust.
        if (prefs.isAcked(msg.id)) {
            Log.d(TAG, "Skip already-acked id=${msg.id}")
            return
        }
        // One send per message at a time — concurrent polls/worker must not race.
        val probe = PendingSend(AtomicInteger(0))
        if (pendingSends.putIfAbsent(msg.id, probe) != null) {
            Log.d(TAG, "Send already in flight id=${msg.id} — skip")
            return
        }

        val statusApi: SmsApi? = resolveApi(appContext, config)

        fun failNow(error: String) {
            pendingSends.remove(msg.id)
            reportStatus(appContext, statusApi, config, msg, "failed", error)
            prefs.incrementFailed()
        }

        try {
            val smsManager = pickSmsManager(appContext)
            val parts = try {
                smsManager.divideMessage(msg.message)
            } catch (e: Exception) {
                failNow(e.message ?: "divide failed")
                return
            }
            val totalParts = parts.size.coerceAtLeast(1)
            probe.remaining.set(totalParts)

            val sentAction = "$ACTION_SENT.${msg.id}"
            val deliveredAction = "$ACTION_DELIVERED.${msg.id}"

            val sentReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val st = pendingSends[msg.id] ?: return
                    if (resultCode != Activity.RESULT_OK) {
                        st.failed.set(true)
                        if (st.firstError == null) {
                            st.firstError = when (resultCode) {
                                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "RESULT_ERROR_GENERIC_FAILURE"
                                SmsManager.RESULT_ERROR_NO_SERVICE -> "RESULT_ERROR_NO_SERVICE"
                                SmsManager.RESULT_ERROR_NULL_PDU -> "RESULT_ERROR_NULL_PDU"
                                SmsManager.RESULT_ERROR_RADIO_OFF -> "RESULT_ERROR_RADIO_OFF"
                                else -> "UNKNOWN_$resultCode"
                            }
                        }
                    }
                    if (st.remaining.decrementAndGet() <= 0) {
                        finishSend(appContext, msg, config, statusApi, st)
                    }
                }
            }
            val deliveredReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (resultCode != Activity.RESULT_OK) return
                    val st = pendingSends[msg.id] ?: return
                    if (!st.deliveredReported.compareAndSet(false, true)) return
                    Log.d(TAG, "SMS delivered id=${msg.id}")
                    LogStore.add("✓ Delivered to ${msg.to}")
                    try { appContext.unregisterReceiver(this) } catch (_: Exception) {}
                    st.deliveredReceiver = null
                    reportStatus(appContext, resolveApi(appContext, config), config, msg, "delivered", null)
                }
            }
            probe.sentReceiver = sentReceiver
            probe.deliveredReceiver = deliveredReceiver

            // Timeout failsafe: never leak receivers, never leave a message hanging.
            val handler = Handler(appContext.mainLooper)
            val timeout = Runnable {
                val st = pendingSends.remove(msg.id)
                if (st != null && st.remaining.get() > 0) {
                    unregisterQuietly(appContext, st.sentReceiver, st.deliveredReceiver)
                    val error = st.firstError ?: "TIMEOUT_NO_CARRIER_RESULT"
                    Log.w(TAG, "Send timeout id=${msg.id}")
                    LogStore.add("✗ Timeout sending to ${msg.to}")
                    reportStatus(appContext, resolveApi(appContext, config), config, msg, "failed", error)
                    prefs.incrementFailed()
                }
            }
            probe.timeout = timeout
            handler.postDelayed(timeout, TIMEOUT_MS)

            val sentFilter = IntentFilter(sentAction)
            val deliveredFilter = IntentFilter(deliveredAction)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(sentReceiver, sentFilter, Context.RECEIVER_NOT_EXPORTED)
                appContext.registerReceiver(deliveredReceiver, deliveredFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(sentReceiver, sentFilter)
                appContext.registerReceiver(deliveredReceiver, deliveredFilter)
            }

            if (totalParts > 1) {
                val sentIntents = ArrayList<PendingIntent>(totalParts)
                val deliveredIntents = ArrayList<PendingIntent>(totalParts)
                for (i in 0 until totalParts) {
                    sentIntents.add(
                        PendingIntent.getBroadcast(
                            appContext,
                            requestCodeSeq.getAndIncrement(),
                            Intent(sentAction).apply { putExtra("msg_id", msg.id); putExtra("part", i) },
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    deliveredIntents.add(
                        PendingIntent.getBroadcast(
                            appContext,
                            requestCodeSeq.getAndIncrement(),
                            Intent(deliveredAction).apply { putExtra("msg_id", msg.id); putExtra("part", i) },
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                }
                smsManager.sendMultipartTextMessage(msg.to, null, parts, sentIntents, deliveredIntents)
                Log.d(TAG, "sendMultipart to=${msg.to} parts=$totalParts id=${msg.id} via ${config?.name}")
            } else {
                val sentIntent = PendingIntent.getBroadcast(
                    appContext,
                    requestCodeSeq.getAndIncrement(),
                    Intent(sentAction).apply { putExtra("msg_id", msg.id) },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val deliveredIntent = PendingIntent.getBroadcast(
                    appContext,
                    requestCodeSeq.getAndIncrement(),
                    Intent(deliveredAction).apply { putExtra("msg_id", msg.id) },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                smsManager.sendTextMessage(msg.to, null, msg.message, sentIntent, deliveredIntent)
                Log.d(TAG, "sendText to=${msg.to} id=${msg.id} via ${config?.name}")
            }
        } catch (e: SecurityException) {
            pendingSends.remove(msg.id)
            Log.e(TAG, "SecurityException sending SMS: ${e.message}")
            LogStore.add("✗ SecurityException: ${e.message}")
            reportStatus(appContext, statusApi, config, msg, "failed", "SecurityException: ${e.message}")
            Prefs.getInstance(appContext).incrementFailed()
        } catch (e: Exception) {
            pendingSends.remove(msg.id)
            Log.e(TAG, "Exception sending SMS: ${e.message}", e)
            LogStore.add("✗ Exception: ${e.message}")
            reportStatus(appContext, statusApi, config, msg, "failed", e.message)
            Prefs.getInstance(appContext).incrementFailed()
        }
    }

    /** Called once all parts of a message have reported. Posts ONE status. */
    private fun finishSend(
        appContext: Context,
        msg: SmsMessage,
        config: BackendConfig?,
        statusApi: SmsApi?,
        st: PendingSend
    ) {
        pendingSends.remove(msg.id)
        try { Handler(appContext.mainLooper).removeCallbacks(st.timeout ?: return) } catch (_: Exception) {}
        unregisterQuietly(appContext, st.sentReceiver, null)
        val prefs = Prefs.getInstance(appContext)
        if (st.failed.get()) {
            val error = st.firstError ?: "UNKNOWN"
            LogStore.add("✗ Failed $error to ${msg.to}")
            prefs.incrementFailed()
            reportStatus(appContext, statusApi, config, msg, "failed", error)
        } else {
            // Carrier confirmed all parts: never send this ID again, even if our
            // status report itself fails below (it goes to the outbox instead).
            prefs.markAcked(msg.id)
            LogStore.add("✓ Sent to ${msg.to} [${msg.id}] via ${config?.name ?: "gateway"}")
            prefs.incrementSent()
            Log.d(TAG, "SMS sent OK id=${msg.id} to=${msg.to}")
            reportStatus(appContext, statusApi, config, msg, "sent", null)
        }
    }

    private fun unregisterQuietly(appContext: Context, vararg receivers: BroadcastReceiver?) {
        for (r in receivers) {
            if (r == null) continue
            try { appContext.unregisterReceiver(r) } catch (_: Exception) {}
        }
    }

    /** Post with bounded retries; on total failure queue into the outbox. */
    private fun reportStatus(
        appContext: Context,
        api: SmsApi?,
        config: BackendConfig?,
        msg: SmsMessage,
        status: String,
        error: String?
    ) {
        val req = StatusRequest(msg.id, status, error)
        senderScope.launch {
            var ok = postWithRetry(api, req)
            var liveApi = api
            if (!ok) {
                // Config may have changed since send started — resolve again.
                liveApi = resolveApi(appContext, config)
                if (liveApi !== api) ok = postWithRetry(liveApi, req)
            }
            val prefs = Prefs.getInstance(appContext)
            if (ok) {
                prefs.markAcked(msg.id)
                Log.d(TAG, "POST status $status for ${msg.id} via ${config?.name}")
            } else {
                prefs.queuePendingStatus(req)
                LogStore.add("! status '$status' queued for ${msg.to} — will retry")
                Log.e(TAG, "postStatus failed id=${msg.id} — queued")
            }
        }
    }

    private suspend fun postWithRetry(api: SmsApi?, req: StatusRequest): Boolean {
        if (api == null) return false
        val delays = listOf(500L, 2000L, 5000L)
        for (attempt in delays.indices) {
            try {
                api.postStatus(req)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "postStatus attempt ${attempt + 1} failed id=${req.id}: ${e.message}")
                if (attempt < delays.lastIndex) {
                    try { delay(delays[attempt]) } catch (_: Exception) { return false }
                }
            }
        }
        return false
    }

    /**
     * Flush queued status reports for one backend. Called at the start of
     * every poll pass (service + worker) so reports survive process death.
     */
    fun flushPendingStatuses(context: Context, config: BackendConfig) {
        val appContext = context.applicationContext
        val prefs = Prefs.getInstance(appContext)
        val queued = prefs.takePendingStatuses()
        if (queued.isEmpty()) return
        Log.d(TAG, "Flushing ${queued.size} queued statuses via ${config.name}")
        senderScope.launch {
            val api = resolveApi(appContext, config)
            for (req in queued) {
                if (postWithRetry(api, req)) {
                    prefs.markAcked(req.id)
                } else {
                    prefs.queuePendingStatus(req)
                }
            }
        }
    }
}

/**
 * In-memory log store — last 50 entries for RecyclerView
 */
object LogStore {
    private val logs = mutableListOf<String>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

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
