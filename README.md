# Local LLM Engine

An Android demo application and reusable Android library for running local GGUF language models with llama.cpp.

> **Development status:** Work in progress. The repository is not yet a verified production-ready inference engine. Successful Kotlin unit tests and APK compilation do not prove real-device model loading, inference, cancellation, hardware offload or performance.

## Project layout

- `:app` — demo Android application.
- `:llm-engine` — reusable Android library containing the public engine API and JNI/native llama.cpp integration.
- `:llm-engine/src/main/cpp/llama.cpp` — pinned Git submodule of `ggml-org/llama.cpp` at `669204ee2b9004845b34f26466a91d003edc98aa` (2026-11-06).
- `INTEGRATION_MYDOCANIZER.md` — integration notes for the intended myDocAnizer-Mobile target.

## Requirements

- JDK 17 for the Android/Native APK build; JDK 21 for the Robolectric SDK 36 unit-test workflow
- Gradle 9.3.1
- Git with recursive submodule checkout (`git clone --recurse-submodules`)
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

### Android APK Build

Workflow: `.github/workflows/manual-apk.yml`

The workflow runs automatically on pushes to `main` when Android app/engine source, Gradle/toolchain configuration, or the workflow changes. It can also be started manually through **Actions → Android APK Build → Run workflow** (Debug only).

The workflow builds `:app:assembleDebug`, including the native C++ build and the configured ARM64 Vulkan path, verifies `app-debug.apk` exists, and uploads it as the `Local-LMM-Engine-debug-apk` artifact. A temporary debug keystore is generated inside the runner; no repository signing secret is required. Artifacts are retained for 14 days. A successful unit-test workflow is not a substitute for this native APK build.

## Inference process boundary

The reusable `:llm-engine` library owns the `InferenceService` declaration in its
library manifest. The demo app does not redeclare the service. The merged APK is
checked to contain exactly one service registration with
`android:process=":inference"`, `android:exported="false"` and
`android:isolatedProcess="false"`. This is a separate process under the app's
UID, not Android's separately sandboxed isolated-UID service mode.

The Android instrumentation tests also inspect the resolved `ServiceInfo`
before binding, then verify that the service PID differs from the app process,
that killing it removes the original PID, and that the client can reconnect.
These CI tests currently kill an idle service: they do not load a GGUF model,
hold a native/JNI inference call blocked, or trigger the client timeout watchdog.
They therefore do not prove hard-abort during native inference, model recovery,
or Vulkan-driver resource release. Emulator results do not replace a real-device
test on the target phone.

### Cancellation and hard-abort sequence

The client first requests cooperative cancellation through the native engine.
If the original request does not finish within `abortGracePeriodMs`, the client
uses the one-way AIDL `killProcess()` command. The service terminates its own
`:inference` process immediately and deliberately does not wait for
`engine.close()`, JNI cleanup, file logging, or coroutine joins. The client
checks that the original PID has disappeared and can issue a same-UID
client-side PID kill if the service did not exit in time. Once process death is
confirmed, the client reconnects; model state and open descriptors from the
dead process are gone, so recovery must reload the previously selected source.

This is the implemented escalation path, not yet a real-device acceptance
result. In particular, a process kill proves process termination; it does not
by itself prove that a vendor Vulkan driver has returned all GPU allocations.

## Native llama.cpp source

The engine pins the upstream `ggml-org/llama.cpp` source as a Git submodule at
`669204ee2b9004845b34f26466a91d003edc98aa` (upstream HEAD checked on 2026-11-06 at
19:51 UTC). The exact revision is also verified in the APK workflow. To initialize
or refresh a clone:

```bash
git submodule update --init --recursive
```

This revision is 15 upstream commits newer than the previous pin
`67107ac34f62882d8b53ea41ad68010ea635b9be`. The updates include Vulkan
ReBAR detection and corresponding device-memory allocation handling, alongside
upstream fixes and improvements in other backends and shared code. This is a
source update, not evidence of faster inference on the target phone. Hexagon is
not enabled in this Android build, so this pin update does not claim NPU
acceleration.
Android API-24 Vulkan loader and Mali extension-dispatch compatibility remain as
a build-only overlay: CMake copies upstream `ggml-vulkan.cpp` into the build
directory, applies checked replacements there, and compiles that copy. The
upstream submodule working tree is never edited. The overlay fails CMake
configuration if its expected upstream anchors change, so a future llama.cpp
revision cannot silently bypass these fixes.

## Native Android backends

- ARM64 builds configure the CPU backend with `GGML_CPU_KLEIDIAI=ON`, matching the official llama.cpp Android reference. llama.cpp fetches the pinned KleidiAI v1.24.0 source at CMake configure time and includes applicable Arm kernels. This does not imply every kernel is used on every Arm CPU; runtime CPU features and supported operations determine applicability.
- OpenMP remains disabled pending an Android-device comparison. The upstream Android sample enables it, while the general upstream Android build instructions describe Android limitations. We will decide based on a controlled benchmark, not an assumption.
- The ARM64 Vulkan backend is built when the Android NDK Vulkan loader, host `glslc`, SPIR-V headers and Vulkan-Hpp headers are available. The APK workflow installs and requires these build tools. x86_64 remains CPU-only.
- Vulkan driver/device detection is not by itself proof that llama.cpp offloaded model layers. The implementation-plan tracks the required registry/offload verification separately.

## Local commands

After cloning with `git clone --recurse-submodules` (or running `git submodule update --init --recursive` in an existing clone), and with Gradle 9.3.1 plus the Android SDK/NDK/CMake versions above available:

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest :llm-engine:testDebugUnitTest
```

## Current limitations

- The supported native ABIs are currently 64-bit `arm64-v8a` and `x86_64`; 32-bit ARM is not included.
- A SAF-selected local GGUF is opened as a `ParcelFileDescriptor` and passed to `:inference`; the native loader opens the retained descriptor through `/proc/self/fd/<n>`. The normal SAF load path does not create a renamed app-private duplicate. This depends on a seekable provider descriptor: pipe-backed/cloud providers may not support the mmap access required by llama.cpp, and provider-specific detection/error messaging still needs verification. APK assets and HTTPS remote sources are still materialized in app-private storage, validated for the GGUF magic header, SHA-256 hashed, and atomically published under a SHA-256-addressed filename. HTTPS redirects are limited and cannot downgrade to HTTP. Downloads are not resumable yet.
- Cooperative cancellation, timeout escalation, process termination, PID-death polling and reconnect are implemented in code. The current emulator test only kills an idle service; timeout-driven termination during a blocked native/JNI inference and the complete reload-and-infer recovery cycle remain unverified on a real device.
- No real-device performance result is implied by CI success.

## Implementation plan

The single active implementation backlog and acceptance criteria are in [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md).

## Integration direction

The `:llm-engine` module is intended to be reusable by myDocAnizer-Mobile without importing the demo application's UI or application-specific storage layer. See [INTEGRATION_MYDOCANIZER.md](INTEGRATION_MYDOCANIZER.md) and verify its instructions against the current public API before integrating.
