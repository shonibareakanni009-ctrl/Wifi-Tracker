# Google Play package-visibility readiness

**Reviewed:** 2026-11-25

WiFi Tracker currently keeps `QUERY_ALL_PACKAGES` because its app-usage feature maps Android per-UID network-stat rows to installed application labels and icons, including packages that are not known in advance. Removing the permission would leave more rows unidentified and weaken the full per-app usage view. The current APK workflow is for the signed sideload release.

Google Play treats the installed-app inventory as sensitive information. Its policy allows `QUERY_ALL_PACKAGES` only when broad visibility is required by the app's core user-facing functionality. A Play submission must include the permission declaration, explain why narrower visibility is insufficient, and receive Google's approval; approval is not guaranteed. Therefore, do not describe this permission as automatically Play-safe. Before publishing, submit the declaration in Play Console and test any proposed limited-visibility build for correct totals and honest unidentified-app labels.

Android documents `<queries>` as the narrower alternative for known package needs, and says the broad permission is for rare cases where that mechanism is inadequate. Because WiFi Tracker attributes arbitrary usage rows rather than interacting with a fixed known set of apps, replacing the permission with a fixed `<queries>` list would not provide equivalent coverage.

## Official sources

- Google Play: [Use of the broad package visibility permission](https://support.google.com/googleplay/android-developer/answer/10158779?hl=en)
- Android Developers: [Package visibility filtering on Android](https://developer.android.com/training/package-visibility)
