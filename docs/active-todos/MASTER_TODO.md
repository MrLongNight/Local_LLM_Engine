# Master TODO — Local LLM Engine

**Purpose:** Single active execution queue. The order is deliberate: first make the standalone LLM engine correct and reliable, then clean up its code and architecture, and only after that adapt/freeze the myDocAnizer-Mobile integration.  
**Repository:** `MrLongNight/Local_LLM_Engine` · **Branch:** `main`  
**Current HEAD at reordering:** `529008a90f0c578af74be53bbfad0fd18bf80d30`  
**Target consumer (final phase only):** `MrLongNight/myDocAnizer_Mobile`

> **Single source of truth:** This is the only active task list. Architecture references, integration guides and historical reports may document facts and contracts, but must not create parallel implementation queues.

## Execution order and invariants

1. **P0 — Correctness defects:** incorrect/garbled model output, backend/kernel selection defects, inference and loading failures, cache/state corruption, cancellation and recovery defects.
2. **P1 — Runtime reliability and proof:** privacy-safe diagnostics, resource stability, automated regression coverage and real-device verification of the corrected engine.
3. **P2 — Code and architecture refactoring:** reduce complexity and establish clear responsibility boundaries without changing behavior.
4. **P3 — Cleanup and engineering guardrails:** feature inventory, dependency/build hygiene, test organization and documentation of the standalone engine.
5. **P4 — myDocAnizer-Mobile integration (last):** define/freeze the consumer contract, perform compatibility changes and run the actual downstream build only after P0–P3 are accepted.

Agents work on existing `main` only. Re-read HEAD and affected blob SHAs before each write and use expected-HEAD leases where available. Make small, independently verifiable commits. Keep the pinned llama.cpp submodule pristine. Do not introduce DI frameworks, dependencies, NPU backends, speculative abstractions or unproven performance-default changes. Preserve public Kotlin/Java APIs, AIDL/Parcelable layouts, manifests, process names, JNI exports and ABI support during P0–P3 unless a defect fix is impossible without a separately documented compatibility change. Correctness comes before performance. Report JVM tests, host-native tests, APK builds, emulator instrumentation and physical-device checks separately. Never claim an APK/Actions result without the actual successful job and artifact.

---

## P0 — Fix engine correctness and runtime defects first

### P0-01 — Restore and lock down output correctness across model families

**Problem:** There have been incorrect/garbled outputs across Qwen3.5 and other model paths. A successful load or non-empty token stream is not sufficient evidence of correct inference.

- [ ] Trace the complete generation path: request/messages → chat-template selection/rendering → tokenization/BOS/EOS → prompt evaluation → sampler/token selection → detokenization/streaming → finish reason.
- [ ] Identify why affected models emit garbage or lose output quality. Inspect model metadata, architecture detection, tokenizer/template choice, special-token handling, sampler configuration, context state and CPU/Vulkan differences. Do not mask defects with prompt wording or arbitrary parameter changes.
- [ ] Add deterministic correctness fixtures per supported model family, including Qwen3, Qwen3.5 (dense/MoE where supported), Gemma 4 and ordinary attention models. Keep prompt fixtures synthetic and content-safe.
- [x] Route JNI role/content conversion through the shared host-testable UTF-16→UTF-8 utility; cover BMP characters, valid/unpaired surrogates and embedded NUL bytes in host-native tests.
- [x] Move token-stream UTF-8 normalization into the host-testable native utility and cover multibyte code points split between token callbacks plus malformed/overlong UTF-8.
- [x] Remove the competing Kotlin-side visible-chunk limit; native token-ID accounting now sets `MAX_TOKENS` and flushes a pending UTF-8 suffix before stream completion (`8f94fe3`).
- [x] Finalize pending UTF-8 as U+FFFD on token-limit, cooperative-cancel, Kotlin coroutine cancellation and terminal error paths; Kotlin performs one final native step before every terminal stream exit; add host-native tests and keep the JNI/Kotlin terminal handoff aligned; add local helper compile smoke-check for 2/3/4-byte incomplete prefixes.
- [ ] Compare CPU and Vulkan outputs using the same GGUF, messages, template, context, sampler and generation limits. Separate backend numerical variation from malformed templates, invalid token IDs, premature EOS and state corruption.
- [ ] Assert expected structure/markers, valid UTF-8, non-empty meaningful output, correct finish reason and no unexpected control/special-token leakage. Include multi-turn and repeated-turn cases.
- [ ] Run correctness gates before changing performance defaults. Do not accept a speed improvement that fails output parity/correctness.

**Acceptance:** Every intended model family passes repeatable output-correctness tests on CPU; Vulkan passes the same correctness contract on supported devices. Root causes and remaining unsupported combinations are explicit.

### P0-02 — Verify and correct the Mali-G57 Q4_K decode kernel path

**Current source finding:** The Android Vulkan overlay selects the Q4_K batch-1 integer-dot path only when the pinned upstream Q8_1 input/layout/alignment/precision prerequisites are satisfied. It intentionally bypasses only `ggml_vk_should_use_mmvq()`'s shape heuristic; explicit base-DMMV override remains separate. This source-level correction is not yet proof that the target device actually dispatches the intended pipeline.

- [x] Compare the selector against pinned llama.cpp `a4d4f2f`; preserve Q8_1 input/layout/alignment/precision eligibility while bypassing only the upstream shape heuristic (`a0f1cee`). CMake now fails configuration if these guards disappear.
- [ ] Trace the pinned upstream Vulkan decode selection and the generated overlay end to end. Confirm the condition is evaluated at the correct point and cannot be undone by later upstream logic.
- [ ] On integer-dot-capable Q4_K batch-1 decode, require runtime evidence `selected=MMVQ_INTEGER_DOT`, `reason=intDot_q4k_batch1`, `quantizeY=1`, `yKernelType=q8_1`.
- [ ] Confirm missing Q4_K×Q8_1 DMMV pipeline fallback is explicit and reports `q8_1_pipeline_unavailable_fallback`; do not report MMVQ as selected when base DMMV actually ran.
- [ ] Confirm Q4_K prefill, batch>1, Q5_K/Q6_K, CPU and the explicit A/B base override retain their intended paths.
- [ ] Add/strengthen deterministic source-overlay and runtime-path tests for selection, fallback and override. Keep upstream llama.cpp untouched.
- [ ] Verify actual dispatch and output correctness on nubia Z2462N / Mali-G57; correlate native run ID with the benchmark sample.

**Acceptance:** The intended path is proven by actual runtime selection and correctness evidence, or the selector is corrected based on a reproducible failing case. Requested configuration or source-string matches alone do not count.

### P0-03 — Root-cause the Vulkan throughput and synchronization regression

**Problem:** Current Mali-G57 measurements show Vulkan decode/prefill is not delivering the expected benefit and can be close to or slower than CPU. The existing measurements show substantial backend synchronization wait; changing threads, batch sizes or layer counts at random is not an acceptable fix.

- [ ] Establish a reproducible on-device baseline for the current pinned build and Qwen3-4B-Instruct Q4_K_M at fixed context/batch/microbatch/generation settings. Record CPU and Vulkan separately.
- [ ] Separate prompt prefill, first-token latency, per-token decode, logits readback, queue submit, fence wait and JNI/client wall time. Identify whether the dominant cost is shader execution, dispatch shape, readback/synchronization or CPU scheduling.
- [ ] Inspect Mali-G57 capabilities and actual driver reports: integer-dot, fp16, subgroup size/features, extension support and Vulkan memory budget. Do not infer capability from a device label.
- [ ] Verify Q4_K batch-1 MMVQ versus base DMMV using actual selected-path logs and identical output correctness. Determine whether the integer-dot path is slower, unavailable or incorrectly dispatched.
- [ ] Inspect the Android fence-wait implementation and queue/readback synchronization against the pinned upstream version. Any wait-strategy change must preserve completion ordering and pass stress/correctness tests.
- [ ] Verify actual CPU/Vulkan graph node assignment and offloaded layers, then measure graphics/native memory before and after inference.
- [ ] Keep profiler-enabled diagnosis separate from profiler-free throughput runs. Do not change production defaults until a controlled A/B result demonstrates both correctness and repeatable improvement.

**Acceptance:** A measured root cause explains the regression. Any fix is backed by repeatable profiler-free on-device CPU/Vulkan measurements and unchanged output correctness; otherwise retain the safe behavior and document the unresolved driver/backend limit.

### P0-04 — Make cancellation, timeout, abort and recovery deterministic

- [ ] Trace cancellation from UI/coroutine through `InferenceClient`, Binder/AIDL, JNI and llama.cpp. Confirm cancellation reaches the active request and cannot be swallowed or delivered to a stale request.
- [ ] Verify the effective `abortGracePeriodMs` from the actual run configuration (the current source default is 3,000 ms; older diagnostic runs may override it). Do not tune it based on a single token timing.
- [x] Capture the actual service PID when the Binder request is dispatched and use that immutable request PID for timeout, explicit cancel and collector-close escalation; a reconnect cannot redirect an old request's abort to a replacement service.
- [x] Add a regression test that changes the reported service PID mid-request and asserts cancel/kill is not sent to the replacement process.
- [ ] Confirm cooperative cancellation is attempted first, then hard-kill targets only the original inference-service PID after the configured grace period; never kill a replacement process.
- [ ] Verify terminal events are emitted exactly once, streams close, timeouts remain timeouts, and client calls cannot hang after process death or stale Binder callbacks.
- [x] Treat `streamChat` collector cancellation as a real native cancellation: retain the request ID through cooperative unwind and escalate to the original service PID after the configured grace period; add a non-cooperative fake-service regression test.
- [ ] Verify reconnect, same-model reload and a successful follow-up inference after cancellation, timeout, native crash and process kill.
- [ ] Add regression coverage for cancellation during model load, prefill, decode, stream collection and reconnect; include repeated abort/reconnect cycles.

**Acceptance:** No stuck request, duplicate terminal event, stale-PID kill or poisoned post-cancel model state. A following inference succeeds after every tested recovery path.

### P0-05 — Correct selected-model loading and descriptor ownership

**Source audit:** The service already accepts and retains a Binder-transferred descriptor, validates regular-file/seek/mmap capability in `ModelSourceResolver`, and passes the service-local `/proc/self/fd/N` to JNI. The defect was in `InferenceClient.loadModelInternal()`: the `ContentUri` branch copied the document into private storage before Binder handoff, so ordinary single-file loading never reached the descriptor route.

- [x] Replace the normal `ContentUri` load branch with `ContentResolver.openFileDescriptor(uri, "r")` and transfer that original descriptor through the existing Binder method.
- [x] Keep the provider URI as the in-memory model identity/recovery source and use the provider display name; explicit bulk/model-library import remains a separate copy operation.
- [x] Reject descriptors without a known size before the memory guard; show an explicit provider/mmap limitation and do not silently copy.
- [ ] Verify descriptor ownership end to end on Android: client copy closes only after Binder handoff, service copy survives while native mmap is active, and service closes exactly once on unload/failure/timeout/process death.
- [ ] Test local document provider and representative cloud/document providers, including unload/reload and follow-up inference.

**Acceptance:** Original-source loading works end to end where supported; unsupported descriptors fail visibly and deterministically; no leaked descriptors or hidden copies.

### P0-06 — Prove model-aware cache and context-state correctness

**Current implementation finding:** Ordinary attention uses common-token-prefix reuse where supported. Hybrid/recurrent models currently fall back to clearing state. Keep this safe fallback until all relevant state components are proven reusable.

- [ ] Audit the exact pinned llama.cpp revision for ordinary attention, sliding-window, recurrent/SSM and convolution state behavior; classify each intended architecture, especially LFM3 and Qwen3.5.
- [ ] Define cache identity/invalidation for loaded model/file identity, adapters, tokenizer/vocabulary, rendered chat template, BOS/EOS, system prompt, context settings and session.
- [ ] Cover appended turns, prompt rewrites/deletions, tool messages, context shifts, cancellation/failure, model reload, process restart and memory pressure.
- [ ] Enable reuse only when all model-state components remain synchronized. Keep full-clear fallback whenever state integrity is uncertain.
- [ ] Prove cache-on/cache-off output equivalence before benchmarking. Bound memory and invalidate on unload/reload, process restart, failed/cancelled inference and pressure eviction.

**Acceptance:** No stale or cross-session state reuse. Every supported architecture has verified state reuse or a documented safe full-clear fallback.

---

## P1 — Privacy, runtime reliability and verification

### P1-01 — Prevent prompt, document and response text from entering diagnostics

**Evidence:** The current Debug Gradle configuration sets `TEXT_DIAGNOSTICS_ENABLED=true` and passes `LOCAL_LLM_ENABLE_TEXT_DIAGNOSTICS=ON`. `EngineDiagnostics.testText()` persists through the normal log path; callers include benchmark/configuration output and token-selection traces. The compact export omits the dedicated test-text section, but the persisted process logs still contain it.

- [x] Make standard Debug and Release builds content-safe by default. Normal Debug disables token diagnostics; the opt-in Gradle property is `localLlmSyntheticTokenDiagnostics` and defaults to false.
- [x] Remove native rendered-prompt, chat-template-source, detokenized-prompt and token-piece logging. The legacy Kotlin `testText()` method remains source-compatible but intentionally discards its payload.
- [x] Restrict the optional native correctness trace to numeric token IDs/logits; record it through a separately gated method and never include token-piece strings.
- [ ] Verify the opt-in cannot be used accidentally with real user conversations; currently it is an explicit build property and must be used only for synthetic correctness prompts.
- [x] Add a Kotlin sentinel test proving caller-supplied raw text is not persisted; add/retain compact-export sentinels. Native runtime/build verification remains open.

**Acceptance:** No supported normal build persists user/document prompt or response content. Engineering diagnostics remain structured, bounded and run-correlated.

- [x] Add a native diagnostics-file startup marker and explicit `fflush` checkpoints after backend/model/prompt initialization and inference terminal paths, so native log bytes are visible to export immediately instead of waiting for the 500 ms batching window.
- [ ] Verify the exported document-provider file is byte-identical and non-empty, and confirm the scan includes both app-process and `:inference` process logs on the target device.

### P1-02 — Stabilize memory/resource handling under repeated failure and recovery

- [ ] Verify model-load peak memory, native heap, Java heap, PSS, graphics PSS and Vulkan heap/budget where available.
- [ ] Confirm memory-pressure guard behavior under model load, prefill, decode, cancellation and reconnect.
- [ ] Run at least five load/inference/timeout/kill/reconnect cycles on nubia Z2462N / Android 16 / ARM64 / Mali-G57.
- [ ] Measure memory from the surviving app process before and after service death; the killed service cannot prove its own resource release.
- [ ] Check file descriptors, Binder references, native handles, backend contexts and temporary files after every path.

**Acceptance:** No resource leak or progressive memory growth across repeated cycles; process isolation and cleanup are verified on hardware.

### P1-03 — Fix model-import cancellation propagation

**Evidence:** `ModelImportViewModel.importModelsFromSaf()` previously caught `Exception` per file without rethrowing `CancellationException`; the explicit rethrow is now implemented, but lifecycle/cleanup tests remain open.

- [x] Rethrow `CancellationException` before handling recoverable per-file failures in the bulk SAF loop.
- [x] Ensure cancellation stops subsequent files, retains completed imports, refreshes the model list after partial success, removes incomplete temporary files in the resolver and resets UI state once.
- [ ] Add tests for cancellation during permission/metadata lookup, copy, verification and between files.

**Acceptance:** A cancelled import cannot continue as a sequence of ordinary file failures or report success.

### P1-04 — Complete standalone engine regression and device acceptance

- [ ] Separate JVM logic tests, host-native tests, Android instrumentation, emulator IPC/process tests and physical-device checks.
- [ ] On nubia Z2462N / Android 16 / ARM64 / Mali-G57, validate each model family intended to be supported with CPU and Vulkan separately.
- [ ] Verify output correctness, prompt/template correctness, finish reason, actual offload and graph-node assignment, Q4_K kernel selection, cancellation, abort, recovery and memory cleanup.
- [ ] Record exact device/build/model/configuration and run-correlated evidence. Clearly distinguish source tests, emulator evidence and physical-device evidence.
- [ ] Update model compatibility status only from direct evidence. No generic performance or production-readiness claims based only on CI.

**Acceptance:** Standalone engine defects are either fixed and verified or explicitly classified as unsupported/unresolved with evidence.

---

## P2 — Refactor code and architecture after correctness is stable

Structural work must preserve behavior. Keep each refactoring independently buildable/testable and separate from behavior changes.

### P2-01 — Replace the deep ViewModel inheritance chain with composition

- [ ] Preserve the existing screen-facing `EngineViewModel` facade initially; move feature behavior behind cohesive internal controllers composed by the facade.
- [ ] Separate chat/session, model selection/loading, explicit import, settings, diagnostics/benchmarks and engine lifecycle.
- [ ] Keep exactly one engine instance and one application-level owner; do not create one engine per screen.
- [ ] Define explicit state ownership and one-way state/event flow. Remove inheritance-only state sharing and stale imports/comments.
- [ ] Add tests for startup/restore order, shared ownership, cross-feature locks, navigation and lifecycle close semantics.

**Acceptance:** No deep feature inheritance chain remains; ownership is explicit and call sites remain compatible.

### P2-02 — Decompose the IPC client

**Evidence:** `InferenceClient.kt` is approximately 1,455 lines / 70 KB and combines binding, Binder callbacks, PID/liveness, reconnect, model recovery, load serialization, streams, watchdogs and cancellation.

- [ ] Preserve `InferenceClient` as a facade while extracting cohesive binding, process-lifecycle, recovery, serialized model-operation and stream/timeout components.
- [ ] Define one owner for Binder references, active request IDs, jobs, mutexes, callbacks and loaded-model snapshots.
- [ ] Model stale callbacks, old PIDs, repeated terminal callbacks and close-during-reconnect explicitly.
- [ ] Keep AIDL and public API stable; carry over the P0 regression tests unchanged.

**Acceptance:** IPC responsibilities are independently testable without duplicate mutable lifecycle state.

### P2-03 — Split native JNI implementation by responsibility

**Evidence:** `local_llm_engine_jni.cpp` is approximately 2,384 lines / 104 KB.

- [ ] Separate engine/backend lifecycle, model loading, prompt/template/cache, inference/decode, metrics/diagnostics and test hooks into focused native files.
- [ ] Keep `EngineInstance` ownership/synchronization unambiguous and shared helpers in narrow internal headers.
- [ ] Preserve all JNI exports and Java native declarations. No C++ exception may cross JNI.
- [ ] Retain native utility tests and boundary tests for load, inference, abort and destroy. Keep the llama.cpp submodule pristine.

**Acceptance:** Same JNI boundary and runtime behavior; native ownership and cleanup are easier to audit and test.

### P2-04 — Modularize CMake and generated Android overlays

**Evidence:** `CMakeLists.txt` is approximately 1,231 lines / 62 KB and contains source overlays, validation anchors, native target wiring and build gates.

- [ ] Split upstream configuration, Android Vulkan compatibility, graph/GDN diagnostics, profiler, native source/target wiring and verification into responsibility-named CMake helpers.
- [ ] Keep the root `CMakeLists.txt` as orchestration only; preserve targets, compile flags and ABI behavior during the move.
- [ ] Retain fail-fast upstream anchors and build-directory-only generated files. Never patch the checked-out llama.cpp submodule.
- [ ] Verify generated compile commands and overlay checks for ARM64 and x86_64.

**Acceptance:** Same outputs/compile configuration with smaller, reviewable CMake components; clean submodule remains unchanged.

### P2-05 — Split diagnostics implementation

**Evidence:** `EngineDiagnostics.kt` is approximately 904 lines / 45 KB and combines storage, rotation, multi-process reading, parsers, aggregation and export.

- [x] Keep the privacy-safe event contract established in P1: raw prompt/document/token-piece/response text is discarded; numeric token traces remain opt-in and validated.
- [x] Extract process-log storage, per-process rotation, deterministic cross-process log merge and verified URI export into `EngineDiagnosticsStorage`.
- [x] Extract native/Kotlin token-step parsing, Vulkan operation parsing and timing/percentile helpers into `EngineDiagnosticsMetrics`.
- [x] Add direct parser tests for current vs legacy sync fields, run correlation, Vulkan metrics and malformed rows. Existing Android process instrumentation continues to verify app-process file persistence.
- [x] Move compact report composition, resource aggregation and UTF-8 export bounding into `EngineDiagnosticsReportBuilder`; `EngineDiagnostics` remains the API facade (143 lines after extraction).
- [ ] Define bounded versioned events with run/request/PID correlation; keep legacy parsing only when required.

**Acceptance:** Cohesive diagnostics components with bounded, privacy-safe exports and independent tests.

### P2-06 — Organize demo and library packages by ownership

- [ ] Split demo chat, model library/import, settings, diagnostics/benchmarks and navigation into feature packages.
- [ ] Replace `com.example` only after auditing manifests, generated BuildConfig, instrumentation, saved state and deep links.
- [ ] Organize library internals by lifecycle, model source, memory/resource policy, diagnostics and IPC; keep consumer-facing types separate.
- [ ] Enforce dependency direction `:app → :llm-engine`; no UI, benchmark or DMS dependency may leak into the library.

**Acceptance:** Package and module boundaries express ownership; the library remains independently consumable.

---

## P3 — Cleanup and engineering guardrails

### P3-01 — Inventory optional and obsolete functionality

- [ ] Inventory every public API, `ModelSource` variant, UI action, build flag, native diagnostic hook and benchmark sweep with owner, callers, tests and maintenance cost.
- [ ] Classify features as engine core, demo-only, diagnostic/test-only, optional or obsolete.
- [ ] Add missing resolver tests for `AssetPath`/`RemoteUrl` or propose deliberate retirement; do not retain half-supported behavior.
- [ ] Remove dependencies, permissions and code only after reference/test/documentation searches prove they are unnecessary. Public API removal requires an explicit decision.

### P3-02 — Clean tests without changing their behavior

- [ ] Extract reusable fake Binder/process fixtures with explicit reset and ownership.
- [ ] Split large IPC and diagnostics suites by responsibility. First move test methods unchanged; change assertions only in a separate defect-specific commit.
- [ ] Keep pure logic in JVM tests, process/Binder claims in `androidTest` and native utility behavior in host C++ tests.
- [ ] Remove genuinely unused imports and extraction residue using compiler-aware analysis, not text search alone.

### P3-03 — Add architecture and build guardrails

- [ ] Add automated checks for module direction, public API leakage and forbidden app/Compose/DMS references in `:llm-engine`.
- [ ] Review dependencies, Gradle wrapper reproducibility, NDK/CMake pinning and clean recursive-submodule builds.
- [ ] Keep CI path filters accurate; documentation-only changes must not trigger expensive native APK jobs.
- [ ] Keep generated overlays and diagnostics outside tracked production sources.

### P3-04 — Keep standalone engine documentation accurate

- [ ] Update architecture, model compatibility, build and diagnostics references after accepted changes.
- [ ] Keep evidence and limitations tied to exact commits, workflow IDs, artifacts and device configurations.
- [ ] Ensure there is no second active implementation checklist.

---

## P4 — myDocAnizer-Mobile integration (intentionally last)

**Do not spend implementation time on consumer adaptation while standalone engine correctness or foundational refactoring remains open.** The integration guide may document requirements, but it is not an active implementation queue.

### P4-01 — Define the minimal stable consumer contract

- [ ] Only after P0–P3 acceptance, define engine ownership/lifecycle, model source/identity, configuration, streaming progress/terminal events, cancellation, diagnostics export and close semantics.
- [ ] Keep demo UI, benchmark orchestration, demo persistence and DMS concepts out of the reusable library.
- [ ] Verify old/new Parcelable/AIDL compatibility, appended fields, enum fallbacks and malformed/truncated legacy data.

### P4-02 — Adapt and verify the actual consumer

- [ ] Verify consumer manifest merge, non-exported `:inference` service, JNI/native ABI packaging, minSdk, coroutine lifecycle and dependency resolution without importing `:app`.
- [ ] Pin the accepted engine commit in myDocAnizer-Mobile and perform the real downstream build/instrumentation only at this phase.
- [ ] Record exact engine revision, consumer commit, build result, test result and generated artifact. A demo APK is not integration evidence.

**Acceptance:** myDocAnizer-Mobile consumes the standalone engine through a minimal stable contract, without depending on the prototype app.

---

## Project-level definition of done

- [ ] Model output correctness passes across every claimed supported model family and backend.
- [ ] Q4_K Vulkan decode selection and actual Mali-G57 execution are evidenced, or support limitations are explicit.
- [ ] Timeouts, cancellation, process abort/reconnect, model loading and cache state are deterministic.
- [ ] Diagnostics do not persist prompts, document/OCR text or model responses.
- [ ] Repeated physical-device cycles show no leaked processes, descriptors or progressive resource growth.
- [ ] Deep inheritance and oversized mixed-responsibility components have been refactored without behavior regressions.
- [ ] Every retained optional feature has an owner, tests and a reason to exist.
- [ ] Standalone engine acceptance precedes all myDocAnizer-Mobile integration work.
- [ ] This file remains the only active implementation queue.

## Audit and current source notes

The audit baseline has a demo `:app` and reusable `:llm-engine` module, with the pinned llama.cpp submodule. Large responsibility hotspots include `InferenceClient.kt`, `EngineDiagnostics.kt`, `local_llm_engine_jni.cpp` and `CMakeLists.txt`. These are refactoring candidates after correctness is stable, not reasons to split files by size alone.

The current Vulkan overlay already contains the intended Q4_K batch-1 integer-dot selector in source. Actual hardware dispatch and output correctness remain unverified. The current `TimeoutConfig` default for `abortGracePeriodMs` is 3,000 ms; use the effective runtime configuration when analyzing any specific diagnostic run.

This list is a plan and source-audit snapshot. A checkbox may be completed only when the named acceptance evidence exists. No task should be marked done merely because a code path or test name is present.
