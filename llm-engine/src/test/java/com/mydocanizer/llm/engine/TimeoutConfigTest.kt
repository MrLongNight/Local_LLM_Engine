package com.mydocanizer.llm.engine

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TimeoutConfigTest {

    @Test
    fun testDefaultTimeoutConfigIsValid() {
        val config = TimeoutConfig()
        assertEquals(10_000L, config.serviceBindMs)
        assertEquals(180_000L, config.modelLoadMs)
        assertEquals(120_000L, config.prefillMs)
        assertEquals(120_000L, config.firstTokenMs)
        assertEquals(30_000L, config.interTokenMs)
        assertEquals(600_000L, config.totalInferenceMs)
        assertEquals(300L, config.abortGracePeriodMs)
        assertEquals(15_000L, config.reconnectMs)

        assertTrue(config.firstTokenMs < config.totalInferenceMs)
        assertTrue(config.prefillMs <= config.firstTokenMs)
        assertTrue(config.interTokenMs < config.totalInferenceMs)
        assertTrue(config.abortGracePeriodMs < config.reconnectMs)

        assertEquals(10.seconds, config.serviceBind)
        assertEquals(180.seconds, config.modelLoad)
        assertEquals(120.seconds, config.prefill)
        assertEquals(120.seconds, config.firstToken)
        assertEquals(30.seconds, config.interToken)
        assertEquals(600.seconds, config.totalInference)
        assertEquals(300.milliseconds, config.abortGracePeriod)
        assertEquals(15.seconds, config.reconnect)
    }

    @Test
    fun testValidationFirstTokenLessThanTotalInference() {
        assertThrows(IllegalArgumentException::class.java) {
            TimeoutConfig(
                firstTokenMs = 200_000L,
                totalInferenceMs = 100_000L
            )
        }
    }

    @Test
    fun testValidationPrefillLessThanOrEqualToFirstToken() {
        assertThrows(IllegalArgumentException::class.java) {
            TimeoutConfig(
                prefillMs = 50_000L,
                firstTokenMs = 30_000L
            )
        }
    }

    @Test
    fun testValidationInterTokenLessThanTotalInference() {
        assertThrows(IllegalArgumentException::class.java) {
            TimeoutConfig(
                interTokenMs = 250_000L,
                totalInferenceMs = 200_000L
            )
        }
    }

    @Test
    fun testValidationAbortGracePeriodLessThanReconnect() {
        assertThrows(IllegalArgumentException::class.java) {
            TimeoutConfig(
                abortGracePeriodMs = 20_000L,
                reconnectMs = 10_000L
            )
        }
    }

    @Test
    fun testValidationNegativeOrZeroValuesThrow() {
        assertThrows(IllegalArgumentException::class.java) {
            TimeoutConfig(serviceBindMs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimeoutConfig(modelLoadMs = -10L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimeoutConfig(prefillMs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimeoutConfig(firstTokenMs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimeoutConfig(interTokenMs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimeoutConfig(totalInferenceMs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimeoutConfig(abortGracePeriodMs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimeoutConfig(reconnectMs = 0L)
        }
    }

    @Test
    fun testParcelableCreator() {
        assertNotNull(TimeoutConfig.CREATOR)
        val original = TimeoutConfig(
            serviceBindMs = 5000L,
            modelLoadMs = 25000L,
            prefillMs = 12000L,
            firstTokenMs = 15000L,
            interTokenMs = 8000L,
            totalInferenceMs = 90000L,
            abortGracePeriodMs = 500L,
            reconnectMs = 8000L
        )
        assertEquals(0, original.describeContents())
        val array = TimeoutConfig.CREATOR.newArray(5)
        assertEquals(5, array.size)
    }

    @Test
    fun testFastFactoryHelper() {
        val fast = TimeoutConfig.fast(
            prefillMs = 200L,
            firstTokenMs = 400L,
            interTokenMs = 200L,
            totalInferenceMs = 1000L
        )
        assertNotNull(fast)
        assertEquals(200L, fast.prefillMs)
        assertEquals(400L, fast.firstTokenMs)
        assertEquals(200L, fast.interTokenMs)
        assertEquals(1000L, fast.totalInferenceMs)
    }

    @Test
    fun testFromDurationsHelper() {
        val config = TimeoutConfig.fromDurations(
            serviceBind = 5.seconds,
            modelLoad = 20.seconds,
            prefill = 10.seconds,
            firstToken = 15.seconds,
            interToken = 5.seconds,
            totalInference = 60.seconds,
            abortGracePeriod = 200.milliseconds,
            reconnect = 10.seconds
        )
        assertEquals(5_000L, config.serviceBindMs)
        assertEquals(20_000L, config.modelLoadMs)
        assertEquals(10_000L, config.prefillMs)
        assertEquals(15_000L, config.firstTokenMs)
        assertEquals(5_000L, config.interTokenMs)
        assertEquals(60_000L, config.totalInferenceMs)
        assertEquals(200L, config.abortGracePeriodMs)
        assertEquals(10_000L, config.reconnectMs)
    }
}
