# 実装依頼：「選択したAPを測定」実行中、処理中のAPを一覧の先頭に表示する

## やりたいこと

`btnMeasureSelected`(選択したAPを測定)を押してAPを1台ずつ順番に測定している間、以下のように一覧(`apList`/`ApAdapter`)を動的に並び替えたい。

- 現在接続処理をしているAPを、常に一覧の一番上に表示する
- 1台の測定が終わったら、そのAPを一覧の一番下に移動する
- 次に処理するAPを一番上に表示する

対象は「選択したAPを測定」ボタンによる測定フローのみでよい(自動スキャンの全AP測定フローは対象外)。

## 現状のコード(変更対象)

`measureAllSupplicant()`(830〜846行目)は、`onProgress`コールバックで進捗テキスト(`progress`, `total`, `name`)だけを通知しており、`apList`自体の並び替えは行っていない。`apList`(RecyclerViewに紐づくリスト)は、全AP測定が終わったあと(`btnMeasureSelected`のクリック処理、438〜442行目)に一度だけ更新される。

## 依頼したい変更

1. `measureAllSupplicant()`に、各APの処理開始時・終了時に呼ばれるコールバックを追加してほしい(例：`onApStart: (AccessPoint) -> Unit`、`onApFinished: (AccessPoint) -> Unit`)。

2. `btnMeasureSelected`のクリック処理内で、これらのコールバックを使い、
   - `onApStart`時：該当APを`apList`から探し、先頭(index 0)に移動して`adapter.notifyDataSetChanged()`
   - `onApFinished`時：該当APを`apList`から探し、末尾に移動(測定結果で更新)して`adapter.notifyDataSetChanged()`
   という処理を実装してほしい。

3. 選択モード(チェックボックス表示)はこの間オフのままでよい(`isInSelectionMode = false`は測定開始前に既に設定済み)。

## 補足

APは1台ずつ順番に処理されるため、同時に複数APが「処理中」になることはない。並び替えロジックはシンプルな「先頭に持ってくる」「末尾に送る」だけで十分。
