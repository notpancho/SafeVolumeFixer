package com.safe.comsafevolumefixer

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single source of truth for the system safety-flag overrides (see REQUIREMENTS.md sections 3 and 5).
 *
 * All `Settings` writes happen on [Dispatchers.IO] (never on the main thread).
 */
object SafetySettings {

    private const val TAG = "VolumeFixer"

    /** Process-wide scope for short fire-and-forget fixes (activity / boot receiver). */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Unhandled error applying safety settings", e) }
    )

    /** Writes one setting; a missing WRITE_SECURE_SETTINGS grant is rethrown, every other failure is ignored. */
    private inline fun attempt(block: () -> Unit) {
        try {
            block()
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
            // Key not supported on this ROM - ignore.
        }
    }

    /**
     * Applies all overrides. `audio_safe_volume_state` MUST be 1 (SAFE_MEDIA_VOLUME_DISABLED in AOSP).
     *
     * @param resetDose also overwrite the *persisted* sound dose (CSD) keys. AudioService keeps the live dose
     *        in memory and re-persists it on every dose update, so writing these periodically only causes a
     *        write war; it is done one-shot (service start, device shutdown) so the value loaded at the next
     *        boot is 0.
     * @return true on success, false if the WRITE_SECURE_SETTINGS permission is missing
     *         (logged as "CRITICAL ERROR: ADB Permission missing!").
     */
    suspend fun apply(context: Context, resetDose: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        try {
            attempt { Settings.Global.putInt(resolver, "audio_safe_volume_state", 1) }
            attempt { Settings.Secure.putInt(resolver, "audio_safe_volume_state", 1) }
            attempt { Settings.Secure.putInt(resolver, "unsafe_volume_music_active_ms", 0) }
            attempt { Settings.Global.putInt(resolver, "safe_audio_volume_enforced", 0) }
            attempt { Settings.Global.putInt(resolver, "safe_media_volume_enabled", 0) }
            attempt { Settings.System.putInt(resolver, "safe_media_volume_enabled", 0) }
            attempt { Settings.System.putInt(resolver, "volume_limiter_enabled", 0) }

            attempt { Settings.Global.putInt(resolver, "audio_safe_csd_as_a_feature_enabled", 0) }

            if (resetDose) {
                attempt { Settings.Global.putFloat(resolver, "audio_safe_csd_current_value", 0.0f) }
                attempt { Settings.Global.putString(resolver, "audio_safe_csd_dose_records", "[]") }
                attempt { Settings.Global.putFloat(resolver, "audio_safe_csd_next_warning", 999.0f) }
            }
            true
        } catch (_: SecurityException) {
            Logger.log(context, "CRITICAL ERROR: ADB Permission missing!")
            false
        }
    }

    /** Current values of every setting we manage, plus whether the key ones match what we want. */
    data class Snapshot(val text: String, val ok: Boolean)

    /**
     * Reads (never writes) the managed settings - used for diagnostics. Call off the main thread.
     * Some keys are not readable by apps (hidden CSD keys -> "unreadable") or are never created on this ROM
     * (System keys -> "unset"); those are shown for information but do not count towards [Snapshot.ok].
     */
    fun snapshot(context: Context): Snapshot {
        val resolver = context.contentResolver
        fun read(block: () -> String?): String = try { block() ?: "unset" } catch (_: Exception) { "unreadable" }
        fun global(key: String) = read { Settings.Global.getString(resolver, key) }
        fun secure(key: String) = read { Settings.Secure.getString(resolver, key) }
        fun system(key: String) = read { Settings.System.getString(resolver, key) }

        val stateG = global("audio_safe_volume_state")
        val stateS = secure("audio_safe_volume_state")
        val enforced = global("safe_audio_volume_enforced")
        val enabledG = global("safe_media_volume_enabled")
        val enabledS = system("safe_media_volume_enabled")
        val limiter = system("volume_limiter_enabled")
        val csdFeature = global("audio_safe_csd_as_a_feature_enabled")
        val csd = global("audio_safe_csd_current_value")
        val next = global("audio_safe_csd_next_warning")

        val ok = stateG == "1" && stateS == "1" && enforced == "0" && enabledG == "0" && csdFeature == "0"
        val text = "volState(g/s)=$stateG/$stateS enforced=$enforced mediaEnabled(g/s)=$enabledG/$enabledS " +
            "limiter=$limiter csdFeature=$csdFeature csd=$csd nextWarn=$next"
        return Snapshot(text, ok)
    }

    /**
     * Fire-and-forget: logs the trigger, applies the overrides, logs the result.
     * [onDone] is always invoked afterwards (use it to finish a `goAsync()` pending result).
     */
    fun applyAsync(context: Context, source: String, onDone: (() -> Unit)? = null) {
        val appContext = context.applicationContext ?: context
        scope.launch {
            try {
                Logger.log(appContext, ">>> TRIGGER: $source")
                if (apply(appContext)) {
                    Logger.log(appContext, "ACTION: Forced safety flags to UNRESTRICTED.")
                }
            } finally {
                onDone?.invoke()
            }
        }
    }
}
