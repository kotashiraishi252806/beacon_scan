package com.example.beacon_scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.MacAddress
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiEnterpriseConfig
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.SupplicantState
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val DIALOG_TIMEOUT_MS = 10_000L
private const val DUMMY_PASSPHRASE = "DUMMY_MEAS_12345"

/**
 * APへの順次Supplicant測定を担うクラス。
 * MainActivity から接続試行・状態記録・タイムアウト管理を切り出したもの。
 *
 * 使い方：
 *   onCreate  → register()
 *   onDestroy → unregister()
 *   停止ボタン → requestStop()
 *   測定開始  → measureAll()
 */
class SupplicantMeasurer(
    private val context: Context,
    private val connectivityManager: ConnectivityManager
) {
    // AP1件の測定中に届いたSupplicant状態遷移を順番に記録するリスト
    private val supplicantTransitions = mutableListOf<SupplicantStateEntry>()

    // measureForAp開始時刻（supplicantReceiverから経過時間を計算するために使用）
    @Volatile private var measureStartMs: Long = 0L

    // 現在アクティブなNetworkCallback(cb)の参照（解除時に使用）
    @Volatile private var supplicantCallback: ConnectivityManager.NetworkCallback? = null

    // BroadcastReceiverとコルーチンを繋ぐ橋（受け皿①②からPhase1/2を起こす）
    @Volatile private var supplicantContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    // OSタイムアウト前にBroadcastReceiverがcont.resume()を呼んだかどうかを示すフラグ
    @Volatile private var brEarlyExitOccurred = false

    // 受け皿①：wpa_supplicantからのSupplicant状態変化を受け取るBroadcastReceiver
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
            supplicantTransitions.add(SupplicantStateEntry(state.name, System.currentTimeMillis() - measureStartMs))
            val cont = supplicantContinuation ?: return
            if (!cont.isActive) return
            when (state) {
                SupplicantState.COMPLETED -> {
                    brEarlyExitOccurred = true
                    cont.resume(Unit)
                }
                SupplicantState.DISCONNECTED -> {
                    if (supplicantTransitions.any {
                        it.state == "ASSOCIATING" || it.state == "ASSOCIATED" || it.state == "FOUR_WAY_HANDSHAKE" || it.state == "GROUP_HANDSHAKE"
                    }) {
                        brEarlyExitOccurred = true
                        cont.resume(Unit)
                    }
                }
                else -> {}
            }
        }
    }

    // BroadcastReceiverを登録する（onCreateで呼ぶ）
    @Suppress("DEPRECATION")
    fun register() {
        context.registerReceiver(
            supplicantReceiver,
            IntentFilter(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
        )
    }

    // BroadcastReceiverとNetworkCallbackを解除する（onDestroyで呼ぶ）
    fun unregister() {
        runCatching { context.unregisterReceiver(supplicantReceiver) }
        supplicantCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
    }

    // 全APを順次測定して結果リストを返す
    suspend fun measureAll(
        apList: List<AccessPoint>,
        onProgress: (Int, Int, String) -> Unit,
        onApStart: (AccessPoint) -> Unit = {},
        onApFinished: (AccessPoint) -> Unit = {}
    ): List<AccessPoint> {
        val results = mutableListOf<AccessPoint>()
        for ((i, ap) in apList.withIndex()) {
            onProgress(i + 1, apList.size, ap.ssids.firstOrNull() ?: ap.bssid)
            onApStart(ap)
            val result = measureForAp(ap)
            results.add(result)
            onApFinished(result)
            delay(300)
        }
        return results
    }

    // AP1件に対してSupplicant測定を行い結果を返す
    @Suppress("DEPRECATION")
    private suspend fun measureForAp(ap: AccessPoint): AccessPoint {
        val startMs = System.currentTimeMillis()
        measureStartMs = startMs
        supplicantTransitions.clear()
        supplicantContinuation = null
        brEarlyExitOccurred = false

        val specifier = buildSpecifier(ap) ?: return ap.copy(
            supplicantFinalState = "SKIPPED",
            supplicantElapsedMs = 0L
        )

        val request = NetworkRequest.Builder()//接続要求（7/23)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)//wifiでつなぐ
            .setNetworkSpecifier(specifier)//接続先AP指定
            .build()

        var finalState = "NOT_FOUND"

        // 受け皿②：ConnectivityManagerからの接続成否通知を受け取るNetworkCallback
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                finalState = "NETWORK_AVAILABLE"
                supplicantContinuation?.let { if (it.isActive) it.resume(Unit) }
                supplicantContinuation = null
                supplicantCallback = null
                runCatching { connectivityManager.unregisterNetworkCallback(this) }
            }
        }

        // 単一待機：最大10秒
        // onAvailable / onUnavailable / terminal state のいずれかで早期終了
        // continuation設定後にrequestNetworkを呼ぶことでonUnavailableとのレース条件を回避
        withTimeoutOrNull(DIALOG_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                supplicantContinuation = cont
                supplicantCallback = cb
                cont.invokeOnCancellation {
                    supplicantContinuation = null
                    supplicantCallback = null
                    runCatching { connectivityManager.unregisterNetworkCallback(cb) }
                }
                connectivityManager.requestNetwork(request, cb, Handler(Looper.getMainLooper()))
            }
        }

        supplicantContinuation = null
        supplicantCallback = null
        runCatching { connectivityManager.unregisterNetworkCallback(cb) }

        val elapsed = System.currentTimeMillis() - startMs
        val transitions = supplicantTransitions.toList()

        finalState = when {
            finalState == "NETWORK_AVAILABLE" -> "NETWORK_AVAILABLE"
            brEarlyExitOccurred -> {
                if (transitions.isNotEmpty() && transitions.last().state == "COMPLETED") {
                    "AUTH_COMPLETED"
                } else {
                    val prevState = if (transitions.size >= 2) transitions[transitions.size - 2].state else transitions.last().state
                    "PRE_TIMEOUT_$prevState"
                }
            }
            else -> {
                val mostAdvanced = mostAdvancedState(transitions)
                if (mostAdvanced != null) "TIMEOUT_$mostAdvanced" else "TIMEOUT_NULL"
            }
        }

        return ap.copy(
            supplicantStates = transitions,
            supplicantFinalState = finalState,
            supplicantElapsedMs = elapsed
        )
    }

    private fun mostAdvancedState(transitions: List<SupplicantStateEntry>): String? {
        if (transitions.isEmpty()) return null
        val rank = mapOf(
            "DISCONNECTED" to 0, "INACTIVE" to 1, "SCANNING" to 2,
            "AUTHENTICATING" to 3, "ASSOCIATING" to 4, "ASSOCIATED" to 5,
            "FOUR_WAY_HANDSHAKE" to 6, "GROUP_HANDSHAKE" to 7, "COMPLETED" to 8
        )
        return transitions.maxByOrNull { rank[it.state] ?: -1 }?.state
    }

    // BSSID・SSID・セキュリティ種別からWifiNetworkSpecifierを組み立てる
    private fun buildSpecifier(ap: AccessPoint): WifiNetworkSpecifier? {
        val mac = runCatching { MacAddress.fromString(ap.bssid) }.getOrNull() ?: return null
        val ssid = ap.ssids.firstOrNull()
        val builder = WifiNetworkSpecifier.Builder().setBssid(mac)
        if (ssid != null) builder.setSsid(ssid)

        if (ap.security == "WEP") return null

        val types = ap.securityTypesRaw.toSet()
        val structured = types.isNotEmpty() // API33+実機のみtrue

        val isOwe = if (structured) WifiConfiguration.SECURITY_TYPE_OWE in types
                    else ap.capabilitiesRaw.contains("OWE")

        val isPasspoint = structured &&
            (WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2 in types || WifiInfo.SECURITY_TYPE_PASSPOINT_R3 in types)

        val isWpa3Ent192 = structured && WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT in types
        val isWpa3EntStd = structured && WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE in types

        val isEap = if (structured) {
            WifiConfiguration.SECURITY_TYPE_EAP in types || isWpa3Ent192 || isWpa3EntStd || isPasspoint
        } else {
            ap.capabilitiesRaw.contains("EAP") && !ap.capabilitiesRaw.contains("OWE")
        }

        fun dummyEapConfig() = WifiEnterpriseConfig().apply {
            eapMethod = WifiEnterpriseConfig.Eap.PEAP
            phase2Method = WifiEnterpriseConfig.Phase2.MSCHAPV2
            identity = "DUMMY_USER"
            password = DUMMY_PASSPHRASE
        }

        when {
            // API31以上要求のメソッドだが、structured=trueは実質API33以上でのみ成立するため安全（4.4節）
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (isWpa3Ent192 || isWpa3EntStd) -> {
                val eapConfig = dummyEapConfig()
                if (isWpa3Ent192) builder.setWpa3Enterprise192BitModeConfig(eapConfig)
                else builder.setWpa3EnterpriseStandardModeConfig(eapConfig)
            }
            isEap -> builder.setWpa2EnterpriseConfig(dummyEapConfig())
            isOwe -> builder.setIsEnhancedOpen(true)
            ap.security == "WPA3" || ap.security == "WPA2/WPA3" ->
                builder.setWpa3Passphrase(DUMMY_PASSPHRASE)
            ap.security == "WPA2" || ap.security == "WPA" ->
                builder.setWpa2Passphrase(DUMMY_PASSPHRASE)
        }
        return runCatching { builder.build() }.getOrNull()
    }
}
