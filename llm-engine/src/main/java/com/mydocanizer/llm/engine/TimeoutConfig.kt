package com.mydocanizer.llm.engine

import android.os.Parcel
import android.os.Parcelable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Centralized, tiered timeout configuration for engine operations and token generation.
 *
 * Implements monotonic timeout evaluation with process-kill escalation as Ultima Ratio.
 *
 * @property serviceBindMs Maximum time to establish initial IPC binding to :inference process.
 * @property modelLoadMs Maximum time allowed to map/load GGUF model weights into RAM/VRAM.
 * @property prefillMs Maximum duration allowed for prompt token ingestion / prefill phase.
 * @property firstTokenMs Maximum time until first output token must be emitted.
 * @property interTokenMs Maximum duration permitted between any two consecutive output tokens.
 * @property totalInferenceMs Hard upper bound for entire token generation pass.
 * @property abortGracePeriodMs Time given for service to self-terminate before client executes hard kill.
 * @property reconnectMs Maximum time allowed to re-establish service connection after process death.
 */
data class TimeoutConfig(
    val serviceBindMs: Long = 10_000L,
    val modelLoadMs: Long = 60_000L,
    val prefillMs: Long = 30_000L,
    val firstTokenMs: Long = 45_000L,
    val interTokenMs: Long = 15_000L,
    val totalInferenceMs: Long = 180_000L,
    val abortGracePeriodMs: Long = 300L,
    val reconnectMs: Long = 15_000L
) : Parcelable {

    init {
        require(serviceBindMs > 0) { "serviceBindMs must be > 0 (was $serviceBindMs)" }
        require(modelLoadMs > 0) { "modelLoadMs must be > 0 (was $modelLoadMs)" }
        require(prefillMs > 0) { "prefillMs must be > 0 (was $prefillMs)" }
        require(firstTokenMs > 0) { "firstTokenMs must be > 0 (was $firstTokenMs)" }
        require(interTokenMs > 0) { "interTokenMs must be > 0 (was $interTokenMs)" }
        require(totalInferenceMs > 0) { "totalInferenceMs must be > 0 (was $totalInferenceMs)" }
        require(abortGracePeriodMs > 0) { "abortGracePeriodMs must be > 0 (was $abortGracePeriodMs)" }
        require(reconnectMs > 0) { "reconnectMs must be > 0 (was $reconnectMs)" }

        require(firstTokenMs < totalInferenceMs) {
            "firstTokenMs ($firstTokenMs) must be strictly less than totalInferenceMs ($totalInferenceMs)"
        }
        require(prefillMs <= firstTokenMs) {
            "prefillMs ($prefillMs) must be <= firstTokenMs ($firstTokenMs)"
        }
        require(interTokenMs < totalInferenceMs) {
            "interTokenMs ($interTokenMs) must be strictly less than totalInferenceMs ($totalInferenceMs)"
        }
        require(abortGracePeriodMs < reconnectMs) {
            "abortGracePeriodMs ($abortGracePeriodMs) must be strictly less than reconnectMs ($reconnectMs)"
        }
    }


    val serviceBind: Duration get() = serviceBindMs.milliseconds
    val modelLoad: Duration get() = modelLoadMs.milliseconds
    val prefill: Duration get() = prefillMs.milliseconds
    val firstToken: Duration get() = firstTokenMs.milliseconds
    val interToken: Duration get() = interTokenMs.milliseconds
    val totalInference: Duration get() = totalInferenceMs.milliseconds
    val abortGracePeriod: Duration get() = abortGracePeriodMs.milliseconds
    val reconnect: Duration get() = reconnectMs.milliseconds

    constructor(parcel: Parcel) : this(
        serviceBindMs = parcel.readLong(),
        modelLoadMs = parcel.readLong(),
        prefillMs = parcel.readLong(),
        firstTokenMs = parcel.readLong(),
        interTokenMs = parcel.readLong(),
        totalInferenceMs = parcel.readLong(),
        abortGracePeriodMs = parcel.readLong(),
        reconnectMs = parcel.readLong()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(serviceBindMs)
        parcel.writeLong(modelLoadMs)
        parcel.writeLong(prefillMs)
        parcel.writeLong(firstTokenMs)
        parcel.writeLong(interTokenMs)
        parcel.writeLong(totalInferenceMs)
        parcel.writeLong(abortGracePeriodMs)
        parcel.writeLong(reconnectMs)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<TimeoutConfig> = object : Parcelable.Creator<TimeoutConfig> {
            override fun createFromParcel(parcel: Parcel): TimeoutConfig = TimeoutConfig(parcel)
            override fun newArray(size: Int): Array<TimeoutConfig?> = arrayOfNulls(size)
        }

        /**
         * Creates a [TimeoutConfig] using Kotlin [Duration] objects.
         */
        fun fromDurations(
            serviceBind: Duration = 10.seconds,
            modelLoad: Duration = 60.seconds,
            prefill: Duration = 30.seconds,
            firstToken: Duration = 45.seconds,
            interToken: Duration = 15.seconds,
            totalInference: Duration = 180.seconds,
            abortGracePeriod: Duration = 300.milliseconds,
            reconnect: Duration = 15.seconds
        ): TimeoutConfig = TimeoutConfig(
            serviceBindMs = serviceBind.inWholeMilliseconds,
            modelLoadMs = modelLoad.inWholeMilliseconds,
            prefillMs = prefill.inWholeMilliseconds,
            firstTokenMs = firstToken.inWholeMilliseconds,
            interTokenMs = interToken.inWholeMilliseconds,
            totalInferenceMs = totalInference.inWholeMilliseconds,
            abortGracePeriodMs = abortGracePeriod.inWholeMilliseconds,
            reconnectMs = reconnect.inWholeMilliseconds
        )

        /**
         * Fast-fail configuration for test environments.
         */
        fun fast(
            prefillMs: Long = 500L,
            firstTokenMs: Long = 1000L,
            interTokenMs: Long = 500L,
            totalInferenceMs: Long = 3000L
        ): TimeoutConfig = TimeoutConfig(
            serviceBindMs = 2000L,
            modelLoadMs = 5000L,
            prefillMs = prefillMs,
            firstTokenMs = firstTokenMs,
            interTokenMs = interTokenMs,
            totalInferenceMs = totalInferenceMs,
            abortGracePeriodMs = 100L,
            reconnectMs = 2000L
        )
    }
}
