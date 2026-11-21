package com.wifitracker.app.data

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.RemoteException
import java.util.Calendar

enum class UsagePeriod {
    TODAY, WEEK, MONTH
}

class WifiUsageRepository(private val context: Context? = null) {

    // For preview / non-context use we keep simple fallbacks
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
        // In real app this would use WifiManager. In this skeleton we return a placeholder.
        // Full implementation requires ACCESS_WIFI_STATE and handling of null SSID.
        return "Current WiFi"
    }

    fun getSignalStrength(): Int {
        return 3 // 0-4 scale placeholder
    }

    fun getTotalWifiUsage(period: UsagePeriod): Long {
        // Placeholder values for the skeleton.
        // Real implementation uses NetworkStatsManager.querySummaryForDevice
        // with ConnectivityManager.TYPE_WIFI and proper start/end timestamps.
        return when (period) {
            UsagePeriod.TODAY -> 1_850_000_000L      // ~1.85 GB example
            UsagePeriod.WEEK -> 8_420_000_000L       // ~8.4 GB
            UsagePeriod.MONTH -> 24_600_000_000L     // ~24.6 GB
        }
    }

    fun getPerAppWifiUsage(period: UsagePeriod): List<Pair<String, Long>> {
        // Placeholder data. Real implementation queries NetworkStatsManager
        // per UID for TYPE_WIFI in the given time range and resolves package names.
        return listOf(
            "YouTube" to 620_000_000L,
            "Chrome" to 410_000_000L,
            "Instagram" to 280_000_000L,
            "WhatsApp" to 190_000_000L,
            "Netflix" to 150_000_000L,
            "TikTok" to 130_000_000L,
            "System" to 95_000_000L,
            "Google Play" to 70_000_000L
        )
    }

    // Helper to compute start of day / week / month
    private fun getStartOfPeriod(period: UsagePeriod): Long {
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
        return cal.timeInMillis
    }
}
