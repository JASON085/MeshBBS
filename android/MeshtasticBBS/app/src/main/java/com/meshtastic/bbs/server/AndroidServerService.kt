package com.meshtastic.bbs.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.meshtastic.bbs.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.meshtastic.core.model.DataPacket
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.Inflater

class AndroidServerService : Service() {

    companion object {
        private const val CHANNEL_ID = "meshbbs_server_host"
        private const val NOTIFICATION_ID = 4207
        private const val ACTION_START = "com.meshtastic.bbs.server.START"
        private const val ACTION_STOP = "com.meshtastic.bbs.server.STOP"
        private const val ACTION_REFRESH = "com.meshtastic.bbs.server.REFRESH"
        private const val MAX_HELP_PACKET_BYTES = 180
        private const val COMPRESSED_REQUEST_TIMEOUT_MS = 90_000L

        fun start(context: Context) {
            val intent = Intent(context, AndroidServerService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AndroidServerService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun refresh(context: Context) {
            val intent = Intent(context, AndroidServerService::class.java).setAction(ACTION_REFRESH)
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bridge: PythonServerBridge? = null
    private var repo: MeshtasticServerRepository? = null
    private var refreshLoopStarted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val requestExecutor = Executors.newSingleThreadExecutor()
    private val recentRequests = ArrayDeque<String>()
    private val recentRequestSet = mutableSetOf<String>()
    private val recentPlainText = mutableMapOf<String, Long>()
    private val pendingCompressedRequests = ConcurrentHashMap<String, PendingCompressedRequest>()

    private data class PendingCompressedRequest(
        val cmd: String,
        val total: Int,
        val chunks: MutableMap<Int, String> = ConcurrentHashMap(),
        var updatedAtMs: Long = SystemClock.elapsedRealtime(),
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> startHost()
            ACTION_STOP -> stopHost()
            ACTION_REFRESH -> refreshDashboard()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        repo?.disconnect()
        requestExecutor.shutdownNow()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun startHost() {
        if (ServerHostStore.state.value.isRunning || ServerHostStore.state.value.isStarting) return
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Android Server 啟動中"))
        acquireWakeLock()
        ServerHostStore.setStarting()
        ServerHostStore.appendLog("開始啟動 Android Server")

        scope.launch {
            try {
                val serverBridge = ensureBridge()
                ServerHostStore.setDashboard(serverBridge.bootstrap())
                repo = MeshtasticServerRepository(
                    context = applicationContext,
                    onLog = { ServerHostStore.appendLog(it) },
                    onMeshState = { bound, myNodeId ->
                        ServerHostStore.setMeshBound(bound, myNodeId)
                        updateNotification(bound, myNodeId)
                    },
                    onRequest = { fromId, text ->
                        requestExecutor.execute { handleIncomingRequest(fromId, text) }
                    },
                )
                repo?.connect()
                ServerHostStore.setRunning(true, "執行中")
                ServerHostStore.appendLog("Android Server 已啟動")
                if (!refreshLoopStarted) {
                    refreshLoopStarted = true
                    startRefreshLoop()
                }
            } catch (e: Exception) {
                ServerHostStore.setError(e.message ?: "啟動失敗")
                ServerHostStore.stop("啟動失敗")
                releaseWakeLock()
                stopSelf()
            }
        }
    }

    private fun stopHost() {
        repo?.disconnect()
        repo = null
        releaseWakeLock()
        ServerHostStore.stop()
        ServerHostStore.appendLog("Android Server 已停止")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refreshDashboard() {
        scope.launch {
            runCatching {
                ensureBridge().refreshDashboard().let(ServerHostStore::setDashboard)
            }.onFailure {
                ServerHostStore.setError(it.message ?: "更新儀表板失敗")
            }
        }
    }

    @Synchronized
    private fun ensureBridge(): PythonServerBridge {
        val current = bridge
        if (current != null) return current
        return PythonServerBridge(applicationContext).also { bridge = it }
    }

    private fun handleIncomingRequest(fromId: String, text: String) {
        if (text.startsWith("BBS:ACK:")) {
            handleBatchAck(fromId, text)
            return
        }
        if (text.startsWith("BBS:WINACK:")) {
            handleWindowAck(fromId, text)
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

    private fun handleMissingChunkRequest(fromId: String, text: String) {
        val parts = text.split(":", limit = 4)
        if (parts.size < 4) {
            ServerHostStore.appendLog("MISS_DROP reason=invalid_parts from=$fromId rawSize=${text.length}")
            return
        }
        val seq = parts[2]
        val pendingKey = "$fromId:$seq"
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
            "MISS_REQ from=$fromId seq=$seq pendingKey=$pendingKey missing=${missingIndexes.joinToString(",")}"
        )
        repo?.resendResponseChunks(fromId, seq, missingIndexes)
    }

    private fun handleWindowAck(fromId: String, text: String) {
        val parts = text.split(":", limit = 4)
        if (parts.size < 4) {
            ServerHostStore.appendLog("WINACK_DROP reason=invalid_parts from=$fromId rawSize=${text.length}")
            return
        }
        val seq = parts[2]
        val nextIndex = parts[3].trim().toIntOrNull()
        if (seq.isBlank() || nextIndex == null || nextIndex < 0) {
            ServerHostStore.appendLog("WINACK_DROP reason=invalid_payload from=$fromId seq=$seq raw=${parts[3]}")
            return
        }
        ServerHostStore.appendLog("WINACK_REQ from=$fromId seq=$seq nextIndex=$nextIndex")
        repo?.acknowledgeWindow(fromId, seq, nextIndex)
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

    private fun rememberRequest(key: String): Boolean {
        if (!recentRequestSet.add(key)) return false
        recentRequests.addLast(key)
        while (recentRequests.size > 120) {
            recentRequestSet.remove(recentRequests.removeFirst())
        }
        return true
    }

    private fun handlePlainTextRequest(fromId: String, text: String) {
        if (fromId.isBlank() || fromId == DataPacket.ID_BROADCAST) return
        if (repo?.hasActiveReadTransfer() == true) {
            ServerHostStore.appendLog("TXT_DROP reason=read_active from=${fromId.takeLast(6)} text=$text")
            return
        }
        val normalized = text.trim()
        if (normalized != "?" && normalized != "/?") return
        val now = SystemClock.elapsedRealtime()
        val dedupeKey = "$fromId:$normalized"
        val lastAt = recentPlainText[dedupeKey]
        if (lastAt != null && now - lastAt < 5_000L) {
            ServerHostStore.appendLog("DUP skip plain from=${fromId.takeLast(6)} text=$normalized")
            return
        }
        recentPlainText[dedupeKey] = now
        recentPlainText.entries.removeAll { now - it.value > 15_000L }
        ServerHostStore.incrementRequest("TXT <- ${fromId.takeLast(6)}")
        repo?.sendPlainTexts(
            destId = fromId,
            texts = mqttSafeHelpMessages(),
            initialDelayMs = 900L,
            pauseMs = 3_600L,
            finalDelayMs = 1_200L,
        )
    }

    private fun helpMessagesCompact(): List<String> = listOf(
        """
        MeshBBS使用說明:
        Meshtastic APP保持連線狀態,下載客戶端APP
        http://reurl.cc/Z24Wql
        安裝好MeshBBS就可以上線
        """.trimIndent(),
        """
        登入與看板:
        LOGIN:<帳號>:<密碼>
        LOGOUT
        LIST                列出看板
        POSTS:<看板>:<頁碼>   顯示文章列表
        """.trimIndent(),
        """
        閱讀與搜尋:
        READ:<文章編號>                    讀取文章
        PUSH:<文章編號>                    推薦,再按一次取消
        SEARCH:<title|author>:<看板>:<關鍵字>
        """.trimIndent(),
        """
        發文與回覆:
        POST:<看板>:<作者>:<標題>:<內容>
        REPLY:<文章編號>:<作者>:<內容>
        Relay格式:
        BBS:REQ:<序號>:<指令>:<參數>
        """.trimIndent(),
    ).mapIndexed { index, text ->
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_HELP_PACKET_BYTES) {
            error("Help packet ${index + 1} exceeds Meshtastic payload limit: $bytes bytes")
        }
        text
    }

    private fun mqttSafeHelpMessages(): List<String> = listOf(
        """
        [1/4] MeshBBS 使用
        私訊 Android Server
        APP: http://reurl.cc/Z24Wql
        LOGIN:<帳號>:<密碼>
        LOGOUT
        """.trimIndent(),
        """
        [2/4] 看板 / 文章
        LIST
        POSTS:<看板>:<頁>
        READ:<文章號>
        PUSH:<文章號>
        """.trimIndent(),
        """
        [3/4] 搜尋 / 發文
        SEARCH:<title|author>:<看板>:<關鍵字>
        POST:<看板>:<作者>:<標題>:<內容>
        """.trimIndent(),
        """
        [4/4] 回覆 / Relay
        REPLY:<文章號>:<作者>:<內容>
        BBS:REQ:<序號>:<命令>:<參數>
        """.trimIndent(),
    ).mapIndexed { index, text ->
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_HELP_PACKET_BYTES) {
            error("MQTT help packet ${index + 1} exceeds limit: $bytes bytes")
        }
        text
    }

    private fun startRefreshLoop() {
        scope.launch {
            while (isActive) {
                delay(12_000L)
                refreshDashboard()
            }
        }
    }

    private fun updateNotification(bound: Boolean, myNodeId: String) {
        val text = if (bound) {
            "Meshtastic 已連線 ${myNodeId.ifBlank { "" }}".trim()
        } else {
            "等待 Meshtastic 連線"
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MeshBBS Android Server",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "com.meshtastic.bbs:AndroidServerHost",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        ServerHostStore.appendLog("已取得喚醒鎖")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        wakeLock = null
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("MeshBBS Android Server")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun zlibDecompress(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 3)
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

    private fun formatChunkList(total: Int, keys: Collection<Int>): String =
        if (total <= 0) "[]"
        else keys.sorted().joinToString(prefix = "[", postfix = "]") { "${it + 1}/$total" }

    private fun missingChunkIndices(total: Int, keys: Collection<Int>): List<Int> =
        if (total <= 0) emptyList() else (0 until total).filterNot(keys.toSet()::contains)

    private fun formatMissingChunks(total: Int, keys: Collection<Int>): String =
        missingChunkIndices(total, keys).joinToString(prefix = "[", postfix = "]") { "${it + 1}/$total" }
}
