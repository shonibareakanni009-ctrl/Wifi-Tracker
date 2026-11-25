package com.wifitracker.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.wifitracker.app.MainActivity
import com.wifitracker.app.R
import com.wifitracker.app.data.UsagePeriod
import com.wifitracker.app.data.WifiUsageRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Performs potentially slow platform statistics reads in WorkManager, never in onUpdate(). */
class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val ids = inputData.getIntArray(KEY_WIDGET_IDS) ?: return Result.failure()
        val kind = inputData.getString(KEY_KIND) ?: return Result.failure()
        return try {
            when (kind) {
                KIND_USAGE -> refreshUsageWidgets(ids)
                KIND_SPEED -> refreshSpeedWidgets(ids)
                else -> return Result.failure()
            }
            Result.success()
        } catch (_: Exception) {
            showUnavailable(ids, kind)
            Result.success()
        }
    }

    private fun refreshUsageWidgets(ids: IntArray) {
        val period = inputData.getString(KEY_PERIOD)
            ?.let { runCatching { UsagePeriod.valueOf(it) }.getOrNull() }
            ?: return
        val title = when (period) {
            UsagePeriod.TODAY -> "Today"
            UsagePeriod.WEEK -> "This Week"
            UsagePeriod.MONTH -> "This Month"
        }
        val report = WifiUsageRepository(applicationContext).getWifiUsageReport(period)
        val value = if (report.isAvailable) formatBytes(report.totalBytes) else "Unavailable"
        val detail = if (report.isAvailable) "Updated ${timeNow()} · tap to refresh"
            else "Open app to check Usage Access"
        ids.forEach { id ->
            val views = RemoteViews(applicationContext.packageName, R.layout.widget_usage)
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_value, value)
            views.setTextViewText(R.id.widget_updated, detail)
            attachOpenAppAction(views, id)
            AppWidgetManager.getInstance(applicationContext).updateAppWidget(id, views)
        }
    }

    private fun refreshSpeedWidgets(ids: IntArray) {
        val repository = WifiUsageRepository(applicationContext)
        val speedText = if (!repository.isWifiConnected()) {
            "Wi-Fi disconnected"
        } else {
            var sample = repository.getCurrentWifiSpeeds("widget")
            if (!sample.isAvailable && sample.note?.contains("Sampling", ignoreCase = true) == true) {
                // The first counter read establishes a baseline. A short second sample avoids
                // leaving widgets stuck on a warm-up state after a long idle period.
                SystemClock.sleep(1_000L)
                sample = repository.getCurrentWifiSpeeds("widget")
            }
            if (sample.isAvailable) {
                "↓ ${formatSpeed(sample.downloadBytesPerSecond)}  ↑ ${formatSpeed(sample.uploadBytesPerSecond)}"
            } else if (sample.note?.contains("Sampling", ignoreCase = true) == true) {
                "Sampling speed…"
            } else {
                "Speed unavailable"
            }
        }
        ids.forEach { id ->
            val views = RemoteViews(applicationContext.packageName, R.layout.widget_speed)
            views.setTextViewText(R.id.widget_speed, speedText)
            views.setTextViewText(R.id.widget_updated, "Updated ${timeNow()} · tap to refresh")
            attachOpenAppAction(views, id)
            AppWidgetManager.getInstance(applicationContext).updateAppWidget(id, views)
        }
    }

    private fun showUnavailable(ids: IntArray, kind: String) {
        ids.forEach { id ->
            val layout = if (kind == KIND_SPEED) R.layout.widget_speed else R.layout.widget_usage
            val views = RemoteViews(applicationContext.packageName, layout)
            if (kind == KIND_SPEED) {
                views.setTextViewText(R.id.widget_speed, "Unavailable")
            } else {
                views.setTextViewText(R.id.widget_value, "Unavailable")
            }
            views.setTextViewText(R.id.widget_updated, "Open app to retry")
            attachOpenAppAction(views, id)
            AppWidgetManager.getInstance(applicationContext).updateAppWidget(id, views)
        }
    }

    private fun attachOpenAppAction(views: RemoteViews, widgetId: Int) {
        val intent = Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            widgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
    }

    private fun timeNow(): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    private fun formatSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
        bytesPerSecond < 1024L * 1024 -> "%.0f KB/s".format(bytesPerSecond / 1024.0)
        else -> "%.1f MB/s".format(bytesPerSecond / (1024.0 * 1024))
    }

    companion object {
        const val KIND_USAGE = "usage"
        const val KIND_SPEED = "speed"
        const val KEY_KIND = "kind"
        const val KEY_PERIOD = "period"
        const val KEY_WIDGET_IDS = "widget_ids"
    }
}
