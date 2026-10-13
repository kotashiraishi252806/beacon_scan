# サプリカント状態遷移の取得時間の検討（アプリ開発向け資料）

作成日: 2026-10-08
元資料: `html/107サプリカント取得検討.html`（サーバ側の集計ページ）
添付データ: `supplicant_attempts.csv`（接続試行 1 回 = 1 行、2,629 行）

## 0. この資料で検討してほしいこと

研究の目的は「移動しながらサプリカント状態遷移を取得できるか」を判断することです。そのために、1 回の接続試行にかかる時間（`supplicant_elapsed_ms`）と、その時間内にどの state まで到達しているかを集計しました。

アプリ側の実装（`SupplicantMeasurer.kt` の `measureForAp()` など）と照らし合わせて、**「6. アプリ側への質問」** に回答してください。回答では、実装から確認できた事実と推測を分けて書いてください。

## 1. 前提

### 1.1 タイムアウトの変更（2026-09-14）

| 期間 | 測定日 | サプリカント状態遷移取得のタイムアウト |
|---|---|---|
| 変更前 | 2026-08-21, 2026-09-10 | 15s |
| 変更後 | 2026-09-14〜2026-09-18 | OS タイムアウト 3s ＋ コールバックタイムアウト 10s |

データ上も区切りを確認しています。`elapsed_ms` の日別最大値は、08-21・09-10 が 15,021〜15,022 ms、09-14〜09-18 が 3,036〜3,050 ms でした。09-14 は最初の測定（15:38:55）から変更後の値になっています。

### 1.2 測定機種

| 機種 | Android API | 接続試行 変更前 | 接続試行 変更後 |
|---|---|---|---|
| motorola edge 40 neo | 34 | 118 | 1,244 |
| ASUS_I006D | 33 | 58 | 1,209 |
| 計 | | 176（51 スキャン） | 2,453（261 スキャン） |

### 1.3 用語

- **接続試行**: `requestNetwork()` を 1 回呼んだこと。データ上は `supplicant_final_state` が記録された AP 1 行に当たる。例外として、`SKIPPED` / `CANCELLED` は `requestNetwork()` を呼んでいないが、独立した区分として件数に含める。
- **状態遷移列**: 1 回の接続試行で記録された supplicant state の並び。例: `INTERFACE_DISABLED>DISCONNECTED>ASSOCIATING>ASSOCIATED>COMPLETED`
- **〇〇到達**: 状態遷移列に 〇〇 が 1 回以上含まれること。
- 「成功」「失敗」という評価語は使わず、どの state に到達したかで表します。

### 1.4 final_state の 7 区分

| 区分 | 含まれる値 |
|---|---|
| SKIPPED | `SKIPPED` |
| CANCELLED | `CANCELLED` |
| COMPLETED | `COMPLETED` |
| NOT_FOUND | `NOT_FOUND` |
| FAILED_AT_\<STATE\> | `FAILED_AT_` で始まる値（`FAILED_AT_DIALOG_TIMEOUT` を除く） |
| TIMEOUT_AT_\<STATE\> | `TIMEOUT_AT_` で始まる値（`TIMEOUT_AT_COMPLETED` を含む） |
| FAILED_AT_DIALOG_TIMEOUT | `FAILED_AT_DIALOG_TIMEOUT` |

### 1.5 elapsed_ms の区間

| 区間 | 範囲 |
|---|---|
| 0-2s | 0〜2,999 ms |
| 3-5s | 3,000〜5,999 ms |
| 6s+ | 6,000 ms 以上 |

## 2. final_state の分布

| final_state 区分 | 変更前 回数 | 変更前 割合 | 変更後 回数 | 変更後 割合 |
|---|---:|---:|---:|---:|
| SKIPPED | 1 | 0.6% | 0 | 0.0% |
| CANCELLED | 0 | 0.0% | 0 | 0.0% |
| COMPLETED | 0 | 0.0% | 0 | 0.0% |
| NOT_FOUND | 0 | 0.0% | 983 | 40.1% |
| FAILED_AT_\<STATE\> | 50 | 28.4% | 389 | 15.9% |
| TIMEOUT_AT_\<STATE\> | 101 | 57.4% | 1,081 | 44.1% |
| FAILED_AT_DIALOG_TIMEOUT | 24 | 13.6% | 0 | 0.0% |
| 計 | 176 | 100% | 2,453 | 100% |

`<STATE>` 別の内訳:

| final_state | 変更前 | 変更後 |
|---|---:|---:|
| FAILED_AT_DISCONNECTED | 40 | 223 |
| FAILED_AT_SCANNING | 10 | 166 |
| TIMEOUT_AT_COMPLETED | 80 | 669 |
| TIMEOUT_AT_ASSOCIATING | 10 | 179 |
| TIMEOUT_AT_FOUR_WAY_HANDSHAKE | 0 | 178 |
| TIMEOUT_AT_INTERFACE_DISABLED | 11 | 34 |
| TIMEOUT_AT_ASSOCIATED | 0 | 21 |

## 3. elapsed_ms の分布（final_state 区分別）

括弧内は、その区間に接続試行が 1 回以上あるスキャン数（重複なし）。

| final_state 区分 | 変更前 0-2s | 変更前 3-5s | 変更前 6s+ | 変更後 0-2s | 変更後 3-5s | 変更後 6s+ |
|---|---:|---:|---:|---:|---:|---:|
| SKIPPED | 1 (1) | 0 | 0 | 0 | 0 | 0 |
| NOT_FOUND | 0 | 0 | 0 | 8 (2) | 975 (73) | 0 |
| FAILED_AT_\<STATE\> | 15 (9) | 2 (2) | 33 (14) | 108 (70) | 281 (100) | 0 |
| TIMEOUT_AT_\<STATE\> | 75 (38) | 8 (7) | 18 (16) | 703 (215) | 378 (86) | 0 |
| FAILED_AT_DIALOG_TIMEOUT | 0 | 0 | 24 (4) | 0 | 0 | 0 |

CANCELLED と COMPLETED は、両期間とも 0 件。

区間ごとの最小値・最大値（ms）:

| 期間 | final_state 区分 | 区間 | 最小 | 最大 |
|---|---|---|---:|---:|
| 変更前 | SKIPPED | 0-2s | 0 | 0 |
| 変更前 | FAILED_AT_\<STATE\> | 0-2s | 375 | 2,664 |
| 変更前 | FAILED_AT_\<STATE\> | 3-5s | 3,395 | 4,558 |
| 変更前 | FAILED_AT_\<STATE\> | 6s+ | 6,582 | 15,022 |
| 変更前 | TIMEOUT_AT_\<STATE\> | 0-2s | 155 | 2,832 |
| 変更前 | TIMEOUT_AT_\<STATE\> | 3-5s | 3,103 | 5,750 |
| 変更前 | TIMEOUT_AT_\<STATE\> | 6s+ | 6,220 | 15,017 |
| 変更前 | FAILED_AT_DIALOG_TIMEOUT | 6s+ | 15,005 | 15,022 |
| 変更後 | NOT_FOUND | 0-2s | 10 | 931 |
| 変更後 | NOT_FOUND | 3-5s | 3,004 | 3,045 |
| 変更後 | FAILED_AT_\<STATE\> | 0-2s | 273 | 2,973 |
| 変更後 | FAILED_AT_\<STATE\> | 3-5s | 3,008 | 3,050 |
| 変更後 | TIMEOUT_AT_\<STATE\> | 0-2s | 43 | 2,987 |
| 変更後 | TIMEOUT_AT_\<STATE\> | 3-5s | 3,005 | 3,040 |

## 4. elapsed_ms の分布（状態遷移で到達した state 別）

final_state ではなく、状態遷移列の中で最も先に到達した state で分けています。3 グループは排他で、1 回の接続試行はどれか 1 つにだけ入ります。

- **COMPLETED 到達**: COMPLETED を含む。
- **ASSOCIATING まで到達**: ASSOCIATING を含み、ASSOCIATED と COMPLETED を含まない。
- **ASSOCIATED まで到達**: ASSOCIATED を含み、COMPLETED を含まない。ASSOCIATED の後に FOUR_WAY_HANDSHAKE などへ進んだ試行も含む。final_state の内訳は次のとおり。
  - `TIMEOUT_AT_FOUR_WAY_HANDSHAKE` 177 件
  - `TIMEOUT_AT_ASSOCIATED` 21 件
  - `FAILED_AT_DISCONNECTED` 17 件
- ASSOCIATING を 1 回も含まない試行（NOT_FOUND など）は、ここでは対象外。CSV では `reached = NONE` になっている。

| 到達 state | 変更前 0-2s | 変更前 3-5s | 変更前 6s+ | 変更前 計 | 変更後 0-2s | 変更後 3-5s | 変更後 6s+ | 変更後 計 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| COMPLETED 到達 | 66 (35) | 8 (7) | 6 (6) | 80 | 669 (207) | 0 | 0 | 669 |
| ASSOCIATING まで到達 | 24 (15) | 1 (1) | 25 (16) | 50 | 138 (81) | 355 (119) | 0 | 493 |
| ASSOCIATED まで到達 | 0 | 1 (1) | 12 (3) | 13 | 4 (4) | 198 (23) | 0 | 202 |

区間ごとの最小値・最大値（ms）:

| 期間 | 到達 state | 区間 | 最小 | 最大 |
|---|---|---|---:|---:|
| 変更前 | COMPLETED 到達 | 0-2s | 155 | 2,832 |
| 変更前 | COMPLETED 到達 | 3-5s | 3,103 | 5,750 |
| 変更前 | COMPLETED 到達 | 6s+ | 6,220 | 10,669 |
| 変更前 | ASSOCIATING まで到達 | 0-2s | 375 | 2,664 |
| 変更前 | ASSOCIATING まで到達 | 3-5s | 3,395 | 3,395 |
| 変更前 | ASSOCIATING まで到達 | 6s+ | 7,792 | 15,022 |
| 変更前 | ASSOCIATED まで到達 | 3-5s | 4,558 | 4,558 |
| 変更前 | ASSOCIATED まで到達 | 6s+ | 6,582 | 12,636 |
| 変更後 | COMPLETED 到達 | 0-2s | 43 | 2,987 |
| 変更後 | ASSOCIATING まで到達 | 0-2s | 273 | 2,973 |
| 変更後 | ASSOCIATING まで到達 | 3-5s | 3,006 | 3,040 |
| 変更後 | ASSOCIATED まで到達 | 0-2s | 384 | 2,374 |
| 変更後 | ASSOCIATED まで到達 | 3-5s | 3,005 | 3,040 |

## 5. 観測された特徴（事実のみ）

1. 変更後は `elapsed_ms` が 6,000 ms 以上の接続試行が 0 件。変更後の最大値は 3,050 ms。
2. 変更後に 3-5s 区間に入った接続試行は、すべて 3,004〜3,050 ms の範囲にある。
3. 変更後の COMPLETED 到達 669 件は、すべて 3,000 ms 未満（43〜2,987 ms）。変更前は 3,000 ms 以上が 14 件あり、最大は 10,669 ms。
4. 変更後に COMPLETED に到達しなかった試行は、多くが 3-5s 区間（3.0 秒付近）に入っている。ASSOCIATING まで到達は 355/493 件、ASSOCIATED まで到達は 198/202 件。
5. final_state が `COMPLETED` の行は 0 件。COMPLETED 到達試行の final_state は、すべて `TIMEOUT_AT_COMPLETED`。
6. `NOT_FOUND` は変更後にだけ出現する（983 件）。そのうち 975 件が 3,004〜3,045 ms。
7. `FAILED_AT_DIALOG_TIMEOUT` は変更前にだけ出現する（24 件、すべて 15,005〜15,022 ms）。
8. `CANCELLED` は両期間とも 0 件。

## 6. アプリ側への質問

1. 変更後の 3-5s 区間の値が 3,004〜3,050 ms に集中しているのは、OS タイムアウト（3s）で打ち切られた結果か。
2. 変更後のデータには 3,050 ms を超える接続試行が 1 件もない。10s のコールバックタイムアウトは、どのような条件で効く設計か。また、OS タイムアウトとの関係（どちらが先に判定されるか、合計 13s になる経路があるか）はどうなっているか。
3. COMPLETED に到達したのに final_state が `TIMEOUT_AT_COMPLETED` として記録されるのはなぜか。COMPLETED 到達を検知した時点で計測を終了しているか。また、そのときの `elapsed_ms` は「COMPLETED 到達までの時間」と解釈してよいか。
4. `NOT_FOUND` が変更後にだけ出現し、`FAILED_AT_DIALOG_TIMEOUT` が変更前にだけ出現する理由は何か。タイムアウト変更と同時に、判定ロジックや final_state の命名も変更したか。
5. `elapsed_ms` の計測開始点と終了点はどこか（例: `requestNetwork()` 呼び出し時からコールバック受信時まで）。
6. 変更後のデータでは、COMPLETED 到達はすべて 3s 未満で終わっている。移動しながら測定することを前提にすると、1 回の接続試行のタイムアウトを何秒に設定するのが妥当か。アプリの実装上の制約（OS の最小タイムアウト、連続して `requestNetwork()` を呼ぶときの待ち時間など）も含めて意見がほしい。

## 7. 添付 CSV（`supplicant_attempts.csv`）の列

- 文字コードは UTF-8（BOM 付き）、区切りはカンマ。
- 対象は `supplicant_final_state` が空でない接続試行 2,629 件（変更前 176 件、変更後 2,453 件）。
- NULL は空欄にしている。

| 列 | 内容 |
|---|---|
| attempt_id | 接続試行の ID（サーバ DB の `scan_aps.id`） |
| scan_id | スキャンの ID。同じスキャン内の接続試行は同じ値になる |
| period | `before`（〜2026-09-13）/ `after`（2026-09-14〜） |
| scanned_at | スキャン時刻 |
| device_model / android_api | 測定機種と Android API レベル |
| ssid | SSID。複数ある場合は `\|` 区切り。空欄は SSID なし |
| bssid / frequency_mhz / band / rssi_dbm / wifi_standard | スキャン時の AP 情報。RSSI はスキャン時の値で、接続試行中の値ではない |
| final_state | アプリが送信した `supplicant_final_state` |
| final_state_category | 1.4 の 7 区分 |
| elapsed_ms | アプリが送信した `supplicant_elapsed_ms` |
| elapsed_bin | 1.5 の区間（`0-2s` / `3-5s` / `6s+`） |
| reached | 4 章のグループ（`COMPLETED` / `ASSOCIATED_ONLY` / `ASSOCIATING_ONLY` / `NONE`） |
| state_count | 状態遷移列の長さ |
| state_sequence | 状態遷移列（`>` 区切り、記録順） |
