package com.wifitracker.app.networkusage

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.content.Context.LOCATION_SERVICE
import android.location.LocationManager
import android.net.TrafficStats
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** User-started, local-only foreground sampler. It never opens a network listener. */
class WifiNetworkUsageService : Service() {
    private var scheduler: ScheduledExecutorService? = null
    private var lastNotificationText: String? = null
    private var lastPrunedDay: String? = null
    private var baseline: Sample? = null
    private lateinit var database: WifiNetworkUsageDatabase

    override fun onCreate() {
        super.onCreate()
        database = WifiNetworkUsageDatabase(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                setTracking(this, false)
                scheduler?.shutdownNow()
                scheduler = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            null -> {
                // START_STICKY restoration is allowed only if a prior explicit user start is persisted.
                if (!WifiNetworkUsageStore(applicationContext).isTracking()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
            }
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        try {
            createNotificationChannel()
            val initialText = waitingForSsidMessage()
            startAsForeground(buildNotification(initialText))
            setTracking(this, true)
            updateNotification(initialText)
            ensureSampler()
        } catch (_: Exception) {
            setTracking(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scheduler?.shutdownNow()
        scheduler = null
        if (::database.isInitialized) database.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureSampler() {
        if (scheduler != null) return
        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "WifiUsageSampler").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay({ sampleSafely() }, 0L, SAMPLE_INTERVAL_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun sampleSafely() {
        try {
            pruneExpiredRowsOnceToday()
            val current = readSample()
            if (current == null) {
                // Force a fresh baseline after any period with no valid SSID/interface.
                baseline = null
                updateNotification(waitingForSsidMessage())
                return
            }
            val previous = baseline
            if (previous == null || previous.ssid != current.ssid || previous.interfaceName != current.interfaceName ||
                current.rxBytes < previous.rxBytes || current.txBytes < previous.txBytes
            ) {
                // Initial, changed-network/interface, and reset samples are baselines only.
                baseline = current
                updateNotification("Sampling Wi-Fi traffic locally (about every 15 seconds)")
                return
            }

            val rxDelta = current.rxBytes - previous.rxBytes
            val txDelta = current.txBytes - previous.txBytes
            if (rxDelta > 0L || txDelta > 0L) {
                // LocalDate uses the device's current timezone. A sample spanning midnight is
                // assigned wholly to the day observed at this sample; the interval is approximate.
                val stored = runCatching {
                    database.addDelta(
                        current.ssid,
                        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
                        rxDelta,
                        txDelta
                    )
                }.isSuccess
                if (stored) {
                    contentResolver.notifyChange(Uri.parse(WifiNetworkUsageStore.CONTENT_URI), null)
                    contentResolver.notifyChange(Uri.parse(WifiNetworkUsageStore.DAILY_CONTENT_URI), null)
                }
            }
            baseline = current
            updateNotification("Sampling Wi-Fi traffic locally (about every 15 seconds)")
        } catch (_: Exception) {
            // Missing permissions, Wi-Fi changes, service/device quirks, or DB errors never crash the service.
            baseline = null
            updateNotification(waitingForSsidMessage())
        }
    }

    private fun pruneExpiredRowsOnceToday() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (lastPrunedDay == today) return
        val preferences = getSharedPreferences(
            WifiNetworkUsageStore.SETTINGS_PREFERENCES,
            Context.MODE_PRIVATE
        )
        val retentionDays = preferences.getInt(
            WifiNetworkUsageStore.PREF_RETENTION_DAYS,
            WifiNetworkUsageStore.DEFAULT_RETENTION_DAYS
        )
        runCatching { WifiNetworkUsageStore(applicationContext).pruneExpired(retentionDays) }
            .onSuccess { lastPrunedDay = today }
    }

    private fun waitingForSsidMessage(): String = when {
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED -> "Allow Wi-Fi name permission to track per network"
        !isLocationEnabled() -> "Turn on Location Services to identify the Wi-Fi network"
        else -> "Waiting for a connected Wi-Fi network and readable name"
    }

    private fun readSample(): Sample? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        if (!isLocationEnabled()) return null

        val wifiManager = getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val ssid = normalizeSsid(runCatching { wifiManager.connectionInfo?.ssid }.getOrNull()) ?: return null

        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val wifiInterface = runCatching {
            val network = connectivity.activeNetwork ?: return null
            val caps = connectivity.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                connectivity.getLinkProperties(network)?.interfaceName?.takeIf { it.isNotBlank() }
            } else null
        }.getOrNull() ?: return null

        val rx = TrafficStats.getRxBytes(wifiInterface)
        val tx = TrafficStats.getTxBytes(wifiInterface)
        if (rx < 0L || tx < 0L) return null
        return Sample(ssid, wifiInterface, rx, tx)
    }

    @Suppress("DEPRECATION")
    private fun normalizeSsid(raw: String?): String? {
        val value = raw?.trim()?.removeSurrounding("\"", "\"")?.trim() ?: return null
        if (value.isBlank() || value.equals("<unknown ssid>", ignoreCase = true) ||
            value.equals("unknown ssid", ignoreCase = true) || value == "0x"
        ) return null
        return value
    }

    private fun isLocationEnabled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val manager = getSystemService(LOCATION_SERVICE) as? LocationManager
            manager?.isLocationEnabled == true
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE) !=
                Settings.Secure.LOCATION_MODE_OFF
        }
    } catch (_: Exception) {
        false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Wi-Fi usage tracking",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Visible status for user-started local Wi-Fi usage sampling" }
            )
        }
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = Intent(this, WifiNetworkUsageService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Wi-Fi usage tracking")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        if (lastNotificationText == status) return
        lastNotificationText = status
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification(status))
        }
    }

    private data class Sample(
        val ssid: String,
        val interfaceName: String,
        val rxBytes: Long,
        val txBytes: Long
    )

    companion object {
        const val ACTION_START = "com.wifitracker.app.networkusage.action.START"
        const val ACTION_STOP = "com.wifitracker.app.networkusage.action.STOP"
        const val PREFERENCES_NAME = "wifi_network_usage_tracker"
        const val PREF_TRACKING = "tracking_requested"
        private const val CHANNEL_ID = "wifi_network_usage_tracking"
        private const val NOTIFICATION_ID = 24015
        private const val STOP_REQUEST_CODE = 24016
        private const val SAMPLE_INTERVAL_SECONDS = 15L

        /** Call only from the foreground UI after required runtime permissions are granted. */
        fun startTracking(context: Context) {
            val intent = Intent(context, WifiNetworkUsageService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /** UI stop helper; the persistent notification also sends ACTION_STOP directly. */
        fun stopTracking(context: Context) {
            setTracking(context.applicationContext, false)
            context.stopService(Intent(context, WifiNetworkUsageService::class.java))
        }

        private fun setTracking(context: Context, tracking: Boolean) {
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_TRACKING, tracking).commit()
        }
    }
}
