package org.meshtastic.core.model

import android.os.Parcel
import android.os.Parcelable

enum class MessageStatus {
    UNKNOWN,
    RECEIVED,
    QUEUED,
    ENROUTE,
    DELIVERED,
    SFPP_ROUTING,
    SFPP_CONFIRMED,
    ERROR,
}

/**
 * Wire-compatible local mirror of Meshtastic Android 2.7.x DataPacket.
 *
 * The real API model uses okio.ByteString, but the Binder parcel only carries a
 * length-prefixed byte array. Keeping this class dependency-free makes our AIDL
 * calls match Meshtastic's service without pulling the whole API library.
 */
data class DataPacket(
    var to: String? = ID_BROADCAST,
    var bytes: ByteArray? = null,
    var dataType: Int = TEXT_MESSAGE_APP,
    var from: String? = ID_LOCAL,
    var time: Long = System.currentTimeMillis(),
    var id: Int = 0,
    var status: MessageStatus? = MessageStatus.UNKNOWN,
    var hopLimit: Int = 0,
    var channel: Int = 0,
    var wantAck: Boolean = true,
    var hopStart: Int = 0,
    var snr: Float = 0f,
    var rssi: Int = 0,
    var replyId: Int? = null,
    var relayNode: Int? = null,
    var relays: Int = 0,
    var viaMqtt: Boolean = false,
    var emoji: Int = 0,
    var sfppHash: ByteArray? = null,
    var transportMechanism: Int = 0,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        to = parcel.readString(),
        bytes = parcel.readLengthPrefixedBytes(),
        dataType = parcel.readInt(),
        from = parcel.readString(),
        time = parcel.readLong(),
        id = parcel.readInt(),
        status = parcel.readStatus(),
        hopLimit = parcel.readInt(),
        channel = parcel.readInt(),
        wantAck = parcel.readInt() != 0,
        hopStart = parcel.readInt(),
        snr = parcel.readFloat(),
        rssi = parcel.readInt(),
        replyId = parcel.readNullableInt(),
        relayNode = parcel.readNullableInt(),
        relays = parcel.readInt(),
        viaMqtt = parcel.readInt() != 0,
        emoji = parcel.readInt(),
        sfppHash = parcel.readLengthPrefixedBytes(),
        transportMechanism = parcel.readInt(),
    )

    val text: String?
        get() = if (dataType == TEXT_MESSAGE_APP || dataType == PRIVATE_APP) bytes?.toString(Charsets.UTF_8) else null

    fun readFromParcel(parcel: Parcel) {
        to = parcel.readString()
        bytes = parcel.readLengthPrefixedBytes()
        dataType = parcel.readInt()
        from = parcel.readString()
        time = parcel.readLong()
        id = parcel.readInt()
        status = parcel.readStatus()
        hopLimit = parcel.readInt()
        channel = parcel.readInt()
        wantAck = parcel.readInt() != 0
        hopStart = parcel.readInt()
        snr = parcel.readFloat()
        rssi = parcel.readInt()
        replyId = parcel.readNullableInt()
        relayNode = parcel.readNullableInt()
        relays = parcel.readInt()
        viaMqtt = parcel.readInt() != 0
        emoji = parcel.readInt()
        sfppHash = parcel.readLengthPrefixedBytes()
        transportMechanism = parcel.readInt()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(to)
        parcel.writeLengthPrefixedBytes(bytes)
        parcel.writeInt(dataType)
        parcel.writeString(from)
        parcel.writeLong(time)
        parcel.writeInt(id)
        parcel.writeStatus(status)
        parcel.writeInt(hopLimit)
        parcel.writeInt(channel)
        parcel.writeInt(if (wantAck) 1 else 0)
        parcel.writeInt(hopStart)
        parcel.writeFloat(snr)
        parcel.writeInt(rssi)
        parcel.writeNullableInt(replyId)
        parcel.writeNullableInt(relayNode)
        parcel.writeInt(relays)
        parcel.writeInt(if (viaMqtt) 1 else 0)
        parcel.writeInt(emoji)
        parcel.writeLengthPrefixedBytes(sfppHash)
        parcel.writeInt(transportMechanism)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean =
        other is DataPacket && id == other.id && from == other.from

    override fun hashCode(): Int = 31 * id.hashCode() + (from?.hashCode() ?: 0)

    companion object {
        const val ID_BROADCAST = "^all"
        const val ID_LOCAL = "^local"
        const val TEXT_MESSAGE_APP = 1
        const val PRIVATE_APP = 256

        @JvmField
        val CREATOR: Parcelable.Creator<DataPacket> = object : Parcelable.Creator<DataPacket> {
            override fun createFromParcel(parcel: Parcel): DataPacket = DataPacket(parcel)
            override fun newArray(size: Int): Array<DataPacket?> = arrayOfNulls(size)
        }
    }
}

private fun Parcel.writeLengthPrefixedBytes(value: ByteArray?) {
    writeByteArray(value)
}

private fun Parcel.readLengthPrefixedBytes(): ByteArray? = createByteArray()

private fun Parcel.writeStatus(value: MessageStatus?) {
    if (value == null) {
        writeInt(0)
    } else {
        writeInt(1)
        writeString(value.name)
    }
}

private fun Parcel.readStatus(): MessageStatus? {
    if (readInt() == 0) return null
    val name = readString() ?: return MessageStatus.UNKNOWN
    return runCatching { MessageStatus.valueOf(name) }.getOrDefault(MessageStatus.UNKNOWN)
}

private fun Parcel.writeNullableInt(value: Int?) {
    if (value == null) {
        writeInt(0)
    } else {
        writeInt(1)
        writeInt(value)
    }
}

private fun Parcel.readNullableInt(): Int? =
    if (readInt() == 0) null else readInt()
