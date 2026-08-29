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
                
                Settings.Global.putInt(resolver, "audio_safe_volume_state", 2)
                Settings.Secure.putInt(resolver, "unsafe_volume_music_active_ms", 0)
                Settings.Global.putInt(resolver, "safe_audio_volume_enforced", 0)
                Settings.Global.putFloat(resolver, "audio_safe_csd_current_value", 0.0f)
                Settings.Global.putString(resolver, "audio_safe_csd_dose_records", "[]")
                Settings.Global.putFloat(resolver, "audio_safe_csd_next_warning", 999.0f)
                Settings.Global.putInt(resolver, "audio_safe_csd_as_a_feature_enabled", 0)

                val serviceIntent = Intent(context, FixerService::class.java)
                context.startForegroundService(serviceIntent)

                Log.d("VolumeFixer", "Hardened Boot Fix applied ($action).")
                Logger.log(context, "Hardened fix: Boot ($action)")
            } catch (e: SecurityException) {
                Logger.log(context, "ERROR: Boot permission failure")
            }
        }
    }
}
