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
    val recommendedThreads: Int = 4,
    /** Backend requested by the caller (AUTO, CPU or VULKAN). */
    val requestedBackend: String = "UNKNOWN",
    /** Backend selected by llama.cpp for the loaded model. */
    val activeBackend: String = "UNKNOWN",
    /** GPU layers requested by the caller; -1 means unavailable. */
    val requestedGpuLayers: Int = -1,
    /** GPU layers confirmed by llama.cpp load diagnostics; -1 means unknown. */
    val allocatedGpuLayers: Int = -1,
    /** Total offloadable layers reported by llama.cpp; -1 means unknown. */
    val totalGpuLayers: Int = -1,
    /** True when a non-CPU request resulted in an active CPU backend. */
    val cpuFallback: Boolean = false
) : Parcelable {
    constructor(parcel: Parcel) : this(
        backendName = parcel.readString() ?: "llama.cpp (CPU)",
        systemInfo = parcel.readString() ?: "",
        isAvailable = parcel.readByte() != 0.toByte(),
        recommendedThreads = parcel.readInt(),
        requestedBackend = if (parcel.dataAvail() > 0) parcel.readString() ?: "UNKNOWN" else "UNKNOWN",
        activeBackend = if (parcel.dataAvail() > 0) parcel.readString() ?: "UNKNOWN" else "UNKNOWN",
        requestedGpuLayers = if (parcel.dataAvail() >= 4) parcel.readInt() else -1,
        allocatedGpuLayers = if (parcel.dataAvail() >= 4) parcel.readInt() else -1,
        totalGpuLayers = if (parcel.dataAvail() >= 4) parcel.readInt() else -1,
        cpuFallback = parcel.dataAvail() > 0 && parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(backendName)
        parcel.writeString(systemInfo)
        parcel.writeByte(if (isAvailable) 1 else 0)
        parcel.writeInt(recommendedThreads)
        parcel.writeString(requestedBackend)
        parcel.writeString(activeBackend)
        parcel.writeInt(requestedGpuLayers)
        parcel.writeInt(allocatedGpuLayers)
        parcel.writeInt(totalGpuLayers)
        parcel.writeByte(if (cpuFallback) 1 else 0)
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
