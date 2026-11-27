package com.wifitracker.app.data

import android.Manifest
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.Calendar


enum class UsagePeriod {
    TODAY, WEEK, MONTH
}

data class AppUsage(
    /** A stable row key; shared UIDs may contain more than one package name. */
    val packageName: String,
    val appName: String,
    val bytes: Long,
    /** Installed packages that can supply an icon for this UID. */
    val iconPackageNames: List<String> = emptyList()
)

data class WifiUsageReport(
    val totalBytes: Long,
    val rows: List<AppUsage>,
    val isAvailable: Boolean,
    val note: String? = null
)

data class DailyWifiUsage(
    val dayStartMillis: Long,
    val report: WifiUsageReport
)

data class MonthlyWifiUsage(
    val monthStartMillis: Long,
    val totalBytes: Long,
    val isAvailable: Boolean
)

data class WifiSpeedSample(
    val downloadBytesPerSecond: Long,
    val uploadBytesPerSecond: Long,
    /** False while sampling, when interface counters are unsupported, or when the sample is stale. */
    val isAvailable: Boolean,
    val note: String? = null
)

class WifiUsageRepository(private val context: Context) {

    private val networkStatsManager =
        context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

    /**
     * Uses Android's device-level Wi-Fi total and per-UID Wi-Fi summary for the same interval.
     * Every UID with bytes is represented, including system/uninstalled UIDs. If Android's
     * device and UID totals differ, the gap is placed in a System / unassigned row so the visible
     * rows always add up exactly to the displayed total.
     */
    fun getWifiUsageReport(period: UsagePeriod): WifiUsageReport {
        val (start, end) = getTimeRange(period)
        return getWifiUsageReport(start, end)
    }

    /** Returns a report for one calendar month, using local calendar boundaries. */
    fun getWifiUsageReportForMonth(monthStartMillis: Long): WifiUsageReport {
        val start = Calendar.getInstance().apply {
            timeInMillis = monthStartMillis
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        return getWifiUsageReport(start.timeInMillis, minOf(end.timeInMillis, System.currentTimeMillis()))
    }

    /**
     * Loads month totals from December of the previous calendar year through the current month.
     * Android may not retain all older network buckets, so an unavailable result is preserved.
     */
    fun getWifiUsageMonthHistorySinceLastDecember(): List<MonthlyWifiUsage> {
        val now = Calendar.getInstance()
        val firstMonth = Calendar.getInstance().apply {
            timeInMillis = now.timeInMillis
            set(Calendar.MONTH, Calendar.DECEMBER)
            set(Calendar.YEAR, now.get(Calendar.YEAR) - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val result = mutableListOf<MonthlyWifiUsage>()
        val cursor = firstMonth.clone() as Calendar
        while (cursor.timeInMillis <= now.timeInMillis) {
            val start = cursor.timeInMillis
            val next = (cursor.clone() as Calendar).apply { add(Calendar.MONTH, 1) }.timeInMillis
            val report = getWifiUsageReport(start, minOf(next, now.timeInMillis))
            result += MonthlyWifiUsage(start, report.totalBytes, report.isAvailable)
            cursor.add(Calendar.MONTH, 1)
        }
        return result
    }

    /** Returns Wi-Fi usage for an explicit interval, using the same reconciled device/UID totals. */
    fun getWifiUsageReport(startTimeMillis: Long, endTimeMillis: Long): WifiUsageReport {
        val start = startTimeMillis.coerceAtMost(endTimeMillis)
        val end = endTimeMillis.coerceAtLeast(startTimeMillis)

        val deviceTotal = try {
            val bucket = networkStatsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_WIFI,
                null,
                start,
                end
            )
            bucket?.let { safeAdd(it.rxBytes, it.txBytes) }
        } catch (_: Exception) {
            null
        }

        val stats = try {
            networkStatsManager.querySummary(
                ConnectivityManager.TYPE_WIFI,
                null,
                start,
                end
            )
        } catch (_: Exception) {
            return WifiUsageReport(
                totalBytes = 0L,
                rows = emptyList(),
                isAvailable = false,
                note = "Android could not read Wi-Fi usage. Check Usage Access permission and try again."
            )
        } ?: return WifiUsageReport(
            totalBytes = 0L,
            rows = emptyList(),
            isAvailable = false,
            note = "Android did not provide Wi-Fi usage data for this period."
        )

        val bytesByUid = mutableMapOf<Int, Long>()
        try {
            while (stats.hasNextBucket()) {
                val bucket = NetworkStats.Bucket()
                if (!stats.getNextBucket(bucket)) break

                // UID_ALL is an aggregate sentinel; counting it alongside per-UID buckets would
                // double-count traffic. The device total below accounts for it when available.
                if (bucket.uid == NetworkStats.Bucket.UID_ALL) continue

                val bytes = safeAdd(bucket.rxBytes, bucket.txBytes)
                if (bytes > 0L) {
                    bytesByUid[bucket.uid] = safeAdd(bytesByUid[bucket.uid] ?: 0L, bytes)
                }
            }
        } catch (_: Exception) {
            return WifiUsageReport(
                totalBytes = 0L,
                rows = emptyList(),
                isAvailable = false,
                note = "Android returned incomplete Wi-Fi usage data. Refresh and try again."
            )
        } finally {
            stats.close()
        }

        val uidTotal = bytesByUid.values.fold(0L, ::safeAdd)
        val total = maxOf(deviceTotal ?: uidTotal, uidTotal)
        val rows = mutableListOf<AppUsage>()

        for ((uid, bytes) in bytesByUid) {
            if (uid < 0) {
                // Removed/tethering/system sentinel UIDs cannot be attributed to a regular app.
                rows += AppUsage(
                    packageName = "uid-$uid",
                    appName = "System traffic (UID $uid)",
                    bytes = bytes
                )
                continue
            }

            val packages = try {
                context.packageManager.getPackagesForUid(uid)
                    ?.distinct()
                    ?.sorted()
                    .orEmpty()
            } catch (_: Exception) {
                emptyList()
            }

            when (packages.size) {
                0 -> rows += AppUsage(
                    packageName = "uid-$uid",
                    appName = "Unidentified app/system (UID $uid)",
                    bytes = bytes
                )
                1 -> {
                    val packageName = packages.single()
                    rows += AppUsage(
                        packageName = packageName,
                        appName = getAppLabel(packageName),
                        bytes = bytes,
                        iconPackageNames = listOf(packageName)
                    )
                }
                else -> {
                    // Android reports usage for a shared UID, not separately for each package.
                    // Keep it as one combined row rather than assigning all bytes to one app.
                    val labels = packages.map(::getAppLabel)
                    rows += AppUsage(
                        packageName = packages.joinToString(separator = "+"),
                        appName = "Shared UID: ${labels.joinToString(" + ")}",
                        bytes = bytes,
                        iconPackageNames = packages
                    )
                }
            }
        }

        val unexplainedDeviceBytes = (total - uidTotal).coerceAtLeast(0L)
        if (unexplainedDeviceBytes > 0L) {
            rows += AppUsage(
                packageName = "system-unassigned",
                appName = "System / unassigned",
                bytes = unexplainedDeviceBytes
            )
        }

        val sortedRows = rows.sortedByDescending { it.bytes }
        val notes = buildList {
            if (deviceTotal == null) {
                add("Device-level total was unavailable; the total is the sum of Android's per-UID Wi-Fi data.")
            } else if (deviceTotal != uidTotal) {
                add("Android's device and per-UID totals differ. The larger total is shown, and System / unassigned reconciles any positive gap so all rows add up.")
            }
            if (rows.any { it.appName.startsWith("Unidentified app/system") }) {
                add("Some UID usage could not be matched to an installed app name; those bytes remain visible by UID.")
            }
        }
        val note = notes.takeIf { it.isNotEmpty() }?.joinToString(" ")

        return WifiUsageReport(
            totalBytes = total,
            rows = sortedRows,
            isAvailable = true,
            note = note
        )
    }

    /**
     * Reads one report per calendar day for the current local week. Android's historical network
     * statistics remain the source of truth; days beyond today are returned as empty future days.
     */
    fun getCurrentWeekWifiUsage(): List<DailyWifiUsage> {
        val now = System.currentTimeMillis()
        val today = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = today.timeInMillis
        today.set(Calendar.DAY_OF_WEEK, today.firstDayOfWeek)
        val weekStart = today.timeInMillis
        val dayCursor = Calendar.getInstance().apply { timeInMillis = weekStart }
        val result = mutableListOf<DailyWifiUsage>()

        repeat(7) {
            val dayStart = dayCursor.timeInMillis
            val nextDay = (dayCursor.clone() as Calendar).apply {
                add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
            if (dayStart > todayStart) {
                result += DailyWifiUsage(
                    dayStartMillis = dayStart,
                    report = WifiUsageReport(0L, emptyList(), isAvailable = true)
                )
            } else {
                val dayEnd = minOf(nextDay, now)
                result += DailyWifiUsage(
                    dayStartMillis = dayStart,
                    report = getWifiUsageReport(dayStart, dayEnd)
                )
            }
            dayCursor.add(Calendar.DAY_OF_MONTH, 1)
        }
        return result
    }

    /**
     * Samples byte counters for the currently connected Wi-Fi interface only (not cellular or
     * other interfaces). A distinct sample key keeps the screen and widget baselines independent.
     */
    fun getCurrentWifiSpeeds(sampleKey: String = "screen"): WifiSpeedSample {
        val prefs = context.getSharedPreferences("wifi_speed_samples", Context.MODE_PRIVATE)
        val key = sampleKey.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val interfaceName = getWifiInterfaceName()

        if (interfaceName == null) {
            clearSpeedBaseline(prefs, key)
            return WifiSpeedSample(0L, 0L, isAvailable = true)
        }

        val rx = TrafficStats.getRxBytes(interfaceName)
        val tx = TrafficStats.getTxBytes(interfaceName)
        if (rx < 0L || tx < 0L) {
            clearSpeedBaseline(prefs, key)
            return WifiSpeedSample(
                0L,
                0L,
                isAvailable = false,
                note = "Wi-Fi interface counters are unavailable on this device."
            )
        }

        val now = System.currentTimeMillis()
        val rxKey = "${key}_rx"
        val txKey = "${key}_tx"
        val ifaceKey = "${key}_iface"
        val timeKey = "${key}_time"
        val previousRx = prefs.getLong(rxKey, -1L)
        val previousTx = prefs.getLong(txKey, -1L)
        val previousInterface = prefs.getString(ifaceKey, null)
        val previousTime = prefs.getLong(timeKey, 0L)

        prefs.edit()
            .putLong(rxKey, rx)
            .putLong(txKey, tx)
            .putString(ifaceKey, interfaceName)
            .putLong(timeKey, now)
            .apply()

        val elapsed = now - previousTime
        if (previousRx < 0L || previousTx < 0L || previousInterface != interfaceName || elapsed !in 250L..15_000L) {
            return WifiSpeedSample(
                0L,
                0L,
                isAvailable = false,
                note = "Sampling Wi-Fi throughput; rates will appear after another reading."
            )
        }

        val down = ((rx - previousRx).coerceAtLeast(0L) * 1000L) / elapsed
        val up = ((tx - previousTx).coerceAtLeast(0L) * 1000L) / elapsed
        return WifiSpeedSample(down, up, isAvailable = true)
    }

    fun getCurrentSsid(): String {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) return "Allow Wi-Fi name access"
            if (!isLocationEnabled()) return "Turn on Location Services to show Wi-Fi name"
            if (!isWifiConnected()) return "Not connected"
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ssid = wifiManager.connectionInfo?.ssid?.removeSurrounding("\"")
            if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") "Wi-Fi name unavailable" else ssid
        } catch (_: Exception) {
            "Wi-Fi name unavailable"
        }
    }

    fun getSignalStrength(): Int {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val rssi = wifiManager.connectionInfo?.rssi ?: -100
            WifiManager.calculateSignalLevel(rssi, 5).coerceIn(0, 4)
        } catch (_: Exception) {
            0
        }
    }

    fun isWifiConnected(): Boolean = getWifiInterfaceName() != null

    private fun isLocationEnabled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                ?.isLocationEnabled == true
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE) !=
                Settings.Secure.LOCATION_MODE_OFF
        }
    } catch (_: Exception) {
        false
    }

    private fun getWifiInterfaceName(): String? {
        return try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networks = (listOfNotNull(connectivityManager.activeNetwork) + connectivityManager.allNetworks)
                .distinct()

            networks.firstNotNullOfOrNull { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    connectivityManager.getLinkProperties(network)?.interfaceName
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getAppLabel(packageName: String): String {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private fun clearSpeedBaseline(prefs: android.content.SharedPreferences, key: String) {
        prefs.edit()
            .remove("${key}_rx")
            .remove("${key}_tx")
            .remove("${key}_iface")
            .remove("${key}_time")
            .apply()
    }

    private fun safeAdd(first: Long, second: Long): Long {
        if (first <= 0L) return second.coerceAtLeast(0L)
        if (second <= 0L) return first
        return if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second
    }

    private fun getTimeRange(period: UsagePeriod): Pair<Long, Long> {
        val end = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = end }

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
        return cal.timeInMillis to end
    }

}
