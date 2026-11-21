package com.wifitracker.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.wifitracker.app.R

class DailyUsageWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_usage)
            views.setTextViewText(R.id.widget_title, "Today")
            views.setTextViewText(R.id.widget_value, "— GB")
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

class WeeklyUsageWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_usage)
            views.setTextViewText(R.id.widget_title, "This Week")
            views.setTextViewText(R.id.widget_value, "— GB")
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

class MonthlyUsageWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_usage)
            views.setTextViewText(R.id.widget_title, "This Month")
            views.setTextViewText(R.id.widget_value, "— GB")
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

class LiveSpeedWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_speed)
            views.setTextViewText(R.id.widget_speed, "↓ — MB/s")
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
