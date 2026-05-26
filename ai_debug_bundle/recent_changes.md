# Recent Changes

Generated from current code plus project maintenance notes in `維護記錄/`.

## 1. 2026-05-23: hopLimit 4 + safer request pacing

Files:
- `MeshtasticRepository.kt`
- `MeshtasticServerRepository.kt`
- `AndroidServerService.kt`

Changes:
- Client and server `hopLimit` moved to `4`.
- Client compressed request pacing was slowed from earlier faster settings to:
  - `REQUEST_CHUNK_CHARS = 180`
  - `REQUEST_CHUNK_PAUSE_MS = 320ms`
- Added stale request/response chunk cleanup.

Why:
- MQTT relay users were dropping boundary chunks.

## 2. 2026-05-25: full chunk debug instrumentation + MQTT-safe pacing

Files:
- `MeshtasticRepository.kt`
- `MeshtasticServerRepository.kt`
- `AndroidServerService.kt`
- `ServerHostStore.kt`
- `ServerModels.kt`
- `ServerHostScreen.kt`

Changes:
- Added verbose chunk logging:
  - `CHUNK_RX`
  - `CHUNK_WAIT`
  - `CHUNK_TIMEOUT`
  - `CHUNK_DROP`
  - `TX_PLAN`
  - `TX_CHUNK`
  - `MISS_*`
- Response chunk size was temporarily reduced aggressively for MQTT.
- Response delay was raised.
- Out-of-order response chunk caching was explicitly supported.
- Added debug option to force broadcast response instead of directed/private response.

Why:
- To diagnose MQTT relay loss on private/direct packet responses.

## 3. 2026-05-25: TransportProfile split

Files:
- `ServerModels.kt`
- `ServerHostStore.kt`
- `MeshtasticServerRepository.kt`
- `ServerHostScreen.kt`

Changes:
- Introduced runtime transport profiles:
  - `LORA_DIRECT`
  - `MQTT_SAFE`
  - `DEBUG_ULTRA_SAFE`
- Added runtime-adjustable settings:
  - `transportProfile`
  - `responseChunkSize`
  - `responseChunkDelayMs`

Why:
- The MQTT-safe settings were too conservative for LoRa direct and made `LIST / POSTS / READ` split into too many packets.

## 4. 2026-05-26: LoRa direct re-optimization

Files:
- `ServerModels.kt`
- `MeshtasticServerRepository.kt`
- `MeshtasticRepository.kt`

Changes:
- Restored LoRa direct defaults to:
  - `chunkSize = 200`
  - `delayMs = 250ms`
- Kept MQTT safe separately at:
  - `chunkSize = 170`
  - `delayMs = 600ms`
- Added response summary log in `sendResponse()`.
- Added dynamic client timeout formula:

```text
60000 + totalChunks * 8000, capped at 240000
```

Why:
- MQTT tuning had made LoRa direct reads too slow and fragile.

## 5. 2026-05-26: selective resend with `BBS:MISS`

Files:
- `MeshtasticRepository.kt`
- `MeshtasticServerRepository.kt`
- `AndroidServerService.kt`
- `BbsViewModel.kt`

Changes:
- Client now requests only missing response chunks:

```text
BBS:MISS:<seq>:<missing_indexes>
```

- Server caches recent response chunks under:

```text
<destId>:<seq>
```

- On miss request, server resends only requested indexes.
- READ whole-response auto-retry was disabled.
- `schedulePostListRetry()` is currently a no-op, reducing UI-level flood.

Why:
- To avoid resending an entire article when only a few chunks were lost.

## 6. 2026-05-26: batch-level reliable transport on top of MBBS2

Files:
- `MeshtasticRepository.kt`
- `MeshtasticServerRepository.kt`
- `AndroidServerService.kt`

Changes:
- Added:

```text
BBS:META:<seq>:<total>:<sha256>
BBS:ACK:<seq>:<sha256>
```

- Client verifies `SHA-256` of the full compressed payload before final parse.
- Server clears response cache early when ACK arrives.
- Metadata is sent before and after the response chunk burst.

Why:
- To add batch-level integrity and cleanup without changing the underlying MBBS2 chunk format.

## 7. 2026-05-26: login failure fix after reliable transport

Files:
- `MeshtasticRepository.kt`

Change:
- Client now strips the `MBBS1` prefix before dispatching control text messages such as `BBS:META`.

Why:
- After `BBS:META` became required for finalization, login could stall if the client failed to parse metadata packets at all.

## 8. Where chunk size / delay / timeout / retry / pending changed

### Chunk size

- `ServerModels.kt`
  - `TransportProfile.LORA_DIRECT(200, 250L)`
  - `TransportProfile.MQTT_SAFE(170, 600L)`
  - `TransportProfile.DEBUG_ULTRA_SAFE(120, 900L)`
- `MeshtasticRepository.kt`
  - request-side `REQUEST_CHUNK_CHARS = 180`

### Delay

- `ServerModels.kt`
  - response delay per profile
- `MeshtasticServerRepository.kt`
  - response pacing uses `ServerHostStore.currentResponseChunkDelayMs()`
  - `BATCH_META_PAUSE_MS = 40L`
- `MeshtasticRepository.kt`
  - request-side `REQUEST_CHUNK_PAUSE_MS = 320L`

### Timeout

- `MeshtasticRepository.kt`
  - `BASE_PENDING_CHUNK_TIMEOUT_MS = 60000L`
  - `PER_CHUNK_TIMEOUT_MS = 8000L`
  - `MAX_PENDING_CHUNK_TIMEOUT_MS = 240000L`
- `AndroidServerService.kt`
  - `COMPRESSED_REQUEST_TIMEOUT_MS = 90000L`
- `MeshtasticServerRepository.kt`
  - `RESPONSE_CHUNK_CACHE_TIMEOUT_MS = 7 * 60_000L`

### Retry / resend

- `MeshtasticRepository.kt`
  - `MISSING_CHUNK_REQUEST_IDLE_MS = 4000L`
  - `MISSING_CHUNK_REQUEST_INTERVAL_MS = 4000L`
  - `MAX_MISSING_CHUNK_REQUESTS = 3`
- `AndroidServerService.kt`
  - selective resend request handling
- `MeshtasticServerRepository.kt`
  - resend from cached chunk packets only

### Pending handling

- `MeshtasticRepository.kt`
  - unified pending map for `MBBS2`, `BBS:RES`, `BBS:META`
  - out-of-order assembly
  - timeout cleanup
  - hash verification before parse
- `AndroidServerService.kt`
  - server-side pending map for compressed `BBS:REQC`
