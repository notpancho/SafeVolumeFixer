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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FixerService : Service() {

    // Lifecycle-bound scope: cancelled in onDestroy so no work outlives the service.
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Unhandled error in FixerService", e) }
    )
    private var periodicJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var lastFixTimestamp = 0L
    @Volatile private var settingsWriteInProgress = false
    @Volatile private var lastSettingsWriteEnd = 0L
    private var lastIdleFlushTimestamp = 0L // only touched by the periodic job

    private var receiverRegistered = false
    private var observerRegistered = false

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            val now = System.currentTimeMillis()
            // Ignore the echo of our own writes (several watched keys are also keys we write),
            // otherwise a slow settings IPC could make us re-trigger ourselves in a loop.
            if (settingsWriteInProgress || now - lastSettingsWriteEnd < SELF_WRITE_GRACE_MS) return
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

        // Must come first: if anything below throws, the OS would otherwise kill us for not
        // calling startForeground() in time.
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}")
            stopSelf()
            return
        }

        registerReceivers()
        registerObservers()
        Logger.log(this, "SERVICE: created (receivers + observers registered)")

        // CSD Adaptive Active / Idle Force-Flush Engine
        startPeriodicReset()
        resetVolumeSettings(this, "Service Start")
    }

    private fun registerReceivers() {
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
        receiverRegistered = true
    }

    private fun registerObservers() {
        val resolver = contentResolver
        // ONLY protected safety keys - never volume index keys (see REQUIREMENTS.md section 2).
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
            } catch (_: Exception) {
                Log.e(TAG, "Could not observe Global $key")
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
            } catch (_: Exception) {
                Log.e(TAG, "Could not observe System $key")
            }
        }
        observerRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Guarantee Service Auto-Restart if terminated by system
        if (intent == null) Logger.log(this, "SERVICE: restarted by system (START_STICKY, null intent)")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Logger.log(this, "SERVICE: app task removed from recents, requesting restart")
        // Auto-restart service if user swipes app away from Recent Apps
        try {
            val restartServiceIntent = Intent(applicationContext, FixerService::class.java)
            restartServiceIntent.setPackage(packageName)
            startForegroundService(restartServiceIntent)
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException - START_STICKY still covers us.
            Log.e(TAG, "Could not restart service after task removal: ${e.message}")
        }
    }

    private fun handleVolumeChange(context: Context, intent: Intent) {
        val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
        if (streamType != AudioManager.STREAM_MUSIC) return

        val newVolume = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
        val prevVolume = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
        val flags = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_FLAGS", 0)
        val dropAmount = prevVolume - newVolume

        val audioManager = context.getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // Safe volume threshold in Android is typically ~60% of max volume (e.g., 8/15 or 15/25)
        val safeVolumeThreshold = (maxVolume * 0.60f).toInt()

        // User Intent Detection:
        // FLAG_SHOW_UI = 1 (on-screen volume slider / panel interaction)
        // FLAG_FROM_KEY = 4096 (physical volume buttons)
        val isShowUi = (flags and 1) != 0
        val isFromKey = (flags and 4096) != 0

        // Decide (see REQUIREMENTS.md section 1). A null reason means "genuine system-forced cap".
        val ignoreReason: String? = when {
            // If the volume went UP or stayed the same, do nothing.
            dropAmount <= 0 -> "volume up/unchanged"
            // If the drop was triggered by user interaction (touchscreen slider or hardware keys), allow it!
            isShowUi || isFromKey -> "user action (UI/key flags)"
            // System-forced drops NEVER lower volume below the safe threshold (nor to 0): that is a user action.
            newVolume < safeVolumeThreshold -> "below safe threshold"
            // Tightened (2.4.2): a real system cap lands EXACTLY on the safe threshold and removes several
            // steps at once. Single-step changes without flags (e.g. Bluetooth earbud buttons / absolute
            // volume sync) are user actions and must never be reverted.
            newVolume != safeVolumeThreshold || dropAmount < MIN_SYSTEM_CAP_DROP_STEPS -> "not an exact system cap"
            prevVolume <= safeVolumeThreshold -> "previous volume not above threshold"
            System.currentTimeMillis() - lastFixTimestamp <= 800 -> "debounced (fix just ran)"
            else -> null
        }

        // Every music volume change is logged with the decision, so unexpected behaviour can be analysed later.
        Logger.log(
            context,
            "VOL: $prevVolume->$newVolume/$maxVolume (safe=$safeVolumeThreshold) flags=0x${flags.toString(16)} " +
                "[${describeVolumeFlags(flags)}] -> ${ignoreReason?.let { "ignored: $it" } ?: "SYSTEM CAP"}"
        )
        if (ignoreReason != null) return

        Logger.log(context, ">>> DROP DETECTED: System Forced Volume Drop $prevVolume -> $newVolume")
        resetVolumeSettings(context, "Auto Volume Drop Guard")
        // NOTE: Do NOT request audio focus here. A MAY_DUCK focus request makes Android duck the
        // playing app (e.g. YouTube) to 20% volume, which is itself heard as the volume dropping.

        // Restore previous volume level instantly
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, prevVolume, 0)
            Logger.log(context, "ACTION: Restored Music Volume back to $prevVolume")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore volume: ${e.message}")
            Logger.log(context, "ERROR: Failed to restore volume: ${e.message}")
        }
    }

    private fun describeVolumeFlags(flags: Int): String {
        val names = listOf(
            1 to "SHOW_UI", 2 to "ALLOW_RINGER_MODES", 4 to "PLAY_SOUND", 8 to "REMOVE_SOUND_AND_VIBRATE",
            16 to "VIBRATE", 32 to "FIXED_VOLUME", 64 to "BLUETOOTH_ABS_VOLUME", 128 to "SHOW_SILENT_HINT",
            256 to "HIDE_RINGER_MODE", 512 to "SHOW_VIBRATE_HINT", 4096 to "FROM_KEY"
        )
        val set = names.filter { (flags and it.first) != 0 }.map { it.second }
        return if (set.isEmpty()) "none" else set.joinToString("+")
    }

    private fun sendGhostAcknowledge(context: Context) {
        val actions = listOf(
            "com.android.server.audio.action.VOL_SAFE_WARNING_ACK",
            "android.media.action.SAFE_VOLUME_WARNING_ACKNOWLEDGED",
            "com.android.systemui.volume.SAFE_VOLUME_ACK"
        )
        actions.forEach { action ->
            try {
                // Targeted at SystemUI only so the broadcast is not delivered to arbitrary apps.
                context.sendBroadcast(Intent(action).setPackage(SYSTEM_UI_PACKAGE))
            } catch (_: Exception) {
            }
        }
    }

    private fun startPeriodicReset() {
        periodicJob?.cancel()
        periodicJob = serviceScope.launch {
            delay(5_000)
            var lastHeartbeat = 0L
            while (isActive) {
                val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
                val isPlaying = audioManager?.isMusicActive == true
                val now = System.currentTimeMillis()

                if (isPlaying) {
                    resetVolumeSettings(applicationContext, "CSD Force-Flush Engine (Active 10s)")
                } else if (now - lastIdleFlushTimestamp >= IDLE_FLUSH_INTERVAL_MS) { // Every 5 minutes when idle
                    lastIdleFlushTimestamp = now
                    resetVolumeSettings(applicationContext, "CSD Force-Flush Engine (Idle 5m)")
                }

                // Compact state line every 5 minutes (instead of a line per 10s tick) for post-mortem analysis.
                if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                    lastHeartbeat = now
                    logHeartbeat(applicationContext, isPlaying)
                }
                delay(10_000) // Ticks every 10 seconds
            }
        }
    }

    private fun logHeartbeat(context: Context, isPlaying: Boolean) {
        val audioManager = context.getSystemService(AUDIO_SERVICE) as? AudioManager
        val vol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        val max = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1
        val snapshot = SafetySettings.snapshot(context)
        Logger.log(
            context,
            "HEARTBEAT[${if (snapshot.ok) "OK" else "DRIFT"}]: vol=$vol/$max playing=$isPlaying ${snapshot.text}"
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.log(this, "SERVICE: destroyed")
        if (receiverRegistered) {
            try { unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
            receiverRegistered = false
        }
        if (observerRegistered) {
            contentResolver.unregisterContentObserver(settingsObserver)
            observerRegistered = false
        }
        periodicJob?.cancel()
        serviceScope.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun resetVolumeSettings(context: Context, source: String) {
        val now = System.currentTimeMillis()
        if (now - lastFixTimestamp < 500 && source != "Auto Volume Drop Guard") {
            return // Debounce rapid intent storms
        }
        lastFixTimestamp = now

        // Settings IPC always runs on Dispatchers.IO inside the lifecycle-bound serviceScope.
        serviceScope.launch {
            // Periodic ticks are not logged individually (see the 5-minute HEARTBEAT instead).
            val quiet = source.startsWith("CSD Force-Flush Engine")
            if (!quiet) Logger.log(context, ">>> TRIGGER: $source")

            settingsWriteInProgress = true
            val applied = try {
                SafetySettings.apply(context)
            } finally {
                settingsWriteInProgress = false
                lastSettingsWriteEnd = System.currentTimeMillis()
            }
            if (!applied) return@launch // permission missing (already logged as CRITICAL ERROR)

            // Ghost Acknowledge to dismiss SystemUI safe volume warning dialogs
            sendGhostAcknowledge(context)

            val audioManager = context.getSystemService(AUDIO_SERVICE) as? AudioManager
            val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
            val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1
            if (!quiet) Logger.log(context, "ACTION: Forced safety flags to UNRESTRICTED (Vol: $currentVol/$maxVol)")

            Log.d(TAG, "Fix applied: $source")
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
        private const val TAG = "VolumeFixer"
        private const val NOTIFICATION_ID = 101
        private const val MIN_SYSTEM_CAP_DROP_STEPS = 2
        private const val SELF_WRITE_GRACE_MS = 1000L
        private const val IDLE_FLUSH_INTERVAL_MS = 300_000L
        private const val HEARTBEAT_INTERVAL_MS = 300_000L
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
