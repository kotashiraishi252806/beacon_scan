# システム改変内容 ver2
## Phase1/Phase2統合（リスク評価反映版）

日付：2026-07-27  
対象ファイル：`app/src/main/java/com/example/beacon_scan/SupplicantMeasurer.kt`  
前版：`7-27-システム改変：接続試行処理の変更.md`  
リスク評価：`統合後リスク一覧.md`

---

## 1. 概要

AP1台あたりのSupplicant測定処理を、現状の**Phase1（15秒）＋Phase2（8秒）の2段構成**から**単一待機（15秒）の1本構成**へ変更する。

前版（7-27-システム改変）からの変更点は以下の1点：  
**中間の早期returnブロックを「丸ごと削除」するのではなく、`stopRequested`・`unavailableFired` のチェックをfinalState確定処理に移植する。**

これによりリスク一覧で指摘されたリスク1（停止ボタン誤記録）・リスク2（NOT_FOUND判別喪失）を解消する。

---

## 2. 変更箇所一覧

| 変更 | 場所 | 内容 | 対応リスク |
|---|---|---|---|
| ① | `supplicantReceiver` | `isFirst` ロジック削除 | - |
| ② | `measureForAp()` | Phase1/Phase2 → 単一待機に統合 | - |
| ③ | `measureForAp()` | finalState確定処理に `stopRequested`・`unavailableFired` チェックを追加 | リスク1・2 |
| ④ | ファイル先頭 | `AUTH_TIMEOUT_MS` 定数削除 | - |

---

## 3. 変更詳細

### 変更①：BroadcastReceiver の `isFirst` ロジック削除

**現状：**
```kotlin
val isFirst = supplicantTransitions.isEmpty()
supplicantTransitions.add(state.name)
val cont = supplicantContinuation ?: return
if (!cont.isActive) return
if (isFirst) {
    cont.resume(Unit)  // 最初のイベントで無条件に resume → Phase1 終了
    return
}
when (state) {
    SupplicantState.COMPLETED -> cont.resume(Unit)
    SupplicantState.DISCONNECTED -> {
        if (supplicantTransitions.any {
            it == "ASSOCIATED" || it == "FOUR_WAY_HANDSHAKE" || it == "GROUP_HANDSHAKE"
        }) cont.resume(Unit)
    }
    else -> {}
}
```

**変更後：**
```kotlin
supplicantTransitions.add(state.name)
val cont = supplicantContinuation ?: return
if (!cont.isActive) return
// isFirst ロジック削除。terminal state のみ resume
when (state) {
    SupplicantState.COMPLETED -> cont.resume(Unit)
    SupplicantState.DISCONNECTED -> {
        if (supplicantTransitions.any {
            it == "ASSOCIATED" || it == "FOUR_WAY_HANDSHAKE" || it == "GROUP_HANDSHAKE"
        }) cont.resume(Unit)
    }
    else -> {}
}
```

---

### 変更②：`measureForAp()` の待機構造を単一化

**現状（2段構成）：**
```kotlin
// Phase1：最大15秒
val phase1Completed = withTimeoutOrNull(DIALOG_TIMEOUT_MS) {
    suspendCancellableCoroutine<Unit> { cont ->
        supplicantContinuation = cont
        supplicantCallback = cb
        cont.invokeOnCancellation { ... }
        connectivityManager.requestNetwork(request, cb)
    }
}

if (phase1Completed == null) {
    // → FAILED_AT_DIALOG_TIMEOUT
    return ap.copy(supplicantFinalState = "FAILED_AT_DIALOG_TIMEOUT", ...)
}

if (finalState == "COMPLETED" || unavailableFired || stopRequested) {
    return ap.copy(...)  // ← この中間ブロックを削除するのではなく、後段に移植する
}

// Phase2：最大8秒
withTimeoutOrNull(AUTH_TIMEOUT_MS) {
    suspendCancellableCoroutine<Unit> { cont ->
        supplicantContinuation = cont
        cont.invokeOnCancellation { supplicantContinuation = null }
    }
}
```

**変更後（単一待機）：**
```kotlin
// 単一待機：最大15秒
// onAvailable / onUnavailable / terminal state のいずれかで早期終了
withTimeoutOrNull(DIALOG_TIMEOUT_MS) {
    suspendCancellableCoroutine<Unit> { cont ->
        supplicantContinuation = cont
        supplicantCallback = cb
        cont.invokeOnCancellation { ... }
        connectivityManager.requestNetwork(request, cb)
    }
}
// ↓ 後片付け＋変更③のfinalState確定処理へ続く
```

---

### 変更③：finalState確定処理（リスク1・2の対策）

**現状：**
```kotlin
// Phase2終了後の確定処理（stopRequested・unavailableFiredを参照していない）
if (finalState != "COMPLETED") {
    finalState = if (transitions.isNotEmpty()) {
        val last = transitions.last()
        if (last == "DISCONNECTED" || last == "INACTIVE" || last == "SCANNING") {
            "FAILED_AT_$last"
        } else {
            "TIMEOUT_AT_$last"
        }
    } else {
        "NOT_FOUND"
    }
}
```

**変更後：**
```kotlin
// 単一待機終了後の確定処理
// stopRequested・unavailableFired を明示的に参照する（リスク1・2対策）
finalState = when {
    stopRequested && finalState != "COMPLETED" -> "CANCELLED"
    finalState == "COMPLETED"                  -> "COMPLETED"
    unavailableFired && transitions.isEmpty()  -> "NOT_FOUND"
    transitions.isNotEmpty() -> {
        val last = transitions.last()
        if (last == "DISCONNECTED" || last == "INACTIVE" || last == "SCANNING") {
            "FAILED_AT_$last"
        } else {
            "TIMEOUT_AT_$last"
        }
    }
    else -> "NOT_FOUND"
}
```

判定の優先順位：
1. 停止ボタンが押されていた → `CANCELLED`
2. onAvailable が来た → `COMPLETED`
3. onUnavailable が来て状態遷移もなかった → `NOT_FOUND`
4. 状態遷移あり → 最終状態から `FAILED_AT_xxx` / `TIMEOUT_AT_xxx`
5. それ以外（15秒沈黙）→ `NOT_FOUND`

---

### 変更④：`AUTH_TIMEOUT_MS` 定数削除

**現状：**
```kotlin
private const val AUTH_TIMEOUT_MS = 8_000L    // Phase2用
private const val DIALOG_TIMEOUT_MS = 15_000L
```

**変更後：**
```kotlin
// AUTH_TIMEOUT_MS は削除
private const val DIALOG_TIMEOUT_MS = 15_000L  // 単一タイムアウトとして残す
```

---

## 4. リスクへの対応まとめ

| リスク | 内容 | 対応 |
|---|---|---|
| リスク1：停止ボタン誤記録 | `stopRequested` が最終ラベルに反映されなくなる | 変更③で解消 |
| リスク2：NOT_FOUND判別喪失 | `unavailableFired` が最終ラベルに反映されなくなる | 変更③で解消 |
| リスク3：待機時間増加 | 一部APの最大待機が8秒→15秒に伸びる | 設計上の意図的トレードオフとして許容 |
| リスク4：TIMEOUT_AT_COMPLETED命名問題 | 名前と実態の乖離（既存の問題） | 今回は対応しない（残課題） |

---

## 5. 残課題（今回対応しないもの）

### TIMEOUT_AT_COMPLETED 命名問題

wpa_supplicant側はCOMPLETED（認証完了）まで到達しているが、`onAvailable()` が来なかった場合に "TIMEOUT_AT_COMPLETED" というラベルが付く。実態はタイムアウトではなく「認証は完了したがOSの最終確定通知が来なかった」状態であり、名前と実態が食い違っている。

実測データ（7/24）では4件確認されている（elapsed 約1.2〜1.4秒、8秒・15秒タイムアウト境界ではないことからも真のタイムアウトでないことが確認できる）。

対応案：最終ラベル決定で `last == "COMPLETED"` の場合を専用分岐にして "AUTH_COMPLETED_NO_CONFIRMATION" などの別名を付ける。ただし今回の統合変更とは独立した問題のため、別タスクとして扱う。

---

## 6. 実装手順

1. `supplicantReceiver` の `isFirst` ロジックを削除（変更①）
2. `measureForAp()` の Phase2ブロック（`withTimeoutOrNull(AUTH_TIMEOUT_MS)`）を削除（変更②）
3. Phase1の `withTimeoutOrNull` を単一待機として残す（変更②）
4. Phase1タイムアウト時の早期return（`FAILED_AT_DIALOG_TIMEOUT`）を削除（変更②）
5. Phase1/Phase2間の中間ブロックを削除（変更②）
6. 後片付け後のfinalState確定処理を変更③の内容に書き換える
7. `AUTH_TIMEOUT_MS` 定数を削除（変更④）
8. 実機テスト：停止ボタンを押した場合にCANCELLEDが記録されることを確認
