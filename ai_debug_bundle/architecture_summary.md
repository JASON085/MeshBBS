# Architecture Summary

## 1. High-level client/server structure

This project builds two Android apps from one codebase:

- `client` flavor: `MeshBBS`
- `server` flavor: `MeshServer`

The transport path is not raw Bluetooth or direct serial access. Both sides talk through the installed Meshtastic Android app via Android IPC / `IMeshService`.

## 2. Meshtastic IPC path

### Client

`MeshtasticRepository`:
- binds to `com.geeksville.mesh.Service`
- subscribes the receiver `com.meshtastic.bbs.data.MeshPacketReceiver`
- sends `DataPacket` objects through `IMeshService.send(...)`
- receives packets through Android broadcast + receiver callback

### Server

`MeshtasticServerRepository`:
- also binds to the same Meshtastic service
- also subscribes the same receiver class
- forwards decoded request text to `AndroidServerService`
- sends response packets back through `IMeshService.send(...)`

## 3. Python bridge

`AndroidServerService` hosts the logical BBS server.

Its business logic is not implemented directly in Kotlin. Instead:

- `AndroidServerService` owns a `PythonServerBridge`
- incoming commands are passed to:

```text
ensureBridge().handleRequest(cmd, args, fromId, fromId)
```

- the Python side returns a JSON string response
- Kotlin transport code then compresses and chunks that JSON response

So the split is:

| Layer | Responsibility |
|---|---|
| Python bridge | BBS business logic, database-backed request handling |
| Kotlin server repository | packetization, chunking, pacing, resend cache |
| Kotlin client repository | packet receive, chunk assembly, timeout, UI event emission |

## 4. Chunk transport

### Request side

- Plain requests use `BBS:REQ`
- Large compose/edit requests use `BBS:REQC`
- Request text packets are wrapped with `MBBS1`

### Response side

- Server sends compressed binary chunks using `MBBS2`
- Metadata and reliability helpers are text control packets:
  - `BBS:META`
  - `BBS:MISS`
  - `BBS:ACK`

### Reliability model

Current model is batch-level reliability:
- no per-chunk ACK
- selective resend for missing chunks
- full compressed payload `SHA-256` verification
- completion ACK for cache cleanup

## 5. READ / POSTS / LIST data flow

### `LIST`

1. client `repo.getBoards()` -> `BBS:REQ`
2. server Python bridge handles `LIST`
3. server compresses full JSON response
4. server sends `BBS:META` + `MBBS2` chunks
5. client reassembles, verifies SHA-256, parses JSON
6. `BbsViewModel` receives `BoardsLoaded`
7. board list UI updates

### `POSTS`

1. client `repo.getPosts(board, page)` -> `BBS:REQ`
2. server Python bridge handles `POSTS`
3. response follows the same `BBS:META` + `MBBS2` path
4. client emits `LoadProgress` during assembly
5. `BbsViewModel` receives `PostsLoaded`
6. `postListRetryJob` is currently canceled and not rescheduled

### `READ`

1. client `repo.getPost(postId)` -> `BBS:REQ`
2. server Python bridge handles `READ`
3. response is compressed and chunked
4. client caches chunks out-of-order if necessary
5. if chunks stall, client can send `BBS:MISS`
6. server resends only missing chunks from cache
7. after successful hash verification, client sends `BBS:ACK`
8. `BbsViewModel` receives `PostLoaded`
9. post view UI shows full article at once

## 6. UI behavior relevant to transport

The current UI intent is:

- no page-based article reading
- one tap on a post should eventually show the full article
- progress feedback should stay on the receive side
- UI-level whole-request retry flooding for `READ` and `POSTS` has been reduced/disabled

## 7. Current architectural boundary to keep in mind

Most transport problems being debugged now are in the Kotlin transport layer, not in Python BBS logic:

- chunk size / delay
- metadata/control packet delivery
- pending chunk assembly
- selective resend
- timeout policy
- Meshtastic directed/private packet behavior under LoRa direct vs MQTT relay
