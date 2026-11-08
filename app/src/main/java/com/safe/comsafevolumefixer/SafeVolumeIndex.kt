package com.safe.comsafevolumefixer

import android.annotation.SuppressLint
import android.content.res.Resources
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * Computes the real per-device safe media volume index the same way AOSP `SoundDoseHelper` does:
 * the highest music index whose gain (from the device's volume curve) is still <= the safe dBFS limit
 * (`config_safe_media_volume_usb_mB`, -37 dBFS by default).
 *
 * This is the index Android lowers the volume to when the sound dose (CSD) reaches 100% or the legacy
 * safe-volume timer fires (e.g. 6/25 for Bluetooth A2DP on a Pixel 9, not the old 60%-of-max guess).
 */
object SafeVolumeIndex {

    private const val DEFAULT_SAFE_DBFS = -37.0f
    private val cache = ConcurrentHashMap<Int, Int>()

    /** Output types AOSP applies the safe-volume cap / sound dose to (mSafeMediaVolumeDevices). */
    @SuppressLint("InlinedApi") // int constants are inlined at compile time
    private val SAFE_VOLUME_DEVICE_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_BROADCAST
    )

    /** Fallback priority (API < 33) when the media route cannot be queried directly. */
    @SuppressLint("InlinedApi")
    private val FALLBACK_PRIORITY = listOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE
    )

    /** Safe dBFS limit from the framework config, or -37 dBFS. */
    val safeDbfs: Float by lazy { readSafeDbfs() }

    @SuppressLint("DiscouragedApi") // framework-internal resource, only reachable by name
    private fun readSafeDbfs(): Float =
        try {
            val res = Resources.getSystem()
            val id = res.getIdentifier("config_safe_media_volume_usb_mB", "integer", "android")
            if (id != 0) res.getInteger(id) / 100.0f else DEFAULT_SAFE_DBFS
        } catch (_: Exception) {
            DEFAULT_SAFE_DBFS
        }

    fun isSafeVolumeDevice(deviceType: Int): Boolean = deviceType in SAFE_VOLUME_DEVICE_TYPES

    /** The output device type music is currently routed to. */
    fun currentMusicDeviceType(audioManager: AudioManager): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                audioManager.getAudioDevicesForAttributes(attrs).firstOrNull()?.let { return it.type }
            } catch (_: Exception) {
                // fall through to the heuristic
            }
        }
        val outputs = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
        return FALLBACK_PRIORITY.firstOrNull { it in outputs } ?: AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    /** Safe index for [deviceType] (cached), or null if the volume curve cannot be queried. */
    fun safeIndexFor(audioManager: AudioManager, deviceType: Int): Int? {
        cache[deviceType]?.let { return it }
        val index = if (deviceType == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            deviceType == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        ) {
            // AOSP keeps the legacy config index for wired headsets (dumpsys: mSafeMediaVolumeIndex[4/8]).
            legacyWiredIndex(audioManager) ?: compute(audioManager, deviceType)
        } else {
            compute(audioManager, deviceType)
        }
        return index?.also { cache[deviceType] = it }
    }

    @SuppressLint("DiscouragedApi")
    private fun legacyWiredIndex(audioManager: AudioManager): Int? = try {
        val res = Resources.getSystem()
        val id = res.getIdentifier("config_safe_media_volume_index", "integer", "android")
        val value = if (id != 0) res.getInteger(id) else 0
        value.takeIf { it in 1 until audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    } catch (_: Exception) {
        null
    }

    fun clearCache() = cache.clear()

    private fun compute(audioManager: AudioManager, deviceType: Int): Int? {
        val stream = AudioManager.STREAM_MUSIC
        var lo = audioManager.getStreamMinVolume(stream)
        var hi = audioManager.getStreamMaxVolume(stream)
        var best: Int? = null
        // Gain grows monotonically with the index: binary-search the highest index <= safeDbfs.
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val db = try {
                audioManager.getStreamVolumeDb(stream, mid, deviceType)
            } catch (_: Exception) {
                return null
            }
            if (db.isNaN()) return null
            if (db <= safeDbfs) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }

    @SuppressLint("InlinedApi")
    fun deviceName(deviceType: Int): String = when (deviceType) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE_BROADCAST"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        else -> "TYPE_$deviceType"
    }
}
