# Relevant Code Snippets

Only transport / chunk / timeout / retry / MBBS2 related code is included below.
These are copied from the current workspace state on 2026-05-26.

## 1. `ServerModels.kt`

```kotlin
package com.meshtastic.bbs.server

enum class TransportProfile(
    val responseChunkSize: Int,
    val responseChunkDelayMs: Long,
) {
    LORA_DIRECT(200, 250L),
    MQTT_SAFE(170, 600L),
    DEBUG_ULTRA_SAFE(120, 900L),
}

data class ServerHostState(
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val meshBound: Boolean = false,
    val hopLimit: Int = 4,
    val transportProfile: TransportProfile = TransportProfile.LORA_DIRECT,
    val responseChunkSize: Int = TransportProfile.LORA_DIRECT.responseChunkSize,
    val responseChunkDelayMs: Long = TransportProfile.LORA_DIRECT.responseChunkDelayMs,
    val broadcastResponsesForDebug: Boolean = false,
    val myNodeId: String = "",
    val status: String = "撌脣?甇?,
    val requestCount: Int = 0,
    val lastEvent: String = "",
    val dashboard: ServerDashboard = ServerDashboard(),
    val logs: List<String> = emptyList(),
    val error: String? = null,
)
```

## 2. `ServerHostStore.kt`

```kotlin
package com.meshtastic.bbs.server

object ServerHostStore {
    private val _state = MutableStateFlow(ServerHostState())
    val state: StateFlow<ServerHostState> = _state.asStateFlow()

    fun setHopLimit(hopLimit: Int) {
        _state.update { it.copy(hopLimit = hopLimit.coerceIn(1, 7)) }
    }

    fun currentHopLimit(): Int = _state.value.hopLimit

    fun setTransportProfile(profile: TransportProfile) {
        _state.update {
            it.copy(
                transportProfile = profile,
                responseChunkSize = profile.responseChunkSize,
                responseChunkDelayMs = profile.responseChunkDelayMs,
            )
        }
    }

    fun currentTransportProfile(): TransportProfile = _state.value.transportProfile

    fun setResponseChunkSize(chunkSize: Int) {
        _state.update { it.copy(responseChunkSize = chunkSize.coerceIn(80, 220)) }
    }

    fun currentResponseChunkSize(): Int = _state.value.responseChunkSize

    fun setResponseChunkDelayMs(delayMs: Long) {
        _state.update { it.copy(responseChunkDelayMs = delayMs.coerceIn(100L, 2_000L)) }
    }

    fun currentResponseChunkDelayMs(): Long = _state.value.responseChunkDelayMs

    fun setBroadcastResponsesForDebug(enabled: Boolean) {
        _state.update { it.copy(broadcastResponsesForDebug = enabled) }
    }

    fun currentBroadcastResponsesForDebug(): Boolean = _state.value.broadcastResponsesForDebug

    fun appendLog(line: String) {
        _state.update { it.copy(logs = it.logs + line) }
    }
}
```

## 3. `MeshtasticServerRepository.kt`

### 3.1 Constants / cache / runtime transport settings

```kotlin
class MeshtasticServerRepository(
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val onMeshState: (Boolean, String) -> Unit,
    private val onRequest: (String, String) -> Unit,
) {

    companion object {
        private const val MESH_PACKAGE = "com.geeksville.mesh"
        private const val MESH_SERVICE = "$MESH_PACKAGE.Service"
        private const val ACTION_RECEIVED = "$MESH_PACKAGE.DATA_PACKET_RECEIVED"
        private const val EXTRA_PACKET = "$MESH_PACKAGE.DATA_PACKET"
        private const val EXTRA_PAYLOAD = "$MESH_PACKAGE.Payload"
        private const val RECEIVER_CLASS = "com.meshtastic.bbs.data.MeshPacketReceiver"
        private const val BBS_APP = 257
        private val BBS_PRIVATE_PREFIX =
            byteArrayOf('M'.code.toByte(), 'B'.code.toByte(), 'B'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())
        private val BBS_BINARY_PREFIX =
            byteArrayOf('M'.code.toByte(), 'B'.code.toByte(), 'B'.code.toByte(), 'S'.code.toByte(), '2'.code.toByte(), '|'.code.toByte())
        private val MESH_CONNECTED_ACTIONS = listOf(
            "$MESH_PACKAGE.MESH_CONNECTED",
            "$MESH_PACKAGE.MeshConnected",
            "$MESH_PACKAGE.action.RADIO_CONNECTED",
        )
        private const val RESPONSE_CHUNK_CACHE_TIMEOUT_MS = 7 * 60_000L
        private const val RESPONSE_CHUNK_CACHE_CLEANUP_INTERVAL_MS = 60_000L
        private const val BATCH_META_PAUSE_MS = 40L
    }

    private val sender = Executors.newSingleThreadExecutor()
    private val cacheMaintenanceExecutor = Executors.newSingleThreadScheduledExecutor()
    private var meshService: IMeshService? = null
    private var myNodeId: String = ""
    private var responseCacheCleanupTask: ScheduledFuture<*>? = null
    private val responseChunkCache = ConcurrentHashMap<String, CachedResponse>()

    private val packetHopLimit: Int
        get() = ServerHostStore.currentHopLimit()

    private val useBroadcastResponsesForDebug: Boolean
        get() = ServerHostStore.currentBroadcastResponsesForDebug()

    private data class ResponseTransportSettings(
        val profile: TransportProfile,
        val chunkSize: Int,
        val delayMs: Long,
    )

    private data class CachedResponse(
        val command: String,
        val logicalDest: String,
        val wireTarget: String,
        val delayMs: Long,
        val sha256: String,
        val chunks: List<ByteArray>,
        var updatedAtMs: Long,
    )

    private fun currentResponseTransportSettings(): ResponseTransportSettings =
        ResponseTransportSettings(
            profile = ServerHostStore.currentTransportProfile(),
            chunkSize = ServerHostStore.currentResponseChunkSize(),
            delayMs = ServerHostStore.currentResponseChunkDelayMs(),
        )
```

### 3.2 Response send / chunking / metadata

```kotlin
    fun sendResponse(destId: String, seq: String, responseJson: String, responseLabel: String = "-") {
        pruneResponseChunkCache()
        val settings = currentResponseTransportSettings()
        val rawBytes = responseJson.toByteArray(Charsets.UTF_8)
        val compressed = deflate(rawBytes)
        val payloadSha256 = sha256Hex(compressed)
        val chunks = compressed.chunkedBytes(settings.chunkSize)
        val total = chunks.size.coerceAtLeast(1)
        val actualChunks = if (chunks.isEmpty()) listOf(ByteArray(0)) else chunks
        val wireTarget = if (useBroadcastResponsesForDebug) DataPacket.ID_BROADCAST else destId
        val directedPacket = wireTarget != DataPacket.ID_BROADCAST
        val responseType = parseResponseType(responseJson)
        val packets = actualChunks.mapIndexed { index, chunk ->
            val header = "MBBS2|$destId|$seq|$index|$total\n".toByteArray(Charsets.UTF_8)
            header + chunk
        }
        val cacheKey = responseCacheKey(destId, seq)
        val now = System.currentTimeMillis()
        responseChunkCache[cacheKey] = CachedResponse(
            command = responseLabel,
            logicalDest = destId,
            wireTarget = wireTarget,
            delayMs = settings.delayMs,
            sha256 = payloadSha256,
            chunks = packets,
            updatedAtMs = now,
        )
        onLog(
            "TX_PLAN command=$responseLabel type=$responseType seq=$seq rawBytes=${rawBytes.size} " +
                "compressedBytes=${compressed.size} chunkSize=${settings.chunkSize} totalChunks=$total " +
                "delayMs=${settings.delayMs} selectedProfile=${settings.profile.name} targetNode=$destId " +
                "sha256=${payloadSha256.take(12)}... " +
                "wireDest=$wireTarget directed=$directedPacket privateLike=$directedPacket"
        )
        sendBatchMeta(destId, wireTarget, seq, total, payloadSha256, "head")
        packets.forEachIndexed { index, packetBytes ->
            val pauseMs = settings.delayMs
            val retrySafeKey = "$seq:${index + 1}/$total"
            val chunkBytes = actualChunks[index].size
            onLog(
                "TX_CHUNK key=$retrySafeKey seq=$seq idx=${index + 1}/$total chunkBytes=$chunkBytes " +
                    "packetBytes=${packetBytes.size} delayAfterMs=$pauseMs target=$wireTarget logicalDest=$destId " +
                    "profile=${settings.profile.name} directed=$directedPacket privateLike=$directedPacket"
            )
            sendPrivate(
                bytes = packetBytes,
                to = wireTarget,
                logSuccess = false,
                pauseMs = pauseMs,
                txLogLabel = "key=$retrySafeKey seq=$seq idx=${index + 1}/$total target=$wireTarget logicalDest=$destId profile=${settings.profile.name} directed=$directedPacket",
            )
        }
        sendBatchMeta(destId, wireTarget, seq, total, payloadSha256, "tail")
    }
```

### 3.3 Selective resend + completion ACK

```kotlin
    fun resendResponseChunks(destId: String, seq: String, missingIndexes: List<Int>) {
        pruneResponseChunkCache()
        val cacheKey = responseCacheKey(destId, seq)
        val cached = responseChunkCache[cacheKey]
        if (cached == null) {
            onLog("MISS_DROP reason=cache_miss pendingKey=$cacheKey missing=${missingIndexes.joinToString(",")}")
            return
        }
        val validIndexes = missingIndexes.distinct().sorted().filter { it in cached.chunks.indices }
        if (validIndexes.isEmpty()) {
            onLog("MISS_DROP reason=invalid_indexes pendingKey=$cacheKey missing=${missingIndexes.joinToString(",")}")
            return
        }
        cached.updatedAtMs = System.currentTimeMillis()
        onLog(
            "MISS_HIT pendingKey=$cacheKey cmd=${cached.command.ifBlank { "-" }} " +
                "missing=${validIndexes.joinToString(",")} resendChunks=${validIndexes.size}/${cached.chunks.size} " +
                "cacheTimeoutMs=$RESPONSE_CHUNK_CACHE_TIMEOUT_MS"
        )
        sendBatchMeta(cached.logicalDest, cached.wireTarget, seq, cached.chunks.size, cached.sha256, "miss")
        validIndexes.forEachIndexed { resendOrder, index ->
            sendPrivate(
                bytes = cached.chunks[index],
                to = cached.wireTarget,
                logSuccess = false,
                pauseMs = cached.delayMs,
                txLogLabel = "MISS key=$cacheKey idx=${index + 1}/${cached.chunks.size} target=${cached.wireTarget} logicalDest=${cached.logicalDest}",
            )
            if (resendOrder == validIndexes.lastIndex) {
                onLog("MISS_DONE pendingKey=$cacheKey resent=${validIndexes.joinToString(",")}")
            }
        }
    }

    fun acknowledgeResponse(destId: String, seq: String, sha256: String) {
        pruneResponseChunkCache()
        val cacheKey = responseCacheKey(destId, seq)
        val cached = responseChunkCache[cacheKey]
        if (cached == null) {
            onLog("ACK_DROP reason=cache_miss pendingKey=$cacheKey sha256=${sha256.take(12)}...")
            return
        }
        if (!cached.sha256.equals(sha256, ignoreCase = true)) {
            onLog(
                "ACK_DROP reason=sha_mismatch pendingKey=$cacheKey " +
                    "expected=${cached.sha256.take(12)}... actual=${sha256.take(12)}..."
            )
            return
        }
        responseChunkCache.remove(cacheKey)
        onLog(
            "ACK_OK pendingKey=$cacheKey cmd=${cached.command.ifBlank { "-" }} " +
                "totalChunks=${cached.chunks.size} sha256=${cached.sha256.take(12)}..."
        )
    }
```

### 3.4 Packet send primitive / `BBS:META`

```kotlin
    private fun sendPrivate(
        bytes: ByteArray,
        to: String,
        logSuccess: Boolean = true,
        pauseMs: Long = 280L,
        txLogLabel: String = "",
    ) {
        sender.execute {
            val packet = DataPacket(
                to = to,
                bytes = bytes,
                dataType = BBS_APP,
                from = myNodeId.ifBlank { DataPacket.ID_LOCAL },
                hopLimit = packetHopLimit,
                channel = 0,
                wantAck = false,
            )
            runCatching { meshService?.send(packet) }
                .onSuccess {
                    if (logSuccess) {
                        onLog("TX_OK ${txLogLabel.ifBlank { "to=$to bytes=${bytes.size}" }} packetBytes=${bytes.size}")
                    }
                }
                .onFailure {
                    onLog("TX_FAIL ${txLogLabel.ifBlank { "to=$to bytes=${bytes.size}" }} error=${it.message}")
                }
            Thread.sleep(pauseMs)
        }
    }

    private fun sendBatchMeta(
        logicalDest: String,
        wireTarget: String,
        seq: String,
        total: Int,
        sha256: String,
        phase: String,
    ) {
        val text = "BBS:META:$seq:$total:$sha256"
        onLog(
            "BATCH_META_TX phase=$phase seq=$seq total=$total sha256=${sha256.take(12)}... " +
                "target=$wireTarget logicalDest=$logicalDest"
        )
        sendPrivate(
            bytes = BBS_PRIVATE_PREFIX + text.toByteArray(Charsets.UTF_8),
            to = wireTarget,
            logSuccess = false,
            pauseMs = BATCH_META_PAUSE_MS,
            txLogLabel = "META phase=$phase seq=$seq target=$wireTarget logicalDest=$logicalDest",
        )
    }
```

## 4. `AndroidServerService.kt`

### 4.1 Main request dispatcher

```kotlin
    private fun handleIncomingRequest(fromId: String, text: String) {
        if (text.startsWith("BBS:ACK:")) {
            handleBatchAck(fromId, text)
            return
        }
        if (text.startsWith("BBS:MISS:")) {
            handleMissingChunkRequest(fromId, text)
            return
        }
        if (text.startsWith("BBS:REQC:")) {
            handleCompressedRequest(fromId, text)
            return
        }
        if (!text.startsWith("BBS:REQ:")) {
            handlePlainTextRequest(fromId, text.trim())
            return
        }

        val parts = text.split(":", limit = 5)
        if (parts.size < 4) return
        val seq = parts[2]
        val cmd = parts[3].uppercase()
        val args = if (parts.size > 4) parts[4] else ""
        val requestKey = "$fromId:$seq"
        if (!rememberRequest(requestKey)) {
            ServerHostStore.appendLog("REQ_DROP reason=duplicate pendingKey=$requestKey from=$fromId seq=$seq cmd=$cmd")
            return
        }

        val startedAt = SystemClock.elapsedRealtime()
        ServerHostStore.incrementRequest("$cmd <- ${fromId.takeLast(6)}")
        val response = ensureBridge().handleRequest(cmd, args, fromId, fromId).orEmpty()
        val handledAt = SystemClock.elapsedRealtime()

        if (response.isNotBlank() && response != "null") {
            repo?.sendResponse(fromId, seq, response, cmd)
        }
        val queuedAt = SystemClock.elapsedRealtime()
        ServerHostStore.appendLog(
            "PERF $cmd seq=$seq py=${handledAt - startedAt}ms queue=${queuedAt - handledAt}ms bytes=${response.length}"
        )
    }
```

### 4.2 Missing-chunk request / batch ACK receive

```kotlin
    private fun handleMissingChunkRequest(fromId: String, text: String) {
        val parts = text.split(":", limit = 4)
        if (parts.size < 4) {
            ServerHostStore.appendLog("MISS_DROP reason=invalid_parts from=$fromId rawSize=${text.length}")
            return
        }
        val seq = parts[2]
        val missingIndexes = parts[3]
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it >= 0 }
            .distinct()
            .sorted()
        if (seq.isBlank() || missingIndexes.isEmpty()) {
            ServerHostStore.appendLog("MISS_DROP reason=invalid_indexes from=$fromId seq=$seq raw=${parts[3]}")
            return
        }
        ServerHostStore.appendLog(
            "MISS_REQ from=$fromId seq=$seq missing=${missingIndexes.joinToString(",")}"
        )
        repo?.resendResponseChunks(fromId, seq, missingIndexes)
    }

    private fun handleBatchAck(fromId: String, text: String) {
        val parts = text.split(":", limit = 4)
        if (parts.size < 4) {
            ServerHostStore.appendLog("ACK_DROP reason=invalid_parts from=$fromId rawSize=${text.length}")
            return
        }
        val seq = parts[2]
        val sha256 = parts[3].trim().lowercase()
        if (seq.isBlank() || sha256.length != 64) {
            ServerHostStore.appendLog("ACK_DROP reason=invalid_payload from=$fromId seq=$seq sha=${parts[3]}")
            return
        }
        ServerHostStore.appendLog("ACK_REQ from=$fromId seq=$seq sha256=${sha256.take(12)}...")
        repo?.acknowledgeResponse(fromId, seq, sha256)
    }
```

### 4.3 Compressed request reassembly / timeout

```kotlin
    private fun handleCompressedRequest(fromId: String, text: String) {
        val parts = text.split(":", limit = 7)
        if (parts.size < 7) {
            ServerHostStore.appendLog("REQC_DROP reason=invalid_parts from=$fromId rawSize=${text.length}")
            return
        }
        val seq = parts[2]
        val cmd = parts[3].uppercase()
        val index = parts[4].toIntOrNull() ?: return
        val total = parts[5].toIntOrNull() ?: return
        val pendingKey = "$fromId:$seq"
        val payloadSize = parts[6].length
        if (total <= 0 || index !in 0 until total) {
            ServerHostStore.appendLog(
                "REQC_DROP reason=invalid_index from=$fromId seq=$seq idx=${index + 1}/$total " +
                    "payloadSize=$payloadSize pendingKey=$pendingKey timeoutMs=$COMPRESSED_REQUEST_TIMEOUT_MS"
            )
            return
        }
        val chunk = parts[6]
        val now = SystemClock.elapsedRealtime()
        pruneStaleCompressedRequests(now)
        val pending = pendingCompressedRequests.getOrPut(pendingKey) {
            PendingCompressedRequest(cmd = cmd, total = total, updatedAtMs = now)
        }
        pending.updatedAtMs = now
        pending.chunks[index] = chunk
        ServerHostStore.appendLog(
            "REQC_RX from=$fromId seq=$seq idx=${index + 1}/$total payloadSize=$payloadSize " +
                "pendingKey=$pendingKey pending=${formatChunkList(total, pending.chunks.keys)} " +
                "missing=${formatMissingChunks(total, pending.chunks.keys)} timeoutMs=$COMPRESSED_REQUEST_TIMEOUT_MS"
        )
        if (pending.chunks.size < pending.total) return

        val missingBeforeAssemble = missingChunkIndices(pending.total, pending.chunks.keys)
        if (missingBeforeAssemble.isNotEmpty()) {
            ServerHostStore.appendLog(
                "REQC_WAIT reason=missing_chunks from=$fromId seq=$seq pendingKey=$pendingKey " +
                    "pending=${formatChunkList(pending.total, pending.chunks.keys)} missing=${formatMissingChunks(pending.total, pending.chunks.keys)}"
            )
            return
        }

        pendingCompressedRequests.remove(pendingKey)
        val encoded = buildString {
            for (i in 0 until pending.total) {
                append(pending.chunks[i].orEmpty())
            }
        }
        val args = runCatching {
            String(zlibDecompress(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse {
            ServerHostStore.appendLog(
                "REQC_DROP reason=decode_failed from=$fromId seq=$seq pendingKey=$pendingKey " +
                    "pending=${formatChunkList(pending.total, pending.chunks.keys)} error=${it.message}"
            )
            return
        }
        val requestKey = pendingKey
        if (!rememberRequest(requestKey)) {
            ServerHostStore.appendLog("REQC_DROP reason=duplicate from=$fromId seq=$seq pendingKey=$pendingKey")
            return
        }

        val startedAt = SystemClock.elapsedRealtime()
        ServerHostStore.incrementRequest("$cmd <- ${fromId.takeLast(6)}")
        val response = ensureBridge().handleRequest(cmd, args, fromId, fromId).orEmpty()
        val handledAt = SystemClock.elapsedRealtime()
        if (response.isNotBlank() && response != "null") {
            repo?.sendResponse(fromId, seq, response, cmd)
        }
        val queuedAt = SystemClock.elapsedRealtime()
        ServerHostStore.appendLog(
            "PERF $cmd seq=$seq py=${handledAt - startedAt}ms queue=${queuedAt - handledAt}ms bytes=${response.length}"
        )
    }

    private fun pruneStaleCompressedRequests(now: Long) {
        val staleEntries = pendingCompressedRequests.entries
            .filter { (_, pending) -> now - pending.updatedAtMs > COMPRESSED_REQUEST_TIMEOUT_MS }
        staleEntries.forEach { (key, pending) ->
            ServerHostStore.appendLog(
                "REQC_TIMEOUT pendingKey=$key cmd=${pending.cmd} pending=${formatChunkList(pending.total, pending.chunks.keys)} " +
                    "missing=${formatMissingChunks(pending.total, pending.chunks.keys)} timeoutMs=$COMPRESSED_REQUEST_TIMEOUT_MS"
            )
            pendingCompressedRequests.remove(key)
        }
    }
```

## 5. `MeshtasticRepository.kt`

### 5.1 Constants / pending model

```kotlin
class MeshtasticRepository(private val context: Context) {

    companion object {
        const val MESH_PACKAGE = "com.geeksville.mesh"
        const val MESH_SERVICE = "$MESH_PACKAGE.Service"
        const val ACTION_RECEIVED = "$MESH_PACKAGE.DATA_PACKET_RECEIVED"
        const val ACTION_NODE_CHANGE = "$MESH_PACKAGE.NODE_CHANGE"
        const val EXTRA_PACKET = "$MESH_PACKAGE.DATA_PACKET"
        const val EXTRA_PAYLOAD = "$MESH_PACKAGE.Payload"
        const val BBS_APP = 257
        const val BUILD = "b0604s"
        private val BBS_PRIVATE_PREFIX = "MBBS1".toByteArray(Charsets.UTF_8)
        private val BBS_BINARY_PREFIX = "MBBS2|".toByteArray(Charsets.UTF_8)
        private val MESH_CHAT_PREFIX = "MBCHAT1".toByteArray(Charsets.UTF_8)
        private const val NODEINFO_APP = 4
        private const val REQUEST_CHUNK_CHARS = 180
        private const val REQUEST_CHUNK_PAUSE_MS = 320L
        private const val PACKET_HOP_LIMIT = 4
        private const val BASE_PENDING_CHUNK_TIMEOUT_MS = 60_000L
        private const val PER_CHUNK_TIMEOUT_MS = 8_000L
        private const val MAX_PENDING_CHUNK_TIMEOUT_MS = 240_000L
        private const val PENDING_CHUNK_CLEANUP_INTERVAL_MS = 5_000L
        private const val MISSING_CHUNK_REQUEST_IDLE_MS = 4_000L
        private const val MISSING_CHUNK_REQUEST_INTERVAL_MS = 4_000L
        private const val MAX_MISSING_CHUNK_REQUESTS = 3
    }

    private data class PendingChunk(
        val chunks: MutableMap<Int, String> = ConcurrentHashMap(),
        var total: Int = -1,
        val cmd: String = "",
        val stage: String = "",
        val startedAtMs: Long = SystemClock.elapsedRealtime(),
        var updatedAtMs: Long = SystemClock.elapsedRealtime(),
        var sourceNodeId: String = "",
        var expectedSha256: String = "",
        var proto: PendingProto = PendingProto.UNKNOWN,
        var missRequestCount: Int = 0,
        var lastMissRequestAtMs: Long = 0L,
    )

    private enum class PendingProto {
        UNKNOWN,
        MBBS2,
        BBS_RES,
    }
```

### 5.2 Packet receive / control-text decode

```kotlin
    private fun handlePacket(packet: DataPacket): BbsEvent? {
        val bytes = packet.bytes ?: return null
        debug("rx packet from=${packet.from} to=${packet.to} type=${packet.dataType} size=${bytes.size}")
        parseMeshChat(bytes, packet.from.orEmpty())?.let { return it }
        if (packet.dataType == NODEINFO_APP) {
            parseNodeInfoPayload(bytes, packet.from.orEmpty())?.let { return BbsEvent.NodeChanged(it) }
            return null
        }
        if (packet.dataType != DataPacket.PRIVATE_APP &&
            packet.dataType != DataPacket.TEXT_MESSAGE_APP &&
            packet.dataType != BBS_APP
        ) {
            return null
        }
        handleMbbs2(bytes, packet.from.orEmpty(), packet.to.orEmpty())?.let { return it }
        if (bytes.startsWithPrefix(BBS_BINARY_PREFIX)) {
            debug("ignore raw MBBS2 packet from=${packet.from} size=${bytes.size}")
            return null
        }
        val text = decodeControlText(bytes, packet.dataType) ?: return null
        val fromId = packet.from.orEmpty()
        if (fromId.isNotBlank() && fromId != DataPacket.ID_LOCAL && fromId != myNodeId) {
            return handleIncoming(text, fromId, packet.to.orEmpty())
                ?: BbsEvent.NodeChanged(MeshNode(fromId, fromId, "", System.currentTimeMillis()))
        }
        return handleIncoming(text, fromId, packet.to.orEmpty())
    }

    private fun decodeControlText(bytes: ByteArray, dataType: Int): String? {
        return when {
            bytes.startsWithPrefix(BBS_PRIVATE_PREFIX) ->
                String(bytes, BBS_PRIVATE_PREFIX.size, bytes.size - BBS_PRIVATE_PREFIX.size, Charsets.UTF_8)
            dataType == BBS_APP || dataType == DataPacket.PRIVATE_APP || dataType == DataPacket.TEXT_MESSAGE_APP ->
                runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
            else -> null
        }
    }
```

### 5.3 `MBBS2` chunk receive / metadata receive

```kotlin
    private fun handleMbbs2(bytes: ByteArray, fromNode: String, toNode: String): BbsEvent? {
        val headerEnd = bytes.indexOf('\n'.code.toByte())
        if (headerEnd <= 0) {
            comm("CHUNK_DROP proto=MBBS2 from=$fromNode to=$toNode reason=missing_header payloadSize=${bytes.size}")
            return null
        }
        val header = String(bytes, 0, headerEnd, Charsets.UTF_8)
        if (!header.startsWith("MBBS2|")) return null

        val parts = header.split("|")
        if (parts.size < 5) {
            comm("CHUNK_DROP proto=MBBS2 from=$fromNode to=$toNode reason=invalid_header header=$header")
            return null
        }
        val destId = parts[1]
        val seq = parts[2]
        val idx = parts[3].toIntOrNull() ?: return null
        val total = parts[4].toIntOrNull() ?: return null
        val payloadSize = bytes.size - headerEnd - 1
        if (total <= 0 || idx !in 0 until total) {
            comm(
                "CHUNK_DROP proto=MBBS2 from=$fromNode to=$toNode seq=$seq idx=${idx + 1}/$total " +
                    "payloadSize=$payloadSize pendingKey=$seq timeoutMs=${pendingChunkTimeoutMs(total)} reason=invalid_index"
            )
            return null
        }

        if (myNodeId.isNotBlank() && destId != myNodeId) {
            comm(
                "CHUNK_DROP proto=MBBS2 from=$fromNode to=$toNode seq=$seq idx=${idx + 1}/$total " +
                    "payloadSize=$payloadSize pendingKey=$seq timeoutMs=${pendingChunkTimeoutMs(total)} " +
                    "reason=dest_mismatch logicalTo=$destId myNodeId=$myNodeId"
            )
            return null
        }
        if (seq in completed) {
            comm(
                "CHUNK_DROP proto=MBBS2 from=$fromNode to=$toNode seq=$seq idx=${idx + 1}/$total " +
                    "payloadSize=$payloadSize pendingKey=$seq timeoutMs=${pendingChunkTimeoutMs(total)} reason=already_completed"
            )
            return null
        }

        val now = SystemClock.elapsedRealtime()
        pruneStalePendingChunks(now)
        val entry = pending.getOrPut(seq) { PendingChunk() }
        entry.updatedAtMs = now
        entry.sourceNodeId = fromNode.ifBlank { entry.sourceNodeId }
        entry.proto = PendingProto.MBBS2
        entry.chunks[idx] = Base64.encodeToString(bytes.copyOfRange(headerEnd + 1, bytes.size), Base64.NO_WRAP)
        entry.total = total
        comm(
            "CHUNK_RX proto=MBBS2 from=$fromNode to=$toNode seq=$seq idx=${idx + 1}/$total payloadSize=$payloadSize " +
                "pendingKey=$seq pending=${formatChunkList(total, entry.chunks.keys)} " +
                "missing=${formatMissingChunks(total, entry.chunks.keys)} timeoutMs=${pendingChunkTimeoutMs(total)}"
        )
        if (entry.stage.isNotBlank()) {
            val progress = (20 + ((entry.chunks.size * 75) / total.coerceAtLeast(1))).coerceAtMost(95)
            emit(BbsEvent.LoadProgress("${entry.stage} ${entry.chunks.size}/$total", progress, true))
        }
        return tryFinalizePending(seq, entry, fromNode, toNode)
    }

    private fun handleIncoming(text: String, fromId: String, toId: String): BbsEvent? {
        return when {
            text.startsWith("BBS:META:") -> handleBatchMeta(text, fromId, toId)
            text.startsWith("BBS:RES:") -> handleBbsRes(text, fromId, toId)
            text.startsWith("MBBS2|") -> null
            !text.startsWith("BBS:") -> BbsEvent.NewMeshMessage(
                MeshMessage(fromId, fromId.takeLast(6), text, "")
            )
            else -> null
        }
    }

    private fun handleBatchMeta(text: String, fromNode: String, toNode: String): BbsEvent? {
        val parts = text.split(":", limit = 5)
        if (parts.size < 5) {
            comm("BATCH_META_DROP from=$fromNode to=$toNode reason=invalid_parts payloadSize=${text.length}")
            return null
        }
        val seq = parts[2]
        val total = parts[3].toIntOrNull() ?: return null
        val sha256 = parts[4].trim().lowercase()
        if (seq.isBlank() || total <= 0 || sha256.length != 64) {
            comm("BATCH_META_DROP from=$fromNode to=$toNode seq=$seq total=$total reason=invalid_meta")
            return null
        }
        val entry = pending.getOrPut(seq) { PendingChunk(total = total) }
        entry.updatedAtMs = SystemClock.elapsedRealtime()
        entry.sourceNodeId = fromNode.ifBlank { entry.sourceNodeId }
        if (entry.total <= 0) entry.total = total
        entry.expectedSha256 = sha256
        comm(
            "BATCH_META_RX from=$fromNode to=$toNode seq=$seq expectedTotal=$total " +
                "sha256=${sha256.take(12)}..."
        )
        return tryFinalizePending(seq, entry, fromNode, toNode)
    }
```

### 5.4 Legacy `BBS:RES` / timeout / missing / finalize

```kotlin
    private fun handleBbsRes(text: String, fromNode: String, toNode: String): BbsEvent? {
        val parts = text.split(":", limit = 7)
        if (parts.size < 7) {
            comm("CHUNK_DROP proto=BBS_RES from=$fromNode to=$toNode reason=invalid_parts payloadSize=${text.length}")
            return null
        }

        val destId = parts[2]
        val seq = parts[3]
        val idx = parts[4].toIntOrNull() ?: return null
        val total = parts[5].toIntOrNull() ?: return null
        val data = parts[6]
        if (total <= 0 || idx !in 0 until total) {
            comm(
                "CHUNK_DROP proto=BBS_RES from=$fromNode to=$toNode seq=$seq idx=${idx + 1}/$total " +
                    "payloadSize=${data.length} pendingKey=$seq timeoutMs=${pendingChunkTimeoutMs(total)} reason=invalid_index"
            )
            return null
        }

        if (myNodeId.isNotBlank() && destId != myNodeId) {
            comm(
                "CHUNK_DROP proto=BBS_RES from=$fromNode to=$toNode seq=$seq idx=${idx + 1}/$total payloadSize=${data.length} " +
                    "pendingKey=$seq timeoutMs=${pendingChunkTimeoutMs(total)} reason=dest_mismatch logicalTo=$destId myNodeId=$myNodeId"
            )
            return null
        }
        if (seq in completed) {
            comm(
                "CHUNK_DROP proto=BBS_RES from=$fromNode to=$toNode seq=$seq idx=${idx + 1}/$total payloadSize=${data.length} " +
                    "pendingKey=$seq timeoutMs=${pendingChunkTimeoutMs(total)} reason=already_completed"
            )
            return null
        }

        val now = SystemClock.elapsedRealtime()
        pruneStalePendingChunks(now)
        val entry = pending.getOrPut(seq) { PendingChunk() }
        entry.updatedAtMs = now
        entry.sourceNodeId = fromNode.ifBlank { entry.sourceNodeId }
        entry.proto = PendingProto.BBS_RES
        entry.chunks[idx] = data
        entry.total = total
        comm(
            "CHUNK_RX proto=BBS_RES from=$fromNode to=$toNode seq=$seq idx=${idx + 1}/$total payloadSize=${data.length} " +
                "pendingKey=$seq pending=${formatChunkList(total, entry.chunks.keys)} " +
                "missing=${formatMissingChunks(total, entry.chunks.keys)} timeoutMs=${pendingChunkTimeoutMs(total)}"
        )
        if (entry.stage.isNotBlank()) {
            val progress = (20 + ((entry.chunks.size * 75) / total.coerceAtLeast(1))).coerceAtMost(95)
            emit(BbsEvent.LoadProgress("${entry.stage} ${entry.chunks.size}/$total", progress, true))
        }
        return tryFinalizePending(seq, entry, fromNode, toNode)
    }

    private fun pruneStalePendingChunks(now: Long) {
        requestMissingChunksIfNeeded(now)
        val staleEntries = pending.entries
            .filter { (_, entry) -> now - entry.updatedAtMs > pendingChunkTimeoutMs(entry.total) }
        staleEntries.forEach { (key, entry) ->
            val timeoutMs = pendingChunkTimeoutMs(entry.total)
            val elapsedMs = now - entry.startedAtMs
            comm(
                "CHUNK_TIMEOUT seq=$key cmd=${entry.cmd.ifBlank { "-" }} expectedTotal=${entry.total} " +
                    "received=${formatChunkList(entry.total, entry.chunks.keys)} " +
                    "missing=${formatMissingChunks(entry.total, entry.chunks.keys)} " +
                    "elapsedMs=$elapsedMs timeoutMs=$timeoutMs"
            )
            if (entry.stage.isNotBlank()) {
                emit(BbsEvent.LoadProgress("", 0, false))
                emit(
                    BbsEvent.ServerError(
                        "${entry.stage} 接收逾時，缺少 ${formatMissingChunks(entry.total, entry.chunks.keys)}"
                    )
                )
            }
            pending.remove(key)
        }
    }

    private fun requestMissingChunksIfNeeded(now: Long) {
        pending.forEach { (seq, entry) ->
            val total = entry.total
            if (total <= 0 || entry.chunks.isEmpty() || entry.chunks.size >= total) return@forEach
            if (entry.missRequestCount >= MAX_MISSING_CHUNK_REQUESTS) return@forEach
            if (now - entry.updatedAtMs < MISSING_CHUNK_REQUEST_IDLE_MS) return@forEach
            if (entry.lastMissRequestAtMs > 0L && now - entry.lastMissRequestAtMs < MISSING_CHUNK_REQUEST_INTERVAL_MS) {
                return@forEach
            }
            val missing = missingChunkIndices(total, entry.chunks.keys)
            if (missing.isEmpty()) return@forEach
            sendMissingChunkRequest(seq, entry, missing, now, "missing")
        }
    }

    private fun tryFinalizePending(seq: String, entry: PendingChunk, fromNode: String, toNode: String): BbsEvent? {
        val total = entry.total
        if (total <= 0 || entry.chunks.size < total) return null

        val missingBeforeAssemble = missingChunkIndices(total, entry.chunks.keys)
        if (missingBeforeAssemble.isNotEmpty()) {
            comm(
                "CHUNK_WAIT proto=${entry.proto.name} pendingKey=$seq pending=${formatChunkList(total, entry.chunks.keys)} " +
                    "missing=${formatMissingChunks(total, entry.chunks.keys)} reason=assemble_blocked"
            )
            return null
        }
        if (entry.expectedSha256.isBlank()) {
            comm(
                "CHUNK_WAIT proto=${entry.proto.name} pendingKey=$seq pending=${formatChunkList(total, entry.chunks.keys)} " +
                    "missing=[] reason=await_meta"
            )
            return null
        }

        val compressed = when (entry.proto) {
            PendingProto.MBBS2 -> buildCompressedBytes(entry, total)
            PendingProto.BBS_RES -> Base64.decode((0 until total).joinToString("") { index -> entry.chunks[index] ?: "" }, Base64.DEFAULT)
            PendingProto.UNKNOWN -> return null
        }
        val actualSha256 = sha256Hex(compressed)
        if (!actualSha256.equals(entry.expectedSha256, ignoreCase = true)) {
            comm(
                "CHUNK_HASH_FAIL proto=${entry.proto.name} from=$fromNode to=$toNode seq=$seq " +
                    "expected=${entry.expectedSha256.take(12)}... actual=${actualSha256.take(12)}..."
            )
            entry.chunks.clear()
            entry.updatedAtMs = SystemClock.elapsedRealtime()
            sendMissingChunkRequest(seq, entry, (0 until total).toList(), entry.updatedAtMs, "hash_mismatch")
            return null
        }

        return try {
            val event = when (entry.proto) {
                PendingProto.MBBS2 -> parseJson(JSONObject(String(zlibDecompress(compressed), Charsets.UTF_8)))
                PendingProto.BBS_RES -> parseJson(JSONObject(String(zlibDecompress(compressed), Charsets.UTF_8)))
                PendingProto.UNKNOWN -> null
            }
            completed.add(seq)
            pending.remove(seq)
            if (completed.size > 300) completed.remove(completed.first())
            sendBatchAck(seq, entry.expectedSha256, entry.sourceNodeId)
            if (entry.stage.isNotBlank()) emit(BbsEvent.LoadProgress("", 0, false))
            comm(
                "CHUNK_DONE proto=${entry.proto.name} pendingKey=$seq total=$total " +
                    "sha256=${actualSha256.take(12)}... from=$fromNode to=$toNode"
            )
            event
        } catch (e: Exception) {
            if (entry.stage.isNotBlank()) emit(BbsEvent.LoadProgress("", 0, false))
            comm(
                "CHUNK_DROP proto=${entry.proto.name} from=$fromNode to=$toNode seq=$seq pendingKey=$seq " +
                    "pending=${formatChunkList(total, entry.chunks.keys)} reason=parse_failed error=${e.message}"
            )
            BbsEvent.ServerError("BBS 回應解析失敗: ${e.message}")
        }
    }

    private fun sendMissingChunkRequest(
        seq: String,
        entry: PendingChunk,
        indexes: List<Int>,
        now: Long,
        reason: String,
    ) {
        if (indexes.isEmpty() || entry.missRequestCount >= MAX_MISSING_CHUNK_REQUESTS) return
        val targetNode = entry.sourceNodeId.ifBlank { serverNodeId }
        if (targetNode.isBlank() || targetNode == DataPacket.ID_BROADCAST) return
        val missingSpec = indexes.joinToString(",")
        comm(
            "SEND BBS:MISS seq=$seq cmd=${entry.cmd.ifBlank { "-" }} to=$targetNode " +
                "missing=$missingSpec retry=${entry.missRequestCount + 1}/$MAX_MISSING_CHUNK_REQUESTS reason=$reason"
        )
        sendRaw("BBS:MISS:$seq:$missingSpec", targetNode)
        entry.missRequestCount += 1
        entry.lastMissRequestAtMs = now
    }

    private fun sendBatchAck(seq: String, sha256: String, sourceNodeId: String) {
        val targetNode = sourceNodeId.ifBlank { serverNodeId }
        if (targetNode.isBlank() || targetNode == DataPacket.ID_BROADCAST) return
        comm("SEND BBS:ACK seq=$seq to=$targetNode sha256=${sha256.take(12)}...")
        sendRaw("BBS:ACK:$seq:$sha256", targetNode)
    }

    private fun pendingChunkTimeoutMs(total: Int): Long {
        if (total <= 0) return BASE_PENDING_CHUNK_TIMEOUT_MS
        return (BASE_PENDING_CHUNK_TIMEOUT_MS + total.toLong() * PER_CHUNK_TIMEOUT_MS)
            .coerceAtMost(MAX_PENDING_CHUNK_TIMEOUT_MS)
    }
```

### 5.5 Request send path

```kotlin
    fun login(name: String, password: String) {
        sendReq("LOGIN", "$name:${hashPassword(password)}")
    }

    fun getBoards() = sendReq("LIST")

    fun getPosts(board: String, page: Int = 1) = sendReq("POSTS", "$board:$page")

    fun getPost(postId: Int) = sendReq("READ", postId.toString())

    private fun sendReq(cmd: String, args: String = "") {
        val seq = nextSeq()
        val stage = loadStageFor(cmd)
        pending[seq] = PendingChunk(cmd = cmd, stage = stage)
        if (stage.isNotBlank()) {
            emit(BbsEvent.LoadProgress("正在送出請求", 5, true))
        }
        val preview = when (cmd) {
            "LOGIN" -> "<redacted>"
            "CHPASS" -> "<redacted>"
            else -> args.take(120)
        }
        comm("SEND BBS:REQ seq=$seq cmd=$cmd to=$serverNodeId args=$preview")
        sendRaw("BBS:REQ:$seq:$cmd:$args")
    }

    private fun sendCompressedReq(cmd: String, args: String, compressStage: String) {
        val seq = nextSeq()
        pending[seq] = PendingChunk()
        emit(BbsEvent.SubmitProgress(compressStage, 5, true))
        requestSender.execute {
            runCatching {
                val compressed = zlibCompress(args.toByteArray(Charsets.UTF_8))
                emit(BbsEvent.SubmitProgress(compressStage, 25, true))
                val encoded = Base64.encodeToString(compressed, Base64.NO_WRAP)
                val chunks = encoded.chunked(REQUEST_CHUNK_CHARS).ifEmpty { listOf("") }
                val total = chunks.size
                chunks.forEachIndexed { index, chunk ->
                    comm("SEND BBS:REQC seq=$seq cmd=$cmd chunk=${index + 1}/$total to=$serverNodeId")
                    sendRaw("BBS:REQC:$seq:$cmd:$index:$total:$chunk")
                    val progress = 25 + (((index + 1) * 70) / total)
                    emit(BbsEvent.SubmitProgress("正在發送 ${index + 1}/$total", progress.coerceAtMost(95), true))
                    Thread.sleep(REQUEST_CHUNK_PAUSE_MS)
                }
                emit(BbsEvent.SubmitProgress("等待伺服器回應", 100, true))
            }.onFailure { error ->
                pending.remove(seq)
                emit(BbsEvent.SubmitProgress("", 0, false))
                emit(BbsEvent.ServerError("發送失敗: ${error.message ?: "未知錯誤"}"))
            }
        }
    }

    private fun sendRaw(text: String, to: String = serverNodeId) {
        val payload = BBS_PRIVATE_PREFIX + text.toByteArray(Charsets.UTF_8)
        debug("sendRaw to=$to bytes=${payload.size} type=$BBS_APP")
        runCatching {
            meshService?.send(
                DataPacket(
                    to = to,
                    bytes = payload,
                    dataType = BBS_APP,
                    wantAck = false,
                    hopLimit = PACKET_HOP_LIMIT,
                    channel = 0,
                )
            )
        }.onSuccess { packetId ->
            debug("meshService.send ok packetId=$packetId")
        }.onFailure { error ->
            debug("meshService.send failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }
```

## 6. `BbsViewModel.kt`

```kotlin
class BbsViewModel(app: Application) : AndroidViewModel(app) {
    private var repo: MeshtasticRepository? = null
    private var postListRetryJob: Job? = null
    private var postReadRetryJob: Job? = null

    fun login(name: String, password: String) {
        _state.update { it.copy(isLoading = true, error = null) }
        repo?.login(name, password)
    }

    fun refreshBoards() {
        _state.update { it.copy(isLoading = true) }
        repo?.getBoards()
    }

    fun loadPosts(board: String = _state.value.currentBoardName, page: Int = 1) {
        _state.update { it.copy(isLoading = true, currentPage = page) }
        repo?.getPosts(board, page)
        schedulePostListRetry(board, page)
    }

    fun openPost(postId: Int) {
        postReadRetryJob?.cancel()
        postReadRetryJob = null
        _state.update { it.copy(isLoading = true, currentPost = null) }
        _screen.value = Screen.PostView(postId)
        repo?.getPost(postId)
    }

    private fun handleEvent(event: BbsEvent) {
        when (event) {
            is BbsEvent.LoginOk -> {
                val user = UserInfo(event.nodeId, event.name, event.isAdmin)
                repo?.setCurrentUser(user)
                _state.update {
                    it.copy(
                        isLoading = false,
                        currentUser = user,
                        onlineUsers = event.onlineUsers,
                    )
                }
                _screen.value = Screen.Boards
                refreshBoards()
            }

            is BbsEvent.LoginError -> {
                _state.update { it.copy(isLoading = false, error = event.msg) }
            }

            is BbsEvent.PostsLoaded -> {
                postListRetryJob?.cancel()
                postListRetryJob = null
                _state.update { s ->
                    val merged = if (event.page == 1) event.posts else s.posts + event.posts
                    s.copy(
                        isLoading = false,
                        posts = merged,
                        postTotal = event.total,
                        currentPage = event.page,
                        currentBoardName = event.board,
                    )
                }
            }

            is BbsEvent.PostLoaded -> {
                postReadRetryJob?.cancel()
                postReadRetryJob = null
                _state.update { it.copy(isLoading = false, currentPost = event.detail) }
            }

            is BbsEvent.LoadProgress -> {
                _state.update {
                    it.copy(
                        loadInProgress = event.active,
                        loadStage = if (event.active) event.stage else "",
                        loadProgress = if (event.active) event.progress.coerceIn(0, 100) else 0,
                    )
                }
            }

            is BbsEvent.ServerError -> {
                postListRetryJob?.cancel()
                postListRetryJob = null
                _state.update {
                    it.copy(
                        isLoading = false,
                        toast = event.msg,
                        submitInProgress = false,
                        submitStage = "",
                        submitProgress = 0,
                        loadInProgress = false,
                        loadStage = "",
                        loadProgress = 0,
                    )
                }
            }

            is BbsEvent.BssCommLog -> {
                _state.update { s ->
                    val log = "${s.bssLog}\n${event.msg}".trimStart('\n')
                    s.copy(bssLog = limitChars(log, MAX_BSS_LOG_CHARS))
                }
                appendDiagnostic("BBS", event.msg)
            }

            else -> Unit
        }
    }

    private fun schedulePostListRetry(board: String, page: Int) {
        postListRetryJob?.cancel()
        postListRetryJob = null
    }
}
```

## 7. Notes for code reviewers

- `READ` whole-response retry is no longer being scheduled in `BbsViewModel`.
- Reliability is now implemented primarily inside `MeshtasticRepository` and `MeshtasticServerRepository`.
- `BBS:META`, `BBS:MISS`, and `BBS:ACK` are additive around the existing `MBBS2` chunk format; the `MBBS2` binary packet body itself was not redesigned.
