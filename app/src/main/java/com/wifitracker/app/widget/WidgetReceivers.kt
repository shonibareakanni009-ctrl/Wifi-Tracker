package com.wifitracker.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.wifitracker.app.R
import com.wifitracker.app.data.UsagePeriod
import java.util.concurrent.Executor

class DailyUsageWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        enqueueUpdate(context, appWidgetIds, WidgetRefreshWorker.KIND_USAGE, UsagePeriod.TODAY, goAsync())
    }
}

class WeeklyUsageWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        enqueueUpdate(context, appWidgetIds, WidgetRefreshWorker.KIND_USAGE, UsagePeriod.WEEK, goAsync())
    }
}

class MonthlyUsageWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        enqueueUpdate(context, appWidgetIds, WidgetRefreshWorker.KIND_USAGE, UsagePeriod.MONTH, goAsync())
    }
}

class LiveSpeedWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        enqueueUpdate(context, appWidgetIds, WidgetRefreshWorker.KIND_SPEED, null, goAsync())
    }
}

/** Only does quick scheduling in the broadcast; NetworkStatsManager work runs in WorkManager. */
private fun enqueueUpdate(
    context: Context,
    widgetIds: IntArray,
    kind: String,
    period: UsagePeriod?,
    pendingResult: BroadcastReceiver.PendingResult? = null
) {
    if (widgetIds.isEmpty()) {
        pendingResult?.finish()
        return
    }
    val requestData = Data.Builder()
        .putString(WidgetRefreshWorker.KEY_KIND, kind)
        .putIntArray(WidgetRefreshWorker.KEY_WIDGET_IDS, widgetIds)
        .apply { period?.let { putString(WidgetRefreshWorker.KEY_PERIOD, it.name) } }
        .build()
    val work = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
        .setInputData(requestData)
        .build()
    try {
        val operation = WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("wifi-widget-$kind-${period?.name ?: "speed"}", ExistingWorkPolicy.REPLACE, work)
        operation.result.addListener({ pendingResult?.finish() }, Executor { command -> command.run() })
    } catch (_: Exception) {
        pendingResult?.finish()
    }
}

/** Refresh every installed widget when the user opens the app or returns from Android Settings. */
object WifiTrackerWidgetUpdater {
    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val providers: List<Triple<Class<out AppWidgetProvider>, String, UsagePeriod?>> = listOf(
            Triple(DailyUsageWidgetReceiver::class.java, WidgetRefreshWorker.KIND_USAGE, UsagePeriod.TODAY),
            Triple(WeeklyUsageWidgetReceiver::class.java, WidgetRefreshWorker.KIND_USAGE, UsagePeriod.WEEK),
            Triple(MonthlyUsageWidgetReceiver::class.java, WidgetRefreshWorker.KIND_USAGE, UsagePeriod.MONTH),
            Triple(LiveSpeedWidgetReceiver::class.java, WidgetRefreshWorker.KIND_SPEED, null)
        )
        providers.forEach { (receiverClass, kind, period) ->
            val ids = manager.getAppWidgetIds(ComponentName(context, receiverClass))
            enqueueUpdate(context, ids, kind, period)
        }
    }
}
