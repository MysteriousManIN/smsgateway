package com.smsgateway

import com.squareup.moshi.JsonClass
import java.util.UUID

@JsonClass(generateAdapter = true)
data class BackendConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val token: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastStatus: String? = null, // ok / fail / null
    val lastPollAt: Long? = null
) {
    fun normalizedBaseUrl(): String? {
        if (!baseUrl.startsWith("https://")) return null
        var url = baseUrl.trim().trimEnd('/')
        // Accept any existing API path (e.g. /api/v1/sms, /api/v2/sms):
        // only append the default when no /api/ segment is present yet.
        if (url.contains("/api/")) {
            // Drop a pasted operation suffix (.../pending|status|send|logs).
            for (op in listOf("/pending", "/status", "/send", "/logs")) {
                if (url.endsWith(op)) {
                    url = url.removeSuffix(op)
                    break
                }
            }
        } else {
            url += "/api/v1/sms"
        }
        if (!url.endsWith("/")) url += "/"
        return url
    }

    fun maskedUrl(): String = baseUrl

    fun maskedToken(): String = if (token.length <= 8) "••••" else token.take(4) + "••••" + token.takeLast(4)
}
