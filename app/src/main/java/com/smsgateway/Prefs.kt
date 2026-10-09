package com.smsgateway

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = createOrRecoverPrefs(appContext)

    private fun buildMasterKey(context: Context): MasterKey {
        return MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private fun encryptedPrefs(context: Context, masterKey: MasterKey): SharedPreferences {
        return EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Keystore / keyset corruption (e.g. after backup restore or reinstall)
     * throws AEADBadTagException on every launch = instant-crash loop.
     * Wipe the corrupted state once and start fresh instead of dying.
     */
    private fun createOrRecoverPrefs(context: Context): SharedPreferences {
        try {
            return encryptedPrefs(context, buildMasterKey(context))
        } catch (e: Exception) {
            Log.e(TAG, "Encrypted prefs corrupt — wiping and recreating fresh", e)
            wipeCorruptedState(context)
            return encryptedPrefs(context, buildMasterKey(context))
        }
    }

    private fun wipeCorruptedState(context: Context) {
        try { context.deleteSharedPreferences(PREF_NAME) } catch (_: Exception) {}
        try { context.deleteSharedPreferences(KEYSET_PREF_NAME) } catch (_: Exception) {}
        try {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        } catch (_: Exception) {}
    }

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

    // --- service desired state (user toggle in Service Controls) ---
    // Persists across reboot so BootReceiver / Worker don't auto-start when user left it OFF.
    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
        set(v) { prefs.edit().putBoolean(KEY_SERVICE_ENABLED, v).apply() }

    fun isServiceEnabled(): Boolean = serviceEnabled

    // --- selected SIM for sending (-1 = system default) ---
    var simSubscriptionId: Int
        get() = prefs.getInt(KEY_SIM_SUB_ID, -1)
        set(v) { prefs.edit().putInt(KEY_SIM_SUB_ID, v).apply() }

    var simDisplayName: String?
        get() = prefs.getString(KEY_SIM_NAME, null)
        set(v) { prefs.edit().putString(KEY_SIM_NAME, v).apply() }

    // --- permission bookkeeping ---
    var permSmsAsked: Boolean
        get() = prefs.getBoolean(KEY_PERM_SMS_ASKED, false)
        set(v) { prefs.edit().putBoolean(KEY_PERM_SMS_ASKED, v).apply() }

    // --- multi-backend ---
    @Synchronized
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

    @Synchronized
    fun saveBackends(list: List<BackendConfig>) {
        persistBackends(list, invalidateApi = true)
    }

    /**
     * Persist without touching ApiClient cache — for hot paths like per-poll
     * status updates. Rebuilding Retrofit/OkHttp every 15s is pure waste
     * since URL/token are unchanged. Callers editing URL/token must use
     * saveBackends() (or ApiClient detects the change on next forConfig()).
     */
    @Synchronized
    fun saveBackendsLight(list: List<BackendConfig>) {
        persistBackends(list, invalidateApi = false)
    }

    private fun persistBackends(list: List<BackendConfig>, invalidateApi: Boolean) {
        // validate https
        for (c in list) {
            require(c.baseUrl.startsWith("https://")) { "Backend ${c.name} must start with https://" }
        }
        val json = adapter.toJson(list)
        prefs.edit().putString(KEY_BACKEND_CONFIGS, json).apply()
        if (invalidateApi) {
            // invalidate ApiClient caches
            ApiClient.invalidateAll()
        }
    }

    fun getEnabledBackends(): List<BackendConfig> = getBackends().filter { it.enabled }

    @Synchronized
    fun addBackend(config: BackendConfig) {
        val list = getBackends().toMutableList()
        list.add(config)
        saveBackends(list)
    }

    @Synchronized
    fun updateBackend(config: BackendConfig) {
        val list = getBackends().map { if (it.id == config.id) config else it }
        saveBackends(list)
    }

    @Synchronized
    fun deleteBackend(id: String) {
        val list = getBackends().filterNot { it.id == id }
        saveBackends(list)
    }

    @Synchronized
    fun setBackendStatus(id: String, status: String) {
        val list = getBackends().map {
            if (it.id == id) it.copy(lastStatus = status, lastPollAt = System.currentTimeMillis()) else it
        }
        // light path: no ApiClient rebuild on every poll (see saveBackendsLight)
        saveBackendsLight(list)
    }

    // ---------- Acked message IDs (duplicate-send guard) ----------

    private val stringListType = Types.newParameterizedType(List::class.java, String::class.java)
    private val stringListAdapter = moshi.adapter<List<String>>(stringListType)

    /** IDs whose final status was reported (or queued) — skip re-sends. */
    @Synchronized
    fun isAcked(id: String): Boolean {
        return try {
            val json = prefs.getString(KEY_ACKED_IDS, null) ?: return false
            (stringListAdapter.fromJson(json) ?: emptyList()).contains(id)
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun markAcked(id: String) {
        try {
            val current = try {
                stringListAdapter.fromJson(prefs.getString(KEY_ACKED_IDS, null) ?: "[]") ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            if (current.contains(id)) return
            val pruned = (current + id).takeLast(MAX_ACKED_IDS)
            prefs.edit().putString(KEY_ACKED_IDS, stringListAdapter.toJson(pruned)).apply()
        } catch (_: Exception) {}
    }

    // ---------- Pending status outbox (postStatus retry across restarts) ----------

    private val statusListType = Types.newParameterizedType(List::class.java, StatusRequest::class.java)
    private val statusListAdapter = moshi.adapter<List<StatusRequest>>(statusListType)

    /** Queue a status report that failed to reach the backend (deduped by id). */
    @Synchronized
    fun queuePendingStatus(req: StatusRequest) {
        try {
            val current = readPendingStatuses()
            val updated = (current.filterNot { it.id == req.id } + req).takeLast(MAX_PENDING_STATUS)
            prefs.edit().putString(KEY_PENDING_STATUS, statusListAdapter.toJson(updated)).apply()
        } catch (_: Exception) {}
    }

    /** Drain the outbox (returns items and clears storage). */
    @Synchronized
    fun takePendingStatuses(): List<StatusRequest> {
        return try {
            val current = readPendingStatuses()
            prefs.edit().remove(KEY_PENDING_STATUS).apply()
            current
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readPendingStatuses(): List<StatusRequest> {
        return try {
            statusListAdapter.fromJson(prefs.getString(KEY_PENDING_STATUS, null) ?: "[]") ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
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

        private const val TAG = "SmsGateway"
        private const val KEYSET_PREF_NAME = "__androidx_security_crypto_encrypted_prefs_key_keyset__"

        private const val PREF_NAME = "sms_gateway_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_TOKEN = "gateway_token"
        private const val KEY_SENT = "sent_count"
        private const val KEY_FAILED = "failed_count"
        private const val KEY_LAST_POLL = "last_poll"
        private const val KEY_BACKEND_CONFIGS = "backend_configs"
        private const val KEY_MIGRATED = "backend_migrated_v2"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_SIM_SUB_ID = "sim_sub_id"
        private const val KEY_SIM_NAME = "sim_name"
        private const val KEY_PERM_SMS_ASKED = "perm_sms_asked"
        private const val KEY_ACKED_IDS = "acked_msg_ids"
        private const val KEY_PENDING_STATUS = "pending_status_outbox"
        private const val MAX_ACKED_IDS = 300
        private const val MAX_PENDING_STATUS = 200

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
