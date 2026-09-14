# スループット測定モード — AI開発者向け実装仕様書

## 1. 概要

本ドキュメントは、複数の測定モードを持つ新規Androidアプリにおける「スループット測定」モードの実装仕様を定義する。
本モードの機能は既存アプリ `ThroughputMeasurement` の `MainActivity.kt` から移植する。

---

## 2. アプリ全体アーキテクチャ

- **言語**: Kotlin
- **minSdk**: 30（Android 11）
- **targetSdk**: 35
- **アーキテクチャ**: Single Activity + Fragment（Android Navigation Component による画面遷移）

### Fragment 構成

| Fragment クラス名 | 役割 |
|---|---|
| `ModeSelectionFragment` | モード選択画面（ホーム） |
| `ThroughputFragment` | スループット測定モード（本仕様の対象） |
| `SignalFragment` | 電波状況測定モード（別仕様） |
| `ConnectionAttemptFragment` | 接続試行測定モード（別仕様） |

`MainActivity` は `NavHostFragment` のみをホストする。各 Fragment への遷移は Navigation Component で管理する。

---

## 3. AndroidManifest.xml

### 必要なパーミッション

```xml
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
```

### `<application>` タグに追加する属性

```xml
android:usesCleartextTraffic="true"
```

HTTP（平文）通信のサーバに送信するため必須。

---

## 4. 依存ライブラリ（app/build.gradle.kts）

```kotlin
implementation("com.google.code.gson:gson:2.10.1")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
implementation(libs.play.services.location)          // com.google.android.gms:play-services-location
implementation(libs.androidx.core.ktx)
implementation(libs.androidx.appcompat)
implementation(libs.material)
implementation(libs.androidx.constraintlayout)
implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
implementation("androidx.navigation:navigation-ui-ktx:2.7.7")
```

---

## 5. データクラス

`ThroughputFragment.kt` 内または同パッケージの別ファイルに定義する。

```kotlin
data class MeasureLocationData(
    val latitude: Double,
    val longitude: Double
)

data class WifiData(
    val ssid: String,
    val rssi: Int,
    val standard: String
)

data class ThroughputData(
    val measuretime: Double,
    val throughput: Double,
    val downloadfilesize: Int
)

// サーバ送信用フラット構造（Gson でシリアライズ）
data class FlatCollectedData(
    val latitude: Double,
    val longitude: Double,
    val ssid: String,
    val rssi: Int,
    val wifistandard: String,
    val downloadfilesize: Int,
    val measure_time: Double,
    val throughput: Double
)
```

---

## 6. ThroughputFragment 実装仕様

### 6.1 クラス定義

```kotlin
class ThroughputFragment : Fragment(R.layout.fragment_throughput) {

    private lateinit var btnStart: Button
    private lateinit var tvLatitude: TextView
    private lateinit var tvLongitude: TextView
    private lateinit var tvSsid: TextView
    private lateinit var tvRssi: TextView
    private lateinit var tvStartTime: TextView
    private lateinit var tvEndTime: TextView
    private lateinit var tvMeasureTime: TextView
    private lateinit var tvThroughput: TextView
    private lateinit var tvCounter: TextView
    private lateinit var tvFileSize: TextView
    private lateinit var tvStandard: TextView
    private lateinit var spinnerServer: Spinner
    private lateinit var spinnerFile: Spinner
    private lateinit var etCustomServerUrl: EditText
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var downloadFileUrl = ""
    private var destinationServerUrl = ""
    private var counter = 0
}
```

### 6.2 onViewCreated の処理順序

1. `FusedLocationProviderClient` を `LocationServices.getFusedLocationProviderClient(requireContext())` で初期化
2. `ACCESS_FINE_LOCATION` パーミッションが未取得ならリクエスト（requestCode: 1001）
3. `spinnerServer` の `onItemSelectedListener` を設定（下記 6.3 参照）
4. `spinnerFile` の `onItemSelectedListener` を設定（選択値を `downloadFileUrl` に格納）
5. `btnStart` の `setOnClickListener` を設定（下記 6.4 参照）

### 6.3 spinnerServer の挙動

```
選択値 == "自分で入力"
  → etCustomServerUrl.visibility = View.VISIBLE
  → TextWatcher で destinationServerUrl = 入力テキスト
それ以外
  → etCustomServerUrl.visibility = View.GONE
  → destinationServerUrl = 選択値
```

### 6.4 btnStart クリック時の処理

```kotlin
btnStart.isEnabled = false
btnStart.text = "測定中"

CoroutineScope(Dispatchers.IO).launch {
    try {
        val json = collectData(requireContext(), fusedLocationClient)
        if (json != null) {
            sendJsonToServer(json, requireContext(), destinationServerUrl)
        } else {
            Log.e("CollectData", "データ収集に失敗")
        }
        withContext(Dispatchers.Main) {
            counter++
            tvCounter.text = "測定 : ${counter} 回目"
            btnStart.text = "測定開始"
            btnStart.isEnabled = true
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            btnStart.text = "エラー発生"
            btnStart.isEnabled = true
        }
    }
}
```

---

## 7. 主要 suspend 関数の仕様

### 7.1 fetchLocation()

```kotlin
suspend fun fetchLocation(
    context: Context,
    fusedLocationProviderClient: FusedLocationProviderClient
): MeasureLocationData?
```

- `ACCESS_FINE_LOCATION` が未許可 → UIにエラー表示して `return null`
- `fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()` で現在地を取得
- 取得成功 → `tvLatitude`, `tvLongitude` を更新して `MeasureLocationData` を返す
- 取得失敗（location == null）または例外 → UIにエラー表示して `null` を返す

### 7.2 fetchWifiInfo()

```kotlin
suspend fun fetchWifiInfo(context: Context): WifiData?
```

- `WifiManager.connectionInfo` から SSID・RSSI を取得
- SSID が `null` または `"<unknown ssid>"` → `null` を返す
- Wi-Fi 規格の判定:

**Android 11 以上（Build.VERSION_CODES.R 以上）:**

| `info.wifiStandard` | 返す文字列 |
|---|---|
| `WIFI_STANDARD_11BE` | `"802.11be"` |
| `WIFI_STANDARD_11AX` | `"802.11ax"` |
| `WIFI_STANDARD_11AC` | `"802.11ac"` |
| `WIFI_STANDARD_11N` | `"802.11n"` |
| `WIFI_STANDARD_LEGACY`（frequency 2400〜2500） | `"802.11b/g"` |
| `WIFI_STANDARD_LEGACY`（frequency 4900〜5900） | `"802.11a"` |
| その他 | `"Unknown"` |

**Android 10 以下（フォールバック）:**

| `info.frequency` | 返す文字列 |
|---|---|
| 2400〜2500 | `"2.4GHz帯 (b/g/n)"` |
| 4900〜5900 | `"5GHz帯 (a/n/ac)"` |
| その他 | `"Unknown"` |

- 取得後、`tvSsid`, `tvRssi`, `tvStandard` を更新して `WifiData` を返す

### 7.3 measureThroughput()

```kotlin
suspend fun measureThroughput(): ThroughputData?
```

- ダウンロード先 URL: `downloadFileUrl`（Fragment のプロパティ）
- タイムアウト: `withTimeoutOrNull(5000L)`
- 接続時はキャッシュを無効化:
  ```kotlin
  useCaches = false
  setRequestProperty("Cache-Control", "no-cache")
  setRequestProperty("Pragma", "no-cache")
  ```
- 読み取りバッファ: `ByteArray(8 * 1024)`（8KB）
- 計算式:
  ```
  timeTakenSeconds = (downloadEndTime - startTime) / 1000.0
  throughputMbps   = (totalBytesRead * 8) / (timeTakenSeconds * 1_000_000)
  downloadFileSizeMB = totalBytesRead / 1_000_000
  ```
- タイムアウト時も `downloadEndTime = System.currentTimeMillis()` で終了時刻を記録し、途中データで計算する
- タイムアウト時の UI 表示: `EndTime` に `"(タイムアウト)"` を付加、`FileSize` に `"(途中)"` を付加
- 時刻フォーマット: `SimpleDateFormat("HH:mm:ss.SS", Locale.getDefault())`
- 計算後、`tvStartTime`, `tvEndTime`, `tvMeasureTime`, `tvThroughput`, `tvFileSize` を更新
- 成功・タイムアウトどちらの場合も `ThroughputData` を返す。例外発生時のみ `null` を返す

### 7.4 collectData()

```kotlin
suspend fun collectData(
    context: Context,
    client: FusedLocationProviderClient
): String?
```

- `fetchLocation()` → `fetchWifiInfo()` → `measureThroughput()` を順番に呼ぶ
- いずれかが `null` を返したら即座に `null` を返す
- `FlatCollectedData` を生成:
  - `ssid`: `wifi.ssid.trim('"')` で前後の `"` を除去
- `Gson().toJson(flatCollectedData)` で JSON 文字列に変換して返す

### 7.5 sendJsonToServer()

```kotlin
suspend fun sendJsonToServer(
    json: String,
    context: Context,
    destinationServerUrl: String
)
```

- HTTP POST
- `Content-Type: application/json`
- タイムアウト: `withTimeoutOrNull(5000L)`
- レスポンスコードが `200` 以外は例外をスロー
- タイムアウト時: Toast で `"サーバ応答なし(10sec タイムアウト)"` を表示
- 例外時: Toast で `"通信エラー: ${e.message}"` を表示

---

## 8. レイアウト（res/layout/fragment_throughput.xml）

`ConstraintLayout` を使用し、以下のViewを縦方向に並べる。

| View 種別 | ID | 初期テキスト / 備考 |
|---|---|---|
| `TextView` | `tv_latitude` | `"緯度"` |
| `TextView` | `tv_longitude` | `"経度"` |
| `TextView` | `tv_ssid` | `"SSID"` |
| `TextView` | `tv_rssi` | `"RSSI"` |
| `TextView` | `tv_start_time` | `"測定開始時刻"` |
| `TextView` | `tv_end_time` | `"測定終了時刻"` |
| `TextView` | `tv_file_size` | `"ファイルサイズ"` |
| `Spinner` | `spinner_file` | `entries="@array/throughput_file_download_urls"` |
| `TextView` | `tv_measure_time` | `"測定時間"` |
| `TextView` | `tv_throughput` | `"スループット"` |
| `TextView` | `tv_standard` | `"通信規格"` |
| `TextView` | `tv_counter` | `"測定 : 0 回目"` |
| `TextView` | `tv_destination_server` | `"送信先サーバ:"` |
| `Spinner` | `spinner_server` | `entries="@array/throughput_server_urls"` |
| `EditText` | `et_custom_server_url` | `hint="送信先URLを入力"`, `inputType="textUri"`, `visibility="gone"` |
| `Button` | `btn_start` | `"測定開始"`, 画面下部に配置 |

---

## 9. String リソース（res/values/strings.xml への追加）

```xml
<!-- スループット測定モード用 -->
<string name="throughput_btn_start">測定開始</string>
<string name="throughput_btn_measuring">測定中</string>
<string name="throughput_latitude">緯度</string>
<string name="throughput_longitude">経度</string>
<string name="throughput_ssid">SSID</string>
<string name="throughput_rssi">RSSI</string>
<string name="throughput_start_time">測定開始時刻</string>
<string name="throughput_end_time">測定終了時刻</string>
<string name="throughput_file_size">ファイルサイズ</string>
<string name="throughput_measure_time">測定時間</string>
<string name="throughput_value">スループット</string>
<string name="throughput_standard">通信規格</string>
<string name="throughput_counter">測定回数</string>
<string name="throughput_destination_server">送信先サーバ:</string>
<string name="throughput_custom_url_hint">送信先URLを入力</string>

<string-array name="throughput_file_download_urls">
    <item>https://wifimap.matsuda-lab.jp/DownloadFiles/5Mtest</item>
    <item>https://wlv.matsuda-lab.jp/DownloadFiles/5Mtest.bin</item>
    <item>http://wlv.matsuda-lab.jp/DownloadFiles/10Mtest</item>
    <item>自分で入力</item>
</string-array>

<string-array name="throughput_server_urls">
    <item>http://192.168.11.119:8081/test_receive.php</item>
    <item>http://10.40.21.231:8081/test_receive.php</item>
    <item>http://wlv.matsuda-lab.jp/test_receive.php</item>
    <item>http://192.168.11.56:8081/test_receive.php</item>
    <item>自分で入力</item>
</string-array>
```

---

## 10. モード選択画面（ModeSelectionFragment）との接続

`ModeSelectionFragment` に「スループット測定」ボタンを配置し、Navigation Component でナビゲートする。

```kotlin
// ModeSelectionFragment.kt 内
btnThroughput.setOnClickListener {
    findNavController().navigate(R.id.action_modeSelection_to_throughputFragment)
}
```

`res/navigation/nav_graph.xml` に以下のフラグメントと action を定義する:

```xml
<fragment
    android:id="@+id/modeSelectionFragment"
    android:name="com.example.newapp.ModeSelectionFragment"
    tools:layout="@layout/fragment_mode_selection">
    <action
        android:id="@+id/action_modeSelection_to_throughputFragment"
        app:destination="@id/throughputFragment"/>
</fragment>

<fragment
    android:id="@+id/throughputFragment"
    android:name="com.example.newapp.ThroughputFragment"
    tools:layout="@layout/fragment_throughput"/>
```

---

## 11. 既知の制約・注意点

| 項目 | 内容 |
|---|---|
| `WifiManager.connectionInfo` の deprecation | Android 12 以降で deprecated。現状はそのまま使用する。将来的に `NetworkCallback` ベースの API へ移行検討が必要 |
| HTTP 平文通信 | `android:usesCleartextTraffic="true"` が必須。送信先サーバに HTTP エンドポイントが含まれるため |
| 位置情報パーミッション | Wi-Fi の SSID 取得に Android 10 以降は `ACCESS_FINE_LOCATION` が必須 |
| SSID の `"` 除去 | `WifiManager.connectionInfo.ssid` は前後に `"` が付くため `trim('"')` で除去する |
| タイムアウト値 | 測定・サーバ送信ともに 5000ms。タイムアウト時も計算は実行される |
| 二重押し防止 | 測定中はボタンを `isEnabled = false` にすること |
