package com.wifitracker.app.networkusage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/** Restores only a tracker the user had left running before the device rebooted. */
class WifiNetworkUsageBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val appContext = context.applicationContext
        if (!WifiNetworkUsageStore(appContext).isTracking()) return

        val serviceIntent = Intent(appContext, WifiNetworkUsageService::class.java)
            .setAction(WifiNetworkUsageService.ACTION_START)
        try {
            ContextCompat.startForegroundService(appContext, serviceIntent)
        } catch (exception: Exception) {
            // A device or OEM restriction can still prevent auto-resume; do not crash boot.
            Log.w(TAG, "Could not resume Wi-Fi usage tracking after reboot", exception)
        }
    }

    private companion object {
        const val TAG = "WifiTrackerBoot"
    }
}
