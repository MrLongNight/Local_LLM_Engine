package com.mydocanizer.llm.engine

import android.os.Parcel
import android.os.Parcelable

/**
 * Diagnostic and runtime information regarding the underlying llama.cpp backend.
 *
 * @property backendName Identifier of the active backend (e.g. CPU, KleidiAI).
 * @property systemInfo Detailed build and hardware feature flags reported by llama.cpp.
 * @property isAvailable True if native library loaded successfully.
 * @property recommendedThreads Recommended worker threads for this device.
 */
data class BackendInfo(
    val backendName: String = "llama.cpp (CPU)",
    val systemInfo: String = "",
    val isAvailable: Boolean = false,
    val recommendedThreads: Int = 4
) : Parcelable {
    constructor(parcel: Parcel) : this(
        backendName = parcel.readString() ?: "llama.cpp (CPU)",
        systemInfo = parcel.readString() ?: "",
        isAvailable = parcel.readByte() != 0.toByte(),
        recommendedThreads = parcel.readInt()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(backendName)
        parcel.writeString(systemInfo)
        parcel.writeByte(if (isAvailable) 1 else 0)
        parcel.writeInt(recommendedThreads)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<BackendInfo> = object : Parcelable.Creator<BackendInfo> {
            override fun createFromParcel(parcel: Parcel): BackendInfo = BackendInfo(parcel)
            override fun newArray(size: Int): Array<BackendInfo?> = arrayOfNulls(size)
        }
    }
}
