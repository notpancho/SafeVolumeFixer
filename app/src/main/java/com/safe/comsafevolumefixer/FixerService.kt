package com.safe.comsafevolumefixer

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
                    if (intent.getIntExtra("state", -1) == 1) resetVolumeSettings(context, "Wired Plug")
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> resetVolumeSettings(context, "Bluetooth Link")
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED) {
                        resetVolumeSettings(context, "Bluetooth Audio")
                    }
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
        }
        registerReceiver(receiver, filter)

        val resolver = contentResolver
        val keys = listOf(
            "audio_safe_volume_state", 
            "audio_safe_csd_current_value", 
            "audio_safe_csd_next_warning", 
            "safe_audio_volume_enforced"
        )
        keys.forEach { key ->
            try {
                resolver.registerContentObserver(Settings.Global.getUriFor(key), false, settingsObserver)
            } catch (e: Exception) {
                Log.e("VolumeFixer", "Could not observe $key")
            }
        }

        startPeriodicReset()
        startForeground(NOTIFICATION_ID, createNotification())
        resetVolumeSettings(this, "Service Start")
    }

    private fun startPeriodicReset() {
        timer = Timer()
        timer?.schedule(object : TimerTask() {
            override fun run() {
                resetVolumeSettings(applicationContext, "Background Guard")
            }
        }, 60000, 1000 * 60 * 15)
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
            
            // Log the TRIGGER event with a clean timestamp
            Logger.log(context, ">>> TRIGGER: $source")
            
            // Apply Fixes
            Settings.Global.putInt(resolver, "audio_safe_volume_state", 2)
            Settings.Secure.putInt(resolver, "unsafe_volume_music_active_ms", 0)
            Settings.Global.putInt(resolver, "safe_audio_volume_enforced", 0)
            Settings.Global.putFloat(resolver, "audio_safe_csd_current_value", 0.0f)
            Settings.Global.putString(resolver, "audio_safe_csd_dose_records", "[]")
            Settings.Global.putFloat(resolver, "audio_safe_csd_next_warning", 999.0f)
            Settings.Global.putInt(resolver, "audio_safe_csd_as_a_feature_enabled", 0)

            // Log ACTION taken
            Logger.log(context, "ACTION: Forced safety flags to UNRESTRICTED.")
            Logger.log(context, "---") // Divider for readability
            
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
            .setContentText("Watching system for restrictions...")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 101
    }
}
