package com.example.beacon_scan

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val DEFAULT_SERVER_URL = "http://192.168.11.119:8081/test_receive2.php"
private const val PREFS_NAME = "beacon_scan_prefs"
private const val KEY_DEVICE_UUID = "device_uuid"
private const val KEY_SERVER_URL = "server_url"
private const val KEY_SEND_MODE = "send_mode"
private const val PENDING_FILE = "pending_scans.json"
private const val AUTO_SCAN_INTERVAL_MS = 10_000L

data class AccessPoint(
    val bssid: String,
    val oui: String,
    val ssids: List<String>,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val band: String,
    val channelWidthMhz: Int,
    val wifiStandard: String,
    val wifiStandardCode: Int,
    val security: String,
    val capabilitiesRaw: String
)

object ScanStore {
    fun load(context: Context): JSONArray {
        val file = File(context.filesDir, PENDING_FILE)
        return if (file.exists()) runCatching { JSONArray(file.readText()) }.getOrElse { JSONArray() }
        else JSONArray()
    }

    fun append(context: Context, entry: JSONObject) {
        val all = load(context)
        all.put(entry)
        File(context.filesDir, PENDING_FILE).writeText(all.toString())
    }

    fun removeFirst(context: Context) {
        val all = load(context)
        if (all.length() == 0) return
        val remaining = JSONArray()
        for (i in 1 until all.length()) remaining.put(all.get(i))
        if (remaining.length() == 0) File(context.filesDir, PENDING_FILE).delete()
        else File(context.filesDir, PENDING_FILE).writeText(remaining.toString())
    }

    fun count(context: Context): Int = load(context).length()

    fun totalRecords(context: Context): Int {
        val all = load(context)
        var total = 0
        for (i in 0 until all.length()) {
            total += runCatching { all.getJSONObject(i).getJSONArray("access_points").length() }.getOrElse { 0 }
        }
        return total
    }

    fun updateLabelBySessionId(context: Context, sessionId: String, newLabel: String) {
        val all = load(context)
        for (i in 0 until all.length()) {
            val obj = all.getJSONObject(i)
            if (obj.optString("label") == sessionId) obj.put("label", newLabel)
        }
        File(context.filesDir, PENDING_FILE).writeText(all.toString())
    }

    fun countByLabel(context: Context): Map<String, Int> {
        val all = load(context)
        val result = mutableMapOf<String, Int>()
        for (i in 0 until all.length()) {
            val label = runCatching { all.getJSONObject(i).getString("label") }.getOrElse { "" }
            val key = label.ifEmpty { "（ラベルなし）" }
            result[key] = (result[key] ?: 0) + 1
        }
        return result
    }

    fun clearAll(context: Context) {
        File(context.filesDir, PENDING_FILE).delete()
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnScan: Button
    private lateinit var btnSendPending: Button
    private lateinit var btnDiscardPending: Button
    private lateinit var btnPauseAutoScan: Button
    private lateinit var btnToggleList: Button
    private lateinit var switchAutoScan: SwitchCompat
    private lateinit var switchSendMode: SwitchCompat
    private lateinit var etUrl: TextInputEditText
    private lateinit var tvEmpty: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvSsidCount: TextView
    private val apList = mutableListOf<AccessPoint>()
    private lateinit var adapter: ApAdapter

    private var latestLocation: Location? = null
    private val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, AUTO_SCAN_INTERVAL_MS)
        .setMinUpdateIntervalMillis(5_000L)
        .build()
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            latestLocation = result.lastLocation
        }
    }

    private var isScanInProgress = false
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
            startLocationUpdates()
            startScan()
        } else {
            isScanInProgress = false
            btnScan.isEnabled = true
            Toast.makeText(this, "位置情報の権限が必要です", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        recyclerView = findViewById(R.id.recyclerView)
        btnScan = findViewById(R.id.btnScan)
        btnSendPending = findViewById(R.id.btnSendPending)
        btnDiscardPending = findViewById(R.id.btnDiscardPending)
        btnPauseAutoScan = findViewById(R.id.btnPauseAutoScan)
        btnToggleList = findViewById(R.id.btnToggleList)
        switchAutoScan = findViewById(R.id.switchAutoScan)
        switchSendMode = findViewById(R.id.switchSendMode)
        etUrl = findViewById(R.id.etUrl)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvStatus = findViewById(R.id.tvPending)
        tvSsidCount = findViewById(R.id.tvSsidCount)

        val prefs2 = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        switchSendMode.isChecked = prefs2.getBoolean(KEY_SEND_MODE, true)
        switchSendMode.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SEND_MODE, isChecked).apply()
            if (!isChecked) updatePendingCount()
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

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        etUrl.setText(prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL))

        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) saveUrl()
            false
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
                tvStatus.text = "送信中..."
                trySendAllPending()
                val remainingCount = withContext(Dispatchers.IO) { ScanStore.count(this@MainActivity) }
                if (remainingCount == 0) {
                    tvSsidCount.visibility = View.GONE
                } else {
                    val remainingRecords = withContext(Dispatchers.IO) { ScanStore.totalRecords(this@MainActivity) }
                    if (tvSsidCount.visibility == View.VISIBLE) {
                        val firstLine = tvSsidCount.text.lines().firstOrNull() ?: ""
                        tvSsidCount.text = "$firstLine\n未送信データ合計: ${remainingRecords}件"
                    }
                }
                btnScan.isEnabled = !isScanInProgress
                updatePendingCount()
            }
        }

        btnDiscardPending.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("未送信データの破棄")
                .setMessage("未送信データをすべて削除します。この操作は取り消せません。")
                .setPositiveButton("破棄する") { _, _ ->
                    ScanStore.clearAll(this)
                    tvSsidCount.visibility = View.GONE
                    updatePendingCount()
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }

        btnPauseAutoScan.setOnClickListener {
            if (!isAutoScanPaused) {
                autoScanHandler.removeCallbacks(autoScanRunnable)
                isAutoScanPaused = true
                btnPauseAutoScan.text = "再開"
                tvStatus.text = "自動スキャン 一時停止中"
            } else {
                isAutoScanPaused = false
                btnPauseAutoScan.text = "一時停止"
                tvStatus.text = "自動スキャン ON (10秒間隔)"
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
                if (!isScanInProgress) {
                    autoScanHandler.postDelayed(autoScanRunnable, AUTO_SCAN_INTERVAL_MS)
                }
                tvStatus.text = "自動スキャン ON (10秒間隔)"
            } else {
                autoScanHandler.removeCallbacks(autoScanRunnable)
                isAutoScanPaused = false
                btnPauseAutoScan.visibility = View.GONE
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

        registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))

        updatePendingCount()
    }

    override fun onResume() {
        super.onResume()
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(wifiScanReceiver)
        autoScanHandler.removeCallbacks(autoScanRunnable)
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null && latestLocation == null) latestLocation = location
        }
    }

    private fun saveUrl() {
        val url = etUrl.text?.toString()?.trim() ?: return
        if (url.isNotEmpty()) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SERVER_URL, url).apply()
        }
    }

    private fun getServerUrl(): String {
        val saved = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        return saved.ifEmpty { DEFAULT_SERVER_URL }
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
                    withContext(Dispatchers.IO) { ScanStore.updateLabelBySessionId(this@MainActivity, sessionId, autoLabel) }
                    updatePendingCount()
                }
            }
            .setNegativeButton("変更して保存") { _, _ ->
                val customLabel = editText.text?.toString()?.trim()?.ifEmpty { autoLabel } ?: autoLabel
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { ScanStore.updateLabelBySessionId(this@MainActivity, sessionId, customLabel) }
                    updatePendingCount()
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun updatePendingCount() {
        val count = ScanStore.count(this)
        tvStatus.text = if (count == 0) {
            "スキャン待機中"
        } else {
            val byLabel = ScanStore.countByLabel(this)
            buildString {
                append("未送信スキャン数: ${count}回\n")
                byLabel.forEach { (label, n) -> append("  $label: ${n}回\n") }
            }.trimEnd()
        }
        btnSendPending.isEnabled = count > 0 && !isScanInProgress
        btnDiscardPending.isEnabled = count > 0 && !isScanInProgress
    }

    private fun getOrCreateDeviceUuid(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_UUID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_UUID, it).apply()
        }
    }

    private fun getBand(frequencyMhz: Int): String = when {
        frequencyMhz in 2400..2500 -> "2.4GHz"
        frequencyMhz in 4900..5924 -> "5GHz"
        frequencyMhz >= 5925 -> "6GHz"
        else -> "Unknown"
    }

    private fun getWifiStandardLabel(code: Int): String = when (code) {
        ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g"
        ScanResult.WIFI_STANDARD_11N    -> "802.11n"
        ScanResult.WIFI_STANDARD_11AC   -> "802.11ac"
        ScanResult.WIFI_STANDARD_11AX   -> "802.11ax"
        ScanResult.WIFI_STANDARD_11AD   -> "802.11ad"
        ScanResult.WIFI_STANDARD_11BE   -> "802.11be"
        else -> "Unknown"
    }

    private fun getSecurity(capabilities: String): String = when {
        capabilities.contains("WPA3") -> "WPA3"
        capabilities.contains("WPA2") -> "WPA2"
        capabilities.contains("WPA")  -> "WPA"
        capabilities.contains("WEP")  -> "WEP"
        else -> "Open"
    }

    @Suppress("DEPRECATION")
    private fun groupByBssid(results: List<ScanResult>): List<AccessPoint> {
        val grouped = mutableMapOf<String, MutableList<ScanResult>>()
        for (r in results) {
            grouped.getOrPut(r.BSSID) { mutableListOf() }.add(r)
        }
        return grouped.map { (bssid, scanResults) ->
            val representative = scanResults.maxByOrNull { it.level } ?: scanResults.first()
            val ssids = scanResults.mapNotNull { it.SSID.ifEmpty { null } }.distinct()
            val wifiStandardCode = representative.wifiStandard
            val channelWidthMhz = when (representative.channelWidth) {
                ScanResult.CHANNEL_WIDTH_20MHZ          -> 20
                ScanResult.CHANNEL_WIDTH_40MHZ          -> 40
                ScanResult.CHANNEL_WIDTH_80MHZ          -> 80
                ScanResult.CHANNEL_WIDTH_160MHZ         -> 160
                ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
                5                                       -> 320
                else                                    -> 20
            }
            AccessPoint(
                bssid = bssid,
                oui = bssid.take(8),
                ssids = ssids,
                rssiDbm = representative.level,
                frequencyMhz = representative.frequency,
                band = getBand(representative.frequency),
                channelWidthMhz = channelWidthMhz,
                wifiStandard = getWifiStandardLabel(wifiStandardCode),
                wifiStandardCode = wifiStandardCode,
                security = getSecurity(representative.capabilities),
                capabilitiesRaw = representative.capabilities
            )
        }.sortedByDescending { it.rssiDbm }
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
        btnScan.isEnabled = false
        tvStatus.text = "スキャン中..."
        @Suppress("DEPRECATION")
        val started = wifiManager.startScan()
        if (!started) {
            isScanInProgress = false
            btnScan.isEnabled = true
            Toast.makeText(this, "スキャンがスロットリングされています。しばらく待ってから再試行してください", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onScanResultsReady() {
        if (!isScanInProgress) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            isScanInProgress = false
            btnScan.isEnabled = true
            return
        }

        @Suppress("DEPRECATION")
        val rawResults = wifiManager.scanResults
        val grouped = groupByBssid(rawResults)
        apList.clear()
        apList.addAll(grouped)
        adapter.notifyDataSetChanged()

        if (apList.isEmpty()) {
            tvSsidCount.visibility = View.GONE
            btnToggleList.visibility = View.GONE
            recyclerView.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvSsidCount.text = "今回検出: ${apList.size}件\n未送信データ合計: 集計中..."
            tvSsidCount.visibility = View.VISIBLE
            btnToggleList.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            btnToggleList.text = "一覧"
            tvEmpty.visibility = View.GONE
        }

        val location = latestLocation
        val snapshotList = apList.toList()
        val scanId = UUID.randomUUID().toString()
        val currentScanCount = apList.size
        val label = autoScanSessionId ?: ""

        lifecycleScope.launch {
            withContext(Dispatchers.IO) { saveToPending(snapshotList, location, scanId, label) }
            val totalRecords = withContext(Dispatchers.IO) { ScanStore.totalRecords(this@MainActivity) }
            if (currentScanCount > 0) {
                tvSsidCount.text = "今回検出: ${currentScanCount}件\n未送信データ合計: ${totalRecords}件"
            }
            updatePendingCount()

            if (switchSendMode.isChecked) {
                tvStatus.text = "送信中..."
                trySendAllPending()
                val remainingRecords = withContext(Dispatchers.IO) { ScanStore.totalRecords(this@MainActivity) }
                if (currentScanCount > 0) {
                    tvSsidCount.text = "今回検出: ${currentScanCount}件\n未送信データ合計: ${remainingRecords}件"
                }
            }

            isScanInProgress = false
            btnScan.isEnabled = true
            updatePendingCount()

            if (switchAutoScan.isChecked) {
                autoScanHandler.postDelayed(autoScanRunnable, AUTO_SCAN_INTERVAL_MS)
            }
        }
    }

    private fun saveToPending(accessPoints: List<AccessPoint>, location: Location?, scanId: String, label: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
        val apArray = JSONArray()
        for (ap in accessPoints) {
            apArray.put(JSONObject().apply {
                put("bssid", ap.bssid)
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
        ScanStore.append(this, entry)
    }

    private suspend fun trySendAllPending() {
        val pending = withContext(Dispatchers.IO) { ScanStore.load(this@MainActivity) }
        val total = pending.length()
        if (total == 0) {
            updatePendingCount()
            return
        }

        for (i in 0 until total) {
            val singleArray = JSONArray().put(pending.getJSONObject(i))
            tvStatus.text = "送信中... (${i + 1}/${total}件)"

            val success = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL(getServerUrl()).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    conn.doOutput = true
                    conn.connectTimeout = 5000
                    conn.readTimeout = 10000
                    OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(singleArray.toString()) }
                    val code = conn.responseCode
                    Log.d("BeaconScan", "response: $code")
                    conn.disconnect()
                    code in 200..299
                }.getOrElse { e ->
                    Log.e("BeaconScan", "error: ${e.javaClass.simpleName}: ${e.message}", e)
                    false
                }
            }

            if (success) {
                withContext(Dispatchers.IO) { ScanStore.removeFirst(this@MainActivity) }
            } else {
                val remaining = withContext(Dispatchers.IO) { ScanStore.count(this@MainActivity) }
                tvStatus.text = "未送信: ${remaining}件 (送信失敗)"
                Toast.makeText(this, "送信失敗 — 次回スキャン時に再送します", Toast.LENGTH_SHORT).show()
                updatePendingCount()
                return
            }
        }

        tvStatus.text = "送信成功 (計${total}件)"
        Toast.makeText(this, "送信成功 (計${total}件)", Toast.LENGTH_SHORT).show()
        updatePendingCount()
    }
}

class ApAdapter(private val items: List<AccessPoint>) : RecyclerView.Adapter<ApAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvBssid: TextView = view.findViewById(R.id.tvBssid)
        val tvSsids: TextView = view.findViewById(R.id.tvSsids)
        val tvSignal: TextView = view.findViewById(R.id.tvSignal)
        val tvStandard: TextView = view.findViewById(R.id.tvStandard)
        val tvSecurity: TextView = view.findViewById(R.id.tvSecurity)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ap, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ap = items[position]
        holder.tvBssid.text = ap.bssid
        holder.tvSsids.text = if (ap.ssids.isEmpty()) "(非公開)" else ap.ssids.joinToString(" / ")
        holder.tvSignal.text = "${ap.rssiDbm} dBm  |  ${ap.band}  |  ${ap.channelWidthMhz}MHz幅"
        holder.tvStandard.text = "${ap.wifiStandard} (${ap.wifiStandardCode})"
        holder.tvSecurity.text = ap.security
    }

    override fun getItemCount() = items.size
}
