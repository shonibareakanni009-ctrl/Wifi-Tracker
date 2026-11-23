package com.wifitracker.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wifitracker.app.data.AppUsage
import com.wifitracker.app.data.UsagePeriod
import com.wifitracker.app.data.WifiSpeedSample
import com.wifitracker.app.data.WifiUsageRepository
import com.wifitracker.app.data.WifiUsageReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiTrackerApp(
    hasUsageAccess: Boolean,
    onRequestUsageAccess: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { WifiUsageRepository(context) }

    var selectedPeriod by remember { mutableStateOf(UsagePeriod.TODAY) }
    var speedSample by remember {
        mutableStateOf(
            WifiSpeedSample(
                0L,
                0L,
                isAvailable = false,
                note = "Sampling Wi-Fi throughput; rates will appear after another reading."
            )
        )
    }
    var currentSsid by remember { mutableStateOf("Not connected") }
    var signalStrength by remember { mutableStateOf(0) }
    var usageReport by remember { mutableStateOf<WifiUsageReport?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    suspend fun refreshUsage(period: UsagePeriod) {
        isRefreshing = true
        try {
            usageReport = withContext(Dispatchers.IO) {
                repository.getWifiUsageReport(period)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            usageReport = WifiUsageReport(
                totalBytes = 0L,
                rows = emptyList(),
                isAvailable = false,
                note = "Could not load Wi-Fi usage. Please refresh and try again."
            )
        } finally {
            isRefreshing = false
        }
    }

    // Real-time speed + SSID polling
    LaunchedEffect(Unit) {
        while (true) {
            try {
                speedSample = withContext(Dispatchers.IO) {
                    repository.getCurrentWifiSpeeds("screen")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                speedSample = WifiSpeedSample(
                    0L,
                    0L,
                    isAvailable = false,
                    note = "Could not read Wi-Fi interface counters."
                )
            }
            currentSsid = repository.getCurrentSsid()
            signalStrength = repository.getSignalStrength()
            delay(1000)
        }
    }

    // Load historical + per-app when period or permission changes
    LaunchedEffect(selectedPeriod, hasUsageAccess) {
        if (hasUsageAccess) {
            refreshUsage(selectedPeriod)
        } else {
            usageReport = null
            isRefreshing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wifi Tracker", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(enabled = !isRefreshing, onClick = {
                        if (hasUsageAccess) {
                            coroutineScope.launch { refreshUsage(selectedPeriod) }
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        if (!hasUsageAccess) {
            PermissionScreen(
                modifier = Modifier.padding(padding),
                onRequest = onRequestUsageAccess
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    LiveSpeedCard(
                        sample = speedSample,
                        ssid = currentSsid,
                        signal = signalStrength
                    )
                }

                item {
                    PeriodSelector(
                        selected = selectedPeriod,
                        onSelect = { selectedPeriod = it }
                    )
                }

                item {
                    TotalUsageCard(
                        period = selectedPeriod,
                        report = usageReport,
                        isLoading = isRefreshing
                    )
                }

                item {
                    Text(
                        text = "All Apps and System Usage (Wi-Fi only)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                val displayedReport = usageReport
                if (displayedReport == null || !displayedReport.isAvailable) {
                    item {
                        Text(
                            text = if (isRefreshing) "Loading Wi-Fi usage…" else
                                displayedReport?.note ?: "Wi-Fi usage is not available yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                } else if (displayedReport.rows.isEmpty()) {
                    item {
                        Text(
                            text = displayedReport.note ?: "Android reported no Wi-Fi usage for this period.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                } else {
                    val breakdownBytes = displayedReport.rows.fold(0L) { sum, row ->
                        if (row.bytes > Long.MAX_VALUE - sum) Long.MAX_VALUE else sum + row.bytes
                    }
                    if (displayedReport.note != null) {
                        item {
                            Text(
                                text = displayedReport.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    item {
                        Text(
                            text = "Breakdown total: ${formatBytes(breakdownBytes)} across ${displayedReport.rows.size} groups",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    itemsIndexed(displayedReport.rows, key = { index, app -> "${app.packageName}-$index" }) { _, app ->
                        AppUsageRow(appName = app.appName, bytes = app.bytes)
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
fun PermissionScreen(
    modifier: Modifier = Modifier,
    onRequest: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Usage Access Needed",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "To show past WiFi usage and which apps use the most data, please grant Usage Access permission.\n\nThis is required by Android for any app that reads network statistics.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onRequest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Grant Permission")
        }
    }
}

@Composable
fun LiveSpeedCard(
    sample: WifiSpeedSample,
    ssid: String,
    signal: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = ssid,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Signal: ${"●".repeat(signal)}${"○".repeat(4 - signal)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SpeedItem(label = "Download", speed = sample.downloadBytesPerSecond, isAvailable = sample.isAvailable, icon = Icons.Default.Download)
                SpeedItem(label = "Upload", speed = sample.uploadBytesPerSecond, isAvailable = sample.isAvailable, icon = Icons.Default.Upload)
            }
            if (!sample.isAvailable && sample.note != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = sample.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun SpeedItem(
    label: String,
    speed: Long,
    isAvailable: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (isAvailable) formatSpeed(speed) else "—",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
        )
    }
}

@Composable
fun PeriodSelector(
    selected: UsagePeriod,
    onSelect: (UsagePeriod) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            UsagePeriod.TODAY to "Today",
            UsagePeriod.WEEK to "Week",
            UsagePeriod.MONTH to "Month"
        ).forEach { (period, label) ->
            FilterChip(
                selected = selected == period,
                onClick = { onSelect(period) },
                label = { Text(label) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun TotalUsageCard(
    period: UsagePeriod,
    report: WifiUsageReport?,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = when (period) {
                    UsagePeriod.TODAY -> "Today's Wi-Fi Usage"
                    UsagePeriod.WEEK -> "This Week's Wi-Fi Usage"
                    UsagePeriod.MONTH -> "This Month's Wi-Fi Usage"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            } else if (report == null || !report.isAvailable) {
                Text(
                    text = "Unavailable",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = formatBytes(report.totalBytes),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun AppUsageRow(
    appName: String,
    bytes: Long
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Apps,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = appName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = formatBytes(bytes),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}

fun formatSpeed(bytesPerSecond: Long): String {
    return when {
        bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
        bytesPerSecond < 1024 * 1024 -> "%.1f KB/s".format(bytesPerSecond / 1024.0)
        else -> "%.1f MB/s".format(bytesPerSecond / (1024.0 * 1024))
    }
}
