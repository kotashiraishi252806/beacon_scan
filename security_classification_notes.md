# セキュリティ規格判定 — 調査・修正メモ

## 1. セキュリティ規格情報の取得元

`ScanResult.capabilities` という Android API のフィールドを使用。  
OS がビーコンフレームを解析して組み立てた文字列で、構造は以下の形式：

```
[認証方式-鍵管理-暗号化][認証方式-鍵管理-暗号化]...[ESS/IBSS]
```

例：
```
[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]
[RSN-SAE-CCMP][ESS]
[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP][ESS]
```

各要素に入りうる値は IEEE 802.11 規格・Wi-Fi Alliance で定義されており有限（新規格で拡張はありうる）。

| 要素 | 既知の値 |
|---|---|
| 認証方式 | WPA, WPA2, RSN（WPA2の別名） |
| 鍵管理 | PSK, EAP, SAE, OWE, FT/PSK, FT/EAP, FT/SAE, PSK-SHA256, EAP-SHA256, EAP/SHA1, EAP_SUITE_B_192 |
| 暗号化 | CCMP, TKIP, GCMP, GCMP-256, WEP |

この文字列には公式な仕様書がなく、Android バージョンや端末・APのドライバによって表現が変わる。  
アプリ側では `capabilitiesRaw` としてそのまま保存・送信もしている。

### 情報の流れ

| レイヤ | ソース | 役割 |
|---|---|---|
| ハードウェア | AP のビーコンフレーム（802.11 Management Frame） | セキュリティ情報を電波に乗せて送信 |
| OS | `wpa_supplicant`（Linux/Android の Wi-Fi スタック） | ビーコンを解析して capabilities 文字列を組み立てる |
| Android API | `ScanResult.capabilities`（`android.net.wifi.ScanResult`） | capabilities 文字列をアプリに渡す |
| アプリ | `getSecurity()` 関数 | 文字列を解析して分類ラベルを付ける |

アプリが直接ビーコンを読んでいるわけではなく、OS が変換した文字列に依存している。これが表現のバラつきが生じる根本原因。

---

## 2. 旧判定ロジック

```kotlin
private fun getSecurity(capabilities: String): String = when {
    capabilities.contains("WPA3") -> "WPA3"
    capabilities.contains("WPA2") -> "WPA2"
    capabilities.contains("WPA")  -> "WPA"
    capabilities.contains("WEP")  -> "WEP"
    else -> "Open"
}
```

### フローチャート

```
capabilities_raw の文字列に対して上から順に評価

  ┌──────────────────┐
  │ "WPA3" を含む？  │
  └──────────────────┘
      YES │     NO
          ▼      │
       "WPA3"    ▼
          ┌──────────────────┐
          │ "WPA2" を含む？  │
          └──────────────────┘
              YES │     NO
                  ▼      │
               "WPA2"    ▼
                  ┌──────────────────┐
                  │ "WPA" を含む？   │
                  └──────────────────┘
                      YES │     NO
                          ▼      │
                       "WPA"     ▼
                          ┌──────────────────┐
                          │ "WEP" を含む？   │
                          └──────────────────┘
                              YES │     NO
                                  ▼      ▼
                               "WEP"  "Open"
```

---

## 3. 旧ロジックの問題点

### 問題① SAE を含む文字列が "Open" になる

WPA3 の認証方式 **SAE**（WPA3-Personal）は、capabilities 文字列中に `WPA3` という文字列が出ない形式で出現することがある。

| capabilities_raw | 旧判定 | 正しい判定 |
|---|---|---|
| `[RSN-SAE-CCMP][ESS]` | Open | WPA3 |
| `[RSN-SAE-CCMP][ESS][MFPR][MFPC]` | Open | WPA3 |
| `[RSN-SAE+FT/SAE-CCMP][ESS]` | Open | WPA3 |
| `[RSN-SAE-GCMP-256+CCMP][ESS]` | Open | WPA3 |

収集データ上での影響件数：約 **4,182 件** が誤って "Open" と判定されていた。

### 問題② WPA2/WPA3 トランジションモードが WPA2 に分類される

WPA2 と WPA3 の両クライアントに対応するための「トランジションモード」のAP は、
capabilities に `WPA2` と `SAE` が **両方** 含まれる。

旧ロジックでは `WPA2` が先にマッチするため "WPA2" と判定されていたが、
これは WPA3（SAE）も使用可能な AP を過小評価している。

例：`[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP][ESS]`

収集データ上での影響件数：約 **7,685 件**

### 問題③ WPA3-Enterprise（EAP_SUITE_B）が "Open" になる

`[RSN-EAP_SUITE_B_192-GCMP-256][ESS]` は WPA3-Enterprise 192bit モードだが、
`WPA3`・`WPA2`・`WPA`・`WEP` いずれの文字列も含まないため "Open" になっていた。

収集データ上での影響件数：**1 件**（現時点）

---

## 4. 新判定ロジック

```kotlin
private fun getSecurity(capabilities: String): String {
    val hasWPA2 = capabilities.contains("WPA2")
    val hasSAE  = capabilities.contains("SAE")
    return when {
        hasWPA2 && hasSAE                                          -> "WPA2/WPA3"
        capabilities.contains("WPA3") || hasSAE
            || capabilities.contains("OWE")
            || capabilities.contains("EAP_SUITE_B")               -> "WPA3"
        hasWPA2                                                    -> "WPA2"
        capabilities.contains("WPA")                              -> "WPA"
        capabilities.contains("WEP")                              -> "WEP"
        else                                                       -> "Open"
    }
}
```

### フローチャート

```
capabilities_raw の文字列に対して上から順に評価

  ┌──────────────────────────────────────┐
  │ "WPA2" を含む AND "SAE" を含む？     │
  └──────────────────────────────────────┘
      YES │     NO
          ▼      │
     "WPA2/WPA3"  │
                  ▼
        ┌─────────────────────────────────────────────┐
        │ "WPA3" OR "SAE" OR "OWE-" OR                │
        │ "EAP_SUITE_B" のいずれかを含む？             │
        └─────────────────────────────────────────────┘
              YES │     NO
                  ▼      │
               "WPA3"    ▼
                  ┌──────────────────┐
                  │ "WPA2" を含む？  │
                  └──────────────────┘
                      YES │     NO
                          ▼      │
                       "WPA2"    ▼
                          ┌──────────────────┐
                          │ "WPA" を含む？   │
                          └──────────────────┘
                              YES │     NO
                                  ▼      │
                               "WPA"     ▼
                                  ┌──────────────────┐
                                  │ "WEP" を含む？   │
                                  └──────────────────┘
                                      YES │     NO
                                          ▼      ▼
                                       "WEP"  "Open"
```

### 変更点まとめ

| 追加条件 | 対応するケース |
|---|---|
| `SAE` | WPA3-Personal（純粋SAE） |
| `WPA2` + `SAE` の組み合わせ | WPA2/WPA3 トランジションモード → "WPA2/WPA3" |
| `OWE` | WPA3 Enhanced Open |
| `EAP_SUITE_B` | WPA3-Enterprise 192bit |

---

## 5. 例外への対処

### OWE Transition モード（Open側 BSSID）

OWE Transition モードでは、AP が **2つの BSSID** を持つ：

```
AP
├── BSSID-A: [RSN-OWE_TRANSITION-CCMP][ESS]
│             → 暗号なし（Open）。古い端末向け。
└── BSSID-B: [RSN-OWE-CCMP][RSN-OWE_TRANSITION-CCMP][ESS]
              → OWE 暗号あり（WPA3）。OWE 対応端末向け。
```

`OWE_TRANSITION`（アンダースコア）と `OWE-CCMP`（ハイフン）は文字列上で区別できる。

| capabilities_raw | 含まれる文字列 | 判定 |
|---|---|---|
| `[RSN-OWE_TRANSITION-CCMP][ESS]` | `OWE-` を含まない | Open ✓ |
| `[RSN-OWE-CCMP][RSN-OWE_TRANSITION-CCMP][ESS]` | `OWE-CCMP` を含む | WPA3 ✓ |

**対処：** `contains("OWE")` → `contains("OWE-")` に変更することで解決済み。

---

## 6. その他の重要ポイント

### capabilities_raw の多様性

今回の収集データで確認された distinct な capabilities_raw は **71 種類**（75種類のうち）。  
同じ AP でも Android バージョンや端末ごとに文字列の形式が異なることがある。

### 情報の重複表現

`WPA2` と `RSN` は同じものを指すが、同一の capabilities 文字列に両方出現することがある。  
例：`[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]`

### MFPC / MFPR フラグ

`MFPC`（Management Frame Protection Capable）・`MFPR`（Required）は PMF（Protected Management Frames）の対応状況を示す。  
WPA3 では PMF が必須のため、SAE 系の capabilities に `[MFPC]` が付いていることが多い。  
現在のロジックでは考慮していない（SAE で WPA3 判定されるため実害なし）。

### 判定優先順位の重要性

`WPA2` と `WPA` の判定順序が逆になると、`WPA2` が `WPA` にマッチしてしまう（`"WPA2".contains("WPA")` は true）。  
新ロジックでもこの順序は維持している。

### Wi-Fi 規格（802.11世代）は別ソース

セキュリティ規格（WPA2/WPA3 など）は `capabilities` 文字列から判定するが、  
**Wi-Fi 世代**（802.11n/ac/ax など）は `ScanResult.wifiStandard` という別の API フィールドから取得しており、OS が提供する整数コードをそのままラベルに変換している。
