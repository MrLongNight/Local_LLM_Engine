# Architecture — Local LLM Engine

**Scope:** This repository develops and verifies the reusable `:llm-engine` runtime. Its `:app` is a prototype/test harness only; the demo UI and screen architecture are not productization targets. The current work concerns engine correctness, backend execution, model lifecycle, reliability, memory and performance. Analysis of future consumer integration is deferred until explicitly requested.

**Source review baseline:** code commit `d77db69166bb02542fb9bc89787e347a61867ce2` (2026-11-18). This baseline does not imply physical-device acceptance. Current follow-up measures are maintained only in [`../active-todos/MASTER_TODO.md`](../active-todos/MASTER_TODO.md).

This is the canonical architecture document for the repository. Update it in the same commit whenever module boundaries, package ownership, process boundaries, public API, model flow, IPC or native backend flow changes. Do not maintain competing copies of these diagrams elsewhere.

## 1. Module and runtime boundaries

```mermaid
flowchart TB
  subgraph APP[":app — prototype and test harness"]
    UI[Compose screens and settings sections]
    VM[Shared EngineViewModel facade and feature ViewModels]
    STORE[Prototype model list and LastModelStore]
    UI --> VM
    VM <--> STORE
  end

  subgraph LIB[":llm-engine — reusable Android library"]
    API[Public Kotlin API]
    CLIENT[InferenceClient in app process]
    IMPORT[ModelSourceResolver]
    SERVICE[InferenceService in :inference process]
    IMPL[LocalLlmEngineImpl]
    GUARD[ModelMemoryGuard]
    AIDL[AIDL service and callback contracts]
  end

  subgraph NATIVE["Native Android execution"]
    JNI[NativeEngineHandle and JNI bridge]
    LLAMA[Pinned llama.cpp submodule]
    CPU[GGML CPU backend]
    VULKAN[GGML Vulkan backend]
    JNI --> LLAMA
    LLAMA --> CPU
    LLAMA --> VULKAN
  end

  VM --> API
  API --> CLIENT
  CLIENT --> IMPORT
  CLIENT <--> AIDL
  AIDL <--> SERVICE
  SERVICE --> GUARD
  SERVICE --> IMPL
  IMPL --> JNI

  classDef app fill:#e8f0ff,stroke:#476a9b,color:#172b4d
  classDef lib fill:#e9f7ef,stroke:#3c8060,color:#153d2b
  classDef native fill:#fff3df,stroke:#a8792b,color:#49330e
  class UI,VM,STORE app
  class API,CLIENT,IMPORT,SERVICE,IMPL,GUARD,AIDL lib
  class JNI,LLAMA,CPU,VULKAN native
```

### Ownership and dependency rules

- `:app` is the prototype/test harness only. Its screens, settings, model-list UI and demo persistence are not transferred into myDocAnizer.
- `:llm-engine` owns reusable engine behavior. It must not depend on `:app`, Compose UI, Room or DMS-specific entities.
- Public consumer-facing contracts live in `com.mydocanizer.llm.engine`. Implementation details belong in `internal` and `ipc` packages.
- AIDL contracts live under `llm-engine/src/main/aidl/`. The library manifest owns the private `InferenceService` declaration.
- JNI, CMake, checked Android overlays and the pinned llama.cpp submodule live under `llm-engine/src/main/cpp/`. The upstream submodule itself stays unmodified.
- `InferenceService` runs in a separate `:inference` process under the same app UID (`isolatedProcess=false`); it is not an Android isolated-UID service.

## 2. Model load and inference sequence

```mermaid
sequenceDiagram
  autonumber
  participant Screen as Prototype screen
  participant VM as Shared EngineViewModel
  participant Library as ModelLibrary
  participant API as LocalLlmEngine
  participant Client as InferenceClient
  participant Resolver as ModelSourceResolver
  participant Service as InferenceService
  participant Impl as LocalLlmEngineImpl
  participant JNI as JNI and llama.cpp

  Screen->>VM: User selects one or multiple GGUF documents
  VM->>Library: importFromSaf(uri) — same path for single and multi-select
  Library->>Resolver: resolveToFile(ContentUri)
  Resolver->>Resolver: Validate GGUF, hash and atomically publish private copy
  Resolver-->>Library: Private model file
  Library-->>VM: Imported model entry
  VM-->>Screen: Refresh imported model list

  Screen->>VM: User selects an imported model or starts chat
  VM->>API: loadModel(FilePath, config)
  API->>Client: loadModel(source, config)
  Client->>Resolver: resolveToFile(source)
  alt SAF ContentUri, AssetPath or RemoteUrl
    Resolver->>Resolver: Copy to app-private filesDir/models
    Resolver->>Resolver: Validate GGUF, hash and atomic publish
  else FilePath
    Resolver->>Resolver: Validate readable GGUF file
  end
  Client->>Client: Open ParcelFileDescriptor
  Client->>Service: AIDL loadModel(path, descriptor, config)
  Service->>Service: Memory estimate and effective context selection
  Service->>Impl: loadModel(FilePath(/proc/self/fd/N), effectiveConfig)
  Impl->>JNI: nativeLoadModel
  alt architecture is gemma4-assistant
    JNI->>JNI: Detect required ctx_other target context/shared KV
    JNI-->>Impl: Reject standalone load with native result 107
  else standalone chat architecture
    JNI->>JNI: Create llama.cpp context and backend
    JNI-->>Impl: Model metadata and progress
  end
  Impl-->>Service: ModelInfo
  Service-->>Client: AIDL callback with result and progress
  Client-->>API: Result and StateFlow updates
  API-->>VM: Model state and progress

  Screen->>VM: Send chat request
  VM->>API: streamChat(InferenceRequest)
  API->>Client: Start correlated IPC stream
  Client->>Service: AIDL inference request
  Service->>Impl: Stream inference
  Impl->>JNI: Prompt evaluation and token generation
  Note over JNI: Hybrid/recurrent models clear attention and recurrent backing data before every new request, and again after cancellation or error; model replacement and unload clear before context teardown. Ordinary attention models retain guarded prefix reuse.
  JNI-->>Impl: Progress and token pieces (control/special IDs and malformed UTF-8 rejected natively)
  Note over JNI,Impl: Kotlin output gate rejects empty/invalid/control text; MAX_TOKENS is explicitly marked TRUNCATED
  Impl-->>Service: Inference events
  Service-->>Client: AIDL callback events
  Client-->>API: Flow of InferenceEvent
  API-->>VM: Started, Progress, Token and terminal event
  Note over Client,Service: Request-ID-scoped callback gates serialize delivery; the first terminal event wins and later token/progress/completion callbacks are discarded.
  Note over Client,Service: Each bind has a generation-scoped ServiceConnection; callbacks from an invalidated binding cannot publish an old service PID.
  VM-->>Screen: UI state and generated text
```

## 3. Source layout and placement

| Area | Responsibility |
|---|---|
| `app/src/main/java/com/example/ui/navigation/` | Main navigation screen and `AppTab` navigation contract. |
| `app/src/main/java/com/example/ui/chat/` | Chat screen, chat ViewModel, chat bubbles and inference/model-load progress UI. |
| `app/src/main/java/com/example/ui/modelimport/` | Model-import screen/ViewModels, model-library state, last-model persistence and SAF folder scanning. |
| `app/src/main/java/com/example/ui/settings/` | Settings screen/ViewModel and focused settings sections. |
| `app/src/main/java/com/example/ui/diagnostics/` | Lifecycle/configuration/Vulkan/benchmark ViewModels and their pure plans/policies. |
| `app/src/main/java/com/example/ui/` | Shared `EngineViewModel` facade and app entry point. |




| `app/src/main/java/com/example/ui/settings/sections/` | Focused composable sections used by `SettingsScreen`. |
| App-side model helpers/ViewModels | Prototype-only model selection, app persistence and import orchestration. |
| `llm-engine/src/main/java/com/mydocanizer/llm/engine/` | Public API, request/config/state/result/model types, diagnostics facade, and bounded event-envelope/storage/metrics/report components. |
| `llm-engine/src/main/java/com/mydocanizer/llm/engine/internal/` | Engine implementation, source resolver, native handle, memory guard and internal helpers. |
| `llm-engine/src/main/java/com/mydocanizer/llm/engine/ipc/` | Inference client/service, Binder lifecycle and IPC coordination. |
| `llm-engine/src/main/aidl/` | AIDL interfaces and parcelable declarations crossing the process boundary. |
| `llm-engine/src/main/cpp/CMakeLists.txt` | Native build orchestration, target wiring, compile flags and pinned llama.cpp integration. |
| `llm-engine/src/main/cpp/cmake/` | Android toolchain and upstream configuration, generated graph/GDN and Vulkan overlays, native target wiring and scheduler-race verification modules. |
| `llm-engine/src/main/cpp/` (JNI and headers) | JNI bridge, checked native utility/policy headers and pinned llama.cpp submodule. |
| `app/src/test/`, `llm-engine/src/test/`, `llm-engine/src/androidTest/` | JVM/host tests and Android instrumentation for the corresponding production responsibility. |

## 4. Architecture documentation gate

Before a code change:

1. Inspect the existing module/package tree and the nearest implementation before creating or moving files.
2. Choose the final responsibility-aligned file, package and module before writing the implementation. Do not place code temporarily in a convenient but incorrect location.
3. Reuse existing contracts and helpers; check whether equivalent functionality already exists before introducing a new class or abstraction.
4. Review the Mermaid diagrams and source-layout table against the proposed change.

After a structural code change, update the affected Mermaid diagram(s) and source-layout table in the same commit. For a change that does not alter architecture or control flow, leave the diagrams unchanged rather than editing them decoratively.

The diagrams describe ownership and control flow. They do not prove backend availability, GPU layer offload, model compatibility or performance; those require runtime diagnostics and device acceptance.
