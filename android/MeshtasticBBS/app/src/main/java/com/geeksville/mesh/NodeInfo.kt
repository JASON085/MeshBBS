package com.geeksville.mesh

import android.os.Parcel
import android.os.Parcelable

/**
 * Partial-read NodeInfo — never throws, returns best-effort result.
 *
 * Priority: always read num + user? (longName) first.
 * Position + tail fields are best-effort; on any failure we return early
 * so broadcast parsing still produces a valid displayName.
 *
 * For getNodes() list parsing, MeshtasticRepository.parseNodeList() is used
 * instead (manual iteration with look-ahead) — this CREATOR is only used for
 * NODE_CHANGE broadcast extras and the TX-probe helper.
 */
class NodeInfo : Parcelable {

    var num: Int = 0
    var shortName: String = ""
    private var userId: String = ""
    private var longName: String = ""

    val nodeId: String
        get() = userId.ifBlank { "!%08x".format(num) }

    val displayName: String
        get() = longName.ifBlank { shortName.ifBlank { nodeId } }

    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {}  // receive-only

    companion object CREATOR : Parcelable.Creator<NodeInfo> {

        private val POSITION_OFFSETS: List<Int> = buildList {
            for (n in listOf(9,10,8,11,7,12,6,13,5,14,15,16,4,17,18,3,2,1,0,19,20,21,22,23,24,25))
                add(n * 4)
            for (n in listOf(9,11,7,13,5,3,1,0,15,17,6,8,10,12,14,16))
                add(16 + n * 4)
            for (n in 0..30) add(n * 4)
        }.distinct()

        @Volatile var extraUserInts: Int = 0
        @Volatile var extraTrailingInts: Int = 0

        override fun createFromParcel(source: Parcel): NodeInfo = NodeInfo().also { node ->
            try {
                // ── 1. num ────────────────────────────────────────────────────
                node.num = source.readInt()

                // ── 2. user? ─────────────────────────────────────────────────
                if ((source.dataSize() - source.dataPosition()) < 4) return@also
                if (source.readInt() != 0) {
                    node.setUserFields(source)
                }

                // ── 3-10. position? + tail (best-effort) ─────────────────────
                if ((source.dataSize() - source.dataPosition()) < 4) return@also
                val posInd = source.readInt()
                if (posInd !in 0..1) return@also     // corrupted — but we have the name

                if (posInd != 0) {
                    val posStart = source.dataPosition()
                    var posEnd   = -1

                    // 5-field backward validation (same logic as parseNodeList)
                    for (offset in POSITION_OFFSETS) {
                        val end = posStart + offset
                        if (end + 28 > source.dataSize()) continue
                        source.setDataPosition(end)
                        val snr = source.readFloat()
                        if (snr.isNaN() || snr.isInfinite()) continue
                        val ab = snr.toBits() and 0x7FFFFFFF
                        if (ab in 1..0x007FFFFF) continue          // subnormal
                        val lh = source.readInt()
                        if (lh < 0 || (lh != 0 && lh < 1_000_000_000)) continue
                        source.readInt()                            // channel skip
                        if (source.readInt() !in 0..1) continue    // viaMqtt
                        source.readInt()                            // hopsAway skip
                        if (source.readInt() !in 0..1) continue    // isFavorite
                        if (source.readInt() !in 0..1) continue    // isMqttGateway
                        posEnd = end; break
                    }
                    if (posEnd < 0) return@also      // can't skip position — return with name
                    source.setDataPosition(posEnd)
                }

                if ((source.dataSize() - source.dataPosition()) < 28) return@also
                source.readFloat()                   // snr
                repeat(6) { source.readInt() }       // lastHeard…isMqttGateway
                repeat(extraTrailingInts) { source.readInt() }

            } catch (_: Exception) {
                // Partial result is fine — node.num and node.longName may already be set
            }
        }

        private fun NodeInfo.setUserFields(source: Parcel) {
            userId    = source.readString() ?: ""
            longName  = source.readString() ?: ""
            shortName = source.readString() ?: ""
            if ((source.dataSize() - source.dataPosition()) < 12) return
            source.readInt()  // hwModel
            source.readInt()  // isLicensed
            source.readInt()  // role
            if ((source.dataSize() - source.dataPosition()) < extraUserInts * 4) return
            repeat(extraUserInts) { source.readInt() }
        }

        override fun newArray(size: Int): Array<NodeInfo?> = arrayOfNulls(size)
    }
}
