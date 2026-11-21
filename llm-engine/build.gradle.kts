plugins {
    alias(libs.plugins.android.library)
}

// Profiler builds are intentionally opt-in: timestamp queries perturb throughput.
// Debug APKs are the diagnostic harness. Capture complete, bounded inference
// evidence by default; use -PlocalLlmSyntheticTokenDiagnostics=false to opt out.
// Release builds explicitly keep every text-diagnostic compile flag disabled.
val localLlmVulkanPerfProfiler = providers.gradleProperty("localLlmVulkanPerfProfiler")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)

// TEMPORARY P1-01d Mali fence-wait A/B selector. SPIN is diagnostic-only.
val localLlmMaliFenceWaitStrategy = providers.gradleProperty("localLlmMaliFenceWaitStrategy")
    .map { it.trim().uppercase() }
    .getOrElse("BLOCK")
if (localLlmMaliFenceWaitStrategy != "BLOCK" && localLlmMaliFenceWaitStrategy != "SPIN") {
    throw GradleException("localLlmMaliFenceWaitStrategy must be BLOCK or SPIN")
}
if (localLlmMaliFenceWaitStrategy == "SPIN" && !localLlmVulkanPerfProfiler) {
    throw GradleException("TEMPORARY Mali SPIN strategy requires -PlocalLlmVulkanPerfProfiler=true")
}

val localLlmSyntheticTokenDiagnostics = providers.gradleProperty("localLlmSyntheticTokenDiagnostics")
    .map { raw ->
        when (raw.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw GradleException(
                "localLlmSyntheticTokenDiagnostics must be either true or false (was '$raw')"
            )
        }
    }
    .getOrElse(true)

// ARM CPU kernels: the baseline arm64 build has no dot-product instructions
// (runtime log: DOTPROD=0). Every Armv8.2-A core (Cortex-A55/A75/A76 and newer, i.e. every
// Mali-G57 phone) supports them, and they make int8 matmul (prefill, Q8_0) about 2-3x faster.
// Set -PlocalLlmCpuArmArch= (empty) to build the portable baseline for pre-Armv8.2 devices.
val localLlmCpuArmArch = providers.gradleProperty("localLlmCpuArmArch")
    .getOrElse("armv8.2-a+dotprod")
    .trim()

android {
    namespace = "com.mydocanizer.llm.engine"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    ndkVersion = "27.2.12479018"

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "VULKAN_PERF_PROFILER_ENABLED", localLlmVulkanPerfProfiler.toString())
            buildConfigField("boolean", "TEXT_DIAGNOSTICS_ENABLED", localLlmSyntheticTokenDiagnostics.toString())
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DLOCAL_LLM_ENABLE_TEST_HOOKS=ON",
                        "-DLOCAL_LLM_ENABLE_TEXT_DIAGNOSTICS=${if (localLlmSyntheticTokenDiagnostics) "ON" else "OFF"}",
                        "-DLOCAL_LLM_VULKAN_PERF_PROFILER_ENABLED=${if (localLlmVulkanPerfProfiler) "ON" else "OFF"}",
                        "-DLOCAL_LLM_MALI_FENCE_WAIT_STRATEGY=$localLlmMaliFenceWaitStrategy"
                    )
                }
            }
        }
        getByName("release") {
            buildConfigField("boolean", "VULKAN_PERF_PROFILER_ENABLED", "false")
            buildConfigField("boolean", "TEXT_DIAGNOSTICS_ENABLED", "false")
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DLOCAL_LLM_ENABLE_TEST_HOOKS=OFF",
                        "-DLOCAL_LLM_ENABLE_TEXT_DIAGNOSTICS=OFF",
                        "-DLOCAL_LLM_VULKAN_PERF_PROFILER_ENABLED=OFF",
                        "-DLOCAL_LLM_MALI_FENCE_WAIT_STRATEGY=BLOCK"
                    )
                }
            }
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
                arguments += listOf("-DANDROID_STL=c++_shared")
                // Consumed by ggml's ARM CPU backend only; ignored for the x86_64 ABI.
                if (localLlmCpuArmArch.isNotEmpty()) {
                    arguments += listOf("-DGGML_CPU_ARM_ARCH=$localLlmCpuArmArch")
                }
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            // Robolectric supplies real ParcelFileDescriptor behavior for host JVM tests.
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
}
