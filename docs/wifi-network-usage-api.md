# Local Wi-Fi usage API

The tracker keeps aggregate device Wi-Fi interface RX/TX byte deltas in the app's private SQLite database. Collection begins only after the user explicitly starts the tracker in the foreground; no historical usage is backfilled. Sampling is attempted about every 15 seconds, so totals are approximate and may omit intervals when the process/device sleeps, Wi-Fi changes, SSID access is unavailable, counters reset, or the service is stopped. An interval spanning local midnight is assigned to the date observed at its ending sample. The database and tracker-state preference are excluded from Android cloud backup and device transfer. No raw HTTP server is exposed.

## Consumer access and signing

A consumer app must declare the read permission in its manifest:

```xml
<uses-permission android:name="com.wifitracker.app.permission.READ_WIFI_USAGE" />
```

The provider is exported but read-only and guarded by the custom `signature` permission `com.wifitracker.app.permission.READ_WIFI_USAGE`. The consumer must be signed with the same signing certificate/key as WiFi Tracker (for development, both builds must use the same debug key). Apps with a different signing key cannot query the provider. Do not request write access; all insert/update/delete calls are rejected.

## URI and columns

Authority: `com.wifitracker.app.networkusage`

- `content://com.wifitracker.app.networkusage/networks` — one row per SSID, aggregated across all stored dates.
- `content://com.wifitracker.app.networkusage/daily` — one row per SSID and local calendar day, newest day first.

Both endpoints return numeric byte counts as SQLite INTEGER/`Long`. Public Kotlin constants are in `WifiNetworkUsageStore.Companion`: `AUTHORITY`, `CONTENT_URI`, `DAILY_CONTENT_URI`, `PATH_NETWORKS`, `PATH_DAILY`, `COLUMN_SSID`, `COLUMN_DAY`, `COLUMN_RX_BYTES`, `COLUMN_TX_BYTES`, `COLUMN_TOTAL_BYTES`, and `READ_PERMISSION`.

| Column | Type | Meaning |
| --- | --- | --- |
| `ssid` | TEXT | Normalized readable Wi-Fi SSID; masked/unknown SSIDs are not recorded. |
| `day` | TEXT (`yyyy-MM-dd`) | Device-local calendar date; present only on `/daily`. |
| `rx_bytes` | INTEGER | Sampled receive-byte delta. |
| `tx_bytes` | INTEGER | Sampled transmit-byte delta. |
| `total_bytes` | INTEGER | `rx_bytes + tx_bytes`. |

The provider supports only allowlisted projections and fixed ordering; SQL selection and caller-provided sorting are deliberately rejected. `/daily` accepts optional bound URI query parameters `ssid`, `fromDay` (inclusive), and `throughDay` (inclusive), e.g.:

```kotlin
val uri = Uri.parse("content://com.wifitracker.app.networkusage/daily")
    .buildUpon()
    .appendQueryParameter("ssid", "Office Wi-Fi")
    .appendQueryParameter("fromDay", "2026-01-01")
    .build()
val cursor = contentResolver.query(uri, null, null, null, null)
```

## In-app UI integration

Use `WifiNetworkUsageStore(context).getTotals()` for `List<WifiNetworkUsageTotal>` (`ssid`, `rxBytes`, `txBytes`, `totalBytes`) and `.isTracking()` for the persisted requested-tracking state. `getTotals()` performs synchronous SQLite I/O and should be called off the main thread.

After the UI has obtained the required SSID/location runtime permission and the user explicitly chooses Start while the app is foregrounded, call `WifiNetworkUsageService.startTracking(context)`. To stop from the UI call `WifiNetworkUsageService.stopTracking(context)`. The public explicit service action constants are `ACTION_START` and `ACTION_STOP`; the service component is non-exported. A visible ongoing notification with a Stop action remains while tracking. A true `isTracking()` value means tracking was requested and may be resumed after an OS process restart; it is not a guarantee that an SSID is currently readable or that every sampling interval was recorded.
