# Local LLM Engine

A standalone Android application and reusable Android library for running local GGUF language models with llama.cpp.

> **Development status:** Work in progress. The repository is not yet a verified production-ready inference engine. In particular, Vulkan acceleration and several inference/lifecycle behaviours still require implementation and CI/device verification. Do not interpret a successful Kotlin unit-test run as proof that the native Android build or on-device inference works.

## Project layout

- `:app` — demo Android application.
- `:llm-engine` — reusable Android library containing the public engine API and JNI/native llama.cpp integration.
- `:llm-engine/src/main/cpp/llama.cpp` — vendored llama.cpp source.
- `INTEGRATION_MYDOCANIZER.md` — integration notes for the intended myDocAnizer-Mobile target.

## Requirements

- JDK 17 for the Android/Native APK build; JDK 21 for the Robolectric SDK 36 unit-test workflow
- Gradle 9.3.1
- Android SDK platform 36
- Android NDK 27.2.12479018
- CMake 3.22.1
- Android ABIs currently configured: `arm64-v8a` and `x86_64`

The repository currently keeps the Gradle distribution configuration in `gradle/wrapper/gradle-wrapper.properties`; the Gradle wrapper launcher scripts/JAR are not checked in. CI therefore installs Gradle 9.3.1 directly.

## GitHub Actions

### Unit Tests

Workflow: `.github/workflows/unit-tests.yml`

Runs automatically for every pushed commit and pull request. It executes:

```bash
gradle --no-daemon :app:testDebugUnitTest :llm-engine:testDebugUnitTest
```

This is a JVM/unit-test job. It does not build the native C++ libraries and does not prove that an APK can be installed or that inference works on a device.

### Manual APK Build

Workflow: `.github/workflows/manual-apk.yml`

1. Open the repository's **Actions** tab.
2. Select **Manual Android APK Build**.
3. Select **Run workflow** on the desired branch (only Debug is currently offered).
4. Wait for the workflow to complete successfully.
5. Download the `Local-LMM-Engine-debug-apk` artifact from the completed run.

The workflow builds `:app:assembleDebug`, including the configured native build, and uploads `app-debug.apk`. A temporary debug keystore is generated inside the runner; no repository signing secret is required. Artifacts are retained for 14 days.

## Local commands

With Gradle 9.3.1 installed and the Android SDK/NDK/CMake versions above available:

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest :llm-engine:testDebugUnitTest
```

## Current limitations

- The arm64-v8a Android build enables GGML_VULKAN when the Android NDK Vulkan loader, host `glslc`, and SPIRV-Headers CMake package are available. The APK workflow installs and requires these build tools. x86_64 and local builds without the tools remain CPU builds. Runtime GPU availability is checked against llama.cpp's backend registry, never inferred from the Android Vulkan loader alone.
- Unit tests and APK compilation do not replace real-device model loading, generation, cancellation, memory, and performance checks.
- The supported native ABIs are currently 64-bit `arm64-v8a` and `x86_64`; 32-bit ARM is not included.
- Remote model imports are HTTPS-only and staged in app-private storage under a SHA-256-addressed filename. Downloads are not resumable yet.
- Coroutine and explicit cancellation are propagated to llama.cpp's native abort callback; real-device latency and cancellation behaviour still require device verification.
- The API and integration document may evolve while the engine implementation is stabilized.

## Integration direction

The `:llm-engine` module is intended to be reusable by myDocAnizer-Mobile without importing the demo application's UI or application-specific storage layer. See [INTEGRATION_MYDOCANIZER.md](INTEGRATION_MYDOCANIZER.md) and verify its instructions against the current public API before integrating.
