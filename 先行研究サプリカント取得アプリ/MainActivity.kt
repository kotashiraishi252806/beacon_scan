package com.example.test_wifi_information

import android.net.wifi.ScanResult
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import com.example.test_wifi_information.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Environment
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.pow

class MainActivity : AppCompatActivity(),WifiStateChangeListener,WifiConnectionSetListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mainLayout: View
    private lateinit var wifiScan: WifiScan
    private val measurementDownloadStreaming = MeasurementDownloadStreaming()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private lateinit var wifiConnector: WifiConnector
    /**自前CheckPermissionクラスのインスタンス*/
    private val checkPermission = CheckPermission()
    private var isRunning:Boolean = false
    private var setWifiConnection:Boolean = false
    private var isWiFiConnecting:Boolean = false
    private lateinit var monitoringConnectingWifi:MonitoringConnectingWifi
    val downloadURL = "https://wifimap.matsuda-lab.jp/DownloadFiles/10Mtest"
    private lateinit var location: Location
    private val viewTextList = mutableListOf<String>()
    private val layoutManager = LinearLayoutManager(this)
    private val textAdapter = DataAdapter(viewTextList)
    private var executeOnceFlag = false
    private var targetRSSI:Int = 0
    private val dateTime = DateTime()
    private lateinit var csvWriter: CsvWriter
    private val rssiMeasurement = RssiMeasurement()
    private var state_csv_file = ""
    private var beacon_csv_file = ""
    companion object {
        const val REQUEST_PERMISSION_CODE = 101
    }

    var targetSSIDInfo: ScanResult? =null

    private val handler = Handler(Looper.getMainLooper()) /*メインスレッド以外での処理を行ってくれる*/

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        //⑦ ユーザーが権限を許可したか
        if (isGranted) {
            //⑧a 制限された機能にアクセス可能
            checkPermission.setPermissionArray(true)
        } else {
            //⑧b パーミッションが許可されていない状態
            checkPermission.setPermissionArray(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        mainLayout = binding.root
        setContentView(mainLayout)

        val recyclerView = binding.recyclerView
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = textAdapter

        binding.clearButton.setOnClickListener {
            viewTextList.clear()
            textAdapter.notifyDataSetChanged()
        }

        wifiScan = WifiScan(applicationContext,this)
        wifiConnector = WifiConnector()
        monitoringConnectingWifi = MonitoringConnectingWifi(applicationContext,this)
        location = Location(this)
        /*パーミッションをチェック*/
        checkPermission.checkPermission(this,requestPermissionLauncher)


        binding.button.setOnClickListener {
            if (!isRunning) {
                isRunning = true
                binding.button.text = "実行中"

                val currentDate = LocalDate.now()
                val formatter = DateTimeFormatter.ofPattern("yyyyMMdd")
                val formattedDate = currentDate.format((formatter))
                beacon_csv_file = "${formattedDate}_${Build.MODEL}_WifiBeacon.csv"
                state_csv_file = "${formattedDate}_${Build.MODEL}_WifiState.csv"

                wifiScan.registerWifiScanReceiver()
                wifiScan.registerWifiStateReceiver()
                executeOnceFlag = true
                rssiMeasurement.startRssiMeasurement(this, beacon_csv_file)
//                wifiScan.startRSSIMeasurement(1000)

//                measurementDownloadStreaming.downloadFile(downloadURL,1024*8)
            } else {
                isRunning = false
                binding.button.text = "停止中"
                wifiScan.unregisterWifiScanReceiver()
                wifiScan.unregisterWifiStateReceiver()
                rssiMeasurement.stopRssiMeasurement(applicationContext,beacon_csv_file)
//                wifiScan.stopRSSIMeasurement()
                // 測定ファイルをストレージにコピー
                copyFilesToDocumentsDirectory()

            }
        }

    }

    override fun onResume() {
        super.onResume()
        if (setWifiConnection){
            measurementDownloadStreaming.downloadFile(downloadURL,1024*8)
            setWifiConnection = false
            binding.button.text = "停止中"
        }
    }

    override fun startUpWifiSetting() {
        setWifiConnection = true
        Log.d("intent","startup")
    }

    override fun endMeasurement() {
        isRunning = false
        binding.button.text = "停止中"
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onWifiStateChange(isConnected: Boolean) {
        if (executeOnceFlag){
            timeCount=0
            sumBeaconDBM=0.0
            sumConnectDBM=0.0
            sumRX=0.0
            sumTX=0.0
            wifiScan.startWifiScan()
            executeOnceFlag = false

            coroutineScope.launch {
                while (isRunning){
                    getWiFiInfo(isConnected)
//                    delay(delayTime)
                }
            }
        } else {
            getWiFiInfo(isConnected)
        }
    }

    private fun getWiFiInfo(isConnected: Boolean){
        collectionWifiInfo(isConnected)
//        if (wifiScan.checkScanSucceeded()){
//            collectionWifiInfo(isConnected)
//        } else {
//            location.getCurrentLocation()
//            collectionWifiInfo(isConnected)
//        }

//            if (wifiScan.detailedState!=NetworkInfo.DetailedState.CONNECTED) {
//
//            }
//        if (wifiScan.detailedState!=NetworkInfo.DetailedState.CONNECTED && wifiScan.detailedState!=NetworkInfo.DetailedState.DISCONNECTED) {
//            collectionWifiInfo(isConnected)
//        }
    }

    private var previousSupplicant = ""
    private var previousDetails = ""
    private var sumBeaconDBM:Double=0.0
    private var sumConnectDBM:Double=0.0
    private var sumRX:Double = 0.0
    private var sumTX:Double = 0.0
    private var timeCount:Int=0
    private val delayTime:Long=500
    private fun collectionWifiInfo(isConnected: Boolean){
        var wifiInfo = wifiScan.getWifiInfo()
        var scanResult = wifiScan.getTargetWifiScanResult()
        csvWriter = CsvWriter(state_csv_file, applicationContext,listOf("dateTime","latitude","longitude","supplicantState","detailedState","currentSecurityType","applicableRedactions","subscriptionId","ssid","bssid","frequency","networkId","wifiStandard","maxSupportedRxLinkSpeedMbps","maxSupportedTxLinkSpeedMbps","rssi","rxLinkSpeedMbps","txLinkSpeedMbps","linkSpeed","ipAddress","isRestricted","uuid"))
        if(wifiInfo != null) {
            monitoringState(wifiInfo,scanResult,isConnected)

//            if (scanResult != null) {
//                measurementAvgLinkSpeed(wifiInfo,scanResult)
//            }
        }


    }
    fun dBmToMilliWatts(dBm: Number):Double{
        return 10.0.pow(dBm.toDouble() / 10.0)
    }
    fun milliWattsTodBm(milliWatts: Double):Double{
        return 10.0 * log10(milliWatts)
    }

    fun monitoringState(wifiInfo: WifiInfo,scanResult: ScanResult?,isConnected: Boolean){
        var outputViewText = ""
        if ("${wifiInfo.supplicantState}"!=previousSupplicant||"${wifiScan.detailedState}"!=previousDetails){
            previousSupplicant = "${wifiInfo.supplicantState}"
            previousDetails = "${wifiScan.detailedState}"
            outputViewText = checkCertificateParameter(wifiInfo)
//            outputViewText += if (scanResult == null) {
//                "ビーコン情報\nnull"
//            } else {
//                "ビーコン情報 rssi:${scanResult.level}(${wifiScan.getCalculateSignalLevel(scanResult.level)}),WiSt:${scanResult.wifiStandard}\nTime:${scanResult.timestamp},mac:${scanResult.BSSID}"
//            }

            handler.post {
                viewTextList.add(outputViewText)
                textAdapter.notifyDataSetChanged()
            }
        }
    }
    fun measurementAvgLinkSpeed(wifiInfo: WifiInfo,scanResult: ScanResult){
        var outputViewText = ""
        
        timeCount++
        sumBeaconDBM+=dBmToMilliWatts(scanResult.level)
        sumConnectDBM+=dBmToMilliWatts(wifiInfo.rssi)
        sumRX+=wifiInfo.rxLinkSpeedMbps
        sumTX+=wifiInfo.txLinkSpeedMbps
        if (timeCount==10) {
            outputViewText = "SSID:${wifiInfo.ssid},Wi-Fi規格：${wifiInfo.wifiStandard}\n" +
                    "10回測定平均,1回遅延時間:${delayTime}\n" +
                    "ビーコンRSSI平均：${milliWattsTodBm(sumBeaconDBM/timeCount)}\n" +
                    "接続RSSI平均:${milliWattsTodBm(sumConnectDBM/timeCount)}\n" +
                    "受信LS:${sumRX/timeCount}Mbps,送信LS:${sumTX/timeCount}Mbps"
            handler.post {
                viewTextList.add(outputViewText)
                textAdapter.notifyDataSetChanged()
            }
            timeCount=0
            sumConnectDBM = 0.0
            sumBeaconDBM = 0.0
            sumRX=0.0
            sumTX=0.0
        }
    }
//"dateTime","supplicantState","detailedState","currentSecurityType","applicableRedactions","subscriptionId","ssid","bssid","frequency","networkId","wifiStandard","maxSupportedRxLinkSpeedMbps","maxSupportedTxLinkSpeedMbps","rssi","rxLinkSpeedMbps","txLinkSpeedMbps","linkSpeed","ipAddress"
    fun checkCertificateParameter(wifiInfo: WifiInfo): String {
        var nowLatitude:Double?
        var nowLongitude:Double?
        val nowLocation = location.getCurrentLocation()
        nowLatitude = nowLocation.first
        nowLongitude = nowLocation.second
        var writeText = "${dateTime.getDateMillis()},${nowLatitude},${nowLongitude},${wifiInfo.supplicantState},${wifiScan.detailedState},"
        var outputText = "${dateTime.getDateMillis()}\n緯度経度:[${nowLatitude},${nowLongitude}]\nsup:${wifiInfo.supplicantState},det:${wifiScan.detailedState}\n"
        if (Build.VERSION.SDK_INT >= 31) {
            writeText+="${wifiScan.convertSecTypeIntToString(wifiInfo.currentSecurityType)},${wifiInfo.applicableRedactions},${wifiInfo.subscriptionId},"
            outputText+="Sec:${wifiScan.convertSecTypeIntToString(wifiInfo.currentSecurityType)},"
//                    "appRed:${wifiInfo.applicableRedactions}" +
//                    "subId:${wifiInfo.subscriptionId}\n"
        } else {
            writeText+="null,null,null,"
        }
        writeText+="${wifiInfo.ssid},"
        outputText +=  if ("${wifiInfo.ssid}"!="<unknown ssid>") { //"ssid:"+
            "[AP:${wifiInfo.ssid},"
        }else {
            "[AP:null,"
        }
        writeText+="${wifiInfo.bssid},"
        outputText += wifiInfo.bssid+"]"
//        outputText +=  if (wifiInfo.bssid!=null) { //",mac:"+
//            "〇"
//        }else {
//            "×"
//        }
        writeText += "${wifiInfo.frequency},"
        outputText +=  if (wifiInfo.frequency!=-1) { //",frequency:"+
            "[周波数:${wifiInfo.frequency}]"
        }else {
            "×"
        }
        writeText+="${wifiInfo.networkId},"
        outputText+="[nID:${wifiInfo.networkId}]"
//        outputText +=  if (wifiInfo.networkId!=-1) { //",networkId:"+
//            "〇"
//        }else {
//            "×"
//        }
        writeText+="${wifiScan.convertWifiStandardIntToString(wifiInfo.wifiStandard)},"
        outputText += "[規格:"+wifiScan.convertWifiStandardIntToString(wifiInfo.wifiStandard)+"]"  //,wifi規格:
        writeText+="${wifiInfo.maxSupportedRxLinkSpeedMbps},"
        outputText += if (wifiInfo.maxSupportedRxLinkSpeedMbps!=-1) { //",maxRX:"+
            "[最大送受信速度:${wifiInfo.maxSupportedRxLinkSpeedMbps},"
        }else {
            "×"
        }
        writeText+="${wifiInfo.maxSupportedTxLinkSpeedMbps},"
        outputText +=  if (wifiInfo.maxSupportedTxLinkSpeedMbps!=-1) { //",maxTX:"+
            "${wifiInfo.maxSupportedTxLinkSpeedMbps}]"
        }else {
            "×"
        }
        writeText+="${wifiInfo.rssi},"
        outputText +=  if (wifiInfo.rssi!=-127) { //",rssi:"+
            "[rssi:${wifiInfo.rssi}dBm]"
        }else {
            "×"
        }
        writeText+="${wifiInfo.rxLinkSpeedMbps},"
        outputText += if (wifiInfo.rxLinkSpeedMbps!=-1) { // ",RX:"+
            "[送受信速度:${wifiInfo.rxLinkSpeedMbps}Mbps,"
        }else {
            "×"
        }
        writeText+="${wifiInfo.txLinkSpeedMbps},"
        outputText +=  if (wifiInfo.txLinkSpeedMbps!=-1) { //",TX:"+
            "${wifiInfo.txLinkSpeedMbps}Mbps]"
        }else {
            "×"
        }
        writeText+="${wifiInfo.linkSpeed},"
        outputText +=  if (wifiInfo.linkSpeed!=-1) { //",LS:"+
            "[リンクスピード:${wifiInfo.linkSpeed}]"
        }else {
            "×"
        }
//        outputText += ",hiddenSSID:"+ if (wifiInfo.hiddenSSID) {
//            "〇"
//        }else {
//            "×"
//        }
        writeText+="${wifiInfo.ipAddress},"
        outputText += ",ip:${wifiInfo.ipAddress}" //
        if (Build.VERSION.SDK_INT >= 33) {
            writeText+="${wifiInfo.isRestricted},"
            outputText+=",wifi制限:${wifiInfo.isRestricted},"
        } else {
            writeText+="null,"
        }
        writeText+="${Build.MODEL}"
        csvWriter.writeDataAsyncString(writeText)
        return outputText+"\n"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // パーミッションが許可された場合、ファイルをコピー
                copyFilesToDocumentsDirectory()
            } else {
                // パーミッションが拒否された場合、何らかの処理を行う
            }
        }
    }

    private fun copyFilesToDocumentsDirectory() {
        val sourceDirectory = applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val file1 = File(sourceDirectory, state_csv_file)
        val file2 = File(sourceDirectory, beacon_csv_file)
        val documentsDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)

        // ファイルを ~/Documents ディレクトリにコピー
        copyFile(file1, documentsDirectory)
        copyFile(file2, documentsDirectory)
    }

    private fun copyFile(sourceFile: File, destinationDirectory: File?) {
        try {
            val inputStream = FileInputStream(sourceFile)
            val outputStream = FileOutputStream(File(destinationDirectory, sourceFile.name))
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}