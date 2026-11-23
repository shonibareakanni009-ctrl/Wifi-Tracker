package com.wifitracker.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wifitracker.app.data.InternetCheckResult
import com.wifitracker.app.data.NetworkToolsRepository
import com.wifitracker.app.data.UsagePeriod
import com.wifitracker.app.data.WifiNetworkDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.UnknownHostException

@Composable
fun SettingsAndToolsScreen(
    defaultPeriod: UsagePeriod,
    onDefaultPeriodChanged: (UsagePeriod) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { NetworkToolsRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var wifiDetails by remember { mutableStateOf(WifiNetworkDetails()) }
    var isRefreshingWifi by remember { mutableStateOf(true) }
    var internetResult by remember { mutableStateOf<InternetCheckResult?>(null) }
    var isCheckingInternet by remember { mutableStateOf(false) }
    var hostname by remember { mutableStateOf("example.com") }
    var dnsResult by remember { mutableStateOf<String?>(null) }
    var isLookingUpDns by remember { mutableStateOf(false) }

    suspend fun refreshWifiDetails() {
        isRefreshingWifi = true
        try {
            wifiDetails = withContext(Dispatchers.IO) {
                repository.getWifiNetworkDetails()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            wifiDetails = WifiNetworkDetails()
        } finally {
            isRefreshingWifi = false
        }
    }

    LaunchedEffect(Unit) {
        refreshWifiDetails()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Default usage period",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Choose which period WiFi Tracker selects when it opens.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                            selected = defaultPeriod == period,
                            onClick = { onDefaultPeriodChanged(period) },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        Text(
            text = "Network tools",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Wi-Fi connection",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (isRefreshingWifi) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    DetailLine("Status", if (wifiDetails.isConnected) "Connected" else "Not connected")
                    if (wifiDetails.isConnected) {
                        DetailLine("Network", wifiDetails.ssid)
                        DetailLine("Local IP", wifiDetails.localAddress)
                        DetailLine("Gateway", wifiDetails.gatewayAddress)
                        DetailLine(
                            "Signal",
                            wifiDetails.signalDbm?.let { "$it dBm" } ?: "Unavailable"
                        )
                        DetailLine(
                            "Link speed",
                            wifiDetails.linkSpeedMbps?.let { "$it Mbps" } ?: "Unavailable"
                        )
                        DetailLine(
                            "DNS servers",
                            wifiDetails.dnsServers.takeIf { it.isNotEmpty() }
                                ?.joinToString(", ") ?: "Unavailable"
                        )
                    }
                }
                OutlinedButton(
                    onClick = { scope.launch { refreshWifiDetails() } },
                    enabled = !isRefreshingWifi,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.material3.Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("Refresh connection details", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Default.Router,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Wi-Fi internet reachability",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "A quick Wi-Fi round-trip check, not a full download speed test. It makes a brief request to Google's connectivity-check service.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                internetResult?.let { result ->
                    Text(
                        text = buildString {
                            append(result.detail)
                            result.latencyMillis?.let { append(" · ${it} ms") }
                        },
                        color = if (result.isReachable) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Button(
                    onClick = {
                        scope.launch {
                            isCheckingInternet = true
                            internetResult = null
                            try {
                                internetResult = withContext(Dispatchers.IO) {
                                    repository.testInternetConnectivity()
                                }
                            } finally {
                                isCheckingInternet = false
                            }
                        }
                    },
                    enabled = !isCheckingInternet,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isCheckingInternet) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Check Wi-Fi internet")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "DNS lookup",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "Resolve a hostname using your device's configured DNS, including Private DNS when enabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = hostname,
                    onValueChange = {
                        hostname = it
                        dnsResult = null
                    },
                    label = { Text("Hostname") },
                    placeholder = { Text("example.com") },
                    singleLine = true,
                    enabled = !isLookingUpDns,
                    modifier = Modifier.fillMaxWidth()
                )
                dnsResult?.let { result ->
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (result.startsWith("Resolved:")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
                Button(
                    onClick = {
                        scope.launch {
                            isLookingUpDns = true
                            dnsResult = null
                            val requestedHost = hostname.trim()
                            try {
                                val addresses = withContext(Dispatchers.IO) {
                                    repository.lookupDns(requestedHost)
                                }
                                dnsResult = if (addresses.isNotEmpty()) {
                                    "Resolved: ${addresses.joinToString(", ")}"
                                } else {
                                    "No addresses were returned for $requestedHost."
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (unknownHost: UnknownHostException) {
                                dnsResult = "No DNS result for $requestedHost. Check the name or connection."
                            } catch (error: IllegalArgumentException) {
                                dnsResult = error.message ?: "Enter a valid hostname."
                            } catch (_: Exception) {
                                dnsResult = "DNS lookup failed. Check your connection and try again."
                            } finally {
                                isLookingUpDns = false
                            }
                        }
                    },
                    enabled = !isLookingUpDns,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLookingUpDns) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Look up hostname")
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.36f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier
                .weight(0.64f)
                .padding(start = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End
        )
    }
}
