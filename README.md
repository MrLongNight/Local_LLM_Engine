# Local LLM Engine

An Android demo application and reusable Android library for running local GGUF language models with llama.cpp.

> **Development status:** Work in progress. The repository is not yet a verified production-ready inference engine. Successful Kotlin unit tests and APK compilation do not prove real-device model loading, inference, cancellation, hardware offload or performance.

## Project layout

- `:app` — demo Android application.
- `:llm-engine` — reusable Android library containing the public engine API and JNI/native llama.cpp integration.
- `:llm-engine/src/main/cpp/llama.cpp` — pinned Git submodule of `ggml-org/llama.cpp` at `a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5` (2026-11-09).
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

The workflow builds `:app:assembleDebug`, including the native C++ build and the configured ARM64 Vulkan path, verifies `app-debug.apk` exists, and uploads it as the `Local-LMM-Engine-debug-apk` artifact. If the repository Actions secret `LOCAL_LLM_DEBUG_KEYSTORE_BASE64` is configured, CI uses that persistent debug keystore; otherwise it generates a temporary key and warns that a later APK signed with a different key cannot update the same installed package. The Debug variant uses the separate application ID `com.aistudio.localllm.mdoanx.dmmv` and launcher label `Local LLM Engine – DMMV-Test`, so it can be installed beside the original app without uninstalling it. SAF document grants are package-specific, so select the GGUF again in the DMMV-Test app. Artifacts are retained for 14 days. A successful unit-test workflow is not a substitute for this native APK build.

## Last-model restore

The app stores the source and exact load-time configuration of the last model that
loaded successfully. On the next cold app start, it attempts to load that model
automatically. App-private GGUF files are checked for existence first. A model opened
through Android's Storage Access Framework is remembered only after a persistent read
grant has been obtained; if the file is moved, removed, or access is revoked, startup
shows an actionable error and leaves the saved selection intact for recovery. Manually
unloading a model does not clear the remembered selection.

The source and settings are stored locally in app-private SharedPreferences. The GGUF
file is not copied or uploaded by this feature.

## Model compatibility catalogue

See [`docs/MODEL_COMPATIBILITY.md`](docs/MODEL_COMPATIBILITY.md) for the model-family catalogue and the distinction between upstream architecture support, backend operator coverage and verified Android-device inference. Qwen3.5 and Gemma 4 are recognized by the pinned llama.cpp source, but neither is marked device-verified for the Mali-G57 until an actual GGUF load, chat-template, inference and backend-offload test has passed.

## CPU / Vulkan benchmark

The Settings benchmark reloads the same model explicitly on Vulkan and CPU, in that
order. Each backend receives exactly one deterministic inference request with 24 output
tokens total: the first four warm the execution path, and the next twenty are measured
(temperature 0, top-p 1). This avoids a second expensive prompt prefill per backend.
The screen reports prompt progress, generation progress, time to first token after
prefill, and post-warm-up throughput. A failed Vulkan run is recorded and does not
suppress the CPU attempt; the original model configuration is restored in a
non-cancellable cleanup block.

Native token timing distinguishes llama.cpp decode submission from the subsequent
GPU synchronization wait. Vulkan can enqueue compute asynchronously; timing only
the return of `llama_decode()` under-reports actual GPU work. Prompt prefill is also
synchronized before its duration is finalized, so it is not incorrectly charged to
the first generated token. `decodeUs` now includes submission plus completion wait,
with `decodeSubmitUs` and `backendSyncWaitUs` available separately in the native log. Older exports may call the latter `gpuSyncWaitUs`; that field measures the complete `llama_synchronize()` wait envelope, not isolated GPU-kernel time.

## Debug Vulkan GPU-operation profiler

Debug builds enable llama.cpp's `GGML_VK_PERF_LOGGER` before
`llama_backend_init()`. The Android Vulkan compatibility overlay routes each
per-operation Vulkan timestamp result through the native ggml logger instead
of `std::cerr`, so the rows are retained in the process-specific native
diagnostics file and included in the compact export. Each row contains the
inference-service PID, a graph snapshot ID, operator name, timestamp sample
count, average GPU time and accumulated GPU time; GFLOPS are included where
llama.cpp provides matching flop counts.

Release builds explicitly clear the profiler environment variables. Debug
profiling adds timestamp queries and synchronization/readback overhead, so
**do not use tokens/s from a profiler-enabled Debug run as a clean performance
benchmark**. Use it to identify expensive Vulkan operators and compare kernel
paths; validate any resulting optimization again in a profiler-disabled build.

The diagnostic export summarizes the newest 12 graph snapshots by process,
inference run ID and operator, including interpolated p50/p90 GPU operation
timings. Operation descriptors are sanitized so commas in Vulkan operator
shapes do not truncate the export fields. The RAM summary stays before verbose
token samples. The native log retention limit is 2 MiB per process and
the compact report limit is 32 KiB. Prompt and response contents remain
excluded.

## In-app configuration screening

The Settings screen includes an opt-in on-device screening run for the loaded model and
selected backend. It compares `n_threads`, `n_threads_batch`, `n_batch` and `n_ubatch`
one setting at a time, keeping model, context size, GPU-layer request and backend fixed.
Each profile reloads the model because these llama.cpp context settings are applied at
context creation. A short warm-up precedes a fixed longer prompt at temperature 0 and
top-p 1. The report records prompt-processing time, time-to-first-token, generation
duration, generated token count, throughput and the selected context settings.

The run stops when available system RAM is below its safety threshold, thermal status is
severe, the backend changes unexpectedly, or inference fails. It reloads the user's
original model configuration in a non-cancellable cleanup block. Prompt and response
contents are never written to diagnostics. This is a short configuration smoke test,
not a statistically rigorous benchmark or proof that each operation ran on the GPU.
Interpret prompt processing separately from autoregressive token generation.

## Inference process boundary

The reusable `:llm-engine` library owns the `InferenceService` declaration in its
library manifest. The demo app does not redeclare the service. The merged APK is
checked to contain exactly one service registration with
`android:process=":inference"`, `android:exported="false"` and
`android:isolatedProcess="false"`. This is a separate process under the app's
UID, not Android's separately sandboxed isolated-UID service mode.

The Android instrumentation suite inspects the resolved `ServiceInfo` before
binding and verifies the dedicated service PID, Binder disconnect, process death,
idle-service rebind and automatic client reconnect. It also contains a Debug-only
native test hook: `nativeProcessChat` is deliberately blocked inside JNI and the
real client's prefill watchdog expires. The dedicated recovery test loads a real,
small Q4_K_M GGUF fixture first, then verifies that the app PID survives, the
original `:inference` PID disappears, Binder disconnects, and the surviving app
process persists `PROCESS_ABORT_CONFIRMED` for that original PID. The client then
reconnects, reloads the same original `FilePath` source into the replacement
process and completes a real follow-up inference that generates tokens. The hook
is enabled only for Debug (`LOCAL_LLM_ENABLE_TEST_HOOKS=ON`) and disabled for
Release.

This emulator recovery test uses a small fixture copied from androidTest assets
into app-private cache and CPU inference (`gpuLayers=0`). It does not prove the
Binder handoff of a real SAF-provider descriptor, reload of the user's 2.5-GB
Mali/Vulkan model, Vulkan-driver resource release, or repeated five-cycle
resource stability. Emulator results do not replace a real-device test on the
target phone.

### Cancellation and hard-abort sequence

For an explicit `cancelInference()` request, the client first asks the service
to signal native cooperative cancellation. It waits `abortGracePeriodMs`; if
the request is still active, it sends the one-way AIDL `killProcess()` command.
The service terminates its own `:inference` process immediately and deliberately
does not wait for `engine.close()`, JNI cleanup, file logging, or coroutine
joins.

For a phase timeout, the client now records the pending timeout, sends
`cancelInference(requestId)` to request native cooperative cancellation, and
keeps the request ID active while it waits up to `abortGracePeriodMs` for a
terminal Binder callback. A cooperative terminal callback ends native execution
without killing the process; the client still reports the original timeout as
an error because its deadline was exceeded. If no terminal callback arrives
within the grace period, the client escalates against the original service PID
through the one-way `killProcess()` path, then verifies process death and
uses the client-side same-UID kill only as a fallback. Late completion/error
callbacks cannot turn an already-expired request into a successful result.

Robolectric coverage exercises both branches: a cooperative acknowledgement
must avoid process termination, while a non-cooperative service must not be
hard-killed until the configured grace period has elapsed. The emulator's
blocked-JNI instrumentation test remains the process-death/reconnect check;
real-device behavior is still a separate acceptance requirement.

The client polls the original PID and can issue a same-UID client-side PID kill
if the service did not exit in time. Binder disconnect or confirmed PID death
starts reconnection. The app-side client retains the last successfully loaded
source/configuration and attempts to reload them in the replacement service.
The emulator recovery test now proves this sequence with its pinned tiny GGUF
fixture and a successful token-producing follow-up inference. It is not yet a
proof for a real SAF URI or the large Qwen GGUF on the Mali-G57 device.

This is the implemented process-isolation path, not a real-device acceptance
result. Process termination alone does not prove that a vendor Vulkan driver
has returned all GPU allocations.

## Native llama.cpp source

The engine pins the upstream `ggml-org/llama.cpp` source as a Git submodule at
`a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5` (upstream HEAD checked on 2026-11-09 at
13:48 UTC). This is 51 upstream commits newer than the previous repository pin
`669204ee2b9004845b34f26466a91d003edc98aa`. The APK workflow verifies this exact submodule revision.

To initialize or refresh a clone:

```bash
git submodule update --init --recursive
```

The update includes upstream fixes across llama.cpp and GGML, including Vulkan
changes to RoPE/YARN truncation handling and Argsort pipeline selection. This
is a source update, not evidence of faster inference on the target phone.
Hexagon is not enabled in this Android build, so this pin update does not claim
NPU acceleration.

Android API-24 Vulkan-loader and Mali extension-dispatch compatibility remain
as a build-only overlay: CMake copies upstream `ggml-vulkan.cpp` into the build
directory, applies checked replacements there, and compiles that copy. The
upstream submodule working tree is never edited. The overlay anchors were
checked against the 2026-11-09 upstream source: all three
`vkGetPhysicalDeviceFeatures2` calls and the include, buffer-device-address,
16-bit-storage and device-create anchors are still present. CMake will fail
rather than silently omit the compatibility replacements if those anchors
change again.

## Native Android backends

- ARM64 builds configure the CPU backend with `GGML_CPU_KLEIDIAI=ON`, matching the official llama.cpp Android reference. llama.cpp fetches the pinned KleidiAI v1.24.0 source at CMake configure time and includes applicable Arm kernels. This does not imply every kernel is used on every Arm CPU; runtime CPU features and supported operations determine applicability.
- OpenMP remains disabled pending an Android-device comparison. The upstream Android sample enables it, while the general upstream Android build instructions describe Android limitations. We will decide based on a controlled benchmark, not an assumption.
- The ARM64 Vulkan backend is built when the Android NDK Vulkan loader, host `glslc`, SPIR-V headers and Vulkan-Hpp headers are available. The APK workflow installs and requires these build tools. x86_64 remains CPU-only.
- The APK workflow pins LunarG Vulkan SDK 1.4.363.0 instead of Ubuntu 24.04's `glslc` 2023.8. The latter does not support `GL_EXT_integer_dot_product`; llama.cpp then silently compiles out its quantized MMVQ integer-dot kernels. The native CMake build now fails early unless the pinned shader compiler successfully compiles llama.cpp's integer-dot feature-test shader. At runtime, the compact Vulkan capability line is retained in native diagnostics so the Mali driver's actual `int dot` capability can be distinguished from compile-time shader support.
- Vulkan driver/device detection is not by itself proof that llama.cpp offloaded model layers. The implementation-plan tracks the required registry/offload verification separately.

## Local commands

After cloning with `git clone --recurse-submodules` (or running `git submodule update --init --recursive` in an existing clone), and with Gradle 9.3.1 plus the Android SDK/NDK/CMake versions above available:

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest :llm-engine:testDebugUnitTest
```

## Current limitations

- The supported native ABIs are currently 64-bit `arm64-v8a` and `x86_64`; 32-bit ARM is not included.
- A SAF-selected local GGUF is opened as a `ParcelFileDescriptor` and passed to `:inference`; the service-side loader opens the retained descriptor through `/proc/self/fd/<n>`. Before native loading, the resolver checks that it is a regular file, tests seeking and positional GGUF-header reads, and probes read-only `mmap`. Pipe/non-regular sources are rejected with a no-copy diagnostic. The current instrumentation tests exercise this resolver with a regular file and a pipe in the test process; they do not yet verify a real SAF provider descriptor after the Binder handoff into `:inference` on the target device. APK assets and HTTPS remote sources are still materialized in app-private storage, validated for the GGUF magic header, SHA-256 hashed, and atomically published under a SHA-256-addressed filename. HTTPS redirects are limited and cannot downgrade to HTTP. Downloads are not resumable yet.
- The emulator suite currently runs seven Android instrumentation tests. Its recovery test loads the pinned tiny GGUF fixture, blocks a real JNI inference call, verifies persisted `PROCESS_ABORT_CONFIRMED`, reloads the same `FilePath` into the replacement service and completes a token-producing follow-up inference. Robolectric tests cover the timeout's cooperative-first branch and grace-period escalation. Real SAF-provider/Binder descriptor handoff, the user's large GGUF, five-cycle external resource checks and target-device acceptance remain open.
- No real-device performance result is implied by CI success.

## Implementation plan

The single active implementation backlog and acceptance criteria are in [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md).

## Integration direction

The `:llm-engine` module is intended to be reusable by myDocAnizer-Mobile without importing the demo application's UI or application-specific storage layer. See [INTEGRATION_MYDOCANIZER.md](INTEGRATION_MYDOCANIZER.md) and verify its instructions against the current public API before integrating.
