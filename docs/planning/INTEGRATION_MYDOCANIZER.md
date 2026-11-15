# Integration plan: `:llm-engine` in myDocAnizer-Mobile

**Purpose:** This guide describes how the standalone Local LLM Engine prototype can be embedded later as a reusable Android library in `MrLongNight/myDocAnizer_Mobile`. The current repository remains an independent engine-development/test prototype; this document records the intended downstream integration contract.

> **Compatibility gate:** This is a plan based on the current `main` API, not proof that the library has already been integrated into myDocAnizer-Mobile. Before an integration release, pin the exact Local_LLM_Engine commit and rerun the consumer build and device acceptance tests against that commit. The current prioritized actions are tracked only in [`../active-todos/MASTER_TODO.md`](../active-todos/MASTER_TODO.md); this guide describes the integration contract, not a parallel TODO list.

## 1. Integration shape

`:llm-engine` is an Android library module. Its public API lives in `com.mydocanizer.llm.engine`; native inference, JNI and the AIDL client/service implementation are encapsulated by the library.

The intended boundary is:

- myDocAnizer-Mobile owns document management, Room entities/DAOs, OCR, document workflows, app navigation and product-specific UI.
- `:llm-engine` owns GGUF loading, chat requests, token/progress streaming, inference lifecycle, backend selection, native diagnostics and process isolation.
- The engine library does not depend on Room, Compose, Firebase or DMS-specific types. The consuming app supplies OCR text and prompts as ordinary chat messages.
- Do not move DMS business rules or document persistence into the engine module.

## 2. Add the module to the consumer project

Use a pinned repository submodule or a reviewed vendored copy of the `llm-engine` module. When using a Git submodule, initialize nested submodules recursively so the pinned llama.cpp source is present.

```bash
git submodule add https://github.com/MrLongNight/Local_LLM_Engine.git third_party/Local_LLM_Engine
git -C third_party/Local_LLM_Engine checkout <reviewed-commit>
git -C third_party/Local_LLM_Engine submodule update --init --recursive
```

Then include the module from the consumer's `settings.gradle.kts` (adjust the relative path to the actual submodule location):

```kotlin
include(":llm-engine")
project(":llm-engine").projectDir = file("third_party/Local_LLM_Engine/llm-engine")
```

Add the project dependency to the consuming app module:

```kotlin
dependencies {
    implementation(project(":llm-engine"))
}
```

Do not include the prototype's `:app` module in myDocAnizer-Mobile. Only `:llm-engine` is the intended reusable module.

## 3. Toolchain and native build requirements

Match the library's checked-in Gradle and CMake configuration, or validate an explicitly reviewed compatibility update before integrating:

| Item | Current engine configuration |
|---|---|
| Android Gradle Plugin | 9.1.1 |
| Kotlin | 2.2.10 (version-catalog value) |
| Gradle | 9.3.1 |
| JDK for CI build/tests | 21 |
| Android Java bytecode | Java 17 source/target |
| Android compile SDK | 36 (minor API level 1 in the library module) |
| Library minimum SDK | 24 |
| Android NDK | `27.2.12479018` |
| CMake | `3.22.1` |
| Native ABIs | `arm64-v8a`, `x86_64` |

`targetSdk` is controlled by the consuming application; the library module does not set a separate target SDK. The prototype CI does not check in Gradle wrapper launcher files/JAR, so its workflows install Gradle 9.3.1 directly.

ARM64 builds include the CPU backend with KleidiAI and the configured Vulkan path when the required Android Vulkan loader, SPIR-V/Vulkan headers and pinned `glslc` toolchain are available. The current CI pins LunarG Vulkan SDK 1.4.363.0 and verifies integer-dot shader support. The x86_64 build is CPU-only. Do not infer Vulkan availability on every Android device from the ABI alone.

## 4. Public API and lifecycle

Create one library facade for the application/session scope and share it across ViewModels/screens. Do not create a new engine per screen or per prompt.

```kotlin
import com.mydocanizer.llm.engine.LocalLlmEngine

val engine: LocalLlmEngine = LocalLlmEngine.create(applicationContext)
```

The facade exposes these principal members:

- `state: StateFlow<EngineState>` — `Uninitialized`, `Initializing`, `Ready`, `LoadingModel`, `ModelLoaded`, `Inferring`, `Reconnecting`, `Error` and `Closed`.
- `currentModel: StateFlow<ModelInfo?>` and `backendInfo: StateFlow<BackendInfo>`.
- `modelLoadProgress: StateFlow<Int?>` and `modelLoadProgressEvents: SharedFlow<Int>`.
- `initialize()`, `loadModel()`, `unloadModel()`, `streamChat()`, `generateChat()`, `cancelInference()` and `close()`.

The library manifest owns the non-exported `InferenceService` declaration. It runs in a dedicated `:inference` process with `android:isolatedProcess=false` under the application's UID. Do not redeclare the service in the consuming app manifest or bypass the public facade to call JNI directly.

Close the shared facade from the owner that created it (for example, the application-scoped engine owner or a ViewModel's final lifecycle callback). Do not close it when an individual screen is merely removed from navigation.

### Example owner

```kotlin
class EngineOwner(context: Context) : Closeable {
    val engine: LocalLlmEngine = LocalLlmEngine.create(context.applicationContext)

    override fun close() {
        engine.close()
    }
}
```

## 5. GGUF sources and model-library behavior

`ModelSource` currently supports four source kinds:

| Source | Behavior |
|---|---|
| `ModelSource.FilePath(path)` | Loads a readable local file. App-private storage is the preferred stable source for a model library. |
| `ModelSource.ContentUri(uriString, displayName)` | Imports the SAF document into `<app filesDir>/models` before loading. The source document is not modified. |
| `ModelSource.AssetPath(assetName)` | Copies a packaged asset to app-private model storage, then loads the verified file. |
| `ModelSource.RemoteUrl(url, suggestedFileName)` | Downloads from HTTPS into app-private storage, validates GGUF magic, hashes and atomically publishes the file. |

SAF, asset and HTTPS imports stream through a temporary `.part` file, verify the `GGUF` magic header, calculate SHA-256 and publish by atomic same-filesystem rename. The stored filename is content-addressed (`<sha256>-<original-name>`); use `ModelLibrary.displayName()` for a user-facing original filename. Existing same-content imports can be reused when SAF provider size and modification-time metadata are stable.

SAF imports check available disk space before copying when the provider reports a file size. HTTPS downloads require HTTPS, reject embedded URL credentials and HTTPS-to-HTTP redirects, and allow at most five redirects. Downloads are currently not resumable. The resolver does not overwrite the original SAF document.

For a model-library screen that needs to import without immediately loading into RAM, use:

```kotlin
val file = ModelLibrary.importFromSaf(context, uri, displayName)
val displayName = ModelLibrary.displayName(file.name)
```

Then load the returned private path:

```kotlin
engine.loadModel(ModelSource.FilePath(file.absolutePath), config)
```

The optional borrowed-descriptor `FilePath("/proc/self/fd/N", descriptor, descriptorFd)` path is an advanced no-copy mechanism. Only use it when the provider supplies a regular, seekable and read-only-mmap-capable descriptor, keep the borrowed descriptor open until `loadModel()` returns, and validate the complete Binder/service handoff on target hardware. Prefer the normal `ContentUri` import path for the initial product integration.

## 6. Load configuration and backend expectations

Example starting configuration (tune only after validating a specific model and device):

```kotlin
val config = EngineConfig(
    contextSize = 2048,
    batchSize = 128,
    threads = 4,
    temperature = 0.2f,
    topP = 0.9f,
    maxPredictTokens = 1024,
    backendPreference = BackendPreference.AUTO,
    gpuLayers = 99
)

val result = engine.loadModel(
    ModelSource.FilePath(modelFile.absolutePath),
    config
)
val modelInfo = result.getOrThrow()
```

`gpuLayers = 99` requests the maximum offload; it does not guarantee that all layers execute on the GPU. Read `ModelInfo.activeBackend` and native diagnostics, including actual layer counts and per-run graph backend distribution. A Vulkan backend name or requested layer count alone is not proof of complete GPU execution.

Backend policy:

- `BackendPreference.CPU` explicitly selects CPU.
- `BackendPreference.VULKAN` requires a usable Vulkan backend and returns a load error rather than silently falling back to CPU.
- `BackendPreference.AUTO` prefers a registered GPU backend and can retry CPU when the Vulkan model load fails.
- On x86_64 in this prototype build, the configured native path is CPU-only.
- Qwen3.5/Qwen3.5-MoE Gated Delta Net operations are deliberately routed to CPU by the Android Vulkan overlay for correctness. Other supported graph operations can still use Vulkan.

The model-load guard estimates model file bytes plus an estimated KV cache and 256 MiB runtime headroom. It reduces context when required; the hard refusal boundary is 85% of total system RAM. Available RAM and Android low-memory status are pressure signals, not the sole hard gate. `EngineConfig.memoryGuardOverride` is intended only for an explicit, one-load user acknowledgement after the guard blocks a model; do not enable it silently by default.

## 7. Document/OCR inference request

The consuming app keeps OCR and document logic. It passes the extracted text and a domain-specific system prompt to the engine:

```kotlin
val systemPrompt = """
    You are the local document extraction component.
    Extract document type, sender, date, currency and total amount.
    Return valid JSON matching the application's schema.
""".trimIndent()

val request = InferenceRequest(
    messages = listOf(
        ChatMessage.system(systemPrompt),
        ChatMessage.user("Extract metadata from this OCR text:\n$ocrText")
    ),
    maxPredictTokens = 1024,
    temperature = 0.1f,
    enableThinking = false
)
```

`InferenceRequest` requires a non-empty message list. It supports `maxPredictTokens`, optional per-request `temperature` and `topP`, optional `timeoutConfig` and `enableThinking` (default `false`). `ChatMessage` supports `SYSTEM`, `USER`, `ASSISTANT` and `DEVELOPER` roles.

## 8. Streaming, progress and result handling

`streamChat()` returns a Kotlin `Flow<InferenceEvent>`. Consumers should handle all terminal event types and surface progress separately from generated text:

```kotlin
val answer = StringBuilder()

engine.streamChat(request).collect { event ->
    when (event) {
        is InferenceEvent.Started -> onStarted(event.promptMessageCount)
        is InferenceEvent.Progress -> onProgress(
            phase = event.phase,
            promptProcessed = event.promptTokensProcessed,
            promptTotal = event.promptTokensTotal,
            generated = event.tokensGenerated
        )
        is InferenceEvent.Token -> {
            answer.append(event.token)
            onPartialAnswer(answer.toString())
        }
        is InferenceEvent.Completed -> onCompleted(event.result)
        is InferenceEvent.Cancelled -> onCancelled(event.partialText)
        is InferenceEvent.Error -> onFailed(event.throwable, event.partialText)
    }
}
```

`InferenceEvent.Progress` reports phases (`PROMPT_PREPARATION`, `PROMPT_EVALUATION`, `WAITING_FOR_FIRST_TOKEN`, `GENERATING`), prompt-token progress and generated-token progress. `InferenceEvent.Started.promptMessageCount` is a message count, not a token count.

`InferenceResult` includes response text, generated-token count, wall-clock duration, `tokensPerSecond`, finish reason, graph-split count and diagnostic/native run IDs. Do not treat the aggregate result rate as a pure decode-only benchmark: compare prefill, time-to-first-token and autoregressive decode separately using the diagnostics export.

## 9. Cancellation and timeout behavior

The default `TimeoutConfig` is:

| Timeout | Default |
|---|---:|
| Service bind | 10 s |
| Model load | 180 s |
| Prompt prefill | 120 s |
| First token | 120 s |
| Between tokens | 30 s |
| Total inference | 600 s |
| Cooperative-abort grace | 3 s |
| Reconnect | 15 s |

Coroutine cancellation and `cancelInference()` request cooperative native cancellation first. If the inference service does not finish within `abortGracePeriodMs`, the client escalates by terminating the original `:inference` process and reconnecting. CPU decode has the pinned llama.cpp abort-callback path; Vulkan retains process-level termination as a backend-independent fallback. A timeout remains a timeout even if the service later reports completion.

The IPC client retains the last successfully loaded source/configuration during the client lifetime. Recovery after service death can be LAZY (reload on the next chat; default) or EAGER. This is distinct from cold-start persistence: the demo app's `LastModelStore` is app-level prototype code. A consuming app must implement its own remembered-model policy if it wants automatic model restoration after a full application restart.

## 10. Diagnostics, privacy and acceptance

The library writes bounded diagnostics without recording normal prompt/response contents. Debug-only text diagnostics are a separate opt-in/debug facility and must not be enabled in production builds. Correlate token measurements and resource samples by their run IDs.

Before treating the module as integrated, verify on the actual consumer app and target device:

1. Build the consuming app with the pinned library commit, recursive llama.cpp submodule and both intended ABIs.
2. Import a representative GGUF through SAF; confirm the original document is unchanged, the app-private copy is complete and its SHA-256 is stable.
3. Load and chat with explicit CPU and explicit Vulkan requests. A Vulkan request must not silently pass as CPU.
4. Confirm model architecture, chat-template behavior, finish reason, actual GPU offload and CPU/Vulkan graph-node distribution.
5. Exercise progress, cancellation, each timeout tier, service death/reconnect, reload and a successful follow-up inference.
6. Repeat load/inference/abort/reconnect cycles while measuring PSS, native/graphics memory and Vulkan heap/budget data where exposed.
7. Test the consuming app's own cold-start model persistence and app lifecycle. Do not assume the demo app's remembered-model preferences are part of the library.

Emulator and CI success are necessary engineering checks, but they do not replace the target-device acceptance run. Qwen3.5, Gemma 4 and other model families remain individually unverified until the exact GGUF and consumer/device combination passes these gates.
