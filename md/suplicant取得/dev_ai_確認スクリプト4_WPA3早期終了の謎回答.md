# 確認スクリプト4 回答：WPA3が1〜2秒でFAILED_AT_DISCONNECTEDになる矛盾の解消

## 前置き：回答の分類

- **【コード確認】** `MainActivity.kt` のコードから断定できる事実
- **【技術推測】** WPA3/SAEの仕様・Androidの実装から推測されるが実機未確認
- **【結論】** 矛盾を解消する最終回答

---

## 確認1：Phase2の`DISCONNECTED`早期resume条件の読解に誤りはないか

**【コード確認】** 読解は完全に正しい。

`supplicantReceiver.onReceive()` のPhase2判定（266〜269行）：

```kotlin
SupplicantState.DISCONNECTED -> {
    if (supplicantTransitions.any {
        it == "ASSOCIATED" || it == "FOUR_WAY_HANDSHAKE" || it == "GROUP_HANDSHAKE"
    }) cont.resume(Unit)
}
```

今回の3件はいずれも`supplicantTransitions`に`ASSOCIATED`・`FOUR_WAY_HANDSHAKE`・`GROUP_HANDSHAKE`のいずれも含まれない。したがって`DISCONNECTED`が何度来てもこの分岐は`cont.resume(Unit)`を呼ばない。**この読解は正しい。**

---

## 確認2：8秒待たずに終わる別経路が存在するか ← これが矛盾の答え

**【コード確認】** 存在する。**`cb.onUnavailable()` がPhase2中に発火する経路**が完全に見落とされていた。

### 経路の詳細追跡

#### Phase1がreceiverのisFirst分岐で完了するとき

`supplicantReceiver.onReceive()` の257〜260行：

```kotlin
if (isFirst) {
    cont.resume(Unit)   // Phase1のcontinuationをresumeする
    return              // ← ここで終わり。supplicantContinuationはクリアしない
}
```

**重要**：この `cont.resume(Unit)` はPhase1のcontinuation（`cont1`）を再開させるが、`supplicantContinuation` フィールドを `null` にも別のものにも書き換えない。receiverはただ `cont.resume(Unit)` して `return` するだけである。

Phase1の `suspendCancellableCoroutine` ブロック（787〜796行）：

```kotlin
suspendCancellableCoroutine<Unit> { cont ->
    supplicantContinuation = cont     // ← cont1 をセット
    supplicantCallback = cb
    cont.invokeOnCancellation {       // ← キャンセル時のみ実行。resume時は実行されない
        supplicantContinuation = null
        supplicantCallback = null
        runCatching { connectivityManager.unregisterNetworkCallback(cb) }
    }
    connectivityManager.requestNetwork(request, cb)
}
```

`invokeOnCancellation` は `cont.cancel()` が呼ばれた時のみ実行される。`cont.resume(Unit)` では実行されない。したがってPhase1がreceiverのisFirst分岐で完了したとき：

- `supplicantContinuation` = `cont1`（Phase1のcont。非アクティブだが null ではない）
- `supplicantCallback` = `cb`（**ConnectivityManagerへの登録は維持されたまま**）

#### Phase2開始時

Phase2の `suspendCancellableCoroutine`（823〜826行）：

```kotlin
suspendCancellableCoroutine<Unit> { cont ->
    supplicantContinuation = cont    // ← cont2 に上書き
    cont.invokeOnCancellation { supplicantContinuation = null }
}
```

`supplicantContinuation` が `cont2`（Phase2のcont、アクティブ）に上書きされる。

#### `cb.onUnavailable()` がPhase2中に発火する

`cb.onUnavailable()`（776〜781行）：

```kotlin
override fun onUnavailable() {
    supplicantContinuation?.let { if (it.isActive) it.resume(Unit) }
    // ↑ Phase2中であれば cont2 がアクティブ → Phase2が即座にresumeされる！
    supplicantContinuation = null
    supplicantCallback = null
    runCatching { connectivityManager.unregisterNetworkCallback(this) }
}
```

`cb` はPhase1で `requestNetwork(request, cb)` によってConnectivityManagerに登録された状態のまま、Phase2まで維持される。OSが「この接続要求は満たせない」と判断して `onUnavailable()` を呼ぶと、現在の `supplicantContinuation`（Phase2の `cont2`）が `resume` され、Phase2が8秒タイムアウト前に終了する。

---

### WPA3の3件のタイムライン（推定）

```
t=0ms    : measureSupplicantForAp() 開始、requestNetwork() 呼び出し
t=~100ms : INTERFACE_DISABLED 受信 → isFirst=true → Phase1 resume
           （supplicantContinuationはcont1のまま、supplicantCallbackはcbのまま）
t=~100ms : Phase2開始、supplicantContinuation = cont2
t=~200ms : DISCONNECTED 受信 → ASSOCIATED等なし → resumeせず
t=~600ms : ASSOCIATING 受信 → resumeせず
t=~800ms : DISCONNECTED 受信 → ASSOCIATED等なし → resumeせず
t=~1200ms: ASSOCIATING 受信 → resumeせず（2回目のSAE試行）
t=~1600ms: DISCONNECTED 受信 → ASSOCIATED等なし → resumeせず
t=~1700ms: onUnavailable() 発火 → cont2.resume(Unit) → Phase2 終了！
           elapsed = ~1700ms → supplicant_elapsed_ms = 1657 ✓

最終処理:
  transitions.last() = "DISCONNECTED"
  → "FAILED_AT_DISCONNECTED" ✓
```

**矛盾の解消**：Phase2を終了させているのはreceiverの`DISCONNECTED`判定ではなく、`cb.onUnavailable()` である。`cb` はPhase1経由（receiver）で完了したときにunregisterされずPhase2まで維持されているため、OSがWPA3接続断念を通知する `onUnavailable()` がPhase2のcontinuationに届く。

---

## 確認3：前APの残留ブロードキャストの可能性

**【コード確認 + 技術推測】** この3件については残留ブロードキャスト仮説は可能性が低い。

### 根拠

前APの`supplicant_elapsed_ms`と比較する。

| WPA3 AP | 前APの elapsed_ms | 前APの final_state |
|---------|-------------------|--------------------|
| 0000mlab（WPA3, 1657ms） | 8153ms | TIMEOUT_AT_FOUR_WAY_HANDSHAKE |
| Mlab_ST5-WPA3（WPA3, 2305ms） | 8139ms | TIMEOUT_AT_FOUR_WAY_HANDSHAKE |
| MLAB-TECH01-5-3（WPA3, 1851ms） | 3611ms | TIMEOUT_AT_COMPLETED |

前APはいずれも数秒〜8秒以上かかっている。これは前AP内でPhase2のタイムアウトが満了するか、Supplicant状態遷移が完了するまで待ったことを意味する。その後 `unregisterNetworkCallback(cb_prev)` が呼ばれ、`delay(300)` を経てWPA3 APの測定が始まる。

**前APの測定中（数秒〜8秒）に前APに関するSupplicant状態遷移は十分到達・処理済みである。** その後の後続ブロードキャストがあるとすれば主に「切断後のDISCONNECTED」であるが、WPA3 APの `supplicantTransitions` に含まれる `INTERFACE_DISABLED` はDISCONNECTEDとは異なる。

### INTERFACE_DISABLED が前APの残留である可能性

否定はできないが、低確率と判断する。理由：

1. **3件すべてに共通して登場する**（前APはそれぞれ異なる）。前APが異なるにもかかわらず常に同じ状態が先頭に来るのは、WPA3測定固有のパターンである可能性が高い
2. INTERFACE_DISABLED は WiFi インターフェースが一時的に無効化されたことを示す。前AP（WPA2）の測定後に WiFi がいったん無効化・再有効化されるのは、OEM 実装が WPA3（SAE）接続のために WiFi スタックを再初期化する動作である可能性がある
3. INTERFACE_DISABLED の後に続く `DISCONNECTED → ASSOCIATING → DISCONNECTED → ASSOCIATING → DISCONNECTED` のパターンが3件で完全に一致しており、これはWPA3 SAEの失敗シーケンスとして技術的に整合している（次項参照）

---

## 確認4：WPA3（SAE）がASSOCIATEDに至らず高速に失敗する技術的妥当性

**【技術推測】** 技術的に完全に妥当である。

### WPA2（PSK）とWPA3（SAE）の認証シーケンスの違い

| フェーズ | WPA2-PSK | WPA3-SAE |
|---------|----------|----------|
| スキャン | ✓ | ✓ |
| 認証フレーム交換 | なし（Open認証） | **SAE Commit / SAE Confirm 交換**（ここでパスフレーズ検証） |
| アソシエーション | ASSOCIATING → **ASSOCIATED** | SAE成功後のみ ASSOCIATED |
| 4-Wayハンドシェイク | ASSOCIATED後に FOUR_WAY_HANDSHAKE | ASSOCIATED後に実施 |
| パスフレーズ失敗の発生点 | **ASSOCIATED後**（FOUR_WAY_HANDSHAKE中） | **ASSOCIATED前**（SAE交換中） |

WPA2の場合、ダミーパスフレーズを使っても一旦 ASSOCIATED まで進み、FOUR_WAY_HANDSHAKE で失敗する（WPA2の観測データ `TIMEOUT_AT_FOUR_WAY_HANDSHAKE` と一致）。

WPA3の場合、SAE（Simultaneous Authentication of Equals）は**楕円曲線Diffie-Hellman鍵交換**を用いた認証であり、パスフレーズが一致しない場合はSAE Commit/Confirm の交換段階で失敗する。このフェーズはアソシエーション前であるため、Supplicant状態は `ASSOCIATING`（アソシエーション試行）→ `DISCONNECTED`（SAE失敗でアソシエーション拒否）の往復になり、**`ASSOCIATED` には永遠に到達しない**。

### 「2回のASSOCIATING → DISCONNECTED」の意味

WPA3 AP はSAE失敗時に数回の再試行を許容することがある。観測された：

```
ASSOCIATING → DISCONNECTED → ASSOCIATING → DISCONNECTED
```

はSAEのコミット交換を2回試みて両方失敗したことを意味する。2回失敗するとAndroid側の `WifiNetworkFactory` または AP 側がこれ以上の試行を断念し、`onUnavailable()` を発火させる。これが1〜2秒で完了するのは技術的に自然（SAEの1往復は無線フレーム数回分で数百ms程度）。

### `ASSOCIATED` が含まれないことの確定的な意味

今回の3件の `supplicant_states` に `ASSOCIATED` が含まれないことは、**WPA3 SAEがパスフレーズ不一致によりアソシエーション前段階で正常に失敗した**ことを示す。これはバグではなく正常な動作である。

---

## 全体まとめ

### 矛盾の完全解消

| 疑問 | 答え |
|------|------|
| Phase2のDISCONNECTED判定は読解通りか | 正しい。ASSOCIATED等がなければresumeしない |
| なぜ8秒待たずに終わるか | Phase1を `cb` ではなくreceiverのisFirst分岐で完了した場合、`cb.onUnavailable()` がPhase2まで有効なまま残る。WPA3のSAE失敗でOSが `onUnavailable()` を呼ぶとPhase2のcontinuationがresumeされる |
| FAILED_AT_DISCONNECTED になる理由 | onUnavailable() でPhase2が終了しても `finalState` は "NOT_FOUND" のまま。最終的に `transitions.last() == "DISCONNECTED"` → `"FAILED_AT_DISCONNECTED"` |
| 前APの残留ブロードキャストか | 可能性は低い。3件でパターンが一致するのはWPA3固有の動作を示す |
| WPA3がASSOCIATEDに達しない理由 | SAE認証はアソシエーション前フェーズで行われ、パスフレーズ不一致時はアソシエーション前に失敗する |

### この動作はバグか仕様か

**動作自体は正確な計測結果を返している。** WPA3 AP に対してダミーパスフレーズで試行し、1〜2秒でSAE失敗が確定したことが正しく記録されている。ただし以下の点が暗黙の設計上の問題として残る。

- **`cb.onUnavailable()` のPhase2への流入がコメントで明示されていない**：Phase2のコードブロックは「8秒待つ」としか読めないが、実際には `cb` 経由でPhase1のコールバックが干渉する構造になっている。コードの見通しが悪く、今回のような読解の混乱が起きた原因でもある
- WPA3専用の判定（SAE失敗時の早期終了）が偶発的に `onUnavailable()` 経路で実現されており、意図的な設計か副産物かが不明確

### 今後の実装検討

WPA3の計測結果として `FAILED_AT_DISCONNECTED` が適切かを再検討する余地がある。SAE失敗による早期終了をより明示的に区別したい場合、`onUnavailable()` 発火時に `ASSOCIATED` が遷移内にあるかで分岐し、ない場合は `FAILED_BEFORE_ASSOCIATED`（またはWPA3向けに `SAE_AUTH_FAILED`）のような専用の `finalState` を返すことが考えられる。ただし現状の `FAILED_AT_DISCONNECTED` でも情報量として問題はない。
