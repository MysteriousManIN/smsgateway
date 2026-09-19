package com.smsgateway

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Prefs with multi-backend support. Migrates old single backend_url/gateway_token to list.
 * // ponytail: JSON list via EncryptedSharedPreferences is enough — Room if >20 configs
 */
class Prefs private constructor(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREF_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, BackendConfig::class.java)
    private val adapter = moshi.adapter<List<BackendConfig>>(listType)

    // theme mode
    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, THEME_SYSTEM)
        set(value) { prefs.edit().putInt(KEY_THEME_MODE, value).apply() }

    // --- legacy single fields (for migration) ---
    var backendUrl: String?
        get() = prefs.getString(KEY_BACKEND_URL, null)
        set(value) {
            if (value != null && value.isNotBlank()) {
                require(value.startsWith("https://")) { "Backend URL must start with https://" }
                prefs.edit().putString(KEY_BACKEND_URL, value.trim().trimEnd('/')).apply()
            } else {
                prefs.edit().remove(KEY_BACKEND_URL).apply()
            }
        }

    var gatewayToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) {
            if (value != null && value.isNotBlank()) {
                prefs.edit().putString(KEY_TOKEN, value.trim()).apply()
            } else {
                prefs.edit().remove(KEY_TOKEN).apply()
            }
        }

    // --- multi-backend ---
    fun getBackends(): List<BackendConfig> {
        // migrate if needed
        migrateIfNeeded()
        val json = prefs.getString(KEY_BACKEND_CONFIGS, null) ?: return emptyList()
        return try {
            adapter.fromJson(json) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveBackends(list: List<BackendConfig>) {
        // validate https
        for (c in list) {
            require(c.baseUrl.startsWith("https://")) { "Backend ${c.name} must start with https://" }
        }
        val json = adapter.toJson(list)
        prefs.edit().putString(KEY_BACKEND_CONFIGS, json).apply()
        // invalidate ApiClient caches
        ApiClient.invalidateAll()
    }

    fun getEnabledBackends(): List<BackendConfig> = getBackends().filter { it.enabled }

    fun addBackend(config: BackendConfig) {
        val list = getBackends().toMutableList()
        list.add(config)
        saveBackends(list)
    }

    fun updateBackend(config: BackendConfig) {
        val list = getBackends().map { if (it.id == config.id) config else it }
        saveBackends(list)
    }

    fun deleteBackend(id: String) {
        val list = getBackends().filterNot { it.id == id }
        saveBackends(list)
    }

    fun setBackendStatus(id: String, status: String) {
        val list = getBackends().map {
            if (it.id == id) it.copy(lastStatus = status, lastPollAt = System.currentTimeMillis()) else it
        }
        saveBackends(list)
    }

    private fun migrateIfNeeded() {
        if (prefs.contains(KEY_MIGRATED)) return
        val oldUrl = prefs.getString(KEY_BACKEND_URL, null)
        val oldToken = prefs.getString(KEY_TOKEN, null)
        val existingJson = prefs.getString(KEY_BACKEND_CONFIGS, null)
        // if already has list, just mark migrated
        if (existingJson != null) {
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            return
        }
        if (!oldUrl.isNullOrBlank() && !oldToken.isNullOrBlank()) {
            try {
                val cfg = BackendConfig(
                    name = "Default",
                    baseUrl = oldUrl.trim().trimEnd('/'),
                    token = oldToken.trim(),
                    enabled = true
                )
                val json = adapter.toJson(listOf(cfg))
                prefs.edit().putString(KEY_BACKEND_CONFIGS, json).putBoolean(KEY_MIGRATED, true).apply()
            } catch (_: Exception) {
                prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            }
        } else {
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            // ensure empty list stored
            if (existingJson == null) {
                prefs.edit().putString(KEY_BACKEND_CONFIGS, "[]").apply()
            }
        }
    }

    // counters
    var sentCount: Int
        get() = prefs.getInt(KEY_SENT, 0)
        set(v) { prefs.edit().putInt(KEY_SENT, v).apply() }

    var failedCount: Int
        get() = prefs.getInt(KEY_FAILED, 0)
        set(v) { prefs.edit().putInt(KEY_FAILED, v).apply() }

    var lastPoll: String?
        get() = prefs.getString(KEY_LAST_POLL, null)
        set(v) { prefs.edit().putString(KEY_LAST_POLL, v).apply() }

    fun isConfigured(): Boolean = getEnabledBackends().isNotEmpty()

    // legacy compat for single checks
    fun isLegacyConfigured(): Boolean {
        val url = backendUrl
        val token = gatewayToken
        return !url.isNullOrBlank() && !token.isNullOrBlank() && url.startsWith("https://")
    }

    fun getNormalizedBaseUrl(): String? {
        // for legacy single — used only for migration compat, new code uses BackendConfig.normalizedBaseUrl()
        val raw = backendUrl ?: return null
        if (!raw.startsWith("https://")) return null
        var url = raw.trim().trimEnd('/')
        if (!url.contains("/api/v1/sms")) url += "/api/v1/sms"
        if (!url.endsWith("/")) url += "/"
        return url
    }

    fun incrementSent() { sentCount = sentCount + 1 }
    fun incrementFailed() { failedCount = failedCount + 1 }

    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        private const val PREF_NAME = "sms_gateway_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_TOKEN = "gateway_token"
        private const val KEY_SENT = "sent_count"
        private const val KEY_FAILED = "failed_count"
        private const val KEY_LAST_POLL = "last_poll"
        private const val KEY_BACKEND_CONFIGS = "backend_configs"
        private const val KEY_MIGRATED = "backend_migrated_v2"

        @Volatile
        private var INSTANCE: Prefs? = null

        fun getInstance(context: Context): Prefs {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Prefs(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun getThemeMode(context: Context): Int {
            return getInstance(context).themeMode
        }

        fun setThemeMode(context: Context, mode: Int) {
            getInstance(context).themeMode = mode
        }

        // for tests
        fun clearInstance() { INSTANCE = null }
    }
}
