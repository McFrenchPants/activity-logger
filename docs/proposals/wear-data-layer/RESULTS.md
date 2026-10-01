# Results — Wear Data Layer round trip (WD1.2)

Run 2026-10-01 on the Pixel 10 Pro and OnePlus Watch 3 (OPWE242), both over Wi-Fi ADB.
Debug `app-wear` (with the debug-only echo service) on the watch; debug `app-phone` plus its
test APK on the phone, installed with `adb install -r` and run with `adb shell am instrument`
(not Gradle's connected-test task, which can uninstall the app and wipe phone data).

```
WearDataLayerProbe: RESULT watchConnected=true
WearDataLayerProbe: RESULT messagePingPong=RECEIVED roundTripMs=1168
WearDataLayerProbe: RESULT dataItemEcho=RECEIVED roundTripMs=210
```

Conclusions: pairing is visible to the app, the shared `applicationId` + signing key work,
and both MessageClient and DataClient round-trip. ADR-025 unaffected (no INTERNET added;
the Data Layer goes through Play Services). The first message took ~1.2 s (cold path);
treat latency as one sample, not a measurement.

Not covered: release-signed builds, battery/doze behaviour, phone app not running.
