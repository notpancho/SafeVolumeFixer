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
    
    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            // No cooldown. We react to everything, but the fix logic itself 
            // will now check if a fix is actually necessary.
            checkAndReactToSystemChanges()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    if (state == 1) resetVolumeSettings(context, "Wired Headphones")
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> resetVolumeSettings(context, "Bluetooth Device")
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    if (state == BluetoothProfile.STATE_CONNECTED) resetVolumeSettings(context, "Bluetooth A2DP")
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

        try {
            contentResolver.registerContentObserver(Settings.Global.getUriFor("audio_safe_volume_state"), false, settingsObserver)
            contentResolver.registerContentObserver(Settings.Global.getUriFor("audio_safe_csd_current_value"), false, settingsObserver)
        } catch (e: Exception) {
            Log.e("VolumeFixer", "Failed to register content observers: ${e.message}")
        }

        startAggressivePhase()
        startPeriodicReset()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private var lastChangeTimestamp = 0L
    private var rapidChangeCount = 0

    private fun checkAndReactToSystemChanges() {
        val now = System.currentTimeMillis()
        
        // If changes are happening faster than 250ms
        if (now - lastChangeTimestamp < 250) {
            rapidChangeCount++
        } else {
            rapidChangeCount = 0
        }
        lastChangeTimestamp = now

        // "Rage Mode": If we detect rapid spam (more than 3 quick changes), 
        // apply the fix INSTANTLY and bypass the debounce.
        if (rapidChangeCount > 3) {
            handler.removeCallbacks(fixRunnable)
            fixRunnable.run()
            rapidChangeCount = 0 // Reset after rage fix
        } else {
            // Standard debounce for normal system background tasks
            handler.removeCallbacks(fixRunnable)
            handler.postDelayed(fixRunnable, 100)
        }
    }

    private val fixRunnable = Runnable {
        resetVolumeSettings(applicationContext, "System Change (Verified)")
    }

    private fun startAggressivePhase() {
        var count = 0
        val runnable = object : Runnable {
            override fun run() {
                if (count < 12) {
                    resetVolumeSettings(applicationContext, "Aggressive Phase ($count)")
                    count++
                    handler.postDelayed(this, 10000)
                }
            }
        }
        handler.post(runnable)
    }

    private fun startPeriodicReset() {
        timer = Timer()
        timer?.schedule(object : TimerTask() {
            override fun run() {
                resetVolumeSettings(applicationContext, "Periodic Timer")
            }
        }, 120000, 1000 * 60 * 30)
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
            val resolver = context.contentResolver
            Settings.Global.putInt(resolver, "audio_safe_volume_state", 2)
            Settings.Secure.putInt(resolver, "unsafe_volume_music_active_ms", 0)
            Settings.Global.putInt(resolver, "safe_audio_volume_enforced", 0)
            Settings.Global.putFloat(resolver, "audio_safe_csd_current_value", 0.0f)
            Settings.Global.putString(resolver, "audio_safe_csd_dose_records", "[]")
            
            Log.d("VolumeFixer", "Volume settings reset via $source.")
            Logger.log(context, "Fix applied via $source")
        } catch (e: SecurityException) {
            Logger.log(context, "ERROR: Permission missing for $source")
        }
    }

    private fun createNotification(): Notification {
        val channelId = "volume_fixer_service"
        manager.createNotificationChannel(NotificationChannel(channelId, "Volume Fixer Service", NotificationManager.IMPORTANCE_LOW))

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Safe Volume Fixer Active")
            .setContentText("Watching system for volume restrictions.")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private val manager get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val NOTIFICATION_ID = 101
    }
}
