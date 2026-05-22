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
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.service.IMeshService
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
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
    }

    private val sender = Executors.newSingleThreadExecutor()
    private var meshService: IMeshService? = null
    private var serviceConn: ServiceConnection? = null
    private var dataReceiver: BroadcastReceiver? = null
    private var meshConnectedReceiver: BroadcastReceiver? = null
    private var myNodeId: String = ""

    fun connect() {
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
    }

    fun sendResponse(destId: String, seq: String, responseJson: String) {
        val compressed = deflate(responseJson.toByteArray(Charsets.UTF_8))
        val chunks = compressed.chunkedBytes(178)
        val total = chunks.size.coerceAtLeast(1)
        val actualChunks = if (chunks.isEmpty()) listOf(ByteArray(0)) else chunks
        onLog("RES $destId seq=$seq ${compressed.size} bytes $total chunk(s)")
        actualChunks.forEachIndexed { index, chunk ->
            val header = "MBBS2|$destId|$seq|$index|$total\n".toByteArray(Charsets.UTF_8)
            sendPrivate(header + chunk, destId, logSuccess = false)
        }
    }

    fun sendPlainText(destId: String, text: String) {
        sendPlainTexts(destId, listOf(text))
    }

    fun sendPlainTexts(destId: String, texts: List<String>, pauseMs: Long = 3_000L) {
        sender.execute {
            val messages = texts.filter { it.isNotBlank() }
            messages.forEachIndexed { index, text ->
                val packet = DataPacket(
                    to = destId,
                    bytes = text.toByteArray(Charsets.UTF_8),
                    dataType = DataPacket.TEXT_MESSAGE_APP,
                    from = myNodeId.ifBlank { DataPacket.ID_LOCAL },
                    hopLimit = 3,
                    channel = 0,
                    wantAck = false,
                )
                runCatching { meshService?.send(packet) }
                    .onSuccess { onLog("TXT $destId ${text.take(60)}") }
                    .onFailure { onLog("Send text failed: ${it.message}") }
                val delayMs = if (index < messages.lastIndex) pauseMs else 280L
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

    private fun sendPrivate(bytes: ByteArray, to: String, logSuccess: Boolean = true) {
        sender.execute {
            val packet = DataPacket(
                to = to,
                bytes = bytes,
                dataType = BBS_APP,
                from = myNodeId.ifBlank { DataPacket.ID_LOCAL },
                hopLimit = 3,
                channel = 0,
                wantAck = false,
            )
            runCatching { meshService?.send(packet) }
                .onSuccess { if (logSuccess) onLog("RES $to ${bytes.size} bytes") }
                .onFailure { onLog("Send failed: ${it.message}") }
            Thread.sleep(280L)
        }
    }

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
