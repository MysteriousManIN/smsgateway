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
        if (prefs.isQuotaBlocked()) {
            Log.w("SmsGateway", "Worker: Android SMS Quota is blocked, skip")
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
        val db = AppDatabase.getInstance(ctx)
        // Dispatch cached offline queue from Room DB first
        try {
            val cachedBatch = db.pendingSmsDao().getBatch(limit = 10)
            for (item in cachedBatch) {
                if (prefs.isQuotaBlocked()) break
                val targetBackend = enabled.find { it.id == item.backendId }
                val normTo = SmsValidator.normalizePhone(item.to) ?: item.to
                val msg = SmsMessage(
                    id = item.id,
                    to = normTo,
                    message = item.message,
                    templateId = item.templateId,
                    createdAt = item.createdAt,
                    channel = item.channel
                )
                try {
                    SmsSender.send(ctx, msg, targetBackend)
                    db.pendingSmsDao().deleteById(item.id)
                    kotlinx.coroutines.delay(prefs.smsThrottleDelayMs)
                } catch (e: Exception) {
                    Log.e("SmsGateway", "Worker send cached failed ${item.id}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("SmsGateway", "Worker error dispatching cached Room queue: ${e.message}")
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
                    if (prefs.isQuotaBlocked()) {
                        Log.w("SmsGateway", "Worker: Quota hit during dispatch, stopping batch")
                        break
                    }
                    if (prefs.isAcked(msg.id)) {
                        Log.d("SmsGateway", "Worker skip already-acked id=${msg.id}")
                        continue
                    }
                    val normalizedTo = SmsValidator.normalizePhone(msg.to)
                    if (normalizedTo == null || !SmsValidator.isValidMessage(msg.message)) {
                        Log.w("SmsGateway", "Worker skip invalid message id=${msg.id}")
                        try {
                            api.postStatus(StatusRequest(msg.id, "failed", "Invalid phone format or message body", channel = msg.channel ?: "sms"))
                        } catch (_: Exception) {}
                        continue
                    }
                    try {
                        SmsSender.send(ctx, msg.copy(to = normalizedTo), config)
                        kotlinx.coroutines.delay(prefs.smsThrottleDelayMs)
                    } catch (e: Exception) {
                        Log.e("SmsGateway", "Worker send failed ${config.name} ${msg.id}: ${e.message}")
                        try { api.postStatus(StatusRequest(msg.id, "failed", e.message, channel = msg.channel ?: "sms")) } catch (_: Exception) {}
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
                val hint = (e.message ?: "").contains("Required value", ignoreCase = true)
                LogStore.add(
                    if (hint) "Worker error ${config.name}: backend JSON missing id/to/message — update backend or app"
                    else "Worker error ${config.name}: ${e.message}"
                )
                prefs.setBackendStatus(config.id, "fail")
                anyFail = true
            }
        }
        prefs.lastPoll = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        return if (anyFail) Result.retry() else Result.success()
    }
}
