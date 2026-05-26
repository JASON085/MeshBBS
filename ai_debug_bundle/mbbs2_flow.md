# MBBS2 Flow

Generated from the current workspace on 2026-05-26.

## 1. Overall protocol layers

Current transport uses these message families:

| Family | Direction | Purpose |
|---|---|---|
| `BBS:REQ` | client -> server | plain request |
| `BBS:REQC` | client -> server | compressed multi-chunk request |
| `MBBS2|...` | server -> client | binary chunked compressed response |
| `BBS:META` | server -> client | batch metadata: total chunks + payload SHA-256 |
| `BBS:MISS` | client -> server | selective resend request |
| `BBS:ACK` | client -> server | batch completion ACK |
| `BBS:RES` | legacy/older response path | still supported on client |

## 2. Request path

### 2.1 Plain request

Client builds:

```text
BBS:REQ:<seq>:<CMD>:<args>
```

Flow:
1. `MeshtasticRepository.sendReq()` allocates `seq`.
2. If command is `LIST`, `POSTS`, or `READ`, client also creates a pending entry with a human-readable `stage`.
3. `sendRaw()` wraps the text with `MBBS1` prefix and sends a Meshtastic `DataPacket` with:
   - `dataType = 257`
   - `hopLimit = 4`
   - `wantAck = false`

### 2.2 Compressed request

Used for:
- `POST`
- `REPLY`
- `EDIT`
- `EDITREP`

Flow:
1. `sendCompressedReq()` zlib-compresses the full argument string.
2. Base64-encodes the compressed bytes.
3. Splits the encoded string into `REQUEST_CHUNK_CHARS = 180`.
4. Sends:

```text
BBS:REQC:<seq>:<CMD>:<index>:<total>:<base64Chunk>
```

5. Sleeps `REQUEST_CHUNK_PAUSE_MS = 320ms` between chunks.

## 3. Server request receive path

Entry point:
- `AndroidServerService.handleIncomingRequest(fromId, text)`

Dispatch order:
1. `BBS:ACK`
2. `BBS:MISS`
3. `BBS:REQC`
4. `BBS:REQ`
5. otherwise plain text help path (`?`)

### 3.1 `BBS:REQ`

1. Parse `seq`, `cmd`, `args`.
2. Build dedupe key:

```text
<fromId>:<seq>
```

3. If duplicate, drop.
4. Call Python bridge:

```text
ensureBridge().handleRequest(cmd, args, fromId, fromId)
```

5. If response text is non-empty, call:

```kotlin
repo?.sendResponse(fromId, seq, response, cmd)
```

### 3.2 `BBS:REQC`

Pending key:

```text
<fromId>:<seq>
```

Flow:
1. Parse `seq`, `cmd`, `index`, `total`, `chunk`.
2. Validate `index in 0 until total`.
3. Cache arbitrary index; no requirement that chunk 1 arrives first.
4. Log `REQC_RX ... pending=[...] missing=[...]`.
5. If not all chunks have arrived, return.
6. If any index still missing, log `REQC_WAIT reason=missing_chunks` and return.
7. Concatenate all chunks in index order.
8. Base64-decode and zlib-decompress to recover request args.
9. Call Python bridge once.
10. Response is sent through normal `sendResponse()`.

Timeout:
- `COMPRESSED_REQUEST_TIMEOUT_MS = 90000ms`
- stale entries are logged as `REQC_TIMEOUT`

## 4. Server response send path

Entry point:
- `MeshtasticServerRepository.sendResponse(destId, seq, responseJson, responseLabel)`

Flow:
1. Read transport profile and current runtime values from `ServerHostStore`.
2. Convert JSON string to bytes.
3. zlib-deflate the full JSON payload.
4. Compute:

```text
SHA-256(compressedPayload)
```

5. Split compressed bytes into chunks using current `responseChunkSize`.
6. For each chunk, build binary packet:

```text
MBBS2|<logicalDestId>|<seq>|<index>|<total>\n<binaryChunkBytes>
```

7. Cache all chunk packets under:

```text
<destId>:<seq>
```

8. Send `BBS:META:<seq>:<total>:<sha256>` once before chunks (`phase=head`).
9. Send every `MBBS2` chunk with current `responseChunkDelayMs`.
10. Send `BBS:META:<seq>:<total>:<sha256>` again after chunks (`phase=tail`).

## 5. Client response receive path

Entry points:
- `MeshtasticRepository.handlePacket()`
- `MeshtasticRepository.handleMbbs2()`
- `MeshtasticRepository.handleIncoming()`

### 5.1 Control text decoding

The client now strips the `MBBS1` prefix before dispatching text control messages.

This is important because:
- `BBS:META`
- `BBS:MISS`
- `BBS:ACK`
- any future control packets

all travel inside a `MBBS1`-prefixed payload.

### 5.2 `MBBS2` chunk receive

Flow:
1. Find the first newline.
2. Parse header:

```text
MBBS2|<destId>|<seq>|<index>|<total>
```

3. Validate:
   - header exists
   - `index in 0 until total`
   - if `myNodeId` is known, `destId` must match current node
   - `seq` must not already be in `completed`
4. Create or reuse client pending entry keyed by:

```text
<seq>
```

5. Store the raw binary chunk payload as base64 text in `entry.chunks[index]`.
6. Update `entry.total`, `entry.sourceNodeId`, `entry.proto = MBBS2`.
7. Log:

```text
CHUNK_RX proto=MBBS2 ...
```

8. Call `tryFinalizePending(seq, entry, fromNode, toNode)`.

### 5.3 `BBS:META`

Flow:
1. Parse:

```text
BBS:META:<seq>:<total>:<sha256>
```

2. Create or reuse the same pending entry keyed by `<seq>`.
3. Set:
   - `entry.total`
   - `entry.expectedSha256`
   - `entry.sourceNodeId`
4. Log `BATCH_META_RX`.
5. Call `tryFinalizePending(...)`.

### 5.4 Legacy `BBS:RES`

Still supported:

```text
BBS:RES:<destId>:<seq>:<index>:<total>:<data>
```

It uses the same pending map and finalization path, but stores base64 text chunks directly instead of binary `MBBS2` packet bodies.

## 6. Pending handling / completion rules

Pending entry stores:
- `chunks`
- `total`
- `cmd`
- `stage`
- `startedAtMs`
- `updatedAtMs`
- `sourceNodeId`
- `expectedSha256`
- `proto`
- `missRequestCount`
- `lastMissRequestAtMs`

Completion conditions in `tryFinalizePending(...)`:
1. `total > 0`
2. `entry.chunks.size >= total`
3. `missingChunkIndices(total, entry.chunks.keys)` is empty
4. `expectedSha256` is not blank
5. reconstructed compressed payload hash matches `expectedSha256`
6. zlib decompression + JSON parse succeeds

On success:
1. emit parsed `BbsEvent`
2. move `seq` into `completed`
3. remove `pending[seq]`
4. send `BBS:ACK:<seq>:<sha256>`
5. clear UI `LoadProgress`

## 7. Missing chunk handling

Current implementation is batch-level reliable transport, not per-chunk ACK.

### Client trigger rules

Client can send:

```text
BBS:MISS:<seq>:<missing_indexes>
```

when:
1. a response has started (`entry.chunks` not empty)
2. not all chunks are present
3. no chunk has arrived for `4000ms`
4. last miss request is older than `4000ms`
5. `missRequestCount < 3`

### Server selective resend

On `BBS:MISS`:
1. server looks up cache key `<destId>:<seq>`
2. validates requested indexes
3. sends `BBS:META` again (`phase=miss`)
4. resends only the missing cached `MBBS2` chunk packets
5. does **not** rerun Python `handleRequest`
6. does **not** resend the whole article

## 8. Hash mismatch handling

If all chunks arrived but `SHA-256` does not match:
1. client logs `CHUNK_HASH_FAIL`
2. clears `entry.chunks`
3. requests all indexes via `BBS:MISS`
4. waits for resend path instead of immediately failing the whole request

## 9. Timeout handling

### Client response timeout

If a response remains incomplete beyond the dynamic timeout:
1. log `CHUNK_TIMEOUT`
2. stop `LoadProgress`
3. emit `ServerError("<stage> 接收逾時，缺少 [...]")`
4. remove the pending entry

### Server compressed request timeout

If a `BBS:REQC` request remains incomplete beyond `90000ms`:
1. log `REQC_TIMEOUT`
2. remove the pending request entry

## 10. Important current weakness

This is not full reliable transport yet.

Current design still depends on the control plane arriving:
- `BBS:META` must arrive at least once for the client to finalize.
- `BBS:META` is not individually ACKed.
- There is no per-chunk ACK / congestion control / RTT adaptation.

So even with `BBS:MISS`, LoRa direct and MQTT can still fail if:
- metadata packets are lost repeatedly
- missing requests are themselves lost
- total response size still creates too many chunks for the RF path
