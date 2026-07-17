# 確認スクリプト2 回答：Phase1タイムアウト起点調査

## 前置き：回答の性質について

本回答の各項目は以下の3種類に分類して記述する。

- **【コード確認】** `MainActivity.kt` のコードから断定できる事実
- **【仕様推測】** Android 公式ドキュメント・API 仕様から推測されるが実機未確認
- **【要実測】** コードおよび仕様からは断定できず、実機ログ取得が必要

---

## 確認1：フォーカス変化と各パターンのタイミングの実測

### 実測結果

**【要実測】** 現在のコードにタイムスタンプログが存在しないため、実測値を提供できない。以下にログ取得用のデバッグコード案を示す（ユーザー側での実行が必要）。

```kotlin
// MainActivity.kt に追加するデバッグログ

override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    Log.d("PhaseTimer", "onWindowFocusChanged: hasFocus=$hasFocus t=${System.currentTimeMillis()}")
}

override fun onPause() {
    super.onPause()
    Log.d("PhaseTimer", "onPause t=${System.currentTimeMillis()}")
}

override fun onResume() {
    super.onResume()
    Log.d("PhaseTimer", "onResume t=${System.currentTimeMillis()}")
}

// measureSupplicantForAp() 内の connectivityManager.requestNetwork() 直前に追加
Log.d("PhaseTimer", "requestNetwork called t=${System.currentTimeMillis()} bssid=${ap.bssid}")

// supplicantReceiver の onReceive 内、isFirst==true の resume 直前に追加
Log.d("PhaseTimer", "Phase1 completed via broadcast isFirst=true state=${state.name} t=${System.currentTimeMillis()}")

// onAvailable() 内の resume 直前に追加
Log.d("PhaseTimer", "Phase1 completed via onAvailable t=${System.currentTimeMillis()}")

// onUnavailable() 内の resume 直前に追加
Log.d("PhaseTimer", "Phase1 completed via onUnavailable t=${System.currentTimeMillis()}")
```

フィルタコマンド：`adb logcat -s PhaseTimer`

### そこから読み取れること（仮説）

**【仕様推測】** 各パターンについての予測：

| パターン | 予測される完了経路 | フォーカス変化の有無 |
|----------|-------------------|---------------------|
| ① 自動接続完了 | `onAvailable()` が即時発火（前回承認済みのため） | フォーカス変化なし（ダイアログ非表示） |
| ② 確認→操作→エラー | ダイアログ表示で `onWindowFocusChanged(false)` → OK 押下後にブロードキャスト最初の1件 | あり |
| ③ 検索中→エラー | ダイアログ表示で `onWindowFocusChanged(false)` → 自動タイムアウト後に `onUnavailable()` または15秒タイムアウト | あり（ただし自動終了） |

---

## 確認2：パターン③はユーザー操作を必要とする画面か、完全自動進行の画面か

### 実測結果

**【要実測】** 実際に画面に触れずに放置する試験が必要。

### そこから読み取れること

**【仕様推測】** パターン③（「デバイスを検索しています…」）は **ユーザー操作不要の完全自動進行** と推測される。

根拠：
- `WifiNetworkSpecifier` で指定した BSSID の AP を OS が見つけられない場合、OS は内部的にスキャンを繰り返し、一定時間後に自動で `onUnavailable()` を呼ぶ動作が Android ドキュメントに記載されている
- 「デバイスを検索しています…」は OS のスキャン中 UI であり、ユーザーが触れる必要のあるボタンは存在しない

**この前提が正しければ**：パターン③のケースでは、15秒タイムアウトの「ユーザー操作時間のばらつき」問題は発生しない（人間の介在がないため）。タイムアウト問題が顕在化するのは主にパターン②のケースに限られる。

---

## 確認3：システムUIは別Activityか、オーバーレイか

### 実測結果

**【要実測】** 以下のコマンドで確認可能。

```bash
# ダイアログ表示中に実行
adb shell dumpsys window windows | grep -E "Window #|mOwnerUid|package"

# または Activity スタックの確認
adb shell dumpsys activity activities | grep -E "TaskRecord|ActivityRecord"

# logcat でフォーカス変化を確認
adb logcat -s ActivityTaskManager WindowManager | grep -E "Focus|Pause|Resume"
```

### そこから読み取れること

**【仕様推測】** システム UI は **オーバーレイウィンドウ（別 Activity ではない）** と推測される。

根拠：
- Android 10 以降の `WifiNetworkSpecifier` 接続 UI は、`com.android.settings` または `android` システムパッケージが表示する `TYPE_APPLICATION_OVERLAY` に近いシステムウィンドウとして実装されており、アプリの Activity を置き換えるものではない
- これにより、アプリの `onPause()` は **呼ばれず**、`onWindowFocusChanged(false)` が **呼ばれる** と予測される

**実装への影響**：
- `onWindowFocusChanged(hasFocus: Boolean)` を起点として「ダイアログ表示中かどうか」を判定できる可能性がある
- ただし OEM カスタム ROM では `onPause()` が呼ばれるケースも報告されており、両方を拾うのが安全

**ただし確認必須**：この推測が外れている（別 Activity だった）場合、実装が根本から変わるため、必ず実機の `adb` ログで確認すること。

---

## 確認4：同じAPに繰り返し接続要求すると、2回目以降はダイアログが省略されるか

### 実測結果

**【仕様推測・信頼度高】** 2回目以降はダイアログが省略される（自動接続：パターン①相当になる）。

根拠：Android 公式ドキュメント（`WifiNetworkSpecifier` の項）に明記されている。

> "If the app was granted a similar request in the recent past, it won't ask for user approval again."

「similar request」の同一性は **SSID + BSSID + セキュリティ種別の一致** で判定される。

### そこから読み取れること

**測定タイミングによって同じ AP でもパターンが変わることが確定的に起きる。** 具体的には：

- **1回目の測定**：ダイアログあり（パターン②または③）→ 15秒タイムアウト問題が発生しうる
- **2回目以降**：ダイアログなし自動接続（パターン①）→ `onAvailable()` が即時発火、タイムアウト問題なし

この「承認キャッシュ」がいつリセットされるかは **【要実測】**。アプリのアンインストール、または OS のネットワーク設定リセットでクリアされると推測されるが、期間については不明。

---

## 確認5：複数端末・複数Androidバージョンでの再現性

### 実測結果

**【要実測】** 複数の実機での試験が必要。

### そこから読み取れること

**【仕様推測】** 以下の差異が出やすい箇所として知られている。

| 差異が出やすい箇所 | 理由 |
|-------------------|------|
| パターン③の UI 表示内容 | OEM が WiFi 接続 UI をカスタマイズしているため（Samsung / Pixel / OPPO 等で表示が異なる） |
| `onWindowFocusChanged` vs `onPause` の発火 | OEM による Activity スタック実装の違い |
| 承認キャッシュの保持期間 | OEM の Wi-Fi サービス実装による |
| `onUnavailable()` が呼ばれるまでの時間 | OEM の `WifiNetworkFactory` 実装による |

Android 標準の挙動か OEM 固有かを判別するために、**Google Pixel（素の Android）での結果を基準** として、他端末の結果と比較することを推奨する。

---

## 全体まとめ（初回回答時点）

| 確認項目 | 回答の種別 | 要点 |
|----------|-----------|------|
| 確認1 タイムスタンプ実測 | 要実測 | デバッグコード案を提供。ユーザー実行が必要 |
| 確認2 パターン③の自動進行 | 仕様推測 | 自動進行（ユーザー操作不要）と推測。確認1の実測で確定 |
| 確認3 SystemUI の種別 | 仕様推測＋要実測 | オーバーレイ（`onWindowFocusChanged`）と推測。`adb` で必ず確認 |
| 確認4 2回目以降の省略 | 仕様推測（信頼度高） | Android ドキュメントに明記。1回目と2回目でパターンが変わる |
| 確認5 複数端末の再現性 | 要実測 | Pixel を基準に OEM 差を確認することを推奨 |

### タイムアウト起点変更の実装可否についての暫定見解（初回）

- パターン②（ユーザーが OK を押す場合）のみがタイムアウト起点変更の恩恵を受ける
- パターン③は自動進行のため起点変更の効果がない
- `onWindowFocusChanged(true)` をトリガーに「OK が押された直後」を間接検知する方式は、確認3が「オーバーレイ」と判明した場合に限り有効
- **確認3の実機確認が最優先**

---

## AOSP ソースコード調査による訂正・追記（v2）

ユーザーによる AOSP `NetworkRequestDialogActivity.java` の直接調査に基づき、以下を訂正・確定する。

### 確認3 訂正：SystemUI は別 Activity（オーバーレイではない）

**【初回回答を訂正】** `NetworkRequestDialogActivity` は `FragmentActivity` を継承した正規の別 Activity であり、オーバーレイウィンドウではない。

- 初回回答の「`onPause()` は呼ばれず `onWindowFocusChanged(false)` が呼ばれる」は誤り
- 正しくは：**`NetworkRequestDialogActivity` が前面に来た時点で `onPause()` が呼ばれる**
- `onWindowFocusChanged` ベースの実装案は採用不可

確認1のデバッグログも `onPause` / `onResume` を主軸に読み直す必要がある。

### 確認2 確定：パターン③は完全自動進行（コード上の事実）

**【仕様推測 → コード上の事実に格上げ】** `NetworkRequestDialogActivity.onCreate()` の実装から以下が確定。

- BSSID/SSID を指定したリクエストに対して、まず「デバイスを検索しています…」プログレスダイアログを表示する
- 次画面（「接続しますか？」確認ダイアログ）への遷移は、`WifiManager` からの **`onMatch()` コールバック**（OS 内部再スキャン結果）によってのみ起動される
- ユーザーのボタン操作は一切不要。完全自動進行が確定

### 新発見：OS 側にも 30 秒の内部タイムアウトがある

`NetworkRequestDialogActivity` 内の定数 `DELAY_TIME_STOP_SCAN_MS = 30,000ms` が確認された。

- `onMatch()` が 30 秒以内に呼ばれなければ、OS 自身がエラーダイアログを出して処理を終了する
- **現在のアプリの `DIALOG_TIMEOUT_MS = 15秒` は OS の探索予算（30秒）より短い**
- パターン③は「AP が見つからない = OS の 30 秒探索が完了する前に、アプリの 15 秒タイムアウトが先に発火する」という経路であると確定

### 3パターンの正確な経路（確定版）

| パターン | 経路 | ユーザー操作 | タイムアウト関与 |
|----------|------|-------------|----------------|
| ① 自動接続完了 | 承認キャッシュあり → `NetworkRequestDialogActivity` 自体がスキップ → `onAvailable()` 即時発火 | なし | なし |
| ② 確認→操作→エラー | `onMatch()` 成功（AP発見）→ 確認ダイアログ表示 → ユーザーが OK 押下 → Supplicant 開始 → 認証失敗 | OK 押下 | Phase2 の `AUTH_TIMEOUT_MS`（8秒）が関与 |
| ③ 検索中→エラー | `onMatch()` が来ないまま → アプリの `DIALOG_TIMEOUT_MS`（15秒）が OS の 30 秒より先に発火 → `FAILED_AT_DIALOG_TIMEOUT` | なし | `DIALOG_TIMEOUT_MS` が主因 |

---

## 実装方針の検討（v2 確定事実を踏まえて）

### 問題の再定義

タイムアウト起点の問題は「ユーザー操作時間のばらつき」と「OS 探索予算との不一致」の 2 つに分離される。

| 問題 | 該当パターン | 原因 |
|------|-------------|------|
| アプリが OS より先に諦める | ③ | `DIALOG_TIMEOUT_MS`（15秒）< OS 内部タイムアウト（30秒） |
| ユーザー操作時間が 15 秒を圧迫する | ② | `DIALOG_TIMEOUT_MS` の起点が `requestNetwork()` 呼び出し時点 |

### 推奨実装案

**`DIALOG_TIMEOUT_MS` を 35 秒以上に伸ばす**

これだけで両方の問題に対応できる。

- パターン③：OS の 30 秒探索が完了して `onUnavailable()` を呼ぶのを待てるようになる
- パターン②：ユーザーが OK を押すまでの時間的余裕が増える

`onPause()` を起点にタイマーをリセットする案（より精密な制御）も検討できるが、`onPause()` は `NetworkRequestDialogActivity` が起動した瞬間（`onMatch()` の前）に発火するため、「ユーザーが OK を押せる状態になった瞬間」とは一致しない。精度の問題から、値の変更のみで対処するほうがシンプルで確実。

### 次のアクション候補

1. `DIALOG_TIMEOUT_MS` の値を `35_000L`（35秒）に変更して実装する
2. 確認1のデバッグログ（`onPause` / `onResume` ベースに修正の上）を実機で取得し、パターンごとのタイミングを実測する（任意）
