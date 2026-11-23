# Wifi Tracker

**Real-time & historical WiFi usage monitor for Android**

Track how much WiFi data you use, which apps consume the most, see live speeds, and use home-screen widgets.

## Features

### Real-time
- Live download & upload speeds (updates every second)
- Current WiFi name (SSID)
- Signal strength indicator

### Historical
- Today / This Week / This Month totals
- Per-app breakdown (which apps used the most WiFi data)

### Widgets
- Daily usage widget
- Weekly usage widget
- Monthly usage widget
- Live speed widget

### Other
- Dark mode by default
- Clean Material 3 UI
- minSdk 24 (Android 7.0+)
- Uses official `NetworkStatsManager` + `TrafficStats`

## Permissions

The app needs **Usage Access** permission so it can read past network statistics and per-app data.  
You will be prompted on first launch.

## Build

### GitHub Actions
Push to `main` or run the workflow manually.  
Download the APK from **Actions → Artifacts** (`wifi-tracker-apk`).

### Local
```bash
./gradlew assembleDebug
```

## How it works

- **Live speeds** → `TrafficStats` delta every second
- **Historical totals + per-app** → `NetworkStatsManager` (requires Usage Access)
- **SSID & signal** → `WifiManager`

## Notes

- Per-SSID historical tracking is limited by Android. The app shows the current network with the selected period total as a practical view.
- Widgets update when the system requests an update (or when you open the app).

## License

MIT
