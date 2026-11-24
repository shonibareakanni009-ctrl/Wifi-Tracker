package com.wifitracker.app

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.wifitracker.app.ui.WifiTrackerApp
import com.wifitracker.app.ui.theme.WifiTrackerTheme

class MainActivity : ComponentActivity() {
    private val usageAccessGranted = mutableStateOf(false)
    private val wifiNamePermissionGranted = mutableStateOf(false)
    private val notificationPermissionGranted = mutableStateOf(false)
    private val isAppInForeground = mutableStateOf(false)

    private val wifiNamePermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        wifiNamePermissionGranted.value = hasWifiNamePermission()
    }

    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notificationPermissionGranted.value = hasNotificationPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        usageAccessGranted.value = hasUsageAccessPermission()
        wifiNamePermissionGranted.value = hasWifiNamePermission()
        notificationPermissionGranted.value = hasNotificationPermission()
        setContent {
            WifiTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WifiTrackerApp(
                        hasUsageAccess = usageAccessGranted.value,
                        onRequestUsageAccess = { openUsageAccessSettings() },
                        hasWifiNamePermission = wifiNamePermissionGranted.value,
                        onRequestWifiNamePermission = { requestWifiNamePermission() },
                        hasNotificationPermission = notificationPermissionGranted.value,
                        onRequestNotificationPermission = { requestNotificationPermission() },
                        isAppInForeground = isAppInForeground.value
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        usageAccessGranted.value = hasUsageAccessPermission()
        wifiNamePermissionGranted.value = hasWifiNamePermission()
        notificationPermissionGranted.value = hasNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        isAppInForeground.value = true
    }

    override fun onStop() {
        isAppInForeground.value = false
        super.onStop()
    }

    private fun hasUsageAccessPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun openUsageAccessSettings() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun hasWifiNamePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestWifiNamePermission() {
        wifiNamePermissionRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasNotificationPermission(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationPermissionGranted.value = true
        }
    }
}
