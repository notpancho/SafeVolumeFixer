package com.safe.comsafevolumefixer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        try {
            context.startForegroundService(Intent(context, FixerService::class.java))
        } catch (e: Exception) {
            Log.e("VolumeFixer", "Could not start FixerService at boot: ${e.message}")
        }

        // Keep the process alive until the settings write finishes (goAsync), then release the receiver.
        val pending = goAsync()
        SafetySettings.applyAsync(context, "Boot ($action)") { pending.finish() }
    }
}
