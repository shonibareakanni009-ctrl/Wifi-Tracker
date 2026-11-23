package com.wifitracker.app.data

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.os.RemoteException
import android.telephony.TelephonyManager
import java.util.Calendar

enum class UsagePeriod {
    TODAY, WEEK, MONTH
}

data class AppUsage(
    val packageName: String,
    val appName: String,
    val bytes: Long
)

data class NetworkUsage(
    val ssid: String,
    val bytes: Long
)

class WifiUsageRepository(private val context: Context) {

    private val networkStatsManager =
        context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

    private var lastRxBytes = TrafficStats.getTotalRxBytes()
    private var lastTxBytes = TrafficStats.getTotalTxBytes()
    private var lastTimestamp = System.currentTimeMillis()

    fun getCurrentSpeeds(): Pair<Long, Long> {
        val currentRx = TrafficStats.getTotalRxBytes()
        val currentTx = TrafficStats.getTotalTxBytes()
        val now = System.currentTimeMillis()
        val elapsedSeconds = ((now - lastTimestamp) / 1000).coerceAtLeast(1)

        val downloadSpeed = ((currentRx - lastRxBytes) / elapsedSeconds).coerceAtLeast(0)
        val uploadSpeed = ((currentTx - lastTxBytes) / elapsedSeconds).coerceAtLeast(0)

        lastRxBytes = currentRx
        lastTxBytes = currentTx
        lastTimestamp = now

        return Pair(downloadSpeed, uploadSpeed)
    }

    fun getCurrentSsid(): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo
            val ssid = info?.ssid?.removeSurrounding("\"")
            if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") {
                "Not connected"
            } else {
                ssid
            }
        } catch (e: Exception) {
            "Not connected"
        }
    }

    fun getSignalStrength(): Int {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val rssi = wifiManager.connectionInfo?.rssi ?: -100
            WifiManager.calculateSignalLevel(rssi, 5) // 0-4
        } catch (e: Exception) {
            0
        }
    }

    fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun getTotalWifiUsage(period: UsagePeriod): Long {
        val (start, end) = getTimeRange(period)
        return try {
            val bucket = networkStatsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_WIFI,
                null,
                start,
                end
            )
            (bucket?.rxBytes ?: 0L) + (bucket?.txBytes ?: 0L)
        } catch (e: Exception) {
            0L
        }
    }

    fun getPerAppWifiUsage(period: UsagePeriod): List<AppUsage> {
        val (start, end) = getTimeRange(period)
        val result = mutableListOf<AppUsage>()
        val pm = context.packageManager

        try {
            val stats = networkStatsManager.querySummary(
                ConnectivityManager.TYPE_WIFI,
                null,
                start,
                end
            )

            val uidMap = mutableMapOf<Int, Long>()

            while (stats.hasNextBucket()) {
                val bucket = NetworkStats.Bucket()
                stats.getNextBucket(bucket)
                if (bucket.uid == NetworkStats.Bucket.UID_ALL ||
                    bucket.uid == NetworkStats.Bucket.UID_REMOVED ||
                    bucket.uid == NetworkStats.Bucket.UID_TETHERING) continue

                val total = bucket.rxBytes + bucket.txBytes
                uidMap[bucket.uid] = (uidMap[bucket.uid] ?: 0L) + total
            }
            stats.close()

            for ((uid, bytes) in uidMap) {
                if (bytes < 10_000) continue // skip tiny usage
                val packages = pm.getPackagesForUid(uid)
                val packageName = packages?.firstOrNull() ?: continue
                val appName = try {
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    packageName
                }
                result.add(AppUsage(packageName, appName, bytes))
            }
        } catch (e: Exception) {
            // Permission or other error
        }

        return result.sortedByDescending { it.bytes }
    }

    /**
     * Per-SSID tracking is limited by Android.
     * We return the current SSID with today's total as a practical approximation.
     * Full historical per-SSID requires a persistent service that records
     * TrafficStats deltas on every SSID change.
     */
    fun getPerNetworkUsage(period: UsagePeriod): List<NetworkUsage> {
        val current = getCurrentSsid()
        val total = getTotalWifiUsage(period)
        return if (current != "Not connected" && total > 0) {
            listOf(NetworkUsage(current, total))
        } else {
            emptyList()
        }
    }

    private fun getTimeRange(period: UsagePeriod): Pair<Long, Long> {
        val end = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        when (period) {
            UsagePeriod.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
            }
            UsagePeriod.WEEK -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
            }
            UsagePeriod.MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
            }
        }
        return Pair(cal.timeInMillis, end)
    }
}
