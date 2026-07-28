# 順次接続試行シーケンス図 ver2

Phase1/Phase2 の区別をなくし、接続試行を1本にまとめた案。

```plantuml
@startuml
box "アプリ (MainActivity)" #LightGreen
    participant "measureAllSupplicant\n〔measureAll() と measureForAp()〕" as App
    participant "BroadcastReceiver\n〔Supplicant状態受信〕" as Recv
    participant "NetworkCallback(cb)\n〔接続成否受信〕" as Cb
end box

box "Android OS" #LightBlue
    participant "ConnectivityManager" as OS
    participant "wpa_supplicant" as Sup
end box

== 停止ボタン押下時（任意のタイミングで割り込み可） ==
note over App : stopRequested = true
App -> App : supplicantContinuation.resume()\n（待機中なら即時起こす）
App -> App : continuation = null\nunregisterNetworkCallback()
note over App : 次APループ先頭で\nstopRequested チェック → CANCELLED

== 通常フロー ==

loop AP1件ごと（全AP分繰り返し）

    App -> App : buildSpecifier()\nBSSID+SSID+セキュリティでリクエスト構築
    note over App : WEP → SKIPPED で即終了\n構築失敗（BSSIDが不正等）→ SKIPPED で即終了

    App -> App : supplicantContinuation セット\n（コルーチンへの再開ボタンをフィールドに保存）
    App -> OS : requestNetwork(request, cb)
    note over App : ここで suspendCancellableCoroutine により\nコルーチンを一時停止。\n以下のいずれかが来るまでこの行で待ち続ける。
    OS -> Sup : 接続試行指示

    note over App : ── 接続試行待機（最大15秒）──\nOSからの通知 または terminal state を待つ

    alt ① OSが接続不可と判断
        OS --> Cb : onUnavailable()
        Cb -> App : continuation.resume()
        note over App : 結果 → NOT_FOUND\n→ 後片付けへ
    else ② OSが接続成功を通知
        OS --> Cb : onAvailable(network)
        Cb -> App : finalState = COMPLETED\ncontinuation.resume()
        note over App : 結果 → COMPLETED\n→ 後片付けへ
    else ③ wpa_supplicant から terminal state が届く
        loop Supplicant状態が届くたびに
            Sup --> Recv : SUPPLICANT_STATE_CHANGED_ACTION
            Recv -> App : 状態を supplicantTransitions に追記
            alt COMPLETED
                Recv -> App : continuation.resume()
                note over App : 結果 → TIMEOUT_AT_COMPLETED\n（onAvailable は来なかった）\n→ continuation.resume() でループを抜ける
            else DISCONNECTED（ASSOCIATED等を経由）
                Recv -> App : continuation.resume()
                note over App : 結果 → FAILED_AT_DISCONNECTED\n→ continuation.resume() でループを抜ける
            else その他の状態（ASSOCIATING / ASSOCIATED 等）
                note over Recv : supplicantTransitions に追記のみ\nresume しない → ループ継続
            end
        end
    else ④ 15秒どれも来ない
        note over App : 結果 → TIMEOUT_AT_最終状態\n（supplicantTransitions が空なら NOT_FOUND）\n→ 後片付けへ
    end

    note over App : ── 後片付け（全分岐がここに合流）──
    App -> App : supplicantContinuation = null\n（古い再開ボタンを捨てる）
    App -> App : supplicantCallback = null\n（古いコールバック参照を捨てる）
    App -> OS : unregisterNetworkCallback(cb)\n（OSへの接続要求を解除）
    App -> App : elapsed = 現在時刻 - startMs\n（このAPにかかった時間を計算）
    App -> App : transitions = supplicantTransitions.toList()\n（記録済みの状態遷移リストを確定）
    note over App : finalState の確定\n・onAvailable が来た → "COMPLETED" のまま\n・それ以外 → transitionsの最後の状態から決定\n　└ DISCONNECTED / INACTIVE / SCANNING → "FAILED_AT_最終状態"\n　└ それ以外（ASSOCIATED等）→ "TIMEOUT_AT_最終状態"\n　└ transitions が空 → "NOT_FOUND"
    App -> App : ap.copy(\n  supplicantStates = transitions,\n  supplicantFinalState = finalState,\n  supplicantElapsedMs = elapsed\n) を results に追加
    App -> App : delay(300ms)\n（次のAPへ移る前のインターバル）

end

App -> App : 全AP分の結果リストを返却
@enduml
```

## ver1 との主な変更点

| 項目 | ver1 | ver2 |
|---|---|---|
| フェーズ数 | Phase1（15s）+ Phase2（8s）の2段 | 1本（15s）|
| BroadcastReceiverの役割 | Phase1：最初の状態で即 resume / Phase2：terminal state で resume | terminal state のみ resume（最初のイベントで resume しない） |
| `isFirst` ロジック | 必要 | 不要 |
| `suspendCancellableCoroutine` | 2回 | 1回 |
| タイムアウト種別 | FAILED_AT_DIALOG_TIMEOUT / TIMEOUT_AT_xxx の2種 | TIMEOUT_AT_xxx の1種 |

## 補足

| 待機 | 最大時間 | 早期終了条件 |
|---|---|---|
| 接続試行 | 15秒 | onAvailable / onUnavailable / terminal state 受信 |
| AP間 | 300ms固定 | なし |

## アプリが能動的に行っていること

- `requestNetwork()` の呼び出し（接続試行のトリガー）
- タイムアウト管理
- Supplicant状態の記録

## OSが行っていること（アプリ干渉不可）

- 実際のWiFiフレーム送受信・認証処理
- `SUPPLICANT_STATE_CHANGED_ACTION` の発火タイミング
- `onAvailable()` / `onUnavailable()` の呼び出し
