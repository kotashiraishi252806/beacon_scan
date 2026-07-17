package com.example.beacon_scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class SupplicantScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCAN_ID = "scan_id"
        const val EXTRA_AP_LIST_JSON = "ap_list_json"
        private const val TIMEOUT_MS = 5_000
        private const val DUMMY_PASSPHRASE = "DUMMY_MEAS_12345"
    }

    data class StateRecord(val state: String, val timestamp: String)

    data class ApResult(
        val apId: String,
        val ssid: String,
        val bssid: String,
        val finalState: String,
        val failureReason: String?,
        val stateTransitions: List<StateRecord>,
        val elapsedMs: Long
    )

    private lateinit var tvProgress: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnStop: Button
    private lateinit var connectivityManager: ConnectivityManager

    private val results = mutableListOf<ApResult>()
    private lateinit var adapter: SupplicantResultAdapter

    @Volatile private var stopRequested = false
    @Volatile private var currentCallback: ConnectivityManager.NetworkCallback? = null

    private val currentTransitions = mutableListOf<StateRecord>()

    @Suppress("DEPRECATION")
    private val supplicantReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != WifiManager.SUPPLICANT_STATE_CHANGED_ACTION) return
            val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(WifiManager.EXTRA_NEW_STATE, SupplicantState::class.java)
            } else {
                intent.getParcelableExtra<SupplicantState>(WifiManager.EXTRA_NEW_STATE)
            } ?: return
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            currentTransitions.add(StateRecord(state.name, ts))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_supplicant_scan)
        supportActionBar?.apply {
            title = "Supplicant 状態測定"
            setDisplayHomeAsUpEnabled(true)
        }

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        tvProgress = findViewById(R.id.tvProgress)
        progressBar = findViewById(R.id.progressBar)
        recyclerView = findViewById(R.id.recyclerView)
        btnStop = findViewById(R.id.btnStop)

        adapter = SupplicantResultAdapter(results)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        @Suppress("DEPRECATION")
        registerReceiver(supplicantReceiver, IntentFilter(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION))

        btnStop.setOnClickListener {
            if (stopRequested || btnStop.text == "閉じる") {
                finish()
                return@setOnClickListener
            }
            stopRequested = true
            btnStop.text = "停止中..."
            btnStop.isEnabled = false
            currentCallback?.let { cb ->
                runCatching { connectivityManager.unregisterNetworkCallback(cb) }
                currentCallback = null
            }
        }

        val scanId = intent.getStringExtra(EXTRA_SCAN_ID) ?: ""
        val apListJson = intent.getStringExtra(EXTRA_AP_LIST_JSON) ?: run { finish(); return }
        val apList = parseApList(apListJson)

        progressBar.max = apList.size

        lifecycleScope.launch { runMeasurement(apList, scanId) }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(supplicantReceiver)
        stopRequested = true
        currentCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private suspend fun runMeasurement(apList: List<AccessPoint>, scanId: String) {
        val measuredAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())

        for ((i, ap) in apList.withIndex()) {
            if (stopRequested) break

            tvProgress.text = "測定中 ${i + 1}/${apList.size}: ${ap.ssids.firstOrNull() ?: "(非公開)"}"
            progressBar.progress = i

            currentTransitions.clear()
            val result = measureSingleAp(ap, i + 1)

            results.add(result)
            adapter.notifyItemInserted(results.size - 1)
            recyclerView.scrollToPosition(results.size - 1)

            delay(500)
        }

        val stopped = stopRequested
        tvProgress.text = if (!stopped) "測定完了 (${results.size}台)" else "測定停止 (${results.size}/${apList.size}台)"
        progressBar.progress = if (!stopped) apList.size else progressBar.progress
        btnStop.text = "閉じる"
        btnStop.isEnabled = true

        if (results.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                val json = buildSupplicantJson(measuredAt, apList.size)
                ScanStore.addSupplicantResults(this@SupplicantScanActivity, scanId, json)
            }
        }
    }

    private suspend fun measureSingleAp(ap: AccessPoint, index: Int): ApResult {
        val startMs = System.currentTimeMillis()

        val specifier = buildSpecifier(ap) ?: return ApResult(
            apId = "AP$index",
            ssid = ap.ssids.firstOrNull() ?: "",
            bssid = ap.bssid,
            finalState = "SKIPPED",
            failureReason = "EAP/WEP不対応",
            stateTransitions = emptyList(),
            elapsedMs = 0L
        )

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        var finalState = "TIMEOUT"
        var failureReason: String? = "timeout"

        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        finalState = "COMPLETED"
                        failureReason = null
                        currentCallback = null
                        runCatching { connectivityManager.unregisterNetworkCallback(this) }
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onUnavailable() {
                        currentCallback = null
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
                currentCallback = cb
                cont.invokeOnCancellation {
                    currentCallback = null
                    runCatching { connectivityManager.unregisterNetworkCallback(cb) }
                }
                connectivityManager.requestNetwork(request, cb, TIMEOUT_MS)
            }
        } catch (_: Exception) {}

        val elapsed = System.currentTimeMillis() - startMs
        val transitions = currentTransitions.toList()

        if (finalState == "TIMEOUT" && transitions.isNotEmpty()) {
            val last = transitions.last().state
            if (last != "DISCONNECTED" && last != "INACTIVE" && last != "SCANNING") {
                finalState = last
                failureReason = "timeout_at_$last"
            }
        }

        return ApResult(
            apId = "AP$index",
            ssid = ap.ssids.firstOrNull() ?: "",
            bssid = ap.bssid,
            finalState = finalState,
            failureReason = failureReason,
            stateTransitions = transitions,
            elapsedMs = elapsed
        )
    }

    private fun buildSpecifier(ap: AccessPoint): WifiNetworkSpecifier? {
        val mac = runCatching { MacAddress.fromString(ap.bssid) }.getOrNull() ?: return null
        val builder = WifiNetworkSpecifier.Builder().setBssid(mac)
        val caps = ap.capabilitiesRaw

        when {
            ap.security == "WEP" -> return null
            caps.contains("EAP") && !caps.contains("OWE") -> return null
            caps.contains("OWE") -> builder.setIsEnhancedOpen(true)
            ap.security == "WPA3" || ap.security == "WPA2/WPA3" ->
                builder.setWpa3Passphrase(DUMMY_PASSPHRASE)
            ap.security == "WPA2" || ap.security == "WPA" ->
                builder.setWpa2Passphrase(DUMMY_PASSPHRASE)
        }

        return runCatching { builder.build() }.getOrNull()
    }

    private fun buildSupplicantJson(measuredAt: String, apCount: Int): JSONObject {
        return JSONObject().apply {
            put("measured_at", measuredAt)
            put("detected_ap_count", apCount)
            put("results", JSONArray().apply {
                results.forEach { r ->
                    put(JSONObject().apply {
                        put("ap_id", r.apId)
                        put("ssid", r.ssid)
                        put("bssid", r.bssid)
                        put("final_state", r.finalState)
                        put("failure_reason", r.failureReason ?: JSONObject.NULL)
                        put("state_transitions", JSONArray().apply {
                            r.stateTransitions.forEach { t ->
                                put(JSONObject().apply {
                                    put("state", t.state)
                                    put("t", t.timestamp)
                                })
                            }
                        })
                        put("elapsed_ms", r.elapsedMs)
                    })
                }
            })
        }
    }

    private fun parseApList(json: String): List<AccessPoint> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val ssidsArr = o.getJSONArray("ssids")
                AccessPoint(
                    bssid = o.getString("bssid"),
                    mldMacAddress = o.optString("mld_mac_address").ifEmpty { null },
                    oui = o.getString("oui"),
                    ssids = (0 until ssidsArr.length()).map { ssidsArr.getString(it) },
                    rssiDbm = o.getInt("rssi_dbm"),
                    frequencyMhz = o.getInt("frequency_mhz"),
                    band = o.getString("band"),
                    channelWidthMhz = o.getInt("channel_width_mhz"),
                    wifiStandard = o.getString("wifi_standard"),
                    wifiStandardCode = o.getInt("wifi_standard_code"),
                    security = o.getString("security"),
                    capabilitiesRaw = o.getString("capabilities_raw")
                )
            }.getOrNull()
        }
    }
}

class SupplicantResultAdapter(private val items: List<SupplicantScanActivity.ApResult>)
    : RecyclerView.Adapter<SupplicantResultAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvHeader: TextView = view.findViewById(R.id.tvHeader)
        val tvBssid: TextView = view.findViewById(R.id.tvBssid)
        val tvTransitions: TextView = view.findViewById(R.id.tvTransitions)
        val tvElapsed: TextView = view.findViewById(R.id.tvElapsed)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_supplicant_result, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val r = items[position]
        val ssid = r.ssid.ifEmpty { "(非公開)" }
        holder.tvHeader.text = "${r.apId} | $ssid | ${r.finalState}"
        holder.tvHeader.setTextColor(
            when (r.finalState) {
                "COMPLETED" -> Color.parseColor("#4CAF50")
                "TIMEOUT", "SKIPPED" -> Color.parseColor("#9E9E9E")
                else -> Color.parseColor("#FF9800")
            }
        )
        holder.tvBssid.text = r.bssid
        val transStr = r.stateTransitions.joinToString(" → ") { it.state }
        holder.tvTransitions.text = if (transStr.isEmpty()) "(遷移なし)" else transStr
        holder.tvElapsed.text = "${r.elapsedMs} ms"
    }

    override fun getItemCount() = items.size
}
