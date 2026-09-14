package com.example.beacon_scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.appbar.MaterialToolbar
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MeasureLocationData(val latitude: Double, val longitude: Double)
data class WifiData(val ssid: String, val rssi: Int, val standard: String)
data class ThroughputData(val measuretime: Double, val throughput: Double, val downloadfilesize: Int)
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

class ThroughputActivity : AppCompatActivity() {

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

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "位置情報の権限が必要です", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_throughput)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        btnStart = findViewById(R.id.btnStart)
        tvLatitude = findViewById(R.id.tv_latitude)
        tvLongitude = findViewById(R.id.tv_longitude)
        tvSsid = findViewById(R.id.tv_ssid)
        tvRssi = findViewById(R.id.tv_rssi)
        tvStartTime = findViewById(R.id.tv_start_time)
        tvEndTime = findViewById(R.id.tv_end_time)
        tvMeasureTime = findViewById(R.id.tv_measure_time)
        tvThroughput = findViewById(R.id.tv_throughput)
        tvCounter = findViewById(R.id.tv_counter)
        tvFileSize = findViewById(R.id.tv_file_size)
        tvStandard = findViewById(R.id.tv_standard)
        spinnerServer = findViewById(R.id.spinner_server)
        spinnerFile = findViewById(R.id.spinner_file)
        etCustomServerUrl = findViewById(R.id.et_custom_server_url)

        // spinnerFile の初期値設定と選択ハンドラ
        val fileUrls = resources.getStringArray(R.array.throughput_file_download_urls)
        downloadFileUrl = fileUrls.firstOrNull() ?: ""
        spinnerFile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                downloadFileUrl = fileUrls[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        // spinnerServer の選択ハンドラ
        val serverUrls = resources.getStringArray(R.array.throughput_server_urls)
        destinationServerUrl = serverUrls.firstOrNull() ?: ""
        spinnerServer.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selected = serverUrls[position]
                if (selected == "自分で入力") {
                    etCustomServerUrl.visibility = View.VISIBLE
                    etCustomServerUrl.addTextChangedListener(object : TextWatcher {
                        override fun afterTextChanged(s: Editable?) {
                            destinationServerUrl = s?.toString() ?: ""
                        }
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    })
                } else {
                    etCustomServerUrl.visibility = View.GONE
                    destinationServerUrl = selected
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        btnStart.setOnClickListener {
            btnStart.isEnabled = false
            btnStart.text = "測定中"

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val json = collectData()
                    if (json != null) {
                        val sendOk = sendJsonToServer(json)
                        withContext(Dispatchers.Main) {
                            if (sendOk) {
                                counter++
                                tvCounter.text = "測定 : ${counter} 回目"
                            }
                            btnStart.text = "測定開始"
                            btnStart.isEnabled = true
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            btnStart.text = "測定開始"
                            btnStart.isEnabled = true
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ThroughputActivity, "エラー: ${e.message}", Toast.LENGTH_SHORT).show()
                        btnStart.text = "測定開始"
                        btnStart.isEnabled = true
                    }
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private suspend fun fetchLocation(): MeasureLocationData? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ThroughputActivity, "位置情報の権限が必要です", Toast.LENGTH_SHORT).show()
            }
            return null
        }
        return try {
            val cts = CancellationTokenSource()
            val location = fusedLocationClient
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .await()
            if (location == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ThroughputActivity, "位置情報を取得できませんでした", Toast.LENGTH_SHORT).show()
                }
                null
            } else {
                withContext(Dispatchers.Main) {
                    tvLatitude.text = "緯度: ${location.latitude}"
                    tvLongitude.text = "経度: ${location.longitude}"
                }
                MeasureLocationData(location.latitude, location.longitude)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ThroughputActivity, "位置情報エラー: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            null
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun fetchWifiInfo(): WifiData? {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifiManager.connectionInfo
        val ssid = info?.ssid
        if (ssid == null || ssid == "<unknown ssid>") return null

        val standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            when (info.wifiStandard) {
                ScanResult.WIFI_STANDARD_11BE -> "802.11be"
                ScanResult.WIFI_STANDARD_11AX -> "802.11ax"
                ScanResult.WIFI_STANDARD_11AC -> "802.11ac"
                ScanResult.WIFI_STANDARD_11N  -> "802.11n"
                ScanResult.WIFI_STANDARD_LEGACY ->
                    if (info.frequency in 2400..2500) "802.11b/g" else "802.11a"
                else -> "Unknown"
            }
        } else {
            when {
                info.frequency in 2400..2500 -> "2.4GHz帯 (b/g/n)"
                info.frequency in 4900..5900 -> "5GHz帯 (a/n/ac)"
                else -> "Unknown"
            }
        }

        withContext(Dispatchers.Main) {
            tvSsid.text = "SSID: $ssid"
            tvRssi.text = "RSSI: ${info.rssi} dBm"
            tvStandard.text = "通信規格: $standard"
        }

        return WifiData(ssid = ssid, rssi = info.rssi, standard = standard)
    }

    private suspend fun measureThroughput(): ThroughputData? {
        val dateFormat = SimpleDateFormat("HH:mm:ss.SS", Locale.getDefault())
        val startTime = System.currentTimeMillis()
        withContext(Dispatchers.Main) {
            tvStartTime.text = "測定開始時刻: ${dateFormat.format(Date(startTime))}"
        }

        return try {
            var totalBytesRead = 0L
            var isTimeout = false

            withTimeoutOrNull(5000L) {
                val conn = URL(downloadFileUrl).openConnection() as HttpURLConnection
                conn.useCaches = false
                conn.setRequestProperty("Cache-Control", "no-cache")
                conn.setRequestProperty("Pragma", "no-cache")
                val buffer = ByteArray(8 * 1024)
                val input = conn.inputStream
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    totalBytesRead += bytesRead
                }
                input.close()
                conn.disconnect()
            } ?: run { isTimeout = true }

            val downloadEndTime = System.currentTimeMillis()
            val timeTakenSeconds = (downloadEndTime - startTime) / 1000.0
            val throughputMbps = if (timeTakenSeconds > 0) (totalBytesRead * 8) / (timeTakenSeconds * 1_000_000) else 0.0
            val downloadFileSizeMB = (totalBytesRead / 1_000_000).toInt()

            val endTimeStr = dateFormat.format(Date(downloadEndTime)) + if (isTimeout) " (タイムアウト)" else ""
            val fileSizeStr = "${downloadFileSizeMB}MB" + if (isTimeout) " (途中)" else ""

            withContext(Dispatchers.Main) {
                tvEndTime.text = "測定終了時刻: $endTimeStr"
                tvMeasureTime.text = "測定時間: ${"%.3f".format(timeTakenSeconds)}秒"
                tvThroughput.text = "スループット: ${"%.2f".format(throughputMbps)}Mbps"
                tvFileSize.text = "ファイルサイズ: $fileSizeStr"
            }

            ThroughputData(
                measuretime = timeTakenSeconds,
                throughput = throughputMbps,
                downloadfilesize = downloadFileSizeMB
            )
        } catch (e: Exception) {
            Log.e("ThroughputActivity", "measureThroughput error: ${e.message}", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ThroughputActivity, "ダウンロードエラー: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            null
        }
    }

    private suspend fun collectData(): String? {
        val location = fetchLocation() ?: return null
        val wifi = fetchWifiInfo() ?: return null
        val throughput = measureThroughput() ?: return null

        val flat = FlatCollectedData(
            latitude = location.latitude,
            longitude = location.longitude,
            ssid = wifi.ssid.trim('"'),
            rssi = wifi.rssi,
            wifistandard = wifi.standard,
            downloadfilesize = throughput.downloadfilesize,
            measure_time = throughput.measuretime,
            throughput = throughput.throughput
        )
        return Gson().toJson(flat)
    }

    private suspend fun sendJsonToServer(json: String): Boolean {
        val result = withTimeoutOrNull(5000L) {
            runCatching {
                val conn = URL(destinationServerUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(json) }
                val code = conn.responseCode
                conn.disconnect()
                if (code != 200) throw Exception("HTTP $code")
                code
            }
        }

        return withContext(Dispatchers.Main) {
            when {
                result == null -> {
                    Toast.makeText(this@ThroughputActivity, "サーバ応答なし (タイムアウト)", Toast.LENGTH_SHORT).show()
                    false
                }
                result.isFailure -> {
                    Toast.makeText(this@ThroughputActivity, "送信エラー: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                    false
                }
                else -> true
            }
        }
    }
}

// Task<Location>.await() のための拡張関数
private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T? =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) cont.resume(task.result, null)
            else cont.resume(null, null)
        }
    }
