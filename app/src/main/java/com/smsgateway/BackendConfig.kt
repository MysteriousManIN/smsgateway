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
        if (!url.contains("/api/v1/sms")) url += "/api/v1/sms"
        if (!url.endsWith("/")) url += "/"
        return url
    }

    fun maskedUrl(): String = baseUrl

    fun maskedToken(): String = if (token.length <= 8) "••••" else token.take(4) + "••••" + token.takeLast(4)
}
