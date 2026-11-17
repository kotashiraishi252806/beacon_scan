# 設計: BSSIDごとの接続試行開始時刻記録 (`connection_started_at`)

## 1. 目的

BSSIDごとに接続試行を開始した時刻を絶対時刻で記録し、送信JSONに含めてサーバ側で参照可能にする。既存の相対時間 (`supplicant_states[].elapsed_ms`、`supplicant_elapsed_ms`、`supplicant_api_calls[].elapsed_ms`) の基準点を、各APごとの絶対時刻として明示する。

## 2. 入力

- `measureForAp(ap: AccessPoint)` の入口で `System.currentTimeMillis()` から取得する現在時刻（long, UNIX epoch ms）
- 既存の `measureStartMs` と同一の値

## 3. 処理手順

1. `SupplicantMeasurer.measureForAp()` 入口で `val startMs = System.currentTimeMillis()` を実行（既存処理）
2. `measureStartMs = startMs` を代入（既存処理）
3. `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.getDefault()).format(Date(startMs))` で ISO8601 文字列を生成し `connectionStartedAt` ローカル変数に保持
4. SKIPPED早期return経路: `ap.copy(connectionStartedAt = connectionStartedAt, supplicantFinalState = "SKIPPED", supplicantElapsedMs = 0L)` を返す
5. 正常完了経路: `ap.copy(connectionStartedAt = connectionStartedAt, supplicantStates = ..., supplicantFinalState = ..., supplicantElapsedMs = ..., supplicantApiCalls = ...)` を返す
6. `ConnectionMeasurementActivity.saveToPending()` 内で、各APの JSONObject に `put("connection_started_at", ap.connectionStartedAt ?: JSONObject.NULL)` を追加（`capabilities_raw` の直後、`supplicant_states` の直前）

## 4. 出力

送信JSONの `access_points[i]` に新規フィールド追加:

```json
"access_points": [{
  "bssid": "aa:bb:cc:dd:ee:ff",
  ...
  "capabilities_raw": "[WPA2-PSK-CCMP][ESS]",
  "connection_started_at": "2026-11-17T10:30:45.123",
  "supplicant_states": [...],
  "supplicant_final_state": "...",
  "supplicant_elapsed_ms": 1234,
  "supplicant_api_calls": [...]
}]
```

- 型: string（SKIPPED以外は必ず値あり）または null
- 書式: `yyyy-MM-dd'T'HH:mm:ss.SSS`
- タイムゾーン: 端末デフォルト（`SimpleDateFormat` のデフォルト動作、既存 `scanned_at` と同じ）

## 5. 使用技術

- `java.text.SimpleDateFormat`
- `java.util.Date`
- `java.util.Locale`
- `System.currentTimeMillis()`
- `org.json.JSONObject.NULL`
- Kotlin data class の `copy()`

## 6. 該当ファイル

| ファイル | 変更内容 |
|---|---|
| `app/src/main/java/com/example/beacon_scan/ScanData.kt` | `AccessPoint` に `connectionStartedAt: String? = null` を追加（`supplicantStates` の直前） |
| `app/src/main/java/com/example/beacon_scan/SupplicantMeasurer.kt` | `import java.text.SimpleDateFormat` / `java.util.Date` / `java.util.Locale` 追加、`measureForAp()` で `connectionStartedAt` を生成し2経路の `ap.copy()` に渡す |
| `app/src/main/java/com/example/beacon_scan/ConnectionMeasurementActivity.kt` | `saveToPending()` の APループ内に `put("connection_started_at", ...)` を追加 |
| `docs/specs/connection_started_at記録.md` | 事前仕様 |
| `docs/design/connection_started_at記録.md` | 本ファイル |
| `CLAUDE.md` | 機能索引に行追加 |

## 7. 判断理由

- **基準点をA（`measureForAp()` 入口）としたこと**: 既存の `measureStartMs`（= `supplicant_states[].elapsed_ms` 等の基準）と一致させるため。Bを採用すると数ms〜十数msの基準ズレが発生し、既存相対時間と組み合わせた集計が煩雑になる。
- **書式に `.SSS`（ミリ秒）を含めたこと**: APごとの試行間隔が秒未満になり得るため、秒精度では順序や経過が解像できない。既存 `scanned_at` は秒精度だが、こちらは APごとの細かい時間を扱うため解像度を上げた。
- **SKIPPED時にも値を記録すること**: `startMs` が L164 で既に設定済みであり、「測定サイクル開始時刻」としての意味は保持される。ユーザー指示で「無視していい」とされた範囲で、既存コード構造から自然に導かれる動作を採用した。
- **`SimpleMeasurementActivity` には追加しないこと**: 同アクティビティは supplicant 測定を行わず、`supplicant_states` 等も出していない。接続試行していないAPに `connection_started_at` は定義できない。
- **フィールド位置を `supplicant_states` の直前にしたこと**: supplicant関連グループの先頭に時刻基準点を置き、`elapsed_ms` 系の起点が明示されるようにした。
