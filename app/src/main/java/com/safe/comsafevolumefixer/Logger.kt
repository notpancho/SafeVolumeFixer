package com.safe.comsafevolumefixer

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

data class LogEntry(val timestampStr: String, val message: String, val rawMs: Long)

object Logger {
    private const val PREFS_NAME = "VolumeFixerLogs_v2"
    private const val KEY_LOGS = "logs"
    private const val MAX_LOGS = 200

    fun log(context: Context, message: String) {
        if (message == "---") return // Ignore legacy dividers
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingSet = prefs.getStringSet(KEY_LOGS, null)
        val logs = existingSet?.toMutableList() ?: mutableListOf()
        
        val timeMs = System.currentTimeMillis()
        val timestamp = SimpleDateFormat("MMM dd, yyyy • hh:mm:ss a", Locale.getDefault()).format(Date(timeMs))
        val entry = "$timeMs|$timestamp|$message"
        
        logs.add(entry)
        
        val trimmedLogs = logs.sortedByDescending { it.substringBefore("|").toLongOrNull() ?: 0L }.take(MAX_LOGS)
        prefs.edit().putStringSet(KEY_LOGS, LinkedHashSet(trimmedLogs)).apply()
    }

    fun getLogs(context: Context): List<LogEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingSet = prefs.getStringSet(KEY_LOGS, null) ?: return emptyList()
        return existingSet.mapNotNull {
            val parts = it.split("|", limit = 3)
            if (parts.size == 3) {
                val ms = parts[0].toLongOrNull() ?: 0L
                LogEntry(parts[1], parts[2], ms)
            } else null
        }.sortedByDescending { it.rawMs }
    }

    fun clearLogs(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
