package com.mydocanizer.llm.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufHeaderValidatorTest {

    @Test
    fun acceptsGgufMagicWithMinimumLength() {
        assertTrue(
            GgufHeaderValidator.isValid(
                byteArrayOf(0x47, 0x47, 0x55, 0x46),
                fileLength = 4L
            )
        )
    }

    @Test
    fun rejectsShortFileAndShortHeader() {
        assertFalse(GgufHeaderValidator.isValid(byteArrayOf(0x47, 0x47, 0x55, 0x46), 3L))
        assertFalse(GgufHeaderValidator.isValid(byteArrayOf(0x47, 0x47, 0x55), 4L))
    }

    @Test
    fun rejectsWrongMagic() {
        assertFalse(
            GgufHeaderValidator.isValid(
                byteArrayOf(0x42, 0x47, 0x55, 0x46),
                fileLength = 1024L
            )
        )
    }
}
