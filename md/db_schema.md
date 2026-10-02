# DBスキーマ

**DB名:** `new_index_wifi`  
**DBMS:** MySQL  
**文字コード:** utf8mb4 / utf8mb4_general_ci

---

## テーブル一覧

- [beacon_data](#beacon_data)
- [state_data](#state_data)

---

## beacon_data

Wi-Fiスキャンデータを格納するテーブル。

**POST先:** `/api/upload/beacon`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---|---|---|---|---|
| `measurement_id` | INT | NO | AUTO_INCREMENT | PK |
| `direction` | CHAR(3) | YES | NULL | 方角（領域識別用） |
| `region_latitude` | INT | YES | NULL | 緯度領域番号 |
| `region_longitude` | INT | YES | NULL | 経度領域番号 |
| `date` | DATETIME | YES | NULL | 計測日時 |
| `latitude` | DOUBLE | YES | NULL | 緯度 |
| `longitude` | DOUBLE | YES | NULL | 経度 |
| `ssid` | VARCHAR(32) | YES | NULL | SSID |
| `mac` | CHAR(17) | YES | NULL | MACアドレス |
| `rssi` | INT | YES | NULL | 電波強度（dBm） |
| `capabilities` | VARCHAR(100) | YES | NULL | セキュリティ方式など |
| `frequency` | SMALLINT UNSIGNED | YES | NULL | 周波数（MHz） |
| `standard` | VARCHAR(8) | YES | NULL | Wi-Fi規格 |
| `uuid` | VARCHAR(36) | YES | NULL | 端末識別ID |

---

## state_data

Wi-Fi接続状態データを格納するテーブル。

**POST先:** `/api/upload/state`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---|---|---|---|---|
| `state_id` | INT | NO | AUTO_INCREMENT | PK |
| `direction` | CHAR(3) | YES | NULL | 方角（領域識別用） |
| `region_latitude` | INT | YES | NULL | 緯度領域番号 |
| `region_longitude` | INT | YES | NULL | 経度領域番号 |
| `datetime` | DATETIME(3) | NO | - | 計測日時（ミリ秒精度） |
| `latitude` | DOUBLE | NO | - | 緯度 |
| `longitude` | DOUBLE | NO | - | 経度 |
| `applicableRedactions` | DOUBLE | YES | NULL | リダクション情報（Android API 31以上） |
| `subscriptionId` | INT | YES | NULL | SIMサブスクリプションID |
| `supplicantState` | VARCHAR(18) | NO | - | 接続状態（例: COMPLETED） |
| `detailedState` | VARCHAR(18) | NO | - | 詳細状態 |
| `currentSecurityType` | VARCHAR(10) | YES | NULL | セキュリティ種別 |
| `ssid` | VARCHAR(32) | YES | NULL | SSID |
| `bssid` | CHAR(17) | YES | NULL | BSSIDアドレス |
| `frequency` | SMALLINT | YES | NULL | 周波数（MHz） |
| `networkId` | INT | YES | NULL | ネットワークID |
| `wifiStandard` | VARCHAR(8) | YES | NULL | Wi-Fi規格 |
| `maxSupportedRxLinkSpeedMbps` | SMALLINT | YES | NULL | 最大受信リンク速度（Mbps） |
| `maxSupportedTxLinkSpeedMbps` | SMALLINT | YES | NULL | 最大送信リンク速度（Mbps） |
| `rssi` | INT | YES | NULL | 電波強度（dBm） |
| `rxLinkSpeedMbps` | SMALLINT | YES | NULL | 受信リンク速度（Mbps） |
| `txLinkSpeedMbps` | SMALLINT | YES | NULL | 送信リンク速度（Mbps） |
| `linkSpeed` | SMALLINT | YES | NULL | リンク速度（Mbps） |
| `ipAddress` | INT | YES | NULL | IPアドレス（数値表現） |
| `isRestricted` | TINYINT(1) | YES | NULL | 制限付きネットワークか |
| `uuid` | VARCHAR(36) | YES | NULL | 端末識別ID |

---

## 備考

- `direction` / `region_latitude` / `region_longitude` はデータ挿入時に緯度・経度から自動算出される領域情報。
- `beacon_data.frequency` が `0` の場合、`rssi` と `frequency` は `NULL` として保存される。
- `state_data.applicableRedactions` / `subscriptionId` は Android API 31未満の端末では `NULL` として保存される。
- DB接続情報は `server/data_access/config.json` に記載。
- テーブル定義の正式なソースは `docker/mysql/create_table.sql`。
