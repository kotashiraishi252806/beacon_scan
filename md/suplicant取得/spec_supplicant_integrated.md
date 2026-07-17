# Supplicant State 統合取得機能 仕様書

**対象アプリ**: beacon_scan (Android)  
**作成日**: 2026-07-13  
**ステータス**: 実装前レビュー用

---

## 1. 背景・目的

### 現状の課題
現在のアプリはWiFiスキャンで周辺APの電波情報（BSSID / RSSI / セキュリティ規格 等）を収集している。  
しかし、APが実際に接続要求に対してどのように応答するかは記録していない。

別画面（SupplicantScanActivity）に「Supplicant測定」ボタンが存在するが、
- スキャン後にユーザが手動で別ボタンを押す必要がある
- 取得データが既存のスキャンデータと別構造で保存される（`supplicant_results`として分離）
- 自動スキャン中は利用不可

### 目的
スキャンと同時にSupplicant状態遷移を自動取得し、**既存のAPデータの一部として一体的に記録・送信できる**ようにする。

---

## 2. ユーザ操作と動作の概要

### 2-1. スキャンボタンを押す（手動スキャン）

| ステップ | ユーザ操作 | アプリの動作 |
|---------|-----------|-------------|
| 1 | 「スキャン」ボタンをタップ | 周辺WiFi APをパッシブスキャン |
| 2 | （待機） | スキャン完了後、検出したN台のAPに対して順次接続試行を開始 |
| 3 | （待機） | 各APのSupplicant状態遷移を記録（1台あたり最大5秒） |
| 4 | （待機） | 全AP完了後、Supplicant状態を含む統合データをローカル保存 |
| 5 | （任意）「送信」ボタン | 保存データをサーバへ送信 |

### 2-2. 自動スキャンスイッチをONにする

5秒ごとにスキャン→Supplicant測定→保存が自動で繰り返される。  
ユーザは操作不要。スイッチをOFFにするとセッションラベルの入力ダイアログが表示される。

---

## 3. ユーザが得られるもの（機能）

### スキャン結果に含まれる新フィールド（AP1台ごと）

```json
{
  "bssid": "AA:BB:CC:DD:EE:FF",
  "ssids": ["MyNetwork"],
  "security": "WPA2",
  "rssi_dbm": -62,
  ...（既存フィールド）...,

  "supplicant_states": ["SCANNING", "ASSOCIATING", "ASSOCIATED", "4WAY_HANDSHAKE", "DISCONNECTED"],
  "supplicant_final_state": "TIMEOUT_AT_4WAY_HANDSHAKE",
  "supplicant_elapsed_ms": 3241
}
```

| 新フィールド | 内容 |
|------------|------|
| `supplicant_states` | 接続試行中に遷移したSupplicant状態の時系列リスト |
| `supplicant_final_state` | 試行終了時の最終状態（`COMPLETED` / `TIMEOUT_AT_<STATE>` / `SKIPPED` 等） |
| `supplicant_elapsed_ms` | 接続試行に要した時間（ミリ秒） |

### 取得できるSupplicant状態の例

| 状態名 | 意味 |
|--------|------|
| `SCANNING` | APを探索中 |
| `ASSOCIATING` | APへの接続要求を送信中 |
| `ASSOCIATED` | APとの接続確立（認証前） |
| `4WAY_HANDSHAKE` | WPA2/WPA3の暗号鍵交換中（パスワード検証フェーズ） |
| `DISCONNECTED` | 接続失敗または切断 |
| `COMPLETED` | 接続完了（認証成功、オープンAPのみ実質到達可能） |

---

## 4. なぜこれが可能なのか（技術的根拠）

### 4-1. Supplicant状態とは

Android端末のWiFiスタックには **wpa_supplicant**（WPA認証を管理するデーモン）が組み込まれている。  
端末がAPに接続を試みると、wpa_supplicantが以下のプロセスを経る：

```
INACTIVE → SCANNING → ASSOCIATING → ASSOCIATED → 4WAY_HANDSHAKE → COMPLETED
                                                              ↓ (パスワード不一致)
                                                        DISCONNECTED
```

### 4-2. ダミーパスフレーズによる接続試行

アプリはパスワードに `DUMMY_MEAS_12345`（固定文字列）を使って各APに接続要求を出す。

- **オープンAP（認証なし）**: `COMPLETED`まで到達する可能性がある
- **WPA2/WPA3 AP**: `4WAY_HANDSHAKE`で失敗し`DISCONNECTED`に遷移する
- **認証到達まで**の状態遷移（`ASSOCIATING` → `ASSOCIATED` → `4WAY_HANDSHAKE`）は記録できる
- 目的は接続成功ではなく、**APが接続要求にどこまで応答するかの記録**

### 4-3. BroadcastReceiverによる状態受信

```
WifiManager.SUPPLICANT_STATE_CHANGED_ACTION ブロードキャスト
```

Androidは接続状態が変化するたびにシステム全体へブロードキャストを発行する。  
アプリはこのブロードキャストを受信するBroadcastReceiverを登録しておくことで、  
接続試行中のすべてのSupplicant状態遷移をリアルタイムに取得できる。

```kotlin
registerReceiver(receiver, IntentFilter(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION))
```

受信したIntentから `WifiManager.EXTRA_NEW_STATE` を取り出すことで状態名を得る。

### 4-4. ConnectivityManagerによる接続要求とタイムアウト

```kotlin
connectivityManager.requestNetwork(request, callback, TIMEOUT_MS /* 5000ms */)
```

`WifiNetworkSpecifier`でBSSID（MACアドレス）を指定することで、特定のAPに限定して接続要求を出せる。  
5秒以内に接続完了しない場合は`onUnavailable()`コールバックが発火し、次のAPへ移行する。

### 4-5. スキャン後に逐次処理する理由

Supplicant状態は**端末が現在接続試行中のAP 1台分**しか反映しない（並列不可）。  
このため、APごとに順番に接続試行を行い、1台ずつ状態を記録する。

---

## 5. スキップされるAP（取得できないケース）

| 条件 | 理由 |
|------|------|
| セキュリティが `WEP` | Android 10以降でWEP接続APIが廃止済み |
| `EAP`（企業認証）かつ`OWE`でない | 証明書なしではAssociationさえ試みられない |
| BSSIDが不正フォーマット | `MacAddress.fromString()`が失敗するケース |

これらのAPは `supplicant_final_state: "SKIPPED"` として記録される。

---

## 6. データ保存・送信フロー

```
スキャン完了
    ↓
AP N台を順次接続試行（最大 N × 5秒）
    ↓
Supplicant状態をAP毎のオブジェクトに付与
    ↓
saveToPending() → pending_scans.json に追記（既存構造に統合）
    ↓
（自動送信ONの場合）trySendAllPending() → サーバPOST
```

### 送信JSONの最終構造（抜粋）

```json
[
  {
    "scan_id": "uuid-...",
    "scanned_at": "2026-07-13T10:00:00",
    "device": { "manufacturer": "Google", "model": "Pixel 8", "android_api": 34 },
    "location": { "latitude": 35.68, "longitude": 139.76, "accuracy_m": 12.5 },
    "label": "",
    "access_points": [
      {
        "bssid": "AA:BB:CC:DD:EE:FF",
        "ssids": ["HomeNetwork"],
        "security": "WPA2",
        "rssi_dbm": -55,
        "supplicant_states": ["SCANNING", "ASSOCIATING", "ASSOCIATED", "4WAY_HANDSHAKE", "DISCONNECTED"],
        "supplicant_final_state": "TIMEOUT_AT_4WAY_HANDSHAKE",
        "supplicant_elapsed_ms": 3100
      }
    ]
  }
]
```

---

## 7. 制約・注意事項

| 項目 | 内容 |
|------|------|
| 処理時間 | AP数 × 最大5秒。10台なら最大50秒かかる |
| 自動スキャン間隔 | 現在5秒間隔だが、Supplicant測定中は次のスキャンを待機させる必要がある |
| Android OS制限 | Android 10以降、`WifiManager.startScan()`はOSによりスロットリングされる場合がある |
| 接続の副作用 | 接続試行中は端末の現在のWiFi接続に影響しないよう`WifiNetworkSpecifier`（一時的な接続要求）を使う |
| 権限 | `ACCESS_FINE_LOCATION` / `NEARBY_WIFI_DEVICES` / `CHANGE_NETWORK_STATE` が必要 |

---

## 8. 変更対象（実装スコープ）

| ファイル | 変更内容 |
|---------|---------|
| `MainActivity.kt` | BroadcastReceiver登録、measureSupplicantForAll()追加、onScanResultsReady()修正、saveToPending()修正 |
| `AccessPoint`データクラス | `supplicantStates`・`supplicantFinalState`・`supplicantElapsedMs`フィールド追加 |
| `SupplicantScanActivity.kt` | 不要になる（削除またはレガシー保持） |

---

## 9. 削除される機能

- メイン画面の「Supplicant測定」ボタン（`btnSupplicantScan`）
- `ScanStore.addSupplicantResults()`（別構造での保存が不要になるため）
- `SupplicantScanActivity`（統合により不要）
