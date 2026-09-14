package com.example.beacon_scan

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

const val DEFAULT_SERVER_URL = "http://192.168.11.119:8081/test_receive2.php"
const val DEFAULT_SIMPLE_SERVER_URL = "http://192.168.11.119:8081/simple_receive.php"
const val DEFAULT_SSID_SERVER_URL = "http://192.168.11.119:8081/test_receive2.php"
const val PREFS_NAME = "beacon_scan_prefs"
const val KEY_DEVICE_UUID = "device_uuid"
const val KEY_SERVER_URL = "server_url"
const val KEY_SIMPLE_SERVER_URL = "simple_server_url"
const val KEY_SSID_SERVER_URL = "ssid_server_url"
const val KEY_SEND_MODE = "send_mode"
const val KEY_SIMPLE_SEND_MODE = "simple_send_mode"
const val KEY_SSID_SEND_MODE = "ssid_send_mode"
const val PENDING_FILE = "pending_scans.json"
const val PENDING_SIMPLE_FILE = "pending_simple_scans.json"
const val PENDING_SSID_FILE = "pending_ssid_scans.json"
const val AUTO_SCAN_INTERVAL_MS = 5_000L

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
    val securityTypesRaw: List<Int> = emptyList(),
    val supplicantStates: List<String> = emptyList(),
    val supplicantFinalState: String = "NOT_MEASURED",
    val supplicantElapsedMs: Long = -1L
)

object ScanStore {
    fun load(context: Context, fileName: String = PENDING_FILE): JSONArray {
        val file = File(context.filesDir, fileName)
        return if (file.exists()) runCatching { JSONArray(file.readText()) }.getOrElse { JSONArray() }
        else JSONArray()
    }

    fun append(context: Context, entry: JSONObject, fileName: String = PENDING_FILE) {
        val all = load(context, fileName)
        all.put(entry)
        File(context.filesDir, fileName).writeText(all.toString())
    }

    fun removeFirst(context: Context, fileName: String = PENDING_FILE) {
        val all = load(context, fileName)
        if (all.length() == 0) return
        val remaining = JSONArray()
        for (i in 1 until all.length()) remaining.put(all.get(i))
        if (remaining.length() == 0) File(context.filesDir, fileName).delete()
        else File(context.filesDir, fileName).writeText(remaining.toString())
    }

    fun count(context: Context, fileName: String = PENDING_FILE): Int = load(context, fileName).length()

    fun totalRecords(context: Context, fileName: String = PENDING_FILE): Int {
        val all = load(context, fileName)
        var total = 0
        for (i in 0 until all.length()) {
            total += runCatching { all.getJSONObject(i).getJSONArray("access_points").length() }.getOrElse { 0 }
        }
        return total
    }

    fun updateLabelBySessionId(context: Context, sessionId: String, newLabel: String, fileName: String = PENDING_FILE) {
        val all = load(context, fileName)
        for (i in 0 until all.length()) {
            val obj = all.getJSONObject(i)
            if (obj.optString("label") == sessionId) obj.put("label", newLabel)
        }
        File(context.filesDir, fileName).writeText(all.toString())
    }

    fun countByLabel(context: Context, fileName: String = PENDING_FILE): Map<String, Int> {
        val all = load(context, fileName)
        val result = mutableMapOf<String, Int>()
        for (i in 0 until all.length()) {
            val label = runCatching { all.getJSONObject(i).getString("label") }.getOrElse { "" }
            val key = label.ifEmpty { "（ラベルなし）" }
            result[key] = (result[key] ?: 0) + 1
        }
        return result
    }

    fun removeByLabels(context: Context, labelsToRemove: Set<String>, fileName: String = PENDING_FILE) {
        val all = load(context, fileName)
        val remaining = JSONArray()
        for (i in 0 until all.length()) {
            val obj = all.getJSONObject(i)
            val label = obj.optString("label").ifEmpty { "（ラベルなし）" }
            if (label !in labelsToRemove) remaining.put(obj)
        }
        if (remaining.length() == 0) File(context.filesDir, fileName).delete()
        else File(context.filesDir, fileName).writeText(remaining.toString())
    }

    fun addSupplicantResults(context: Context, scanId: String, supplicantJson: JSONObject, fileName: String = PENDING_FILE) {
        val all = load(context, fileName)
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
        File(context.filesDir, fileName).writeText(all.toString())
    }
}

data class SsidGroup(
    val ssid: String,
    val accessPoints: MutableList<AccessPoint>
)

class SsidApAdapter(
    private val items: MutableList<SsidGroup>,
    private val onSelectionChanged: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<SsidApAdapter.ViewHolder>() {

    var isMeasurementColoring = false
    var measuringBssid: String? = null

    var isSelectionMode = false
        set(value) {
            field = value
            if (!value) selectedSsids.clear()
            notifyDataSetChanged()
        }

    private val selectedSsids = mutableSetOf<String>()

    fun getSelectedAps(): List<AccessPoint> =
        items.filter { it.ssid in selectedSsids }.flatMap { it.accessPoints }

    fun getSelectedSsids(): Set<String> = selectedSsids.toSet()

    fun getSelectedCount(): Int = selectedSsids.size

    fun areAllSelected(): Boolean = items.isNotEmpty() && items.all { it.ssid in selectedSsids }

    fun selectAll() {
        items.forEach { selectedSsids.add(it.ssid) }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedSsids.size)
    }

    fun clearSelection() {
        selectedSsids.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSsid: TextView = view.findViewById(R.id.tvSsid)
        val tvBssidInfo: TextView = view.findViewById(R.id.tvBssidInfo)
        val tvSignal: TextView = view.findViewById(R.id.tvSignal)
        val tvStandard: TextView = view.findViewById(R.id.tvStandard)
        val tvSecurity: TextView = view.findViewById(R.id.tvSecurity)
        val checkBoxSelect: CheckBox = view.findViewById(R.id.checkBoxSelect)
        val viewMeasuredOverlay: View = view.findViewById(R.id.viewMeasuredOverlay)
        val tvMeasurementStatus: TextView = view.findViewById(R.id.tvMeasurementStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ssid_group, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = items[position]
        val aps = group.accessPoints

        holder.tvSsid.text = if (group.ssid.isEmpty()) "（非公開）" else group.ssid

        holder.tvBssidInfo.text = when {
            aps.size == 1 -> aps[0].bssid
            aps.size <= 3 -> "${aps.size}台: ${aps.joinToString(" / ") { it.bssid }}"
            else -> "${aps.size}台: ${aps.take(3).joinToString(" / ") { it.bssid }} 他${aps.size - 3}台"
        }

        val bestAp = aps.maxByOrNull { it.rssiDbm } ?: aps[0]
        holder.tvSignal.text = "${bestAp.rssiDbm} dBm  |  ${bestAp.band}  |  ${bestAp.channelWidthMhz}MHz幅"
        holder.tvStandard.text = aps.map { it.wifiStandard }.distinct().joinToString(" / ")
        holder.tvSecurity.text = aps.map { it.security }.distinct().joinToString(" / ")

        val allMeasured = aps.all { it.supplicantFinalState != "NOT_MEASURED" }
        val anyMeasuring = aps.any { it.bssid == measuringBssid }
        val anyMeasured = aps.any { it.supplicantFinalState != "NOT_MEASURED" }

        holder.viewMeasuredOverlay.visibility =
            if (isMeasurementColoring && allMeasured) View.VISIBLE else View.GONE

        holder.tvMeasurementStatus.text = when {
            anyMeasuring -> "測定中"
            allMeasured -> "測定済み"
            anyMeasured -> "一部測定済み"
            else -> "未測定"
        }

        if (isSelectionMode) {
            holder.checkBoxSelect.visibility = View.VISIBLE
            holder.checkBoxSelect.isChecked = group.ssid in selectedSsids
            holder.itemView.setOnClickListener {
                val nowChecked = group.ssid !in selectedSsids
                if (nowChecked) selectedSsids.add(group.ssid) else selectedSsids.remove(group.ssid)
                holder.checkBoxSelect.isChecked = nowChecked
                onSelectionChanged?.invoke(selectedSsids.size)
            }
        } else {
            holder.checkBoxSelect.visibility = View.GONE
            holder.itemView.setOnClickListener(null)
        }
    }

    override fun getItemCount() = items.size
}

class ApAdapter(
    private val items: List<AccessPoint>,
    private val onSelectionChanged: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<ApAdapter.ViewHolder>() {

    var isMeasurementColoring = false
    var measuringBssid: String? = null

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
        val viewMeasuredOverlay: View = view.findViewById(R.id.viewMeasuredOverlay)
        val tvMeasurementStatus: TextView = view.findViewById(R.id.tvMeasurementStatus)
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

        holder.viewMeasuredOverlay.visibility =
            if (isMeasurementColoring && ap.supplicantFinalState != "NOT_MEASURED") View.VISIBLE else View.GONE

        holder.tvMeasurementStatus.text = when {
            ap.supplicantFinalState != "NOT_MEASURED" -> "測定済み"
            ap.bssid == measuringBssid -> "測定中"
            else -> "未測定"
        }

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
