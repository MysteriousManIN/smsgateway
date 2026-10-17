package com.smsgateway

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility to export offline Room DB logs into a clean, RFC-4180 compliant CSV file
 * and launch Android system sharesheet for audit / reporting.
 */
object LogExporter {

    private const val TAG = "LogExporter"

    private fun escapeCsv(value: String): String {
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    suspend fun generateCsv(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            val dbEntries = db.logDao().getAll()
            
            val logsDir = File(context.cacheDir, "logs").apply { mkdirs() }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val csvFile = File(logsDir, "sandesh_audit_logs_$timeStamp.csv")

            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.getDefault())

            FileWriter(csvFile).use { writer ->
                // CSV Header
                writer.append("ID,Timestamp_Epoch,ISO_DateTime,Message\n")
                
                if (dbEntries.isNotEmpty()) {
                    for (entry in dbEntries) {
                        val isoDate = isoFormat.format(Date(entry.timestamp))
                        writer.append(entry.id.toString()).append(",")
                            .append(entry.timestamp.toString()).append(",")
                            .append(escapeCsv(isoDate)).append(",")
                            .append(escapeCsv(entry.message)).append("\n")
                    }
                } else {
                    // Fallback to in-memory logs if Room has not loaded or empty
                    val memLogs = LogStore.getAll()
                    for ((idx, logLine) in memLogs.withIndex()) {
                        writer.append((idx + 1).toString()).append(",")
                            .append(System.currentTimeMillis().toString()).append(",")
                            .append(escapeCsv(isoFormat.format(Date()))).append(",")
                            .append(escapeCsv(logLine)).append("\n")
                    }
                }
            }

            Log.d(TAG, "Generated CSV with ${dbEntries.size} entries: ${csvFile.absolutePath}")
            csvFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate CSV: ${e.message}", e)
            null
        }
    }

    suspend fun exportAndShare(context: Context): Boolean {
        val file = generateCsv(context) ?: return false
        return withContext(Dispatchers.Main) {
            try {
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_SUBJECT, "Sandesh SMS Gateway - Audit Logs")
                    putExtra(Intent.EXTRA_TEXT, "Exported audit logs from Sandesh SMS Gateway.")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(intent, "Share Audit Logs (CSV)").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to share CSV: ${e.message}", e)
                false
            }
        }
    }
}

