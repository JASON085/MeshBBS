# Meshtastic APP 通訊技術整理

本文件整理目前 `Meshtastic BBS-Android` 專案裡，與 **Meshtastic Android APP 通訊** 有關的實作方式、封包設計、可靠性問題與踩坑紀錄。

目標是讓下一個基於 Meshtastic 的新專案，尤其是 Android 專案，不要再從同樣的坑重踩一次。

---

## 1. 建議的整體架構

### 1.1 Android 端不要自己重做 LoRa/BLE 通訊堆疊

這個專案採用的策略是：

- **自己的 APP 不直接控制 LoRa 硬體**
- **改由已安裝的 Meshtastic APP 當中介層**
- 我們的 APP 只負責：
  - 綁定 Meshtastic APP 的 Android Service
  - 送出 `DataPacket`
  - 接收 Meshtastic APP 廣播回來的封包
  - 在自己的 APP 內做協議層、重組、壓縮、UI、業務邏輯

這樣做的優點：

- 不必自己維護 BLE / USB / Radio 狀態機
- 可以直接沿用 Meshtastic APP 已有的裝置連線流程
- 使用者先在 Meshtastic APP 配對好 LoRa 裝置，我們的 APP 再接上去

這樣做的缺點：

- 你的 APP 會受 Meshtastic APP 的版本、AIDL、Parcelable 結構影響
- 不是完全官方公開穩定 SDK，某些地方實際上要靠實測
- 手機熄屏、電池最佳化、Meshtastic APP 自身狀態，都會影響你的 APP

---

## 2. Android 與 Meshtastic APP 的通訊方式

### 2.1 核心做法：Bind Meshtastic 的 Service

目前專案的 Android client 與 Android server，都是透過下列方式接 Meshtastic APP：

- 綁定封包服務：`IMeshService`
- 封包模型：`DataPacket`
- 接收回來的資料：`BroadcastReceiver` + `subscribeReceiver(...)`

關鍵檔案：

- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/data/MeshtasticRepository.kt`
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/MeshtasticServerRepository.kt`
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/data/MeshPacketReceiver.kt`
- `android/MeshtasticBBS/app/src/main/AndroidManifest.xml`

### 2.2 綁定 Service 時要做多組 fallback

Meshtastic APP 的 service component 名稱不一定只會有一種寫法，這個專案實作了 3 種 bind 嘗試：

1. `Intent("com.geeksville.mesh.Service").setPackage("com.geeksville.mesh")`
2. `com.geeksville.mesh.service.MeshService`
3. `org.meshtastic.core.service.MeshService`

不要只寫單一路徑，否則換版本或不同包裝時很容易直接綁不上。

---

## 3. Manifest、Permission、Queries 必要項

至少要注意這些：

- `android.permission.INTERNET`
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.WAKE_LOCK`
- `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- `com.geeksville.mesh.MESHSERVICE`
- Android 12+ 的 `BLUETOOTH_CONNECT`
- Android 12+ 的 `BLUETOOTH_SCAN`

另外一定要有：

- `<queries>` 讓系統允許你查詢 / 綁定 `com.geeksville.mesh`

如果缺這些，常見現象是：

- Meshtastic APP 明明有裝，但 `bindService` 失敗
- `send()` 沒反應
- 使用者看起來像有連線，實際上你的 APP 完全拿不到資料

---

## 4. `subscribeReceiver(...)` 是關鍵，不是可有可無

### 4.1 只註冊 runtime receiver 不夠

這個專案踩到的重要坑是：

- Meshtastic 2.7.x 的某些封包，尤其 directed broadcast，不會只靠動態註冊 receiver 就收到
- 必須：
  - 在 Manifest 宣告一個 **靜態 receiver**
  - 再呼叫 `subscribeReceiver(packageName, receiverClassName)`

這個專案最後採用的 receiver 類別：

- `com.meshtastic.bbs.data.MeshPacketReceiver`

### 4.2 receiver 類別名稱不要亂組

Android server 這邊有踩過：

- 不能只依賴字串拼接 `${context.packageName}.data.MeshPacketReceiver`
- 最穩的是直接固定傳已知正確 component class name

### 4.3 Manifest 中要先宣告 action

目前專案宣告過的 action：

- `com.geeksville.mesh.RECEIVED.TEXT_MESSAGE_APP`
- `com.geeksville.mesh.RECEIVED.text`
- `com.geeksville.mesh.RECEIVED.PRIVATE_APP`
- `com.geeksville.mesh.RECEIVED.private`

---

## 5. `DataPacket` / AIDL 是高風險區

### 5.1 `DataPacket` 欄位順序必須和 Meshtastic APP 對上

這是最危險也最容易忽略的地方。

如果你自己複製了一份 `DataPacket` Parcelable 類別，那麼：

- 欄位順序
- 欄位型別
- `Parcel` 讀寫順序

都必須和 Meshtastic APP 版本相符。

只要錯一個欄位，常見結果就是：

- 收得到廣播，但 parse 出來全是垃圾
- `from` / `to` / `bytes` 位移掉
- Service `send()` 或 `getParcelableExtra()` 行為異常

目前專案有兩套相關模型：

- `org.meshtastic.core.model.DataPacket`
- `com.geeksville.mesh.DataPacket`

這代表實務上你可能需要為不同接點做相容層，但核心原則一樣：

- **不要假設未來版本欄位永遠不變**

### 5.2 AIDL method order 也必須對

`IMeshService.aidl` 不是只要方法名字看起來像就好，**transaction order 必須對齊**。

本專案保留 placeholder method，就是為了讓 transaction code 不錯位。

如果順序錯：

- `getMyId()` 可能實際打到別的方法
- `send()` 可能完全失效
- `getNodes()` 可能丟 `SecurityException`

### 5.3 不要過度依賴 `getNodes()`

此專案經驗裡，某些版本 / 路徑下：

- `getNodes()` 可能受限
- 有時會有 `SecurityException`

所以節點資訊不要只靠同步 API 抓，應搭配：

- Meshtastic 廣播事件
- 緩存
- 你的 APP 自己的觀測模型

---

## 6. 封包型別與實際使用方式

### 6.1 目前專案實際用到的型別

- `TEXT_MESSAGE_APP = 1`
- `PRIVATE_APP = 256`
- 自訂 `BBS_APP = 257`

### 6.2 何時用純文字

適合：

- 很短的指令
- 簡短提示
- 使用者手動輸入的簡訊式命令

這個專案的純文字請求例子：

- `BBS:REQ:<seq>:<CMD>:<args>`

### 6.3 何時用自訂 binary / private app payload

適合：

- 大於一般純文字可穩定承載的資料
- 需要分段重組
- 想做壓縮
- 想降低可讀字串暴露

這個專案的做法：

- `MBBS1`：自訂 private text prefix
- `MBBS2|...`：binary chunk envelope

---

## 7. 這個專案的協議設計

### 7.1 短請求：`BBS:REQ`

格式：

```text
BBS:REQ:<seq>:<CMD>:<args>
```

適合：

- `LIST`
- `POSTS`
- `READ`
- `LOGIN`
- `PUSH`

### 7.2 大請求：`BBS:REQC`

當請求內容變長，例如：

- 發文
- 回覆
- 編輯文章
- 編輯回覆

就先做：

1. UTF-8 bytes
2. `zlib`
3. `Base64`
4. 以每段 180 字元切塊

格式：

```text
BBS:REQC:<seq>:<CMD>:<index>:<total>:<chunk>
```

這個設計的好處：

- 先壓縮，chunk 數量變少
- Base64 後再走文字封包，相容性較高
- server 端只要依 `fromId:seq` 重組即可

### 7.3 大回應：`MBBS2`

server 回應較大資料時，做法是：

1. JSON UTF-8
2. `Deflater.BEST_COMPRESSION`
3. 切為多段 binary chunk
4. 每段前面加 header

格式：

```text
MBBS2|<destId>|<seq>|<index>|<total>\n<binary>
```

client 端收到後：

1. 依 `seq` 暫存
2. 全段到齊後合併
3. `zlib inflate`
4. parse JSON

---

## 8. 為什麼 chunk 大小不能貪心

Meshtastic 訊息實務上非常容易踩 payload 限制。

### 8.1 目前專案的保守值

- 純文字幫助訊息：控制在 **180 bytes 以內**
- `BBS:REQC`：每段 **180 chars**
- `MBBS2` binary：每段 **178 bytes**

這些數字不是隨便寫的，而是為了預留：

- 協議 header
- node id / seq / index / total
- UTF-8 與 Base64 的膨脹
- MQTT 路徑下更差的穩定性

### 8.2 不要只看理論 237 bytes

桌面 Python server 端也明確註記：

- Meshtastic 單則訊息上限約 **237 bytes UTF-8**

但實際上你真正能拿來塞自訂資料的空間，永遠要再扣掉：

- 協議 header
- prefix
- Base64 膨脹
- 路由與 transport 額外變數

所以新專案請直接採 **保守 payload 設計**。

---

## 9. MQTT 模式真的比較容易掉邊界包

這次實測的重要結論：

- 正常 Meshtastic 模式下，私訊 `?` 會收到完整多段說明
- 開啟 MQTT 之後，出現 **只收到中間段、首尾段漏掉** 的情況

因此目前修正策略是：

- 幫助訊息改成更短的 6 段
- 每段控制在 180 bytes 以下
- 首包延遲：`900ms`
- 段間延遲：`3600ms`
- 尾包保留：`1200ms`

### 9.1 這代表什麼

如果下一個專案要做對講機 / 語音 / 即時互動：

- **不要假設 MQTT 路徑下，小包連發就一定穩**
- 特別要小心第一包與最後一包
- 最好自己做：
  - seq
  - chunk index
  - total
  - timeout
  - dedupe

---

## 10. 去重與重組是必做，不是加分項

### 10.1 request dedupe

Android server 目前做法：

- 以 `fromId:seq` 當 dedupe key
- 保留最近一段時間內的 key

這是因為 mesh / MQTT / 重傳環境下，你不能保證同一請求只到一次。

### 10.2 plain text dedupe

對於 `?`、`/?` 這種短文字命令，也做了短時間內的 dedupe 視窗。

### 10.3 chunk reassembly

無論 client 或 server，只要做 chunk：

- 一定要有 pending map
- key 至少要包含 `seq`
- 若多來源同時存在，key 最好用 `fromId:seq`

否則很容易把不同裝置的分段混在一起。

---

## 11. 心跳與在線狀態不要只看 Meshtastic 連上沒

Meshtastic APP 顯示已連線，不代表你的上層應用真的還活著。

目前專案的做法：

- client 每 60 秒送一次 `HEARTBEAT`
- server 端更新使用者 `last_seen`
- server 定期 prune stale client
- stale 門檻目前使用過 **75 秒**

這能解決：

- APP 還在，但上層 session 已死掉
- 使用者列表長時間顯示假在線

---

## 12. 手機熄屏 / 背景限制是真正的大坑

### 12.1 僅有 `PARTIAL_WAKE_LOCK` 不一定夠

這次 Android server 長時間測試的現象：

- 早上開 server
- 把螢幕關掉
- 下午 Meshtastic APP 看起來還連著
- 但私訊 `?` 已經沒反應
- 必須重啟 Android server APP 與 Meshtastic APP 才恢復

所以目前專案最後採取兩層保護：

- Service 端：`PARTIAL_WAKE_LOCK`
- UI 端：可選的 **暗屏常亮鎖屏模式**

### 12.2 新專案建議

如果你的新專案是對講機 APP，而且必須長時間待命：

- 前景服務是基本
- 請求使用者忽略電池最佳化
- 必要時提供「暗屏常亮」而不是讓使用者真的關屏

因為實務上：

- 真正熄屏後，Meshtastic APP / 你的 APP / BLE / 背景廣播鏈路，可能有一段會被系統打斷

---

## 13. 不要亂收太多 Meshtastic 廣播

這個專案的 manifest 註解已經提到一個重要經驗：

- 當節點很多時，如果把 `NODEINFO_APP` / `POSITION_APP` 之類也一起大量接收
- 很可能造成 handler 高頻觸發
- 進一步引發 BLE disconnect 或整體不穩

因此：

- 若你的 APP 核心不是節點地圖 / 大規模 telemetry
- 優先只收自己真正需要的 action

---

## 14. Android 14+ receiver 規則要跟上

在較新的 Android 版本上，`registerReceiver()` 需要正確帶 flag。

目前專案使用：

```kotlin
ContextCompat.registerReceiver(
    context,
    receiver,
    filter,
    ContextCompat.RECEIVER_EXPORTED,
)
```

如果你沿用舊寫法，可能在新 Android 上直接收不到。

---

## 15. 新專案若要做對講機 / PTT，建議先接受這些現實

下面這段是根據本專案經驗做的推論，不是 Meshtastic 官方保證：

### 15.1 不要直接丟原始語音

理由：

- payload 很小
- chunk 數一多就容易掉
- MQTT 路徑更不穩
- 即時性會被 LoRa / hop / 重組 / 延遲拉垮

### 15.2 若真的要語音，請先做極限保守設計

建議方向：

- 極低碼率編碼
- 很短的 frame
- 每 frame 自帶 seq
- 能接受掉包
- 可以只做「半即時」而非電話式即時串流

### 15.3 更務實的做法

對講機 APP 可先分兩層：

- 第一層：控制訊號
  - 誰在講
  - 開始講
  - 結束講
  - 佔線中
- 第二層：語音資料
  - 再看是否真的要走 Meshtastic

否則很容易花很多時間後才發現：

- 不是編碼器有問題
- 是底層傳輸量根本不適合長語音流

---

## 16. 新專案建議直接沿用的做法

如果下一個 Android 專案也要走 Meshtastic APP，建議直接延續：

1. 綁定 Meshtastic APP 的 `IMeshService`
2. Manifest 宣告靜態 `MeshPacketReceiver`
3. 啟動後立即 `subscribeReceiver(...)`
4. 保留多組 `bindService` fallback
5. 所有大 payload 先壓縮再切塊
6. chunk 大小先用保守值
7. 每個封包都帶 `seq/index/total`
8. 做 dedupe
9. 做 heartbeat
10. 做前景服務 + WakeLock + 忽略電池最佳化
11. 若要長時間待機，提供暗屏常亮模式

---

## 17. 本專案可直接參考的檔案

### Android client

- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/data/MeshtasticRepository.kt`
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/data/MeshPacketReceiver.kt`
- `android/MeshtasticBBS/app/src/main/AndroidManifest.xml`

### Android server

- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/MeshtasticServerRepository.kt`
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/AndroidServerService.kt`
- `android/MeshtasticBBS/app/src/main/python/meshbbs_android_server.py`

### AIDL / Parcelable 相容層

- `android/MeshtasticBBS/app/src/main/aidl/com/geeksville/mesh/IMeshService.aidl`
- `android/MeshtasticBBS/app/src/main/aidl/org/meshtastic/core/service/IMeshService.aidl`
- `android/MeshtasticBBS/app/src/main/java/org/meshtastic/core/model/DataPacket.kt`
- `android/MeshtasticBBS/app/src/main/java/com/geeksville/mesh/DataPacket.kt`

### 桌面 / Python 端參考

- `meshtastic_bbs_server.py`
- `mesh_bbs_client.py`

---

## 18. 最後的結論

如果新專案要做的是「基於 Meshtastic 的 Android 對講機 APP」，目前最值得記住的不是某段 UI 或某個 class，而是下面這幾句：

- **把 Meshtastic APP 當 transport，不要自己重寫 radio stack**
- **`subscribeReceiver(...)` + 靜態 receiver 是必要條件**
- **AIDL method order 與 `DataPacket` Parcelable 相容性是高風險區**
- **payload 一定要保守，chunk 一定要能重組，重傳 / 去重要自己做**
- **MQTT 下邊界包特別容易掉**
- **真正熄屏可能讓整個鏈路表面在線、實際失效**

如果下一個專案要繼續做，我建議先把：

- `MeshtasticRepository.kt`
- `MeshtasticServerRepository.kt`
- `MeshPacketReceiver.kt`
- `AndroidManifest.xml`
- `DataPacket.kt`

這幾塊先完整移植，再開始做對講機協議本身。
