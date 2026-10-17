package com.smsgateway

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.*
import retrofit2.HttpException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class SmsForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null
    private val backendFailureSince = mutableMapOf<String, Long>()

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        isRunning = true
        Log.d(TAG, "Service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Stop action received")
            try { Prefs.getInstance(this).serviceEnabled = false } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_CLEAR_ACKED) {
            Log.d(TAG, "Clear acked action received")
            Prefs.getInstance(this).clearAcked()
            LogStore.add("Acked cache reset")
            return START_STICKY
        }

        if (intent?.action == ACTION_SEND_NOW) {
            val to = intent.getStringExtra("to") ?: ""
            val message = intent.getStringExtra("message") ?: ""
            val channel = intent.getStringExtra("channel") ?: "sms"
            if (to.isNotBlank() && message.isNotBlank()) {
                Log.d(TAG, "Direct send requested: to=$to msg='$message'")
                LogStore.add("Direct dispatch to $to: '$message'")
                val testMsg = SmsMessage(
                    id = "direct-" + System.currentTimeMillis(),
                    to = to,
                    message = message,
                    channel = channel
                )
                SmsSender.send(this, testMsg)
            }
            return START_STICKY
        }

        try { Prefs.getInstance(this).serviceEnabled = true } catch (_: Exception) {}

        startForeground(NOTIF_ID, buildNotification("Starting…", 0, 0, 0))

        try {
            val work = PeriodicWorkRequestBuilder<SmsPollWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, work
            )
            Log.d(TAG, "WorkManager periodic enqueued")
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager enqueue failed: ${e.message}")
        }

        if (pollingJob == null || pollingJob?.isActive == false) {
            pollingJob = scope.launch { pollingLoop() }
        }

        return START_STICKY
    }

    private fun getBackendAlertId(backendId: String): Int {
        return NotificationHelper.NOTIF_ID_BACKEND_ALERT_BASE + (backendId.hashCode() and 0x7FFF)
    }

    private suspend fun pollingLoop() {
        val prefs = Prefs.getInstance(this)
        val db = AppDatabase.getInstance(this)
        Log.d(TAG, "Polling loop started — offline Room queue + multi-backend")

        while (currentCoroutineContext().isActive) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sandesh:poll").apply {
                    acquire(60_000L)
                }
            } catch (_: Exception) {}

            try {
                val enabled = prefs.getEnabledBackends()
                if (enabled.isEmpty()) {
                    Log.d(TAG, "No enabled backends — skip poll")
                    updateNotification("No gateways configured", prefs.sentCount, prefs.failedCount, 0)
                    delay(15_000)
                    continue
                }

                val isOnline = isNetworkAvailable()
                var lastPollStr = ""

                // ---------------- STEP 1: Fetch-ahead into Room (Online only) ----------------
                if (isOnline) {
                    for (config in enabled) {
                        if (config.lastStatus == STATUS_AUTH_ERROR || config.lastStatus == STATUS_CONFIG_ERROR) {
                            Log.d(TAG, "Skip ${config.name} (${config.lastStatus}) — re-test to resume")
                            continue
                        }
                        try {
                            Log.d(TAG, "Polling backend: ${config.name} (${config.baseUrl})")
                            // Deliver pending outbox delivery reports
                            SmsSender.flushPendingStatuses(this, config)

                            val api = ApiClient.forConfig(config)
                            val callStart = System.currentTimeMillis()
                            val pending = api.getPending(limit = 25)
                            val latencyMs = System.currentTimeMillis() - callStart
                            prefs.recordGatewayLatency(config.id, latencyMs)
                            val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                            lastPollStr = now
                            prefs.lastPoll = now

                            val unacked = pending.messages.filterNot { prefs.isAcked(it.id) }
                            if (unacked.isNotEmpty()) {
                                val entities = unacked.map { msg ->
                                    PendingSmsEntity(
                                        id = msg.id,
                                        backendId = config.id,
                                        to = msg.to,
                                        message = msg.message,
                                        templateId = msg.templateId,
                                        createdAt = msg.createdAt,
                                        channel = msg.channel
                                    )
                                }
                                val inserted = db.pendingSmsDao().insertAll(entities)
                                Log.d(TAG, "Cached ${inserted.size} messages to Room from ${config.name}")
                            }

                            // Successful poll: clear failure tracker and cancel alerts if any
                            val hadFailure = backendFailureSince.remove(config.id)
                            if (hadFailure != null) {
                                NotificationHelper.cancelAlert(this, getBackendAlertId(config.id))
                            }
                            prefs.setBackendStatus(config.id, "ok")

                        } catch (e: HttpException) {
                            when (e.code()) {
                                401, 403 -> {
                                    Log.e(TAG, "Auth failed backend ${config.name} (${e.code()}) — pausing backend")
                                    LogStore.add("! ${config.name}: token rejected (${e.code()}) — check token, then Test")
                                    prefs.setBackendStatus(config.id, STATUS_AUTH_ERROR)
                                }
                                404 -> {
                                    Log.e(TAG, "Not found backend ${config.name} — pausing backend")
                                    LogStore.add("! ${config.name}: 404 — check Base URL, then Test")
                                    prefs.setBackendStatus(config.id, STATUS_CONFIG_ERROR)
                                }
                                else -> {
                                    Log.e(TAG, "HTTP ${e.code()} backend ${config.name}: ${e.message}")
                                    LogStore.add("Server error ${config.name}: HTTP ${e.code()}")
                                    prefs.setBackendStatus(config.id, "fail")
                                    checkBackendDownAlert(config)
                                }
                            }
                        } catch (e: IOException) {
                            Log.e(TAG, "Network error backend ${config.name}: ${e.message}")
                            LogStore.add("Network error ${config.name}: ${e.message}")
                            prefs.setBackendStatus(config.id, "fail")
                            checkBackendDownAlert(config)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Polling error backend ${config.name}: ${e.message}", e)
                            val hint = (e.message ?: "").contains("Required value", ignoreCase = true)
                            LogStore.add(
                                if (hint) "Poll error ${config.name}: backend JSON missing id/to/message"
                                else "Poll error ${config.name}: ${e.message}"
                            )
                            prefs.setBackendStatus(config.id, "fail")
                            checkBackendDownAlert(config)
                        }
                    }
                }

                // ---------------- STEP 2: Dispatch from Local Room Queue (Online OR Offline) ----------------
                if (prefs.isQuotaBlocked()) {
                    val remainingMinutes = ((prefs.quotaBlockedUntil - System.currentTimeMillis()) / 60000L).coerceAtLeast(1)
                    LogStore.setHeartbeat("⚠️ OS SMS rate limit cooldown active (~${remainingMinutes}m remaining)")
                } else {
                    val pendingBatch = db.pendingSmsDao().getBatch(limit = 10)
                    if (pendingBatch.isNotEmpty()) {
                        Log.d(TAG, "Dispatching ${pendingBatch.size} SMS from Room queue")
                        for (entity in pendingBatch) {
                            if (prefs.isQuotaBlocked()) break

                            val cfg = enabled.find { it.id == entity.backendId }
                                ?: prefs.getBackends().find { it.id == entity.backendId }

                            val normalizedTo = SmsValidator.normalizePhone(entity.to)
                            if (normalizedTo == null || !SmsValidator.isValidMessage(entity.message)) {
                                Log.w(TAG, "Skip invalid message id=${entity.id} to='${entity.to}'")
                                LogStore.add("! Skip invalid message to '${entity.to}' [${entity.id}]")
                                db.pendingSmsDao().deleteById(entity.id)
                                if (cfg != null) {
                                    prefs.queuePendingStatus(
                                        cfg.id,
                                        StatusRequest(
                                            entity.id,
                                            "failed",
                                            "Invalid phone format or message body",
                                            channel = entity.channel ?: "sms"
                                        )
                                    )
                                }
                                continue
                            }

                            // Send via SIM
                            SmsSender.send(this, entity.toSmsMessage().copy(to = normalizedTo), cfg)
                            db.pendingSmsDao().deleteById(entity.id)

                            // Configurable throttle delay between SMS
                            val throttleDelay = prefs.smsThrottleDelayMs
                            if (throttleDelay > 0) {
                                delay(throttleDelay)
                            }
                        }
                    }
                }

                // ---------------- STEP 3: Post-pass notification & timing ----------------
                val localQueueCount = db.pendingSmsDao().count()
                prefs.pendingCount = localQueueCount

                if (!isOnline) {
                    if (localQueueCount > 0) {
                        LogStore.setHeartbeat("Offline dispatch: $localQueueCount remaining in local queue")
                        updateNotification("Offline mode — $localQueueCount in queue", prefs.sentCount, prefs.failedCount, enabled.size)
                        delay(3_000L) // quickly process next batch in offline mode
                    } else {
                        LogStore.setHeartbeat("Offline mode — local queue empty, retry 30s")
                        updateNotification("Offline — local queue empty", prefs.sentCount, prefs.failedCount, enabled.size)
                        delay(30_000L)
                    }
                } else {
                    val notifContent = if (localQueueCount > 0) "$localQueueCount in queue | $lastPollStr" else "Last poll $lastPollStr | idle"
                    updateNotification(notifContent, prefs.sentCount, prefs.failedCount, enabled.size)
                    delay(15_000L)
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Polling loop error: ${e.message}", e)
                LogStore.add("Poll error: ${e.message}")
                updateNotification("Error — retry 15s", Prefs.getInstance(this).sentCount, Prefs.getInstance(this).failedCount, Prefs.getInstance(this).getEnabledBackends().size)
                delay(15_000L)
            }
        }
    }

    private fun checkBackendDownAlert(config: BackendConfig) {
        val firstFail = backendFailureSince.getOrPut(config.id) { System.currentTimeMillis() }
        val downDurationMs = System.currentTimeMillis() - firstFail
        if (downDurationMs >= 10 * 60 * 1000L) { // 10 minutes
            NotificationHelper.postLoudAlert(
                this,
                getBackendAlertId(config.id),
                "⚠️ Gateway Down: ${config.name}",
                "Backend ${config.name} has been unreachable for >= 10 minutes. Check network or server status."
            )
        }
    }

    private fun updateNotification(content: String, sent: Int, failed: Int, gatewayCount: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(content, sent, failed, gatewayCount))
    }

    private fun buildNotification(content: String, sent: Int, failed: Int, gatewayCount: Int): Notification {
        val stopIntent = Intent(this, SmsForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPI = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPI = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val prefs = Prefs.getInstance(this)
        val last = prefs.lastPoll ?: "—"
        val fullContent = "$gatewayCount gateways • $content | Sent:$sent Failed:$failed | Last:$last"

        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(fullContent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullContent))
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .setContentIntent(openPI)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPI)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            if (Prefs.getInstance(this).isServiceEnabled()) {
                Log.d(TAG, "Task removed — restarting (service is ON)")
                start(this)
            }
        } catch (e: Exception) {
            val isBlocked = e.javaClass.simpleName == "ForegroundServiceStartNotAllowedException" ||
                (e.message ?: "").contains("NotAllowed", ignoreCase = true)
            Log.e(TAG, "Task-removed restart ${if (isBlocked) "blocked by OS" else "failed"}: ${e.message} — keeping WorkManager fallback")
            try {
                val work = PeriodicWorkRequestBuilder<SmsPollWorker>(15, TimeUnit.MINUTES).build()
                WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, work
                )
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        pollingJob?.cancel()
        scope.cancel()
        try {
            if (!Prefs.getInstance(this).isServiceEnabled()) {
                WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME)
                Log.d(TAG, "WorkManager cancelled (user stop)")
            } else {
                val work = PeriodicWorkRequestBuilder<SmsPollWorker>(15, TimeUnit.MINUTES).build()
                WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, work
                )
                Log.d(TAG, "WorkManager kept (system kill, service is ON)")
            }
        } catch (_: Exception) {}
        Log.d(TAG, "Service onDestroy")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "SmsGateway"
        const val NOTIF_ID = 1001
        const val WORK_NAME = "sms_poll_fallback"
        const val ACTION_STOP = "STOP_SERVICE"
        const val ACTION_CLEAR_ACKED = "CLEAR_ACKED"
        const val ACTION_SEND_NOW = "SEND_NOW"
        const val STATUS_AUTH_ERROR = "auth-error"
        const val STATUS_CONFIG_ERROR = "config-error"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            try { Prefs.getInstance(context).serviceEnabled = true } catch (_: Exception) {}
            val i = Intent(context, SmsForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            try { Prefs.getInstance(context).serviceEnabled = false } catch (_: Exception) {}
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            } catch (_: Exception) {}
            context.stopService(Intent(context, SmsForegroundService::class.java))
        }
    }
}
