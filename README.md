# Wifi Tracker

**Real-time & historical WiFi usage monitor for Android**

Track how much WiFi data you use, which apps consume the most, and get quick insights with home-screen widgets.

## Features

- **Real-time monitoring**
  - Live download & upload speeds
  - Current WiFi network name
  - Signal strength

- **Historical usage**
  - Today / This Week / This Month totals
  - Per-app breakdown (which apps use the most WiFi)

- **Widgets**
  - Daily usage widget
  - Weekly usage widget
  - Monthly usage widget
  - Live speed widget

- **Modern UI**
  - Dark mode by default
  - Clean Material 3 design
  - minSdk 24 (Android 7.0+)

## Permissions

The app needs **Usage Access** permission so it can read past network statistics and per-app data. You will be prompted to grant it on first launch.

## Building the App

### Requirements
- Android Studio Ladybug or newer (or command-line tools)
- JDK 17
- Android SDK 35

### Build APK locally

```bash
./gradlew assembleDebug
```

The APK will be at:
`app/build/outputs/apk/debug/app-debug.apk`

### GitHub Actions

Every push to `main` automatically builds both Debug and Release APKs.  
You can download them from the **Actions** tab → latest workflow run → Artifacts.

## Project Structure

```
app/
├── src/main/java/com/wifitracker/app/
│   ├── MainActivity.kt
│   ├── data/WifiUsageRepository.kt
│   ├── ui/
│   └── widget/
├── src/main/res/
│   ├── drawable/          # App icon (vector)
│   ├── layout/            # Widget layouts
│   └── xml/               # Widget providers
└── build.gradle.kts
```

## Notes for further development

- The current `WifiUsageRepository` contains placeholder data so the UI is fully functional.
- Real `NetworkStatsManager` queries (with proper UID resolution and time ranges) should replace the placeholders.
- Per-SSID tracking requires listening to connectivity changes and attributing TrafficStats deltas to the current network.
- Widgets currently show placeholder values; they can be updated via `AppWidgetManager` or WorkManager.

## License

MIT
