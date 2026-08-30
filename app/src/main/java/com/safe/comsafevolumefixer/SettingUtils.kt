package com.safe.comsafevolumefixer

import android.content.Context
import android.provider.Settings

object SettingUtils {
    
    fun getTargetFlagsState(context: Context): String {
        val resolver = context.contentResolver
        val sb = StringBuilder("Current State: ")
        
        // 1. audio_safe_volume_state (Global)
        sb.append("State=").append(readGlobalInt(resolver, "audio_safe_volume_state"))
        
        // 2. unsafe_volume_music_active_ms (Secure)
        sb.append(", Timer=").append(readSecureInt(resolver, "unsafe_volume_music_active_ms"))
        
        // 3. safe_audio_volume_enforced (Global)
        sb.append(", Enforced=").append(readGlobalInt(resolver, "safe_audio_volume_enforced"))
        
        // 4. audio_safe_csd_current_value (Global)
        sb.append(", CSD=").append(readGlobalFloat(resolver, "audio_safe_csd_current_value"))
        
        // 5. audio_safe_csd_next_warning (Global)
        sb.append(", NextW=").append(readGlobalFloat(resolver, "audio_safe_csd_next_warning"))
        
        return sb.toString()
    }

    private fun readGlobalInt(resolver: android.content.ContentResolver, key: String): String {
        return try {
            Settings.Global.getInt(resolver, key).toString()
        } catch (e: Exception) {
            "P" // Protected/Unreadable
        }
    }

    private fun readSecureInt(resolver: android.content.ContentResolver, key: String): String {
        return try {
            Settings.Secure.getInt(resolver, key).toString()
        } catch (e: Exception) {
            "P"
        }
    }

    private fun readGlobalFloat(resolver: android.content.ContentResolver, key: String): String {
        return try {
            Settings.Global.getFloat(resolver, key).toString()
        } catch (e: Exception) {
            "P"
        }
    }
}
