# Supplicant測定設計考察

CLAUDE.mdの設計概要を補完する詳細考察。実測データ（52AP・約5分間測定）に基づく。

---

## アプリが制御できることとできないこと

### アプリが能動的に行えること（唯一の制御）
- `requestNetwork(request, cb)` の呼び出し（OSへの接続依頼トリガー）
- タイムアウト管理（Phase1: 15s、Phase2: 8s）
- Supplicant状態の記録

### OSが行うこと（アプリ干渉不可）
- ダイアログ表示・非表示の判断
- 実際のWiFiフレーム送受信・認証処理
- `SUPPLICANT_STATE_CHANGED_ACTION` の発火タイミング
- `onAvailable()` / `onUnavailable()` の呼び出し

---

## Phase1（15秒）の設計意図と定義

### 目的
「OSがrequestNetwork()の依頼に対して動き出したか」を確認する。

### タイムアウト条件の定義
> BroadcastReceiverにSupplicantStateの状態値が届かず、かつNetworkCallback(cb)のonUnavailable()も呼ばれなかった状態が15秒継続した場合。

`withTimeoutOrNull(DIALOG_TIMEOUT_MS)` が `null` を返すことで判定。nullはOSから来るのではなくKotlinランタイムが自動セットする。

### 当初設計との乖離
当初の想定：「Phase1 = ユーザーがダイアログのOKを押すまで待つ」  
実態：ダイアログはOSが表示するかしないかを決定する。ユーザー操作なしにSupplicant状態が発火するケースが多数存在する。

---

## Phase2（8秒）の設計意図

### 目的
「接続試行の結果（成功・失敗）」を確認する。Supplicant状態遷移の全列を記録するために必要。

### Phase2がない場合の問題
最初のSupplicant状態受信後に即次のAPへ移ると、認証シーケンス（ASSOCIATING→ASSOCIATED→FOUR_WAY_HANDSHAKE→COMPLETED等）が記録できない。

### 2フェーズ設計の課題
「Phase1 = ダイアログ待ち」「Phase2 = 認証待ち」という区分けの前提が崩れているため、実態に即した設計としては**単一タイムアウト＋終端状態での早期終了**への統合が考えられる。

---

## 実測データから判明したUIパターンとsupplicant_statesの対応

測定環境：屋内（鉄筋コンクリート）、52AP、約5分、Android API 30 (moto g31(w))

### UIパターン定義
| 番号 | 表示内容 |
|------|--------|
| 1 | 「デバイスを検索しています」 |
| 2-1 | 「デバイスに接続」確認ダイアログ（接続ボタンあり） |
| 2-2 | 「デバイスに接続」確認ダイアログ（約0.1秒で消える） |
| 3-1 | 「デバイスに接続しています」 |
| 3-2 | 「エラーが発生しました。アプリはデバイス選択リクエストをキャンセルしました。」 |
| 4 | 「Wi-Fiネットワークに接続できませんでした"SSID"」 |

### パターンとfinal_stateの対応
| UIパターン | 件数 | supplicant_final_state | 備考 |
|-----------|------|----------------------|------|
| J（何も表示なし） | 約22件 | 混在 | 最多・最も多様 |
| I（4のみ） | 15件 | 主にFAILED_AT_DISCONNECTED | FOUR_WAY_HANDSHAKE後に失敗 |
| A（1→2-2→3-2） | 4件 | 全てTIMEOUT_AT_COMPLETED | WPA2/WPA3遷移またはEAP |
| B（1→2-1→3-1→3-2） | 4件 | FAILED_AT_DISCONNECTEDまたはTIMEOUT_AT_FOUR_WAY_HANDSHAKE | |
| F（1→3-2） | 3件 | TIMEOUT_AT_COMPLETEDまたはFAILED_AT_DIALOG_TIMEOUT | |
| E（2-1→3-1→3-2） | 2件 | FAILED_AT_DISCONNECTEDまたはFAILED_AT_SCANNING | |
| D（1→2-1→3-1→4） | 1件 | FAILED_AT_DISCONNECTED | WPA3-SAE |
| H（1→3-1→3-2） | 1件 | TIMEOUT_AT_COMPLETED（1432ms、非常に速い） | EAP |

### FAILED_AT_DIALOG_TIMEOUT（OSが動き出さなかったケース）5件
| BSSID | SSID | elapsed |
|-------|------|---------|
| e8:48:b8:a0:23:31 | FujitaLab_ROS | 15012ms |
| b0:1f:8c:7f:ad:c1 | 0000tohtech | 15009ms |
| 84:e8:cb:3d:90:1a | Buffalo-5G-9010-WPA3 | 15009ms |
| 84:e8:cb:3d:90:18 | []（SSIDなし） | 15014ms |
| d8:07:b6:8f:96:cb | TP-Link_96CC_5G | 15007ms |

supplicant_statesが全て空。BroadcastReceiverにもcbにも15秒間何も届かなかった。

### WPA3-SAEの傾向
ASSOCIATINGで詰まるケースが多い（`TIMEOUT_AT_ASSOCIATING` または ASSOCIATING→DISCONNECTED繰り返し）。

---

## requestNetwork()の受け皿構造

アプリはrequestNetwork()呼び出し前に2つの窓口を事前設置する。

| 窓口 | 受け取るもの | 役割 |
|------|------------|------|
| BroadcastReceiver（supplicantReceiver） | SupplicantState状態値 | 接続過程の逐次記録 |
| NetworkCallback（cb） | onAvailable() / onUnavailable() | 接続成否の通知 |

OSはこの2つの窓口を通じて結果を返すことがAndroid仕様として保証されている。「何が来るかは予測できないが、どこに来るかは決まっている」構造。
