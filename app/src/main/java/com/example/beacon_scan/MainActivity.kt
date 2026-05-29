package com.example.beacon_scan

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.textfield.TextInputEditText
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
}

class MainActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnScan: Button
    private lateinit var etUrl: TextInputEditText
    private lateinit var tvEmpty: TextView
    private lateinit var tvStatus: TextView
    private val apList = mutableListOf<ScanResult>()
    private lateinit var adapter: ApAdapter

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

        recyclerView = findViewById(R.id.recyclerView)
        btnScan = findViewById(R.id.btnScan)
        etUrl = findViewById(R.id.etUrl)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvStatus = findViewById(R.id.tvPending)

        adapter = ApAdapter(apList)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // 保存済みURLを表示（なければデフォルト）
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        etUrl.setText(prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL))

        // キーボードのDoneでURL保存
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) saveUrl()
            false
        }

        btnScan.setOnClickListener { saveUrl(); checkPermissionsAndScan() }

        registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))

        updatePendingCount()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(wifiScanReceiver)
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        @Suppress("DEPRECATION")
        val results = wifiManager.scanResults
        apList.clear()
        apList.addAll(results.sortedByDescending { it.level })
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (apList.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (apList.isEmpty()) View.GONE else View.VISIBLE

        if (apList.isEmpty()) {
            tvStatus.text = "APが見つかりませんでした"
            btnScan.isEnabled = true
            return
        }

        lifecycleScope.launch {
            // まず端末に保存
            withContext(Dispatchers.IO) { saveToPending(apList) }
            updatePendingCount()

            // 保存済み全件を順番に送信
            tvStatus.text = "送信中..."
            trySendAllPending()
            btnScan.isEnabled = true
        }
    }

    private fun saveToPending(results: List<ScanResult>) {
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
            put("results", resultsArray)
        }
        ScanStore.append(this, entry)
    }

    private suspend fun trySendAllPending() {
        var sentCount = 0
        while (true) {
            val pending = withContext(Dispatchers.IO) { ScanStore.load(this@MainActivity) }
            if (pending.length() == 0) break

            val entry = pending.getJSONObject(0)
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = URL(getServerUrl()).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    conn.doOutput = true
                    conn.connectTimeout = 5000
                    conn.readTimeout = 10000
                    OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(entry.toString()) }
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
                sentCount++
            } else {
                break
            }
        }

        val remaining = withContext(Dispatchers.IO) { ScanStore.count(this@MainActivity) }
        if (remaining == 0) {
            tvStatus.text = "送信成功 (計${sentCount}件)"
            Toast.makeText(this, "送信成功 (計${sentCount}件)", Toast.LENGTH_SHORT).show()
        } else {
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
