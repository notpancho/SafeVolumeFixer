package com.safe.comsafevolumefixer

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class LogEntry(val timestampStr: String, val message: String, val rawMs: Long)

/**
 * Persistent diagnostic log.
 *
 * - Stored as plain-text files in **device-protected** storage, so it works during Direct Boot
 *   (before the first unlock) and survives process death / crashes.
 * - Two rotating files of ~[MAX_FILE_BYTES] each (~15,000 events in total, i.e. days of history).
 * - Writes happen on a single background thread, so callers (including the main thread) never block.
 * - One line per event: `epochMs|human readable time|message` (newlines in messages are flattened).
 */
object Logger {
    private const val TAG = "VolumeFixer"
    private const val LEGACY_PREFS = "VolumeFixerLogs_v2"
    private const val LEGACY_KEY = "logs"
    private const val DIR_NAME = "logs"
    private const val CURRENT_FILE = "events.log"
    private const val OLD_FILE = "events.1.log"
    private const val MAX_FILE_BYTES = 768L * 1024

    private val fileLock = Any()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "VolumeFixer-Logger").apply { isDaemon = true }
    }

    private var legacyChecked = false

    /** Queues an event; returns immediately. */
    fun log(context: Context, message: String) {
        if (message == "---") return // Ignore legacy dividers
        val appContext = context.applicationContext ?: context
        val timeMs = System.currentTimeMillis()
        try {
            executor.execute { append(appContext, timeMs, message) }
        } catch (e: Exception) {
            Log.e(TAG, "Logger.log failed: ${e.message}")
        }
    }

    /** Newest first. Call from a background thread. */
    fun getLogs(context: Context): List<LogEntry> {
        val appContext = context.applicationContext ?: context
        return try {
            // Run on the logger thread so every previously queued event is already on disk.
            executor.submit(Callable { readAll(appContext) }).get()
        } catch (e: Exception) {
            Log.e(TAG, "Logger.getLogs failed: ${e.message}")
            emptyList()
        }
    }

    fun clearLogs(context: Context) {
        val appContext = context.applicationContext ?: context
        try {
            executor.submit {
                synchronized(fileLock) {
                    val dir = logDir(appContext)
                    File(dir, CURRENT_FILE).delete()
                    File(dir, OLD_FILE).delete()
                }
            }.get()
        } catch (e: Exception) {
            Log.e(TAG, "Logger.clearLogs failed: ${e.message}")
        }
    }

    /**
     * Writes the whole log (oldest first, with a device/app header) to the cache dir and returns the file,
     * ready to be shared through the FileProvider. Call from a background thread.
     */
    fun exportToFile(context: Context): File? {
        return try {
            val entries = getLogs(context).sortedBy { it.rawMs }
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "volumefixer-logs.txt")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            file.bufferedWriter().use { w ->
                w.write("Safe Volume Fixer ${info.versionName} (${info.longVersionCode}) | ")
                w.write("${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) | ")
                w.write("${entries.size} events\n\n")
                entries.forEach { w.write("${it.timestampStr}  ${it.message}\n") }
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Logger.exportToFile failed: ${e.message}")
            null
        }
    }

    /** Records uncaught exceptions (with a short stack trace) before the process dies. */
    fun installCrashHandler(context: Context) {
        val appContext = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Flush anything still queued, then write the crash synchronously.
                try { executor.submit {}.get(500, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
                val frames = throwable.stackTrace.take(8).joinToString(" ⏎ ") { "at $it" }
                val cause = throwable.cause?.let { " ⏎ caused by ${it.javaClass.name}: ${it.message}" } ?: ""
                append(
                    appContext,
                    System.currentTimeMillis(),
                    "CRASH: ${throwable.javaClass.name}: ${throwable.message} (thread ${thread.name}) ⏎ $frames$cause"
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    // ---- internals ----

    private fun logDir(context: Context): File {
        val dp = context.createDeviceProtectedStorageContext()
        return File(dp.filesDir, DIR_NAME).apply { mkdirs() }
    }

    private fun sanitize(message: String) = message.replace("\r", " ").replace("\n", " ⏎ ")

    private fun append(context: Context, timeMs: Long, message: String) {
        try {
            synchronized(fileLock) {
                val dir = logDir(context)
                importLegacyLocked(context, dir)
                val current = File(dir, CURRENT_FILE)
                if (current.length() > MAX_FILE_BYTES) {
                    val old = File(dir, OLD_FILE)
                    old.delete()
                    current.renameTo(old)
                }
                // Created per write so a locale change is picked up (SimpleDateFormat is not thread-safe anyway).
                val timeText = SimpleDateFormat("MMM dd, yyyy • hh:mm:ss a", Locale.getDefault()).format(Date(timeMs))
                File(dir, CURRENT_FILE).appendText("$timeMs|$timeText|${sanitize(message)}\n")
            }
        } catch (e: Exception) {
            // Logging must never crash the service/receiver.
            Log.e(TAG, "Logger.append failed: ${e.message}")
        }
    }

    /** One-time import of the old SharedPreferences-based log (<= 2.4.3, max 200 events). */
    private fun importLegacyLocked(context: Context, dir: File) {
        if (legacyChecked) return
        legacyChecked = true
        try {
            val prefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            val legacy = prefs.getStringSet(LEGACY_KEY, null)
            if (!legacy.isNullOrEmpty()) {
                val lines = legacy.sortedBy { it.substringBefore("|").toLongOrNull() ?: 0L }
                File(dir, CURRENT_FILE).appendText(lines.joinToString("\n", postfix = "\n") { sanitize(it) })
                prefs.edit().remove(LEGACY_KEY).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Legacy log import failed: ${e.message}")
        }
    }

    private fun readAll(context: Context): List<LogEntry> {
        synchronized(fileLock) {
            val dir = logDir(context)
            importLegacyLocked(context, dir)
            val entries = ArrayList<LogEntry>()
            for (name in listOf(OLD_FILE, CURRENT_FILE)) {
                val file = File(dir, name)
                if (!file.exists()) continue
                file.forEachLine { line ->
                    val parts = line.split("|", limit = 3)
                    if (parts.size == 3) {
                        entries.add(LogEntry(parts[1], parts[2], parts[0].toLongOrNull() ?: 0L))
                    }
                }
            }
            entries.sortByDescending { it.rawMs }
            return entries
        }
    }
}
