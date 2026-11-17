# 用語集

## `connection_started_at`

送信JSONの `access_points[i]` 直下に置く文字列フィールド。`SupplicantMeasurer.measureForAp()` 入口で `System.currentTimeMillis()` を取得した時刻を ISO8601 形式 (`yyyy-MM-dd'T'HH:mm:ss.SSS`) で保持する。タイムゾーンは端末デフォルト。既存の `supplicant_states[].elapsed_ms` 等の相対時間の基準点に対応する絶対時刻。SKIPPED時も値が入る。

## 接続試行開始時刻（A定義）

`SupplicantMeasurer.measureForAp()` 入口で `measureStartMs` を設定する瞬間。`buildSpecifier()` / `NetworkRequest.Builder` / `requestNetwork()` の呼び出しより前。`supplicant_states[].elapsed_ms`、`supplicant_elapsed_ms`、`supplicant_api_calls[].elapsed_ms` はすべてこの時点を 0 ms とする。
