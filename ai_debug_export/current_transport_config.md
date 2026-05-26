# Current Transport Config

生成時間：2026-05-26  
用途：提供另一位 AI 工程助手快速確認目前 Android client / Android server 的 LoRa direct 與 MQTT 傳輸參數。

## 1. Server response profiles

來源檔案：
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/ServerModels.kt`
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/ServerHostStore.kt`
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/MeshtasticServerRepository.kt`

目前定義：

| Profile | chunk size | chunk delay | 用途 | 設定位置 |
|---|---:|---:|---|---|
| `LORA_DIRECT` | 200 bytes | 250 ms | 預設 LoRa direct | `TransportProfile.LORA_DIRECT` |
| `MQTT_SAFE` | 170 bytes | 600 ms | MQTT / relay 測試 | `TransportProfile.MQTT_SAFE` |
| `DEBUG_ULTRA_SAFE` | 120 bytes | 900 ms | 極保守 debug | `TransportProfile.DEBUG_ULTRA_SAFE` |

目前預設 selected profile：
- `ServerHostState.transportProfile = TransportProfile.LORA_DIRECT`
- `ServerHostState.responseChunkSize = TransportProfile.LORA_DIRECT.responseChunkSize`
- `ServerHostState.responseChunkDelayMs = TransportProfile.LORA_DIRECT.responseChunkDelayMs`

實際套用位置：
- `MeshtasticServerRepository.currentResponseTransportSettings()`
- `MeshtasticServerRepository.sendResponse(...)`

## 2. Server runtime controls

來源檔案：
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/ServerHostStore.kt`
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/ui/screens/ServerHostScreen.kt`

目前可調整參數：

| 參數 | 目前值 / 範圍 | 說明 | 來源 |
|---|---|---|---|
| `hopLimit` | 預設 `4`，UI 限 `1..7` | server 送出 `DataPacket.hopLimit` | `ServerHostState.hopLimit` / `ServerHostStore.setHopLimit()` |
| `transportProfile` | `LORA_DIRECT` / `MQTT_SAFE` / `DEBUG_ULTRA_SAFE` | response 切 chunk 與 delay 的主設定 | `ServerHostStore.setTransportProfile()` |
| `responseChunkSize` | `80..220` | 可由 profile 覆蓋，也可個別調整 | `ServerHostStore.setResponseChunkSize()` |
| `responseChunkDelayMs` | `100..2000` | 可由 profile 覆蓋，也可個別調整 | `ServerHostStore.setResponseChunkDelayMs()` |
| `broadcastResponsesForDebug` | `false` 預設 | `true` 時 response 改送 `^all` | `ServerHostStore.setBroadcastResponsesForDebug()` |

## 3. Server response send parameters

來源檔案：
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/MeshtasticServerRepository.kt`

| 參數 | 目前行為 | 設定 function |
|---|---|---|
| chunk 切法 | `deflate(rawJson)` 後用 `chunkedBytes(settings.chunkSize)` 切分 | `sendResponse()` |
| chunk header | `MBBS2|<destId>|<seq>|<index>|<total>\n` | `sendResponse()` |
| per-chunk delay | 每個 chunk 發完 `Thread.sleep(settings.delayMs)` | `sendPrivate()` |
| retry 次數 | `0` 次顯式 chunk resend | `sendResponse()` / `sendPrivate()` |
| ACK | `wantAck = false` | `sendPrivate()` |
| target selection | `broadcastResponsesForDebug=true` 時送 `^all`，否則 directed 到 `destId` | `sendResponse()` |

## 4. Client request transport parameters

來源檔案：
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/data/MeshtasticRepository.kt`

| 參數 | 目前值 | 用途 | 設定位置 |
|---|---:|---|---|
| `REQUEST_CHUNK_CHARS` | 180 | `BBS:REQC` Base64 字串切段大小 | companion object |
| `REQUEST_CHUNK_PAUSE_MS` | 320 ms | `POST/REPLY/EDIT/EDITREP` 每段發送間隔 | `sendCompressedReq()` |
| `PACKET_HOP_LIMIT` | 4 | client 發 request / text / mesh chat 的 hopLimit | `sendRaw()` / `sendPlainText()` / `sendMeshChat()` |
| `wantAck` | `false` | client 發送不要求 ACK | `sendRaw()` / `sendPlainText()` / `sendMeshChat()` |
| retry 次數 | `0` 次顯式 request chunk resend | `sendReq()` / `sendCompressedReq()` |

## 5. Client response reassembly timeout

來源檔案：
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/data/MeshtasticRepository.kt`

目前 timeout 公式：

```text
pendingChunkTimeoutMs(total) =
    min(
        BASE_PENDING_CHUNK_TIMEOUT_MS + total * PER_CHUNK_TIMEOUT_MS,
        MAX_PENDING_CHUNK_TIMEOUT_MS
    )
```

目前常數：

| 常數 | 值 | 設定位置 |
|---|---:|---|
| `BASE_PENDING_CHUNK_TIMEOUT_MS` | 60000 ms | companion object |
| `PER_CHUNK_TIMEOUT_MS` | 8000 ms | companion object |
| `MAX_PENDING_CHUNK_TIMEOUT_MS` | 240000 ms | companion object |
| `PENDING_CHUNK_CLEANUP_INTERVAL_MS` | 5000 ms | companion object |

說明：
- timeout 只用在 client 端等待 `MBBS2` / `BBS:RES` 回應重組。
- cleanup 由 `startChunkCleanup()` 每 5 秒掃一次。
- timeout 後會發 `BbsEvent.ServerError(...)`，並清掉 pending。

## 6. Request-side / UI retry

來源檔案：
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/viewmodel/BbsViewModel.kt`

這些不是 chunk resend，而是 UI 層重新送 request：

| 命令 | 自動重試 | 時間 |
|---|---|---|
| `READ` | 2 次 | 第 5 秒、第 9 秒 |
| `POSTS` 第 1 頁 | 2 次 | 第 4 秒、第 8 秒 |
| `LIST` | 無專屬自動重試 | 僅依畫面流程重新觸發 |

## 7. Server request-side timeout

來源檔案：
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/AndroidServerService.kt`

| 參數 | 目前值 | 說明 |
|---|---:|---|
| `COMPRESSED_REQUEST_TIMEOUT_MS` | 90000 ms | server 重組 `BBS:REQC` 的 timeout |

## 8. 問題焦點

目前最值得關注的傳輸參數組合：

1. LoRa direct response 預設已回到 `200 bytes / 250 ms`，理論上不應再套用 `MQTT_SAFE` 的 `170 / 600ms`。
2. client 端 `READ` 若回應 chunk 數很多，timeout 會依 `totalChunks` 變長，但 UI 仍有自己的 `READ` 自動重試節奏。
3. 現行 response chunk 沒有顯式 resend，任何單一 chunk 漏失都只能靠重送整個 request 補救。
