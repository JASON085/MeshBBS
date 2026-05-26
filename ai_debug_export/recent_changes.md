# Recent Changes

生成時間：2026-05-26  
用途：整理最近為了 MQTT boundary loss / chunk recovery / LoRa direct 回歸調整而變動的檔案與差異摘要。

## 1. 變更脈絡

### 2026-05-23 - `hop4` 與 MQTT 保守優化

主要影響：
- client / server 的 `hopLimit` 改到 `4`
- client `BBS:REQC` chunk 間隔從 `220ms` 調到 `320ms`
- client / server 都加 stale chunk cleanup

相關檔案：
- `MeshtasticRepository.kt`
- `MeshtasticServerRepository.kt`
- `AndroidServerService.kt`

摘要：
- 這次先偏向保守，目的是先提升 MQTT 路徑穩定性。
- 副作用是後續修 MQTT 的方向開始往更小 chunk、更慢節奏傾斜。

## 2. 2026-05-25 - MQTT chunk debug 與 recovery

主要影響：
- 加入大量 chunk debug log
- response chunk size 一度縮到 `96`
- chunk delay 拉到 `600ms`
- client reassembly 改為可 out-of-order cache
- 加入 timeout cleanup
- 加入 broadcast response debug mode

相關檔案：
- `MeshtasticRepository.kt`
- `MeshtasticServerRepository.kt`
- `AndroidServerService.kt`
- `ServerHostStore.kt`
- `ServerModels.kt`
- `ServerHostScreen.kt`

差異摘要：

### 哪裡調小了 chunk size

- `MeshtasticServerRepository.kt`
  - 舊固定值曾為 `RESPONSE_CHUNK_BYTES = 160`
  - MQTT 修正階段一度改成更保守的小 chunk（後續被 profile 化取代）

### 哪裡增加 delay

- `MeshtasticServerRepository.kt`
  - 舊固定值曾為 `RESPONSE_CHUNK_PAUSE_MS = 420L`
  - MQTT 修正階段一度拉到 `600ms`

### 哪裡改 timeout

- `MeshtasticRepository.kt`
  - 舊版為固定 `PENDING_CHUNK_TIMEOUT_MS = 90_000L`
  - 後來改為動態：
    - `BASE_PENDING_CHUNK_TIMEOUT_MS = 60_000L`
    - `PER_CHUNK_TIMEOUT_MS = 8_000L`
    - `MAX_PENDING_CHUNK_TIMEOUT_MS = 240_000L`

### 哪裡改 pending chunk handling

- `MeshtasticRepository.kt`
  - `handleMbbs2(...)`
  - `handleBbsRes(...)`
  - `pruneStalePendingChunks(...)`
  - 變更內容：
    - 允許 out-of-order chunk 先存
    - `pending` / `missing` 詳細 log
    - `CHUNK_TIMEOUT` 會帶 `seq / cmd / expectedTotal / received / missing / elapsedMs`

- `AndroidServerService.kt`
  - `handleCompressedRequest(...)`
  - `pruneStaleCompressedRequests(...)`
  - 變更內容：
    - `REQC_RX / REQC_WAIT / REQC_TIMEOUT / REQC_DROP` 詳細 log

### 哪裡改 directed / broadcast 行為

- `MeshtasticServerRepository.kt`
  - `sendResponse(...)` 會依 `broadcastResponsesForDebug` 決定 `wireTarget`
- `ServerHostStore.kt`
  - 新增 `setBroadcastResponsesForDebug(...)`
- `ServerModels.kt`
  - `ServerHostState.broadcastResponsesForDebug`
- `ServerHostScreen.kt`
  - UI 新增 `Response 廣播測試`

## 3. 2026-05-25 - TransportProfile 分流

主要影響：
- 把原本全域固定的小 chunk / 慢 delay 改成 profile
- `LORA_DIRECT` 與 `MQTT_SAFE` 分流

相關檔案：
- `ServerModels.kt`
- `ServerHostStore.kt`
- `MeshtasticServerRepository.kt`
- `ServerHostScreen.kt`

差異摘要：

### 新增 profile

- `LORA_DIRECT`
- `MQTT_SAFE`
- `DEBUG_ULTRA_SAFE`

### 新增可調參數

- `transportProfile`
- `responseChunkSize`
- `responseChunkDelayMs`

### sendResponse 改動

- 不再依賴單一硬編碼常數
- 改為：
  - `currentResponseTransportSettings()`
  - `sendResponse(...)` 依 profile 的 `chunkSize` / `delayMs`
  - `TX_PLAN` summary log

## 4. 2026-05-26 - LoRa direct 回歸調整（目前狀態）

主要影響：
- `LORA_DIRECT` 改成 `200 / 250ms`
- `MQTT_SAFE` 保留 `170 / 600ms`
- timeout 改成依 `totalChunks` 動態延長
- 補 response summary log 與 client timeout log

相關檔案：
- `ServerModels.kt`
- `MeshtasticServerRepository.kt`
- `MeshtasticRepository.kt`
- `build.gradle.kts`

差異摘要：

### 哪裡調回較大的 LoRa direct chunk size

- `ServerModels.kt`
  - `TransportProfile.LORA_DIRECT(200, 250L)`

### 哪裡保留 MQTT safe profile

- `ServerModels.kt`
  - `TransportProfile.MQTT_SAFE(170, 600L)`

### 哪裡增加 response summary log

- `MeshtasticServerRepository.kt`
  - `sendResponse(...)`
  - `TX_PLAN command=... rawBytes=... compressedBytes=... totalChunks=... selectedProfile=...`

### 哪裡增加 client timeout log

- `MeshtasticRepository.kt`
  - `pruneStalePendingChunks(...)`
  - `CHUNK_TIMEOUT seq=... cmd=... expectedTotal=... received=... missing=... elapsedMs=...`

## 5. 與目前 LoRa direct READ 問題最相關的檔案

優先分析順序建議：

1. `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/MeshtasticServerRepository.kt`
2. `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/data/MeshtasticRepository.kt`
3. `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/viewmodel/BbsViewModel.kt`
4. `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/AndroidServerService.kt`
5. `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/ServerModels.kt`
