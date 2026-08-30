package com.safe.comsafevolumefixer

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

object Logger {
    private const val PREFS_NAME = "VolumeFixerLogs"
    private const val KEY_LOGS = "logs"
    private const val MAX_LOGS = 100 // Increased limit for detailed logs

    fun log(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val logs = prefs.getStringSet(KEY_LOGS, LinkedHashSet())?.toMutableList() ?: mutableListOf()
        
        // Full date and time format
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "$timestamp | $message"
        
        logs.add(0, entry)
        
        val trimmedLogs = if (logs.size > MAX_LOGS) logs.take(MAX_LOGS) else logs
        prefs.edit().putStringSet(KEY_LOGS, LinkedHashSet(trimmedLogs)).apply()
    }

    fun getLogs(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_LOGS, LinkedHashSet())?.toList()?.sortedDescending() ?: emptyList()
    }

    fun clearLogs(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
