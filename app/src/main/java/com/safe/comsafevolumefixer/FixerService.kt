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
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            val now = System.currentTimeMillis()
            if (now - lastFixTimestamp > 1000) { // 1 second debounce
                resetVolumeSettings(applicationContext, "Real-time Detection")
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

        // Monitor all relevant safety settings
        val resolver = contentResolver
        val uris = listOf(
            "audio_safe_volume_state",
            "audio_safe_csd_current_value",
            "audio_safe_csd_next_warning",
            "safe_audio_volume_enforced"
        )
        uris.forEach { key ->
            try {
                resolver.registerContentObserver(Settings.Global.getUriFor(key), false, settingsObserver)
            } catch (e: Exception) {
                Log.e("VolumeFixer", "Could not observe $key")
            }
        }

        startPeriodicReset()
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Immediate fix on service start
        resetVolumeSettings(this, "Service Start")
    }

    private fun startPeriodicReset() {
        timer = Timer()
        timer?.schedule(object : TimerTask() {
            override fun run() {
                resetVolumeSettings(applicationContext, "Periodic Guard")
            }
        }, 30000, 1000 * 60 * 5) // Every 5 minutes
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
        try {
            lastFixTimestamp = System.currentTimeMillis()
            val resolver = context.contentResolver
            
            // 1. Force safety state to 'Acknowledged'
            Settings.Global.putInt(resolver, "audio_safe_volume_state", 2)
            
            // 2. Reset playback timer
            Settings.Secure.putInt(resolver, "unsafe_volume_music_active_ms", 0)
            
            // 3. Disable enforcement at root
            Settings.Global.putInt(resolver, "safe_audio_volume_enforced", 0)
            
            // 4. Wipe Sound Dose (CSD)
            Settings.Global.putFloat(resolver, "audio_safe_csd_current_value", 0.0f)
            Settings.Global.putString(resolver, "audio_safe_csd_dose_records", "[]")
            
            // 5. Push next warning into the distant future
            Settings.Global.putFloat(resolver, "audio_safe_csd_next_warning", 999.0f)
            
            // 6. Disable CSD feature if possible
            Settings.Global.putInt(resolver, "audio_safe_csd_as_a_feature_enabled", 0)

            Log.d("VolumeFixer", "Hardened fix applied via $source.")
            Logger.log(context, "Hardened fix: $source")
        } catch (e: SecurityException) {
            Log.e("VolumeFixer", "Fix failed: ${e.message}")
            Logger.log(context, "ERROR: Permission lost")
        }
    }

    private fun createNotification(): Notification {
        val channelId = "volume_fixer_service"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(channelId, "Volume Fixer Service", NotificationManager.IMPORTANCE_LOW))

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("VolumeFixer: Hardened Mode")
            .setContentText("Actively blocking system volume restrictions.")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 101
    }
}
