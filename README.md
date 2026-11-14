# Local LLM Engine

An Android prototype for developing, testing and validating local GGUF inference with `llama.cpp`. The repository contains a demo app and a reusable Android library module. It is an engineering and measurement project, not a production-ready consumer app.

> **Verification boundary:** A green JVM test, APK build or emulator test does not prove that a large model loads correctly, produces correct output, uses the requested GPU path or performs well on a physical Android device. Target-device acceptance is tracked separately.

## Modules

- `:app` — prototype/demo UI, model selection, configuration, diagnostics and on-device test flows.
- `:llm-engine` — reusable Android library, public Kotlin API, AIDL process boundary, JNI and native `llama.cpp` integration.
- `:llm-engine/src/main/cpp/llama.cpp` — pinned upstream Git submodule. Current pin: `a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5`.

The app module is a test harness for the engine. It is not the downstream myDocAnizer-Mobile application. The planned downstream integration is documented separately and remains an important use case for the library.

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
- `ModelSource` supports private/local files, Android SAF content URIs, packaged assets and HTTPS downloads. SAF/asset/remote imports are materialized and verified in app-private storage; see the integration guide for exact behavior and constraints.
- `BackendPreference.VULKAN` requires a usable Vulkan backend and fails rather than silently switching to CPU. `AUTO` may fall back to CPU. A requested GPU-layer count or Vulkan selection alone is not proof of actual GPU execution.
- Qwen3.5 Gated Delta Net operators are currently forced to the CPU reference path by the Android Vulkan overlay; other eligible graph operations may still run through Vulkan.
- The demo app persists the last selected model/configuration across cold starts. That demo-app behavior is not an automatic persistence feature of the library API.

## Documentation

All project documentation is organized by document type under [`docs/`](docs/README.md):

- [Technical documentation](docs/technical/) — model compatibility, CI signing and change history.
- [Active todos](docs/active-todos/) — current refactoring status and model-aware cache work.
- [Planning](docs/planning/) — overall implementation plan and the downstream myDocAnizer-Mobile integration plan.
- [Agent rules](docs/AGENTS.md) — repository invariants and documentation ownership.

Start with the [documentation index](docs/README.md). The myDocAnizer-Mobile integration guide is intentionally retained as a key downstream integration document; it does not change the fact that this repository itself is an independent engine prototype.
