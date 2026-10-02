package com.example.test_wifi_information

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.NetworkInfo.DetailedState
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi

interface WifiStateChangeListener {
    fun onWifiStateChange(isConnected:Boolean)
}

/**
 * wifiScanした結果を処理するためのクラス
 * @constructor context アプリケーションのコンテキスト applicationContext,activity　位置情報へアクセスするためにactivityが必要
 * */
class WifiScan(private val context: Context, wifiStateChangeListener: WifiStateChangeListener){
    /**wifi情報を取得するオブジェクト本体*/
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    // ConnectivityManagerを取得
    private val intentFilter = IntentFilter()
    private var isScanSuccess = false
    private var isConnectingNowWifi = ""
    private lateinit var cachedWifiInfo:WifiInfo
    lateinit var detailedState:DetailedState

    /**
     * wifi規格を取得
     * */
    fun accessPointCompatibility(wifiStandard:Int): String {
        return when (wifiStandard) {
            ScanResult.WIFI_STANDARD_11BE -> "11be"
            ScanResult.WIFI_STANDARD_11AX -> "11ax"
            ScanResult.WIFI_STANDARD_11AD -> "11ad"
            ScanResult.WIFI_STANDARD_11AC -> "11ac"
            ScanResult.WIFI_STANDARD_11N -> "11n"
            ScanResult.WIFI_STANDARD_LEGACY -> "11legacy"
            else -> "unknown"
        }
    }

    fun startWifiScan(): List<ScanResult?> {
        wifiManager.startScan()
        return getResult()
    }


    fun getWifiInfo(): WifiInfo? {
        return wifiManager.connectionInfo
    }

    fun checkScanSucceeded(): Boolean {
        return if (isScanSuccess) {
            isScanSuccess = false
            startWifiScan()
            true
        } else {
            false
        }
    }

    fun registerWifiScanReceiver(){
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        context.registerReceiver(wifiScanReceiver, intentFilter)
    }
    fun unregisterWifiScanReceiver(){
        context.unregisterReceiver(wifiScanReceiver)
    }
    fun registerWifiStateReceiver(){
        intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        context.registerReceiver(wifiStateReceiver, intentFilter)
    }

    fun unregisterWifiStateReceiver(){
        context.unregisterReceiver(wifiStateReceiver)
    }

    /**接続中のWi-FiのSSIDをターゲットとし、Wi-Fiビーコンから取得したターゲットのスキャン結果を取得*/
    fun getTargetWifiScanResult(): ScanResult? {
        val targetSSID = getTargetSSID()
        if (targetSSID != "<unknown ssid>" && targetSSID != null) {
            isConnectingNowWifi = targetSSID
            cachedWifiInfo = wifiManager.connectionInfo
        }
        var ssid = ""
        getResult().forEach {
            if (it!=null){
                ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { /**SSIDがapi33から非推奨になるので getWifiSsid wifiSsidで取得*/
                    it.wifiSsid.toString()
                } else {
                    it.SSID
                }
                if (ssid == isConnectingNowWifi){
                    return it
                }
            } else {
            }
        }
        return null
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        @RequiresApi(Build.VERSION_CODES.M)
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (!success) {
                wifiManager.startScan()
            } else {
                //wifiビーコンスキャン成功時の処理
                isScanSuccess = true
            }
        }
    }

    /**
     * 端末自身のチップセットがどのwifi規格に対応しているか確認
     * */
    fun checkSelfWifiStandard(): String {
        /*11adだけ互換性がないので*/
        return if (wifiManager.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AD)) {
            "11ad"+wifiSelfCompatibility()
        } else {
            wifiSelfCompatibility()
        }
    }
    /**
     * 11ad以外のwifi規格は互換性があるので最高のwifi規格を取得
     * */
    private fun wifiSelfCompatibility(): String {
        if (wifiManager.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11BE)){
            return "11be"
        } else {
            return if (wifiManager.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX)) {
                "11ax"
            } else {
                if (wifiManager.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AC)) {
                    "11ac"
                } else {
                    if (wifiManager.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11N)) {
                        "11n"
                    } else {
                        "11legacy"
                    }
                }
            }
        }
    }
    /**
     * scan結果を返すためのメソッド
     */
    private fun getResult(): List<ScanResult> {
        return wifiManager.scanResults
    }


    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (WifiManager.NETWORK_STATE_CHANGED_ACTION == intent?.action) {
                val networkInfo: NetworkInfo? =
                    intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO)
                if (networkInfo != null) {
                    detailedState = networkInfo.detailedState
                }
                if (networkInfo != null) {
                    wifiStateChangeListener.onWifiStateChange(networkInfo.isConnected)
                } else {
                    //networkInfoが取得できないのであれば接続されていないだろう
                    wifiStateChangeListener.onWifiStateChange(false)
                }
            }
        }
    }

    /**
     * wifiInfoから取得できるSSIDは""付なのでscanResultから取得できるSSIDと比較できない
     * wifiInfoから取得したSSIDを""はずして返却
     * */
    private fun getTargetSSID(): String? {
//        return "Mlab-WXR-6000AX12S-ST24"
        return getWifiInfo()?.ssid?.replace("\"", "")
    }

    fun getCalculateSignalLevel(rssi:Int): Int {
        return wifiManager.calculateSignalLevel(rssi)
    }

    fun convertSecTypeIntToString(secTypeInt:Int):String{
        return when(secTypeInt){
            0 -> "OPEN"
            1 -> "WEP"
            2 -> "PSK"
            3 -> "EAP"
            4 -> "SAE"
            5 -> "EAP_WPA3_ENTERPRISE_192_BIT"
            6 -> "OWE"
            7 -> "WAPI_PSK"
            8 -> "WAPI_CERT"
            9 -> "EAP_WPA3_ENTERPRISE"
            10 -> "OSEN"
            11 -> "PASSPOINT_R1_R2"
            12 -> "PASSPOINT_R3"
            13 -> "DPP"
            else -> "UNKNOWN"
        }
    }
    fun convertWifiStandardIntToString(wifiStandard: Int):String{
        return when(wifiStandard){
            1 -> "LEGACY"
            4 -> "N"
            5 -> "AC"
            6 -> "AX"
            7 -> "AD"
            8 -> "BE"
            else -> "UNKNOWN"
        }
    }
}