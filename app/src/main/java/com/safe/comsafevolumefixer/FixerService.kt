package com.safe.comsafevolumefixer

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*

class FixerService : Service() {

    private var timer: Timer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastFixTimestamp = 0L
    private var lastUserVolumeChangeTimestamp = 0L

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            val now = System.currentTimeMillis()
            if (now - lastFixTimestamp > 800) {
                val key = uri?.lastPathSegment ?: "unknown"
                resetVolumeSettings(applicationContext, "System Watcher [$key]")
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_HEADSET_PLUG -> {
                    if (intent.getIntExtra("state", -1) == 1) {
                        resetVolumeSettings(context, "Wired Plug")
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    resetVolumeSettings(context, "Bluetooth Link")
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED) {
                        resetVolumeSettings(context, "Bluetooth Audio Connected")
                    }
                }
                BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothA2dp.STATE_PLAYING) {
                        resetVolumeSettings(context, "Bluetooth Playback Unpaused")
                    }
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    resetVolumeSettings(context, "Ringer Mode Change")
                }
                "android.media.VOLUME_CHANGED_ACTION" -> {
                    handleVolumeChange(context, intent)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            @Suppress("DEPRECATION")
            addAction("android.media.VOLUME_CHANGED_ACTION")
        }
        registerReceiver(receiver, filter)

        val resolver = contentResolver
        val globalKeys = listOf(
            "audio_safe_volume_state",
            "audio_safe_csd_current_value",
            "audio_safe_csd_next_warning",
            "safe_audio_volume_enforced"
        )
        globalKeys.forEach { key ->
            try {
                resolver.registerContentObserver(Settings.Global.getUriFor(key), false, settingsObserver)
            } catch (e: Exception) {
                Log.e("VolumeFixer", "Could not observe Global $key")
            }
        }

        val systemKeys = listOf(
            "volume_music_bt_a2dp",
            "volume_music_headset",
            "volume_music"
        )
        systemKeys.forEach { key ->
            try {
                resolver.registerContentObserver(Settings.System.getUriFor(key), false, settingsObserver)
            } catch (e: Exception) {
                Log.e("VolumeFixer", "Could not observe System $key")
            }
        }

        // CSD 60-second Force-Flush Engine
        startPeriodicReset()
        startForeground(NOTIFICATION_ID, createNotification())
        resetVolumeSettings(this, "Service Start")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Guarantee Service Auto-Restart if terminated by system
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Auto-restart service if user swipes app away from Recent Apps
        val restartServiceIntent = Intent(applicationContext, FixerService::class.java)
        restartServiceIntent.setPackage(packageName)
        startForegroundService(restartServiceIntent)
    }

    private fun handleVolumeChange(context: Context, intent: Intent) {
        val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
        if (streamType == AudioManager.STREAM_MUSIC) {
            val newVolume = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            val prevVolume = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
            val flags = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_FLAGS", 0)

            val isUserButtonPress = (flags and AudioManager.FLAG_SHOW_UI) != 0

            if (isUserButtonPress) {
                lastUserVolumeChangeTimestamp = System.currentTimeMillis()
                return
            }

            // If volume dropped without user pressing buttons (System Attenuation / Safe Volume Drop)
            if (newVolume < prevVolume && (System.currentTimeMillis() - lastUserVolumeChangeTimestamp > 1500)) {
                Logger.log(context, ">>> DROP DETECTED: Music Volume $prevVolume -> $newVolume")
                resetVolumeSettings(context, "Auto Volume Drop Guard")
                resetAudioFocus(context)

                // Restore previous volume level
                try {
                    val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, prevVolume, 0)
                    Logger.log(context, "ACTION: Restored Music Volume back to $prevVolume")
                } catch (e: Exception) {
                    Log.e("VolumeFixer", "Failed to restore volume: ${e.message}")
                }
            }
        }
    }

    private fun resetAudioFocus(context: Context) {
        try {
            val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .build()
            audioManager.requestAudioFocus(focusRequest)
            audioManager.abandonAudioFocusRequest(focusRequest)
            Log.d("VolumeFixer", "Audio focus reset triggered.")
        } catch (e: Exception) {
            Log.e("VolumeFixer", "Audio focus reset error: ${e.message}")
        }
    }

    private fun startPeriodicReset() {
        timer = Timer()
        timer?.schedule(object : TimerTask() {
            override fun run() {
                resetVolumeSettings(applicationContext, "CSD Force-Flush Engine (1m)")
            }
        }, 10000, 1000 * 60 * 1) // Every 60 seconds
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        contentResolver.unregisterContentObserver(settingsObserver)
        timer?.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun resetVolumeSettings(context: Context, source: String) {
        lastFixTimestamp = System.currentTimeMillis()

        try {
            val resolver = context.contentResolver

            // 1. Log the TRIGGER event
            Logger.log(context, ">>> TRIGGER: $source")

            // 2. Apply Fixes & CSD Flush
            Settings.Global.putInt(resolver, "audio_safe_volume_state", 2)
            Settings.Secure.putInt(resolver, "unsafe_volume_music_active_ms", 0)
            Settings.Global.putInt(resolver, "safe_audio_volume_enforced", 0)
            Settings.Global.putFloat(resolver, "audio_safe_csd_current_value", 0.0f)
            Settings.Global.putString(resolver, "audio_safe_csd_dose_records", "[]")
            Settings.Global.putFloat(resolver, "audio_safe_csd_next_warning", 999.0f)
            Settings.Global.putInt(resolver, "audio_safe_csd_as_a_feature_enabled", 0)

            // 3. Log ACTION taken
            Logger.log(context, "ACTION: Forced safety flags to UNRESTRICTED.")
            Logger.log(context, "---")

            Log.d("VolumeFixer", "Fix applied: $source")
        } catch (e: SecurityException) {
            Logger.log(context, "CRITICAL ERROR: ADB Permission missing!")
        }
    }

    private fun createNotification(): Notification {
        val channelId = "volume_fixer_service"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(channelId, "Volume Fixer", NotificationManager.IMPORTANCE_LOW))

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("VolumeFixer Active")
            .setContentText("Volume Guard & CSD Force-Flush Active")
            .setSmallIcon(R.drawable.ic_lock_silent_mode)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 101
    }
}
