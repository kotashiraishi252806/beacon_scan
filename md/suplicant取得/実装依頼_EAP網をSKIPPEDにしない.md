# 実装依頼：EAP(企業認証)のAPを`SKIPPED`にせず、Supplicant状態を取得できるようにする

## 前提の食い違い

現在の`buildSupplicantSpecifier()`(856〜872行目)は、AP のセキュリティが EAP(証明書やID/パスワードによる企業認証、例：eduroam)の場合、ダミーパスワードでは認証を突破できないという理由で`null`を返し、接続要求自体を送らずに`SKIPPED`として処理を終えている。

```kotlin
caps.contains("EAP") && !caps.contains("OWE") -> return null
```

しかし、この測定の目的は「ダミーパスワードで最終的な認証を突破すること」ではない。目的は「そのAPに対してどこまで接続処理が進むか(具体的には`ASSOCIATING`・`ASSOCIATED`まで到達できるか)を観測すること」であり、認証(パスワード等の中身の検証)が失敗すること自体は許容範囲内である。実際、WPA2/WPA3のAPに対しても、ダミーパスワードで意図的に認証を失敗させて、そこに至るまでのSupplicant状態遷移を記録している。

つまり、EAPのAPだけ「どうせ認証を突破できないから」という理由で最初から諦めているのは、他のセキュリティ種別APと扱いが一貫しておらず、本来取得したいはずの`ASSOCIATING`到達可否のデータを、無条件に捨ててしまっている。

## 技術的な確認事項

Wi-Fiの接続順序は、802.11レベルのアソシエーション(電波レベルでの接続)が先に行われ、EAP認証(ID・パスワード等の検証)はその**後**に行われる。したがって、EAP認証がダミー値で失敗するとしても、アソシエーション(`ASSOCIATING`→`ASSOCIATED`)までは到達できる可能性がある。

`WifiNetworkSpecifier.Builder`には、EAP用の認証情報を設定する`setWpa2EnterpriseConfig(WifiEnterpriseConfig)`・`setWpa3EnterpriseConfig(WifiEnterpriseConfig)`というメソッドが存在する(これはAPI仕様として存在することは確認済みだが、実機での動作は未検証)。これを使えば、EAPのAPに対してもダミーの認証情報(ID・パスワード)を設定した接続要求を組み立てて送ることができるはずである。

## 依頼したいこと

1. `buildSupplicantSpecifier()`のEAP判定分岐(`caps.contains("EAP") && !caps.contains("OWE") -> return null`)を、`null`を返して諦めるのではなく、`WifiEnterpriseConfig`にダミーのID・パスワードを設定した`setWpa2EnterpriseConfig()`(または`security`が`WPA3`系相当であれば`setWpa3EnterpriseConfig()`)を使う実装に変更してほしい。

2. `WifiEnterpriseConfig`には、EAPの方式(PEAP、TTLS、TLS、SIMなど)を指定する必要があるはずである。`capabilities_raw`の文字列(例:`[WPA2-EAP+FT/EAP-CCMP][RSN-EAP+FT/EAP-CCMP][ESS]`)から具体的なEAP方式までは判別できない可能性が高いため、判別できない場合にどのEAP方式をデフォルトとして試すべきか(一般的にはPEAP+MSCHAPv2が広く使われる)、意見がほしい。

3. TLS方式(クライアント証明書が必須)の場合、ダミーの証明書を用意するのが現実的に困難な可能性がある。その場合は諦めて`SKIPPED`のままにする、という判断が必要になるかもしれないが、その判定をどう実装するか(事前に`capabilities_raw`だけから判断できるか、それとも実際に接続を試みてダメだった場合のみ`SKIPPED`相当の別状態にするか)を検討してほしい。

4. **WEPについては対象外**である。`WifiNetworkSpecifier.Builder`にはWEP用の認証情報を設定するメソッドが存在しないため、ダミー値であっても接続要求を組み立てること自体が技術的に不可能。WEPは引き続き`SKIPPED`のままで問題ない。

5. 変更後、EAPのAPに対する新しい終了状態(`SKIPPED`ではなく、実際に接続を試みた結果としての`FAILED_AT_X`・`TIMEOUT_AT_X`など)を、既存の分類とどう整合させるか(そのまま流用できるか、EAP専用の状態名を新設すべきか)についても意見がほしい。
