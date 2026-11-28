plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

val appBaseVersionName = providers.gradleProperty("appVersionName").orElse("1.0").get()
// CI passes the exact APK workflow run number as a Gradle property.
 // Prefer that explicit value over inherited environment variables, which may
 // belong to an upstream workflow_run trigger.
val localLlmP101dAbPair = providers.gradleProperty("localLlmP101dAbPair")
  .map { it.equals("true", ignoreCase = true) }
  .getOrElse(false)

val appBuildNumber = providers.gradleProperty("appBuildNumber")
  .orElse(providers.environmentVariable("GITHUB_RUN_NUMBER"))
  .orElse("1")
  .get()
  .toIntOrNull()
  ?.takeIf { it in 1..2_100_000_000 }
  ?: error("App build number must be a positive Android versionCode (1..2100000000)")

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }
  ndkVersion = "27.2.12479018"

  defaultConfig {
    applicationId = "com.aistudio.localllm.mdoanx"
    minSdk = 24
    targetSdk = 36
    versionCode = appBuildNumber
    versionName = "$appBaseVersionName.$appBuildNumber"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    ndk {
      abiFilters += listOf("arm64-v8a", "x86_64")
    }
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Keep the diagnostic build side-by-side with earlier installs. A previous CI APK
      // may have been signed with a different one-run key; never force an uninstall that
      // would delete the user's app-private model data.
      // P1-01d A/B builds use a separate package so they can coexist with
      // the regular Debug app even when CI uses a one-run signing key.
      applicationIdSuffix = if (localLlmP101dAbPair) ".dmmv.p1ab" else ".dmmv"
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
      // Robolectric resolves its SDK artifacts from the Gradle/Maven cache.
    }
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

dependencies {
  implementation(project(":llm-engine"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    // Maven Central occasionally rate-limits Robolectric's multi-hundred-MiB SDK jars.
    // Google's synchronized Maven Central mirror keeps the test runtime deterministic.
    systemProperty(
        "robolectric.dependency.repo.url",
        "https://maven-central.storage-download.googleapis.com/maven2"
    )
    systemProperty("robolectric.dependency.repo.id", "googleMavenCentralMirror")
}
