# 接続施行測定（SSID）送信JSONスキーマ

作成日：2026-09-10

---

## 送信形式

- **メソッド**：POST
- **Content-Type**：`application/json; charset=UTF-8`
- **ボディ**：1スキャンを1要素とした **JSON配列**（1リクエストにつき1エントリ）

```json
[
  { ...スキャンエントリ... }
]
```

---

## スキャンエントリ（トップレベル）

| フィールド | 型 | 説明 |
|---|---|---|
| `scan_id` | string (UUID) | スキャン1回ごとに生成 |
| `device_id` | string (UUID) | 端末識別子。初回起動時に生成し永続保持 |
| `device` | object | 端末情報（下記参照） |
| `scanned_at` | string (ISO8601) | スキャン実行時刻 `yyyy-MM-dd'T'HH:mm:ss` |
| `label` | string | セッション期間のタイムスタンプ文字列（例：`2026/09/10 13:00~13:45`）。セッション中は内部UUIDが入り、終了時に一括更新される |
| `targeted_ssids` | array\<string\> | セッション開始時に選択したSSID一覧。**セッションモード時のみ含まれる**（通常スキャン時は省略） |
| `location` | object \| null | GPS位置情報。取得できない場合は `null` |
| `access_points` | array\<object\> | 測定したAP一覧（下記参照）。選択SSIDが見つからなかった場合は空配列 |

### device オブジェクト

| フィールド | 型 | 説明 |
|---|---|---|
| `manufacturer` | string | 端末メーカー（例：`Google`） |
| `model` | string | 機種名（例：`Pixel 7`） |
| `android_api` | integer | Android APIレベル（例：`34`） |

### location オブジェクト

| フィールド | 型 | 説明 |
|---|---|---|
| `latitude` | number | 緯度 |
| `longitude` | number | 経度 |
| `accuracy_m` | number | 精度（メートル） |

---

## access_points エントリ（APごと）

| フィールド | 型 | 説明 |
|---|---|---|
| `bssid` | string | MACアドレス（例：`aa:bb:cc:dd:ee:ff`） |
| `mld_mac_address` | string \| null | Wi-Fi 7 MLD MAC。MLO非対応APは `null` |
| `oui` | string | BSSIDの先頭3オクテット（例：`aa:bb:cc`） |
| `ssids` | array\<string\> | このBSSIDが発しているSSID一覧。非公開APは空配列 |
| `rssi_dbm` | integer | 電波強度（dBm）。大きいほど強い（例：`-65`） |
| `frequency_mhz` | integer | 使用周波数（例：`5180`） |
| `band` | string | `"2.4GHz"` / `"5GHz"` / `"6GHz"` |
| `channel_width_mhz` | integer | チャネル幅（`20` / `40` / `80` / `160` / `320`） |
| `wifi_standard` | string | 世代（例：`"802.11ax"`） |
| `wifi_standard_code` | integer | `wifiStandard` の整数コード |
| `security` | string | `"Open"` / `"WEP"` / `"WPA"` / `"WPA2"` / `"WPA3"` / `"WPA2/WPA3"` |
| `capabilities_raw` | string | OSが返すセキュリティ情報の生文字列（例：`[WPA2-PSK-CCMP][ESS]`） |
| `supplicant_states` | array\<string\> | Supplicant状態遷移の記録（順番通り） |
| `supplicant_final_state` | string | 最終状態（下記参照） |
| `supplicant_elapsed_ms` | integer | 接続試行にかかった時間（ミリ秒）。未測定は `-1` |

### supplicant_final_state の値一覧

| 値 | 意味 |
|---|---|
| `COMPLETED` | 接続成功 |
| `FAILED_AT_<STATE>` | 指定状態で失敗終了（例：`FAILED_AT_DISCONNECTED`） |
| `TIMEOUT_AT_<STATE>` | 指定状態でタイムアウト |
| `NOT_FOUND` | APが見つからなかった（`onUnavailable`で即終了） |
| `SKIPPED` | WEP等の非対応APはスキップ |
| `CANCELLED` | 測定中断ボタンで停止 |
| `NOT_MEASURED` | 未測定（接続試行が行われていない） |

---

## targeted_ssids による「見つからなかったSSID」の導出

`targeted_ssids` にあって `access_points[].ssids` の和集合に含まれないSSIDが、そのスキャン地点で**見つからなかったSSID**。

```
見つからなかったSSID = targeted_ssids - ∪(access_points[i].ssids)
```

`not_found_ssids` フィールドは持たず、差分で導出する設計。

---

## JSONサンプル（フルエントリ）

```json
[
  {
    "scan_id": "550e8400-e29b-41d4-a716-446655440000",
    "device_id": "550e8400-e29b-41d4-a716-446655440001",
    "device": {
      "manufacturer": "Google",
      "model": "Pixel 7",
      "android_api": 34
    },
    "scanned_at": "2026-09-10T13:05:00",
    "label": "2026/09/10 13:00~13:45",
    "targeted_ssids": ["MyWifi", "CafeWifi"],
    "location": {
      "latitude": 35.123456,
      "longitude": 135.123456,
      "accuracy_m": 5.0
    },
    "access_points": [
      {
        "bssid": "aa:bb:cc:dd:ee:ff",
        "mld_mac_address": null,
        "oui": "aa:bb:cc",
        "ssids": ["MyWifi"],
        "rssi_dbm": -65,
        "frequency_mhz": 5180,
        "band": "5GHz",
        "channel_width_mhz": 80,
        "wifi_standard": "802.11ax",
        "wifi_standard_code": 6,
        "security": "WPA2",
        "capabilities_raw": "[WPA2-PSK-CCMP][ESS]",
        "supplicant_states": [
          "DISCONNECTED", "SCANNING", "ASSOCIATING",
          "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "DISCONNECTED"
        ],
        "supplicant_final_state": "FAILED_AT_DISCONNECTED",
        "supplicant_elapsed_ms": 3241
      }
    ]
  }
]
```
