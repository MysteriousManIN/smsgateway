package com.smsgateway

import android.content.Context
import com.squareup.moshi.FromJson
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

/** Shared Moshi instance — tolerant SMS adapters first, Kotlin fallback second. */
fun newAppMoshi(): Moshi = Moshi.Builder()
    .add(SmsMessageAdapter)
    .add(PendingResponseAdapter)
    .add(KotlinJsonAdapterFactory())
    .build()

/**
 * Canonical message model. The wire format varies across backends:
 * - destination may arrive as `to` (API doc) or `recipient` (DB column),
 *   also tolerated: `phone`, `phoneNumber`, `destination`.
 * - body may arrive as `message` (API doc) or `body`/`text`.
 * - template may arrive as `templateId` (API doc) or `template_id` (DB column).
 * - `id` may be a JSON string or number.
 * - extra keys (e.g. `"channel": "rcs"`) are ignored: Android exposes no
 *   third-party RCS send API, so everything is dispatched as SMS via
 *   SmsManager (carrier SMS, incl. multipart). An RCS-flagged payload with a
 *   text body still sends as SMS; rich-card-only payloads without text fail
 *   validation and are reported as `failed`, never silently dropped.
 */
@JsonClass(generateAdapter = true)
data class SmsMessage(
    val id: String,
    val to: String,
    val message: String,
    val templateId: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    val channel: String? = null
)

/** Lenient decoder: accepts every known backend alias, extracts RCS text, emits canonical shape. */
object SmsMessageAdapter {
    private fun extractText(v: Any?): String? {
        if (v == null) return null
        return when (v) {
            is String -> if (v.isNotBlank()) v.trim() else null
            is Number -> {
                val d = v.toDouble()
                if (d % 1.0 == 0.0) d.toLong().toString() else v.toString()
            }
            is Boolean -> v.toString()
            is Map<*, *> -> {
                val priorityKeys = listOf(
                    "text", "body", "message", "content", "fallback", "fallbackText",
                    "fallback_text", "smsFallback", "sms_fallback", "description",
                    "title", "caption", "textMessage", "plainText"
                )
                for (k in priorityKeys) {
                    val found = extractText(v[k])
                    if (!found.isNullOrBlank()) return found
                }
                for ((_, nested) in v) {
                    val found = extractText(nested)
                    if (!found.isNullOrBlank()) return found
                }
                null
            }
            is List<*> -> {
                for (item in v) {
                    val found = extractText(item)
                    if (!found.isNullOrBlank()) return found
                }
                null
            }
            else -> null
        }
    }

    @FromJson
    @Suppress("UNUSED")
    fun fromJson(map: Map<String, Any?>): SmsMessage {
        fun str(vararg keys: String): String? {
            for (k in keys) {
                val v = map[k] ?: continue
                val text = extractText(v)
                if (!text.isNullOrBlank()) return text
            }
            return null
        }

        val to = str("to", "recipient", "phone", "phoneNumber", "phone_number", "destination", "msisdn", "mobile", "receiver")
            ?: throw com.squareup.moshi.JsonDataException("Required value 'to'/'recipient' missing")

        val channelRaw = str("channel", "type", "messageType", "msgType", "route", "medium")?.lowercase()
        val hasRcsPayload = map["rcs"] != null || map["richCard"] != null || map["cards"] != null || channelRaw?.contains("rcs") == true
        val channel = if (hasRcsPayload) "rcs" else (channelRaw ?: "sms")

        val message = str(
            "message", "body", "text", "content", "smsFallback", "fallbackText",
            "fallback_text", "sms_fallback", "rcs", "richCard", "card", "data"
        ) ?: throw com.squareup.moshi.JsonDataException("Required value 'message' missing")

        val id = str("id", "_id", "messageId", "message_id", "uuid", "msgId")
            ?: "msg-${System.currentTimeMillis()}-${(1000..9999).random()}"

        val templateId = str("templateId", "template_id", "templateID", "dltTemplateId", "dlt_template_id", "template")
        val createdAt = str("created_at", "createdAt", "created")

        return SmsMessage(
            id = id,
            to = to,
            message = message,
            templateId = templateId,
            createdAt = createdAt,
            channel = channel
        )
    }

    @ToJson
    @Suppress("UNUSED")
    fun toJson(msg: SmsMessage): Map<String, Any?> = buildMap {
        put("id", msg.id)
        put("to", msg.to)
        put("message", msg.message)
        if (msg.templateId != null) put("templateId", msg.templateId)
        if (msg.createdAt != null) put("created_at", msg.createdAt)
        if (msg.channel != null) put("channel", msg.channel)
    }
}

@JsonClass(generateAdapter = true)
data class PendingResponse(
    val messages: List<SmsMessage> = emptyList()
)

/** Tolerates `{ "messages": [...] }` (API doc) and `{ "data": [...] }` wrappers. */
object PendingResponseAdapter {
    @FromJson
    @Suppress("UNUSED")
    fun fromJson(map: Map<String, Any?>): PendingResponse {
        @Suppress("UNCHECKED_CAST")
        val raw = (map["messages"] as? List<Map<String, Any?>>)
            ?: (map["data"] as? List<Map<String, Any?>>)
            ?: (map["items"] as? List<Map<String, Any?>>)
            ?: emptyList()
        return PendingResponse(messages = raw.map { SmsMessageAdapter.fromJson(it) })
    }

    @ToJson
    @Suppress("UNUSED")
    fun toJson(resp: PendingResponse): Map<String, Any?> = mapOf("messages" to resp.messages)
}

@JsonClass(generateAdapter = true)
data class StatusRequest(
    val id: String,
    val status: String,
    val error: String? = null,
    val channel: String? = null
)

/** A status report pinned to the backend it belongs to (outbox entry). */
@JsonClass(generateAdapter = true)
data class QueuedStatus(
    val backendId: String,
    val req: StatusRequest
)

@JsonClass(generateAdapter = true)
data class StatusResponse(
    val ok: Boolean = true
)

@JsonClass(generateAdapter = true)
data class SendRequest(
    val to: String,
    val message: String,
    val templateId: String? = null,
    val priority: String = "normal"
)

@JsonClass(generateAdapter = true)
data class SendResponse(
    val id: String,
    val status: String,
    val to: String,
    @Json(name = "created_at") val created_at: String? = null
)

@JsonClass(generateAdapter = true)
data class LogsResponse(
    val data: List<SmsLogItem> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val limit: Int = 20
)

@JsonClass(generateAdapter = true)
data class SmsLogItem(
    val id: String,
    val to: String,
    val message: String,
    val status: String,
    val error: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null
)

interface SmsApi {
    @GET("pending")
    suspend fun getPending(
        @Query("limit") limit: Int = 10,
        @Query("_t") timestamp: Long = System.currentTimeMillis()
    ): PendingResponse

    @POST("status")
    suspend fun postStatus(@Body body: StatusRequest): StatusResponse

    @POST("send")
    suspend fun sendSms(@Body body: SendRequest): SendResponse

    @GET("logs")
    suspend fun getLogs(
        @Query("status") status: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
        @Query("_t") timestamp: Long = System.currentTimeMillis()
    ): LogsResponse
}

/**
 * Per-config Retrofit cache — id -> SmsApi
 * // ponytail: no DI/Hilt — simple map cache
 */
object ApiClient {
    /**
     * Debug logger that masks PII/secrets inside JSON bodies
     * (recipients, message text, tokens) before they reach logcat.
     */
    private object RedactingLogger : HttpLoggingInterceptor.Logger {
        private val messageField = Regex("\"message\"\\s*:\\s*\"[^\"]*\"")
        private val tokenField = Regex("\"token\"\\s*:\\s*\"[^\"]*\"")
        private val toField = Regex("\"to\"\\s*:\\s*\"(\\d{3})[^\"]*\"")
        override fun log(message: String) {
            var out = message.replace(messageField, "\"message\":\"***\"")
            out = out.replace(tokenField, "\"token\":\"***\"")
            out = out.replace(toField, "\"to\":\"$1***\"")
            android.util.Log.d("SmsApi", out)
        }
    }

    private val cache = mutableMapOf<String, SmsApi>()
    /** Last known baseUrl+token per gateway id — rebuild entry only on change. */
    private val fingerprints = mutableMapOf<String, String>()
    private val lock = Any()

    // Legacy single — kept for compat where old callers used getApi(context)
    @Volatile private var legacyApi: SmsApi? = null
    @Volatile private var legacyBaseUrl: String? = null

    fun forConfig(config: BackendConfig): SmsApi {
        val baseUrl = config.normalizedBaseUrl()
            ?: throw IllegalStateException("Backend ${config.name} URL must start with https://")
        val fingerprint = baseUrl + "|" + config.token
        synchronized(lock) {
            val cached = cache[config.id]
            // Return cached entry only if URL/token are unchanged; otherwise rebuild.
            // This makes hot paths (per-poll status writes) cheap even without
            // explicit invalidation, and user edits take effect immediately.
            if (cached != null && fingerprints[config.id] == fingerprint) return cached

            val moshi = newAppMoshi()
            val authInterceptor = Interceptor { chain ->
                val req = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer ${config.token}")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                    .addHeader("Pragma", "no-cache")
                    .build()
                chain.proceed(req)
            }
            val builder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(authInterceptor)
            if (BuildConfig.DEBUG) {
                val logging = HttpLoggingInterceptor(RedactingLogger).apply {
                    level = HttpLoggingInterceptor.Level.BODY
                    // Never print bearer tokens to logcat (bodies are redacted too).
                    redactHeader("Authorization")
                }
                builder.addInterceptor(logging)
            }
            val client = builder.build()
            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
            val api = retrofit.create(SmsApi::class.java)
            cache[config.id] = api
            fingerprints[config.id] = fingerprint
            return api
        }
    }

    /** Legacy compat: uses first enabled backend if Prefs migrated, else old single */
    fun getApi(context: Context): SmsApi {
        val prefs = Prefs.getInstance(context)
        val enabled = prefs.getEnabledBackends()
        if (enabled.isNotEmpty()) {
            return forConfig(enabled.first())
        }
        // fallback to legacy single
        val baseUrl = prefs.getNormalizedBaseUrl()
            ?: throw IllegalStateException("Backend URL not configured or not https://")
        synchronized(lock) {
            if (legacyApi != null && legacyBaseUrl == baseUrl) return legacyApi!!
            val moshi = newAppMoshi()
            val prefsToken = prefs.gatewayToken ?: ""
            val authInterceptor = Interceptor { chain ->
                val req = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $prefsToken")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                    .addHeader("Pragma", "no-cache")
                    .build()
                chain.proceed(req)
            }
            val builder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(authInterceptor)
            if (BuildConfig.DEBUG) builder.addInterceptor(HttpLoggingInterceptor(RedactingLogger).apply {
                level = HttpLoggingInterceptor.Level.BODY
                redactHeader("Authorization")
            })
            val client = builder.build()
            val retrofit = Retrofit.Builder().baseUrl(baseUrl).client(client).addConverterFactory(MoshiConverterFactory.create(moshi)).build()
            val api = retrofit.create(SmsApi::class.java)
            legacyApi = api; legacyBaseUrl = baseUrl
            return api
        }
    }

    fun invalidate() {
        synchronized(lock) { legacyApi = null; legacyBaseUrl = null }
    }

    fun invalidate(id: String) {
        synchronized(lock) { cache.remove(id); fingerprints.remove(id) }
    }

    fun invalidateAll() {
        synchronized(lock) { cache.clear(); fingerprints.clear(); legacyApi = null; legacyBaseUrl = null }
    }
}
