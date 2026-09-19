package com.smsgateway

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.*
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class SmsForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isRunning = true
        Log.d(TAG, "Service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Stop action received")
            stopSelf()
            return START_NOT_STICKY
        }

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

    private suspend fun pollingLoop() {
        var backoffMs = 15_000L
        val prefs = Prefs.getInstance(this)
        Log.d(TAG, "Polling loop started — 15s interval, multi-backend")
        while (currentCoroutineContext().isActive) {
            try {
                val enabled = prefs.getEnabledBackends()
                if (enabled.isEmpty()) {
                    Log.d(TAG, "No enabled backends — skip poll")
                    updateNotification("No gateways configured", prefs.sentCount, prefs.failedCount, 0)
                    delay(15_000)
                    continue
                }
                if (!isNetworkAvailable()) {
                    Log.d(TAG, "No network — backoff 30s")
                    LogStore.add("No network — waiting 30s")
                    updateNotification("Offline — retry 30s", prefs.sentCount, prefs.failedCount, enabled.size)
                    delay(30_000)
                    continue
                }

                var totalPending = 0
                var anySuccess = false
                var lastPollStr = ""

                for (config in enabled) {
                    try {
                        Log.d(TAG, "Polling backend: ${config.name} (${config.baseUrl})")
                        val api = ApiClient.forConfig(config)
                        val pending = api.getPending(limit = 10)
                        val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                        lastPollStr = now
                        prefs.lastPoll = now

                        if (pending.messages.isEmpty()) {
                            Log.d(TAG, "Poll ${config.name} $now — 0 pending")
                            prefs.setBackendStatus(config.id, "ok")
                        } else {
                            Log.d(TAG, "Poll ${config.name} $now — ${pending.messages.size} pending")
                            LogStore.add("Poll ${config.name} $now — ${pending.messages.size} pending")
                            totalPending += pending.messages.size
                            for (msg in pending.messages) {
                                SmsSender.send(this, msg, config)
                                delay(500)
                            }
                            prefs.setBackendStatus(config.id, "ok")
                        }
                        anySuccess = true
                    } catch (e: IOException) {
                        Log.e(TAG, "Network error backend ${config.name}: ${e.message}")
                        LogStore.add("Network error ${config.name}: ${e.message}")
                        prefs.setBackendStatus(config.id, "fail")
                        // don't block other backends — continue
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Polling error backend ${config.name}: ${e.message}", e)
                        LogStore.add("Poll error ${config.name}: ${e.message}")
                        prefs.setBackendStatus(config.id, "fail")
                    }
                }

                if (anySuccess) backoffMs = 15_000L

                val notifContent = if (totalPending > 0) "Last poll $lastPollStr | $totalPending pending" else "Last poll $lastPollStr | idle"
                updateNotification(notifContent, prefs.sentCount, prefs.failedCount, enabled.size)

                // if no backend succeeded, backoff
                if (!anySuccess) {
                    backoffMs = if (backoffMs == 15_000L) 30_000L else 60_000L
                    if (backoffMs > 60_000L) backoffMs = 60_000L
                    updateNotification("All backends error — retry ${backoffMs/1000}s", prefs.sentCount, prefs.failedCount, enabled.size)
                    delay(backoffMs)
                } else {
                    delay(15_000)
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Polling loop error: ${e.message}", e)
                LogStore.add("Poll error: ${e.message}")
                updateNotification("Error — retry 15s", Prefs.getInstance(this).sentCount, Prefs.getInstance(this).failedCount, Prefs.getInstance(this).getEnabledBackends().size)
                delay(15_000)
            }
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("smsgateway active")
            .setContentText(fullContent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullContent))
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .setContentIntent(openPI)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPI)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.channel_desc) }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        pollingJob?.cancel()
        scope.cancel()
        try {
            WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "WorkManager cancelled on destroy")
        } catch (_: Exception) {}
        Log.d(TAG, "Service onDestroy")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "SmsGateway"
        const val CHANNEL_ID = "sms_gateway"
        const val NOTIF_ID = 1001
        const val WORK_NAME = "sms_poll_fallback"
        const val ACTION_STOP = "STOP_SERVICE"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val i = Intent(context, SmsForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SmsForegroundService::class.java))
        }
    }
}
