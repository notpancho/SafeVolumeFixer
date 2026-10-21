package com.safe.comsafevolumefixer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

class FixerService : Service() {

    private var timer: Timer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastFixTimestamp = 0L
    private var lastIdleFlushTimestamp = 0L

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
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    resetVolumeSettings(context, "USB DAC/Headphone Attached")
                }
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    resetVolumeSettings(context, "Audio Noisy Event")
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val devName = try { device?.name ?: "Bluetooth Device" } catch (_: SecurityException) { "Bluetooth Device" }
                    resetVolumeSettings(context, "Connected to $devName")
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED) {
                        resetVolumeSettings(context, "Bluetooth A2DP Connected")
                    }
                }
                BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothA2dp.STATE_PLAYING) {
                        resetVolumeSettings(context, "Bluetooth Playback Active")
                    }
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED) {
                        resetVolumeSettings(context, "Bluetooth Headset Connected")
                    }
                }
                BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
                        resetVolumeSettings(context, "Bluetooth Headset Audio Active")
                    }
                }
                "android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED" -> {
                    if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED) {
                        resetVolumeSettings(context, "Bluetooth LE Audio Connected")
                    }
                }
                "android.app.action.ENTER_CAR_MODE" -> {
                    resetVolumeSettings(context, "Android Auto / Car Mode Active")
                }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    resetVolumeSettings(context, "Doze Mode State Changed")
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    resetVolumeSettings(context, "Battery Saver Mode Changed")
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    resetVolumeSettings(context, "Car Charger Plugged In")
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    resetVolumeSettings(context, "Car Charger Unplugged")
                }
                Intent.ACTION_SCREEN_ON -> {
                    resetVolumeSettings(context, "Screen Turned On")
                }
                Intent.ACTION_USER_PRESENT -> {
                    resetVolumeSettings(context, "Screen Unlocked")
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
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            @Suppress("DEPRECATION")
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            @Suppress("DEPRECATION")
            addAction("android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED")
            addAction("android.app.action.ENTER_CAR_MODE")
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            @Suppress("DEPRECATION")
            addAction("android.media.VOLUME_CHANGED_ACTION")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        val resolver = contentResolver
        val globalKeys = listOf(
            "audio_safe_volume_state",
            "audio_safe_csd_current_value",
            "audio_safe_csd_next_warning",
            "safe_audio_volume_enforced",
            "safe_media_volume_enabled"
        )
        globalKeys.forEach { key ->
            try {
                val uri = Settings.Global.getUriFor(key)
                if (uri != null) {
                    resolver.registerContentObserver(uri, false, settingsObserver)
                }
            } catch (e: Exception) {
                Log.e("VolumeFixer", "Could not observe Global $key")
            }
        }

        val systemKeys = listOf(
            "volume_limiter_enabled",
            "safe_media_volume_enabled"
        )
        systemKeys.forEach { key ->
            try {
                val uri = Settings.System.getUriFor(key)
                if (uri != null) {
                    resolver.registerContentObserver(uri, false, settingsObserver)
                }
            } catch (e: Exception) {
                Log.e("VolumeFixer", "Could not observe System $key")
            }
        }

        // CSD Adaptive Active / Idle Force-Flush Engine
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

            val dropAmount = prevVolume - newVolume

            // If the volume went UP or stayed the same, do nothing.
            if (dropAmount <= 0) {
                return
            }

            val audioManager = context.getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

            // Safe volume threshold in Android is typically ~60% of max volume (e.g., 8/15 or 15/25)
            val safeVolumeThreshold = (maxVolume * 0.60f).toInt()

            // User Intent Detection:
            // FLAG_SHOW_UI = 1 (on-screen volume slider / panel interaction)
            // FLAG_FROM_KEY = 4096 (physical volume buttons)
            val isShowUi = (flags and 1) != 0
            val isFromKey = (flags and 4096) != 0

            // If the drop was triggered by user interaction (touchscreen slider or hardware keys), allow it!
            if (isShowUi || isFromKey) {
                return
            }

            // Safe Volume Level Check:
            // System-forced safe volume drops ONLY happen when capping volume DOWN TO the safe volume threshold.
            // A system safe volume drop NEVER lowers volume below the safe volume threshold (and NEVER drops to 0).
            // If newVolume is below safeVolumeThreshold (or 0), it is 100% a user action!
            if (newVolume < safeVolumeThreshold) {
                return
            }

            // System-Forced Drop Verification:
            // A system safe volume drop occurs when previous volume was above safe threshold,
            // and the system forcibly drops it down to the safe volume threshold without user UI/key flags.
            if (prevVolume > safeVolumeThreshold && (System.currentTimeMillis() - lastFixTimestamp > 800)) {
                Logger.log(context, ">>> DROP DETECTED: System Forced Volume Drop $prevVolume -> $newVolume")
                resetVolumeSettings(context, "Auto Volume Drop Guard")
                resetAudioFocus(context)

                // Restore previous volume level instantly
                try {
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

    private fun sendGhostAcknowledge(context: Context) {
        try {
            val actions = listOf(
                "com.android.server.audio.action.VOL_SAFE_WARNING_ACK",
                "android.media.action.SAFE_VOLUME_WARNING_ACKNOWLEDGED",
                "com.android.systemui.volume.SAFE_VOLUME_ACK"
            )
            actions.forEach { action ->
                try {
                    context.sendBroadcast(Intent(action))
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e("VolumeFixer", "Ghost acknowledge error: ${e.message}")
        }
    }

    private fun startPeriodicReset() {
        timer = Timer()
        timer?.schedule(object : TimerTask() {
            override fun run() {
                val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
                val isPlaying = audioManager?.isMusicActive == true
                val now = System.currentTimeMillis()

                if (isPlaying) {
                    resetVolumeSettings(applicationContext, "CSD Force-Flush Engine (Active 10s)")
                } else if (now - lastIdleFlushTimestamp >= 300000) { // Every 5 minutes when idle
                    lastIdleFlushTimestamp = now
                    resetVolumeSettings(applicationContext, "CSD Force-Flush Engine (Idle 5m)")
                }
            }
        }, 5000, 10000) // Ticks every 10 seconds
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
        val now = System.currentTimeMillis()
        if (now - lastFixTimestamp < 500 && source != "Auto Volume Drop Guard") {
            return // Debounce rapid intent storms
        }
        lastFixTimestamp = now

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val resolver = context.contentResolver

                // 1. Log the TRIGGER event
                Logger.log(context, ">>> TRIGGER: $source")

                // 2. Apply Fixes & CSD Flush safely per key (1 = SAFE_MEDIA_VOLUME_DISABLED in AOSP)
                try { Settings.Global.putInt(resolver, "audio_safe_volume_state", 1) } catch (e: SecurityException) { throw e } catch (_: Exception) {}
                try { Settings.Secure.putInt(resolver, "audio_safe_volume_state", 1) } catch (e: SecurityException) { throw e } catch (_: Exception) {}
                try { Settings.Secure.putInt(resolver, "unsafe_volume_music_active_ms", 0) } catch (e: SecurityException) { throw e } catch (_: Exception) {}
                try { Settings.Global.putInt(resolver, "safe_audio_volume_enforced", 0) } catch (e: SecurityException) { throw e } catch (_: Exception) {}
                try { Settings.Global.putInt(resolver, "safe_media_volume_enabled", 0) } catch (e: SecurityException) { throw e } catch (_: Exception) {}
                try { Settings.System.putInt(resolver, "safe_media_volume_enabled", 0) } catch (e: SecurityException) { throw e } catch (_: Exception) {}
                try { Settings.System.putInt(resolver, "volume_limiter_enabled", 0) } catch (e: SecurityException) { throw e } catch (_: Exception) {}
                
                try { Settings.Global.putFloat(resolver, "audio_safe_csd_current_value", 0.0f) } catch (e: SecurityException) { throw e } catch (_: Exception) {}
                try { Settings.Global.putString(resolver, "audio_safe_csd_dose_records", "[]") } catch (e: SecurityException) { throw e } catch (_: Exception) {}
                try { Settings.Global.putFloat(resolver, "audio_safe_csd_next_warning", 999.0f) } catch (e: SecurityException) { throw e } catch (_: Exception) {}
                try { Settings.Global.putInt(resolver, "audio_safe_csd_as_a_feature_enabled", 0) } catch (e: SecurityException) { throw e } catch (_: Exception) {}

                // Ghost Acknowledge to dismiss SystemUI safe volume warning dialogs
                sendGhostAcknowledge(context)

                // 3. Log ACTION taken with current volume
                val audioManager = context.getSystemService(AUDIO_SERVICE) as? AudioManager
                val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
                val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1
                Logger.log(context, "ACTION: Forced safety flags to UNRESTRICTED (Vol: $currentVol/$maxVol)")

                Log.d("VolumeFixer", "Fix applied: $source")
            } catch (e: SecurityException) {
                Logger.log(context, "CRITICAL ERROR: ADB Permission missing!")
            }
        }
    }

    private fun createNotification(): Notification {
        val channelId = "volume_fixer_service"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(channelId, "Volume Fixer", NotificationManager.IMPORTANCE_LOW))

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("VolumeFixer Active")
            .setContentText("Volume Guard & CSD Force-Flush Active")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 101
    }
}
