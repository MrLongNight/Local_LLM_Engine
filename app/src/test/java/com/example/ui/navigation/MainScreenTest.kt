package com.example.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class MainScreenTest {

    @Test
    fun `app version label includes actual app version and CI build number`() {
        assertEquals("v1.0.642 · Build 642", formatAppVersion(versionName = "1.0.642", versionCode = 642))
    }
}
