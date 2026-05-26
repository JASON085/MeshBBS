package com.meshtastic.bbs.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.ContextCompat
import com.geeksville.mesh.NodeInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.service.IMeshService
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.Inflater

class MeshtasticRepository(private val context: Context) {

    companion object {
        private enum class ClientTransportProfile(val winAckDebounceMs: Long) {
            STABLE(1_000L),
            BALANCED(800L),
            FAST_TEST(600L),
        }

        const val MESH_PACKAGE = "com.geeksville.mesh"
        const val MESH_SERVICE = "$MESH_PACKAGE.Service"
        const val ACTION_RECEIVED = "$MESH_PACKAGE.DATA_PACKET_RECEIVED"
        const val ACTION_NODE_CHANGE = "$MESH_PACKAGE.NODE_CHANGE"
        const val EXTRA_PACKET = "$MESH_PACKAGE.DATA_PACKET"
        const val EXTRA_PAYLOAD = "$MESH_PACKAGE.Payload"
        const val BBS_APP = 257
        const val BUILD = "b0604aa"
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
        private const val READ_FIRST_CHUNK_TIMEOUT_MS = 8_000L
        private const val MISSING_CHUNK_REQUEST_IDLE_MS = 4_000L
        private const val MISSING_CHUNK_REQUEST_INTERVAL_MS = 4_000L
        private const val MAX_MISSING_CHUNK_REQUESTS = 3
        private const val MAX_MISSING_CHUNK_BATCH_SIZE = 1
        private val CLIENT_TRANSPORT_PROFILE = ClientTransportProfile.BALANCED
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
        var sourceNodeId: String = "",
        var expectedSha256: String = "",
        var proto: PendingProto = PendingProto.UNKNOWN,
        var missRequestCount: Int = 0,
        var lastMissRequestAtMs: Long = 0L,
        var lastWindowAckAtMs: Long = 0L,
        var lastWindowAckNextIndex: Int = 0,
        var metaReceivedAtMs: Long = 0L,
    )

    private enum class PendingProto {
        UNKNOWN,
        MBBS2,
        BBS_RES,
    }

    private data class NodeBundleMeta(
        val nodeId: String,
        val longName: String = "",
        val shortName: String = "",
    )

    private data class ProtoUser(
        val id: String = "",
        val longName: String = "",
        val shortName: String = "",
    )

    private fun emit(event: BbsEvent) {
        eventSink?.invoke(event)
    }

    private fun debug(message: String) {
        emit(BbsEvent.NodeFetchDebug(message))
    }

    private fun comm(message: String) {
        emit(BbsEvent.BssCommLog(message))
    }

    fun connect(): Flow<BbsEvent> = callbackFlow {
        eventSink = { event -> trySend(event) }
        debug("connect() start, build=$BUILD")
        val dataReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                debug("broadcast received: action=${intent.action ?: "(none)"}")
                collectIntentEvents(intent).forEach { event ->
                    if (event is BbsEvent.NodeChanged) NodeCacheStore.merge(context, listOf(event.node))
                    trySend(event)
                }
            }
        }

        runCatching {
            ContextCompat.registerReceiver(
                context,
                dataReceiver,
                IntentFilter(ACTION_RECEIVED),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.onSuccess {
            debug("registerReceiver ok: $ACTION_RECEIVED")
        }.onFailure { error ->
            debug("registerReceiver failed: ${error.javaClass.simpleName}: ${error.message}")
        }

        val serviceConn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                debug("onServiceConnected: ${name.className}")
                meshService = IMeshService.Stub.asInterface(binder)
                myNodeId = runCatching { meshService?.getMyId().orEmpty() }
                    .onFailure { debug("getMyId failed: ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrDefault("")
                debug("myNodeId=${myNodeId.ifBlank { "(blank)" }}")

                val radioConnected = myNodeId.isNotBlank()
                debug("radioConnected inferred=$radioConnected")

                runCatching {
                    meshService?.subscribeReceiver(
                        context.packageName,
                        "com.meshtastic.bbs.data.MeshPacketReceiver",
                    )
                }.onSuccess {
                    debug("subscribeReceiver ok")
                }.onFailure { error ->
                    debug("subscribeReceiver failed: ${error.javaClass.simpleName}: ${error.message}")
                }

                comm("Meshtastic connected [$BUILD]")
                trySend(BbsEvent.Connected(myNodeId))
                trySend(BbsEvent.MeshStatus(true, myNodeId))
                val nodes = loadMeshNodes()
                debug("loadMeshNodes() -> ${nodes.size} node(s)")
                nodes.forEach { trySend(BbsEvent.NodeChanged(it)) }
                NodeCacheStore.merge(context, nodes)
                trySend(BbsEvent.PreflightResult(radioConnected = radioConnected, bbsFound = nodes.isNotEmpty()))
            }

            override fun onServiceDisconnected(name: ComponentName) {
                debug("onServiceDisconnected: ${name.className}")
                meshService = null
                trySend(BbsEvent.MeshStatus(false, myNodeId))
                trySend(BbsEvent.Disconnected)
            }
        }

        MeshPacketReceiver.handler = { intent ->
            debug("handler received: action=${intent.action ?: "(none)"}")
            collectIntentEvents(intent).forEach { event ->
                if (event is BbsEvent.NodeChanged) NodeCacheStore.merge(context, listOf(event.node))
                trySend(event)
            }
        }

        debug("binding Meshtastic service")
        startChunkCleanup()
        val bound = bindService(serviceConn)
        debug("bindService result=$bound")
        if (!bound) {
            val installed = runCatching {
                context.packageManager.getPackageInfo(MESH_PACKAGE, 0)
                true
            }.getOrDefault(false)
            val msg = if (installed) {
                "Meshtastic APP 已安裝，但服務無法連接。\n請重新開啟 Meshtastic APP 並確認裝置已連線後再試。"
            } else {
                "找不到 Meshtastic APP，\n請確認已安裝並開啟 Meshtastic。"
            }
            debug("bind failed, installed=$installed")
            trySend(BbsEvent.ConnectError(msg))
            channel.close()
            return@callbackFlow
        }

        awaitClose {
            debug("connect() closed")
            stopHeartbeat()
            stopChunkCleanup()
            MeshPacketReceiver.handler = null
            runCatching { context.unregisterReceiver(dataReceiver) }
            runCatching { context.unbindService(serviceConn) }
            meshService = null
            eventSink = null
        }
    }

    fun disconnect() {
        debug("disconnect() called")
        stopHeartbeat()
        stopChunkCleanup()
        meshService = null
    }

    fun setServerNode(nodeId: String) {
        serverNodeId = nodeId.ifBlank { DataPacket.ID_BROADCAST }
        debug("setServerNode -> $serverNodeId")
    }

    private fun bindService(serviceConn: ServiceConnection): Boolean {
        debug("bind attempt 1: action service")
        if (context.bindService(Intent(MESH_SERVICE).setPackage(MESH_PACKAGE), serviceConn, Context.BIND_AUTO_CREATE)) {
            return true
        }
        debug("bind attempt 2: com.geeksville.mesh.service.MeshService")
        if (runCatching {
                context.bindService(
                    Intent().apply {
                        component = ComponentName(MESH_PACKAGE, "$MESH_PACKAGE.service.MeshService")
                    },
                    serviceConn,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(false)
        ) {
            return true
        }
        debug("bind attempt 3: org.meshtastic.core.service.MeshService")
        return runCatching {
            context.bindService(
                Intent().apply {
                    component = ComponentName(MESH_PACKAGE, "org.meshtastic.core.service.MeshService")
                },
                serviceConn,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
    }

    private fun extractPacket(intent: Intent): DataPacket? {
        return try {
            intent.getParcelableExtra(EXTRA_PACKET, DataPacket::class.java)
                ?: intent.getParcelableExtra(EXTRA_PAYLOAD, DataPacket::class.java)
        } catch (error: Exception) {
            debug("extractPacket modern API failed: ${error.javaClass.simpleName}: ${error.message}")
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<DataPacket>(EXTRA_PACKET)
                ?: intent.getParcelableExtra<DataPacket>(EXTRA_PAYLOAD)
        }
    }

    private fun collectIntentEvents(intent: Intent): List<BbsEvent> {
        val events = mutableListOf<BbsEvent>()
        parseNodeChange(intent)?.let { node ->
            events += BbsEvent.NodeChanged(node)
        }
        val packet = extractPacket(intent)
        if (packet == null) {
            if (intent.action?.contains("NODE_CHANGE") == true) {
                debug("NODE_CHANGE without DataPacket handled")
            } else {
                debug("intent had no DataPacket payload")
            }
            return events
        }
        packet.toObservedNode()?.let { node ->
            if (events.none { it is BbsEvent.NodeChanged && it.node.nodeId == node.nodeId }) {
                events += BbsEvent.NodeChanged(node)
            }
        }
        handlePacket(packet)?.let { events += it }
        return events
    }

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

    private fun parseNodeChange(intent: Intent): MeshNode? {
        if (intent.action?.contains("NODE_CHANGE") != true) return null
        val extras = runCatching { intent.extras }.getOrNull() ?: return null
        extras.classLoader = NodeInfo::class.java.classLoader
        debug("NODE_CHANGE extras=${extras.keySet().joinToString(",")}")

        for (key in extras.keySet()) {
            val node = runCatching {
                @Suppress("DEPRECATION")
                extras.getParcelable(key) as? NodeInfo
            }.getOrNull()
            if (node?.nodeId?.isNotBlank() == true) {
                debug("NODE_CHANGE node=${node.nodeId} name=${node.displayName}")
                return node.toMeshNode()?.takeUnless { it.nodeId == myNodeId }
            }
        }

        val meta = scanBundleForNodeMeta(extras)
        if (meta != null) {
            debug(
                "NODE_CHANGE fallback nodeId=${meta.nodeId} long=${meta.longName.ifBlank { "-" }} short=${meta.shortName.ifBlank { "-" }}"
            )
            if (meta.nodeId == myNodeId) return null
            return MeshNode(
                nodeId = meta.nodeId,
                displayName = meta.longName.ifBlank { meta.shortName.ifBlank { meta.nodeId } },
                shortName = meta.shortName,
                lastSeen = System.currentTimeMillis(),
            )
        }
        return null
    }

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
        val firstChunkWasMissing = !entry.chunks.containsKey(0)
        entry.chunks[idx] = Base64.encodeToString(bytes.copyOfRange(headerEnd + 1, bytes.size), Base64.NO_WRAP)
        entry.total = total
        comm(
            "CHUNK_RX proto=MBBS2 from=$fromNode to=$toNode seq=$seq idx=${idx + 1}/$total payloadSize=$payloadSize " +
                "pendingKey=$seq pending=${formatChunkList(total, entry.chunks.keys)} " +
                "missing=${formatMissingChunks(total, entry.chunks.keys)} timeoutMs=${pendingChunkTimeoutMs(total)}"
        )
        if (entry.cmd.equals("READ", ignoreCase = true) && idx == 0 && firstChunkWasMissing) {
            comm("READ_FIRST_CHUNK_RX seq=$seq idx=1/$total payloadSize=$payloadSize from=$fromNode")
        }
        maybeSendWindowAck(seq, entry, now)
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
        entry.metaReceivedAtMs = entry.updatedAtMs
        entry.sourceNodeId = fromNode.ifBlank { entry.sourceNodeId }
        if (entry.total <= 0) entry.total = total
        entry.expectedSha256 = sha256
        comm(
            "BATCH_META_RX from=$fromNode to=$toNode seq=$seq expectedTotal=$total " +
                "sha256=${sha256.take(12)}..."
        )
        if (entry.cmd.equals("READ", ignoreCase = true)) {
            comm("READ_META_RX seq=$seq total=$total from=$fromNode")
        }
        return tryFinalizePending(seq, entry, fromNode, toNode)
    }

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
        val firstChunkWasMissing = !entry.chunks.containsKey(0)
        entry.chunks[idx] = data
        entry.total = total
        comm(
            "CHUNK_RX proto=BBS_RES from=$fromNode to=$toNode seq=$seq idx=${idx + 1}/$total payloadSize=${data.length} " +
                "pendingKey=$seq pending=${formatChunkList(total, entry.chunks.keys)} " +
                "missing=${formatMissingChunks(total, entry.chunks.keys)} timeoutMs=${pendingChunkTimeoutMs(total)}"
        )
        if (entry.cmd.equals("READ", ignoreCase = true) && idx == 0 && firstChunkWasMissing) {
            comm("READ_FIRST_CHUNK_RX seq=$seq idx=1/$total payloadSize=${data.length} from=$fromNode")
        }
        maybeSendWindowAck(seq, entry, now)
        if (entry.stage.isNotBlank()) {
            val progress = (20 + ((entry.chunks.size * 75) / total.coerceAtLeast(1))).coerceAtMost(95)
            emit(BbsEvent.LoadProgress("${entry.stage} ${entry.chunks.size}/$total", progress, true))
        }
        return tryFinalizePending(seq, entry, fromNode, toNode)
    }

    private fun zlibDecompress(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 3)
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray()
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
            if (total <= 0 || entry.chunks.size >= total) return@forEach
            if (entry.cmd.equals("READ", ignoreCase = true) &&
                entry.expectedSha256.isNotBlank() &&
                entry.chunks.isEmpty()
            ) {
                if (entry.missRequestCount >= MAX_MISSING_CHUNK_REQUESTS) return@forEach
                if (now - entry.metaReceivedAtMs < READ_FIRST_CHUNK_TIMEOUT_MS) return@forEach
                if (entry.lastMissRequestAtMs > 0L && now - entry.lastMissRequestAtMs < MISSING_CHUNK_REQUEST_INTERVAL_MS) {
                    return@forEach
                }
                comm(
                    "READ_NO_FIRST_CHUNK_TIMEOUT seq=$seq total=$total idleMs=${now - entry.metaReceivedAtMs} " +
                        "hasMeta=${entry.expectedSha256.isNotBlank()} target=${entry.sourceNodeId.ifBlank { serverNodeId }}"
                )
                sendMissingChunkRequest(seq, entry, listOf(0), now, "first_chunk_timeout")
                return@forEach
            }
            if (entry.chunks.isEmpty()) return@forEach
            if (entry.missRequestCount >= MAX_MISSING_CHUNK_REQUESTS) return@forEach
            if (now - entry.updatedAtMs < MISSING_CHUNK_REQUEST_IDLE_MS) return@forEach
            if (entry.lastMissRequestAtMs > 0L && now - entry.lastMissRequestAtMs < MISSING_CHUNK_REQUEST_INTERVAL_MS) {
                return@forEach
            }
            val missing = missingChunkIndices(total, entry.chunks.keys)
            if (missing.isEmpty()) return@forEach
            val requestBatch = missing.take(MAX_MISSING_CHUNK_BATCH_SIZE)
            val idleMs = now - entry.updatedAtMs
            comm(
                "CHUNK_STALL seq=$seq cmd=${entry.cmd.ifBlank { "-" }} total=$total " +
                    "received=${formatChunkList(total, entry.chunks.keys)} " +
                    "missing=${formatMissingChunks(total, entry.chunks.keys)} idleMs=$idleMs " +
                    "missCount=${entry.missRequestCount} hasMeta=${entry.expectedSha256.isNotBlank()}"
            )
            sendMissingChunkRequest(seq, entry, requestBatch, now, "missing")
        }
    }

    private fun maybeSendWindowAck(seq: String, entry: PendingChunk, now: Long) {
        val total = entry.total
        if (total <= 1) return
        val targetNode = entry.sourceNodeId.ifBlank { serverNodeId }
        if (targetNode.isBlank() || targetNode == DataPacket.ID_BROADCAST) return
        val nextIndex = contiguousReceivedCount(total, entry.chunks.keys)
        if (nextIndex <= entry.lastWindowAckNextIndex) return
        if (entry.lastWindowAckAtMs > 0L &&
            now - entry.lastWindowAckAtMs < CLIENT_TRANSPORT_PROFILE.winAckDebounceMs
        ) return
        comm(
            "SEND BBS:WINACK seq=$seq cmd=${entry.cmd.ifBlank { "-" }} to=$targetNode " +
                "nextIndex=$nextIndex/$total received=${formatChunkList(total, entry.chunks.keys)}"
        )
        sendRaw("BBS:WINACK:$seq:$nextIndex", targetNode)
        entry.lastWindowAckAtMs = now
        entry.lastWindowAckNextIndex = nextIndex
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

    private fun buildCompressedBytes(entry: PendingChunk, total: Int): ByteArray {
        val compressed = ByteArrayOutputStream()
        for (index in 0 until total) {
            val chunk = entry.chunks[index] ?: ""
            compressed.write(Base64.decode(chunk, Base64.DEFAULT))
        }
        return compressed.toByteArray()
    }

    private fun sendMissingChunkRequest(
        seq: String,
        entry: PendingChunk,
        indexes: List<Int>,
        now: Long,
        reason: String,
    ) {
        val total = entry.total
        if (indexes.isEmpty() || entry.missRequestCount >= MAX_MISSING_CHUNK_REQUESTS) return
        val targetNode = entry.sourceNodeId.ifBlank { serverNodeId }
        if (targetNode.isBlank() || targetNode == DataPacket.ID_BROADCAST) return
        val missingSpec = indexes.sorted().take(MAX_MISSING_CHUNK_BATCH_SIZE).joinToString(",")
        comm(
            "SEND BBS:MISS seq=$seq cmd=${entry.cmd.ifBlank { "-" }} to=$targetNode " +
                "total=$total received=${formatChunkList(total, entry.chunks.keys)} " +
                "missing=${formatMissingChunks(total, entry.chunks.keys)} " +
                "retry=${entry.missRequestCount + 1}/$MAX_MISSING_CHUNK_REQUESTS targetNode=$targetNode spec=$missingSpec reason=$reason"
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

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun pendingChunkTimeoutMs(total: Int): Long {
        if (total <= 0) return BASE_PENDING_CHUNK_TIMEOUT_MS
        return (BASE_PENDING_CHUNK_TIMEOUT_MS + total.toLong() * PER_CHUNK_TIMEOUT_MS)
            .coerceAtMost(MAX_PENDING_CHUNK_TIMEOUT_MS)
    }

    private fun formatChunkList(total: Int, keys: Collection<Int>): String =
        if (total <= 0) "[]"
        else keys.sorted().joinToString(prefix = "[", postfix = "]") { "${it + 1}/$total" }

    private fun missingChunkIndices(total: Int, keys: Collection<Int>): List<Int> =
        if (total <= 0) emptyList() else (0 until total).filterNot(keys.toSet()::contains)

    private fun contiguousReceivedCount(total: Int, keys: Collection<Int>): Int {
        if (total <= 0) return 0
        val keySet = keys.toSet()
        var nextIndex = 0
        while (nextIndex < total && keySet.contains(nextIndex)) {
            nextIndex += 1
        }
        return nextIndex
    }

    private fun formatMissingChunks(total: Int, keys: Collection<Int>): String =
        missingChunkIndices(total, keys).joinToString(prefix = "[", postfix = "]") { "${it + 1}/$total" }

    private fun zlibCompress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size)
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun parseJson(obj: JSONObject): BbsEvent? {
        val type = obj.optString("type")
        val compactType = obj.optString("t")

        return when {
            type == "login_ok" -> BbsEvent.LoginOk(
            nodeId = obj.optString("node_id"),
            name = obj.optString("name"),
            onlineUsers = obj.optJSONArray("online_users")?.toOnlineUsers() ?: emptyList(),
            isAdmin = obj.optBoolean("is_admin"),
            )
            type == "login_error" -> BbsEvent.LoginError(obj.optString("msg"))
            type == "boards" -> BbsEvent.BoardsLoaded(
            boards = obj.optJSONArray("boards")?.toBoards() ?: emptyList(),
            onlineUsers = obj.optJSONArray("online_users")?.toOnlineUsers() ?: emptyList(),
            )
            type == "posts" -> obj.optString("board").let { board ->
                BbsEvent.PostsLoaded(
                board = board,
                posts = obj.optJSONArray("posts")?.toPosts(board) ?: emptyList(),
                total = obj.optInt("total"),
                page = obj.optInt("page", 1),
                )
            }
            type == "post" -> parsePostLoaded(obj)
            type == "post_created" -> BbsEvent.PostCreated(obj.optInt("post_id"))
            type == "reply_created" -> BbsEvent.ReplyCreated(obj.optInt("reply_id"))
            type == "reply_edited" || type == "mod_reply_edited" -> BbsEvent.ReplyEdited(obj.optInt("reply_id"))
            type == "reply_deleted" || type == "mod_reply_deleted" -> BbsEvent.ReplyDeleted(obj.optInt("reply_id"))
            type == "post_edited" || type == "mod_post_edited" -> BbsEvent.PostEdited(obj.optInt("post_id"))
            type == "post_deleted" || type == "mod_post_deleted" -> BbsEvent.PostDeleted(
            postId = obj.optInt("post_id"),
            board = obj.optString("board"),
            )
            type == "push_updated" -> BbsEvent.PushUpdated(
            postId = obj.optInt("post_id"),
            pushCount = obj.optInt("push_count"),
            pushed = obj.optBoolean("pushed"),
            )
            type == "password_changed" -> BbsEvent.PasswordChanged(obj.optString("name"))
            type == "search_results" -> BbsEvent.SearchResults(
            posts = obj.optJSONArray("posts")?.toPosts() ?: emptyList(),
            query = obj.optString("query"),
            total = obj.optInt("total"),
            )
            type == "user_join" -> BbsEvent.UserJoin(obj.optString("name"))
            type == "user_leave" -> BbsEvent.UserLeave(obj.optString("name"))
            type == "new_post_notice" -> BbsEvent.NewPostNotice(
            board = obj.optString("board"),
            author = obj.optString("author"),
            title = obj.optString("title"),
            )
            type == "force_logout" -> BbsEvent.ForceLogout(obj.optString("msg"))
            type == "error" -> BbsEvent.ServerError(obj.optString("msg"))
            compactType == "B" -> BbsEvent.BoardsLoaded(
                boards = obj.optJSONArray("b")?.toBoards() ?: emptyList(),
                onlineUsers = obj.optJSONArray("u")?.toOnlineUsers() ?: emptyList(),
            )
            compactType == "P" -> {
                val board = obj.optString("b")
                BbsEvent.PostsLoaded(
                    board = board,
                    posts = obj.optJSONArray("p")?.toPosts(board) ?: emptyList(),
                    total = obj.optInt("n"),
                    page = obj.optInt("g", 1),
                )
            }
            compactType == "R" -> parsePostLoaded(obj)
            else -> null
        }
    }

    private fun parsePostLoaded(obj: JSONObject): BbsEvent.PostLoaded? {
        val replies = obj.optJSONArray("replies")?.toReplies()
            ?: obj.optJSONArray("r")?.toReplies()
            ?: emptyList()

        obj.optJSONObject("post")?.let { post ->
            return BbsEvent.PostLoaded(
                PostDetail(
                    id = post.optInt("id"),
                    authorId = post.optString("author_id"),
                    author = post.optString("author"),
                    title = post.optString("title"),
                    body = post.optString("body"),
                    board = post.optString("board"),
                    replyCount = post.optInt("reply_count", replies.size),
                    pushCount = post.optInt("push_count"),
                    pushed = post.optBoolean("pushed"),
                    createdAt = post.optString("created_at"),
                    replies = replies,
                )
            )
        }

        obj.optJSONArray("p")?.let { compactPost ->
            return BbsEvent.PostLoaded(compactPost.toPostDetailCompact(replies))
        }

        return null
    }

    fun setCurrentUser(user: UserInfo) {
        currentUser = user
        startHeartbeat()
    }

    fun login(name: String, password: String) {
        sendReq("LOGIN", "$name:${hashPassword(password)}")
    }

    fun getBoards() = sendReq("LIST")

    fun getPosts(board: String, page: Int = 1) = sendReq("POSTS", "$board:$page")

    fun getPost(postId: Int) = sendReq("READ", postId.toString())

    fun createPost(board: String, title: String, body: String) =
        sendCompressedReq("POST", "$board:${currentUser?.name ?: ""}:$title:$body", "正在壓縮文章")

    fun createReply(postId: Int, body: String) =
        sendCompressedReq("REPLY", "$postId:${currentUser?.name ?: ""}:$body", "正在壓縮回覆")

    fun editPost(postId: Int, title: String, body: String) =
        sendCompressedReq("EDIT", "$postId:$title:$body", "正在壓縮文章")

    fun editReply(replyId: Int, body: String) =
        sendCompressedReq("EDITREP", "$replyId:$body", "正在壓縮回覆")

    fun deletePost(postId: Int) = sendReq("DEL", postId.toString())

    fun deleteReply(replyId: Int) = sendReq("DELREP", replyId.toString())

    fun togglePush(postId: Int) = sendReq("PUSH", postId.toString())

    fun changePassword(password: String) = sendReq("CHPASS", hashPassword(password))

    fun sendMesh(text: String) {
        val author = currentUser?.name?.ifBlank { null } ?: myNodeId.ifBlank { "匿名" }
        sendMeshChat(author, text)
    }

    fun searchPosts(query: String, field: String = "title", board: String? = null) =
        sendReq("SEARCH", "$field:${board ?: ""}:$query")

    fun logout(nodeId: String, name: String) {
        stopHeartbeat()
        sendReq("LOGOUT", name)
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        val user = currentUser ?: return
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(
            {
                if (meshService == null) return@scheduleAtFixedRate
                if (hasActiveLoadTransfer()) return@scheduleAtFixedRate
                sendReq("HEARTBEAT", user.name)
            },
            60L,
            60L,
            TimeUnit.SECONDS,
        )
    }

    private fun stopHeartbeat() {
        heartbeatTask?.cancel(false)
        heartbeatTask = null
    }

    private fun hasActiveLoadTransfer(): Boolean =
        pending.values.any { entry ->
            entry.cmd.uppercase() in setOf("LIST", "POSTS", "READ") &&
                entry.total > 0 &&
                entry.chunks.isNotEmpty() &&
                entry.chunks.size < entry.total
        }

    private fun startChunkCleanup() {
        stopChunkCleanup()
        chunkCleanupTask = heartbeatExecutor.scheduleAtFixedRate(
            { pruneStalePendingChunks(SystemClock.elapsedRealtime()) },
            PENDING_CHUNK_CLEANUP_INTERVAL_MS,
            PENDING_CHUNK_CLEANUP_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun stopChunkCleanup() {
        chunkCleanupTask?.cancel(false)
        chunkCleanupTask = null
    }

    private fun nextSeq() = seqCounter.getAndIncrement().toString()

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

    private fun sendPlainText(text: String, to: String = DataPacket.ID_BROADCAST) {
        debug("sendPlainText to=$to chars=${text.length}")
        runCatching {
            meshService?.send(
                DataPacket(
                    to = to,
                    bytes = text.toByteArray(Charsets.UTF_8),
                    dataType = DataPacket.TEXT_MESSAGE_APP,
                    wantAck = false,
                    hopLimit = PACKET_HOP_LIMIT,
                    channel = 0,
                )
            )
        }.onSuccess { packetId ->
            debug("meshService.send text ok packetId=$packetId")
        }.onFailure { error ->
            debug("meshService.send text failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun sendMeshChat(author: String, text: String, to: String = DataPacket.ID_BROADCAST) {
        val payload = JSONObject()
            .put("author", author)
            .put("text", text)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val bytes = MESH_CHAT_PREFIX + payload
        debug("sendMeshChat to=$to chars=${text.length}")
        runCatching {
            meshService?.send(
                DataPacket(
                    to = to,
                    bytes = bytes,
                    dataType = BBS_APP,
                    wantAck = false,
                    hopLimit = PACKET_HOP_LIMIT,
                    channel = 0,
                )
            )
        }.onSuccess { packetId ->
            debug("meshService.send chat ok packetId=$packetId")
        }.onFailure { error ->
            debug("meshService.send chat failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun parseMeshChat(bytes: ByteArray, fromId: String): BbsEvent.NewMeshMessage? {
        if (!bytes.startsWithPrefix(MESH_CHAT_PREFIX)) return null
        if (fromId.isNotBlank() && fromId == myNodeId) return null
        return runCatching {
            val obj = JSONObject(String(bytes.copyOfRange(MESH_CHAT_PREFIX.size, bytes.size), Charsets.UTF_8))
            val author = obj.optString("author").ifBlank { fromId.takeLast(6) }
            val text = obj.optString("text")
            if (text.isBlank()) null else BbsEvent.NewMeshMessage(MeshMessage(fromId, author, text, ""))
        }.getOrNull()
    }

    private fun loadMeshNodes(): List<MeshNode> {
        val cached = NodeCacheStore.load(context)
        debug("cached nodes=${cached.size}")
        val live = runCatching {
            meshService?.getNodes()
                ?.filterNotNull()
                ?.mapNotNull { it.toMeshNode() }
                .orEmpty()
        }.onSuccess {
            debug("getNodes() live nodes=${it.size}")
        }.onFailure { error ->
            debug("getNodes() failed: ${error.javaClass.simpleName}: ${error.message}")
        }.getOrDefault(emptyList())

        val merged = (cached + live)
            .filter { it.nodeId.isNotBlank() && it.nodeId != myNodeId }
            .distinctBy { it.nodeId }
        debug("merged nodes=${merged.size}")
        return merged
    }

    private fun DataPacket.toObservedNode(): MeshNode? {
        val fromId = from.orEmpty()
        if (fromId.isBlank() || fromId == DataPacket.ID_LOCAL || fromId == myNodeId) return null
        return MeshNode(
            nodeId = fromId,
            displayName = fromId,
            shortName = "",
            lastSeen = System.currentTimeMillis(),
        )
    }

    private fun Any.toMeshNode(): MeshNode? {
        if (this is NodeInfo) {
            return MeshNode(nodeId, displayName, shortName, System.currentTimeMillis())
        }

        val id = readStringProperty("nodeId", "userId", "id")
            ?: readIntProperty("num")?.let { "!%08x".format(it) }
            ?: return null
        val longName = readStringProperty("displayName", "longName", "name").orEmpty()
        val shortName = readStringProperty("shortName").orEmpty()
        return MeshNode(
            nodeId = id,
            displayName = longName.ifBlank { shortName.ifBlank { id } },
            shortName = shortName,
            lastSeen = System.currentTimeMillis(),
        )
    }

    private fun Any.readStringProperty(vararg names: String): String? {
        for (name in names) {
            runCatching {
                val getter = "get" + name.replaceFirstChar { it.uppercaseChar() }
                javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }
                    ?.invoke(this) as? String
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }

            runCatching {
                val field = javaClass.declaredFields.firstOrNull { it.name == name } ?: return@runCatching null
                field.isAccessible = true
                field.get(this) as? String
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun Any.readIntProperty(name: String): Int? {
        runCatching {
            val getter = "get" + name.replaceFirstChar { it.uppercaseChar() }
            javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }
                ?.invoke(this) as? Int
        }.getOrNull()?.let { return it }

        return runCatching {
            val field = javaClass.declaredFields.firstOrNull { it.name == name } ?: return@runCatching null
            field.isAccessible = true
            field.get(this) as? Int
        }.getOrNull()
    }

    private fun hashPassword(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun scanBundleForNodeIds(bundle: android.os.Bundle): List<String> {
        val parcel = android.os.Parcel.obtain()
        return try {
            bundle.writeToParcel(parcel, 0)
            val bytes = parcel.marshall()
            val ids = mutableListOf<String>()
            var index = 0
            val hexChars = "0123456789abcdef"
            while (index <= bytes.size - 24) {
                if (bytes[index] == 0x09.toByte() &&
                    bytes[index + 1] == 0.toByte() &&
                    bytes[index + 2] == 0.toByte() &&
                    bytes[index + 3] == 0.toByte() &&
                    bytes[index + 4] == '!'.code.toByte() &&
                    bytes[index + 5] == 0.toByte()
                ) {
                    val builder = StringBuilder(9)
                    var valid = true
                    for (j in 0 until 9) {
                        val lo = bytes[index + 4 + j * 2].toInt() and 0xFF
                        val hi = bytes[index + 5 + j * 2].toInt() and 0xFF
                        if (hi != 0) {
                            valid = false
                            break
                        }
                        val c = lo.toChar()
                        if (j > 0 && c !in hexChars) {
                            valid = false
                            break
                        }
                        builder.append(c)
                    }
                    if (valid) {
                        ids += builder.toString()
                        index += 24
                        continue
                    }
                }
                index++
            }
            ids.distinct()
        } catch (_: Exception) {
            emptyList()
        } finally {
            parcel.recycle()
        }
    }

    private fun scanBundleForNodeMeta(bundle: android.os.Bundle): NodeBundleMeta? {
        val parcel = android.os.Parcel.obtain()
        return try {
            bundle.writeToParcel(parcel, 0)
            val bytes = parcel.marshall()
            val strings = scanParcelStrings(bytes)
            val nodeId = strings.firstOrNull(::isNodeId)
                ?: scanBundleForNodeIds(bundle).firstOrNull()
                ?: return null
            val idIndex = strings.indexOfFirst { it == nodeId }
            val labels = if (idIndex >= 0) {
                strings.drop(idIndex + 1).filter(::isLikelyNodeLabel)
            } else {
                emptyList()
            }
            NodeBundleMeta(
                nodeId = nodeId,
                longName = labels.getOrNull(0).orEmpty(),
                shortName = labels.getOrNull(1).orEmpty(),
            )
        } catch (_: Exception) {
            null
        } finally {
            parcel.recycle()
        }
    }

    private fun scanParcelStrings(bytes: ByteArray): List<String> {
        val results = mutableListOf<String>()
        var index = 0
        while (index <= bytes.size - 6) {
            val length = readLittleEndianInt(bytes, index)
            if (length in 1..64) {
                val end = index + 4 + length * 2
                if (end + 1 < bytes.size && bytes[end] == 0.toByte() && bytes[end + 1] == 0.toByte()) {
                    val value = runCatching {
                        String(bytes, index + 4, length * 2, Charset.forName("UTF-16LE"))
                    }.getOrNull()?.trim().orEmpty()
                    if (isPlausibleParcelString(value)) {
                        results += value
                        index = end + 2
                        continue
                    }
                }
            }
            index++
        }
        return results.distinct()
    }

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun isPlausibleParcelString(value: String): Boolean {
        if (value.isBlank()) return false
        if (value.any { it.code in 0..8 || it.code in 14..31 }) return false
        return true
    }

    private fun isNodeId(value: String): Boolean =
        value.length == 9 && value[0] == '!' && value.drop(1).all { it in '0'..'9' || it in 'a'..'f' }

    private fun isLikelyNodeLabel(value: String): Boolean {
        if (value.isBlank() || isNodeId(value)) return false
        if (value.startsWith("com.") || value.contains("NodeInfo") || value.startsWith("android.")) return false
        if (value.length > 48) return false
        return true
    }

    private fun parseNodeInfoPayload(bytes: ByteArray, fromId: String): MeshNode? {
        val user = parseProtoUser(bytes) ?: return null
        val nodeId = user.id.ifBlank { fromId }
        if (nodeId.isBlank() || nodeId == DataPacket.ID_LOCAL || nodeId == myNodeId) return null
        val longName = user.longName.trim()
        val shortName = user.shortName.trim()
        debug("NODEINFO_APP parsed nodeId=$nodeId long=${longName.ifBlank { "-" }} short=${shortName.ifBlank { "-" }}")
        return MeshNode(
            nodeId = nodeId,
            displayName = longName.ifBlank { shortName.ifBlank { nodeId } },
            shortName = shortName,
            lastSeen = System.currentTimeMillis(),
        )
    }

    private fun parseProtoUser(bytes: ByteArray): ProtoUser? {
        var index = 0
        var id = ""
        var longName = ""
        var shortName = ""

        while (index < bytes.size) {
            val tag = readProtoVarint(bytes, index) ?: break
            index = tag.nextIndex
            val field = (tag.value ushr 3).toInt()
            val wire = (tag.value and 0x07L).toInt()

            when (wire) {
                0 -> {
                    val value = readProtoVarint(bytes, index) ?: break
                    index = value.nextIndex
                }
                1 -> index += 8
                2 -> {
                    val length = readProtoVarint(bytes, index) ?: break
                    index = length.nextIndex
                    val end = index + length.value.toInt()
                    if (end > bytes.size || end < index) break
                    val text = bytes.copyOfRange(index, end).toString(Charsets.UTF_8)
                    when (field) {
                        1 -> id = text
                        2 -> longName = text
                        3 -> shortName = text
                    }
                    index = end
                }
                5 -> index += 4
                else -> break
            }
        }

        if (id.isBlank() && longName.isBlank() && shortName.isBlank()) return null
        return ProtoUser(id = id, longName = longName, shortName = shortName)
    }

    private data class ProtoVarint(val value: Long, val nextIndex: Int)

    private fun readProtoVarint(bytes: ByteArray, start: Int): ProtoVarint? {
        var result = 0L
        var shift = 0
        var index = start
        while (index < bytes.size && shift < 64) {
            val b = bytes[index].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            index++
            if ((b and 0x80) == 0) return ProtoVarint(result, index)
            shift += 7
        }
        return null
    }

    private fun ByteArray.startsWithPrefix(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }
}

