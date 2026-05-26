# Transport TODO

Generated from the current workspace on 2026-05-26.

This file is intended for another AI engineer to continue analyzing and extending the current LoRa / MQTT transport behavior without first rediscovering the entire codebase.

## 1. What the current transport still lacks

The project already has:
- `MBBS2` binary chunk transport
- `BBS:META` batch metadata
- `BBS:MISS` selective resend request
- `BBS:ACK` completion ACK
- full compressed payload `SHA-256` verification

But the transport still lacks these capabilities:

### 1.1 Metadata reliability is still weak

Current issue:
- client cannot finalize a response without `BBS:META`
- `BBS:META` is only sent as a best-effort control packet
- if metadata is lost repeatedly, chunk data alone cannot complete

Missing:
- either stronger metadata redundancy
- or the ability to infer / recover metadata without depending on a separate control packet

### 1.2 No per-chunk delivery confidence

Current issue:
- there is no per-chunk ACK
- server has no signal for “which chunks definitely arrived”
- `BBS:MISS` only helps after the client notices a gap

Missing:
- fine-grained delivery visibility
- adaptive pacing based on observed delivery quality

### 1.3 No RTT / congestion awareness

Current issue:
- pacing is fixed by profile
- no backoff when many chunks are lost
- no faster path when link quality is good

Missing:
- dynamic pacing adjustment
- per-link or per-session transport adaptation

### 1.4 No structured persistent transport log

Current issue:
- client/server logs are mostly UI-visible text streams
- useful for manual copy/paste, but not great for automated correlation

Missing:
- structured session capture with timestamps and `seq` grouping

### 1.5 No automatic LoRa/MQTT path detection

Current issue:
- operator must manually choose `LORA_DIRECT` vs `MQTT_SAFE`
- wrong profile can be used accidentally

Missing:
- path-aware profile selection
- or at least a session-level hint/log stating which path is believed active

## 2. ACK / MISS: what is already done, and what should happen next

## 2.1 Current `MISS` implementation

Already implemented:

Client:
- when partial chunks exist
- and no new chunk arrives for `4000ms`
- and `missRequestCount < 3`
- send:

```text
BBS:MISS:<seq>:<missing_indexes>
```

Server:
- cache key: `<destId>:<seq>`
- lookup cached response chunks
- resend only requested indexes
- do not rerun Python
- do not resend whole article

This is a good incremental base and should be preserved.

## 2.2 Current `ACK` implementation

Already implemented:

Client:
- after full response reassembly and hash verification
- send:

```text
BBS:ACK:<seq>:<sha256>
```

Server:
- if cache entry exists and sha matches
- remove cached response

This is useful for memory cleanup, but not yet a transport quality signal.

## 2.3 What ACK/MISS should do next

Recommended next steps, in order:

### Step 1: strengthen `BBS:META` survival

Most urgent gap:
- a complete response can still fail if metadata is lost

Safer options:
- send `BBS:META` more than twice for large responses
- send `BBS:META` again whenever `BBS:MISS` occurs
  - this part is already partially done via `phase=miss`
- consider periodic metadata repeat during long chunk bursts for large totals

### Step 2: make `MISS` smarter, not just timed

Current trigger:
- idle-based only

Better trigger candidates:
- also send `MISS` shortly before timeout if response is near complete
- prioritize missing low indexes / boundary indexes in logs
- include current received count in client log for each miss request

### Step 3: use `ACK` as a quality indicator

Right now:
- `ACK` only clears cache

Possible next use:
- server can record whether a response completed cleanly without any `MISS`
- compare:
  - completed without miss
  - completed after miss
  - timed out without ack

This gives much better transport diagnostics without changing protocol basics.

### Step 4: consider metadata-only recovery path

Future idea:
- if client has all chunks but no `BBS:META`, send a request like:

```text
BBS:MISS:<seq>:META
```

or equivalent minimal control request

This is not in current protocol, but it is the cleanest incremental improvement if `META` loss becomes the dominant failure mode.

## 3. Best classes / files to extend

## 3.1 `MeshtasticRepository.kt`

Best for:
- client chunk assembly
- timeout rules
- missing-chunk request policy
- metadata recovery logic
- hash verification behavior
- client-side transport logging

Why:
- all client pending response state already lives here
- `BBS:META`, `BBS:RES`, `MBBS2`, `BBS:MISS`, `BBS:ACK` already converge here

Best extension points:
- `handleMbbs2(...)`
- `handleBatchMeta(...)`
- `pruneStalePendingChunks(...)`
- `requestMissingChunksIfNeeded(...)`
- `tryFinalizePending(...)`
- `sendMissingChunkRequest(...)`
- `pendingChunkTimeoutMs(...)`

## 3.2 `MeshtasticServerRepository.kt`

Best for:
- response chunking
- pacing
- resend cache policy
- response metadata redundancy
- transport profile behavior

Why:
- all server response packetization is centralized here

Best extension points:
- `sendResponse(...)`
- `resendResponseChunks(...)`
- `acknowledgeResponse(...)`
- `sendBatchMeta(...)`
- `sendPrivate(...)`
- `currentResponseTransportSettings()`

## 3.3 `AndroidServerService.kt`

Best for:
- control-request dispatch
- request-side compressed chunk assembly
- request dedupe
- server-side logging of transport events

Why:
- it already parses `BBS:ACK`, `BBS:MISS`, `BBS:REQC`, `BBS:REQ`

Best extension points:
- `handleIncomingRequest(...)`
- `handleMissingChunkRequest(...)`
- `handleBatchAck(...)`
- `handleCompressedRequest(...)`
- `pruneStaleCompressedRequests(...)`

## 3.4 `ServerModels.kt` / `ServerHostStore.kt`

Best for:
- profile definitions
- runtime transport tuning knobs

Why:
- already owns:
  - `TransportProfile`
  - `hopLimit`
  - `responseChunkSize`
  - `responseChunkDelayMs`

Best extension points:
- `TransportProfile`
- `ServerHostState`
- `setTransportProfile(...)`
- `setResponseChunkSize(...)`
- `setResponseChunkDelayMs(...)`

## 3.5 `BbsViewModel.kt`

Best for:
- UI-level retry policy only

Not the best place for transport logic.

Why:
- transport bugs should not be masked by blind UI retries
- current code has already moved away from whole-request retry flooding

Use only for:
- showing better progress/error states
- preventing retry storms

## 4. Where LoRa flooding is most likely created

## 4.1 Large `READ` response with too many chunks

Most obvious flooding source:
- large article response
- compressed size still large
- many `MBBS2` chunks

Risk:
- airtime explosion
- one lost chunk can trigger resend traffic
- metadata + data + miss + ack all add control overhead

## 4.2 Response pacing that is too aggressive for current RF quality

Current default:
- `LORA_DIRECT = 200 bytes / 250ms`

This may still be too aggressive on weak links or multi-hop paths.

Risk:
- late chunks or tail chunks get dropped
- miss/retry adds even more traffic

## 4.3 `BBS:MISS` bursts on already weak sessions

Current issue:
- after idle 4s, client may request all missing indexes at once

Risk:
- if the link is already congested, a large miss request can cause another burst of server resend traffic

## 4.4 `BBS:META` head/tail overhead on every response

Current behavior:
- send metadata before chunk burst
- send metadata after chunk burst
- send metadata again on resend

This is usually acceptable, but on very small responses it is extra overhead.

Risk:
- for small responses, control-packet ratio can become high

## 4.5 Whole request retries from UI layer

Current status:
- `READ` whole retry is not actively scheduled
- `POSTS` retry scheduler is currently no-op

This was a historical flooding source and should stay disabled unless transport evidence proves it is needed.

## 4.6 Help message multi-packet text path

`?` help still sends multiple text packets with large pauses.

Risk:
- not the main issue for `READ`, but still extra traffic when users test repeatedly through Meshtastic app

## 5. Which current timeouts look questionable

## 5.1 Client response timeout may still be too long for obvious failure

Current formula:

```text
60000 + totalChunks * 8000, capped at 240000
```

Potential issue:
- on obviously dead sessions, the client may wait too long before surfacing failure
- especially if only metadata is missing

Possible refinement:
- separate timeout categories:
  - waiting for first chunk
  - waiting between chunks
  - waiting for metadata after all chunks

## 5.2 `MISSING_CHUNK_REQUEST_IDLE_MS = 4000ms` may be too slow for short responses

Potential issue:
- if total chunks are only 2 to 4, waiting 4 seconds before asking for the missing one may feel unnecessarily slow

Possible refinement:
- make miss-request idle time depend on `totalChunks`
- smaller totals -> earlier miss request

## 5.3 `MAX_MISSING_CHUNK_REQUESTS = 3` may be low for long weak links

Potential issue:
- on unstable LoRa paths, 3 miss cycles might not be enough

But:
- blindly increasing it can create flooding

Better approach:
- keep 3 for now
- first gather real logs showing whether sessions fail because miss requests are exhausted

## 5.4 Server compressed request timeout `90000ms` may be too generous or too strict depending on command size

Current issue:
- one fixed timeout is used for all compressed requests

Potential refinement:
- dynamic timeout based on `total`
- or at least log per-request elapsed time to see whether current threshold is realistic

## 5.5 Response cache timeout `7 min` is safe, but may over-retain stale sessions

Current value:
- `420000ms`

This is not obviously wrong, but:
- if many clients fail and never ACK
- cache can retain a lot of large responses longer than needed

Possible refinement:
- keep 7 min for now
- add log/metrics about cache size and eviction frequency

## 6. Suggested next analysis order

1. Determine whether real LoRa direct `READ` failures are mostly:
   - missing data chunk
   - missing metadata
   - hash mismatch after all chunks
   - resend not arriving

2. If metadata loss is common:
   - improve metadata redundancy before touching chunk size again

3. If data chunk loss is common:
   - compare `LORA_DIRECT 200/250` against a slightly safer but still practical profile
   - avoid jumping straight back to ultra-small chunks

4. If miss requests are common but successful:
   - keep current model
   - improve logs and maybe shorten miss trigger for small totals

5. If miss requests are common and unsuccessful:
   - inspect whether `BBS:MISS` or re-sent `BBS:META` is being lost

## 7. Short version for another AI

If another AI only reads one section, it should know this:

- The current code already has selective resend and batch completion ACK.
- The likely remaining weak point is control-plane reliability, especially `BBS:META`.
- Best files to extend are `MeshtasticRepository.kt` and `MeshtasticServerRepository.kt`.
- Do not reintroduce UI-level whole-request retries until transport logs prove they are necessary.
