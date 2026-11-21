package com.wifitracker.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifitracker.app.data.UsagePeriod
import com.wifitracker.app.data.WifiUsageRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiTrackerApp(
    hasUsageAccess: Boolean,
    onRequestUsageAccess: () -> Unit
) {
    val repository = remember { WifiUsageRepository() }
    var selectedPeriod by remember { mutableStateOf(UsagePeriod.TODAY) }
    var downloadSpeed by remember { mutableStateOf(0L) }
    var uploadSpeed by remember { mutableStateOf(0L) }
    var currentSsid by remember { mutableStateOf("Not connected") }
    var signalStrength by remember { mutableStateOf(0) }
    var totalUsage by remember { mutableStateOf(0L) }
    var perAppUsage by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }

    val scope = rememberCoroutineScope()

    // Real-time speed polling
    LaunchedEffect(Unit) {
        while (true) {
            val speeds = repository.getCurrentSpeeds()
            downloadSpeed = speeds.first
            uploadSpeed = speeds.second
            currentSsid = repository.getCurrentSsid()
            signalStrength = repository.getSignalStrength()
            delay(1000)
        }
    }

    // Load historical data when period changes
    LaunchedEffect(selectedPeriod, hasUsageAccess) {
        if (hasUsageAccess) {
            totalUsage = repository.getTotalWifiUsage(selectedPeriod)
            perAppUsage = repository.getPerAppWifiUsage(selectedPeriod)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wifi Tracker", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
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
                        downloadSpeed = downloadSpeed,
                        uploadSpeed = uploadSpeed,
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
                        bytes = totalUsage
                    )
                }

                item {
                    Text(
                        text = "Top Apps (WiFi)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(perAppUsage.take(12)) { (appName, bytes) ->
                    AppUsageRow(appName = appName, bytes = bytes)
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
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
            text = "To show your past WiFi usage and which apps use the most data, please grant Usage Access permission.",
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
    downloadSpeed: Long,
    uploadSpeed: Long,
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
                Text(
                    text = ssid,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SpeedItem(
                    label = "Download",
                    speed = downloadSpeed,
                    icon = Icons.Default.Download
                )
                SpeedItem(
                    label = "Upload",
                    speed = uploadSpeed,
                    icon = Icons.Default.Upload
                )
            }
        }
    }
}

@Composable
fun SpeedItem(
    label: String,
    speed: Long,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = formatSpeed(speed),
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
    bytes: Long
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
                    UsagePeriod.TODAY -> "Today's WiFi Usage"
                    UsagePeriod.WEEK -> "This Week's WiFi Usage"
                    UsagePeriod.MONTH -> "This Month's WiFi Usage"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = formatBytes(bytes),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
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
