# Relevant Code Snippets

生成時間：2026-05-26  
用途：提供另一位 AI 工程助手直接閱讀目前與傳輸相關的實際 Kotlin 程式碼片段。以下內容刻意不做摘要。

## 1. `ServerModels.kt` - `TransportProfile` / `ServerHostState`

來源：`android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/ServerModels.kt`

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

data class ServerStats(
    val posts: Int = 0,
    val replies: Int = 0,
    val users: Int = 0,
    val online: Int = 0,
    val boards: Int = 0,
    val meshMessages: Int = 0,
)

data class ServerBoardSummary(
    val name: String,
    val title: String,
    val moderator: String,
    val moderatorId: String,
    val postCount: Int,
)

data class ServerUserSummary(
    val nodeId: String,
    val name: String,
    val postCount: Int,
    val online: Boolean,
    val banned: Boolean,
    val hasPassword: Boolean,
)

data class ServerAdminSummary(
    val id: Int,
    val username: String,
    val createdAt: String,
)

data class ServerRecentPostSummary(
    val id: Int,
    val board: String,
    val author: String,
    val title: String,
    val replyCount: Int,
    val pushCount: Int,
    val createdAt: String,
)

data class ServerBackupEntry(
    val name: String,
    val path: String,
    val size: Long,
    val createdAt: String,
)

data class ServerDashboard(
    val stats: ServerStats = ServerStats(),
    val boards: List<ServerBoardSummary> = emptyList(),
    val users: List<ServerUserSummary> = emptyList(),
    val dbPath: String = "",
    val backupDir: String = "",
    val lastBackupPath: String = "",
    val backups: List<ServerBackupEntry> = emptyList(),
    val admins: List<ServerAdminSummary> = emptyList(),
    val recentPosts: List<ServerRecentPostSummary> = emptyList(),
    val relayClients: Int = 0,
    val adminClients: Int = 0,
)

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

## 2. `ServerHostStore.kt` - runtime transport settings

來源：`android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/ServerHostStore.kt`

```kotlin
package com.meshtastic.bbs.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object ServerHostStore {
    private val _state = MutableStateFlow(ServerHostState())
    val state: StateFlow<ServerHostState> = _state.asStateFlow()

    fun setStarting() {
        _state.update {
            it.copy(
                isStarting = true,
                isRunning = false,
                status = "??銝?,
                error = null,
            )
        }
    }

    fun setRunning(running: Boolean, status: String? = null) {
        _state.update {
            it.copy(
                isStarting = false,
                isRunning = running,
                status = status ?: if (running) "?瑁?銝? else "撌脣?甇?,
            )
        }
    }

    fun setMeshBound(bound: Boolean, myNodeId: String = "") {
        _state.update {
            it.copy(
                meshBound = bound,
                myNodeId = myNodeId.ifBlank { it.myNodeId },
            )
        }
    }

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
```

## 3. `MeshtasticServerRepository.kt` - `sendResponse()` / send chunk 相關

來源：`android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/MeshtasticServerRepository.kt`

```kotlin
    private val useBroadcastResponsesForDebug: Boolean
        get() = ServerHostStore.currentBroadcastResponsesForDebug()

    private data class ResponseTransportSettings(
        val profile: TransportProfile,
        val chunkSize: Int,
        val delayMs: Long,
    )

    private fun currentResponseTransportSettings(): ResponseTransportSettings =
        ResponseTransportSettings(
            profile = ServerHostStore.currentTransportProfile(),
            chunkSize = ServerHostStore.currentResponseChunkSize(),
            delayMs = ServerHostStore.currentResponseChunkDelayMs(),
        )

    fun sendResponse(destId: String, seq: String, responseJson: String, responseLabel: String = "-") {
        val settings = currentResponseTransportSettings()
        val rawBytes = responseJson.toByteArray(Charsets.UTF_8)
        val compressed = deflate(rawBytes)
        val chunks = compressed.chunkedBytes(settings.chunkSize)
        val total = chunks.size.coerceAtLeast(1)
        val actualChunks = if (chunks.isEmpty()) listOf(ByteArray(0)) else chunks
        val wireTarget = if (useBroadcastResponsesForDebug) DataPacket.ID_BROADCAST else destId
        val directedPacket = wireTarget != DataPacket.ID_BROADCAST
        val responseType = parseResponseType(responseJson)
        onLog(
            "TX_PLAN command=$responseLabel type=$responseType seq=$seq rawBytes=${rawBytes.size} " +
                "compressedBytes=${compressed.size} chunkSize=${settings.chunkSize} totalChunks=$total " +
                "delayMs=${settings.delayMs} selectedProfile=${settings.profile.name} targetNode=$destId " +
                "wireDest=$wireTarget directed=$directedPacket privateLike=$directedPacket"
        )
        actualChunks.forEachIndexed { index, chunk ->
            val header = "MBBS2|$destId|$seq|$index|$total\n".toByteArray(Charsets.UTF_8)
            val pauseMs = settings.delayMs
            val retrySafeKey = "$seq:${index + 1}/$total"
            val packetBytes = header.size + chunk.size
            onLog(
                "TX_CHUNK key=$retrySafeKey seq=$seq idx=${index + 1}/$total chunkBytes=${chunk.size} " +
                    "packetBytes=$packetBytes delayAfterMs=$pauseMs target=$wireTarget logicalDest=$destId " +
                    "profile=${settings.profile.name} directed=$directedPacket privateLike=$directedPacket"
            )
            sendPrivate(
                bytes = header + chunk,
                to = wireTarget,
                logSuccess = false,
                pauseMs = pauseMs,
                txLogLabel = "key=$retrySafeKey seq=$seq idx=${index + 1}/$total target=$wireTarget logicalDest=$destId profile=${settings.profile.name} directed=$directedPacket",
            )
        }
    }
```

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

    private fun parseResponseType(responseJson: String): String =
        runCatching {
            val obj = JSONObject(responseJson)
            obj.optString("type").ifBlank { obj.optString("t") }.ifBlank { "unknown" }
        }.getOrDefault("unknown")
```

## 4. `MeshtasticRepository.kt` - receive MBBS2 / pending chunk / timeout

來源：`android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/data/MeshtasticRepository.kt`

```kotlin
        private const val NODEINFO_APP = 4
        private const val REQUEST_CHUNK_CHARS = 180
        private const val REQUEST_CHUNK_PAUSE_MS = 320L
        private const val PACKET_HOP_LIMIT = 4
        private const val BASE_PENDING_CHUNK_TIMEOUT_MS = 60_000L
        private const val PER_CHUNK_TIMEOUT_MS = 8_000L
        private const val MAX_PENDING_CHUNK_TIMEOUT_MS = 240_000L
        private const val PENDING_CHUNK_CLEANUP_INTERVAL_MS = 5_000L
    }

    private var meshService: IMeshService? = null
    private var eventSink: ((BbsEvent) -> Unit)? = null
    var myNodeId = ""
        private set

    private var currentUser: UserInfo? = null
    private var serverNodeId: String = DataPacket.ID_BROADCAST
    private val seqCounter = AtomicInteger((1000..9999).random())
    private val pending = ConcurrentHashMap<String, PendingChunk>()
    private val completed = LinkedHashSet<String>()
    private val requestSender = Executors.newSingleThreadExecutor()
    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()
    private var heartbeatTask: ScheduledFuture<*>? = null
    private var chunkCleanupTask: ScheduledFuture<*>? = null

    private data class PendingChunk(
        val chunks: MutableMap<Int, String> = ConcurrentHashMap(),
        var total: Int = -1,
        val cmd: String = "",
        val stage: String = "",
        val startedAtMs: Long = SystemClock.elapsedRealtime(),
        var updatedAtMs: Long = SystemClock.elapsedRealtime(),
    )
```

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
        if (entry.chunks.size < total) return null

        val missingBeforeAssemble = missingChunkIndices(total, entry.chunks.keys)
        if (missingBeforeAssemble.isNotEmpty()) {
            comm(
                "CHUNK_WAIT proto=MBBS2 pendingKey=$seq pending=${formatChunkList(total, entry.chunks.keys)} " +
                    "missing=${formatMissingChunks(total, entry.chunks.keys)} reason=assemble_blocked"
            )
            return null
        }

        completed.add(seq)
        pending.remove(seq)
        if (completed.size > 300) completed.remove(completed.first())

        val compressed = ByteArrayOutputStream()
        for (index in 0 until total) {
            val chunk = entry.chunks[index] ?: ""
            compressed.write(Base64.decode(chunk, Base64.DEFAULT))
        }
        return try {
            val event = parseJson(JSONObject(String(zlibDecompress(compressed.toByteArray()), Charsets.UTF_8)))
            if (entry.stage.isNotBlank()) emit(BbsEvent.LoadProgress("", 0, false))
            comm("CHUNK_DONE proto=MBBS2 pendingKey=$seq total=$total from=$fromNode to=$toNode")
            event
        } catch (e: Exception) {
            if (entry.stage.isNotBlank()) emit(BbsEvent.LoadProgress("", 0, false))
            comm(
                "CHUNK_DROP proto=MBBS2 from=$fromNode to=$toNode seq=$seq pendingKey=$seq " +
                    "pending=${formatChunkList(total, entry.chunks.keys)} reason=parse_failed error=${e.message}"
            )
            BbsEvent.ServerError("BBS 回應解析失敗: ${e.message}")
        }
    }
```

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
        if (entry.chunks.size < total) return null

        val missingBeforeAssemble = missingChunkIndices(total, entry.chunks.keys)
        if (missingBeforeAssemble.isNotEmpty()) {
            comm(
                "CHUNK_WAIT proto=BBS_RES pendingKey=$seq pending=${formatChunkList(total, entry.chunks.keys)} " +
                    "missing=${formatMissingChunks(total, entry.chunks.keys)} reason=assemble_blocked"
            )
            return null
        }

        completed.add(seq)
        pending.remove(seq)
        if (completed.size > 300) completed.remove(completed.first())

        val joined = (0 until total).joinToString("") { index -> entry.chunks[index] ?: "" }
        return try {
            val decompressed = String(
                zlibDecompress(Base64.decode(joined, Base64.DEFAULT)),
                Charsets.UTF_8,
            )
            val event = parseJson(JSONObject(decompressed))
            if (entry.stage.isNotBlank()) emit(BbsEvent.LoadProgress("", 0, false))
            comm("CHUNK_DONE proto=BBS_RES pendingKey=$seq total=$total from=$fromNode to=$toNode")
            event
        } catch (e: Exception) {
            if (entry.stage.isNotBlank()) emit(BbsEvent.LoadProgress("", 0, false))
            comm(
                "CHUNK_DROP proto=BBS_RES from=$fromNode to=$toNode seq=$seq pendingKey=$seq " +
                    "pending=${formatChunkList(total, entry.chunks.keys)} reason=parse_failed error=${e.message}"
            )
            BbsEvent.ServerError("BBS 回應解析失敗: ${e.message}")
        }
    }
```

```kotlin
    private fun pruneStalePendingChunks(now: Long) {
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

    private fun pendingChunkTimeoutMs(total: Int): Long {
        if (total <= 0) return BASE_PENDING_CHUNK_TIMEOUT_MS
        return (BASE_PENDING_CHUNK_TIMEOUT_MS + total.toLong() * PER_CHUNK_TIMEOUT_MS)
            .coerceAtMost(MAX_PENDING_CHUNK_TIMEOUT_MS)
    }
```

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

    private fun loadStageFor(cmd: String): String = when (cmd.uppercase()) {
        "LIST" -> "正在接收討論板列表"
        "POSTS" -> "正在接收文章列表"
        "READ" -> "正在接收文章內容"
        else -> ""
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

## 5. `AndroidServerService.kt` - request handling / response dispatch

來源：`android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/server/AndroidServerService.kt`

```kotlin
    private fun handleIncomingRequest(fromId: String, text: String) {
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
```

```kotlin
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

## 6. `BbsViewModel.kt` - READ / LIST / POSTS timeout 或 UI loading 相關

來源：`android/MeshtasticBBS/app/src/main/java/com/meshtastic/bbs/viewmodel/BbsViewModel.kt`

```kotlin
    fun refreshBoards() {
        _state.update { it.copy(isLoading = true) }
        repo?.getBoards()
    }

    fun openBoard(boardName: String) {
        _state.update {
            it.copy(
                currentBoardName = boardName,
                posts = emptyList(),
                postTotal = 0,
                currentPage = 1,
                currentPost = null,
                searchResults = null,
                searchQuery = "",
            )
        }
        _screen.value = Screen.Posts(boardName)
        loadPosts(boardName, 1)
    }

    fun loadPosts(board: String = _state.value.currentBoardName, page: Int = 1) {
        _state.update { it.copy(isLoading = true, currentPage = page) }
        repo?.getPosts(board, page)
        schedulePostListRetry(board, page)
    }

    fun openPost(postId: Int) {
        postReadRetryJob?.cancel()
        _state.update { it.copy(isLoading = true, currentPost = null) }
        _screen.value = Screen.PostView(postId)
        repo?.getPost(postId)
        postReadRetryJob = viewModelScope.launch {
            listOf(5_000L, 9_000L).forEachIndexed { index, waitMs ->
                delay(waitMs)
                val screen = _screen.value
                val state = _state.value
                val stillWaiting = screen is Screen.PostView &&
                    screen.postId == postId &&
                    state.currentPost?.id != postId
                if (!stillWaiting) return@launch
                _state.update { it.copy(isLoading = true) }
                appendDiagnostic("BBS", "文章 $postId 讀取逾時，自動重試 ${index + 1}/2")
                repo?.getPost(postId)
            }
            delay(12_000L)
            val screen = _screen.value
            val state = _state.value
            if (screen is Screen.PostView && screen.postId == postId && state.currentPost?.id != postId) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        toast = "文章讀取逾時，請再試一次",
                    )
                }
            }
        }
    }
```

```kotlin
    private fun schedulePostListRetry(board: String, page: Int) {
        postListRetryJob?.cancel()
        if (page != 1) return
        postListRetryJob = viewModelScope.launch {
            listOf(4_000L, 8_000L).forEachIndexed { index, waitMs ->
                delay(waitMs)
                val screen = _screen.value
                val state = _state.value
                val stillWaiting = screen is Screen.Posts &&
                    screen.boardName == board &&
                    state.currentBoardName == board &&
                    state.posts.isEmpty()
                if (!stillWaiting) return@launch
                _state.update { it.copy(isLoading = true) }
                appendDiagnostic("BBS", "看板 $board 讀取較慢，自動重試 ${index + 1}/2")
                repo?.getPosts(board, page)
            }
            delay(10_000L)
            val screen = _screen.value
            val state = _state.value
            val stillWaiting = screen is Screen.Posts &&
                screen.boardName == board &&
                state.currentBoardName == board &&
                state.posts.isEmpty()
            if (stillWaiting) {
                _state.update { it.copy(isLoading = false, toast = "看板讀取逾時，請重試" ) }
            }
        }
    }
```

```kotlin
            is BbsEvent.LoadProgress -> {
                _state.update {
                    it.copy(
                        loadInProgress = event.active,
                        loadStage = if (event.active) event.stage else "",
                        loadProgress = if (event.active) event.progress.coerceIn(0, 100) else 0,
                    )
                }
            }
```

```kotlin
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
```
