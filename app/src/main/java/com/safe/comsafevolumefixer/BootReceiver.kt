package com.safe.comsafevolumefixer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, FixerService::class.java)
            context.startForegroundService(serviceIntent)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val resolver = context.contentResolver
                    
                    // 1. Log the TRIGGER at Boot
                    Logger.log(context, ">>> TRIGGER: Boot ($action)")

                    // 2. Apply fixes safely per key
                    try { Settings.Global.putInt(resolver, "audio_safe_volume_state", 2) } catch (e: SecurityException) { throw e } catch (_: Exception) {}
                    try { Settings.Secure.putInt(resolver, "audio_safe_volume_state", 2) } catch (e: SecurityException) { throw e } catch (_: Exception) {}
                    try { Settings.Secure.putInt(resolver, "unsafe_volume_music_active_ms", 0) } catch (e: SecurityException) { throw e } catch (_: Exception) {}
                    try { Settings.Global.putInt(resolver, "safe_audio_volume_enforced", 0) } catch (e: SecurityException) { throw e } catch (_: Exception) {}
                    try { Settings.Global.putInt(resolver, "safe_media_volume_enabled", 0) } catch (e: SecurityException) { throw e } catch (_: Exception) {}
                    try { Settings.System.putInt(resolver, "safe_media_volume_enabled", 0) } catch (e: SecurityException) { throw e } catch (_: Exception) {}
                    try { Settings.System.putInt(resolver, "volume_limiter_enabled", 0) } catch (e: SecurityException) { throw e } catch (_: Exception) {}

                    try { Settings.Global.putFloat(resolver, "audio_safe_csd_current_value", 0.0f) } catch (e: SecurityException) { throw e } catch (_: Exception) {}
                    try { Settings.Global.putString(resolver, "audio_safe_csd_dose_records", "[]") } catch (e: SecurityException) { throw e } catch (_: Exception) {}
                    try { Settings.Global.putFloat(resolver, "audio_safe_csd_next_warning", 999.0f) } catch (e: SecurityException) { throw e } catch (_: Exception) {}
                    try { Settings.Global.putInt(resolver, "audio_safe_csd_as_a_feature_enabled", 0) } catch (e: SecurityException) { throw e } catch (_: Exception) {}

                    // 3. Log action
                    Logger.log(context, "ACTION: Forced safety flags to UNRESTRICTED.")

                    Log.d("VolumeFixer", "Hardened Boot Fix applied ($action).")
                } catch (e: SecurityException) {
                    Log.e("VolumeFixer", "ERROR: Boot permission failure")
                    Logger.log(context, "ERROR: Permission missing at Boot")
                }
            }
        }
    }
}
