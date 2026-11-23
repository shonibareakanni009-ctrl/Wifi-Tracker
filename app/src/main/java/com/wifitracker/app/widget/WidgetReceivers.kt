package com.wifitracker.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.wifitracker.app.R
import com.wifitracker.app.data.UsagePeriod
import com.wifitracker.app.data.WifiUsageRepository

class DailyUsageWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val repo = WifiUsageRepository(context)
        val bytes = try { repo.getTotalWifiUsage(UsagePeriod.TODAY) } catch (e: Exception) { 0L }
        val value = formatBytes(bytes)

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_usage)
            views.setTextViewText(R.id.widget_title, "Today")
            views.setTextViewText(R.id.widget_value, value)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

class WeeklyUsageWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val repo = WifiUsageRepository(context)
        val bytes = try { repo.getTotalWifiUsage(UsagePeriod.WEEK) } catch (e: Exception) { 0L }
        val value = formatBytes(bytes)

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_usage)
            views.setTextViewText(R.id.widget_title, "This Week")
            views.setTextViewText(R.id.widget_value, value)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

class MonthlyUsageWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val repo = WifiUsageRepository(context)
        val bytes = try { repo.getTotalWifiUsage(UsagePeriod.MONTH) } catch (e: Exception) { 0L }
        val value = formatBytes(bytes)

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_usage)
            views.setTextViewText(R.id.widget_title, "This Month")
            views.setTextViewText(R.id.widget_value, value)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

class LiveSpeedWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val repo = WifiUsageRepository(context)
        val (down, up) = try { repo.getCurrentSpeeds() } catch (e: Exception) { Pair(0L, 0L) }
        val text = "↓ ${formatSpeed(down)}  ↑ ${formatSpeed(up)}"

        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_speed)
            views.setTextViewText(R.id.widget_speed, text)
            appWidgetManager.updateAppWidget(id, views)
        }
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
