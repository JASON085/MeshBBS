package com.meshtastic.bbs.server

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.meshtastic.bbs.data.MeshPacketReceiver
import org.json.JSONObject
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.service.IMeshService
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater

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
        private const val ADAPTIVE_WARMUP_TRANSFER_COUNT = 3
        private const val ADAPTIVE_SCORE_UP_THRESHOLD = 5
        private const val READ_SAFE_FIRST_CHUNK_DELAY_MS = 1_200L
    }

    private val sender = Executors.newSingleThreadExecutor()
    private val transferExecutor = Executors.newCachedThreadPool()
    private val cacheMaintenanceExecutor = Executors.newSingleThreadScheduledExecutor()
    private var meshService: IMeshService? = null
    private var serviceConn: ServiceConnection? = null
    private var dataReceiver: BroadcastReceiver? = null
    private var meshConnectedReceiver: BroadcastReceiver? = null
    private var myNodeId: String = ""
    private var responseCacheCleanupTask: ScheduledFuture<*>? = null
    private val responseChunkCache = ConcurrentHashMap<String, CachedResponse>()
    private var adaptiveReadSettings = AdaptiveReadSettings(
        level = 0,
        windowSize = 2,
        delayMs = 900L,
        winAckDebounceMs = 800L,
        successStreak = 0,
        successfulTransfers = 0,
        linkQualityScore = 0,
    )
    private var readAdaptiveState = ReadAdaptiveState(
        level = 1,
        delayMs = 1_100L,
        successStreak = 0,
    )

    private val packetHopLimit: Int
        get() = ServerHostStore.currentHopLimit()

    private val useBroadcastResponsesForDebug: Boolean
        get() = ServerHostStore.currentBroadcastResponsesForDebug()

    private val useBroadcastResendForDebug: Boolean
        get() = ServerHostStore.currentBroadcastResendForDebug()

    private data class ResponseTransportSettings(
        val profile: TransportProfile,
        val chunkSize: Int,
        val delayMs: Long,
        val windowSize: Int,
        val winAckDebounceMs: Long,
    )

    private data class ResendTransportSettings(
        val profile: ResendTransportProfile,
        val delayMs: Long,
        val batchSize: Int,
        val windowSize: Int,
    )

    private data class ReadTransportSettings(
        val chunkSize: Int,
        val delayMs: Long,
        val windowSize: Int,
        val winAckDebounceMs: Long,
        val resendWindowSize: Int,
        val headMetaRepeatCount: Int,
        val headMetaIntervalMs: Long,
        val firstChunkDelayMs: Long,
    )

    private data class PostsTransportSettings(
        val chunkSize: Int,
        val delayMs: Long,
        val windowSize: Int,
        val winAckDebounceMs: Long,
        val resendWindowSize: Int,
    )

    private data class AdaptiveReadSettings(
        val level: Int,
        val windowSize: Int,
        val delayMs: Long,
        val winAckDebounceMs: Long,
        val successStreak: Int,
        val successfulTransfers: Int,
        val linkQualityScore: Int,
    )

    private data class ReadAdaptiveState(
        val level: Int,
        val delayMs: Long,
        val successStreak: Int,
    )

    private data class CachedResponse(
        val command: String,
        val logicalDest: String,
        val wireTarget: String,
        val delayMs: Long,
        val windowSize: Int,
        val sha256: String,
        val compressedBytes: Int,
        val startedAtMs: Long,
        val profileName: String,
        val firstChunkDelayMs: Long,
        val readStable: Boolean,
        val adaptiveLevel: Int,
        val commandProfileReason: String,
        val chunks: List<ByteArray>,
        var updatedAtMs: Long,
        var nextSendIndex: Int = 0,
        var lastAckNextIndex: Int = 0,
        var tailMetaSent: Boolean = false,
        var missCount: Int = 0,
        var firstChunkSent: Boolean = false,
        var metadataRecoveryCount: Int = 0,
        val lock: Any = Any(),
    )

    private fun currentResponseTransportSettings(): ResponseTransportSettings =
        ResponseTransportSettings(
            profile = ServerHostStore.currentTransportProfile(),
            chunkSize = ServerHostStore.currentResponseChunkSize(),
            delayMs = ServerHostStore.currentResponseChunkDelayMs(),
            windowSize = ServerHostStore.currentResponseWindowSize(),
            winAckDebounceMs = ServerHostStore.currentWinAckDebounceMs(),
        )

    private fun currentResendTransportSettings(): ResendTransportSettings {
        val profile = ServerHostStore.currentResendTransportProfile()
        return ResendTransportSettings(
            profile = profile,
            delayMs = profile.resendChunkDelayMs,
            batchSize = profile.resendBatchSize,
            windowSize = ServerHostStore.currentResendWindowSize(),
        )
    }

    private fun currentReadTransportSettings(): ReadTransportSettings {
        val profile = ServerHostStore.currentReadTransportProfile()
        return ReadTransportSettings(
            chunkSize = profile.chunkSize,
            delayMs = profile.chunkDelayMs,
            windowSize = profile.windowSize,
            winAckDebounceMs = profile.winAckDebounceMs,
            resendWindowSize = profile.resendWindowSize,
            headMetaRepeatCount = profile.headMetaRepeatCount,
            headMetaIntervalMs = profile.headMetaIntervalMs,
            firstChunkDelayMs = profile.firstChunkDelayMs,
        )
    }

    private fun currentPostsTransportSettings(): PostsTransportSettings {
        val profile = ServerHostStore.currentPostsTransportProfile()
        return PostsTransportSettings(
            chunkSize = profile.chunkSize,
            delayMs = profile.chunkDelayMs,
            windowSize = profile.windowSize,
            winAckDebounceMs = profile.winAckDebounceMs,
            resendWindowSize = profile.resendWindowSize,
        )
    }

    private fun currentAdaptiveReadSettings(): AdaptiveReadSettings = adaptiveReadSettings

    private fun currentReadAdaptiveState(): ReadAdaptiveState = readAdaptiveState

    private fun balancedResponseTransportSettings(): ResponseTransportSettings =
        ResponseTransportSettings(
            profile = TransportProfile.BALANCED,
            chunkSize = TransportProfile.BALANCED.responseChunkSize,
            delayMs = TransportProfile.BALANCED.responseChunkDelayMs,
            windowSize = TransportProfile.BALANCED.responseWindowSize,
            winAckDebounceMs = TransportProfile.BALANCED.winAckDebounceMs,
        )

    fun connect() {
        startResponseCacheCleanup()
        registerReceivers()
        bindService()
    }

    fun disconnect() {
        MeshPacketReceiver.handler = null
        dataReceiver?.let { receiver -> runCatching { context.unregisterReceiver(receiver) } }
        meshConnectedReceiver?.let { receiver -> runCatching { context.unregisterReceiver(receiver) } }
        serviceConn?.let { conn -> runCatching { context.unbindService(conn) } }
        meshService = null
        onMeshState(false, myNodeId)
        serviceConn = null
        dataReceiver = null
        meshConnectedReceiver = null
        stopResponseCacheCleanup()
        responseChunkCache.clear()
    }

    fun sendResponse(destId: String, seq: String, responseJson: String, responseLabel: String = "-") {
        pruneResponseChunkCache()
        val readStable = responseLabel.equals("READ", ignoreCase = true)
        val transportProfile = ServerHostStore.currentTransportProfile()
        val adaptiveMode = transportProfile == TransportProfile.ADAPTIVE
        val adaptiveRead = adaptiveMode && readStable
        val commandAdaptive = adaptiveMode && !readStable
        val generalSettings = currentResponseTransportSettings()
        val balancedSettings = balancedResponseTransportSettings()
        val readSettings = if (readStable && !adaptiveRead) currentReadTransportSettings() else null
        val adaptiveSettings = if (commandAdaptive) currentAdaptiveReadSettings() else null
        val readAdaptiveSettings = if (adaptiveRead) currentReadAdaptiveState() else null
        val chunkSize = when {
            adaptiveRead -> 140
            commandAdaptive -> balancedSettings.chunkSize
            readStable -> readSettings?.chunkSize ?: generalSettings.chunkSize
            else -> generalSettings.chunkSize
        }
        val delayMs = when {
            adaptiveRead -> readAdaptiveSettings?.delayMs ?: 1_100L
            commandAdaptive -> adaptiveSettings?.delayMs ?: balancedSettings.delayMs
            readStable -> readSettings?.delayMs ?: generalSettings.delayMs
            else -> generalSettings.delayMs
        }
        val windowSize = when {
            adaptiveRead -> 1
            commandAdaptive -> adaptiveSettings?.windowSize ?: balancedSettings.windowSize
            readStable -> readSettings?.windowSize ?: generalSettings.windowSize
            else -> generalSettings.windowSize
        }
        val winAckDebounceMs = when {
            adaptiveRead -> 900L
            commandAdaptive -> adaptiveSettings?.winAckDebounceMs ?: balancedSettings.winAckDebounceMs
            readStable -> readSettings?.winAckDebounceMs ?: generalSettings.winAckDebounceMs
            else -> generalSettings.winAckDebounceMs
        }
        val selectedProfileName = when {
            adaptiveRead -> "READ_BALANCED_SAFE"
            commandAdaptive -> "ADAPTIVE"
            readStable -> "READ_STABLE"
            else -> generalSettings.profile.name
        }
        val commandProfileReason = when {
            adaptiveRead -> "read_uses_safe_profile"
            commandAdaptive -> "non_read_uses_global_adaptive"
            readStable -> "read_uses_manual_profile"
            else -> "selected_transport_profile"
        }
        val adaptiveLevel = when {
            adaptiveRead -> readAdaptiveSettings?.level ?: 1
            commandAdaptive -> adaptiveSettings?.level ?: 0
            else -> -1
        }
        val rawBytes = responseJson.toByteArray(Charsets.UTF_8)
        val compressed = deflate(rawBytes)
        val payloadSha256 = sha256Hex(compressed)
        val chunks = compressed.chunkedBytes(chunkSize)
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
            delayMs = delayMs,
            windowSize = windowSize,
            sha256 = payloadSha256,
            compressedBytes = compressed.size,
            startedAtMs = now,
            profileName = selectedProfileName,
            firstChunkDelayMs = when {
                adaptiveRead -> READ_SAFE_FIRST_CHUNK_DELAY_MS
                else -> readSettings?.firstChunkDelayMs ?: 0L
            },
            readStable = readStable,
            adaptiveLevel = adaptiveLevel,
            commandProfileReason = commandProfileReason,
            chunks = packets,
            updatedAtMs = now,
        )
        val firstChunkPacketBytes = packets.firstOrNull()?.size ?: 0
        val lastChunkPacketBytes = packets.lastOrNull()?.size ?: 0
        onLog(
            "TX_PLAN command=$responseLabel type=$responseType seq=$seq rawBytes=${rawBytes.size} " +
                "compressedBytes=${compressed.size} chunkSize=$chunkSize totalChunks=$total " +
                "delayMs=$delayMs windowSize=$windowSize winAckDebounceMs=$winAckDebounceMs " +
                "selectedProfile=$selectedProfileName adaptiveLevel=$adaptiveLevel targetNode=$destId " +
                "commandProfileReason=$commandProfileReason " +
                "sha256=${payloadSha256.take(12)}... " +
                "wireDest=$wireTarget directed=$directedPacket privateLike=$directedPacket"
        )
        if (readStable) {
            onLog(
                "READ_TX_START seq=$seq rawBytes=${rawBytes.size} compressedBytes=${compressed.size} " +
                    "chunkSize=$chunkSize totalChunks=$total firstChunkPacketBytes=$firstChunkPacketBytes " +
                    "lastChunkPacketBytes=$lastChunkPacketBytes targetNode=$destId"
            )
        }
        transferExecutor.execute {
            if (readStable) {
                val headMetaRepeatCount = readSettings?.headMetaRepeatCount ?: 2
                val headMetaIntervalMs = readSettings?.headMetaIntervalMs ?: 800L
                val firstChunkDelayMs = if (adaptiveRead) READ_SAFE_FIRST_CHUNK_DELAY_MS else (readSettings?.firstChunkDelayMs ?: 0L)
                repeat(headMetaRepeatCount) { index ->
                    onLog("READ_META_SENT seq=$seq attempt=${index + 1}/$headMetaRepeatCount target=$wireTarget")
                    sendBatchMetaNow(destId, wireTarget, seq, total, payloadSha256, "head")
                    if (index < headMetaRepeatCount - 1) {
                        Thread.sleep(headMetaIntervalMs)
                    }
                }
                if (firstChunkDelayMs > 0L) {
                    Thread.sleep(firstChunkDelayMs)
                }
            } else {
                sendBatchMeta(destId, wireTarget, seq, total, payloadSha256, "head")
            }
            sendNextWindow(cacheKey, seq, selectedProfileName)
        }
    }

    fun resendResponseChunks(destId: String, seq: String, missingIndexes: List<Int>) {
        pruneResponseChunkCache()
        val cacheKey = responseCacheKey(destId, seq)
        val cached = responseChunkCache[cacheKey]
        if (cached == null) {
            onLog("MISS_DROP reason=cache_miss pendingKey=$cacheKey missing=${missingIndexes.joinToString(",")}")
            return
        }
        val settings = currentResendTransportSettings()
        val resendWireTarget = if (useBroadcastResendForDebug) DataPacket.ID_BROADCAST else cached.wireTarget
        val validIndexes = missingIndexes.distinct().sorted().filter { it in cached.chunks.indices }
        if (validIndexes.isEmpty()) {
            onLog("MISS_DROP reason=invalid_indexes pendingKey=$cacheKey missing=${missingIndexes.joinToString(",")}")
            return
        }
        val resendIndexes = validIndexes.take(minOf(settings.batchSize, settings.windowSize))
        cached.updatedAtMs = System.currentTimeMillis()
        cached.missCount += 1
        cached.metadataRecoveryCount += 1
        if (cached.profileName == "ADAPTIVE") {
            rollbackAdaptive("miss", -2)
        }
        if (cached.profileName == "READ_BALANCED_SAFE") {
            rollbackReadAdaptive("miss")
        }
        onLog(
            "MISS_HIT pendingKey=$cacheKey cmd=${cached.command.ifBlank { "-" }} " +
                "missing=${validIndexes.joinToString(",")} resendChunks=${resendIndexes.size}/${cached.chunks.size} " +
                "cacheTimeoutMs=$RESPONSE_CHUNK_CACHE_TIMEOUT_MS"
        )
        transferExecutor.execute {
            sendBatchMeta(cached.logicalDest, resendWireTarget, seq, cached.chunks.size, cached.sha256, "miss")
            onLog(
                "RESEND_BATCH_START pendingKey=$cacheKey seq=$seq resendBatchSize=${resendIndexes.size} " +
                    "resendDelayMs=${settings.delayMs} resendWindowSize=${settings.windowSize} profile=${settings.profile.name} " +
                    "target=$resendWireTarget directed=${resendWireTarget != DataPacket.ID_BROADCAST}"
            )
            resendIndexes.forEachIndexed { resendOrder, index ->
                onLog(
                    "MISS_RESEND pendingKey=$cacheKey idx=${index + 1}/${cached.chunks.size} " +
                        "resendOrder=${resendOrder + 1}/${resendIndexes.size}"
                )
                sendPrivateNow(
                    bytes = cached.chunks[index],
                    to = resendWireTarget,
                    logSuccess = false,
                    pauseMs = settings.delayMs,
                    txLogLabel = "MISS key=$cacheKey idx=${index + 1}/${cached.chunks.size} target=$resendWireTarget logicalDest=${cached.logicalDest} profile=${settings.profile.name}",
                )
            }
            onLog(
                "RESEND_BATCH_DONE pendingKey=$cacheKey seq=$seq resendBatchSize=${resendIndexes.size} " +
                    "resendDelayMs=${settings.delayMs} resent=${resendIndexes.joinToString(",")}"
            )
            onLog("MISS_DONE pendingKey=$cacheKey resent=${resendIndexes.joinToString(",")}")
        }
    }

    fun acknowledgeWindow(destId: String, seq: String, nextIndex: Int) {
        pruneResponseChunkCache()
        val cacheKey = responseCacheKey(destId, seq)
        val cached = responseChunkCache[cacheKey]
        if (cached == null) {
            onLog("WINACK_DROP reason=cache_miss pendingKey=$cacheKey nextIndex=$nextIndex")
            return
        }
        val boundedNextIndex = nextIndex.coerceIn(0, cached.chunks.size)
        var shouldSendNext = false
        synchronized(cached.lock) {
            cached.updatedAtMs = System.currentTimeMillis()
            if (boundedNextIndex > cached.lastAckNextIndex) {
                cached.lastAckNextIndex = boundedNextIndex
            }
            if (boundedNextIndex > cached.nextSendIndex) {
                cached.nextSendIndex = boundedNextIndex
            }
            shouldSendNext = cached.nextSendIndex < cached.chunks.size && boundedNextIndex >= cached.nextSendIndex
        }
        onLog(
            "WINACK_RX pendingKey=$cacheKey seq=$seq nextIndex=$boundedNextIndex/${cached.chunks.size} " +
                "nextSendIndex=${cached.nextSendIndex}"
        )
        if (boundedNextIndex >= cached.nextSendIndex) {
            transferExecutor.execute { sendNextWindow(cacheKey, seq, cached.profileName) }
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
        val completedAtMs = System.currentTimeMillis()
        val durationMs = (completedAtMs - cached.startedAtMs).coerceAtLeast(1L)
        val effectiveBytesPerSecond = (cached.compressedBytes * 1000L) / durationMs
        onLog(
            "ACK_OK pendingKey=$cacheKey cmd=${cached.command.ifBlank { "-" }} " +
                "totalChunks=${cached.chunks.size} sha256=${cached.sha256.take(12)}..."
        )
        onLog(
            "TRANSFER_DONE cmd=${cached.command.ifBlank { "-" }} seq=$seq chunks=${cached.chunks.size} " +
                "bytes=${cached.compressedBytes} startedAt=${cached.startedAtMs} completedAt=$completedAtMs " +
                "durationMs=$durationMs speed=${effectiveBytesPerSecond}Bps miss=${cached.missCount} " +
                "windowSize=${cached.windowSize} chunkDelayMs=${cached.delayMs} profile=${cached.profileName} " +
                "adaptiveLevel=${cached.adaptiveLevel}"
        )
        if (cached.profileName == "ADAPTIVE") {
            if (cached.missCount == 0 && cached.metadataRecoveryCount == 0) {
                advanceAdaptiveSuccess()
            } else {
                rollbackAdaptive(if (cached.missCount > 0) "miss" else "metadata_recovery", -2)
            }
        }
        if (cached.profileName == "READ_BALANCED_SAFE") {
            if (cached.missCount == 0 && cached.metadataRecoveryCount == 0) {
                advanceReadAdaptiveSuccess()
            } else {
                rollbackReadAdaptive(if (cached.missCount > 0) "miss" else "metadata_recovery")
            }
        }
    }

    fun hasActiveReadTransfer(): Boolean {
        pruneResponseChunkCache()
        val now = System.currentTimeMillis()
        return responseChunkCache.values.any { cached ->
            cached.command.equals("READ", ignoreCase = true) &&
                now - cached.updatedAtMs < 30_000L
        }
    }

    fun sendPlainText(destId: String, text: String) {
        sendPlainTexts(destId, listOf(text))
    }

    fun sendPlainTexts(
        destId: String,
        texts: List<String>,
        initialDelayMs: Long = 0L,
        pauseMs: Long = 3_000L,
        finalDelayMs: Long = 280L,
    ) {
        sender.execute {
            val messages = texts.filter { it.isNotBlank() }
            if (messages.isEmpty()) return@execute
            if (initialDelayMs > 0) {
                Thread.sleep(initialDelayMs)
            }
            messages.forEachIndexed { index, text ->
                val packet = DataPacket(
                    to = destId,
                    bytes = text.toByteArray(Charsets.UTF_8),
                    dataType = DataPacket.TEXT_MESSAGE_APP,
                    from = myNodeId.ifBlank { DataPacket.ID_LOCAL },
                    hopLimit = packetHopLimit,
                    channel = 0,
                    wantAck = false,
                )
                runCatching { meshService?.send(packet) }
                    .onSuccess { onLog("TXT $destId ${text.take(60)}") }
                    .onFailure { onLog("Send text failed: ${it.message}") }
                val delayMs = if (index < messages.lastIndex) pauseMs else finalDelayMs
                Thread.sleep(delayMs)
            }
        }
    }

    private fun registerReceivers() {
        val packetHandler: (Intent) -> Unit = packetHandler@{ intent ->
            val extras = runCatching { intent.extras }.getOrNull()
            val packet = getPayloadPacket(intent)
            val fromId = packet?.from.orEmpty()
                .ifBlank { extras?.let { scanBundleForNodeIds(it).firstOrNull() }.orEmpty() }
            val payload = getPayloadBytes(intent) ?: return@packetHandler
            val isPrivate = intent.action?.contains("PRIVATE_APP") == true ||
                intent.action?.contains(".private") == true
            val text = decodePayload(payload, packet?.dataType, isPrivate) ?: return@packetHandler
            if (text.isNotBlank()) {
                onLog(
                    "RX_REQ from=${fromId.ifBlank { "unknown" }} to=${packet?.to.orEmpty().ifBlank { "-" }} " +
                        "type=${packet?.dataType ?: -1} payloadBytes=${payload.size} privateLike=$isPrivate"
                )
                onLog("REQ ${fromId.ifBlank { "unknown" }} ${text.take(80)}")
                onRequest(fromId.ifBlank { DataPacket.ID_BROADCAST }, text)
            }
        }

        val dynamicReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                packetHandler(intent)
            }
        }
        dataReceiver = dynamicReceiver
        val dataFilter = IntentFilter(ACTION_RECEIVED)
        ContextCompat.registerReceiver(
            context,
            dynamicReceiver,
            dataFilter,
            ContextCompat.RECEIVER_EXPORTED,
        )

        MeshPacketReceiver.handler = packetHandler

        val radioReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                onMeshState(true, myNodeId)
                onLog("Mesh radio connected")
            }
        }
        meshConnectedReceiver = radioReceiver
        val meshFilter = IntentFilter().also { filter ->
            MESH_CONNECTED_ACTIONS.forEach(filter::addAction)
        }
        ContextCompat.registerReceiver(
            context,
            radioReceiver,
            meshFilter,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private fun bindService() {
        serviceConn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                meshService = IMeshService.Stub.asInterface(binder)
                myNodeId = runCatching { meshService?.getMyId().orEmpty() }.getOrDefault("")
                runCatching {
                    meshService?.subscribeReceiver(
                        context.packageName,
                        RECEIVER_CLASS,
                    )
                }.onSuccess {
                    onLog("subscribeReceiver ok: $RECEIVER_CLASS")
                }.onFailure {
                    onLog("subscribeReceiver failed: ${it.message}")
                }
                onMeshState(true, myNodeId)
                onLog("Meshtastic service bound ${myNodeId.ifBlank { "(id pending)" }}")
            }

            override fun onServiceDisconnected(name: ComponentName) {
                meshService = null
                onMeshState(false, myNodeId)
                onLog("Meshtastic service disconnected")
            }
        }

        var bound = context.bindService(
            Intent(MESH_SERVICE).setPackage(MESH_PACKAGE),
            serviceConn!!,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) {
            bound = runCatching {
                context.bindService(
                    Intent().apply {
                        component = ComponentName(MESH_PACKAGE, "$MESH_PACKAGE.service.MeshService")
                    },
                    serviceConn!!,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(false)
        }
        if (!bound) {
            bound = runCatching {
                context.bindService(
                    Intent().apply {
                        component = ComponentName(MESH_PACKAGE, "org.meshtastic.core.service.MeshService")
                    },
                    serviceConn!!,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(false)
        }
        if (!bound) {
            onLog("Unable to bind Meshtastic service")
            onMeshState(false, myNodeId)
        }
    }

    private fun sendPrivate(
        bytes: ByteArray,
        to: String,
        logSuccess: Boolean = true,
        pauseMs: Long = 280L,
        txLogLabel: String = "",
    ) {
        sender.execute { sendPrivateNow(bytes, to, logSuccess, pauseMs, txLogLabel) }
    }

    private fun sendPrivateNow(
        bytes: ByteArray,
        to: String,
        logSuccess: Boolean = true,
        pauseMs: Long = 280L,
        txLogLabel: String = "",
    ) {
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

    private fun parseResponseType(responseJson: String): String =
        runCatching {
            val obj = JSONObject(responseJson)
            obj.optString("type").ifBlank { obj.optString("t") }.ifBlank { "unknown" }
        }.getOrDefault("unknown")

    private fun sendBatchMeta(
        logicalDest: String,
        wireTarget: String,
        seq: String,
        total: Int,
        sha256: String,
        phase: String,
    ) {
        sendBatchMetaInternal(logicalDest, wireTarget, seq, total, sha256, phase, immediate = false)
    }

    private fun sendBatchMetaNow(
        logicalDest: String,
        wireTarget: String,
        seq: String,
        total: Int,
        sha256: String,
        phase: String,
    ) {
        sendBatchMetaInternal(logicalDest, wireTarget, seq, total, sha256, phase, immediate = true)
    }

    private fun sendBatchMetaInternal(
        logicalDest: String,
        wireTarget: String,
        seq: String,
        total: Int,
        sha256: String,
        phase: String,
        immediate: Boolean,
    ) {
        val text = "BBS:META:$seq:$total:$sha256"
        onLog(
            "BATCH_META_TX phase=$phase seq=$seq total=$total sha256=${sha256.take(12)}... " +
                "target=$wireTarget logicalDest=$logicalDest"
        )
        val bytes = BBS_PRIVATE_PREFIX + text.toByteArray(Charsets.UTF_8)
        if (immediate) {
            sendPrivateNow(
                bytes = bytes,
                to = wireTarget,
                logSuccess = false,
                pauseMs = BATCH_META_PAUSE_MS,
                txLogLabel = "META phase=$phase seq=$seq target=$wireTarget logicalDest=$logicalDest",
            )
        } else {
            sendPrivate(
                bytes = bytes,
                to = wireTarget,
                logSuccess = false,
                pauseMs = BATCH_META_PAUSE_MS,
                txLogLabel = "META phase=$phase seq=$seq target=$wireTarget logicalDest=$logicalDest",
            )
        }
    }

    private fun startResponseCacheCleanup() {
        stopResponseCacheCleanup()
        responseCacheCleanupTask = cacheMaintenanceExecutor.scheduleAtFixedRate(
            { pruneResponseChunkCache() },
            RESPONSE_CHUNK_CACHE_CLEANUP_INTERVAL_MS,
            RESPONSE_CHUNK_CACHE_CLEANUP_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun stopResponseCacheCleanup() {
        responseCacheCleanupTask?.cancel(false)
        responseCacheCleanupTask = null
    }

    private fun pruneResponseChunkCache() {
        val now = System.currentTimeMillis()
        responseChunkCache.entries.removeIf { (_, cached) ->
            val expired = now - cached.updatedAtMs > RESPONSE_CHUNK_CACHE_TIMEOUT_MS
            if (expired && cached.profileName == "ADAPTIVE") {
                rollbackAdaptive("timeout", -4)
            }
            if (expired && cached.profileName == "READ_BALANCED_SAFE") {
                rollbackReadAdaptive("timeout")
            }
            expired
        }
    }

    private fun sendNextWindow(cacheKey: String, seq: String, profileName: String) {
        val cached = responseChunkCache[cacheKey] ?: return
        val sendPlan = synchronized(cached.lock) {
            if (cached.nextSendIndex >= cached.chunks.size) {
                if (!cached.tailMetaSent) {
                    cached.tailMetaSent = true
                    Triple(emptyList<Int>(), cached.logicalDest, cached.wireTarget)
                } else {
                    null
                }
            } else {
                val start = cached.nextSendIndex
                val endExclusive = minOf(start + cached.windowSize, cached.chunks.size)
                cached.nextSendIndex = endExclusive
                Triple((start until endExclusive).toList(), cached.logicalDest, cached.wireTarget)
            }
        } ?: return

        val indexes = sendPlan.first
        if (indexes.isEmpty()) {
            sendBatchMeta(cached.logicalDest, cached.wireTarget, seq, cached.chunks.size, cached.sha256, "tail")
            return
        }

        val directedPacket = cached.wireTarget != DataPacket.ID_BROADCAST
        indexes.forEach { index ->
            val retrySafeKey = "$seq:${index + 1}/${cached.chunks.size}"
            val payloadSize = extractPayloadSize(cached.chunks[index])
            if (cached.readStable && index == 0 && !cached.firstChunkSent) {
                onLog("READ_FIRST_CHUNK_SENT seq=$seq idx=1/${cached.chunks.size} packetBytes=${cached.chunks[index].size} chunkBytes=$payloadSize")
            }
            onLog(
                "TX_CHUNK key=$retrySafeKey seq=$seq idx=${index + 1}/${cached.chunks.size} chunkBytes=$payloadSize " +
                    "packetBytes=${cached.chunks[index].size} delayAfterMs=${cached.delayMs} windowSize=${cached.windowSize} " +
                    "target=${cached.wireTarget} logicalDest=${cached.logicalDest} profile=$profileName " +
                    "directed=$directedPacket privateLike=$directedPacket"
            )
            if (cached.readStable) {
                sendPrivateNow(
                    bytes = cached.chunks[index],
                    to = cached.wireTarget,
                    logSuccess = false,
                    pauseMs = cached.delayMs,
                    txLogLabel = "key=$retrySafeKey seq=$seq idx=${index + 1}/${cached.chunks.size} target=${cached.wireTarget} logicalDest=${cached.logicalDest} profile=$profileName directed=$directedPacket",
                )
            } else {
                sendPrivate(
                    bytes = cached.chunks[index],
                    to = cached.wireTarget,
                    logSuccess = false,
                    pauseMs = cached.delayMs,
                    txLogLabel = "key=$retrySafeKey seq=$seq idx=${index + 1}/${cached.chunks.size} target=${cached.wireTarget} logicalDest=${cached.logicalDest} profile=$profileName directed=$directedPacket",
                )
            }
            if (cached.readStable && index == 0) {
                cached.firstChunkSent = true
            }
        }
        val shouldSendTail = synchronized(cached.lock) {
            cached.nextSendIndex >= cached.chunks.size && !cached.tailMetaSent
        }
        if (shouldSendTail) {
            synchronized(cached.lock) {
                if (!cached.tailMetaSent && cached.nextSendIndex >= cached.chunks.size) {
                    cached.tailMetaSent = true
                } else {
                    return
                }
            }
            sendBatchMeta(cached.logicalDest, cached.wireTarget, seq, cached.chunks.size, cached.sha256, "tail")
        }
    }

    private fun advanceAdaptiveSuccess() {
        val previous = adaptiveReadSettings
        val nextStreak = previous.successStreak + 1
        val nextSuccessCount = previous.successfulTransfers + 1
        val nextScore = previous.linkQualityScore + 1
        var newSettings = previous.copy(
            successStreak = nextStreak,
            successfulTransfers = nextSuccessCount,
            linkQualityScore = nextScore,
        )
        val warmupComplete = nextSuccessCount >= ADAPTIVE_WARMUP_TRANSFER_COUNT
        val canAdvance = warmupComplete &&
            nextStreak >= 3 &&
            nextScore > ADAPTIVE_SCORE_UP_THRESHOLD
        if (canAdvance) {
            newSettings = adaptiveSettingsForLevel((previous.level + 1).coerceAtMost(4)).copy(
                successStreak = 0,
                successfulTransfers = nextSuccessCount,
                linkQualityScore = nextScore,
            )
        }
        adaptiveReadSettings = newSettings
        if (hasAdaptiveRateChange(previous, newSettings)) {
            onLog(
                "ADAPTIVE_UP successStreak=$nextStreak score=$nextScore previous=level${previous.level}/window${previous.windowSize}/delay${previous.delayMs} " +
                    "new=level${newSettings.level}/window${newSettings.windowSize}/delay${newSettings.delayMs}"
            )
        }
    }

    private fun rollbackAdaptive(reason: String, scoreDelta: Int) {
        val previous = adaptiveReadSettings
        val base = adaptiveSettingsForLevel(0)
        val newSettings = base.copy(
            successStreak = 0,
            successfulTransfers = 0,
            linkQualityScore = previous.linkQualityScore + scoreDelta,
        )
        adaptiveReadSettings = newSettings
        if (hasAdaptiveRateChange(previous, newSettings)) {
            onLog(
                "ADAPTIVE_DOWN reason=$reason score=${newSettings.linkQualityScore} previous=level${previous.level}/window${previous.windowSize}/delay${previous.delayMs} " +
                    "new=level${newSettings.level}/window${newSettings.windowSize}/delay${newSettings.delayMs}"
            )
        }
    }

    private fun advanceReadAdaptiveSuccess() {
        val previous = readAdaptiveState
        val nextStreak = previous.successStreak + 1
        var next = previous.copy(successStreak = nextStreak)
        if (nextStreak >= 3) {
            next = when (previous.level) {
                0 -> ReadAdaptiveState(level = 1, delayMs = 1_100L, successStreak = 0)
                1 -> ReadAdaptiveState(level = 2, delayMs = 1_000L, successStreak = 0)
                2 -> ReadAdaptiveState(level = 3, delayMs = 900L, successStreak = 0)
                else -> previous.copy(successStreak = nextStreak)
            }
        }
        readAdaptiveState = next
        if (previous.level != next.level || previous.delayMs != next.delayMs) {
            onLog(
                "READ_ADAPTIVE_UP successStreak=$nextStreak previous=level${previous.level}/delay${previous.delayMs} " +
                    "new=level${next.level}/delay${next.delayMs}"
            )
        }
    }

    private fun rollbackReadAdaptive(reason: String) {
        val previous = readAdaptiveState
        val next = ReadAdaptiveState(level = 0, delayMs = 1_200L, successStreak = 0)
        readAdaptiveState = next
        if (previous.level != next.level || previous.delayMs != next.delayMs) {
            onLog(
                "READ_ADAPTIVE_DOWN reason=$reason previous=level${previous.level}/delay${previous.delayMs} " +
                    "new=level${next.level}/delay${next.delayMs}"
            )
        }
    }

    private fun adaptiveSettingsForLevel(level: Int): AdaptiveReadSettings =
        when (level.coerceIn(0, 4)) {
            1 -> AdaptiveReadSettings(level = 1, windowSize = 2, delayMs = 800L, winAckDebounceMs = 800L, successStreak = 0, successfulTransfers = 0, linkQualityScore = 0)
            2 -> AdaptiveReadSettings(level = 2, windowSize = 2, delayMs = 700L, winAckDebounceMs = 700L, successStreak = 0, successfulTransfers = 0, linkQualityScore = 0)
            3 -> AdaptiveReadSettings(level = 3, windowSize = 3, delayMs = 700L, winAckDebounceMs = 700L, successStreak = 0, successfulTransfers = 0, linkQualityScore = 0)
            4 -> AdaptiveReadSettings(level = 4, windowSize = 3, delayMs = 600L, winAckDebounceMs = 600L, successStreak = 0, successfulTransfers = 0, linkQualityScore = 0)
            else -> AdaptiveReadSettings(level = 0, windowSize = 2, delayMs = 900L, winAckDebounceMs = 800L, successStreak = 0, successfulTransfers = 0, linkQualityScore = 0)
        }

    private fun hasAdaptiveRateChange(previous: AdaptiveReadSettings, next: AdaptiveReadSettings): Boolean =
        previous.level != next.level ||
            previous.windowSize != next.windowSize ||
            previous.delayMs != next.delayMs ||
            previous.winAckDebounceMs != next.winAckDebounceMs

    private fun extractPayloadSize(packetBytes: ByteArray): Int {
        val headerEnd = packetBytes.indexOf('\n'.code.toByte())
        return if (headerEnd >= 0) packetBytes.size - headerEnd - 1 else packetBytes.size
    }

    private fun responseCacheKey(destId: String, seq: String): String = "$destId:$seq"

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun decodePayload(bytes: ByteArray, dataType: Int?, isPrivate: Boolean): String? {
        return when {
            startsWithPrefix(bytes, BBS_PRIVATE_PREFIX) ->
                String(bytes, BBS_PRIVATE_PREFIX.size, bytes.size - BBS_PRIVATE_PREFIX.size, Charsets.UTF_8)
            startsWithPrefix(bytes, BBS_BINARY_PREFIX) -> null
            dataType == BBS_APP -> runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            !isPrivate -> runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            else -> null
        }
    }

    private fun getPayloadPacket(intent: Intent): DataPacket? {
        for (key in listOf(EXTRA_PAYLOAD, EXTRA_PACKET, "payload", "packet")) {
            val packet = runCatching {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<DataPacket>(key)
            }.getOrNull()
            if (packet != null) return packet
        }
        return null
    }

    private fun getPayloadBytes(intent: Intent): ByteArray? {
        getPayloadPacket(intent)?.bytes?.takeIf { it.isNotEmpty() }?.let { return it }
        val extras = runCatching { intent.extras }.getOrNull() ?: return null
        for (key in extras.keySet()) {
            val bytes = runCatching { extras.getByteArray(key) }.getOrNull()
            if (bytes?.isNotEmpty() == true) return bytes
        }
        return null
    }

    private fun scanBundleForNodeIds(bundle: android.os.Bundle): List<String> {
        val parcel = android.os.Parcel.obtain()
        return try {
            bundle.writeToParcel(parcel, 0)
            val bytes = parcel.marshall()
            scanParcelStrings(bytes).filter(::isNodeId).distinct()
        } catch (_: Exception) {
            emptyList()
        } finally {
            parcel.recycle()
        }
    }

    private fun startsWithPrefix(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) {
            if (bytes[i] != prefix[i]) return false
        }
        return true
    }

    private fun scanParcelStrings(bytes: ByteArray): List<String> {
        val results = mutableListOf<String>()
        var index = 0
        while (index <= bytes.size - 6) {
            val length = readLittleEndianInt(bytes, index)
            if (length in 1..80) {
                val end = index + 4 + length * 2
                if (end + 1 < bytes.size && bytes[end] == 0.toByte() && bytes[end + 1] == 0.toByte()) {
                    val value = runCatching {
                        String(bytes, index + 4, length * 2, Charsets.UTF_16LE)
                    }.getOrNull()?.trim().orEmpty()
                    if (value.isNotBlank()) {
                        results += value
                        index = end + 2
                        continue
                    }
                }
            }
            index++
        }
        return results
    }

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun isNodeId(value: String): Boolean =
        value.length == 9 && value[0] == '!' && value.drop(1).all { it in '0'..'9' || it in 'a'..'f' }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArrayOutputStream(input.size)
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            out.write(buffer, 0, count)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun ByteArray.chunkedBytes(size: Int): List<ByteArray> {
        if (isEmpty()) return emptyList()
        val chunks = mutableListOf<ByteArray>()
        var start = 0
        while (start < this.size) {
            val end = minOf(start + size, this.size)
            chunks += copyOfRange(start, end)
            start = end
        }
        return chunks
    }
}
