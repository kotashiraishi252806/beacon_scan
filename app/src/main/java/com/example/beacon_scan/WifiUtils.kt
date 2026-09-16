package com.example.beacon_scan

import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.os.Build
import androidx.annotation.RequiresApi

fun getBand(frequencyMhz: Int): String = when {
    frequencyMhz in 2400..2500 -> "2.4GHz"
    frequencyMhz in 4900..5924 -> "5GHz"
    frequencyMhz >= 5925 -> "6GHz"
    else -> "Unknown"
}

fun getWifiStandardLabel(code: Int): String = when (code) {
    ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g"
    ScanResult.WIFI_STANDARD_11N    -> "802.11n"
    ScanResult.WIFI_STANDARD_11AC   -> "802.11ac"
    ScanResult.WIFI_STANDARD_11AX   -> "802.11ax"
    ScanResult.WIFI_STANDARD_11AD   -> "802.11ad"
    ScanResult.WIFI_STANDARD_11BE   -> "802.11be"
    else -> "Unknown"
}

fun getSecurityFromCapabilities(capabilities: String): String {
    val hasWPA2 = capabilities.contains("WPA2")
    val hasSAE  = capabilities.contains("SAE")
    return when {
        hasWPA2 && hasSAE                                          -> "WPA2/WPA3"
        capabilities.contains("WPA3") || hasSAE
            || capabilities.contains("OWE-")
            || capabilities.contains("EAP_SUITE_B")               -> "WPA3"
        hasWPA2                                                    -> "WPA2"
        capabilities.contains("WPA")                              -> "WPA"
        capabilities.contains("WEP")                              -> "WEP"
        else                                                       -> "Open"
    }
}

// SECURITY_TYPE_PSK はWPA1/WPA2を区別しないため、PSK確定後の二値判定のみ補助的に使う
fun disambiguateWpaVersion(capabilities: String): String =
    if (capabilities.contains("WPA2") || capabilities.contains("RSN")) "WPA2" else "WPA"

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun getSecurityFromTypes(result: ScanResult): String {
    val types = result.securityTypes.toSet()
    val hasPsk       = WifiConfiguration.SECURITY_TYPE_PSK in types
    val hasEap       = WifiConfiguration.SECURITY_TYPE_EAP in types
    val hasSae       = WifiConfiguration.SECURITY_TYPE_SAE in types
    val hasOwe       = WifiConfiguration.SECURITY_TYPE_OWE in types
    val hasWpa3Ent   = WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE in types
        || WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT in types
    val hasPasspoint = WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2 in types
        || WifiInfo.SECURITY_TYPE_PASSPOINT_R3 in types
    val hasWep       = WifiConfiguration.SECURITY_TYPE_WEP in types

    return when {
        hasPsk && hasSae               -> "WPA2/WPA3"
        hasSae || hasOwe || hasWpa3Ent -> "WPA3"
        hasPsk                         -> disambiguateWpaVersion(result.capabilities)
        hasEap || hasPasspoint         -> "WPA2"
        hasWep                         -> "WEP"
        else                           -> "Open"
    }
}

fun getSecurity(result: ScanResult): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getSecurityFromTypes(result)
    else getSecurityFromCapabilities(result.capabilities)

// capabilities_raw 文字列から先行研究の5分類に当てはめる。
// 優先順位: WPA3-SAE > WPA2-EAP > WPA2-PSK > WPA-PSK > Open > その他
// WPA2/WPA3トランジション([RSN-PSK+SAE-CCMP])は-SAEに非該当のためWPA2-PSKに分類される。
fun getSecurityGroup(capabilities: String): String = when {
    capabilities.contains("-SAE")                                         -> "WPA3-SAE"
    capabilities.contains("EAP")                                          -> "WPA2-EAP"
    (capabilities.contains("WPA2") || capabilities.contains("RSN"))
        && capabilities.contains("PSK")                                   -> "WPA2-PSK"
    capabilities.contains("WPA") && capabilities.contains("PSK")         -> "WPA-PSK"
    !capabilities.contains("WPA") && !capabilities.contains("WEP")
        && !capabilities.contains("OWE")                                  -> "Open"
    else                                                                   -> "その他"
}

fun groupBySsid(accessPoints: List<AccessPoint>): List<SsidGroup> {
    val grouped = mutableMapOf<String, MutableList<AccessPoint>>()
    for (ap in accessPoints) {
        val key = ap.ssids.firstOrNull() ?: ""
        grouped.getOrPut(key) { mutableListOf() }.add(ap)
    }
    return grouped.map { (ssid, aps) -> SsidGroup(ssid, aps) }
        .sortedByDescending { group -> group.accessPoints.maxOf { it.rssiDbm } }
}

private val SECURITY_GROUP_ORDER = listOf("Open", "WPA-PSK", "WPA2-PSK", "WPA2-EAP", "WPA3-SAE", "その他")

fun groupBySecurityGroup(accessPoints: List<AccessPoint>): List<SecurityGroup> {
    val grouped = mutableMapOf<String, MutableList<AccessPoint>>()
    for (ap in accessPoints) {
        val key = getSecurityGroup(ap.capabilitiesRaw)
        grouped.getOrPut(key) { mutableListOf() }.add(ap)
    }
    return grouped.map { (sec, aps) ->
        SecurityGroup(sec, aps.sortedByDescending { it.rssiDbm }.toMutableList())
    }.sortedBy { SECURITY_GROUP_ORDER.indexOf(it.securityGroup).let { i -> if (i < 0) Int.MAX_VALUE else i } }
}

@Suppress("DEPRECATION")
fun groupByBssid(results: List<ScanResult>): List<AccessPoint> {
    val grouped = mutableMapOf<String, MutableList<ScanResult>>()
    for (r in results) {
        grouped.getOrPut(r.BSSID) { mutableListOf() }.add(r)
    }
    return grouped.map { (bssid, scanResults) ->
        val representative = scanResults.maxByOrNull { it.level } ?: scanResults.first()
        val ssids = scanResults.mapNotNull { it.SSID.ifEmpty { null } }.distinct()
        val wifiStandardCode = representative.wifiStandard
        val channelWidthMhz = when (representative.channelWidth) {
            ScanResult.CHANNEL_WIDTH_20MHZ          -> 20
            ScanResult.CHANNEL_WIDTH_40MHZ          -> 40
            ScanResult.CHANNEL_WIDTH_80MHZ          -> 80
            ScanResult.CHANNEL_WIDTH_160MHZ         -> 160
            ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
            5                                       -> 320
            else                                    -> 20
        }
        val mldMac = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            representative.apMldMacAddress?.toString()
        } else null
        val securityTypesRaw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            representative.securityTypes.toList()
        } else emptyList()
        AccessPoint(
            bssid = bssid,
            mldMacAddress = mldMac,
            oui = bssid.take(8),
            ssids = ssids,
            rssiDbm = representative.level,
            frequencyMhz = representative.frequency,
            band = getBand(representative.frequency),
            channelWidthMhz = channelWidthMhz,
            wifiStandard = getWifiStandardLabel(wifiStandardCode),
            wifiStandardCode = wifiStandardCode,
            security = getSecurity(representative),
            capabilitiesRaw = representative.capabilities,
            securityTypesRaw = securityTypesRaw
        )
    }.sortedByDescending { it.rssiDbm }
}
