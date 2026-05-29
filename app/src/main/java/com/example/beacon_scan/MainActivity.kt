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
private const val PENDING_FILE = "pending_scans.json"
private const val AUTO_SCAN_INTERVAL_MS = 10_000L

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

    fun clearAll(context: Context) {
        File(context.filesDir, PENDING_FILE).delete()
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnScan: Button
    private lateinit var switchAutoScan: SwitchCompat
    private lateinit var etUrl: TextInputEditText
    private lateinit var tvEmpty: TextView
    private lateinit var tvStatus: TextView
    private val apList = mutableListOf<ScanResult>()
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
        switchAutoScan = findViewById(R.id.switchAutoScan)
        etUrl = findViewById(R.id.etUrl)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvStatus = findViewById(R.id.tvPending)

        adapter = ApAdapter(apList)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

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

        switchAutoScan.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!isScanInProgress) {
                    autoScanHandler.postDelayed(autoScanRunnable, AUTO_SCAN_INTERVAL_MS)
                }
                tvStatus.text = "自動スキャン ON (10秒間隔)"
            } else {
                autoScanHandler.removeCallbacks(autoScanRunnable)
                if (!isScanInProgress) updatePendingCount()
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
        // キャッシュがあれば即座に latestLocation へ反映
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

    private fun updatePendingCount() {
        val count = ScanStore.count(this)
        tvStatus.text = if (count == 0) "スキャン待機中" else "未送信: ${count}件"
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

    private fun startScan() {
        if (isScanInProgress) return
        isScanInProgress = true
        btnScan.isEnabled = false
        tvStatus.text = "スキャン中..."
        @Suppress("DEPRECATION")
        val started = wifiManager.startScan()
        if (!started) {
            Toast.makeText(this, "スキャンがスロットリングされています。キャッシュ結果を使用します", Toast.LENGTH_SHORT).show()
            onScanResultsReady()
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
        val results = wifiManager.scanResults
        apList.clear()
        apList.addAll(results.sortedByDescending { it.level })
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (apList.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (apList.isEmpty()) View.GONE else View.VISIBLE

        val location = latestLocation
        val snapshotList = apList.toList()

        lifecycleScope.launch {
            withContext(Dispatchers.IO) { saveToPending(snapshotList, location) }
            updatePendingCount()

            tvStatus.text = "送信中..."
            trySendAllPending()

            isScanInProgress = false
            btnScan.isEnabled = true

            if (switchAutoScan.isChecked) {
                autoScanHandler.postDelayed(autoScanRunnable, AUTO_SCAN_INTERVAL_MS)
            }
        }
    }

    private fun saveToPending(results: List<ScanResult>, location: Location?) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
        val resultsArray = JSONArray()
        for (ap in results) {
            @Suppress("DEPRECATION")
            resultsArray.put(JSONObject().apply {
                put("ssid", ap.SSID.ifEmpty { "" })
                put("bssid", ap.BSSID)
                put("rssi", ap.level)
            })
        }
        val entry = JSONObject().apply {
            put("device_id", getOrCreateDeviceUuid())
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("timestamp", timestamp)
            if (location != null) {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("location_accuracy", location.accuracy.toDouble())
            } else {
                put("latitude", JSONObject.NULL)
                put("longitude", JSONObject.NULL)
                put("location_accuracy", JSONObject.NULL)
            }
            put("results", resultsArray)
        }
        ScanStore.append(this, entry)
    }

    private suspend fun trySendAllPending() {
        val pending = withContext(Dispatchers.IO) { ScanStore.load(this@MainActivity) }
        if (pending.length() == 0) {
            updatePendingCount()
            return
        }

        val success = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(getServerUrl()).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(pending.toString()) }
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
            withContext(Dispatchers.IO) { ScanStore.clearAll(this@MainActivity) }
            val sentCount = pending.length()
            tvStatus.text = "送信成功 (計${sentCount}件)"
            Toast.makeText(this, "送信成功 (計${sentCount}件)", Toast.LENGTH_SHORT).show()
        } else {
            val remaining = withContext(Dispatchers.IO) { ScanStore.count(this@MainActivity) }
            tvStatus.text = "未送信: ${remaining}件 (送信失敗)"
            Toast.makeText(this, "送信失敗 — 次回スキャン時に再送します", Toast.LENGTH_SHORT).show()
        }
        updatePendingCount()
    }
}

class ApAdapter(private val items: List<ScanResult>) : RecyclerView.Adapter<ApAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSsid: TextView = view.findViewById(R.id.tvSsid)
        val tvBssid: TextView = view.findViewById(R.id.tvBssid)
        val tvSignal: TextView = view.findViewById(R.id.tvSignal)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ap, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ap = items[position]
        @Suppress("DEPRECATION")
        holder.tvSsid.text = ap.SSID.ifEmpty { "(非公開)" }
        holder.tvBssid.text = "BSSID: ${ap.BSSID}"
        holder.tvSignal.text = "電波強度: ${ap.level} dBm"
    }

    override fun getItemCount() = items.size
}
