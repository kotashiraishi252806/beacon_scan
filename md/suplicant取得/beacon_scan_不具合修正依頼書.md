# 不具合修正依頼書

Wi-Fi APスキャンアプリ（beacon_scan）

| 項目 | 内容 |
|---|---|
| 日付 | 2026年7月16日 |
| 依頼者 | 白石光汰（guangtaibaishi63@gmail.com） |
| 宛先 | 開発AI ご担当者様 |
| 対象 | Androidアプリ「beacon_scan」（Kotlin） |
| 添付ファイル | MainActivity.kt / activity_main.xml（本文書と合わせて提供） |

## 1. 依頼概要

下記2件の不具合について、原因調査および修正をお願いいたします。両不具合は同一の操作フロー（「全て選択」→「選択したAPを測定」）の中で連続して発生しており、関連している可能性があります。原因欄は開発者側での一次分析（コードを読んだ上での推定）であり、断定ではありません。実装調査のうえ正確な原因を特定してください。

---

## 2. 不具合①：複数AP選択時、接続許可ダイアログの候補が1件に絞られてしまう

### 再現手順

1. アプリでスキャンを実行する（手動スキャン）
2. 検出結果一覧で「全て選択」ボタンを押す
3. 「選択したAPを測定」ボタンを押す

### 期待する動作

選択した全AP（複数）に対して、順番に接続測定（Supplicant状態測定）が行われる。

### 実際の動作

SSIDリストの1番上のSSIDから接続測定が始まるが、Androidシステムの制約により、ユーザーに接続許可を求めるダイアログのSSID候補が、そのSSID1件のみに絞り込まれてしまう。

### 推定原因（要調査）

`MainActivity.kt` の `measureSupplicantForAp()`（873〜976行目）が、選択されたAP1台ごとに個別のNetworkRequestを作成し、`connectivityManager.requestNetwork()` を呼び出す実装になっています。

```kotlin
// buildSupplicantSpecifier(ap) 978〜1002行目
WifiNetworkSpecifier.Builder().setBssid(mac).setSsid(ssid) ...
```

BSSID・SSIDを1つに指定したNetworkSpecifierでrequestNetwork()を呼ぶと、Android OS側の仕様上、システムの接続確認ダイアログにはその条件に一致するネットワークのみが表示されます。そのため実装としては「AP1台ずつ・都度システムダイアログでユーザー許可を得る」形になっており、「複数APを一括選択して連続測定する」というアプリ側のUI上の期待と、OSの制約との間にギャップが生じていると考えられます。

### 依頼事項

- この制約がAndroid OS仕様として回避不可能なものか、実装変更（NetworkSpecifierの指定方法、WifiNetworkSuggestion APIの利用など）で軽減可能かを調査してください。
- 回避できない場合は、「AP毎に接続確認ダイアログが表示される」旨を測定開始前にユーザーへ明示するなど、UX面での改善（案内文言・進捗表示の見直し等）を検討してください。
- この現象が発生している最中に、不具合②（中断ボタン消失）が併発していないか、合わせて調査してください。

---

## 3. 不具合②：測定中に「測定中断」ボタンが消え、中断操作ができなくなる

### 再現手順

不具合①と同じ手順（全て選択→選択したAPを測定）で測定を開始し、Android接続確認ダイアログの表示中〜複数AP連続測定中における「測定中断」ボタンの状態を確認する。

### 期待する動作

測定処理中は常に「測定中断」ボタンが画面に表示され、いつでもタップして測定を中断できる。

### 実際の動作

測定中に「測定中断」ボタンが画面から消え、中断操作ができなくなる。

### 推定原因（要調査）

`btnStopMeasurement` の表示制御は以下の2か所のみで行われています。

```kotlin
// btnMeasureSelected.setOnClickListener 内（425〜427行目付近）
btnStopMeasurement.visibility = View.VISIBLE  // 測定開始時

// lifecycleScope.launch { ... } 内（454行目付近）
btnStopMeasurement.visibility = View.GONE     // 全AP測定完了後
```

コード上、測定処理の途中で明示的にGONEへ変更している箇所は見当たりません。そのため、以下のようなActivityのライフサイクルに起因する現象が疑われます。

- Android接続確認システムダイアログの表示に伴いActivityがonStop / onDestroyされる場合があり、システムによってActivityが再生成されると、`btnStopMeasurement` は `activity_main.xml` 上の初期値（`android:visibility="gone"`）に戻ってしまう。
- 測定処理は `lifecycleScope.launch`（Activityのライフサイクルに紐づくコルーチンスコープ）で起動されているため、Activityが再生成されると、実行中の測定処理とUI（再生成後の新しいボタンインスタンス）との紐付けが失われる可能性がある。
- 画面回転やバックグラウンド遷移など、その他のライフサイクルイベントによるActivity再生成も同様の要因になり得る。

### 依頼事項

- Android接続確認ダイアログの表示時に、Activityのライフサイクルイベント（onPause / onStop / onDestroy）がどのタイミングで発火するか実機で確認してください。
- Activityの再生成が原因である場合、測定処理をActivityのライフサイクルに依存しない形（例：ViewModel + viewModelScopeへの移行、または長時間処理としてForeground Service化）に変更するか、状態保持（onSaveInstanceState等）により再生成後もボタン表示・処理状態を正しく復元できるよう修正してください。
- 修正後は、画面回転や一時的なバックグラウンド遷移を挟んでも「測定中断」ボタンが常時表示・操作可能であることをテストしてください。

---

## 4. 全体としてのお願い

- 不具合①・②はいずれも「全て選択→選択したAPを測定」の連続測定フロー（`MainActivity.kt` 408〜477行目 `btnMeasureSelected.setOnClickListener`、および873〜976行目 `measureSupplicantForAp()`）に関するものです。自動スキャン時の同種の処理（1130〜1164行目）についても同じ問題が起きていないか、合わせてご確認ください。
- 原因調査の結果、上記の推定と異なる原因が判明した場合は、実際の原因と対応方針を共有してください。
- 修正内容は、可能であれば再現手順に沿った動作確認結果（ログまたはスクリーンショット等）と合わせてご報告ください。

添付：MainActivity.kt、activity_main.xml
