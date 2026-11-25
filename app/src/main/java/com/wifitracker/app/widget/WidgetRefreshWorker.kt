package com.wifitracker.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wifitracker.app.MainActivity
import com.wifitracker.app.R
import com.wifitracker.app.data.UsagePeriod
import com.wifitracker.app.data.WifiUsageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Performs potentially slow platform statistics reads in WorkManager, never in onUpdate(). */
class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND) ?: return Result.failure()
        val period = inputData.getString(KEY_PERIOD)
            ?.let { runCatching { UsagePeriod.valueOf(it) }.getOrNull() }
        val ids = inputData.getIntArray(KEY_WIDGET_IDS) ?: currentWidgetIds(kind, period)
        if (ids.isEmpty()) return Result.success()

        return try {
            when (kind) {
                KIND_USAGE -> refreshUsageWidgets(ids)
                KIND_SPEED -> refreshSpeedWidgets(ids)
                else -> return Result.failure()
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            showUnavailable(ids, kind)
            Result.success()
        }
    }

    private suspend fun refreshUsageWidgets(ids: IntArray) {
        val period = inputData.getString(KEY_PERIOD)
            ?.let { runCatching { UsagePeriod.valueOf(it) }.getOrNull() }
            ?: return
        val title = when (period) {
            UsagePeriod.TODAY -> "Today"
            UsagePeriod.WEEK -> "This Week"
            UsagePeriod.MONTH -> "This Month"
        }
        val report = withContext(Dispatchers.IO) {
            WifiUsageRepository(applicationContext).getWifiUsageReport(period)
        }
        val value = if (report.isAvailable) formatBytes(report.totalBytes) else "Unavailable"
        val detail = if (report.isAvailable) "Updated ${timeNow()} · periodic refresh"
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

    private suspend fun refreshSpeedWidgets(ids: IntArray) {
        val repository = WifiUsageRepository(applicationContext)
        val isWifiConnected = withContext(Dispatchers.IO) { repository.isWifiConnected() }
        val speedText = if (!isWifiConnected) {
            "Wi-Fi disconnected"
        } else {
            var sample = withContext(Dispatchers.IO) { repository.getCurrentWifiSpeeds("widget") }
            if (!sample.isAvailable && sample.note?.contains("Sampling", ignoreCase = true) == true) {
                // The first counter read establishes a baseline. Suspend instead of blocking a worker thread.
                delay(SPEED_SAMPLE_INTERVAL_MS)
                sample = withContext(Dispatchers.IO) { repository.getCurrentWifiSpeeds("widget") }
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
            views.setTextViewText(R.id.widget_updated, "Updated ${timeNow()} · periodic refresh")
            attachOpenAppAction(views, id)
            AppWidgetManager.getInstance(applicationContext).updateAppWidget(id, views)
        }
    }

    private fun currentWidgetIds(kind: String, period: UsagePeriod?): IntArray {
        val receiverClass = when (kind) {
            KIND_SPEED -> LiveSpeedWidgetReceiver::class.java
            KIND_USAGE -> when (period) {
                UsagePeriod.TODAY -> DailyUsageWidgetReceiver::class.java
                UsagePeriod.WEEK -> WeeklyUsageWidgetReceiver::class.java
                UsagePeriod.MONTH -> MonthlyUsageWidgetReceiver::class.java
                null -> return intArrayOf()
            }
            else -> return intArrayOf()
        }
        return AppWidgetManager.getInstance(applicationContext)
            .getAppWidgetIds(ComponentName(applicationContext, receiverClass))
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
        private const val SPEED_SAMPLE_INTERVAL_MS = 1_000L
    }
}
