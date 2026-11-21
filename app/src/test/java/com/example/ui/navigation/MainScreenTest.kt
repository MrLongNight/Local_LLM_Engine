package com.example.ui.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.example.BuildConfig
import com.mydocanizer.llm.engine.EngineDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `app version label includes actual app version and CI build number`() {
        assertEquals(
            "v1.0.642 · Build 642",
            formatAppVersion(versionName = "1.0.642", versionCode = 642)
        )
    }

    @Test
    fun currentAppBuildInfoCanBeRecordedImmediatelyBeforeDiagnosticsExport() {
        recordCurrentAppBuildInfo()

        val latestEntry = EngineDiagnostics.getLogs().lastOrNull().orEmpty()
        assertTrue(
            latestEntry,
            latestEntry.contains(
                "[app] APP_BUILD_INFO - versionName=${BuildConfig.VERSION_NAME}," +
                    "versionCode=${BuildConfig.VERSION_CODE},buildType=${BuildConfig.BUILD_TYPE}," +
                    "applicationId=${BuildConfig.APPLICATION_ID},debug=${BuildConfig.DEBUG}"
            )
        )
    }

    @Test
    fun topBarVersionComposableRendersBuildConfigVersionAndCode() {
        composeRule.setContent {
            MaterialTheme {
                AppVersionLabel()
            }
        }

        composeRule.onNodeWithTag("top_app_bar_version")
            .assertTextEquals("v${BuildConfig.VERSION_NAME} · Build ${BuildConfig.VERSION_CODE}")
    }
}
