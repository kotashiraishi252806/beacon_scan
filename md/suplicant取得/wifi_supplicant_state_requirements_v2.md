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

### 3.2 逐次接続試行フェーズ（詳細）

#### 3.2.1 全体アルゴリズム（疑似コード）
```
for i in 1..n:
    log(AP_i, state="DISCONNECTED", t=now())
    request = build_network_request(ssid=AP_i.ssid, bssid=AP_i.bssid)
    start_timer()
    result = connectivityManager.requestNetwork(request, callback, timeoutMs=5000)
    // callback.onAvailable()   -> ASSOCIATED相当（疎通確立）
    // callback.onUnavailable() -> 「失敗」（3.2.4参照）
    // callback.onLosing()/onLost() -> 切断検知
    record_all_transitions(AP_i)  // 遷移イベントごとにtimestamp付きで記録
    connectivityManager.unregisterNetworkCallback(callback)  // 切断して次へ
```

#### 3.2.2 使用API（Android想定）
- `WifiNetworkSpecifier.Builder().setSsid(...).setBssid(...).build()`
- `NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).setNetworkSpecifier(specifier).build()`
- `ConnectivityManager.requestNetwork(request, networkCallback, timeoutMs)`
- コールバック: `onAvailable()`（成功）／`onUnavailable()`（失敗・タイムアウト）／`onLosing()`／`onLost()`（切断）

#### 3.2.3 必要パーミッション
- `ACCESS_FINE_LOCATION`（Wi-Fiスキャン・接続情報の取得に必須）
- `CHANGE_WIFI_STATE`, `ACCESS_WIFI_STATE`
- `NEARBY_WIFI_DEVICES`（Android 13以降、位置情報権限なしでWi-Fi操作する場合の代替）
- `AndroidManifest.xml`への宣言に加え、実行時権限リクエストの実装が必要。

#### 3.2.4 「失敗」の定義（再掲・詳細化）
タイムアウト内にASSOCIATED（=`onAvailable()`）へ到達できなかったこと全般。内訳は以下を区別して記録すること。
- 認証拒否（パスワード誤り等）
- APからの応答なし（電波状況悪化等）
- タイムアウト（既定5秒、要調整）
- ユーザーがシステムダイアログを拒否/無視した場合（3.2.5参照）

#### 3.2.5 【重要】ユーザー承認ダイアログに関する制約
- 一般アプリが`WifiNetworkSpecifier`で未承認の新規SSIDへ接続要求すると、**初回は必ずシステム確認ダイアログが表示され、人間の手動承認が必要**（プログラムだけでは完結しない）。
- 同一アプリが同一SSID/BSSIDへ再度specific requestを行う場合、一度承認済みなら自動接続される（ダイアログは省略される）。
- したがって、未承認AP群を含む測定では、**1AP試行ごとにユーザーがダイアログを操作する前提のUIフロー**を組む必要がある。非機能要件（4章）の時間見積もり（0.1〜数秒/AP）は、この人手介在時間を含んでいない点に注意。
- 完全無人化（ダイアログなしで全AP自動試行）が必要な場合は、Device Owner（MDM/エンタープライズ管理下デバイス）権限の取得、または端末のroot化が前提となり、通常の一般アプリ権限では実現不可能。

#### 3.2.6 開発AIが行うべきタスク（サマリ）
1. Android向けに`ConnectivityManager` + `WifiNetworkSpecifier`を用いた逐次接続試行ロジックを実装する。
2. 各AP試行についてタイムスタンプ付き状態遷移ログ（3.3のデータ構造）を記録する処理を実装する。
3. 未承認SSIDへの接続時にシステムダイアログが挟まることを前提としたUIフロー（進捗表示、承認待ち状態の表示等）を実装する。
4. `WifiInfo.getSupplicantState()`等で AUTHENTICATING/ASSOCIATING の粒度が実機で取得可能か検証する（3.2.7参照、未検証事項）。取得不可の場合は`NetworkCallback`イベントを代替指標とした簡略状態モデルに切り替える。
5. タイムアウト値・リトライ有無を設定可能にする（6章の確認事項）。

#### 3.2.7 【未検証・要確認】supplicant state取得の技術的リスク
Android 9以降、Wi-Fi関連の詳細情報取得APIの一部はプライバシー保護のため値が制限される場合があると言われているが、`
getSupplicantState()`が対象APIレベル・権限状況でどこまで細かい遷移（AUTHENTICATING/ASSOCIATING等）を返すかは、
本ドキュメント作成時点で公式ドキュメントから断定できていない。
開発AIは実機検証を行い、取得不可の場合は代替の簡略状態モデル（DISCONNECTED/接続試行中/ASSOCIATED/失敗の4値程度）へ設計変更すること。

### 3.3 ロギング・出力
- AP毎に以下を記録:
  - 最終状態（ASSOCIATED / DISCONNECTED / タイムアウト等）
  - 状態遷移の履歴（state, timestamp のリスト）
  - 試行開始〜終了までの所要時間
  - （3.2.4を受けて）失敗した場合はその内訳（認証拒否／無応答／タイムアウト／ダイアログ未承認）
- 全AP試行完了後、一覧表形式で出力（AP1: ASSOCIATED, AP2: DISCONNECTED, …）。

## 4. 非機能要件（時間見積もり）
- 1AP試行あたりの所要時間は概算0.1〜数秒（認証方式やEAPの有無で変動）。**ただし3.2.5の通り、未承認SSIDが多い場合は人の操作時間が支配的になり、この見積もりは大幅に超過し得る。**
- 全体所要時間 t は検出台数 n にほぼ比例（O(n)）。
- 目安: t ≈ n ×（0.1〜数秒）（全AP承認済み、かつダイアログ不要な場合のみ成立）
- 例: n=100 の場合、t は概ね10〜100秒台のオーダーと推測（タイムアウトや再試行、ダイアログ承認待ちが絡むと数百秒〜それ以上に伸びる可能性あり）。

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
      "failure_reason": null,
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

## 6. FAILED_AT_DIALOG_TIMEOUT の動作詳細（実装解析）

> **本章の記述は「コードから断定できる事実」「外部仕様に基づく推測」「OS内部実装依存で断定不能」の3分類を明示する。**
> v2.1 更新：別AIレビューによる3点の指摘（6.5参照）を反映。

### 6.1 Phase1（15秒間）で待っているコールバック

`measureSupplicantForAp()` の Phase1 は `withTimeoutOrNull(DIALOG_TIMEOUT_MS)` で包まれた `suspendCancellableCoroutine` であり、以下の **4経路のいずれか** が来るまで停止する（要するに、アプリがOSに「このAP（スキャンで検出したAP1~APn）に接続してみて」とOSにお願いする。その間andoridOSから返事がくるまで次のAP接続処理は行わないという意味）。すべて **コードから断定できる事実**。

**A. `SUPPLICANT_STATE_CHANGED_ACTION` BroadcastReceiver（最初の受信）**（253–261行）

`supplicantTransitions` が空のとき最初の Supplicant 状態通知が届いた瞬間に resume する。状態の種類は問わない。

**B. `NetworkCallback.onUnavailable()`**（776–780行）

OS が「ネットワーク要求を満たせない」と判断したときに呼ばれる。`finalState` は `"NOT_FOUND"` のまま変わらないため resume 後に Phase2 へ進む。

**C. `NetworkCallback.onAvailable()`**（769–774行）

OS が要求したネットワークへの接続確立を通知したときに呼ばれる。このコールバック内で `finalState = "COMPLETED"` が先にセットされ、その後 continuation を resume する。resume 後は line 811 の分岐（`if (finalState == "COMPLETED")`）で即 return し、Phase2 をスキップする。

- **観測①（認証ダイアログが表示されず自動で接続完了するSSID）との対応**：`onAvailable()` が Supplicant ブロードキャストより先に発火した場合、この経路をたどって Phase2 をスキップしたまま `COMPLETED` で終了する。「発火順序」は外部仕様依存（後述 6.3）。

**D. 15秒タイムアウト**（786行、`withTimeoutOrNull`）

A・B・C のいずれも来なかった場合、`withTimeoutOrNull` が `null` を返し `FAILED_AT_DIALOG_TIMEOUT` として即リターン。

### 6.2 「ビーコンフレームの直接受信」と「OSからのコールバック通知」は別段階の処理

この2つは**別物**。Phase1 はビーコンフレームの受信を直接判定していない（受信した情報をOSが処理した内容をアプリが見ている）。

アプリが待っているのは「WPA Supplicant デーモンの状態機械が最初に何らかの遷移を起こした」という OS からの通知（`SUPPLICANT_STATE_CHANGED_ACTION`）である。

ビーコンフレームの受信はその遥か手前、Wi-Fi ドライバ・ファームウェアレベルの処理であり、アプリからは不可視。Supplicant が状態遷移を報告してくるのは、ドライバがスキャン・アソシエーション処理を試み始めた後の出来事。

### 6.3 3分類による記述

#### コードから断定できる事実

- `connectivityManager.requestNetwork(request, cb)` を呼んだ時点で、OS の ConnectivityService に「この BSSID・SSID・セキュリティ種別に合致する Wi-Fi ネットワークを確立せよ」という要求が登録される。
- Phase1 を完了させる経路は 6.1 に示した A・B・C・D の4つである。
- `onAvailable()` が発火すると `finalState = "COMPLETED"` がセットされ、Phase1 resume 後の line 811 分岐で Phase2 をスキップして即 return する。
- `isProcessingResults` フラグは `onScanResultsReady()` への**再入防止フラグ**である（line 944–945 で `true` にセット、line 1014 でコルーチン末尾に `false` にリセット）。

#### 外部仕様（Android 公式ドキュメント）に基づく推測

- `WifiNetworkSpecifier` 経由の `requestNetwork()` を呼ぶと、Android 10 以降でシステム確認 UI がユーザーに表示される。これは Android の公式 API 仕様に基づく推測であり、`MainActivity.kt` のコード内にダイアログの表示・検知・制御を行っている箇所は存在しない。
  - **観測③（「接続しますか？」が表示されず「デバイスを検索しています…」でエラーになる）** は、システム UI の表示内容がデバイス・Android バージョンによって異なりうることと整合する傍証である。
- `onAvailable()` が Supplicant ブロードキャストより先に発火する場合がある（例：過去に承認済みで自動接続されるSSID）という挙動は、Android の接続フロー仕様から推測されるが、コードから直接確認できる事実ではない。

#### OS 内部実装依存で断定不能

- Phase1 待機中に OS が背後で AP 再スキャンを実施するかどうかは `WifiNetworkFactory` 等の Android OS 内部実装に依存しており、本コードからは断定できない。`isProcessingResults` フラグは仮に再スキャンが起きたとしても `onScanResultsReady()` の二重実行を防ぐための再入防止措置であり、OS が再スキャンを行うことの根拠にはならない。
- ダイアログが表示されている間に OS が `onUnavailable()` を呼ぶ条件およびタイミングは OS 内部の判断であり断定不能。コードのコメントに「OS がダイアログを強制キャンセルして `onUnavailable()` も呼ばれないケースに備え」とあるように、`onUnavailable()` が来ないケースが現実に存在することは確認済みだが、その条件は不明。
- システム UI の表示内容・操作体験（ダイアログの種類・タイミング・デバイスによる差異）はすべて OS 内部実装依存で断定不能。

### 6.4 「見つからない」の最も正確な表現

`FAILED_AT_DIALOG_TIMEOUT` が意味するのは：

> **「Android OS に対してこの AP への接続を15秒間要求し続けたが、WPA Supplicant が一度も状態遷移を報告せず、かつ OS も要求不可能（`onUnavailable`）を通知してこなかった」**

「AP が存在しない」「電波が届いていない」とは断言できない。OS がシステム UI を出したまま内部処理を放置した可能性、OS が応答を返さなかった実装上の挙動である可能性を排除できない。**アプリ側が能動的に確認した「不在」ではなく、OS が沈黙し続けた結果としてのタイムアウト**が正確な意味。

### 6.5 本章の改訂履歴（別AIレビューによる指摘）

旧 6.1 では Phase1 の完了経路を「ブロードキャスト最初の1件」「`onUnavailable()`」「15秒タイムアウト」の3つとしていたが、**`onAvailable()` による第4の経路（現 6.1-C）が漏れていた**。

旧 6.3 で「OS はシステムダイアログをユーザーに表示する」を"コードから断定できる範囲"に分類していたが、**コード内にダイアログの表示・検知・制御を行う箇所は存在しない**ため「外部仕様に基づく推測」に分類し直した（現 6.3）。

旧 6.3 で「`isProcessingResults` フラグが OS 再スキャンを防いでいる」と記述していたが、このフラグは**再入防止フラグ**であり OS 再スキャンの有無とは別論点である。Phase1 待機中の OS 再スキャンの有無はコードから断定できず「OS 内部実装依存」に分類し直した（現 6.3）。

## 7. 今後の確認事項
- 対象OS（Android/iOS/両方）
- タイムアウト値の具体的な設定
- リトライの要否
- 認証方式（オープン/WPA2-PSK/WPA2-Enterprise等）による所要時間差の扱い
- **未承認SSIDへの接続確認ダイアログをどう運用するか（都度ユーザー操作を前提とするか、Device Owner/root化で無人化するか）**
- **`getSupplicantState()`で細かい状態遷移が実機取得可能かの検証結果**
