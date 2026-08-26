package com.safe.comsafevolumefixer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            try {
                val resolver = context.contentResolver
                // Overrides the "has seen warning" state
                Settings.Global.putInt(resolver, "audio_safe_volume_state", 2)

                // Resets the 20-hour playback timer back to zero
                Settings.Secure.putInt(resolver, "unsafe_volume_music_active_ms", 0)

                // Enforcement Flag (Disables the feature entirely on some devices)
                Settings.Global.putInt(resolver, "safe_audio_volume_enforced", 0)

                // Newer CSD (Calculated Sound Dose) flags for Android 14+
                Settings.Global.putFloat(resolver, "audio_safe_csd_current_value", 0.0f)
                Settings.Global.putString(resolver, "audio_safe_csd_dose_records", "[]")

                // Start the background service to listen for headphones
                val serviceIntent = Intent(context, FixerService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                Log.d("VolumeFixer", "Boot script ran successfully ($action).")
                Logger.log(context, "Fix applied via Boot ($action)")
            } catch (e: SecurityException) {
                Log.e("VolumeFixer", "Failed: Missing WRITE_SECURE_SETTINGS via ADB.")
                Logger.log(context, "ERROR: Permission missing at Boot")
            }
        }
    }
}
