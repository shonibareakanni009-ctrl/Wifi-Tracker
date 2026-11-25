package com.wifitracker.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.wifitracker.app.data.UsagePeriod
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

private const val WIDGET_REFRESH_MINUTES = 15L

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

/** Only schedules work in the broadcast; statistics queries run in WorkManager. */
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

    schedulePeriodicRefresh(context, kind, period)

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
            .enqueueUniqueWork(workName(kind, period), ExistingWorkPolicy.REPLACE, work)
        operation.result.addListener({ pendingResult?.finish() }, Executor { command -> command.run() })
    } catch (_: Exception) {
        pendingResult?.finish()
    }
}

private fun schedulePeriodicRefresh(context: Context, kind: String, period: UsagePeriod?) {
    val requestData = Data.Builder()
        .putString(WidgetRefreshWorker.KEY_KIND, kind)
        .apply { period?.let { putString(WidgetRefreshWorker.KEY_PERIOD, it.name) } }
        .build()
    val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(WIDGET_REFRESH_MINUTES, TimeUnit.MINUTES)
        .setInputData(requestData)
        .build()
    try {
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                periodicWorkName(kind, period),
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    } catch (_: Exception) {
        // The immediate one-time refresh still runs if periodic scheduling is unavailable.
    }
}

private fun workName(kind: String, period: UsagePeriod?): String =
    "wifi-widget-$kind-${period?.name ?: "speed"}"

private fun periodicWorkName(kind: String, period: UsagePeriod?): String =
    "wifi-widget-periodic-$kind-${period?.name ?: "speed"}"

/** Refresh installed widgets on app open and ensure periodic work only exists for installed types. */
object WifiTrackerWidgetUpdater {
    private data class Provider(
        val receiverClass: Class<out AppWidgetProvider>,
        val kind: String,
        val period: UsagePeriod?
    )

    private val providers = listOf(
        Provider(DailyUsageWidgetReceiver::class.java, WidgetRefreshWorker.KIND_USAGE, UsagePeriod.TODAY),
        Provider(WeeklyUsageWidgetReceiver::class.java, WidgetRefreshWorker.KIND_USAGE, UsagePeriod.WEEK),
        Provider(MonthlyUsageWidgetReceiver::class.java, WidgetRefreshWorker.KIND_USAGE, UsagePeriod.MONTH),
        Provider(LiveSpeedWidgetReceiver::class.java, WidgetRefreshWorker.KIND_SPEED, null)
    )

    fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val workManager = WorkManager.getInstance(appContext)
        providers.forEach { provider ->
            val ids = manager.getAppWidgetIds(ComponentName(appContext, provider.receiverClass))
            if (ids.isEmpty()) {
                workManager.cancelUniqueWork(periodicWorkName(provider.kind, provider.period))
            } else {
                enqueueUpdate(appContext, ids, provider.kind, provider.period)
            }
        }
    }
}
