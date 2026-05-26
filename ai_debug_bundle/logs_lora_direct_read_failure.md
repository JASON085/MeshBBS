# Logs - LoRa Direct READ Failure

Generated on 2026-05-26.

## 1. Current honesty status

There is **no complete preserved runtime session log in this repo** for a real LoRa direct failure covering:

1. `LOGIN`
2. `LIST`
3. `POSTS`
4. `READ` failure

So this file does **not** invent timestamps, `seq`, chunk counts, or missing indexes.

Instead, this file records:
- what log lines the current code will emit
- where those logs come from
- which runtime data is still missing and must be captured on the next reproduction

## 2. Runtime log sources that currently exist

### Client side

Source:
- `MeshtasticRepository.comm(...)` -> `BbsEvent.BssCommLog`
- surfaced in app UI via `bssLog`

Expected location:
- MeshBBS client app
- the new `Client Log` button on the board list page

### Server side

Source:
- `ServerHostStore.appendLog(...)`

Expected location:
- Android Server host UI / log area

### External

Optional but useful:
- Android Studio `logcat`
- Meshtastic app packet view / packet relay observation

## 3. Log lines currently emitted by code

### 3.1 Server response planning

From `MeshtasticServerRepository.sendResponse()`:

```text
TX_PLAN command=<LIST|POSTS|READ|...> type=<json type> seq=<seq> rawBytes=<n> compressedBytes=<n> chunkSize=<n> totalChunks=<n> delayMs=<n> selectedProfile=<profile> targetNode=<node> sha256=<sha12...> wireDest=<node|^all> directed=<true|false> privateLike=<true|false>
```

### 3.2 Server chunk send

From `MeshtasticServerRepository.sendResponse()`:

```text
TX_CHUNK key=<seq:x/total> seq=<seq> idx=<x/total> chunkBytes=<n> packetBytes=<n> delayAfterMs=<n> target=<node|^all> logicalDest=<node> profile=<profile> directed=<true|false> privateLike=<true|false>
```

### 3.3 Server metadata send

From `MeshtasticServerRepository.sendBatchMeta()`:

```text
BATCH_META_TX phase=<head|tail|miss> seq=<seq> total=<n> sha256=<sha12...> target=<wireTarget> logicalDest=<destId>
```

### 3.4 Server selective resend

From `AndroidServerService` + `MeshtasticServerRepository`:

```text
MISS_REQ from=<node> seq=<seq> missing=<comma-separated zero-based indexes>
MISS_HIT pendingKey=<destId:seq> cmd=<cmd> missing=<...> resendChunks=<count/total> cacheTimeoutMs=420000
MISS_DONE pendingKey=<destId:seq> resent=<...>
MISS_DROP reason=<cache_miss|invalid_indexes> ...
```

### 3.5 Server batch ACK completion

From `AndroidServerService` + `MeshtasticServerRepository`:

```text
ACK_REQ from=<node> seq=<seq> sha256=<sha12...>
ACK_OK pendingKey=<destId:seq> cmd=<cmd> totalChunks=<n> sha256=<sha12...>
ACK_DROP reason=<cache_miss|sha_mismatch|invalid_parts|invalid_payload> ...
```

### 3.6 Client response receive

From `MeshtasticRepository.handleMbbs2()` / `handleBbsRes()`:

```text
CHUNK_RX proto=MBBS2 from=<fromNode> to=<toNode> seq=<seq> idx=<x/total> payloadSize=<n> pendingKey=<seq> pending=[...] missing=[...] timeoutMs=<n>
CHUNK_RX proto=BBS_RES from=<fromNode> to=<toNode> seq=<seq> idx=<x/total> payloadSize=<n> pendingKey=<seq> pending=[...] missing=[...] timeoutMs=<n>
```

### 3.7 Client metadata receive

From `MeshtasticRepository.handleBatchMeta()`:

```text
BATCH_META_RX from=<fromNode> to=<toNode> seq=<seq> expectedTotal=<n> sha256=<sha12...>
```

### 3.8 Client missing chunk request

From `MeshtasticRepository.sendMissingChunkRequest()`:

```text
SEND BBS:MISS seq=<seq> cmd=<cmd> to=<targetNode> missing=<zero-based indexes> retry=<1..3>/3 reason=<missing|hash_mismatch>
```

### 3.9 Client completion / timeout / hash failure

From `MeshtasticRepository.tryFinalizePending()` / `pruneStalePendingChunks()`:

```text
CHUNK_WAIT proto=<MBBS2|BBS_RES> pendingKey=<seq> pending=[...] missing=[...] reason=<assemble_blocked|await_meta>
CHUNK_HASH_FAIL proto=<MBBS2|BBS_RES> from=<fromNode> to=<toNode> seq=<seq> expected=<sha12...> actual=<sha12...>
CHUNK_DONE proto=<MBBS2|BBS_RES> pendingKey=<seq> total=<n> sha256=<sha12...> from=<fromNode> to=<toNode>
CHUNK_TIMEOUT seq=<seq> cmd=<cmd> expectedTotal=<n> received=[...] missing=[...] elapsedMs=<n> timeoutMs=<n>
SEND BBS:ACK seq=<seq> to=<targetNode> sha256=<sha12...>
```

## 4. READ retry status

Current code state:

- There is **no active automatic whole-READ retry** in `BbsViewModel.openPost()`.
- `openPost(postId)` issues a single `repo?.getPost(postId)`.
- `postReadRetryJob` exists as a field but there is no active scheduling function that reissues `READ`.
- `schedulePostListRetry()` is currently a no-op, so `POSTS` is not auto-reflooded either.

Implication:
- If another AI is searching for "READ retry flooding" in the current code, it should treat that as **historical behavior**, not the current implementation.

## 5. Missing runtime evidence right now

The following real LoRa direct failure data is still missing from the repository:

| Missing item | Why it matters |
|---|---|
| Actual `TX_PLAN` for failed `READ` | confirms raw/compressed size and total chunk count |
| Actual `TX_CHUNK` sequence for failed `READ` | shows which chunk numbers were really sent |
| Actual `CHUNK_RX` series for the same `seq` | shows whether client missed specific chunks or metadata |
| Real `CHUNK_TIMEOUT` record for the failed `READ` | shows missing indexes and elapsed time |
| Real `BATCH_META_RX` presence/absence | proves whether metadata loss is part of the failure |
| Real `SEND BBS:MISS` / `MISS_HIT` / `MISS_DONE` chain | proves whether selective resend helped |
| Packet timestamps | currently logs do not include explicit send/receive timestamps inside each line |

## 6. Recommended capture set for the next failed LoRa direct READ

For the next reproduction, capture all of these together:

1. Client `Client Log` copy from app start through failed `READ`
2. Android Server UI log covering the same session
3. Meshtastic app packet display if available
4. Device absolute time when `READ` is tapped
5. Current server `transportProfile`, `responseChunkSize`, `responseChunkDelayMs`, `hopLimit`

## 7. Minimal target sequence to reproduce

1. Start Android server with MQTT disabled.
2. Confirm profile is `LORA_DIRECT`.
3. Login from MeshBBS client.
4. Enter board.
5. Load post list.
6. Open a post that usually fails.
7. Immediately export:
   - server log
   - client log

## 8. What another AI should focus on first

When the next real runtime log exists, the first checks should be:

1. Did `READ` produce a `TX_PLAN` with unexpectedly high `totalChunks`?
2. Did the client receive `BATCH_META_RX` for the same `seq`?
3. Did `CHUNK_RX` stop before the last chunk or before metadata?
4. Did the client emit `SEND BBS:MISS`?
5. Did the server emit `MISS_HIT` / `MISS_DONE`?
6. Did the client still end in `CHUNK_TIMEOUT` after selective resend?
