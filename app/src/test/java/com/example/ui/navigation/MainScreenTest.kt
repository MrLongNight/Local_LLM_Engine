package com.example.ui.navigation

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.core.app.ApplicationProvider
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
    fun `app version label formats installed package version and CI build number`() {
        assertEquals(
            "v1.0.642 · Build 642",
            formatAppVersion(versionName = "1.0.642", versionCode = 642L)
        )
    }

    @Test
    fun currentAppBuildInfoCanBeRecordedImmediatelyBeforeDiagnosticsExport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
        recordCurrentAppBuildInfo(context)

        val latestEntry = EngineDiagnostics.getLogs().lastOrNull().orEmpty()
        assertTrue(
            latestEntry,
            latestEntry.contains(
                "APP_BUILD_INFO - schemaVersion=1"
            ) &&
                latestEntry.contains(
                    "versionName=${packageInfo.versionName},versionCode=$versionCode," +
                        "buildType=${BuildConfig.BUILD_TYPE},applicationId=${context.packageName}," +
                        "debug=${BuildConfig.DEBUG}"
                )
        )
    }

    @Test
    fun topBarVersionComposableRendersInstalledPackageVersionAndCode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)

        composeRule.setContent {
            MaterialTheme {
                AppVersionLabel()
            }
        }

        composeRule.onNodeWithTag("top_app_bar_version")
            .assertTextEquals("v${packageInfo.versionName} · Build $versionCode")
    }
}
