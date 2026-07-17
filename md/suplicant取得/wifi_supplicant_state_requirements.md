# WiFi Supplicant State計測アプリ 要件定義

## 1. 背景・目的
ある地点Aでスキャンして検出したn台のAPそれぞれについて、supplicant stateの状態遷移
（DISCONNECTED → SCANNING → AUTHENTICATING → ASSOCIATING → ASSOCIATED 等）を記録し、
「AP1はDISCONNECTED → SCANNING → AUTHENTICATING → ASSOCIATING → ASSOCIATED」、
「AP2はDISCONNECTED、…、APnは…」という結果を得る機能を開発する。

## 2. 前提・制約
- 1つの無線インターフェースは、時刻tにおいて1つのSSID/APにしか接続（supplicant state取得）できない。
- そのためn台全APの状態を**同時刻に**取得することは不可能。AP1→AP2→…→APnと順番に接続試行するしかない。
- スキャン自体（ビーコン受信・プローブ要求/応答）は全APから同時に情報を得られるため、AP一覧の取得は高速に可能。
- 真の同時計測が必要な場合は、複数の無線インターフェース（複数端末 or マルチアダプタ）で並列に担当APを分担する必要がある。（この機能は現状1台のスマホを測定機器として用いることを想定）

## 3. 機能要件

### 3.1 スキャンフェーズ
- 地点Aで受信可能な全AP（n台）を検出する（SSID, BSSID, RSSI, チャネル, セキュリティ方式を取得）（これは現在実装済み）

### 3.2 逐次接続試行フェーズ
- i = 1 〜 n について、AP_iへ「接続試行」を行い、supplicant stateの遷移をタイムスタンプ付きで記録する。
- 「接続試行」：プログラムが自動で認証→アソシエーションなどの無線LANに接続するまでの一連の動作
- 
| 操作（イベント処理）             | supplicant state |
|:-----------------------|:-----------------|
| 何もしていない                | DISCONNECTED     |
| ビーコンを受信                | DISCONNECTED     |
| OSがプログラムで自動送信(プローブ要求)  | DISCONNECTED     |
| プローブ応答                 | DISCONNECTED     |
| osがapi経由でssidを指定し、接続要求 | ASSOCIATING      |
|||

  
- 各AP試行はタイムアウト（既定値: 例 5秒、要調整）を設け、ASSOCIATEDに到達しない場合は「失敗」として次のAPに移る。
  - 「失敗」：認証拒否、応答なし、途中で止まったなどのassociated状態へ到達できなかったことを全般を意味する
- 1AP試行が終わったら切断し、次のAPへ移行する。

### 3.3 ロギング・出力
- AP毎に以下を記録:
  - 最終状態（ASSOCIATED / DISCONNECTED / タイムアウト等）
  - 状態遷移の履歴（state, timestamp のリスト）
  - 試行開始〜終了までの所要時間
- 全AP試行完了後、一覧表形式で出力（AP1: ASSOCIATED, AP2: DISCONNECTED, …）。

## 4. 非機能要件（時間見積もり）
- 1AP試行あたりの所要時間は概算0.1〜数秒（認証方式やEAPの有無で変動）。
- 全体所要時間 t は検出台数 n にほぼ比例（O(n)）。
- 目安: t ≈ n ×（0.1〜数秒）
- 例: n=100 の場合、t は概ね10〜100秒台のオーダーと推測（タイムアウトや再試行が絡むと数百秒に伸びる可能性あり）。

## 5. データ構造（例）
```json
{
  "location": "A",
  "scan_time": "2026-07-09T10:00:00",
  "detected_ap_count": 100,
  "results": [
    {
      "ap_id": "AP1",
      "ssid": "xxxx",
      "bssid": "xx:xx:xx:xx:xx:xx",
      "final_state": "ASSOCIATED",
      "state_transitions": [
        {"state": "DISCONNECTED", "t": "10:00:01.000"},
        {"state": "AUTHENTICATING", "t": "10:00:01.150"},
        {"state": "ASSOCIATING", "t": "10:00:01.300"},
        {"state": "ASSOCIATED", "t": "10:00:01.500"}
      ],
      "elapsed_ms": 500
    }
  ]
}
```

## 6. 今後の確認事項
- 対象OS（Android/iOS/両方）
- タイムアウト値の具体的な設定
- リトライの要否
- 認証方式（オープン/WPA2-PSK/WPA2-Enterprise等）による所要時間差の扱い
