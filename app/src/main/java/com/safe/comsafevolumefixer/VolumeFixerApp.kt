package com.safe.comsafevolumefixer

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Process
import android.os.UserManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs in every process start (UI, boot receiver, service). Records the diagnostics that are most useful
 * when the app cannot be debugged live: crashes, process starts, and *why previous processes died*.
 */
class VolumeFixerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Logger.installCrashHandler(this)

        val unlocked = getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        Logger.log(this, "PROCESS: started (pid=${Process.myPid()}, v$version, userUnlocked=$unlocked)")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Thread({ logPreviousExits() }, "VolumeFixer-ExitInfo").apply { isDaemon = true }.start()
        }
    }

    /** Logs process deaths (low memory kill, crash, ANR, user force-stop, ...) not yet recorded. */
    private fun logPreviousExits() {
        try {
            val am = getSystemService(ActivityManager::class.java) ?: return
            val prefs = createDeviceProtectedStorageContext().getSharedPreferences("diagnostics", MODE_PRIVATE)
            val lastSeen = prefs.getLong("last_exit_ts", 0L)
            val exits = am.getHistoricalProcessExitReasons(packageName, 0, 15)
                .filter { it.timestamp > lastSeen }
                .sortedBy { it.timestamp }
            if (exits.isEmpty()) return

            val timeFormat = SimpleDateFormat("MMM dd HH:mm:ss", Locale.getDefault())
            exits.forEach { info ->
                Logger.log(
                    this,
                    "EXIT: previous process '${info.processName}' ended at ${timeFormat.format(Date(info.timestamp))}: " +
                        "${reasonName(info.reason)} (status=${info.status}, importance=${info.importance}, " +
                        "desc=${info.description ?: "-"})"
                )
            }
            prefs.edit().putLong("last_exit_ts", exits.last().timestamp).apply()
        } catch (e: Exception) {
            Logger.log(this, "EXIT: could not read exit history: ${e.message}")
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (killed by system)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (force stop)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED (app updated)"
        else -> "UNKNOWN($reason)"
    }
}
