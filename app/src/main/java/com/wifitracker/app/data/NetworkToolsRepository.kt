package com.wifitracker.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/** Wi-Fi connection details available through Android's normal network APIs. */
data class WifiNetworkDetails(
    val isConnected: Boolean = false,
    val ssid: String = "Not connected",
    val localAddress: String = "Unavailable",
    val gatewayAddress: String = "Unavailable",
    val dnsServers: List<String> = emptyList(),
    val signalDbm: Int? = null,
    val linkSpeedMbps: Int? = null,
    val ssidPermissionRequired: Boolean = false,
    val locationServicesRequired: Boolean = false
)

data class InternetCheckResult(
    val isReachable: Boolean,
    val latencyMillis: Long? = null,
    val detail: String
)

/** Device-local network diagnostics. DNS resolution uses the device's configured resolver. */
class NetworkToolsRepository(private val context: Context) {

    fun getWifiNetworkDetails(): WifiNetworkDetails {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiNetwork = findWifiNetwork(connectivityManager)
        val wifiInfo = try {
            wifiManager.connectionInfo
        } catch (_: Exception) {
            null
        }
        if (wifiNetwork == null) {
            return WifiNetworkDetails(ssid = "Not connected")
        }

        val hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val locationEnabled = isLocationEnabled()
        val ssid = when {
            !hasLocationPermission -> "Permission required"
            !locationEnabled -> "Location Services off"
            else -> wifiInfo?.ssid
                ?.removeSurrounding("\"")
                ?.takeIf { it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true) }
                ?: "Wi-Fi name unavailable"
        }

        val linkProperties = try {
            connectivityManager.getLinkProperties(wifiNetwork)
        } catch (_: Exception) {
            null
        }
        val localAddress = linkProperties?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }
            ?.address?.hostAddress
            ?: linkProperties?.linkAddresses?.firstOrNull()?.address?.hostAddress
            ?: "Unavailable"
        val gatewayAddress = linkProperties?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway != null }
            ?.gateway?.hostAddress
            ?: "Unavailable"
        val dnsServers = linkProperties?.dnsServers
            ?.mapNotNull { it.hostAddress }
            ?.distinct()
            .orEmpty()

        return WifiNetworkDetails(
            isConnected = true,
            ssid = ssid,
            localAddress = localAddress,
            gatewayAddress = gatewayAddress,
            dnsServers = dnsServers,
            signalDbm = wifiInfo?.rssi?.takeIf { it in -100..-1 },
            linkSpeedMbps = wifiInfo?.linkSpeed?.takeIf { it > 0 },
            ssidPermissionRequired = !hasLocationPermission,
            locationServicesRequired = hasLocationPermission && !locationEnabled
        )
    }

    /**
     * Makes a brief HTTPS request to Google's connectivity-check endpoint. A 204 response confirms
     * internet reachability; the measurement is a basic round-trip check, not a bandwidth test.
     */
    fun testInternetConnectivity(): InternetCheckResult {
        val startedAt = SystemClock.elapsedRealtime()
        var connection: HttpURLConnection? = null
        return try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifiNetwork = findWifiNetwork(connectivityManager)
                ?: return InternetCheckResult(
                    isReachable = false,
                    detail = "Connect to Wi-Fi before running this check."
                )
            connection = wifiNetwork.openConnection(
                URL("https://connectivitycheck.gstatic.com/generate_204")
            ) as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.useCaches = false

            val responseCode = connection.responseCode
            val latency = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            if (responseCode == 204) {
                InternetCheckResult(
                    isReachable = true,
                    latencyMillis = latency,
                    detail = "Internet connection verified"
                )
            } else {
                InternetCheckResult(
                    isReachable = false,
                    latencyMillis = latency,
                    detail = "Received HTTP $responseCode; a captive portal or network filter may be active"
                )
            }
        } catch (_: SocketTimeoutException) {
            InternetCheckResult(
                isReachable = false,
                detail = "The check timed out. The network may be offline or slow."
            )
        } catch (_: Exception) {
            InternetCheckResult(
                isReachable = false,
                detail = "Could not reach the connectivity-check endpoint."
            )
        } finally {
            connection?.disconnect()
        }
    }

    /** Resolves a hostname with the system resolver, including any configured Private DNS. */
    fun lookupDns(hostnameInput: String): List<String> {
        val hostname = hostnameInput.trim()
        require(hostname.isNotEmpty()) { "Enter a hostname, such as example.com." }
        require(!hostname.any { it.isWhitespace() } && !hostname.contains('/') && !hostname.contains("://")) {
            "Enter a hostname only, without a URL path or scheme."
        }
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiNetwork = findWifiNetwork(connectivityManager)
        val addresses = wifiNetwork?.getAllByName(hostname)?.toList()
            ?: InetAddress.getAllByName(hostname).toList()
        return addresses
            .mapNotNull(InetAddress::getHostAddress)
            .distinct()
    }

    private fun findWifiNetwork(connectivityManager: ConnectivityManager): Network? {
        return try {
            val active = connectivityManager.activeNetwork
            if (active != null && connectivityManager.getNetworkCapabilities(active)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            ) return active
            connectivityManager.allNetworks.firstOrNull { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isLocationEnabled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                ?.isLocationEnabled == true
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE) !=
                Settings.Secure.LOCATION_MODE_OFF
        }
    } catch (_: Exception) {
        false
    }
}
