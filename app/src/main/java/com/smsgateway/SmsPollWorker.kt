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
        if (!prefs.isServiceEnabled()) {
            Log.d("SmsGateway", "Worker: service switched OFF, skip")
            return Result.success()
        }
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
            if (config.lastStatus == SmsForegroundService.STATUS_AUTH_ERROR ||
                config.lastStatus == SmsForegroundService.STATUS_CONFIG_ERROR
            ) {
                Log.d("SmsGateway", "Worker: skip ${config.name} (${config.lastStatus})")
                continue
            }
            try {
                SmsSender.flushPendingStatuses(ctx, config)
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
            } catch (e: retrofit2.HttpException) {
                when (e.code()) {
                    401, 403 -> {
                        prefs.setBackendStatus(config.id, SmsForegroundService.STATUS_AUTH_ERROR)
                        LogStore.add("! ${config.name}: token rejected (${e.code()}) — check token, then Test")
                    }
                    404 -> {
                        prefs.setBackendStatus(config.id, SmsForegroundService.STATUS_CONFIG_ERROR)
                        LogStore.add("! ${config.name}: 404 — check Base URL, then Test")
                    }
                    else -> {
                        prefs.setBackendStatus(config.id, "fail")
                        anyFail = true
                    }
                }
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
