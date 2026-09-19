package com.smsgateway

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager periodic fallback — loops over enabled backends
 * // ponytail: simple periodic 15-min, per-backend try/catch
 */
class SmsPollWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs.getInstance(ctx)
        val enabled = prefs.getEnabledBackends()
        if (enabled.isEmpty()) {
            Log.d("SmsGateway", "Worker: no enabled backends, skip")
            return Result.success()
        }
        if (SmsForegroundService.isRunning) {
            Log.d("SmsGateway", "Worker: service already running, skip")
            return Result.success()
        }
        var anyFail = false
        for (config in enabled) {
            try {
                val api = ApiClient.forConfig(config)
                val pending = api.getPending(limit = 10)
                Log.d("SmsGateway", "Worker: ${config.name} fetched ${pending.messages.size} pending")
                for (msg in pending.messages) {
                    try {
                        SmsSender.send(ctx, msg, config)
                    } catch (e: Exception) {
                        Log.e("SmsGateway", "Worker send failed ${config.name} ${msg.id}: ${e.message}")
                        try { api.postStatus(StatusRequest(msg.id, "failed", e.message)) } catch (_: Exception) {}
                    }
                }
                prefs.setBackendStatus(config.id, "ok")
            } catch (e: Exception) {
                Log.e("SmsGateway", "Worker doWork failed ${config.name}: ${e.message}", e)
                LogStore.add("Worker error ${config.name}: ${e.message}")
                prefs.setBackendStatus(config.id, "fail")
                anyFail = true
            }
        }
        prefs.lastPoll = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        return if (anyFail) Result.retry() else Result.success()
    }
}
