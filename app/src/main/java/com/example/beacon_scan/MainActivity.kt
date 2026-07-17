package com.example.beacon_scan

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiEnterpriseConfig
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
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
import android.widget.CheckBox
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
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
private const val AUTO_SCAN_INTERVAL_MS = 5_000L
private const val AUTH_TIMEOUT_MS = 8_000L
private const val DIALOG_TIMEOUT_MS = 15_000L
private const val DUMMY_PASSPHRASE = "DUMMY_MEAS_12345"

data class AccessPoint(
    val bssid: String,
    val mldMacAddress: String?,
    val oui: String,
    val ssids: List<String>,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val band: String,
    val channelWidthMhz: Int,
    val wifiStandard: String,
    val wifiStandardCode: Int,
    val security: String,
    val capabilitiesRaw: String,
    val supplicantStates: List<String> = emptyList(),
    val supplicantFinalState: String = "NOT_MEASURED",
    val supplicantElapsedMs: Long = -1L
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

    fun removeByLabels(context: Context, labelsToRemove: Set<String>) {
        val all = load(context)
        val remaining = JSONArray()
        for (i in 0 until all.length()) {
            val obj = all.getJSONObject(i)
            val label = obj.optString("label").ifEmpty { "（ラベルなし）" }
            if (label !in labelsToRemove) remaining.put(obj)
        }
        if (remaining.length() == 0) File(context.filesDir, PENDING_FILE).delete()
        else File(context.filesDir, PENDING_FILE).writeText(remaining.toString())
    }

    fun addSupplicantResults(context: Context, scanId: String, supplicantJson: JSONObject) {
        val all = load(context)
        var found = false
        for (i in 0 until all.length()) {
            val obj = all.getJSONObject(i)
            if (obj.optString("scan_id") == scanId) {
                obj.put("supplicant_results", supplicantJson)
                found = true
                break
            }
        }
        if (!found) {
            all.put(JSONObject().apply {
                put("scan_id", scanId)
                put("type", "supplicant_only")
                put("supplicant_results", supplicantJson)
            })
        }
        File(context.filesDir, PENDING_FILE).writeText(all.toString())
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnHelp: android.widget.ImageButton
    private lateinit var btnScan: Button
    private lateinit var btnSendPending: Button
    private lateinit var btnDiscardPending: Button
    private lateinit var btnPauseAutoScan: Button
    private lateinit var btnToggleList: Button
    private lateinit var btnStopMeasurement: Button
    private lateinit var btnMeasureSelected: Button
    private lateinit var btnSelectAll: Button
    private lateinit var switchAutoScan: SwitchCompat
    private lateinit var switchSendMode: SwitchCompat
    private lateinit var etUrl: TextInputEditText
    private lateinit var tvEmpty: TextView
    private lateinit var tvAutoScanStatus: TextView
    private lateinit var tvPendingCount: TextView
    private lateinit var tvSsidCount: TextView
    private val apList = mutableListOf<AccessPoint>()
    private lateinit var adapter: ApAdapter
    private var latestScanId: String? = null
    private var isInSelectionMode = false
    private var pendingScanId: String? = null
    private var pendingLocation: Location? = null
    private var pendingLabel: String? = null

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
    private var scanStartMs: Long = 0L
    private var isProcessingResults = false
    private var isProgrammaticSendModeChange = false
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

    private val supplicantTransitions = mutableListOf<String>()
    @Volatile private var supplicantCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var supplicantContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null
    @Volatile private var stopMeasurementRequested = false

    @Suppress("DEPRECATION")
    private val supplicantReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != WifiManager.SUPPLICANT_STATE_CHANGED_ACTION) return
            val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(WifiManager.EXTRA_NEW_STATE, SupplicantState::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<SupplicantState>(WifiManager.EXTRA_NEW_STATE)
            } ?: return
            val isFirst = supplicantTransitions.isEmpty()
            supplicantTransitions.add(state.name)
            val cont = supplicantContinuation ?: return
            if (!cont.isActive) return
            if (isFirst) {
                // フェーズ1完了：最初のSupplicant状態受信＝OKが押された
                cont.resume(Unit)
                return
            }
            // フェーズ2：認証終了を検知
            when (state) {
                SupplicantState.COMPLETED -> cont.resume(Unit)
                SupplicantState.DISCONNECTED -> {
                    if (supplicantTransitions.any {
                        it == "ASSOCIATED" || it == "FOUR_WAY_HANDSHAKE" || it == "GROUP_HANDSHAKE"
                    }) cont.resume(Unit)
                }
                else -> {}
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
            btnScan.isEnabled = !switchAutoScan.isChecked
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
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        recyclerView = findViewById(R.id.recyclerView)
        btnHelp = findViewById(R.id.btnHelp)
        btnScan = findViewById(R.id.btnScan)
        btnSendPending = findViewById(R.id.btnSendPending)
        btnDiscardPending = findViewById(R.id.btnDiscardPending)
        btnPauseAutoScan = findViewById(R.id.btnPauseAutoScan)
        btnToggleList = findViewById(R.id.btnToggleList)
        btnStopMeasurement = findViewById(R.id.btnStopMeasurement)
        btnMeasureSelected = findViewById(R.id.btnMeasureSelected)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        switchAutoScan = findViewById(R.id.switchAutoScan)
        switchSendMode = findViewById(R.id.switchSendMode)
        etUrl = findViewById(R.id.etUrl)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvAutoScanStatus = findViewById(R.id.tvAutoScanStatus)
        tvPendingCount = findViewById(R.id.tvPendingCount)
        tvSsidCount = findViewById(R.id.tvSsidCount)

        val prefs2 = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        switchSendMode.isChecked = prefs2.getBoolean(KEY_SEND_MODE, true)
        switchSendMode.setOnCheckedChangeListener { _, isChecked ->
            if (!isProgrammaticSendModeChange) {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_SEND_MODE, isChecked).apply()
            }
            if (!isChecked) updatePendingCount()
        }

        adapter = ApAdapter(apList) { count ->
            btnMeasureSelected.text = "選択したAPを測定 (${count}件)"
            btnMeasureSelected.isEnabled = count > 0
            if (isInSelectionMode) {
                btnSelectAll.text = if (adapter.areAllSelected()) "全て解除" else "全て選択"
            }
        }
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

        btnStopMeasurement.setOnClickListener {
            stopMeasurementRequested = true
            supplicantContinuation?.let { if (it.isActive) it.resume(Unit) }
            supplicantContinuation = null
            supplicantCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
            supplicantCallback = null
            btnStopMeasurement.isEnabled = false
            btnStopMeasurement.text = "中断中..."
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        etUrl.setText(prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL))

        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) saveUrl()
            false
        }

        btnHelp.setOnClickListener { showHelpDialog() }

        btnSelectAll.setOnClickListener {
            if (!isInSelectionMode) return@setOnClickListener
            if (adapter.areAllSelected()) {
                adapter.clearSelection()
                btnSelectAll.text = "全て選択"
            } else {
                adapter.selectAll()
                btnSelectAll.text = "全て解除"
            }
        }

        btnScan.setOnClickListener {
            saveUrl()
            if (isInSelectionMode) {
                isInSelectionMode = false
                adapter.isSelectionMode = false
                btnMeasureSelected.visibility = View.GONE
                btnSelectAll.text = "全て選択"
            }
            autoScanHandler.removeCallbacks(autoScanRunnable)
            checkPermissionsAndScan()
        }

        btnMeasureSelected.setOnClickListener {
            val selectedAps = adapter.getSelectedItems()
            if (selectedAps.isEmpty()) return@setOnClickListener
            val scanId = pendingScanId ?: return@setOnClickListener
            val location = pendingLocation
            val label = pendingLabel ?: ""

            val count = selectedAps.size
            AlertDialog.Builder(this)
                .setTitle("Supplicant測定を開始します")
                .setMessage(
                    "選択した ${count} 件のAPを順番に測定します。\n\n" +
                    "各APごとにAndroidの「接続確認ダイアログ」が表示されます。\n" +
                    "ダイアログの候補はAP1件ずつ表示されるため、合計 ${count} 回のダイアログ操作が必要です。"
                )
                .setPositiveButton("開始") { _, _ ->
                    isInSelectionMode = false
                    adapter.isSelectionMode = false
                    btnMeasureSelected.visibility = View.GONE
                    btnSelectAll.visibility = View.GONE
                    btnSelectAll.text = "全て選択"
                    recyclerView.visibility = View.VISIBLE
                    btnToggleList.text = "閉じる"

                    val currentScanCount = apList.size
                    tvSsidCount.text = "今回検出: ${currentScanCount}件\nSupplicant測定中..."
                    btnStopMeasurement.visibility = View.VISIBLE
                    btnStopMeasurement.isEnabled = true
                    btnStopMeasurement.text = "測定中断"
                    isScanInProgress = true

                    lifecycleScope.launch {
                        val measuredList = measureAllSupplicant(
                            selectedAps,
                            onProgress = { progress, total, name ->
                                val remaining = total - progress
                                tvSsidCount.text = "今回検出: ${currentScanCount}件\nSupplicant測定中 $progress/$total (残り${remaining}件): $name"
                            },
                            onApStart = { ap ->
                                val idx = apList.indexOfFirst { it.bssid == ap.bssid }
                                if (idx > 0) {
                                    apList.add(0, apList.removeAt(idx))
                                    adapter.notifyDataSetChanged()
                                }
                            },
                            onApFinished = { result ->
                                val idx = apList.indexOfFirst { it.bssid == result.bssid }
                                if (idx >= 0) {
                                    apList.removeAt(idx)
                                    apList.add(result)
                                    adapter.notifyDataSetChanged()
                                }
                            }
                        )

                        btnStopMeasurement.visibility = View.GONE
                        stopMeasurementRequested = false

                        val measuredByBssid = measuredList.associateBy { it.bssid }
                        val fullList = apList.map { ap -> measuredByBssid[ap.bssid] ?: ap }
                        apList.clear()
                        apList.addAll(fullList)
                        adapter.notifyDataSetChanged()

                        withContext(Dispatchers.IO) { saveToPending(measuredList, location, scanId, label) }
                        val totalRecords = withContext(Dispatchers.IO) { ScanStore.totalRecords(this@MainActivity) }
                        tvSsidCount.text = "今回検出: ${currentScanCount}件\n未送信データ合計: ${totalRecords}件"
                        updatePendingCount()

                        if (switchSendMode.isChecked) {
                            trySendAllPending()
                            hideScanResultsView()
                        }

                        isScanInProgress = false
                        btnScan.isEnabled = true
                        updatePendingCount()
                    }
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }

        btnSendPending.setOnClickListener {
            saveUrl()
            btnSendPending.isEnabled = false
            btnScan.isEnabled = false
            lifecycleScope.launch {
                trySendAllPending()
                hideScanResultsView()
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
                if (isInSelectionMode) {
                    isInSelectionMode = false
                    adapter.isSelectionMode = false
                    btnMeasureSelected.visibility = View.GONE
                    btnSelectAll.text = "全て選択"
                }
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
                if (!isScanInProgress) {
                    autoScanHandler.postDelayed(autoScanRunnable, AUTO_SCAN_INTERVAL_MS)
                }
                tvAutoScanStatus.text = "自動測定中"
                tvAutoScanStatus.visibility = View.VISIBLE
            } else {
                autoScanHandler.removeCallbacks(autoScanRunnable)
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

        registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        @Suppress("DEPRECATION")
        registerReceiver(supplicantReceiver, IntentFilter(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION))

        updatePendingCount()
    }

    override fun onResume() {
        super.onResume()
        startLocationUpdates()
        updatePendingCount()
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(wifiScanReceiver)
        unregisterReceiver(supplicantReceiver)
        supplicantCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
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

    private fun showHelpDialog() {
        val msg = """
【概要】
周辺の無線LANアクセスポイント（AP）をスキャンし、測定データをサーバーへ送信します。

━━━━━━━━━━━━━━━━━━
【スキャン単位の情報】
━━━━━━━━━━━━━━━━━━
■ scan_id
　スキャン1回ごとに生成するUUID。

■ device_id
　端末を識別するUUID。初回起動時に生成し永続保持。

■ device（manufacturer / model / android_api）
　端末のメーカー・機種名・APIレベル。常に取得可能。

■ scanned_at
　スキャン実行時刻（ISO 8601形式）。常に取得可能。

■ label
　自動スキャンのセッション単位で付与する識別ラベル。手動スキャンは空文字。

■ location（latitude / longitude / accuracy_m）
　GPS位置情報。取得できない場合は null。
　取得には ACCESS_FINE_LOCATION 権限が必要。

━━━━━━━━━━━━━━━━━━
【APごとの情報】
━━━━━━━━━━━━━━━━━━
■ bssid
　APを識別するMACアドレス。常に取得可能。

■ mld_mac_address
　WiFi 7物理AP（MLD）のMACアドレス。
　null条件：端末がAPI 33未満、またはAPがWiFi 7 MLO非対応。

■ oui
　BSSIDの先頭3オクテット（ベンダー識別子）。BSSIDから常に導出可能。

■ ssids
　このBSSIDが発しているSSID一覧。非公開APの場合は空配列。

■ rssi_dbm
　電波強度（dBm）。値が大きいほど強い（例：-55 > -80）。常に取得可能。

■ frequency_mhz
　使用周波数（MHz）。常に取得可能。

■ band
　frequency_mhzから導出した帯域（2.4GHz / 5GHz / 6GHz）。

■ channel_width_mhz
　チャネル幅（20 / 40 / 80 / 160 / 320 MHz）。常に取得可能。

■ wifi_standard / wifi_standard_code
　Wi-Fi世代（802.11n/ac/ax/be）と対応する整数コード。
　ScanResult.wifiStandard より取得。API 30以上で正確な値が得られる。

■ security
　セキュリティ規格（Open / WEP / WPA / WPA2 / WPA3 / WPA2/WPA3）。
　capabilities_rawの文字列をアプリ側で解析して判定。

■ capabilities_raw
　OSが組み立てたセキュリティ情報の生文字列。
　例：[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP][ESS]
　Android バージョンや端末・APのドライバにより表現が異なる場合がある。

━━━━━━━━━━━━━━━━━━
【Supplicant 状態測定】
━━━━━━━━━━━━━━━━━━
スキャンで検出したAP n台へ順番に接続試行し、
WiFi接続プロセスの状態遷移を記録する機能。

■ 使い方
　1. スキャン実行後に表示される「Supplicant 測定」ボタンをタップ
　2. AP n台に対して自動で順番に接続試行
　3. 完了後、元のスキャンデータに supplicant_results として保存

■ 状態遷移の例（Open AP）
　DISCONNECTED → SCANNING → AUTHENTICATING
　→ ASSOCIATING → ASSOCIATED → COMPLETED

■ 状態遷移の例（WPA2 AP・パスワード不明）
　DISCONNECTED → SCANNING → ASSOCIATING
　→ ASSOCIATED → FOUR_WAY_HANDSHAKE → DISCONNECTED

■ セキュリティ別の動作
　Open       : そのまま接続（COMPLETED到達可能）
　WPA2/WPA   : ダミーパスワードで試行（ハンドシェイク失敗まで記録）
　WPA3       : ダミーパスワード（SAE）で試行
　WEP/EAP   : 非対応のためSKIPPED

■ 制約
　・自動スキャン中は使用不可
　・1台あたり最大5秒タイムアウト
　・測定中は端末のWiFi接続が一時的に切り替わる

━━━━━━━━━━━━━━━━━━
【設計方針】
━━━━━━━━━━━━━━━━━━
取得できない指標は null で送信します。
フィールドの追加は null 許容で後方互換を維持し、
大幅な構造変更時のみ schema_version を更新します。

━━━━━━━━━━━━━━━━━━
【現状の限界】
━━━━━━━━━━━━━━━━━━
・物理AP台数は正確にカウントできない
　MLD MACはMLO対応WiFi 7 APのみ取得可能。
　同一物理APでもSSIDごとに別MLD MACが付く場合がある。

・スリープ中はスキャンが止まる場合がある
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("このアプリについて")
            .setMessage(msg)
            .setPositiveButton("閉じる", null)
            .show()
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

    private fun showDiscardSelectionDialog() {
        val byLabel = ScanStore.countByLabel(this)
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
                ScanStore.removeByLabels(this, selectedLabels)
                val remaining = ScanStore.count(this)
                if (remaining == 0) tvSsidCount.visibility = View.GONE
                updatePendingCount()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun hideScanResultsView() {
        tvSsidCount.visibility = View.GONE
        btnToggleList.visibility = View.GONE
        btnStopMeasurement.visibility = View.GONE
        btnMeasureSelected.visibility = View.GONE
        btnSelectAll.visibility = View.GONE
        recyclerView.visibility = View.GONE
        if (isInSelectionMode) {
            isInSelectionMode = false
            adapter.isSelectionMode = false
        }
    }

    private fun updatePendingCount() {
        val count = ScanStore.count(this)
        if (count == 0) {
            tvPendingCount.visibility = View.GONE
        } else {
            val byLabel = ScanStore.countByLabel(this)
            tvPendingCount.text = buildString {
                append("未送信スキャン数: ${count}回\n")
                byLabel.forEach { (label, n) -> append("  $label: ${n}回\n") }
            }.trimEnd()
            tvPendingCount.visibility = View.VISIBLE
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

    private fun getSecurity(capabilities: String): String {
        val hasWPA2 = capabilities.contains("WPA2")
        val hasSAE  = capabilities.contains("SAE")
        return when {
            hasWPA2 && hasSAE                                          -> "WPA2/WPA3"
            capabilities.contains("WPA3") || hasSAE
                || capabilities.contains("OWE-")
                || capabilities.contains("EAP_SUITE_B")               -> "WPA3"
            hasWPA2                                                    -> "WPA2"
            capabilities.contains("WPA")                              -> "WPA"
            capabilities.contains("WEP")                              -> "WEP"
            else                                                       -> "Open"
        }
    }

    private suspend fun measureAllSupplicant(
        apList: List<AccessPoint>,
        onProgress: (Int, Int, String) -> Unit,
        onApStart: (AccessPoint) -> Unit = {},
        onApFinished: (AccessPoint) -> Unit = {}
    ): List<AccessPoint> {
        stopMeasurementRequested = false
        val results = mutableListOf<AccessPoint>()
        for ((i, ap) in apList.withIndex()) {
            if (stopMeasurementRequested) {
                results.add(ap.copy(supplicantFinalState = "CANCELLED", supplicantElapsedMs = 0L))
                continue
            }
            onProgress(i + 1, apList.size, ap.ssids.firstOrNull() ?: ap.bssid)
            onApStart(ap)
            val result = measureSupplicantForAp(ap)
            results.add(result)
            onApFinished(result)
            delay(300)
        }
        return results
    }

    @Suppress("DEPRECATION")
    private suspend fun measureSupplicantForAp(ap: AccessPoint): AccessPoint {
        val startMs = System.currentTimeMillis()
        supplicantTransitions.clear()
        supplicantContinuation = null

        val specifier = buildSupplicantSpecifier(ap) ?: return ap.copy(
            supplicantFinalState = "SKIPPED",
            supplicantElapsedMs = 0L
        )

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        var finalState = "NOT_FOUND"

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                finalState = "COMPLETED"
                supplicantContinuation?.let { if (it.isActive) it.resume(Unit) }
                supplicantContinuation = null
                supplicantCallback = null
                runCatching { connectivityManager.unregisterNetworkCallback(this) }
            }
            override fun onUnavailable() {
                supplicantContinuation?.let { if (it.isActive) it.resume(Unit) }
                supplicantContinuation = null
                supplicantCallback = null
                runCatching { connectivityManager.unregisterNetworkCallback(this) }
            }
        }
        // フェーズ1：OKが押されるまで待機。OSがダイアログを強制キャンセルしてonUnavailable()も
        // 呼ばれないケースに備え30秒のタイムアウトを設定する。
        // continuation設定後にrequestNetworkを呼ぶことでonUnavailableとのレース条件を回避
        val phase1Completed = withTimeoutOrNull(DIALOG_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                supplicantContinuation = cont
                supplicantCallback = cb
                cont.invokeOnCancellation {
                    supplicantContinuation = null
                    supplicantCallback = null
                    runCatching { connectivityManager.unregisterNetworkCallback(cb) }
                }
                connectivityManager.requestNetwork(request, cb)
            }
        }

        if (phase1Completed == null) {
            // ダイアログがOSに強制キャンセルされ、onUnavailable()も呼ばれなかった
            supplicantContinuation = null
            supplicantCallback = null
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
            return ap.copy(
                supplicantStates = supplicantTransitions.toList(),
                supplicantFinalState = "FAILED_AT_DIALOG_TIMEOUT",
                supplicantElapsedMs = System.currentTimeMillis() - startMs
            )
        }

        if (finalState == "COMPLETED" || stopMeasurementRequested) {
            supplicantCallback = null
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
            return ap.copy(
                supplicantStates = supplicantTransitions.toList(),
                supplicantFinalState = if (stopMeasurementRequested && finalState != "COMPLETED") "CANCELLED" else finalState,
                supplicantElapsedMs = System.currentTimeMillis() - startMs
            )
        }

        // フェーズ2：認証（8秒）
        withTimeoutOrNull(AUTH_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                supplicantContinuation = cont
                cont.invokeOnCancellation { supplicantContinuation = null }
            }
        }

        supplicantContinuation = null
        supplicantCallback = null
        runCatching { connectivityManager.unregisterNetworkCallback(cb) }

        val elapsed = System.currentTimeMillis() - startMs
        val transitions = supplicantTransitions.toList()

        if (finalState != "COMPLETED") {
            finalState = if (transitions.isNotEmpty()) {
                val last = transitions.last()
                if (last == "DISCONNECTED" || last == "INACTIVE" || last == "SCANNING") {
                    "FAILED_AT_$last"
                } else {
                    "TIMEOUT_AT_$last"
                }
            } else {
                "NOT_FOUND"
            }
        }

        return ap.copy(
            supplicantStates = transitions,
            supplicantFinalState = finalState,
            supplicantElapsedMs = elapsed
        )
    }

    private fun buildSupplicantSpecifier(ap: AccessPoint): WifiNetworkSpecifier? {
        val mac = runCatching { MacAddress.fromString(ap.bssid) }.getOrNull() ?: return null
        val ssid = ap.ssids.firstOrNull()
        val builder = WifiNetworkSpecifier.Builder().setBssid(mac)
        if (ssid != null) builder.setSsid(ssid)
        val caps = ap.capabilitiesRaw
        when {
            ap.security == "WEP" -> return null
            caps.contains("EAP") && !caps.contains("OWE") -> {
                val eapConfig = WifiEnterpriseConfig().apply {
                    eapMethod = WifiEnterpriseConfig.Eap.PEAP
                    phase2Method = WifiEnterpriseConfig.Phase2.MSCHAPV2
                    identity = "DUMMY_USER"
                    password = DUMMY_PASSPHRASE
                }
                builder.setWpa2EnterpriseConfig(eapConfig)
            }
            caps.contains("OWE") -> builder.setIsEnhancedOpen(true)
            ap.security == "WPA3" || ap.security == "WPA2/WPA3" ->
                builder.setWpa3Passphrase(DUMMY_PASSPHRASE)
            ap.security == "WPA2" || ap.security == "WPA" ->
                builder.setWpa2Passphrase(DUMMY_PASSPHRASE)
        }
        return runCatching { builder.build() }.getOrNull()
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
            val mldMac = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                representative.apMldMacAddress?.toString()
            } else null
            AccessPoint(
                bssid = bssid,
                mldMacAddress = mldMac,
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
        scanStartMs = System.currentTimeMillis()
        btnScan.isEnabled = false
        @Suppress("DEPRECATION")
        val started = wifiManager.startScan()
        if (!started) {
            isScanInProgress = false
            btnScan.isEnabled = !switchAutoScan.isChecked
            Toast.makeText(this, "スキャンがスロットリングされています。しばらく待ってから再試行してください", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onScanResultsReady() {
        if (!isScanInProgress || isProcessingResults || isInSelectionMode) return
        isProcessingResults = true
        val scanElapsedMs = System.currentTimeMillis() - scanStartMs
        Log.d("BeaconScan", "WiFiスキャン完了: ${scanElapsedMs}ms")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            isScanInProgress = false
            isProcessingResults = false
            btnScan.isEnabled = !switchAutoScan.isChecked
            return
        }

        @Suppress("DEPRECATION")
        val rawResults = wifiManager.scanResults
        val grouped = groupByBssid(rawResults)
        apList.clear()
        apList.addAll(grouped)

        val location = latestLocation
        val scanId = UUID.randomUUID().toString()
        latestScanId = scanId
        val currentScanCount = apList.size
        val label = autoScanSessionId ?: ""

        if (apList.isEmpty()) {
            adapter.isSelectionMode = false
            tvSsidCount.visibility = View.GONE
            btnToggleList.visibility = View.GONE
            btnStopMeasurement.visibility = View.GONE
            btnMeasureSelected.visibility = View.GONE
            btnSelectAll.visibility = View.GONE
            recyclerView.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            isScanInProgress = false
            isProcessingResults = false
            btnScan.isEnabled = !switchAutoScan.isChecked
            return
        }

        if (switchAutoScan.isChecked) {
            adapter.isSelectionMode = false
            tvSsidCount.text = "今回検出: ${currentScanCount}件\nSupplicant測定中..."
            tvSsidCount.visibility = View.VISIBLE
            btnToggleList.visibility = View.VISIBLE
            btnStopMeasurement.visibility = View.VISIBLE
            btnStopMeasurement.isEnabled = true
            btnStopMeasurement.text = "測定中断"
            btnMeasureSelected.visibility = View.GONE
            btnSelectAll.visibility = View.GONE
            btnSelectAll.text = "全て選択"
            recyclerView.visibility = View.GONE
            btnToggleList.text = "一覧"
            tvEmpty.visibility = View.GONE

            val snapshotList = apList.toList()
            lifecycleScope.launch {
                val measuredList = measureAllSupplicant(snapshotList, onProgress = { progress, total, name ->
                    val remaining = total - progress
                    tvSsidCount.text = "今回検出: ${total}件\nSupplicant測定中 $progress/$total (残り${remaining}件): $name"
                })

                btnStopMeasurement.visibility = View.GONE
                stopMeasurementRequested = false

                apList.clear()
                apList.addAll(measuredList)
                adapter.notifyDataSetChanged()

                withContext(Dispatchers.IO) { saveToPending(measuredList, location, scanId, label) }
                val totalRecords = withContext(Dispatchers.IO) { ScanStore.totalRecords(this@MainActivity) }
                if (currentScanCount > 0) {
                    tvSsidCount.text = "今回検出: ${currentScanCount}件\n未送信データ合計: ${totalRecords}件"
                }
                updatePendingCount()

                if (switchSendMode.isChecked) {
                    trySendAllPending()
                    hideScanResultsView()
                }

                isScanInProgress = false
                isProcessingResults = false
                btnScan.isEnabled = !switchAutoScan.isChecked
                updatePendingCount()

                if (switchAutoScan.isChecked) {
                    autoScanHandler.postDelayed(autoScanRunnable, AUTO_SCAN_INTERVAL_MS)
                }
            }
        } else {
            // 手動スキャン：AP選択モードに移行
            pendingScanId = scanId
            pendingLocation = location
            pendingLabel = label

            adapter.isSelectionMode = true

            tvSsidCount.text = "今回検出: ${currentScanCount}件\nAPを選択して「測定」ボタンを押してください"
            tvSsidCount.visibility = View.VISIBLE
            btnToggleList.visibility = View.VISIBLE
            btnToggleList.text = "閉じる"
            recyclerView.visibility = View.VISIBLE
            btnStopMeasurement.visibility = View.GONE
            btnMeasureSelected.text = "選択したAPを測定 (0件)"
            btnMeasureSelected.isEnabled = false
            btnMeasureSelected.visibility = View.VISIBLE
            btnSelectAll.text = "全て選択"
            btnSelectAll.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE

            isInSelectionMode = true
            isScanInProgress = false
            isProcessingResults = false
            btnScan.isEnabled = true
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
                put("supplicant_states", JSONArray(ap.supplicantStates))
                put("supplicant_final_state", ap.supplicantFinalState)
                put("supplicant_elapsed_ms", ap.supplicantElapsedMs)
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
                Toast.makeText(this, "送信失敗 — 次回スキャン時に再送します", Toast.LENGTH_SHORT).show()
                updatePendingCount()
                return
            }
        }

        Toast.makeText(this, "送信成功 (計${total}件)", Toast.LENGTH_SHORT).show()
        updatePendingCount()
    }
}

class ApAdapter(
    private val items: List<AccessPoint>,
    private val onSelectionChanged: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<ApAdapter.ViewHolder>() {

    var isSelectionMode = false
        set(value) {
            field = value
            if (!value) selectedBssids.clear()
            notifyDataSetChanged()
        }

    private val selectedBssids = mutableSetOf<String>()

    fun getSelectedItems(): List<AccessPoint> = items.filter { it.bssid in selectedBssids }
    fun getSelectedCount(): Int = selectedBssids.size
    fun areAllSelected(): Boolean = items.isNotEmpty() && items.all { it.bssid in selectedBssids }

    fun selectAll() {
        items.forEach { selectedBssids.add(it.bssid) }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedBssids.size)
    }

    fun clearSelection() {
        selectedBssids.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvBssid: TextView = view.findViewById(R.id.tvBssid)
        val tvSsids: TextView = view.findViewById(R.id.tvSsids)
        val tvSignal: TextView = view.findViewById(R.id.tvSignal)
        val tvStandard: TextView = view.findViewById(R.id.tvStandard)
        val tvSecurity: TextView = view.findViewById(R.id.tvSecurity)
        val checkBoxSelect: CheckBox = view.findViewById(R.id.checkBoxSelect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ap, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ap = items[position]
        holder.tvBssid.text = if (ap.mldMacAddress != null) "${ap.bssid}  (MLD: ${ap.mldMacAddress})" else ap.bssid
        holder.tvSsids.text = if (ap.ssids.isEmpty()) "(非公開)" else ap.ssids.joinToString(" / ")
        holder.tvSignal.text = "${ap.rssiDbm} dBm  |  ${ap.band}  |  ${ap.channelWidthMhz}MHz幅"
        holder.tvStandard.text = "${ap.wifiStandard} (${ap.wifiStandardCode})"
        holder.tvSecurity.text = ap.security

        if (isSelectionMode) {
            holder.checkBoxSelect.visibility = View.VISIBLE
            holder.checkBoxSelect.isChecked = ap.bssid in selectedBssids
            holder.itemView.setOnClickListener {
                val nowChecked = ap.bssid !in selectedBssids
                if (nowChecked) selectedBssids.add(ap.bssid) else selectedBssids.remove(ap.bssid)
                holder.checkBoxSelect.isChecked = nowChecked
                onSelectionChanged?.invoke(selectedBssids.size)
            }
        } else {
            holder.checkBoxSelect.visibility = View.GONE
            holder.itemView.setOnClickListener(null)
        }
    }

    override fun getItemCount() = items.size
}
