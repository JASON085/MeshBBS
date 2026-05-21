package com.geeksville.mesh

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Mirrors the DataPacket Parcelable in the Meshtastic Android app.
 * Field order and types MUST match exactly so the Parcelable deserialization works
 * when we receive one via Intent extras from the Meshtastic broadcast.
 *
 * Source reference: github.com/meshtastic/Meshtastic-Android — DataPacket.kt
 */
@Parcelize
data class DataPacket(
    var to: String       = ID_BROADCAST,
    var bytes: ByteArray? = null,
    var dataType: Int    = TEXT_MESSAGE_APP,
    var id: Int          = 0,
    var time: Long       = 0L,
    var from: String     = ID_LOCAL,
    var hopLimit: Int    = 0,
    var channel: Int     = 0,
) : Parcelable {

    companion object {
        const val ID_BROADCAST = "^all"
        const val ID_LOCAL     = "^local"
        const val TEXT_MESSAGE_APP = 1
        const val PRIVATE_APP = 256
    }

    override fun equals(other: Any?) =
        other is DataPacket && id == other.id && from == other.from

    override fun hashCode() = 31 * id.hashCode() + from.hashCode()
}
