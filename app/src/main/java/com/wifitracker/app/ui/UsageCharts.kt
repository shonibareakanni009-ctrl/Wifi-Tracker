package com.wifitracker.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wifitracker.app.data.AppUsage
import com.wifitracker.app.data.DailyWifiUsage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
internal fun DailyWifiUsageChart(
    dailyUsage: List<DailyWifiUsage>,
    selectedDayStart: Long,
    isLoading: Boolean,
    onSelectDay: (Long) -> Unit,
    appPackageKey: String? = null,
    title: String = "This week"
) {
    val chartValues = dailyUsage.map { day ->
        val bytes = if (appPackageKey == null) {
            day.report.totalBytes
        } else {
            day.report.rows.firstOrNull { it.packageName == appPackageKey }?.bytes ?: 0L
        }
        day to bytes
    }
    val maxBytes = chartValues.maxOfOrNull { it.second } ?: 0L
    val todayStart = startOfTodayMillis()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            if (chartValues.isEmpty()) {
                Text(
                    "Daily history is loading…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(154.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    chartValues.forEach { (day, bytes) ->
                        val fraction = if (maxBytes > 0L && bytes > 0L) {
                            (bytes.toDouble() / maxBytes.toDouble()).toFloat().coerceIn(0.035f, 1f)
                        } else 0f
                        val canSelect = day.dayStartMillis <= todayStart
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(enabled = canSelect) { onSelectDay(day.dayStartMillis) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (bytes > 0L) formatBytesCompact(bytes) else " ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                if (fraction > 0f) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.56f)
                                            .fillMaxHeight(fraction)
                                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                            .background(
                                                if (day.dayStartMillis == selectedDayStart)
                                                    MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.secondaryContainer
                                            )
                                    )
                                }
                            }
                            Text(
                                text = SimpleDateFormat("EEE", Locale.getDefault()).format(Date(day.dayStartMillis)),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (day.dayStartMillis == selectedDayStart)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (day.dayStartMillis == selectedDayStart)
                                    FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                val selectedBytes = chartValues.firstOrNull {
                    it.first.dayStartMillis == selectedDayStart
                }?.second ?: 0L
                Text(
                    text = "Selected: ${formatDay(selectedDayStart)} · ${formatBytes(selectedBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun AppUsageDonut(rows: List<AppUsage>, totalBytes: Long) {
    val scheme = MaterialTheme.colorScheme
    val chartColors = listOf(
        scheme.primary,
        scheme.tertiary,
        scheme.secondary,
        scheme.error,
        scheme.primaryContainer,
        scheme.tertiaryContainer,
        scheme.secondaryContainer,
        scheme.inversePrimary
    )
    val positiveRows = rows.filter { it.bytes > 0L }
    val sumBytes = positiveRows.fold(0L) { total, row ->
        if (Long.MAX_VALUE - total < row.bytes) Long.MAX_VALUE else total + row.bytes
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Usage by app", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(204.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = size.minDimension * 0.17f
                    var startAngle = -90f
                    if (sumBytes > 0L) {
                        positiveRows.forEachIndexed { index, row ->
                            val sweep = (row.bytes.toDouble() / sumBytes.toDouble() * 360.0).toFloat()
                            drawArc(
                                color = chartColors[index % chartColors.size],
                                startAngle = startAngle,
                                sweepAngle = sweep,
                                useCenter = false,
                                style = Stroke(width = strokeWidth)
                            )
                            startAngle += sweep
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "SELECTED DAY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(formatBytes(totalBytes), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                "Each segment represents an app or system UID. The list below shows the exact byte totals.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
internal fun AppUsageHistoryScreen(
    app: AppUsage,
    dailyUsage: List<DailyWifiUsage>,
    selectedDayStart: Long,
    onSelectDay: (Long) -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    val selectedDay = dailyUsage.firstOrNull { it.dayStartMillis == selectedDayStart }
    val selectedRow = selectedDay?.report?.rows?.firstOrNull { it.packageName == app.packageName }
    val selectedBytes = selectedRow?.bytes ?: 0L

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AppUsageRow(
            appName = app.appName,
            bytes = selectedBytes,
            iconPackageNames = app.iconPackageNames
        )
        Text(
            text = "Wi-Fi data on ${formatDay(selectedDayStart)}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = formatBytes(selectedBytes),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        DailyWifiUsageChart(
            dailyUsage = dailyUsage,
            selectedDayStart = selectedDayStart,
            isLoading = isLoading,
            onSelectDay = onSelectDay,
            appPackageKey = app.packageName,
            title = "Daily Wi-Fi usage · this week"
        )
        Text(
            "Android reports usage by UID. Shared UIDs remain combined, and values are Wi-Fi data only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

internal fun startOfTodayMillis(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

internal fun formatDay(timeMillis: Long): String =
    SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(timeMillis))

private fun formatBytesCompact(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1fG".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024L -> "%.0fM".format(bytes / (1024.0 * 1024))
    bytes >= 1024L -> "%.0fK".format(bytes / 1024.0)
    bytes > 0L -> "${bytes}B"
    else -> "0"
}
