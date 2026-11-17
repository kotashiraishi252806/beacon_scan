# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# 動作ルール

## 作業範囲
- 指示された作業のみ実行する。依頼範囲外のファイル編集、機能追加、リファクタリング、コマンド実行は行わない。
- 範囲外の作業が必要だと判断した場合は、実行せず1行で提案し、承認を待つ。
- 仕様が不明な点は推測で進めず、先に質問する。

## 語彙
- 「適切」「綺麗」「最適」「シンプル」「堅牢」「良い」など基準が定義されていない形容語を使わない。
- 評価を述べる場合は、行数・実行時間・依存数・テスト結果などの測定可能な値で示す。

## 同意と評価
- コードについて述べる各文の末尾に根拠を付ける: [確認済: ファイル名:行番号] または [推測]
- ユーザーの指示や案に誤り・リスク・代替案より劣る点がある場合は、同意より先にそれを述べる。
- 根拠のない同意・称賛・謝罪をしない。

## 回答の長さ
- 質問への答えのみ書く。前置き、要約、次の提案は書かない。
- 質問への回答は、最初に結論を3文以内で書いて停止する。詳細はユーザーが「次」と言った場合のみ書く。
- 説明するのは質問された対象のみ。その対象が呼び出す、または利用している別の関数・ライブラリ・機能・データは、回答の末尾に「関連: 名前1、名前2」の形式で名前だけを書き、説明しない。

## 記録の参照
- docs/glossary.md にない語を使う場合は、使う前に同ファイルへ定義を追記する。ファイルがなければ作成する。
- 機能について質問されたら、下の機能索引で該当行を特定し、コードより先に設計記録を読む。
- 機能索引にない機能について質問されたら、「設計記録なし」と最初に述べてからコードを読んで回答する。

## 開発ルール
- 実装前に、指示の解釈を docs/specs/<機能名>.md に書き、承認を待つ。指示にない内容を補った箇所には「AI補完」と付ける。
- 実装後、docs/design/<機能名>.md を次の7項目で作成または更新する。
  1. 目的
  2. 入力
  3. 処理手順（番号付き）
  4. 出力
  5. 使用技術（ライブラリ名・API名・アルゴリズム名）
  6. 該当ファイル
  7. 判断理由
- 機能を追加・変更・削除したら、同じ作業の中で機能索引の該当行を更新する。

## 機能索引
| 機能名 | 設計記録 | 主なコードファイル |
|---|---|---|
| Supplicant測定 | md/supplicant測定設計考察.md | SupplicantMeasurer.kt |
| セキュリティ分類 | md/セキュリティ、無線LAN規格/security_classification_notes.md | MainActivity.kt |
| BSSIDごとの接続試行開始時刻記録 (`connection_started_at`) | docs/design/connection_started_at記録.md | SupplicantMeasurer.kt, ScanData.kt, ConnectionMeasurementActivity.kt |

## 研究目的とアプリの位置づけ

### 研究目的
近年、観光や災害の拡大に伴い、屋内外問わず通信手段として無線LANの整備が進んでいる。無線LANは電波を用いているため、有線LANに比べると利用環境や利用状況を把握することが難しい。そこで様々な無線LAN環境を把握するシステムが開発・実用化されている。しかし、無線LAN環境の測定に関する既存研究の多くは屋内を対象としており、屋外環境への適用を想定していないツールも存在する。そこで、我々は屋外も含めて無線LANの利用環境を簡便に把握可能なシステムの開発を行っている。

### このアプリの役割（手段）
測定端末には多くの一般ユーザが日常的に持ち歩いているスマートフォンを採用している。スマートフォンで確認できた数値そのものがユーザの体感品質につながるだけでなく、大規模測定となった際のアプリケーションとして流通させる将来性も加味している。できるだけ多くの情報を収集する。

### 重要な設計方針
- **屋外での利用を前提とする**。屋内専用の設計・実装は避ける。

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
- **`SupplicantMeasurer.kt`** — Supplicant measurement class. Handles sequential per-AP connection attempts, state recording, and timeout management. Extracted from MainActivity.
- **`activity_main.xml`** — ConstraintLayout. All scan/send UI on a single screen.

### Data Flow

```
WifiManager.startScan()
    → SCAN_RESULTS_AVAILABLE_ACTION broadcast
    → onScanResultsReady()
    → measurer.measureAll()          ← sequential per-AP connection attempts (SupplicantMeasurer)
    → saveToPending()               ← appends to pending_scans.json
    → trySendAllPending()           ← HTTP POST to configurable server URL
```

### Persistence

`ScanStore` (singleton object in MainActivity.kt) reads/writes `pending_scans.json` in `context.filesDir`. No database. JSON is a top-level array of scan entries.

### Supplicant Measurement

After each WiFi scan, the app sequentially attempts to connect to each detected AP using `WifiNetworkSpecifier` + `ConnectivityManager.requestNetwork()`. Key constraints:

- **ダイアログ表示はAndroid OS側が制御する。必ずしも表示されるとは限らない。** アプリはrequestNetwork()を呼ぶだけであり、その後のUI表示・接続処理はOS任せ。
- `SUPPLICANT_STATE_CHANGED_ACTION` BroadcastReceiver captures state transitions
- タイムアウトは2段階: `NO_ACTIVITY_TIMEOUT_MS`（3s）を `requestNetwork()` 第4引数に渡しOS側タイムアウトとする。通常はこれで `onUnavailable` が発火し早期終了。`DIALOG_TIMEOUT_MS`（10s）は `withTimeoutOrNull` のフォールバックで `onUnavailable` すら来ない異常ケース用。10秒何も来なければ → `FAILED_AT_DIALOG_TIMEOUT`
- `supplicantContinuation` (volatile field) connects the BroadcastReceiver to the suspended coroutine
- **Critical**: `requestNetwork()` must be called INSIDE `suspendCancellableCoroutine` block, after setting `supplicantContinuation`, to avoid race condition where `onUnavailable()` fires before the continuation is registered
- WEP APs are skipped (`SKIPPED`). EAP APs are attempted with dummy credentials.
- `isProcessingResults` flag prevents re-entry into `onScanResultsReady()` when `requestNetwork()` triggers internal system scans
- 詳細な設計考察・実測データ分析は `md/supplicant測定設計考察.md` を参照

### Security Classification (`getSecurity()`)

Parses `ScanResult.capabilities` raw string. Priority order matters — `WPA2+SAE` → `WPA2/WPA3`, then SAE/OWE/EAP_SUITE_B → `WPA3`, then WPA2, WPA, WEP, Open. See `md/セキュリティ、無線LAN規格/security_classification_notes.md` for the full decision rationale and edge cases.

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
