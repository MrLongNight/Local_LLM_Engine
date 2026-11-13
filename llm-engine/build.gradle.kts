plugins {
    alias(libs.plugins.android.library)
}

// Profiler builds are intentionally opt-in: timestamp queries perturb throughput.
val localLlmVulkanPerfProfiler = providers.gradleProperty("localLlmVulkanPerfProfiler")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)

android {
    namespace = "com.mydocanizer.llm.engine"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    ndkVersion = "27.2.12479018"

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "VULKAN_PERF_PROFILER_ENABLED", localLlmVulkanPerfProfiler.toString())
            buildConfigField("boolean", "TEXT_DIAGNOSTICS_ENABLED", "true")
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DLOCAL_LLM_ENABLE_TEST_HOOKS=ON",
                        "-DLOCAL_LLM_ENABLE_TEXT_DIAGNOSTICS=ON"
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
                        "-DLOCAL_LLM_ENABLE_TEXT_DIAGNOSTICS=OFF"
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
