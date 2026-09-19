package com.smsgateway

import android.content.Context
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class SmsMessage(
    val id: String,
    val to: String,
    val message: String,
    val templateId: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class PendingResponse(
    val messages: List<SmsMessage> = emptyList()
)

@JsonClass(generateAdapter = true)
data class StatusRequest(
    val id: String,
    val status: String,
    val error: String? = null
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
    suspend fun getPending(@Query("limit") limit: Int = 10): PendingResponse

    @POST("status")
    suspend fun postStatus(@Body body: StatusRequest): StatusResponse

    @POST("send")
    suspend fun sendSms(@Body body: SendRequest): SendResponse

    @GET("logs")
    suspend fun getLogs(
        @Query("status") status: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): LogsResponse
}

/**
 * Per-config Retrofit cache — id -> SmsApi
 * // ponytail: no DI/Hilt — simple map cache
 */
object ApiClient {
    private val cache = mutableMapOf<String, SmsApi>()
    private val lock = Any()

    // Legacy single — kept for compat where old callers used getApi(context)
    @Volatile private var legacyApi: SmsApi? = null
    @Volatile private var legacyBaseUrl: String? = null

    fun forConfig(config: BackendConfig): SmsApi {
        val baseUrl = config.normalizedBaseUrl()
            ?: throw IllegalStateException("Backend ${config.name} URL must start with https://")
        synchronized(lock) {
            val cached = cache[config.id]
            // we need to check baseUrl change — if url or token changed, invalidate that entry
            // simplest: if baseUrl differs from cached's baseUrl? We don't store baseUrl per id separately, so check token via needRecreate flag
            // To keep simple, return cached if exists and baseUrl matches last used baseUrl for that id (store separately)
            // Instead: store key as id + baseUrl + token hash -> easy
            val key = config.id
            val entry = cache[key]
            // For ponytail: if token changed, caller should have called invalidate(config.id) via saveBackends -> invalidateAll()
            // So just return cached if exists
            if (entry != null) return entry

            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val authInterceptor = Interceptor { chain ->
                val req = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer ${config.token}")
                    .addHeader("Content-Type", "application/json")
                    .build()
                chain.proceed(req)
            }
            val builder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(authInterceptor)
            if (BuildConfig.DEBUG) {
                val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
                builder.addInterceptor(logging)
            }
            val client = builder.build()
            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
            val api = retrofit.create(SmsApi::class.java)
            cache[key] = api
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
            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val prefsToken = prefs.gatewayToken ?: ""
            val authInterceptor = Interceptor { chain ->
                val req = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $prefsToken")
                    .addHeader("Content-Type", "application/json")
                    .build()
                chain.proceed(req)
            }
            val builder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(authInterceptor)
            if (BuildConfig.DEBUG) builder.addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
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
        synchronized(lock) { cache.remove(id) }
    }

    fun invalidateAll() {
        synchronized(lock) { cache.clear(); legacyApi = null; legacyBaseUrl = null }
    }
}
