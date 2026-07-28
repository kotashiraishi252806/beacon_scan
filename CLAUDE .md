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

After each WiFi scan, the app sequentially attempts to connect to each detected AP using `WifiNetworkSpecifier` + `ConnectivityManager.requestNetwork()`. Key constraints:

- **ダイアログ表示はAndroid OS側が制御する。必ずしも表示されるとは限らない。** アプリはrequestNetwork()を呼ぶだけであり、その後のUI表示・接続処理はOS任せ。
- `SUPPLICANT_STATE_CHANGED_ACTION` BroadcastReceiver captures state transitions
- Phase 1 (`DIALOG_TIMEOUT_MS` = 15s): **「OSが動き出したか」の確認。** BroadcastReceiverにSupplicantStateが届くかonUnavailable()が呼ばれれば完了。15秒どちらも来なければ → `FAILED_AT_DIALOG_TIMEOUT`
- Phase 2 (`AUTH_TIMEOUT_MS` = 8s): **「接続試行の結果」の確認。** COMPLETED / DISCONNECTED等の終端状態で完了。8秒来なければ → `TIMEOUT_AT_<STATE>`
- `supplicantContinuation` (volatile field) connects the BroadcastReceiver to the suspended coroutine
- **Critical**: `requestNetwork()` must be called INSIDE `suspendCancellableCoroutine` block, after setting `supplicantContinuation`, to avoid race condition where `onUnavailable()` fires before the continuation is registered
- WEP APs are skipped (`SKIPPED`). EAP APs are attempted with dummy credentials.
- `isProcessingResults` flag prevents re-entry into `onScanResultsReady()` when `requestNetwork()` triggers internal system scans
- 詳細な設計考察・実測データ分析は `md/supplicant測定設計考察.md` を参照

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

**現在、自動スキャンスイッチはUI上で非活性化（`isEnabled = false`）されており、ユーザーはONにできない。**
コード・ロジックはすべて保持されているため、`onCreate()` 内の `switchAutoScan.isEnabled = false` を削除すれば即座に復活可能。

When auto-scan switch is ON, scans run every 5 seconds (`AUTO_SCAN_INTERVAL_MS`). All scans in one ON→OFF cycle share the same `label` (UUID internally, replaced with time-range string on switch OFF). Auto-scan disables the send toggle and manual scan button.

### Permissions Required

`ACCESS_FINE_LOCATION`, `NEARBY_WIFI_DEVICES` (API 31+), `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `INTERNET`
