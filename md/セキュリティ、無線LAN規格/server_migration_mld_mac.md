# サーバー側対応依頼：mld_mac_address フィールドの追加

## 経緯

無線LAN環境の把握精度向上のため、WiFi 7（802.11be）のMLO（Multi-Link Operation）に対応したAPを識別できるよう、スキャンデータに `mld_mac_address` フィールドを追加しました。

### MLD MACアドレスとは

WiFi 7対応APは、MLO機能により複数の帯域（2.4GHz / 5GHz / 6GHz）を同時に使用します。このとき各帯域は異なるBSSIDを持ちますが、物理的には同一のAP（MLD: Multi-Link Device）です。MLD MACアドレスはその物理APを識別するためのアドレスです。

### 取得条件

以下の両方を満たす場合のみ、MLD MACアドレスが取得できます。

| 条件 | 内容 |
|---|---|
| 端末側 | Android API Level 33以上 |
| AP側 | WiFi 7（802.11be）MLO対応 |

どちらか一方でも満たさない場合は `null` になります。

---

## 新しいデータ構造

`access_points` 配列の各要素に `mld_mac_address` フィールドが追加されます。

### フィールド仕様

| フィールド名 | 型 | 値 |
|---|---|---|
| `mld_mac_address` | string or null | MLD MACアドレス（例：`d2:56:f2:96:15:0f`）または `null` |

### 送信JSONの例

```json
{
  "scan_id": "uuid",
  "device_id": "uuid",
  "scanned_at": "2026-06-19T13:00:00",
  "label": "2026/06/19 13:00~14:00",
  "access_points": [
    {
      "bssid": "d2:56:f2:96:15:0f",
      "mld_mac_address": "d2:56:f2:96:15:0f",
      "oui": "d2:56:f2",
      "ssids": ["0000be"],
      "rssi_dbm": -55,
      "frequency_mhz": 5180,
      "band": "5GHz",
      "channel_width_mhz": 80,
      "wifi_standard": "802.11be",
      "wifi_standard_code": 8,
      "security": "WPA3",
      "capabilities_raw": "[RSN-SAE-CCMP][ESS]"
    },
    {
      "bssid": "d0:56:f2:96:16:08",
      "mld_mac_address": null,
      "oui": "d0:56:f2",
      "ssids": ["00002.4"],
      "rssi_dbm": -60,
      "frequency_mhz": 2437,
      "band": "2.4GHz",
      "channel_width_mhz": 20,
      "wifi_standard": "802.11ax",
      "wifi_standard_code": 6,
      "security": "WPA2",
      "capabilities_raw": "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"
    }
  ]
}
```

### null の意味

`mld_mac_address` が `null` の場合、以下のいずれかです。

- 測定端末がAPI 33未満（WiFi 7 MLD MAC取得非対応）
- APがWiFi 7 MLO非対応

サーバー側では `null` をそのままNULLとして保存してください。

---

## サーバー側の対応

### DBマイグレーション

`access_points` テーブルに `mld_mac_address` カラムを追加します。

```sql
ALTER TABLE access_points
  ADD COLUMN mld_mac_address VARCHAR(17) NULL COMMENT 'WiFi 7 MLO対応APのMLD MACアドレス。非対応端末またはAP非対応の場合はNULL';
```

### INSERT処理の変更

受信したJSONの `mld_mac_address` を `null` 許容でそのままINSERTしてください。

```sql
INSERT INTO access_points (
  scan_id,
  bssid,
  mld_mac_address,
  oui,
  ssids,
  rssi_dbm,
  frequency_mhz,
  band,
  channel_width_mhz,
  wifi_standard,
  wifi_standard_code,
  security,
  capabilities_raw
) VALUES (
  :scan_id,
  :bssid,
  :mld_mac_address,  -- NULLの場合はNULLのまま挿入
  :oui,
  :ssids,
  :rssi_dbm,
  :frequency_mhz,
  :band,
  :channel_width_mhz,
  :wifi_standard,
  :wifi_standard_code,
  :security,
  :capabilities_raw
);
```

---

## 既存データについて

このフィールド追加以前に収集済みのデータには `mld_mac_address` が存在しません。既存レコードのカラム値はNULLのままで問題ありません。
