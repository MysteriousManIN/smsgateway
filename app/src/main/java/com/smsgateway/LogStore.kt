package com.smsgateway

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Offline Room Database-backed Log Store with auto-retention and PII sanitization.
 * Enforces:
 * - 7-day auto-purge for data retention
 * - MAX_LOG_ROWS cap (1000 rows) in Room DB and memory
 * - PII masking for phone numbers in logs
 */
object LogStore {
    const val MAX_LOG_ROWS = 1000
    const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000L // 7 days

    private val logs = mutableListOf<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var database: AppDatabase? = null
    private val pendingWrites = mutableListOf<LogEntry>()
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeCounter = AtomicInteger(0)

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    // E.164 and local phone numbers in logs: keep country/leading 2-4 digits + last 4 digits
    private val phoneRegex = Regex("""(\+?\d{2,4})\d{4,8}(\d{4})""")

    /** Masks middle digits of phone numbers to prevent storing/displaying raw PII */
    fun maskPii(text: String): String {
        return phoneRegex.replace(text) { mr ->
            val prefix = mr.groupValues[1]
            val suffix = mr.groupValues[2]
            "$prefix****$suffix"
        }
    }

    fun init(context: Context) {
        val db = AppDatabase.getInstance(context)
        database = db
        ioScope.launch {
            try {
                // Purge expired entries (> 7 days) and trim to MAX_LOG_ROWS on startup
                purgeOldLogs(db)

                // Flush any early pending writes that arrived before init
                val pendingToInsert = synchronized(this@LogStore) {
                    val copy = ArrayList(pendingWrites)
                    pendingWrites.clear()
                    copy
                }
                if (pendingToInsert.isNotEmpty()) {
                    db.logDao().insertAll(pendingToInsert)
                }

                // Load existing logs from Room database (newest first, up to MAX_LOG_ROWS)
                val dbEntries = db.logDao().getAll()
                synchronized(this@LogStore) {
                    val dbFormatted = dbEntries.map { formatEntryForDisplay(it.timestamp, it.message) }
                    val existing = logs.toSet()
                    for (entry in dbFormatted) {
                        if (!existing.contains(entry)) {
                            logs.add(entry)
                        }
                    }
                    while (logs.size > MAX_LOG_ROWS) {
                        logs.removeAt(logs.size - 1)
                    }
                }
                notifyListeners()
            } catch (e: Exception) {
                Log.e("LogStore", "Failed to load logs from Room DB: ${e.message}")
            }
        }
    }

    private suspend fun purgeOldLogs(db: AppDatabase) {
        try {
            val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
            db.logDao().deleteOlderThan(cutoff)
            db.logDao().trimToMaxRows(MAX_LOG_ROWS)
        } catch (e: Exception) {
            Log.e("LogStore", "Error purging old logs: ${e.message}")
        }
    }

    private fun isToday(timestamp: Long): Boolean {
        val entryCal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val nowCal = Calendar.getInstance()
        return entryCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                entryCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
    }

    private fun formatEntryForDisplay(timestamp: Long, message: String): String {
        return if (isToday(timestamp)) {
            val ts = timeFormat.format(Date(timestamp))
            "[$ts] $message"
        } else {
            val ts = dateTimeFormat.format(Date(timestamp))
            "[$ts] $message"
        }
    }

    @Synchronized
    fun add(entry: String) {
        val sanitized = maskPii(entry)
        val now = System.currentTimeMillis()
        val ts = timeFormat.format(Date(now))
        val formatted = "[$ts] $sanitized"

        val heartbeatIdx = logs.indexOfFirst { it.contains("Poll ") }
        if (heartbeatIdx == 0) {
            logs.add(1, formatted)
        } else {
            logs.add(0, formatted)
        }
        while (logs.size > MAX_LOG_ROWS) {
            logs.removeAt(logs.size - 1)
        }
        notifyListeners()
        Log.d("SmsGateway", sanitized)

        val logEntity = LogEntry(
            timestamp = now,
            message = sanitized,
            formatted = formatted
        )

        val db = database
        if (db != null) {
            ioScope.launch {
                try {
                    db.logDao().insert(logEntity)
                    if (writeCounter.incrementAndGet() % 50 == 0) {
                        purgeOldLogs(db)
                    }
                } catch (e: Exception) {
                    Log.e("LogStore", "Error saving log to Room: ${e.message}")
                }
            }
        } else {
            pendingWrites.add(logEntity)
        }
    }

    @Synchronized
    fun setHeartbeat(entry: String) {
        val sanitized = maskPii(entry)
        val ts = timeFormat.format(Date())
        val formatted = "[$ts] $sanitized"
        val idx = logs.indexOfFirst { it.contains("Poll ") }
        if (idx >= 0) {
            logs[idx] = formatted
        } else {
            logs.add(0, formatted)
        }
        notifyListeners()
        Log.d("SmsGateway", sanitized)
    }

    @Synchronized
    fun getAll(): List<String> = logs.toList()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun notifyListeners() {
        listeners.forEach { it.invoke() }
    }

    @Synchronized
    fun clear() {
        logs.clear()
        notifyListeners()
        val db = database
        if (db != null) {
            ioScope.launch {
                try {
                    db.logDao().clearAll()
                } catch (e: Exception) {
                    Log.e("LogStore", "Error clearing Room logs: ${e.message}")
                }
            }
        } else {
            pendingWrites.clear()
        }
    }

    @Synchronized
    fun clearMatching(predicate: (String) -> Boolean) {
        logs.removeAll { predicate(it) }
        notifyListeners()
    }

    @Synchronized
    fun removeLogsContaining(query: String) {
        if (query.isBlank()) return
        logs.removeAll { it.contains(query, ignoreCase = true) }
        notifyListeners()
        val db = database
        if (db != null) {
            ioScope.launch {
                try {
                    db.logDao().deleteContaining(query)
                } catch (e: Exception) {
                    Log.e("LogStore", "Error deleting logs from Room: ${e.message}")
                }
            }
        }
    }
}
