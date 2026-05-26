# Current Transport Config

Generated from the current workspace on 2026-05-26.

## 1. Client request transport

Source:
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/data/MeshtasticRepository.kt`

### Fixed request-side constants

| Item | Current value | Where |
|---|---:|---|
| `BBS_APP` | `257` | `MeshtasticRepository.kt` companion object |
| `BBS_PRIVATE_PREFIX` | `MBBS1` | `MeshtasticRepository.kt` companion object |
| `BBS_BINARY_PREFIX` | `MBBS2|` | `MeshtasticRepository.kt` companion object |
| `REQUEST_CHUNK_CHARS` | `180` | `MeshtasticRepository.kt` companion object |
| `REQUEST_CHUNK_PAUSE_MS` | `320ms` | `MeshtasticRepository.kt` companion object |
| `PACKET_HOP_LIMIT` | `4` | `MeshtasticRepository.kt` companion object |

### Request command paths

| Path | Used for | Wire format |
|---|---|---|
| `sendReq()` | `LOGIN`, `LIST`, `POSTS`, `READ`, `PUSH`, `SEARCH`, `DEL`, `DELREP`, `LOGOUT`, `HEARTBEAT`, `CHPASS` | `BBS:REQ:<seq>:<cmd>:<args>` |
| `sendCompressedReq()` | `POST`, `REPLY`, `EDIT`, `EDITREP` | `BBS:REQC:<seq>:<cmd>:<index>:<total>:<base64-zlib-chunk>` |

## 2. Client response assembly / timeout / retry

Source:
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/data/MeshtasticRepository.kt`

### Pending chunk / recovery parameters

| Item | Current value | Notes |
|---|---:|---|
| `BASE_PENDING_CHUNK_TIMEOUT_MS` | `60000ms` | Base timeout for response assembly |
| `PER_CHUNK_TIMEOUT_MS` | `8000ms` | Added per expected chunk |
| `MAX_PENDING_CHUNK_TIMEOUT_MS` | `240000ms` | Hard cap |
| `PENDING_CHUNK_CLEANUP_INTERVAL_MS` | `5000ms` | Cleanup loop cadence |
| `MISSING_CHUNK_REQUEST_IDLE_MS` | `4000ms` | If no new chunk for 4s, client can request missing chunks |
| `MISSING_CHUNK_REQUEST_INTERVAL_MS` | `4000ms` | Minimum gap between repeated `BBS:MISS` |
| `MAX_MISSING_CHUNK_REQUESTS` | `3` | Max selective resend requests per pending response |

### Timeout formula

Client response timeout is dynamic:

```text
timeoutMs = BASE_PENDING_CHUNK_TIMEOUT_MS + totalChunks * PER_CHUNK_TIMEOUT_MS
timeoutMs = min(timeoutMs, MAX_PENDING_CHUNK_TIMEOUT_MS)
```

Current concrete values:

| totalChunks | timeoutMs |
|---:|---:|
| 1 | `68000ms` |
| 2 | `76000ms` |
| 4 | `92000ms` |
| 8 | `124000ms` |
| 16 | `188000ms` |
| 24+ | capped at `240000ms` |

### Current client recovery behavior

| Behavior | Current state |
|---|---|
| Out-of-order chunk arrival | Supported |
| Need chunk `1/N` first before caching | No |
| Missing chunk request | Yes, `BBS:MISS:<seq>:<missing_indexes>` |
| Hash verification | Yes, full compressed payload `SHA-256` |
| Completion ACK | Yes, `BBS:ACK:<seq>:<sha256>` |
| Per-chunk ACK | No |
| Whole READ auto-retry from `BbsViewModel.openPost()` | Disabled |
| Whole POSTS auto-retry flood | Disabled (`schedulePostListRetry()` is currently a no-op) |

## 3. Server response transport

Sources:
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/ServerModels.kt`
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/ServerHostStore.kt`
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/MeshtasticServerRepository.kt`

### Transport profiles

| Profile | `responseChunkSize` | `responseChunkDelayMs` | Intended use |
|---|---:|---:|---|
| `LORA_DIRECT` | `200` | `250ms` | Default LoRa direct |
| `MQTT_SAFE` | `170` | `600ms` | MQTT relay / more conservative |
| `DEBUG_ULTRA_SAFE` | `120` | `900ms` | Test only |

### Current default server state

| Item | Current value |
|---|---|
| `hopLimit` | `4` |
| `transportProfile` | `LORA_DIRECT` |
| `responseChunkSize` | `200` |
| `responseChunkDelayMs` | `250ms` |
| `broadcastResponsesForDebug` | `false` |

### Server-side cache / metadata

| Item | Current value | Where |
|---|---:|---|
| `RESPONSE_CHUNK_CACHE_TIMEOUT_MS` | `420000ms` (7 min) | `MeshtasticServerRepository.kt` |
| `RESPONSE_CHUNK_CACHE_CLEANUP_INTERVAL_MS` | `60000ms` | `MeshtasticServerRepository.kt` |
| `BATCH_META_PAUSE_MS` | `40ms` | `MeshtasticServerRepository.kt` |

### Server response cache key

```text
<destId>:<seq>
```

Used for:
- selective resend lookup on `BBS:MISS`
- cleanup after `BBS:ACK`

## 4. Server compressed request handling

Source:
- `android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/AndroidServerService.kt`

| Item | Current value |
|---|---:|
| `COMPRESSED_REQUEST_TIMEOUT_MS` | `90000ms` |
| Compressed request pending key | `<fromId>:<seq>` |
| Help packet limit | `MAX_HELP_PACKET_BYTES = 180` |

## 5. HopLimit

### Client

Fixed in code:

```text
PACKET_HOP_LIMIT = 4
```

Used in:
- `sendRaw()`
- `sendPlainText()`
- `sendMeshChat()`

### Server

Runtime configurable through `ServerHostStore` / UI:

```text
current range: 1..7
default: 4
```

Used in:
- `MeshtasticServerRepository.sendPrivate()`
- `MeshtasticServerRepository.sendPlainTexts()`

## 6. MQTT vs LoRa direct difference in current code

### What is actually different today

| Area | LoRa direct | MQTT safe |
|---|---|---|
| Response chunk size | `200` | `170` |
| Response chunk delay | `250ms` | `600ms` |
| Default profile | Yes | No |
| Automatic transport detection | No | No |
| Manual selection via server UI/store | Yes | Yes |

### Important limitation

The current code does **not** auto-detect MQTT vs LoRa direct.

Actual profile selection comes from:
- `ServerHostStore.currentTransportProfile()`
- `ServerHostStore.currentResponseChunkSize()`
- `ServerHostStore.currentResponseChunkDelayMs()`

So the server will only use MQTT-safe pacing if the operator explicitly switches the profile or overrides the values.

## 7. Build / version identifiers relevant to debugging

| Item | Current value |
|---|---|
| `MeshtasticRepository.BUILD` | `b0604s` |
| `app versionName` | `b0604s` |
| Product flavors | `client`, `server` |
| Build types | `debug`, `release` |
