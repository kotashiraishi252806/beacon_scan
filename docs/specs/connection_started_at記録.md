# 仕様: BSSIDごとの接続試行開始時刻記録 (`connection_started_at`)

## 指示（原文要約）

- BSSIDごとに接続試行を開始した時刻を記録する。
- 絶対時刻で保持する。
- JSONフィールド名は `connection_started_at`。
- 格納場所は `access_points[i]` 直下。
- 「接続試行開始」の定義は候補A（`measureForAp()` 入口時点、既存 `measureStartMs` 設定時点）とする。
- SKIPPED時・CANCELLED時の扱いは議論不要（指示より）。
- `elapsed_ms` 系は現状維持。

## 解釈

### 記録する値
- 単位: ISO8601文字列
- 書式: `yyyy-MM-dd'T'HH:mm:ss.SSS`（既存 `scanned_at` の `yyyy-MM-dd'T'HH:mm:ss` と同じ基本書式に、ミリ秒を追加）【AI補完】
  - 理由: BSSIDごとの試行間隔は秒未満になり得るため、秒精度では順序や経過が解像できない。
- タイムゾーン: 端末デフォルト（`Locale.getDefault()` と同じ扱い）【AI補完】
  - 理由: 既存 `scanned_at` と同じく `SimpleDateFormat` のデフォルトタイムゾーン指定なしに合わせる。

### 値の取得点
- `SupplicantMeasurer.measureForAp()` 内、`startMs = System.currentTimeMillis()`（L164）直後に、同一の `startMs` から派生させて ISO8601 文字列を生成する。
- `measureStartMs` と `connectionStartedAt` の時刻基準は同一（同一 `System.currentTimeMillis()` 呼び出し値を再利用）。

### 保存先
- `AccessPoint` データクラスに `connectionStartedAt: String?` を追加する。
- `measureForAp()` が返す `ap.copy(...)` に `connectionStartedAt` を含める。
- `ConnectionMeasurementActivity.saveToPending()` 内で、各 `access_points[i]` に `put("connection_started_at", ap.connectionStartedAt ?: JSONObject.NULL)` を追加する。

### SKIPPED / CANCELLED 時の挙動【AI補完】
指示では議論不要とされたため、既存コード構造に沿って以下となる旨を明記する。
- SKIPPED（`buildSpecifier` が null を返す場合）: `startMs` は L164 で既に設定済みのため、ISO8601値も生成される。SKIPPED用の早期return経路でも同じ値を含めて返す。
- CANCELLED（将来停止機能が挟まる場合）: L164通過後のキャンセルであれば値あり、通過前なら該当APはresultsに追加されない。

## JSONスキーマ変更

### 変更前
```json
"access_points": [{
  "bssid": "...",
  ...
  "supplicant_states": [...],
  "supplicant_final_state": "...",
  "supplicant_elapsed_ms": 1234,
  "supplicant_api_calls": [...]
}]
```

### 変更後
```json
"access_points": [{
  "bssid": "...",
  ...
  "connection_started_at": "2026-11-17T10:30:45.123",
  "supplicant_states": [...],
  "supplicant_final_state": "...",
  "supplicant_elapsed_ms": 1234,
  "supplicant_api_calls": [...]
}]
```

### フィールドの位置【AI補完】
`supplicant_states` の直前に置く。理由: supplicant関連グループの先頭に時刻基準点があると、`supplicant_states[].elapsed_ms` の起点が明示される。

## 実装箇所

| ファイル | 変更内容 |
|---|---|
| `app/src/main/java/com/example/beacon_scan/ScanData.kt` | `AccessPoint` に `connectionStartedAt: String? = null` を追加 |
| `app/src/main/java/com/example/beacon_scan/SupplicantMeasurer.kt` | L164-165 で ISO8601 文字列を生成し、SKIPPED早期return経路・正常return経路の両方の `ap.copy(...)` に含める |
| `app/src/main/java/com/example/beacon_scan/ConnectionMeasurementActivity.kt` | `saveToPending()` で `put("connection_started_at", ...)` を追加（`supplicant_states` の直前） |
| `app/src/main/java/com/example/beacon_scan/SimpleMeasurementActivity.kt` | `saveToPending()` に同一フィールドを追加するかは要判断（下記参照） |

### SimpleMeasurementActivity の扱い【AI補完】
`SimpleMeasurementActivity.saveToPending()` は supplicant 測定を伴わないスキャン用の送信経路のため、`connection_started_at` を追加しない方針とする。
- 理由: `supplicant_states` 等も出していない。接続試行していないAPに `connection_started_at` は定義できない。
- 承認時に異なる方針（例: null固定で入れる）を指定された場合はそれに従う。

## 既存機能への影響

- `AccessPoint` のフィールド追加のみ。既存フィールドは不変。
- `supplicant_elapsed_ms` と `supplicant_states[].elapsed_ms` の基準は不変。
- `scanned_at`（スキャンエントリ全体）とも不変。
- サーバ側受信スキーマが追加フィールドを許容する前提。【AI補完・未確認】

## 変更履歴

- 実装後、作業単位で git commit する（CLAUDE.mdグローバル規約）。
- 実装後、 `docs/design/connection_started_at記録.md` を7項目で作成する。
- 実装後、CLAUDE.md の機能索引に行を追加する。

## 承認待ち項目

以下のAI補完箇所について、問題なければ承認してほしい。問題あれば差戻し。

1. 書式に `.SSS`（ミリ秒）を含める
2. タイムゾーンはデバイスデフォルト（既存 `scanned_at` と同じ扱い）
3. JSON内の挿入位置は `supplicant_states` の直前
4. SKIPPED時も値を記録する（`startMs` が自然に通過するため）
5. `SimpleMeasurementActivity.saveToPending()` には追加しない
