# Master TODO — Local LLM Engine

**Purpose:** The single active implementation queue for architecture, correctness, security, cleanup and downstream integration.  
**Repository:** `MrLongNight/Local_LLM_Engine` · **Branch:** `main`  
**Audit baseline:** `3426533a8d7e6c4be99e898d75ad70586446eb53` (14 November 2026)  
**Target consumer:** `MrLongNight/myDocAnizer_Mobile`

> **Single source of truth:** This is the only active task list in the repository. Technical references, integration guides and historical reports may explain contracts and evidence, but must not contain competing active checklists. Add new findings here, merge duplicates and remove tasks only after acceptance evidence is recorded.

## Priority and execution rules

- **P0 — Integration blocker:** privacy, data integrity, original-file handling, public API/consumer compatibility or required device acceptance.
- **P1 — Architecture foundation:** stable responsibility boundaries and lifecycle contracts needed before substantial integration.
- **P2 — Reliability and maintainability:** tests, source organization, dependency hygiene, cache work and build reproducibility.
- **P3 — Conditional feature cleanup:** remove or retain optional features only after confirming consumer requirements.

Agents work on existing `main` only. Re-read HEAD and affected blob SHAs before each write; use expected-HEAD leases. Make small, independently verifiable commits. Preserve public Kotlin/Java APIs, AIDL and Parcelable layouts, manifests, process names, JNI exports, ABI support and pinned llama.cpp revision. Do not introduce DI frameworks, dependencies, NPU backends, speculative abstractions or performance-default changes as side effects of refactoring. Preserve one shared engine instance in the demo app. Correctness evidence precedes performance tuning. Report JVM tests, APK builds, emulator instrumentation and physical-device acceptance as separate gates.

---

## P0 — Integration blockers

### P0-01 — Make diagnostics safe for document and conversation data

**Evidence:** Debug currently enables `TEXT_DIAGNOSTICS_ENABLED` and `LOCAL_LLM_ENABLE_TEXT_DIAGNOSTICS`. `EngineDiagnostics.testText()` persists through the normal log path. Benchmark/configuration callers can send generated output to it; native text tracing can record rendered prompt/template text and token pieces. This conflicts with the rule that prompts and responses must never enter persistent logs.

- [ ] Remove raw prompt, OCR/document text, rendered template text, generated response text and token-piece strings from persistent logs, in-memory exports and compact reports.
- [ ] Review whether token IDs/logits or other retained traces can expose user content. Keep only bounded structured evidence required for engineering: run IDs, counts, timings, backend/kernel selection, graph assignment and categorized errors.
- [ ] Make text-level tracing an explicit disposable synthetic-test-only opt-in. Default Debug and Release builds must be content-safe.
- [ ] Correct comments/docs that describe text diagnostics as opt-in when Debug currently enables them by default.
- [ ] Add sentinel tests that pass unique fake prompt/response strings through Kotlin and native diagnostic paths and prove they do not appear in logs, compact reports or exported diagnostics.

**Acceptance:** No user/document prompt or response can be persisted or exported by any supported build configuration. Structured timing/backend evidence remains available and correlated by run IDs.

### P0-02 — Load selected GGUF files from their original location by default

**Evidence:** The normal picker calls `ModelSource.ContentUri`; `ModelSourceResolver` materializes the source in `filesDir/models` under a content-addressed internal filename. A borrowed descriptor path exists through `ModelSource.FilePath("/proc/self/fd/N", descriptor, descriptorFd)`, but the normal selected-file workflow is not connected to a proven picker → Binder → service handoff. Folder/bulk import is a separate copy operation.

- [ ] Make “load selected model” use the original selected GGUF URI/file and original display name when Android supplies a regular, seekable, read-only-mmap-capable descriptor.
- [ ] Define descriptor ownership precisely: keep the caller descriptor open until the service has safely duplicated/accepted it; close every owned descriptor exactly once on success, failure, cancellation, timeout and process death.
- [ ] Do not silently copy, rename or relocate a selected model as a side effect of loading. Show original filename and source location in model state.
- [ ] Keep “copy/import into managed private model storage” as a separate explicit user action for bulk management or unsupported providers, not an invisible load fallback.
- [ ] For pipe, non-seekable or provider descriptors that cannot be mmap-loaded, return a clear reason and require an explicit import/copy choice.
- [ ] Add device instrumentation for picker URI/descriptor acquisition, Binder transfer, service-process lifetime, native load, unload, cancellation and follow-up inference. Cover local storage and representative cloud/document providers.

**Acceptance:** Normal load uses the original GGUF without creating a renamed private copy. Any copy is explicit. Descriptor rejection/fallback is visible, deterministic and tested end to end.

### P0-03 — Freeze and verify the reusable library/consumer contract

**Evidence:** `:app` is a prototype/test harness; only `:llm-engine` is intended for myDocAnizer-Mobile. Some Parcelable compatibility tests exist, but `EngineConfig` lacks comprehensive legacy-payload coverage. Current CI verifies the demo app, not the real downstream consumer build.

- [ ] Define the minimal stable consumer API: engine ownership/lifecycle, model source and identity, configuration, streaming progress/terminal events, cancellation, diagnostics export and close semantics.
- [ ] Keep prototype UI, benchmarking orchestration, demo persistence and DMS concepts out of the reusable library.
- [ ] Add old/new parcel compatibility tests for `EngineConfig`, `TimeoutConfig`, `InferenceRequest`, `InferenceResult`, model/backend parcelables and all AIDL data crossing the process boundary. Test appended fields, enum fallbacks and truncated legacy payloads.
- [ ] Add a public API baseline/compatibility check so accidental API removals/additions are deliberate and reviewable.
- [ ] Add a minimal consumer smoke build using only the pinned `:llm-engine` module and recursive llama.cpp submodule; preferably run the actual myDocAnizer-Mobile build against a pinned engine commit.
- [ ] Verify consumer manifest merge, private `:inference` service registration, JNI/native ABI packaging, minSdk, coroutine lifecycle and dependency resolution without importing `:app`.
- [ ] Record exact engine commit, consumer build result and consumer instrumentation result. A demo APK is not proof of downstream integration.

**Acceptance:** The library builds and runs independently of the prototype app; cross-process contracts are backwards-compatible and tested; a pinned consumer build passes.

### P0-04 — Complete actual target-device acceptance

**Evidence:** Unit CI and Android-36 x86_64 emulator tests do not validate Mali-G57 drivers, real SAF descriptors, actual GPU layer assignment, graphics-memory release or real user-model recovery. The compatibility catalogue still marks Qwen3.5 and Gemma 4 device paths unverified.

- [ ] On nubia Z2462N / Android 16 / ARM64 / Mali-G57, test representative Qwen3-4B and each model family intended for downstream use with explicit CPU and Vulkan selection.
- [ ] Verify model identity, prompt/template correctness, output correctness, finish reason, actual offloaded layer count and per-run CPU/Vulkan graph-node assignment. Requested GPU layers or a Vulkan label alone are not proof of GPU execution.
- [ ] Verify Q4_K prefill/decode path and profiler `runId` correlation on-device; repeat performance comparisons with profiling disabled.
- [ ] Exercise timeout, cooperative cancellation, hard process abort, reconnect, same-model reload and successful follow-up inference with a real user-sized GGUF and actual selected-file descriptor path.
- [ ] Run at least five load/inference/timeout/kill/reconnect cycles. Measure app-side before/after PSS, native/graphics PSS and Vulkan heap/budget data where available. The killed service's own resource monitor cannot prove post-kill release.
- [ ] Record exact device/build/model/configuration, raw evidence, failures and acceptance status in the technical compatibility record. Keep emulator and physical-device evidence distinct.

**Acceptance:** Each supported model/device combination has explicit load, correctness, backend, resource and recovery evidence. No generic performance or production-readiness claim is made from CI alone.

---

## P1 — Architecture and correctness foundation

### P1-01 — Replace the deep ViewModel inheritance chain with composition

**Evidence:** The shared `EngineViewModel` inherits through `EngineLifecycleViewModel → ConfigurationSmokeViewModel → VulkanDiagnosticsViewModel → BackendBenchmarkViewModel → BenchmarkDiagnosticsViewModel → SettingsViewModel → ModelImportViewModel → ModelManagementViewModel → ChatViewModel`. This preserves one instance but couples unrelated features through inherited protected state and implementation order. Several extracted files retain imports from the pre-split parent.

- [ ] Keep the existing public `EngineViewModel` constructor and screen-facing facade initially, but move feature behavior behind cohesive internal controllers/components composed by the facade.
- [ ] Separate chat/session state, model selection/loading, explicit import, settings, diagnostics/benchmarks and engine lifecycle. Each component owns its state and dependencies; no inheritance-only visibility sharing.
- [ ] Keep one engine instance and one application-level owner. Do not create one engine or independent engine ViewModel per screen.
- [ ] Define explicit state ownership and one-way state/event flow between facade and feature controllers; avoid duplicate mutable copies of engine state.
- [ ] Remove unused imports and copied dependencies left by the mechanical extraction.
- [ ] Add tests for shared engine ownership, startup/restore order, cross-feature operation locks, navigation and lifecycle close semantics.

**Acceptance:** No deep feature inheritance chain remains. Each feature has a clear owner and test boundary; screen call sites and single-engine lifecycle remain compatible.

### P1-02 — Decompose the IPC client by responsibility

**Evidence:** `InferenceClient.kt` is approximately 1,455 lines / 70 KB. It combines service binding, Binder callbacks, PID/liveness inspection, reconnect/model recovery, model-load serialization, stream coordination, timeout watchdogs, cancellation escalation and terminal event delivery.

- [ ] Preserve `InferenceClient` as the internal facade while extracting collaborators for service binding, process liveness/abort, reconnect/model recovery, serialized model operations and inference stream/timeout coordination.
- [ ] Define ownership of Binder references, active request IDs, jobs, mutexes, callbacks and last-loaded model snapshot. Do not duplicate state.
- [ ] Make transitions explicit and model stale callbacks from old PIDs, replacement processes, repeated terminal callbacks and close during reconnect.
- [ ] Keep cooperative cancellation first, bounded grace period, PID-safe hard abort and reconnect semantics unchanged unless a failing test proves a defect.
- [ ] Preserve public API and AIDL wire contract; add focused tests for extracted collaborators plus existing process tests.

**Acceptance:** The IPC facade coordinates cohesive components rather than implementing all transport/lifecycle concerns. Timeout, cancellation, PID-safety and recovery tests remain green.

### P1-03 — Separate native JNI responsibilities without changing the boundary

**Evidence:** `local_llm_engine_jni.cpp` is approximately 2,384 lines / 104 KB. It owns engine state, backend setup, model loading, chat-template preparation, prompt cache, token generation, progress callbacks, metrics, text traces, cancellation, diagnostics and test hooks.

- [ ] Group native code by responsibility: engine/backend lifecycle, model loading, prompt/template and cache, inference/decode, metrics/diagnostics, and test hooks.
- [ ] Move shared state/helpers into narrowly scoped internal headers/source files. Keep `EngineInstance` ownership and synchronization unambiguous.
- [ ] Keep every exported JNI symbol and Java native declaration compatible unless a dedicated bridge change is approved.
- [ ] Ensure no C++ exception crosses JNI; retain uniform exception conversion and cleanup on every native failure path.
- [ ] Keep upstream llama.cpp pristine. Android-specific modifications remain checked build-directory overlays.
- [ ] Retain native unit tests for cache/parsing utilities and boundary/instrumentation tests for load, inference, abort and destroy.

**Acceptance:** JNI entrypoints remain stable, native ownership is clearer, no behavior/default changes are introduced, and ARM64/x86_64 native builds and tests pass.

### P1-04 — Make CMake and Android overlays modular and reviewable

**Evidence:** `CMakeLists.txt` is approximately 1,231 lines / 62 KB and contains generated-source transformations and verification gates for graph diagnostics, Qwen GDN policy, Android Vulkan compatibility, Q4_K instrumentation and profiler integration.

- [ ] Split CMake into responsibility-named helpers: upstream configuration, Android Vulkan overlay, llama-context diagnostics overlay, profiler overlay, native sources/targets and build verification.
- [ ] Keep `CMakeLists.txt` as a small orchestration entry point; do not change target composition or compiler flags as part of the structural move.
- [ ] Preserve fail-fast anchor checks against the exact pinned upstream source. Changed anchors must fail configuration rather than silently create a partial binary.
- [ ] Preserve build-directory-only generated sources and prove the git submodule remains pristine.
- [ ] Keep CI checks for ARM64 Vulkan source selection, overlay markers, shader features, optimization flags, graph diagnostics and profiler configuration.

**Acceptance:** Same generated compile commands and target behavior for both ABIs; overlays remain deterministic, verified and isolated from upstream source.

### P1-05 — Split diagnostics storage, parsing and export

**Evidence:** `EngineDiagnostics.kt` is approximately 904 lines / 45 KB and combines process-local logging, persistence/rotation, multi-process log reading, metric parsers, aggregation, compact report building and export.

- [ ] Separate stable diagnostics facade from process log storage, structured parsing, metric aggregation and compact export/report composition.
- [ ] Define a versioned structured event schema with bounded fields and explicit run/request/PID correlation; keep legacy parser support only where existing logs require it.
- [ ] Make cross-process log merging deterministic and bounded. Avoid repeated full-directory reads and unbounded intermediate lists during export.
- [ ] Keep profiler overhead opt-in and clearly distinguish profiler-enabled diagnostics from profiler-free performance measurements.
- [ ] Add focused parser/aggregation/export tests and privacy sentinel coverage before moving code.

**Acceptance:** A small facade exposes stable operations; parsers/exporters have independent tests; exports remain bounded, privacy-safe and run-correlated.

### P1-06 — Correct cancellation and lifecycle propagation in model import

**Evidence:** In `ModelImportViewModel.importModelsFromSaf()`, the per-file `catch (Exception)` does not explicitly rethrow `CancellationException`, unlike the folder-import path. Cancellation can therefore be counted as a failed file and the loop may proceed instead of stopping promptly.

- [ ] Rethrow `CancellationException` before handling recoverable per-file failures.
- [ ] Ensure cancelling a bulk import stops new file work, leaves completed imports intact, removes incomplete temporary files and resets UI state exactly once.
- [ ] Distinguish user cancellation from provider/file failure; never report a cancelled operation as successfully completed.
- [ ] Add tests for cancellation during metadata lookup, copy, verification and between files; include folder scan cancellation and lifecycle teardown.

**Acceptance:** Cancellation is never swallowed as a recoverable file error; cleanup and final UI state are deterministic.

### P1-07 — Finish a model-aware in-memory cache strategy safely

**Evidence:** The native engine mirrors sequence-0 tokens and uses longest-common-prefix reuse where sequence removal is supported. Hybrid/recurrent models deliberately fall back to clearing state. This is safe but incomplete. Token-prefix match alone is insufficient when attention, recurrent/SSM, convolution or sliding-window state must remain synchronized.

- [ ] Audit the exact pinned llama.cpp revision and record state behavior for ordinary attention, sliding-window, recurrent/SSM and convolution state; classify intended architectures separately, especially LFM3 and Qwen3.5.
- [ ] Define cache identity/invalidation for model file identity, adapters, tokenizer/vocabulary, rendered chat template, BOS/EOS, system prompt, context settings and model-specific state.
- [ ] Isolate cache ownership by model instance and conversation/session; define prompt edits, message deletion, tool calls/results, truncation and context shifts.
- [ ] Retain verified prefix reuse for supported architectures. Enable hybrid/recurrent reuse only when every state component can be preserved/restored through documented APIs.
- [ ] Preserve a full-clear fallback whenever state integrity is uncertain. Keep CPU/Vulkan correctness contracts identical and synchronize asynchronous Vulkan work where required.
- [ ] Bound cache memory, account for non-KV hybrid state, integrate memory pressure and evict deterministically. Invalidate after unload/reload, process restart, failed/cancelled inference and uncertainty.
- [ ] Add cache hit/miss/fallback/invalidation reason codes and metrics correlated with native run ID and IPC/RAM request ID.
- [ ] Prove cache-on/cache-off output equivalence for repeated prompts, appended turns, prompt rewrites, system/template changes, cancellation, context shift, reload and process recovery on each intended architecture.
- [ ] Benchmark cold prompt, exact repeat and append-only turns on CPU/Vulkan only after correctness passes. Separate prefill savings from decode speed and record peak memory.

**Acceptance:** No stale or cross-session reuse. Enabled architectures have verified full-state correctness; all others use explicit safe full-clear behavior. Performance improvement is repeatable on target hardware.

---

## P2 — Maintainability and quality

### P2-01 — Introduce a clear package layout for the demo harness and library internals

**Evidence:** Demo code is concentrated in `app/src/main/java/com/example/ui/`; `com.example` is a placeholder namespace, and chat, model management, import, settings, benchmark and Vulkan diagnostics share one package. The library has a public/internal/ipc split, but some internal helpers remain in the public package namespace.

- [ ] Define feature packages for demo chat, model library/import, settings, diagnostics/benchmarks and navigation; place shared UI primitives in a small common package.
- [ ] Replace the placeholder `com.example` namespace with a project-owned demo namespace only after auditing manifest, generated BuildConfig, instrumentation, saved state and deep links. This is a demo-app refactor, not a library namespace change.
- [ ] Organize library internals by lifecycle, model source, memory/resource policy, diagnostics and IPC without moving consumer contracts into `internal`.
- [ ] Enforce dependency direction: `:app → :llm-engine`; never `:llm-engine → :app`; no UI or benchmark dependency in the library.
- [ ] Update architecture diagram and import sites in the same structural commit; preserve screen behavior and tests.

**Acceptance:** Package names describe ownership; module dependency direction is enforced; consumer-facing API is independent of demo packages.

### P2-02 — Remove stale imports and mechanical-extraction residue

**Evidence:** The audit found likely unused imports left by the ViewModel split. Candidates include `BackendBenchmarkViewModel.kt`, `BenchmarkDiagnosticsViewModel.kt`, `ConfigurationSmokeViewModel.kt`, `EngineLifecycleViewModel.kt`, `ModelImportViewModel.kt`, `SettingsViewModel.kt`, `VulkanDiagnosticsViewModel.kt` and Compose screen files. These are cleanup candidates, not proof that features are dead.

- [ ] Re-run unused-import analysis using Kotlin compiler/IDE-aware resolution, not text search alone; remove imports that are genuinely unused.
- [ ] Remove redundant whitespace, duplicate imports and misleading inherited-responsibility comments while touching affected files.
- [ ] Do not broadly reformat or mix cleanup with behavior changes. Keep this as a mechanical, independently verified commit.

**Acceptance:** No unused imports or stale split comments remain in audited files; no behavior/API changes; compilation and unit tests pass.

### P2-03 — Make large test suites responsibility-oriented

**Evidence:** `InferenceIpcTest.kt` is approximately 36.9 KB and contains a large fake Binder service plus tests for liveness, cancellation, timeouts, model load, reconnect and stream behavior. `EngineDiagnosticsTest.kt` is approximately 26 KB and combines parser/export scenarios. `ModelSourceResolverTest.kt` currently tests four basic FilePath/header cases but not AssetPath or RemoteUrl.

- [ ] Extract reusable fake service/process fixtures into shared test fixtures with explicit ownership and reset behavior.
- [ ] Group IPC tests by connection/recovery, model loading, streaming/timeouts and cancellation/process termination. Move existing test bodies unchanged first; add/change assertions only in a separate behavior-focused commit.
- [ ] Group diagnostics tests by persistence/rotation, parser, metric aggregation, privacy and export.
- [ ] Add deterministic local tests for AssetPath and RemoteUrl (HTTPS validation, redirect downgrade rejection, filename safety, GGUF validation, cleanup, interruption and atomic publish), or retire those paths through an approved API deprecation plan.
- [ ] Keep Android-only Binder/process claims in `androidTest`; pure logic in JVM tests; native utilities in host C++ tests.

**Acceptance:** Test names and behavior remain traceable; fixtures are not duplicated; each production responsibility has focused deterministic coverage.

### P2-04 — Inventory optional features before pruning or expanding them

**Evidence:** `ModelSource.AssetPath` and `ModelSource.RemoteUrl` are implemented in the public API/resolver but are not used by the demo flow and lack dedicated resolver tests. Remote downloads add networking and partial-file behavior to a library whose initial downstream use is local document processing. Vulkan kernel sweeps are diagnostic tools, not necessarily product features.

- [ ] Inventory every public API, `ModelSource` variant, UI action, build flag, native diagnostic hook and benchmark sweep; record owner, callers, tests, integration value and maintenance cost.
- [ ] Classify each as core engine contract, demo-only tool, diagnostic/test-only, optional consumer feature or obsolete.
- [ ] Decide whether AssetPath and RemoteUrl are genuinely required by the consumer. Do not retain half-supported behavior without tests; do not delete public API casually.
- [ ] Because this prototype has no production consumers, prepare deprecation/removal options where appropriate, but get explicit approval before public API breaks.
- [ ] Hide experimental kernel profiles from normal product-facing controls unless they have a clear operator/diagnostic purpose; keep reproducible experiments accessible in the diagnostic harness.
- [ ] Remove code, dependencies and permissions only after call-site, test, documentation and consumer searches show they are unnecessary.

**Acceptance:** Every retained feature has an owner and purpose. Unused functionality is fully tested and intentionally retained or deliberately deprecated/removed with compatibility handling.

### P2-05 — Establish build and architecture guardrails

- [ ] Verify dependencies: no app/demo dependency leaks into `:llm-engine`; avoid duplicate versions and unnecessary API exposure.
- [ ] Add automated architecture checks for module direction, package ownership, public API leakage and forbidden library references to app/Compose/DMS types.
- [ ] Add appropriate Gradle Android lint and native compiler/static checks using existing tools before proposing new dependencies.
- [ ] Review Gradle wrapper reproducibility. If wrapper scripts/JAR fit the repository/submodule workflow, pin Gradle 9.3.1 and verify CI/local use the same distribution; otherwise document supported parent-project invocation.
- [ ] Keep CI path filters accurate for source, CMake overlays, submodule pins, build scripts and workflows. Documentation-only edits should not consume expensive native APK jobs.
- [ ] Keep generated overlays and diagnostic outputs outside source-controlled production directories; verify clean checkout/submodule builds.

**Acceptance:** CI rejects broken dependency direction/build drift; clean checkout reproduces documented builds without undocumented steps.

### P2-06 — Keep architecture and integration documentation aligned

- [ ] Update `docs/technical/ARCHITECTURE.md` after each accepted structural change: module ownership, state ownership, IPC lifecycle, native boundaries and model-source flow.
- [ ] Keep `docs/planning/INTEGRATION_MYDOCANIZER.md` aligned with public API, min SDK/toolchain, descriptor semantics, dependencies, manifest merge and consumer lifecycle.
- [ ] Keep `docs/technical/MODEL_COMPATIBILITY.md` explicit about source-recognized, backend-supported and physically device-verified models.
- [ ] Move new evidence into technical references or historical reports; keep action items only in this Master TODO.
- [ ] Tie version/revision and verification claims to exact commits, workflow IDs, artifact IDs and device evidence.

**Acceptance:** One live queue, one canonical architecture reference and one integration guide; no stale API examples or competing task lists.

---

## P3 — Conditional cleanup (only after P0–P2)

### P3-01 — Consolidate or remove low-value experimental controls

- [ ] Revisit every benchmark/sweep button and configuration option after the feature inventory. Retain tests needed to isolate correctness/regressions; remove duplicate/obsolete UI paths only when a replacement is verified.
- [ ] Prefer a small number of task-oriented diagnostic workflows over many overlapping manual sweeps.
- [ ] Do not remove diagnostic capabilities required to root-cause CPU/Vulkan correctness/performance until equivalent evidence exists elsewhere.

### P3-02 — Consider further API simplification

- [ ] After a real consumer build exists, review whether advanced descriptor, AssetPath, RemoteUrl, profiler and diagnostic APIs belong in the stable public surface or should remain internal/experimental.
- [ ] Make any API reduction through an explicit compatibility/deprecation decision and update API baseline, parcel tests and integration guide together.

---

## Definition of done for the project foundation

- [ ] No prompts, OCR text or model responses are persisted in diagnostics.
- [ ] Normal model loading preserves selected original file/location; copying is a separate explicit operation.
- [ ] Public library contract and parcel/AIDL compatibility are tested and consumer build passes.
- [ ] Demo remains a thin harness with clear package ownership and no deep ViewModel inheritance chain.
- [ ] IPC, diagnostics, native JNI and CMake responsibilities have explicit, testable boundaries.
- [ ] Every retained feature has an owner, tests and integration reason; unused features are intentionally retired or marked optional.
- [ ] Unit, native host, APK, emulator and target-device acceptance are recorded separately.
- [ ] All current actions live in this document; no second active TODO list exists.

## Audit notes and scope

The audit baseline contains the `:app` prototype and `:llm-engine` library modules, 154 tracked blob entries in the recursive repository tree and 90 Kotlin source files. Responsibility hotspots include `InferenceClient.kt` (~70 KB), `EngineDiagnostics.kt` (~45 KB), `local_llm_engine_jni.cpp` (~104 KB), `CMakeLists.txt` (~62 KB) and the deep ViewModel inheritance chain. These are evidence for responsibility review, not standalone reasons to split files.

The review was structural and source-based. No product code was changed as part of this audit. Items above are planned measures, not claims that implementation, target-device acceptance or downstream integration is complete.
