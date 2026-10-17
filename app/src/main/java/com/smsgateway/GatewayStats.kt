package com.smsgateway

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GatewayStats(
    val sentToday: Int = 0,
    val failedToday: Int = 0,
    val totalSent: Int = 0,
    val totalFailed: Int = 0,
    val totalLatencyMs: Long = 0,
    val latencyCount: Int = 0,
    val lastLatencyMs: Long = 0,
    val lastDate: String = "" // "yyyy-MM-dd"
) {
    val successRate: String
        get() {
            val total = sentToday + failedToday
            if (total == 0) {
                val allTime = totalSent + totalFailed
                return if (allTime > 0) "${(totalSent * 100) / allTime}%" else "—"
            }
            return "${(sentToday * 100) / total}%"
        }

    val avgLatencyMs: Long
        get() = if (latencyCount > 0) totalLatencyMs / latencyCount else lastLatencyMs
}

