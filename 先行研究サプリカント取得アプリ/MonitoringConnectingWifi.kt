package com.example.test_wifi_information

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiSsid
import android.os.Build


class MonitoringConnectingWifi(private val context: Context,wifiStateChangeListener: WifiStateChangeListener) {
    private var intentFilterSetOK = false
    private val intentFilter = IntentFilter()
    private var wifiInfo: WifiInfo? = null

    fun registerWifiStateReceiver(){
        if (!intentFilterSetOK){
            intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        context.registerReceiver(wifiStateReceiver, intentFilter)
        intentFilterSetOK = true
    }

    fun unregisterWifiStateReceiver(){
        context.unregisterReceiver(wifiStateReceiver)
    }

    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (WifiManager.NETWORK_STATE_CHANGED_ACTION == intent?.action) {
                val networkInfo: NetworkInfo? =
                    intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO)
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiInfo = wifiManager.connectionInfo
                if (networkInfo != null) {
                    wifiStateChangeListener.onWifiStateChange(networkInfo.isConnected)
                } else {
                    //networkInfoが取得できないのであれば接続されていないだろう
                    wifiStateChangeListener.onWifiStateChange(false)
                }
            }
        }
    }

    fun getWifiInfo(): WifiInfo? {
        return wifiInfo
    }

    /**
     * wifiInfoから取得できるSSIDは""付なのでscanResultから取得できるSSIDと比較できない
     * wifiInfoから取得したSSIDを""はずして返却
     * */
    private fun getTargetSSID(): String? {
        return "Mlab-WXR-6000AX12S-ST24"
//        return wifiInfo?.ssid?.replace("\"", "")
    }
    /**接続中のWi-FiのSSIDをターゲットとし、Wi-Fiビーコンから取得したターゲットのスキャン結果を取得*/
    fun getTargetWifiScanResult(scanResult:List<ScanResult?>): ScanResult? {
        val targetSSID = getTargetSSID()
        scanResult.forEach {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { /**SSIDがapi33から非推奨になるので getWifiSsid wifiSsidで取得*/
                if (it != null) {
                    if (it.wifiSsid.toString() == targetSSID){
                        return it
                    }
                }
            } else {
                if (it != null) {
                    if (it.SSID == targetSSID) {
                        return it
                    }
                }
            }
        }
        return null
    }
}