# Logs - LoRa Direct READ Failure

生成時間：2026-05-26  
用途：給另一位 AI 工程助手分析 LoRa direct 下 `LOGIN / LIST / POSTS / READ` 的實際傳輸症狀。

## 1. 擷取狀態

目前工作區 **沒有找到一份完整的實機 LoRa direct runtime log**，可直接覆蓋以下四段流程：

1. `LOGIN` 成功
2. `LIST`
3. `POSTS`
4. `READ` 失敗或卡住

也就是說，這份檔案目前提供的是：

- 已知症狀摘要
- 目前程式會打出的 log 格式
- 下一次 LoRa direct 測試時應收集的欄位
- 缺失的觀測點

**沒有捏造任何實機 runtime log。**

## 2. 已知測試前提

以下前提來自目前程式與使用者描述：

| 項目 | 目前狀態 |
|---|---|
| MQTT | 關閉（使用者描述） |
| 傳輸模式 | LoRa direct |
| Android server response 預設 profile | `LORA_DIRECT` |
| `LORA_DIRECT` | `chunkSize=200`, `chunkDelayMs=250ms` |
| server `hopLimit` 預設 | `4` |
| client `PACKET_HOP_LIMIT` | `4` |
| client response timeout | `60000 + totalChunks * 8000`, capped at `240000` |

## 3. 目前可期待的 server log 格式

### 3.1 response summary

程式會輸出：

```text
TX_PLAN command=<LIST|POSTS|READ|...> type=<json type> seq=<seq> rawBytes=<n> compressedBytes=<n> chunkSize=<n> totalChunks=<n> delayMs=<n> selectedProfile=<profile> targetNode=<node> wireDest=<node|^all> directed=<true|false> privateLike=<true|false>
```

### 3.2 per chunk send

程式會輸出：

```text
TX_CHUNK key=<seq:x/total> seq=<seq> idx=<x/total> chunkBytes=<n> packetBytes=<n> delayAfterMs=<n> target=<node|^all> logicalDest=<node> profile=<profile> directed=<true|false> privateLike=<true|false>
```

### 3.3 request performance

程式會輸出：

```text
PERF <CMD> seq=<seq> py=<python-handle-ms>ms queue=<send-queue-ms>ms bytes=<response.length>
```

## 4. 目前可期待的 client log 格式

### 4.1 request send

```text
SEND BBS:REQ seq=<seq> cmd=<LOGIN|LIST|POSTS|READ> to=<serverNodeId> args=<preview>
```

### 4.2 response receive

```text
CHUNK_RX proto=MBBS2 from=<fromNode> to=<toNode> seq=<seq> idx=<x/total> payloadSize=<n> pendingKey=<seq> pending=[...] missing=[...] timeoutMs=<n>
```

或舊格式：

```text
CHUNK_RX proto=BBS_RES from=<fromNode> to=<toNode> seq=<seq> idx=<x/total> payloadSize=<n> pendingKey=<seq> pending=[...] missing=[...] timeoutMs=<n>
```

### 4.3 waiting / completion / timeout

```text
CHUNK_WAIT proto=MBBS2 pendingKey=<seq> pending=[...] missing=[...] reason=assemble_blocked
CHUNK_DONE proto=MBBS2 pendingKey=<seq> total=<n> from=<fromNode> to=<toNode>
CHUNK_TIMEOUT seq=<seq> cmd=<cmd> expectedTotal=<n> received=[...] missing=[...] elapsedMs=<n> timeoutMs=<n>
```

## 5. 目前缺少的觀測點

以下欄位是這次 AI 診斷特別想要，但 **目前 log message 內文本身並沒有帶出**：

1. 每個 chunk send timestamp（毫秒級）
2. 每個 chunk receive timestamp（毫秒級）

現況：

- 若用 Android Studio `logcat` 或系統 log viewer，**時間戳記要靠 logcat 前綴** 取得。
- `ServerHostStore.appendLog(...)` 與 `BbsEvent.BssCommLog(...)` 的訊息字串本身沒有把 `SystemClock.elapsedRealtime()` 寫入 log 文字。

這是一個真實觀測缺口，應告知另一位 AI 助手。

## 6. 本次可提供的 LoRa direct 故障摘要

### 6.1 使用者描述

- `LOGIN` 通常正常
- 進入討論板 / 文章列表有時可進
- `READ` 文章常常失敗或卡住
- 之前為修 MQTT 曾把 chunk size / delay 調得過度保守，導致 LoRa direct 也變卡

### 6.2 現行程式推定需觀察的 READ failure pattern

下次實測優先觀察：

1. `TX_PLAN command=READ ... totalChunks=<n>`
2. `TX_CHUNK ... idx=1/n` 到 `idx=n/n` 是否都有出現
3. client 是否只收到中間 chunk，而缺首段或尾段
4. `CHUNK_TIMEOUT seq=<READ seq> cmd=READ expectedTotal=<n> received=[...] missing=[...] elapsedMs=<n>`
5. `BbsViewModel.openPost()` 的自動重試是否讓同一文章產生第二、第三個新 `seq`

## 7. 建議的下次 LoRa direct 測試填寫表

### 7.1 session metadata

| 欄位 | 值 |
|---|---|
| 日期 | 待填 |
| 測試裝置 | 待填 |
| Meshtastic Android App version | 待填 |
| Node firmware version | 待填 |
| LoRa region | 待填 |
| LoRa preset | 待填 |
| MQTT | 關閉 |
| transportProfile | `LORA_DIRECT` |
| chunkSize | `200` |
| chunkDelayMs | `250` |
| hopLimit | 待填（程式預設 4） |

### 7.2 response summary

| Command | Seq | Raw size | Compressed size | Chunk size | Total chunks | DelayMs | Target node | 結果 |
|---|---|---:|---:|---:|---:|---:|---|---|
| LOGIN | 待填 | 待填 | 待填 | 待填 | 待填 | 待填 | 待填 | 成功 |
| LIST | 待填 | 待填 | 待填 | 待填 | 待填 | 待填 | 待填 | 待填 |
| POSTS | 待填 | 待填 | 待填 | 待填 | 待填 | 待填 | 待填 | 待填 |
| READ | 待填 | 待填 | 待填 | 待填 | 待填 | 待填 | 待填 | 失敗/卡住 |

### 7.3 per chunk trace

| Command | Seq | Chunk | Server send timestamp | Client receive timestamp | Received chunks list after RX | Missing chunks after RX |
|---|---|---|---|---|---|---|
| LOGIN | 待填 | 待填 | 待填 | 待填 | 待填 | 待填 |
| LIST | 待填 | 待填 | 待填 | 待填 | 待填 | 待填 |
| POSTS | 待填 | 待填 | 待填 | 待填 | 待填 | 待填 |
| READ | 待填 | 待填 | 待填 | 待填 | 待填 | 待填 |

### 7.4 timeout record

| Command | Seq | Expected total chunks | Received chunks | Missing chunks | Timeout elapsed | timeoutMs formula result |
|---|---|---:|---|---|---:|---:|
| READ | 待填 | 待填 | 待填 | 待填 | 待填 | 待填 |

## 8. 建議擷取來源

為了補滿這份檔案，建議下一次 LoRa direct 實測同時收集：

1. Android server 的 `ServerHostStore.logs`
2. client 端 `BbsEvent.BssCommLog`
3. Android Studio `logcat` 時間戳前綴
4. Meshtastic App 畫面中對應 directed/private packet 的接收證據
