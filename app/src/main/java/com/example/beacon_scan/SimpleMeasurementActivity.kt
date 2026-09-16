package com.example.beacon_scan

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MenuItem
import android.view.View
import com.google.android.material.appbar.MaterialToolbar
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SimpleMeasurementActivity : AppCompatActivity() {

    // ── OSサービス ──────────────────────────────────────────
    private lateinit var wifiManager: WifiManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // ── 画面部品 ─────────────────────────────────────────────
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnHelp: android.widget.ImageButton
    private lateinit var btnScan: Button
    private lateinit var btnSendPending: Button
    private lateinit var btnDiscardPending: Button
    private lateinit var btnPauseAutoScan: Button
    private lateinit var btnToggleList: Button
    private lateinit var switchAutoScan: SwitchCompat
    private lateinit var switchSendMode: SwitchCompat
    private lateinit var etUrl: TextInputEditText
    private lateinit var tvEmpty: TextView
    private lateinit var tvAutoScanStatus: TextView
    private lateinit var tvPendingCount: TextView
    private lateinit var tvSsidCount: TextView

    // ── データ ───────────────────────────────────────────────
    private val apList = mutableListOf<AccessPoint>()
    private lateinit var adapter: ApAdapter

    // ── 位置情報 ─────────────────────────────────────────────
    private var latestLocation: Location? = null
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            latestLocation = result.lastLocation
        }
    }

    // ── スキャン制御フラグ ────────────────────────────────────
    private var isScanInProgress = false
    private var scanStartMs: Long = 0L
    private var isProgrammaticSendModeChange = false

    // ── 自動スキャン ──────────────────────────────────────────
    private var autoScanSessionId: String? = null
    private var autoScanStartTime: Date? = null
    private var isAutoScanPaused = false
    private val autoScanHandler = Handler(Looper.getMainLooper())
    private val autoScanRunnable = object : Runnable {
        override fun run() {
            if (switchAutoScan.isChecked && !isScanInProgress) {
                checkPermissionsAndScan()
            } else if (switchAutoScan.isChecked) {
                autoScanHandler.postDelayed(this, AUTO_SCAN_INTERVAL_MS)
            }
        }
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onScanResultsReady()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startScan()
        } else {
            isScanInProgress = false
            btnScan.isEnabled = !switchAutoScan.isChecked
            Toast.makeText(this, "位置情報の権限が必要です", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_simple_measurement)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        recyclerView = findViewById(R.id.recyclerView)
        btnHelp = findViewById(R.id.btnHelp)
        btnScan = findViewById(R.id.btnScan)
        btnSendPending = findViewById(R.id.btnSendPending)
        btnDiscardPending = findViewById(R.id.btnDiscardPending)
        btnPauseAutoScan = findViewById(R.id.btnPauseAutoScan)
        btnToggleList = findViewById(R.id.btnToggleList)
        switchAutoScan = findViewById(R.id.switchAutoScan)
        switchSendMode = findViewById(R.id.switchSendMode)
        etUrl = findViewById(R.id.etUrl)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvAutoScanStatus = findViewById(R.id.tvAutoScanStatus)
        tvPendingCount = findViewById(R.id.tvPendingCount)
        tvSsidCount = findViewById(R.id.tvSsidCount)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        switchSendMode.isChecked = prefs.getBoolean(KEY_SIMPLE_SEND_MODE, true)
        switchSendMode.setOnCheckedChangeListener { _, isChecked ->
            if (!isProgrammaticSendModeChange) {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_SIMPLE_SEND_MODE, isChecked).apply()
            }
            if (!isChecked) updatePendingCount()
        }

        etUrl.setText(prefs.getString(KEY_SIMPLE_SERVER_URL, DEFAULT_SIMPLE_SERVER_URL))
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) saveUrl()
            false
        }

        adapter = ApAdapter(apList)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnToggleList.setOnClickListener {
            if (recyclerView.visibility == View.VISIBLE) {
                recyclerView.visibility = View.GONE
                btnToggleList.text = "一覧"
            } else {
                recyclerView.visibility = View.VISIBLE
                btnToggleList.text = "閉じる"
            }
        }

        btnScan.setOnClickListener {
            saveUrl()
            autoScanHandler.removeCallbacks(autoScanRunnable)
            checkPermissionsAndScan()
        }

        btnSendPending.setOnClickListener {
            saveUrl()
            btnSendPending.isEnabled = false
            btnScan.isEnabled = false
            lifecycleScope.launch {
                trySendAllPending()
                btnScan.isEnabled = !isScanInProgress && !switchAutoScan.isChecked
                updatePendingCount()
            }
        }

        btnDiscardPending.setOnClickListener {
            showDiscardSelectionDialog()
        }

        btnPauseAutoScan.setOnClickListener {
            if (!isAutoScanPaused) {
                autoScanHandler.removeCallbacks(autoScanRunnable)
                isAutoScanPaused = true
                btnPauseAutoScan.text = "再開"
                tvAutoScanStatus.text = "測定停止中"
            } else {
                isAutoScanPaused = false
                btnPauseAutoScan.text = "一時停止"
                tvAutoScanStatus.text = "自動測定中"
                if (!isScanInProgress) {
                    autoScanHandler.postDelayed(autoScanRunnable, AUTO_SCAN_INTERVAL_MS)
                }
            }
        }

        switchAutoScan.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                autoScanSessionId = UUID.randomUUID().toString()
                autoScanStartTime = Date()
                isAutoScanPaused = false
                btnPauseAutoScan.text = "一時停止"
                btnPauseAutoScan.visibility = View.VISIBLE
                btnScan.isEnabled = false
                isProgrammaticSendModeChange = true
                switchSendMode.isChecked = false
                isProgrammaticSendModeChange = false
                switchSendMode.isEnabled = false
                startLocationUpdates()
                if (!isScanInProgress) {
                    autoScanHandler.postDelayed(autoScanRunnable, AUTO_SCAN_INTERVAL_MS)
                }
                tvAutoScanStatus.text = "自動測定中"
                tvAutoScanStatus.visibility = View.VISIBLE
            } else {
                autoScanHandler.removeCallbacks(autoScanRunnable)
                stopLocationUpdates()
                isAutoScanPaused = false
                btnPauseAutoScan.visibility = View.GONE
                btnScan.isEnabled = !isScanInProgress
                switchSendMode.isEnabled = true
                tvAutoScanStatus.visibility = View.GONE
                val sessionId = autoScanSessionId
                val startTime = autoScanStartTime
                autoScanSessionId = null
                autoScanStartTime = null
                if (sessionId != null && startTime != null) {
                    showAutoLabelDialog(sessionId, startTime)
                } else {
                    if (!isScanInProgress) updatePendingCount()
                }
            }
        }

        btnHelp.setOnClickListener { showHelpDialog() }

        registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))

        updatePendingCount()
    }

    override fun onResume() {
        super.onResume()
        updatePendingCount()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(wifiScanReceiver)
        autoScanHandler.removeCallbacks(autoScanRunnable)
        stopLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun saveUrl() {
        val url = etUrl.text?.toString()?.trim() ?: return
        if (url.isNotEmpty()) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SIMPLE_SERVER_URL, url).apply()
        }
    }

    private fun getServerUrl(): String {
        val saved = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SIMPLE_SERVER_URL, DEFAULT_SIMPLE_SERVER_URL) ?: DEFAULT_SIMPLE_SERVER_URL
        return saved.ifEmpty { DEFAULT_SIMPLE_SERVER_URL }
    }

    private fun checkPermissionsAndScan() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) startScan() else requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startScan() {
        if (isScanInProgress) return
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(this, "WiFiがオフです。スキャンできません", Toast.LENGTH_SHORT).show()
            return
        }
        isScanInProgress = true
        scanStartMs = System.currentTimeMillis()
        btnScan.isEnabled = false

        if (switchAutoScan.isChecked) {
            // 自動スキャン中は locationCallback が常時 latestLocation を更新しているので待ち不要
            @Suppress("DEPRECATION")
            val started = wifiManager.startScan()
            if (!started) {
                isScanInProgress = false
                Toast.makeText(this, "スキャンがスロットリングされています。しばらく待ってから再試行してください", Toast.LENGTH_SHORT).show()
            }
        } else {
            val cts = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnCompleteListener { task ->
                    latestLocation = task.result
                    @Suppress("DEPRECATION")
                    val started = wifiManager.startScan()
                    if (!started) {
                        isScanInProgress = false
                        btnScan.isEnabled = true
                        Toast.makeText(this, "スキャンがスロットリングされています。しばらく待ってから再試行してください", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    private fun onScanResultsReady() {
        if (!isScanInProgress) return
        isScanInProgress = false  // 同期的にfalseにしてOSのWiFiスキャンによる二重処理を防ぐ
        val scanElapsedMs = System.currentTimeMillis() - scanStartMs
        Log.d("BeaconScan", "簡易スキャン完了: ${scanElapsedMs}ms")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            btnScan.isEnabled = !switchAutoScan.isChecked
            return
        }

        @Suppress("DEPRECATION")
        val rawResults = wifiManager.scanResults
        val grouped = groupByBssid(rawResults)
        apList.clear()
        apList.addAll(grouped)
        adapter.notifyDataSetChanged()

        val location = latestLocation
        val scanId = UUID.randomUUID().toString()
        val currentScanCount = apList.size
        val label = autoScanSessionId ?: ""

        if (apList.isEmpty()) {
            tvSsidCount.visibility = View.GONE
            btnToggleList.visibility = View.GONE
            recyclerView.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            btnScan.isEnabled = !switchAutoScan.isChecked
            if (switchAutoScan.isChecked) {
                val remaining = maxOf(0L, AUTO_SCAN_INTERVAL_MS - (System.currentTimeMillis() - scanStartMs))
                autoScanHandler.postDelayed(autoScanRunnable, remaining)
            }
            return
        }

        tvSsidCount.text = "今回検出: ${currentScanCount}件"
        tvSsidCount.visibility = View.VISIBLE
        btnToggleList.visibility = View.VISIBLE
        btnToggleList.text = "閉じる"
        recyclerView.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            withContext(Dispatchers.IO) { saveToPending(grouped, location, scanId, label) }
            val totalRecords = withContext(Dispatchers.IO) { ScanStore.totalRecords(this@SimpleMeasurementActivity, PENDING_SIMPLE_FILE) }
            tvSsidCount.text = "今回検出: ${currentScanCount}件\n未送信データ合計: ${totalRecords}件"
            updatePendingCount()

            if (switchSendMode.isChecked) {
                trySendAllPending()
            }

            btnScan.isEnabled = !switchAutoScan.isChecked
            updatePendingCount()

            if (switchAutoScan.isChecked) {
                val remaining = maxOf(0L, AUTO_SCAN_INTERVAL_MS - (System.currentTimeMillis() - scanStartMs))
                autoScanHandler.postDelayed(autoScanRunnable, remaining)
            }
        }
    }

    private fun saveToPending(accessPoints: List<AccessPoint>, location: Location?, scanId: String, label: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
        val apArray = JSONArray()
        for (ap in accessPoints) {
            apArray.put(JSONObject().apply {
                put("bssid", ap.bssid)
                put("mld_mac_address", ap.mldMacAddress ?: JSONObject.NULL)
                put("oui", ap.oui)
                put("ssids", JSONArray(ap.ssids))
                put("rssi_dbm", ap.rssiDbm)
                put("frequency_mhz", ap.frequencyMhz)
                put("band", ap.band)
                put("channel_width_mhz", ap.channelWidthMhz)
                put("wifi_standard", ap.wifiStandard)
                put("wifi_standard_code", ap.wifiStandardCode)
                put("security", ap.security)
                put("capabilities_raw", ap.capabilitiesRaw)
            })
        }
        val entry = JSONObject().apply {
            put("scan_id", scanId)
            put("device_id", getOrCreateDeviceUuid())
            put("device", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("android_api", Build.VERSION.SDK_INT)
            })
            put("scanned_at", timestamp)
            put("label", label)
            if (location != null) {
                put("location", JSONObject().apply {
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("accuracy_m", location.accuracy.toDouble())
                })
            } else {
                put("location", JSONObject.NULL)
            }
            put("access_points", apArray)
        }
        ScanStore.append(this, entry, PENDING_SIMPLE_FILE)
    }

    private fun showAutoLabelDialog(sessionId: String, startTime: Date) {
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val endTime = Date()
        val startStr = fmt.format(startTime)
        val endStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(endTime)
        val autoLabel = "$startStr~$endStr"

        val editText = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(autoLabel)
            setPadding(48, 24, 48, 24)
        }

        AlertDialog.Builder(this)
            .setTitle("ラベルを設定しますか？")
            .setView(editText)
            .setPositiveButton("そのまま保存") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        ScanStore.updateLabelBySessionId(this@SimpleMeasurementActivity, sessionId, autoLabel, PENDING_SIMPLE_FILE)
                    }
                    updatePendingCount()
                }
            }
            .setNegativeButton("変更して保存") { _, _ ->
                val customLabel = editText.text?.toString()?.trim()?.ifEmpty { autoLabel } ?: autoLabel
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        ScanStore.updateLabelBySessionId(this@SimpleMeasurementActivity, sessionId, customLabel, PENDING_SIMPLE_FILE)
                    }
                    updatePendingCount()
                }
            }
            .setCancelable(false)
            .show()
    }

    private suspend fun trySendAllPending() {
        val pending = withContext(Dispatchers.IO) { ScanStore.load(this@SimpleMeasurementActivity, PENDING_SIMPLE_FILE) }
        val total = pending.length()
        if (total == 0) {
            updatePendingCount()
            return
        }

        for (i in 0 until total) {
            val singleArray = JSONArray().put(pending.getJSONObject(i))

            val success = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL(getServerUrl()).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    conn.doOutput = true
                    conn.connectTimeout = 5000
                    conn.readTimeout = 10000
                    OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(singleArray.toString().replace("\\/", "/")) }
                    val code = conn.responseCode
                    Log.d("BeaconScan", "simple response: $code")
                    conn.disconnect()
                    code in 200..299
                }.getOrElse { e ->
                    Log.e("BeaconScan", "simple error: ${e.javaClass.simpleName}: ${e.message}", e)
                    false
                }
            }

            if (success) {
                withContext(Dispatchers.IO) { ScanStore.removeFirst(this@SimpleMeasurementActivity, PENDING_SIMPLE_FILE) }
            } else {
                Toast.makeText(this, "送信失敗 — 次回スキャン時に再送します", Toast.LENGTH_SHORT).show()
                updatePendingCount()
                return
            }
        }

        Toast.makeText(this, "送信成功 (計${total}件)", Toast.LENGTH_SHORT).show()
        updatePendingCount()
    }

    private fun getOrCreateDeviceUuid(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_UUID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_UUID, it).apply()
        }
    }

    private fun updatePendingCount() {
        val count = ScanStore.count(this, PENDING_SIMPLE_FILE)
        if (count == 0) {
            tvPendingCount.visibility = View.GONE
        } else {
            val byLabel = ScanStore.countByLabel(this, PENDING_SIMPLE_FILE)
            tvPendingCount.text = buildString {
                append("未送信スキャン数: ${count}回\n")
                byLabel.forEach { (label, n) -> append("  $label: ${n}回\n") }
            }.trimEnd()
            tvPendingCount.visibility = View.VISIBLE
        }
        btnSendPending.isEnabled = count > 0 && !isScanInProgress
        btnDiscardPending.isEnabled = count > 0 && !isScanInProgress
    }

    private fun showDiscardSelectionDialog() {
        val byLabel = ScanStore.countByLabel(this, PENDING_SIMPLE_FILE)
        if (byLabel.isEmpty()) return
        val labels = byLabel.keys.toList()
        val counts = labels.map { byLabel[it] ?: 0 }
        val checked = BooleanArray(labels.size) { false }
        val items = labels.mapIndexed { i, label -> "$label (${counts[i]}回)" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("破棄するデータを選択")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("破棄する") { _, _ ->
                val selectedLabels = labels.filterIndexed { i, _ -> checked[i] }.toSet()
                if (selectedLabels.isEmpty()) return@setPositiveButton
                ScanStore.removeByLabels(this, selectedLabels, PENDING_SIMPLE_FILE)
                updatePendingCount()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showHelpDialog() {
        val msg = """
【簡易測定とは】
周辺の無線LANアクセスポイント（AP）をスキャンし、
ビーコン情報のみを収集してサーバーへ送信します。
接続試行は行わないため、高速に測定できます。

━━━━━━━━━━━━━━━━━━
【取得する情報】
━━━━━━━━━━━━━━━━━━
■ bssid / ssids / rssi_dbm
■ frequency_mhz / band / channel_width_mhz
■ wifi_standard / security / capabilities_raw
■ location（GPS位置情報）
■ device（端末情報）

━━━━━━━━━━━━━━━━━━
【自動スキャンとラベル】
━━━━━━━━━━━━━━━━━━
自動スキャンをONにすると5秒間隔でスキャンを繰り返します。
OFFにした際に開始〜終了時刻のラベルを自動生成し、
同一セッションのデータをまとめて管理できます。

━━━━━━━━━━━━━━━━━━
【サプリカント状態取得（AP）との違い】
━━━━━━━━━━━━━━━━━━
簡易測定はスキャンのみ。
サプリカント状態取得（AP）は各APへの接続試行データ（supplicant状態）も取得します。
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("簡易測定について")
            .setMessage(msg)
            .setPositiveButton("閉じる", null)
            .show()
    }
}
