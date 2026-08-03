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
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val DIALOG_TIMEOUT_MS = 15_000L
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
    private val supplicantTransitions = mutableListOf<String>()

    // 現在アクティブなNetworkCallback(cb)の参照（解除時に使用）
    @Volatile private var supplicantCallback: ConnectivityManager.NetworkCallback? = null

    // BroadcastReceiverとコルーチンを繋ぐ橋（受け皿①②からPhase1/2を起こす）
    @Volatile private var supplicantContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    // 測定停止ボタン押下でtrueになり、次のAPからCANCELLEDにする
    @Volatile var stopRequested = false
        private set

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
            supplicantTransitions.add(state.name)
            val cont = supplicantContinuation ?: return
            if (!cont.isActive) return
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

    // 測定を中断要求する（停止ボタンから呼ぶ）
    fun requestStop() {
        stopRequested = true
        supplicantContinuation?.let { if (it.isActive) it.resume(Unit) }
        supplicantContinuation = null
        supplicantCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        supplicantCallback = null
    }

    // 全APを順次測定して結果リストを返す
    suspend fun measureAll(
        apList: List<AccessPoint>,
        onProgress: (Int, Int, String) -> Unit,
        onApStart: (AccessPoint) -> Unit = {},
        onApFinished: (AccessPoint) -> Unit = {}
    ): List<AccessPoint> {
        stopRequested = false
        val results = mutableListOf<AccessPoint>()
        for ((i, ap) in apList.withIndex()) {
            if (stopRequested) {
                results.add(ap.copy(supplicantFinalState = "CANCELLED", supplicantElapsedMs = 0L))
                continue
            }
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
        supplicantTransitions.clear()
        supplicantContinuation = null

        val specifier = buildSpecifier(ap) ?: return ap.copy(
            supplicantFinalState = "SKIPPED",
            supplicantElapsedMs = 0L
        )

        val request = NetworkRequest.Builder()//接続要求（7/23)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)//wifiでつなぐ
            .setNetworkSpecifier(specifier)//接続先AP指定
            .build()

        var finalState = "NOT_FOUND"
        var unavailableFired = false

        // 受け皿②：ConnectivityManagerからの接続成否通知を受け取るNetworkCallback
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                finalState = "COMPLETED"
                supplicantContinuation?.let { if (it.isActive) it.resume(Unit) }
                supplicantContinuation = null
                supplicantCallback = null
                runCatching { connectivityManager.unregisterNetworkCallback(this) }
            }
            override fun onUnavailable() {
                unavailableFired = true
                supplicantContinuation?.let { if (it.isActive) it.resume(Unit) }
                supplicantContinuation = null
                supplicantCallback = null
                runCatching { connectivityManager.unregisterNetworkCallback(this) }
            }
        }

        // 単一待機：最大15秒
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
                connectivityManager.requestNetwork(request, cb)
            }
        }

        supplicantContinuation = null
        supplicantCallback = null
        runCatching { connectivityManager.unregisterNetworkCallback(cb) }

        val elapsed = System.currentTimeMillis() - startMs
        val transitions = supplicantTransitions.toList()

        finalState = when {
            stopRequested && finalState != "COMPLETED" -> "CANCELLED"
            finalState == "COMPLETED"                  -> "COMPLETED"
            unavailableFired && transitions.isEmpty()  -> "NOT_FOUND"
            transitions.isNotEmpty() -> {
                val last = transitions.last()
                if (last == "DISCONNECTED" || last == "INACTIVE" || last == "SCANNING") {
                    "FAILED_AT_$last"
                } else {
                    "TIMEOUT_AT_$last"
                }
            }
            else -> "FAILED_AT_DIALOG_TIMEOUT"
        }

        return ap.copy(
            supplicantStates = transitions,
            supplicantFinalState = finalState,
            supplicantElapsedMs = elapsed
        )
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
