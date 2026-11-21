package com.example.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class MainScreenTest {

    @Test
    fun `app version label includes version name and build number`() {
        assertEquals("v1.0 · Build 42", formatAppVersion(versionName = "1.0", versionCode = 42))
    }
}
