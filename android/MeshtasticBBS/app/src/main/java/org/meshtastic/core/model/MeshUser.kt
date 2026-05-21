package org.meshtastic.core.model

import android.os.Parcel
import android.os.Parcelable

/**
 * Minimal Parcelable mirror for IMeshService.setOwner().
 *
 * MeshBBS never calls setOwner; this class exists so the official AIDL method
 * order compiles locally and keeps send() at the same Binder transaction code
 * as Meshtastic Android.
 */
data class MeshUser(
    var id: String = "",
    var longName: String = "",
    var shortName: String = "",
) : Parcelable {
    constructor(parcel: Parcel) : this(
        id = parcel.readString().orEmpty(),
        longName = parcel.readString().orEmpty(),
        shortName = parcel.readString().orEmpty(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(longName)
        parcel.writeString(shortName)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<MeshUser> {
        override fun createFromParcel(parcel: Parcel): MeshUser = MeshUser(parcel)
        override fun newArray(size: Int): Array<MeshUser?> = arrayOfNulls(size)
    }
}
