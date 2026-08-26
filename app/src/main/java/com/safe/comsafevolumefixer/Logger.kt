package com.safe.comsafevolumefixer

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

object Logger {
    private const val PREFS_NAME = "VolumeFixerLogs"
    private const val KEY_LOGS = "logs"
    private const val MAX_LOGS = 50

    fun log(context: Context, message: String) {
        // Use Multi-Process SharedPreferences to ensure the UI sees background service logs
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val logs = prefs.getStringSet(KEY_LOGS, LinkedHashSet())?.toMutableList() ?: mutableListOf()
        
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "$timestamp | $message"
        
        // Add to top of list
        logs.add(0, entry)
        
        // Keep only last 50
        val trimmedLogs = if (logs.size > MAX_LOGS) logs.take(MAX_LOGS) else logs
        
        prefs.edit().putStringSet(KEY_LOGS, LinkedHashSet(trimmedLogs)).apply()
    }

    fun getLogs(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Retrieve and sort to ensure chronological order
        return prefs.getStringSet(KEY_LOGS, LinkedHashSet())?.toList()?.sortedByDescending { it } ?: emptyList()
    }

    fun clearLogs(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
