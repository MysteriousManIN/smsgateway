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

/**
 * Offline Room Database-backed Log Store.
 * Every log entry is persisted to the local SQLite Room database,
 * ensuring complete audit history persists across app restarts and reboots.
 */
object LogStore {
    private val logs = mutableListOf<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var database: AppDatabase? = null
    private val pendingWrites = mutableListOf<LogEntry>()
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    fun init(context: Context) {
        val db = AppDatabase.getInstance(context)
        database = db
        ioScope.launch {
            try {
                // Flush any early pending writes that arrived before init
                val pendingToInsert = synchronized(this@LogStore) {
                    val copy = ArrayList(pendingWrites)
                    pendingWrites.clear()
                    copy
                }
                if (pendingToInsert.isNotEmpty()) {
                    db.logDao().insertAll(pendingToInsert)
                }

                // Load all existing logs from Room database
                val dbEntries = db.logDao().getAll()
                synchronized(this@LogStore) {
                    val dbFormatted = dbEntries.map { formatEntryForDisplay(it.timestamp, it.message) }
                    // Preserve any new logs in memory and append historical ones not already present
                    val existing = logs.toSet()
                    for (entry in dbFormatted) {
                        if (!existing.contains(entry)) {
                            logs.add(entry)
                        }
                    }
                }
                notifyListeners()
            } catch (e: Exception) {
                Log.e("LogStore", "Failed to load logs from Room DB: ${e.message}")
            }
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
        val now = System.currentTimeMillis()
        val ts = timeFormat.format(Date(now))
        val formatted = "[$ts] $entry"

        val heartbeatIdx = logs.indexOfFirst { it.contains("Poll ") }
        if (heartbeatIdx == 0) {
            logs.add(1, formatted)
        } else {
            logs.add(0, formatted)
        }
        notifyListeners()
        Log.d("SmsGateway", entry)

        val logEntity = LogEntry(
            timestamp = now,
            message = entry,
            formatted = formatted
        )

        val db = database
        if (db != null) {
            ioScope.launch {
                try {
                    db.logDao().insert(logEntity)
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
        val ts = timeFormat.format(Date())
        val formatted = "[$ts] $entry"
        val idx = logs.indexOfFirst { it.contains("Poll ") }
        if (idx >= 0) {
            logs[idx] = formatted
        } else {
            logs.add(0, formatted)
        }
        notifyListeners()
        Log.d("SmsGateway", entry)
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

