# MBBS2 Flow

生成時間：2026-05-26  
用途：說明目前 Android Server 與 Android client 的 MBBS2 收發流程，讓另一位 AI 工程助手可以直接追查 READ 卡住點。

## 1. 指令路徑概覽

### 1.1 Client -> Server

小請求：

```text
BBS:REQ:<seq>:<cmd>:<args>
```

大請求（POST / REPLY / EDIT / EDITREP）：

```text
BBS:REQC:<seq>:<cmd>:<index>:<total>:<base64 chunk>
```

### 1.2 Server -> Client

目前主要回應格式是 MBBS2：

```text
MBBS2|<destId>|<seq>|<index>|<total>\n<binary compressed chunk>
```

client 端仍保留舊的 `BBS:RES:` text chunk handler 相容邏輯，但 Android server 的現行主要 response path 是 `MBBS2`.

## 2. Server 如何切 chunk

入口：
- `AndroidServerService.handleIncomingRequest(...)`
- `AndroidServerService.handleCompressedRequest(...)`

流程：

1. server 收到 `BBS:REQ` 或 `BBS:REQC`
2. `PythonServerBridge.handleRequest(cmd, args, fromId, fromId)` 產生 JSON 字串回應
3. `MeshtasticServerRepository.sendResponse(destId, seq, responseJson, cmd)` 被呼叫
4. `responseJson.toByteArray(UTF_8)`
5. `deflate(rawBytes)` 壓縮
6. `compressed.chunkedBytes(settings.chunkSize)` 依目前 `TransportProfile` 切段
7. 每段都組成：
   - header：`MBBS2|<destId>|<seq>|<index>|<total>\n`
   - body：該段 compressed bytes
8. 每個 chunk 透過 `sendPrivate(...)` 發成 Meshtastic `DataPacket`

## 3. 每個 chunk 的實際格式

header：

```text
MBBS2|<destId>|<seq>|<index>|<total>\n
```

欄位意義：

| 欄位 | 意義 |
|---|---|
| `destId` | 目標 client node id |
| `seq` | 對應原 request 的序號 |
| `index` | 0-based chunk index |
| `total` | 總 chunk 數 |
| `\n` 後 payload | deflate 壓縮後的原始 bytes 分段 |

發送包裝：

| 欄位 | 值 |
|---|---|
| `DataPacket.to` | `destId` 或 `^all`（broadcast debug mode） |
| `DataPacket.dataType` | `BBS_APP` (`257`) |
| `DataPacket.hopLimit` | `ServerHostStore.currentHopLimit()` |
| `DataPacket.wantAck` | `false` |

## 4. Client 如何收 chunk

入口：
- `MeshtasticRepository.handlePacket(packet)`
- 優先嘗試 `handleMbbs2(bytes, fromNode, toNode)`
- 若是 text chunk，走 `handleBbsRes(text, fromNode, toNode)`

### 4.1 MBBS2 路徑

1. 找 `\n` 分隔 header / payload
2. `header.split("|")`
3. 解析：
   - `destId`
   - `seq`
   - `idx`
   - `total`
4. 驗證：
   - `total > 0`
   - `idx in 0 until total`
   - `destId == myNodeId`（若 client 已知自己的 node id）
   - `seq` 尚未在 `completed`
5. `pending.getOrPut(seq) { PendingChunk() }`
6. 以 `seq` 當 pending key，將 payload 存到 `entry.chunks[idx]`
7. `entry.total = total`
8. 若 `entry.chunks.size < total`：先留在 pending，等待其他 chunk
9. 若數量達到 `total`，再檢查是否仍有 missing index
10. 沒缺塊才 assemble

### 4.2 BBS:RES 相容路徑

流程類似，但 body 是 base64 text：

```text
BBS:RES:<destId>:<seq>:<index>:<total>:<base64 data>
```

組回時用：

1. 依 index 串接 base64 字串
2. `Base64.decode(joined, Base64.DEFAULT)`
3. `zlibDecompress(...)`
4. parse JSON

## 5. pending key 怎麼組

### 5.1 Client response pending key

來源：
- `MeshtasticRepository.pending: ConcurrentHashMap<String, PendingChunk>`

目前 key：

```text
seq
```

也就是：
- request 送出時 `sendReq()` 會先 `pending[seq] = PendingChunk(cmd = cmd, stage = stage)`
- response 到達時 `handleMbbs2()` / `handleBbsRes()` 用同一個 `seq` 找回 pending entry

### 5.2 Server compressed request pending key

來源：
- `AndroidServerService.pendingCompressedRequests`

目前 key：

```text
<fromId>:<seq>
```

server 端這樣做是為了避免不同 client 用到同樣 seq 時互相覆蓋。

## 6. 何時判斷完成

client 完成條件：

1. `entry.chunks.size >= total`
2. `missingChunkIndices(total, entry.chunks.keys)` 為空
3. assemble / decompress / parse JSON 成功

成功後：

1. `completed.add(seq)`
2. `pending.remove(seq)`
3. 若 stage 不空，`emit(BbsEvent.LoadProgress("", 0, false))`
4. 發 `CHUNK_DONE`
5. 回傳實際 `BbsEvent`

## 7. 何時 timeout / fail

### 7.1 Client response timeout

來源：
- `MeshtasticRepository.pruneStalePendingChunks(now)`

條件：

```text
now - entry.updatedAtMs > pendingChunkTimeoutMs(entry.total)
```

公式：

```text
pendingChunkTimeoutMs(total) =
    min(60000 + total * 8000, 240000)
```

timeout 後：

1. 發 `CHUNK_TIMEOUT seq=<seq> cmd=<cmd> expectedTotal=<total> received=... missing=... elapsedMs=... timeoutMs=...`
2. 若此 pending 有 `stage`，發：
   - `BbsEvent.LoadProgress("", 0, false)`
   - `BbsEvent.ServerError("<stage> 接收逾時，缺少 ...")`
3. `pending.remove(seq)`

### 7.2 Client fail / drop 條件

會產生 `CHUNK_DROP` 的常見原因：

- `missing_header`
- `invalid_header`
- `invalid_index`
- `dest_mismatch`
- `already_completed`
- `parse_failed`

### 7.3 Server request-side timeout

來源：
- `AndroidServerService.pruneStaleCompressedRequests(now)`

條件：

```text
now - pending.updatedAtMs > COMPRESSED_REQUEST_TIMEOUT_MS
```

目前值：

```text
90000 ms
```

timeout 後：

```text
REQC_TIMEOUT pendingKey=<fromId:seq> cmd=<cmd> pending=[...] missing=[...] timeoutMs=90000
```

## 8. 與本次 LoRa direct READ 問題最相關的節點

1. server `sendResponse()` 是否用到 `LORA_DIRECT`，而不是 `MQTT_SAFE`
2. `TX_PLAN` 的 `totalChunks` 是否對 `READ` 過高
3. client `CHUNK_RX` 是否能收到首尾段
4. client `CHUNK_TIMEOUT` 前 `received=[...]` / `missing=[...]` 的模式
5. `BbsViewModel.openPost()` 的 UI 層 `READ` 自動重試，是否和 transport 層 timeout 交錯造成重複請求
