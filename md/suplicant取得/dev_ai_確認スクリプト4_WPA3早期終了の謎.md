# 開発AIへの確認スクリプト：WPA3のAPが1〜2秒でFAILED_AT_DISCONNECTEDになる矛盾

## 観測された実データ(`pending_scans.json`より)

以下3件はすべてWPA3のAPで、`supplicant_elapsed_ms`が1657〜2305ms(1.6〜2.3秒)と非常に短く、`supplicant_final_state`は`FAILED_AT_DISCONNECTED`。

```json
{"ssids":["0000mlab"],"security":"WPA3","supplicant_states":["INTERFACE_DISABLED","DISCONNECTED","ASSOCIATING","DISCONNECTED","ASSOCIATING","DISCONNECTED"],"supplicant_final_state":"FAILED_AT_DISCONNECTED","supplicant_elapsed_ms":1657}

{"ssids":["Mlab_ST5-WPA3"],"security":"WPA3","supplicant_states":["INTERFACE_DISABLED","DISCONNECTED","ASSOCIATING","DISCONNECTED","ASSOCIATING","DISCONNECTED"],"supplicant_final_state":"FAILED_AT_DISCONNECTED","supplicant_elapsed_ms":2305}

{"ssids":["MLAB-TECH01-5-3"],"security":"WPA3","supplicant_states":["DISCONNECTED","INTERFACE_DISABLED","DISCONNECTED","ASSOCIATING","DISCONNECTED","ASSOCIATING","DISCONNECTED"],"supplicant_final_state":"FAILED_AT_DISCONNECTED","supplicant_elapsed_ms":1851}
```

3件とも共通して、`ASSOCIATED`状態に一度も到達せず、`ASSOCIATING`と`DISCONNECTED`を往復して終わっている。

## 矛盾点

`measureSupplicantForAp()`内の`supplicantReceiver`のPhase2判定ロジック(266〜269行目)は以下の通り。

```kotlin
SupplicantState.DISCONNECTED -> {
    if (supplicantTransitions.any {
        it == "ASSOCIATED" || it == "FOUR_WAY_HANDSHAKE" || it == "GROUP_HANDSHAKE"
    }) cont.resume(Unit)
}
```

つまりPhase2中に`DISCONNECTED`が来ても、それ以前に`ASSOCIATED`・`FOUR_WAY_HANDSHAKE`・`GROUP_HANDSHAKE`のいずれかが記録されていなければ、continuationは`resume`されない。この3件はいずれも`ASSOCIATED`に到達していないため、この条件を満たさないはずである。

条件を満たさない場合、Phase2は`AUTH_TIMEOUT_MS = 8_000L`(8秒)のタイムアウトが来るまで待ち続けるはずである。Phase1の所要時間を含めても、合計は最低でも8秒を超えるはずだが、実際の`supplicant_elapsed_ms`は1.6〜2.3秒しかない。

**この矛盾(コード上は8秒待つはずなのに、実測は1〜2秒で終わっている)の理由を知りたい。**

---

## 確認してほしいこと

1. 上記のコード読解(Phase2の`DISCONNECTED`早期resume条件、`ASSOCIATED`等が必要)に誤りがないか、`MainActivity.kt`を再確認してほしい。

2. もし読解が正しいなら、8秒待たずに1〜2秒で処理が終わっている経路が他にないか、`measureSupplicantForAp()`全体(732〜854行目)を改めて洗い出してほしい。見落としている分岐がある可能性がある。

3. 1つの仮説として、`SUPPLICANT_STATE_CHANGED_ACTION`がシステム全体のグローバルブロードキャストである点(以前の確認スクリプト3の回答で指摘された内容)を踏まえ、この3件の`supplicant_states`が実は「次のAPの測定が始まった後に、前のAPの残留ブロードキャストとして記録されたもの」であり、実際の当該APのPhase1/Phase2の挙動とは別物である可能性はないか。具体的には、この3件の直前・直後に測定されたAPが何であったか(同じ`pending_scans.json`の`access_points`配列内での前後のエントリ)を確認し、隣接するAPの`supplicant_states`と時系列的に矛盾がないか照合してほしい。

4. WPA3(SAE)特有の挙動として、ダミーパスフレーズによる認証が`ASSOCIATED`に至る前の段階(SAEのcommit/confirm交換)で高速に失敗する、という技術的な妥当性についても、分かる範囲でコメントしてほしい。

---

## 参考：該当APの前後のエントリ(同一スキャン内、`access_points`配列の順序通り)

1件目「0000mlab」の前後：
- 前：`0000mlab-2.4ver2`(WPA2, elapsed 8153ms, `TIMEOUT_AT_FOUR_WAY_HANDSHAKE`)
- 後：`0000mlab-5ver2`(WPA2, elapsed 4518ms, `FAILED_AT_DISCONNECTED`)

2件目「Mlab_ST5-WPA3」の前後：
- 前：`Mlab_ST5`(WPA2, elapsed 8139ms, `TIMEOUT_AT_FOUR_WAY_HANDSHAKE`)
- 後：SSID非公開のAP(WPA2/WPA3, elapsed 3750ms, `TIMEOUT_AT_COMPLETED`)

3件目「MLAB-TECH01-5-3」の前後：
- 前：`MLAB-TECH01-5-2`(WPA2, elapsed 3611ms, `TIMEOUT_AT_COMPLETED`)
- 後：`FujitaLab`(WPA2, elapsed 4005ms, `TIMEOUT_AT_COMPLETED`)

いずれも直前のAPは8秒近く(または3〜4秒)測定にかかっているため、「300msの待機だけでは前APの残留ブロードキャストが届ききっていない」という仮説(確認3の回答)とは、時間差の面では一見つながりにくい(前APの処理自体が既にかなり長くかかっているため)。ただし、前APの`unregisterNetworkCallback`後の後処理(内部的な切断シーケンス)が、AP切り替え後にずれ込んで届く可能性はまだ排除できていない。
