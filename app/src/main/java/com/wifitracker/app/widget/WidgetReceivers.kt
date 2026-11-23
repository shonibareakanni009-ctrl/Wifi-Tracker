package com.wifitracker.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.widget.RemoteViews
import com.wifitracker.app.R
import com.wifitracker.app.data.UsagePeriod
import com.wifitracker.app.data.WifiUsageRepository

class DailyUsageWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateUsageWidgets(context, appWidgetManager, appWidgetIds, UsagePeriod.TODAY, "Today", goAsync())
    }
}

class WeeklyUsageWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateUsageWidgets(context, appWidgetManager, appWidgetIds, UsagePeriod.WEEK, "This Week", goAsync())
    }
}

class MonthlyUsageWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateUsageWidgets(context, appWidgetManager, appWidgetIds, UsagePeriod.MONTH, "This Month", goAsync())
    }
}

private fun updateUsageWidgets(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
    period: UsagePeriod,
    title: String,
    pendingResult: BroadcastReceiver.PendingResult
) {
    Thread({
        try {
            val report = WifiUsageRepository(context.applicationContext).getWifiUsageReport(period)
            val value = if (report.isAvailable) formatBytes(report.totalBytes) else "Unavailable"
            appWidgetIds.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_usage)
                views.setTextViewText(R.id.widget_title, title)
                views.setTextViewText(R.id.widget_value, value)
                appWidgetManager.updateAppWidget(id, views)
            }
        } catch (_: Exception) {
            appWidgetIds.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_usage)
                views.setTextViewText(R.id.widget_title, title)
                views.setTextViewText(R.id.widget_value, "Unavailable")
                appWidgetManager.updateAppWidget(id, views)
            }
        } finally {
            pendingResult.finish()
        }
    }, "wifi-usage-widget").start()
}

class LiveSpeedWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        Thread({
            try {
                val sample = WifiUsageRepository(context.applicationContext).getCurrentWifiSpeeds("widget")
                val text = if (sample.isAvailable) {
                    "↓ ${formatSpeed(sample.downloadBytesPerSecond)}  ↑ ${formatSpeed(sample.uploadBytesPerSecond)}"
                } else {
                    "Wi-Fi speed unavailable"
                }
                appWidgetIds.forEach { id ->
                    val views = RemoteViews(context.packageName, R.layout.widget_speed)
                    views.setTextViewText(R.id.widget_speed, text)
                    appWidgetManager.updateAppWidget(id, views)
                }
            } catch (_: Exception) {
                appWidgetIds.forEach { id ->
                    val views = RemoteViews(context.packageName, R.layout.widget_speed)
                    views.setTextViewText(R.id.widget_speed, "Wi-Fi speed unavailable")
                    appWidgetManager.updateAppWidget(id, views)
                }
            } finally {
                pendingResult.finish()
            }
        }, "wifi-speed-widget").start()
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}

private fun formatSpeed(bytesPerSecond: Long): String {
    return when {
        bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
        bytesPerSecond < 1024 * 1024 -> "%.0f KB/s".format(bytesPerSecond / 1024.0)
        else -> "%.1f MB/s".format(bytesPerSecond / (1024.0 * 1024))
    }
}
