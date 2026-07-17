# 開発AIへの確認スクリプト：「接続しますか？」表示後1〜2秒でボタン操作なしにエラーになる件の調査

## 観測された事象

実機で動作させると、「接続しますか？」という確認ダイアログが表示された後、ユーザーが「接続」ボタンを押す間もなく、1〜2秒でエラーになるケースがある。

## この事象が説明できない理由

AOSP(Android標準)のソースコードを確認したところ、以下の2ファイルが該当する画面を構成している。

- `NetworkRequestDialogActivity.java`：「デバイスを検索しています…」の進捗画面を管理し、OSの内部再スキャン(`onMatch()`)が来て初めて確認ダイアログに切り替える。この進捗画面には30秒の内部タイムアウトがある。
- `NetworkRequestSingleSsidDialogFragment.java`：実際の「接続しますか？」ボタン(接続/キャンセル)を表示する部分。`setCancelable(false)`で外側タップでも閉じないようになっており、コード上は自動タイムアウトの仕組みが見当たらない。ユーザーが明示的にボタンを押すまで閉じないはずの作り。

つまりAOSP標準コードの通りなら、「接続しますか？」が表示されたら、ボタンを押さない限り1〜2秒で勝手にエラーになることは起こらないはずである。この矛盾を解消したい。

---

## 確認1：アプリ内部の記録データを確認してほしい

新しくログを仕込まなくても、既存の`pending_scans.json`(端末の内部ストレージ、`context.filesDir`配下)に、該当SSIDの測定結果が既に記録されているはずである。以下を確認してほしい。

- 該当SSIDの`supplicant_final_state`の値は何か(例：`FAILED_AT_DIALOG_TIMEOUT`、`FAILED_AT_DISCONNECTED`、`NOT_FOUND`など)
- `supplicant_elapsed_ms`の値は何ミリ秒か(体感の1〜2秒と一致するか)
- `supplicant_states`配列に、どのSupplicant状態がいくつ記録されているか(例：`["SCANNING","ASSOCIATING","ASSOCIATED","FOUR_WAY_HANDSHAKE","DISCONNECTED"]`など)

`supplicant_final_state`が`FAILED_AT_DIALOG_TIMEOUT`であれば「15秒待ってタイムアウトした」ことになるはずだが、体感1〜2秒でエラーが出ているなら矛盾するため、実際にどちらなのかを確認してほしい。

---

## 確認2：この「エラー」はどちらの画面のものか

以下のどちらかを確認してほしい。

- ケースA：Android(Settings アプリ)側のシステム画面が、「接続しますか？」から自動的に別のエラー画面(例:`NetworkRequestErrorDialogFragment`によるエラー表示)に切り替わっている
- ケースB：システム画面自体はそのまま残っているが、それとは別に、このアプリ自身がToastやUI表示で何らかの「エラー」を出している(例:`tvSsidCount`の表示内容や、他の`Toast.makeText`呼び出し)

どちらの画面に表示されている「エラー」なのか、スクリーンショットまたは文言をそのまま教えてほしい。

---

## 確認3：`adb logcat`での実測

この事象を再現しながら、以下を実行してログを取得してほしい。

```bash
adb logcat -v time | grep -E "NetworkRequestDialogActivity|WifiNetworkFactory|ConnectivityService|wpa_supplicant|SupplicantStaIfaceHal|BeaconScan"
```

「接続しますか？」が表示された瞬間から、エラーになる瞬間までの、実際のログをそのまま貼ってほしい。特に`onAbort`、`onUnavailable`、状態遷移(`SupplicantState`)に関連する行があれば重要。

---

## 確認4：端末情報

以下を確認してほしい。

- 端末のメーカー・機種名(`Build.MANUFACTURER`、`Build.MODEL`)
- Android バージョン・APIレベル(`Build.VERSION.SDK_INT`)
- Google Pixel(素のAndroid)か、それ以外のメーカー独自カスタマイズが入った端末か

AOSP標準コードには該当する自動タイムアウトが見当たらないため、メーカー独自のカスタマイズによってこの挙動が追加されている可能性がある。その場合、Pixelなど別の端末で同じ現象が再現するかどうかも分かれば教えてほしい。

---

## まとめて欲しい回答の形式

確認1(記録データ)を最優先で確認してほしい。これだけで「アプリのPhase1タイムアウト(15秒)が働いているのか、それとも別の原因で早期に終わっているのか」がほぼ判別できるはずである。
