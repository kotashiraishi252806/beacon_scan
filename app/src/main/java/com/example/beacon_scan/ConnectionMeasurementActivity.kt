package com.example.beacon_scan

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
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

class ConnectionMeasurementActivity : AppCompatActivity() {

    // ── OSサービス ──────────────────────────────────────────
    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // ── 画面部品 ─────────────────────────────────────────────
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
    private lateinit var layoutMoveButtons: android.view.ViewGroup
    private lateinit var btnNextLocation: Button
    private lateinit var btnEndSession: Button
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
    private var latestScanId: String? = null
    private var isInSelectionMode = false
    private var pendingScanId: String? = null
    private var pendingLocation: Location? = null
    private var pendingLabel: String? = null

    // ── セッション ───────────────────────────────────────────
    private var sessionId: String? = null
    private var sessionStartTime: Date? = null
    private var selectedBssids: Set<String> = emptySet()

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
    private var isProcessingResults = false
    private var isManualMeasuring = false
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

    // ── Supplicant測定 ────────────────────────────────────────
    private lateinit var measurer: SupplicantMeasurer

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
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        measurer = SupplicantMeasurer(this, connectivityManager)

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
        layoutMoveButtons = findViewById(R.id.layoutMoveButtons)
        btnNextLocation = findViewById(R.id.btnNextLocation)
        btnEndSession = findViewById(R.id.btnEndSession)
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
            measurer.requestStop()
            btnStopMeasurement.isEnabled = false
            btnStopMeasurement.text = "中断中..."
        }

        btnNextLocation.setOnClickListener {
            layoutMoveButtons.visibility = View.GONE
            checkPermissionsAndScan()
        }

        btnEndSession.setOnClickListener {
            val sid = sessionId ?: return@setOnClickListener
            val startTime = sessionStartTime ?: return@setOnClickListener
            sessionId = null
            sessionStartTime = null
            selectedBssids = emptySet()
            hideScanResultsView()
            btnScan.isEnabled = true
            tvEmpty.visibility = View.VISIBLE
            showAutoLabelDialog(sid, startTime)
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

            val count = selectedAps.size
            AlertDialog.Builder(this)
                .setTitle("Supplicant測定を開始します")
                .setMessage(
                    "選択した ${count} 件のAPを順番に測定します。\n\n" +
                    "各APごとにAndroidの「接続確認ダイアログ」が表示されます。\n" +
                    "ダイアログの候補はAP1件ずつ表示されるため、最大 ${count} 回のダイアログ操作が必要です。"
                )
                .setPositiveButton("開始") { _, _ ->
                    if (sessionId == null) {
                        sessionId = UUID.randomUUID().toString()
                        sessionStartTime = Date()
                    }
                    selectedBssids = selectedAps.map { it.bssid }.toSet()
                    startMeasurementCycleAp(selectedAps, location, scanId)
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

        switchAutoScan.isEnabled = false

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

        registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        measurer.register()

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

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(wifiScanReceiver)
        measurer.unregister()
        autoScanHandler.removeCallbacks(autoScanRunnable)
        stopLocationUpdates()
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
                    withContext(Dispatchers.IO) { ScanStore.updateLabelBySessionId(this@ConnectionMeasurementActivity, sessionId, autoLabel) }
                    updatePendingCount()
                }
            }
            .setNegativeButton("変更して保存") { _, _ ->
                val customLabel = editText.text?.toString()?.trim()?.ifEmpty { autoLabel } ?: autoLabel
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { ScanStore.updateLabelBySessionId(this@ConnectionMeasurementActivity, sessionId, customLabel) }
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

    private fun startMeasurementCycleAp(targetAps: List<AccessPoint>, location: Location?, scanId: String) {
        val sid = sessionId ?: return
        val apCount = targetAps.size

        isInSelectionMode = false
        adapter.isSelectionMode = false
        btnMeasureSelected.visibility = View.GONE
        btnSelectAll.visibility = View.GONE
        btnSelectAll.text = "全て選択"
        layoutMoveButtons.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        btnToggleList.visibility = View.VISIBLE
        btnToggleList.text = "閉じる"

        apList.clear()
        apList.addAll(targetAps)
        adapter.isMeasurementColoring = true
        adapter.notifyDataSetChanged()

        tvSsidCount.text = "セッション測定中 (${apCount}台)..."
        tvSsidCount.visibility = View.VISIBLE
        btnStopMeasurement.visibility = View.VISIBLE
        btnStopMeasurement.isEnabled = true
        btnStopMeasurement.text = "測定中断"
        tvEmpty.visibility = View.GONE
        btnScan.isEnabled = false
        isScanInProgress = true
        isManualMeasuring = true

        lifecycleScope.launch {
            val measuredList = measurer.measureAll(
                targetAps,
                onProgress = { progress, total, name ->
                    val remaining = total - progress
                    tvSsidCount.text = "セッション測定中 $progress/$total (残り${remaining}件): $name"
                },
                onApStart = { ap ->
                    adapter.measuringBssid = ap.bssid
                    val idx = apList.indexOfFirst { it.bssid == ap.bssid }
                    if (idx > 0) {
                        apList.add(0, apList.removeAt(idx))
                    }
                    adapter.notifyDataSetChanged()
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
            adapter.measuringBssid = null

            val measuredByBssid = measuredList.associateBy { it.bssid }
            val fullList = apList.map { ap -> measuredByBssid[ap.bssid] ?: ap }
            apList.clear()
            apList.addAll(fullList)
            adapter.notifyDataSetChanged()

            withContext(Dispatchers.IO) { saveToPending(measuredList, location, scanId, sid) }
            updatePendingCount()

            if (switchSendMode.isChecked) trySendAllPending()

            isScanInProgress = false
            isManualMeasuring = false
            btnScan.isEnabled = false

            tvSsidCount.text = "測定完了 (${apCount}台)\n次の測定場所に移動してください"
            layoutMoveButtons.visibility = View.VISIBLE
            updatePendingCount()
        }
    }

    private fun hideScanResultsView() {
        tvSsidCount.visibility = View.GONE
        btnToggleList.visibility = View.GONE
        btnStopMeasurement.visibility = View.GONE
        btnMeasureSelected.visibility = View.GONE
        btnSelectAll.visibility = View.GONE
        layoutMoveButtons.visibility = View.GONE
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
                        btnScan.isEnabled = !switchAutoScan.isChecked
                        Toast.makeText(this, "スキャンがスロットリングされています。しばらく待ってから再試行してください", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    private fun onScanResultsReady() {
        if (!isScanInProgress || isProcessingResults || isInSelectionMode || isManualMeasuring) return
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
                val measuredList = measurer.measureAll(snapshotList, onProgress = { progress, total, name ->
                    val remaining = total - progress
                    tvSsidCount.text = "今回検出: ${total}件\nSupplicant測定中 $progress/$total (残り${remaining}件): $name"
                })

                btnStopMeasurement.visibility = View.GONE

                apList.clear()
                apList.addAll(measuredList)
                adapter.notifyDataSetChanged()

                withContext(Dispatchers.IO) { saveToPending(measuredList, location, scanId, label) }
                val totalRecords = withContext(Dispatchers.IO) { ScanStore.totalRecords(this@ConnectionMeasurementActivity) }
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
                    val remaining = maxOf(0L, AUTO_SCAN_INTERVAL_MS - (System.currentTimeMillis() - scanStartMs))
                    autoScanHandler.postDelayed(autoScanRunnable, remaining)
                }
            }
        } else {
            // セッション2回目以降：前回選択BSSIDで自動測定
            if (sessionId != null && selectedBssids.isNotEmpty()) {
                val targetAps = apList.filter { it.bssid in selectedBssids }
                isScanInProgress = false
                isProcessingResults = false
                if (targetAps.isNotEmpty()) {
                    startMeasurementCycleAp(targetAps, location, scanId)
                } else {
                    tvSsidCount.text = "選択済みBSSIDが検出されませんでした\n次の測定場所に移動してください"
                    tvSsidCount.visibility = View.VISIBLE
                    layoutMoveButtons.visibility = View.VISIBLE
                    tvEmpty.visibility = View.GONE
                    AlertDialog.Builder(this)
                        .setTitle("選択済みBSSIDが検出されませんでした")
                        .setMessage("対象BSSID:\n${selectedBssids.joinToString("\n")}")
                        .setPositiveButton("OK", null)
                        .show()
                }
                return
            }

            // 手動スキャン初回：AP選択モードに移行
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
        val pending = withContext(Dispatchers.IO) { ScanStore.load(this@ConnectionMeasurementActivity) }
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
                    Log.d("BeaconScan", "response: $code")
                    conn.disconnect()
                    code in 200..299
                }.getOrElse { e ->
                    Log.e("BeaconScan", "error: ${e.javaClass.simpleName}: ${e.message}", e)
                    false
                }
            }

            if (success) {
                withContext(Dispatchers.IO) { ScanStore.removeFirst(this@ConnectionMeasurementActivity) }
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
