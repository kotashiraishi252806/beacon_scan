# finalState 判定ロジック 要件定義書

作成日：2026-10-23

---

## 1. 目的

OSタイムアウトを何秒に設定すれば検出したAPのサプリカント状態を取得できるのかという疑問に答えるため、1台のAPへの接続試行がどの終了条件で終わったかと、そのときのサプリカント状態取得状況を一意に示す finalState を付与する仕組みを実装する。

---

## 2. 変更対象ファイル

- `app/src/main/java/com/example/beacon_scan/SupplicantMeasurer.kt`

---

## 3. finalState 一覧

| 番号 | finalState 文字列 | 終了条件 | サプリカント状態 |
|---|---|---|---|
| ① | `SKIPPED` | 接続試行を開始できなかった（WEP等非対応種別、BSSIDパース失敗） | 記録なし |
| ② | `NETWORK_AVAILABLE` | OSからネットワーク接続確立通知（onAvailable）を受けた | — |
| ③ | `AUTH_COMPLETED` | OSタイムアウト（3s）前にBroadcastReceiverがCOMPLETEDを受信 | COMPLETED |
| ④ | `PRE_OSTIMEOUT_<直前状態>` | OSタイムアウト（3s）前にBroadcastReceiverがDISCONNECTEDを受信（ASSOCIATED等を経由） | DISCONNECTED、直前状態を文字列に含む |
| ⑤ | `OSTIMEOUT_NOSUPPLICANT` | 3s OSタイムアウト発火、サプリカント状態の記録0件 | 記録なし |
| ⑥ | `OSTIMEOUT_<最後の状態>` | 3s OSタイムアウト発火、サプリカント状態の記録1件以上 | 最後の状態を文字列に含む |
| ⑦ | `COREBACKTIMEOUT_<最後の状態>` | 10sフォールバックタイムアウト発火、サプリカント状態の記録1件以上 | 最後の状態を文字列に含む |
| ⑧ | `COREBACKTIMEOUT_NOSUPPLICANT` | 10sフォールバックタイムアウト発火、サプリカント状態の記録0件 | 記録なし |

---

## 4. 判定フロー

`withTimeoutOrNull` ブロック終了後に以下の順序で判定する。

```
1. buildSpecifier() == null
       → SKIPPED

2. finalState == "NETWORK_AVAILABLE"（onAvailable が発火済み）
       → NETWORK_AVAILABLE

3. brEarlyExitOccurred == true（BroadcastReceiverが cont.resume() を呼んだ）
   3a. transitions.last() == "COMPLETED"
           → AUTH_COMPLETED
   3b. それ以外（DISCONNECTED after ASSOCIATED 等）
           → PRE_OSTIMEOUT_<supplicantTransitions[size - 2]>

4. unavailableFired == true（onUnavailable が発火済み）
   4a. transitions.isEmpty()
           → OSTIMEOUT_NOSUPPLICANT
   4b. transitions.isNotEmpty()
           → OSTIMEOUT_<transitions.last()>

5. それ以外（10s フォールバックタイムアウト）
   5a. transitions.isEmpty()
           → COREBACKTIMEOUT_NOSUPPLICANT
   5b. transitions.isNotEmpty()
           → COREBACKTIMEOUT_<transitions.last()>
```

---

## 5. 実装内容

### 5.1 BR早期終了フラグの追加

`measureForAp()` 内に `var brEarlyExitOccurred = false` を追加する。

`supplicantReceiver`（BroadcastReceiver）が `cont.resume()` を呼ぶ直前に `brEarlyExitOccurred = true` をセットする。

対象箇所：
- `SupplicantState.COMPLETED` 受信時
- `SupplicantState.DISCONNECTED` 受信かつ ASSOCIATED 等を経由済みの場合

### 5.2 finalState 文字列の生成規則

| finalState | 生成方法 |
|---|---|
| `PRE_OSTIMEOUT_<直前状態>` | `"PRE_OSTIMEOUT_" + supplicantTransitions[supplicantTransitions.size - 2]` |
| `OSTIMEOUT_<最後の状態>` | `"OSTIMEOUT_" + supplicantTransitions.last()` |
| `COREBACKTIMEOUT_<最後の状態>` | `"COREBACKTIMEOUT_" + supplicantTransitions.last()` |

### 5.3 削除する既存ロジック

- `stopRequested` による CANCELLED 判定を削除する
- 停止ボタン関連の処理（`requestStop()`、`stopRequested` フラグ）を削除する

---

## 6. 変更しないもの

- `supplicantTransitions` への状態記録ロジック
- `unavailableFired` フラグのセットロジック
- `withTimeoutOrNull(DIALOG_TIMEOUT_MS)` の構造
- `buildSpecifier()` の実装
- `onAvailable()` での `finalState = "NETWORK_AVAILABLE"` のセット
