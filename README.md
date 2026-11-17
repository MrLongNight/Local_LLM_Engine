# Local LLM Engine

An Android engine-development prototype for the local GGUF runtime used by `myDocAnizer_Mobile`. The current project objective is to replace the main app's old inference runtime with the reusable `:llm-engine` module developed here. This repository's `:app` is only a test harness, not a separate target app.

> **Verification boundary:** A green JVM test, APK build or emulator test does not prove that a large model loads correctly, produces correct output, uses the requested GPU path or performs well on a physical Android device. Target-device acceptance is tracked separately.

## Modules

- `:app` — prototype/demo UI, model selection, configuration, diagnostics and on-device test flows.
- `:llm-engine` — reusable Android library, public Kotlin API, AIDL process boundary, JNI and native `llama.cpp` integration.
- `:llm-engine/src/main/cpp/llama.cpp` — pinned upstream Git submodule. Current pin: `a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5`.

The intended consumer is `MrLongNight/myDocAnizer_Mobile`. Only `:llm-engine` is transferred; do not import the prototype's Compose demo, benchmark screens or model catalogue. OCR, DMS prompts/parsing, document workflows and the main app's model-management UI remain owned by myDocAnizer.

## Build requirements

- JDK 21 for the current build/test workflows; Android Java bytecode compatibility remains Java 17.
- Gradle 9.3.1. The wrapper properties are present, but wrapper launcher scripts/JAR are not checked in; CI installs Gradle directly.
- Android SDK platform 36, Android NDK `27.2.12479018`, CMake `3.22.1`.
- Git with recursive submodule checkout.
- Native ABIs: `arm64-v8a` and `x86_64`. The configured Android Vulkan path is ARM64; x86_64 is CPU-only.

Clone and initialize the pinned source:

```bash
git clone --recurse-submodules https://github.com/MrLongNight/Local_LLM_Engine.git
cd Local_LLM_Engine
git submodule update --init --recursive
```

Build the demo APK and instrumentation APK:

```bash
gradle --no-daemon :app:assembleDebug :llm-engine:assembleDebugAndroidTest
```

Run JVM and host-native tests:

```bash
gradle --no-daemon --continue --build-cache :llm-engine:testDebugUnitTest :app:testDebugUnitTest
```

## Runtime design at a glance

- `LocalLlmEngine.create(context)` exposes the library API. Inference runs through the library-owned, non-exported `:inference` service process; the service is a separate process under the app UID, not an isolated-UID service.
- `ModelSource` supports private/local files, Android SAF content URIs, packaged assets and HTTPS downloads. Direct `ContentUri` model loading uses the selected provider's file descriptor without copying when the provider exposes a seekable regular file; unsupported provider descriptors need an explicit alternative.
- `BackendPreference.VULKAN` requires a usable Vulkan backend and fails rather than silently switching to CPU. `AUTO` may fall back to CPU. A requested GPU-layer count or Vulkan selection alone is not proof of actual GPU execution.
- Qwen3.5 Gated Delta Net operators are currently forced to the CPU reference path by the Android Vulkan overlay; other eligible graph operations may still run through Vulkan.
- The demo app persists the last selected model/configuration across cold starts. That demo-app behavior is not an automatic persistence feature of the library API.

## Documentation

All project documentation is organized by document type under [`docs/`](docs/README.md):

- [Technical documentation](docs/technical/) — architecture, model compatibility, CI signing and change history.
- [Mermaid architecture](docs/technical/ARCHITECTURE.md) — canonical module, process and inference-flow diagrams.
- [Master TODO](docs/active-todos/MASTER_TODO.md) — the single prioritized list of current architecture, reliability, cleanup and integration measures.
- [Planning](docs/planning/) — overall implementation plan and the downstream myDocAnizer-Mobile integration plan.
- [Agent rules](docs/AGENTS.md) — repository invariants and documentation ownership.

Start with the [documentation index](docs/README.md). The myDocAnizer-Mobile integration guide defines the active migration contract. The audit report evaluates the previous 89 open tasks and records which prototype tasks remain relevant.
