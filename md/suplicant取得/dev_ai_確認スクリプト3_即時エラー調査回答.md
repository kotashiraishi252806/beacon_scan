# 確認スクリプト3 回答：「接続しますか？」表示後1〜2秒でエラーになる件の調査

## 前置き：回答の性質と制約

本回答の各項目は以下の3種類に分類して記述する。

- **【コード確認】** `MainActivity.kt` のコードから断定できる事実
- **【仕様推測】** Android 公式ドキュメント・API 仕様から推測されるが実機未確認
- **【要実測】** コードおよび仕様からは断定できず、実機データが必要

**重要な制約**：回答作成時点でadbに端末が接続されていないため、`pending_scans.json`の実機データを直接取得できない。確認1の回答はコード解析による推論であり、実データが取得できた時点で必ず照合すること。

---

## 確認1：`pending_scans.json`の記録データ（コード解析による推論）

### 実データの取得方法

**【要実測】** 実機接続後に以下のコマンドで取得できる。

```bash
adb shell run-as com.example.beacon_scan cat /data/data/com.example.beacon_scan/files/pending_scans.json
```

または以下でファイルを端末からPCへコピーする。

```bash
adb pull /data/data/com.example.beacon_scan/files/pending_scans.json ./
```

各エントリの `access_points` 配列内の各APについて、`supplicant_final_state`・`supplicant_elapsed_ms`・`supplicant_states` の3フィールドを確認する。

---

### コード解析による推論：1〜2秒での終了は何を意味するか

**【コード確認】** `measureSupplicantForAp()` の構造から、測定が終了する経路は以下の5つに限定される（`DIALOG_TIMEOUT_MS = 15_000L`、`AUTH_TIMEOUT_MS = 8_000L`）。

| 終了経路 | `supplicant_elapsed_ms`の目安 | `supplicant_final_state` |
|----------|-------------------------------|--------------------------|
| Phase1: 最初のSupplicant状態受信 → Phase2: DISCONNECTED（ASSOCIATED等あり）| **測定次第（0〜数秒）** | `FAILED_AT_DISCONNECTED` |
| Phase1: 最初のSupplicant状態受信 → Phase2: COMPLETED | **測定次第（0〜数秒）** | `COMPLETED` |
| Phase1: 最初のSupplicant状態受信 → Phase2: 8秒タイムアウト | 8秒＋Phase1時間 | `FAILED_AT_<最後の状態>` または `TIMEOUT_AT_<最後の状態>` |
| Phase1: `onUnavailable()` → Phase2: 8秒タイムアウト | 8秒＋Phase1時間 | `NOT_FOUND`（遷移なし）または `FAILED_AT_<最後の状態>` |
| Phase1: 15秒タイムアウト | ≈15000ms | `FAILED_AT_DIALOG_TIMEOUT` |

**結論**：体感1〜2秒でエラーになっているなら、`supplicant_elapsed_ms` は **1000〜2000前後** のはずである。

これは15秒Phase1タイムアウト（`FAILED_AT_DIALOG_TIMEOUT`）でも8秒Phase2タイムアウトでもなく、**Phase1とPhase2の両方が短時間で完了した場合に限り**起こりうる。

---

### 1〜2秒で完了できる経路の候補

**【コード確認 + 仕様推測】** Phase1とPhase2を合計1〜2秒で完了させるには、以下のいずれかの経路が必要。

#### 経路A：Supplicant状態遷移が全部連続して1〜2秒以内に到達する

WPA2（ダミーパスワード）の失敗シーケンスとして現実的な例：

```
SCANNING → ASSOCIATING → ASSOCIATED → FOUR_WAY_HANDSHAKE → DISCONNECTED
```

- 最初の状態（例：SCANNING）到着 → Phase1 完了
- その後 DISCONNECTED が到着、`supplicantTransitions` に FOUR_WAY_HANDSHAKE が含まれるため Phase2 も完了
- 合計経過時間が 1〜2 秒に収まる可能性がある

この場合の期待値：
- `supplicant_final_state` = `FAILED_AT_DISCONNECTED`
- `supplicant_states` = `["SCANNING", "ASSOCIATING", "ASSOCIATED", "FOUR_WAY_HANDSHAKE", "DISCONNECTED"]` （またはその一部）
- `supplicant_elapsed_ms` = 1000〜2000程度

#### 経路B：`onUnavailable()` が早期発火し、かつPhase2でも即完了する

- Phase1: `onUnavailable()` が1秒以内に発火 → Phase1完了（`finalState` は `NOT_FOUND` のまま）
- Phase2: 残留Supplicant状態（前APの後処理など）が DISCONNECTED with ASSOCIATED を含んで発火 → Phase2 も即完了
- この場合は `NOT_FOUND` にはならず、Phase2の終了結果によって変わる

このケースは後述の「前APの状態残留」と組み合わさると発生しうる。

---

### 重要：「最初のSupplicant状態 ＝ OKが押された」という仮定について

**【コード確認】** `supplicantReceiver.onReceive()` の核心部分（243〜260行）：

```kotlin
val isFirst = supplicantTransitions.isEmpty()
supplicantTransitions.add(state.name)
val cont = supplicantContinuation ?: return
if (!cont.isActive) return
if (isFirst) {
    // フェーズ1完了：最初のSupplicant状態受信＝OKが押された
    cont.resume(Unit)
    return
}
```

このコードは **「最初の `SUPPLICANT_STATE_CHANGED_ACTION` = ユーザーが OK を押した」** という仮定に基づいている。

しかし `SUPPLICANT_STATE_CHANGED_ACTION` は **システム全体のグローバルブロードキャスト** であり、このアプリが `requestNetwork()` で要求した接続に限定されない。以下の状況でも発火する。

1. **前APの後処理**：AP[k-1] の切断処理（DISCONNECTED）やその後の再スキャン（SCANNING）がまだブロードキャストに残っている
2. **OEM独自の事前接続**：一部のOEM実装では、`onMatch()`（AP発見）の段階でユーザー承認前に接続試行を開始し、Supplicant状態が「接続しますか？」表示より先に発火する
3. **他アプリの WiFi 操作**：同時に他のアプリが WiFi 操作を行っている場合

**この仮定が崩れると、OKを押さなくても（あるいは押す間もなく）Phase1が完了し、1〜2秒でエラーになる。**

---

### 前APの状態残留（クロスAP汚染）の可能性

**【コード確認】** `measureAllSupplicant()` の実装（732〜748行）：

```kotlin
for ((i, ap) in apList.withIndex()) {
    ...
    results.add(measureSupplicantForAp(ap))
    delay(300)   // ← AP間の待機は300ms
}
```

```kotlin
// measureSupplicantForAp() の先頭
supplicantTransitions.clear()
supplicantContinuation = null
```

AP間の待機は **300ms** のみ。前APのネットワークコールバック登録解除後、Supplicant状態ブロードキャストが到着するまでの時間が300msを超えた場合、次APのPhase1計測中に前APの状態が混入する。

典型的な混入パターン：
- AP[k-1] の FOUR_WAY_HANDSHAKE → DISCONNECTED が遅延して到達
- AP[k] の `supplicantTransitions.clear()` 後、Phase1開始直後に FOUR_WAY_HANDSHAKE が最初の状態として受信される
- `isFirst = true` → Phase1 即完了
- DISCONNECTED も続いて到達、`supplicantTransitions.any { it == "FOUR_WAY_HANDSHAKE" }` が true → Phase2 即完了
- AP[k] の `supplicant_elapsed_ms` は100ms以下になりうる

この場合、`supplicant_states` に記録されている遷移は **AP[k]ではなくAP[k-1]のもの** である。

---

### 確認1のまとめ：実データで確認すべき事項

`pending_scans.json` を取得したら以下を確認する。

1. **`supplicant_elapsed_ms`** が本当に 1000〜2000ms か、それとも 100ms 以下（クロスAP汚染）か
2. **`supplicant_states`** に FOUR_WAY_HANDSHAKE や ASSOCIATED が含まれているか
   - 含まれている → 実際にWPA2ハンドシェイクが進行した（OEM事前接続、またはクロスAP汚染）
   - 含まれていない（SCANNINGのみ、またはDISCONNECTEDのみ）→ 前APの後処理の残留が疑われる
3. **`supplicant_final_state`** の値
   - `FAILED_AT_DISCONNECTED` → Phase2 が完了経路を踏んだ（ASSOCIATED 等あり）
   - `NOT_FOUND` → Phase1 が onUnavailable() で終わり、Phase2 も遷移なしで終了した
   - `FAILED_AT_DIALOG_TIMEOUT` → 1〜2秒の体感と矛盾（15秒かかっているはず）

---

## 確認2：「エラー」はどちらの画面か

**【コード確認】** アプリ自身がユーザーに見せる「エラー」相当の表示は以下の2種類のみ。

| 表示 | 発生タイミング | コード箇所 |
|------|----------------|-----------|
| Toast「送信失敗 — 次回スキャン時に再送します」| サーバー送信失敗時 | 1103行 |
| `tvSsidCount` のテキスト更新 | 測定進行中・完了後 | 968〜1004行 |

スキャン・Supplicant測定中の `tvSsidCount` の表示遷移：

```
（測定開始直後）  "今回検出: N件\nSupplicant測定中..."
（各AP処理中）    "今回検出: N件\nSupplicant測定中 k/N (残りm件): <SSID>"
（全AP完了後）    "今回検出: N件\n未送信データ合計: M件"
```

Supplicant測定中の「エラー」に相当するアプリ自身のUI変化は **なく**、Toast も出ない。

**【仕様推測】** システムダイアログ（「接続しますか？」）が閉じるのは `onUnavailable()` がネットワークコールバックに届いたときである。アプリが `connectivityManager.unregisterNetworkCallback(cb)` を呼んだ後（Phase2終了後）にOSがダイアログを閉じるタイミングで `onUnavailable()` が来るケースがありうる。

**確認2の判別方法**：

- ケースA（Androidシステム画面のエラー）：「接続しますか？」ダイアログ自体が閉じ、`NetworkRequestErrorDialogFragment` 等のエラー表示に自動遷移する
- ケースB（アプリの表示変化）：ダイアログは残ったまま、アプリ側の `tvSsidCount` が次のAPの測定進行状況に切り替わる

ユーザーが「エラー」と表現しているのが **ケースAかケースBか** を確認することが、原因特定に直結する。

---

## 確認3：`adb logcat`の実測

**【要実測】** adb未接続のため現時点では取得不可。

実機接続後、以下のコマンドで取得する。

```bash
adb logcat -v time | grep -E "NetworkRequestDialogActivity|WifiNetworkFactory|ConnectivityService|wpa_supplicant|SupplicantStaIfaceHal|BeaconScan"
```

追加で以下のフィルタも有効。

```bash
# Supplicant状態遷移のみ
adb logcat -v time | grep -E "SUPPLICANT_STATE|SupplicantState|onMatch|onUnavailable|onAvailable"
```

注目すべき行：
- `onMatch` が呼ばれた時刻（「接続しますか？」ダイアログが出た時刻）
- `SupplicantState` 遷移の時刻（OKを押す前に発火していないか）
- `onUnavailable` の時刻（ダイアログが消えた原因）
- `onAbort` の時刻（OEM独自の中断処理があるか）

---

## 確認4：端末情報

**【コード確認】** 端末情報はすでに `pending_scans.json` の各スキャンエントリに記録されている。

```json
{
  "device": {
    "manufacturer": "...",
    "model": "...",
    "android_api": 34
  }
}
```

`pending_scans.json` を取得すれば端末情報も同時に確認できる。端末を別途確認する場合は `Build.MANUFACTURER`・`Build.MODEL`・`Build.VERSION.SDK_INT` を参照する（`adb shell getprop ro.product.manufacturer` 等でも取得可能）。

**【仕様推測】** OEM依存の可能性について：

確認スクリプト2回答（v2）で確認された通り、`onUnavailable()` が呼ばれるタイミングや、WiFi接続UIの挙動はOEM実装に依存する。特に以下の挙動がOEM（Samsung、OPPO等）で観測されている。

- 「接続しますか？」表示前に接続試行を開始しSupplicant状態が先行して発火する
- `onUnavailable()` が AOSP の30秒より著しく短いタイムアウトで発火する
- 複数のSupplicant状態ブロードキャストが連続して届く間隔がPixelと異なる

---

## まとめ：現時点での最有力仮説

**【コード確認＋仕様推測】** 1〜2秒での終了の原因として最も可能性が高い仮説は以下の2つ。

### 仮説1：OEM端末が「接続しますか？」表示と並行して接続試行を開始する

AOSPでは `onMatch()` 後にユーザーが OK を押してから接続試行するが、OEM実装では AP 発見と同時に Supplicant 処理を開始する可能性がある。この場合：

- 「接続しますか？」ダイアログが表示される
- **同時に**（または直前に）Supplicant状態遷移が発火する
- Phase1 が即完了し、Phase2 もWPA2ハンドシェイク失敗シーケンスで完了（1〜2秒）
- ダイアログはアプリがコールバック登録解除後に OS が閉じる

この場合の期待値：
- `supplicant_states` に ASSOCIATED または FOUR_WAY_HANDSHAKE が含まれる
- `supplicant_final_state` = `FAILED_AT_DISCONNECTED`
- `supplicant_elapsed_ms` = 1000〜2500程度

### 仮説2：前APのSupplicant状態残留がPhase1を誤トリガー

AP[k-1] の後処理中の Supplicant ブロードキャストが 300ms の待機中に届かず、AP[k] の Phase1 開始後に到達してしまう。この場合：

- `supplicant_states` が前APの遷移（FOUR_WAY_HANDSHAKE + DISCONNECTED など）を示す
- `supplicant_elapsed_ms` が 100ms 以下になる（ブロードキャスト到達が即時であるため）
- 「接続しますか？」ダイアログは AP[k] のものが表示されているが、測定データは AP[k-1] の続きのもの

### 判別方法

`pending_scans.json` を取得し、エラーになった AP エントリの `supplicant_elapsed_ms` を確認する。

- **100ms 以下** → 仮説2（前AP残留）が有力
- **1000〜2000ms** → 仮説1（OEM事前接続）が有力
- **8000ms 前後** → Phase2 タイムアウト（別の経路）
- **15000ms 前後** → `FAILED_AT_DIALOG_TIMEOUT`（Phase1 タイムアウト）

`supplicant_elapsed_ms` と `supplicant_states` の組み合わせで、ほぼ原因を特定できる。
