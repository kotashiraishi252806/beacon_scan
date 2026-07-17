# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Debug build (requires JAVA_HOME)
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug

# Release build
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleRelease

# Run tests
JAVA_HOME=/opt/android-studio/jbr ./gradlew test
```

Java is located at `/opt/android-studio/jbr`. Always prefix gradle commands with `JAVA_HOME=/opt/android-studio/jbr`.

## Architecture

Single-Activity Android app (Kotlin, minSdk 30, targetSdk 36). No fragments, no ViewModel, no DI framework.

### Key Files

- **`MainActivity.kt`** — All logic lives here. Contains `ScanStore` (persistence), `AccessPoint` (data model), `ApAdapter` (RecyclerView), and `MainActivity` itself.
- **`SupplicantScanActivity.kt`** — Legacy standalone Supplicant measurement screen. No longer launched from MainActivity (superseded by integrated measurement). Still registered in AndroidManifest.
- **`activity_main.xml`** — ConstraintLayout. All scan/send UI on a single screen.

### Data Flow

```
WifiManager.startScan()
    → SCAN_RESULTS_AVAILABLE_ACTION broadcast
    → onScanResultsReady()
    → measureAllSupplicant()        ← sequential per-AP connection attempts
    → saveToPending()               ← appends to pending_scans.json
    → trySendAllPending()           ← HTTP POST to configurable server URL
```

### Persistence

`ScanStore` (singleton object in MainActivity.kt) reads/writes `pending_scans.json` in `context.filesDir`. No database. JSON is a top-level array of scan entries.

### Supplicant Measurement

After each WiFi scan, the app sequentially attempts to connect to each detected AP using `WifiNetworkSpecifier` + `ConnectivityManager.requestNetwork()`. This shows an Android system dialog per AP asking the user to confirm the connection. Key constraints:

- `SUPPLICANT_STATE_CHANGED_ACTION` BroadcastReceiver captures state transitions
- Phase 1: AP pre-checked against `wifiManager.scanResults`; if absent → `NOT_FOUND`
- Phase 2: Waits indefinitely (no timeout) for user to press OK on the system dialog; first Supplicant state received = OK was pressed
- Phase 3: 8-second auth timeout (`AUTH_TIMEOUT_MS`) after OK
- `supplicantContinuation` (volatile field) connects the BroadcastReceiver to the suspended coroutine
- **Critical**: `requestNetwork()` must be called INSIDE `suspendCancellableCoroutine` block, after setting `supplicantContinuation`, to avoid race condition where `onUnavailable()` fires before the continuation is registered
- EAP/Enterprise and WEP APs are skipped (`SKIPPED`)
- `isProcessingResults` flag prevents re-entry into `onScanResultsReady()` when `requestNetwork()` triggers internal system scans

### Security Classification (`getSecurity()`)

Parses `ScanResult.capabilities` raw string. Priority order matters — `WPA2+SAE` → `WPA2/WPA3`, then SAE/OWE/EAP_SUITE_B → `WPA3`, then WPA2, WPA, WEP, Open. See `security_classification_notes.md` for the full decision rationale and edge cases.

### Sent JSON Schema

Each scan entry sent to the server:
```json
{
  "scan_id": "uuid",
  "device_id": "uuid (persisted in SharedPreferences)",
  "device": { "manufacturer", "model", "android_api" },
  "scanned_at": "ISO8601",
  "label": "session label or empty string",
  "location": { "latitude", "longitude", "accuracy_m" } or null,
  "access_points": [{
    "bssid", "mld_mac_address", "oui", "ssids", "rssi_dbm",
    "frequency_mhz", "band", "channel_width_mhz",
    "wifi_standard", "wifi_standard_code", "security", "capabilities_raw",
    "supplicant_states", "supplicant_final_state", "supplicant_elapsed_ms"
  }]
}
```

`supplicant_final_state` values: `COMPLETED`, `FAILED_AT_<STATE>`, `TIMEOUT_AT_<STATE>`, `NOT_FOUND`, `SKIPPED`, `CANCELLED`.

### Auto-scan Session

When auto-scan switch is ON, scans run every 5 seconds (`AUTO_SCAN_INTERVAL_MS`). All scans in one ON→OFF cycle share the same `label` (UUID internally, replaced with time-range string on switch OFF). Auto-scan disables the send toggle and manual scan button.

### Permissions Required

`ACCESS_FINE_LOCATION`, `NEARBY_WIFI_DEVICES` (API 31+), `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `INTERNET`
