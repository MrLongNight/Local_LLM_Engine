# Completed Items — Source Audit

**Audit date:** 2026-11-15  
**Repository:** `MrLongNight/Local_LLM_Engine`  
**Branch:** `main`  
**Audited source HEAD:** `07b56cd6a3d7417016b1771ae84d11202419ef38`  
**Scope:** All 55 pre-existing implementation checklist items marked `[x]` in `docs/active-todos/MASTER_TODO.md` at the audited HEAD (excluding the later audit-control meta checkbox). This is a source/test/documentation audit, not a claim that every workflow or device test was executed.

## Executive result

- All checked items were located in the current source tree and reviewed against the implementation and/or the named test evidence.
- Most checked items are correctly represented as implemented source changes with matching tests or explicit historical device evidence.
- **Not all completed work is fully verified.** The following audit findings must remain visible:
  1. **P0-02 device evidence remains open:** current `MASTER_TODO.md` correctly documents that Mali Q4_K AUTO selects BASE DMMV (`local_llm_mali_auto_prefers_mmvq=false`). The 2026-11-14 MMVQ runtime result is historical and does not prove the current AUTO choice; only a fresh device dispatch trace can close this item.
  2. **P1-03 metadata-cancellation edge:** `ModelImportViewModel.importModelsFromSaf()` wraps the display-name `ContentResolver.query()` in `runCatching`. The following `ensureActive()` does observe a cancelled job before processing the file, so it does not silently advance the bulk loop; however, cancellation is not rethrown directly from the query and may wait for the query to return. Add a deterministic blocked-query cancellation test and prefer explicit cancellation propagation.
  4. **Execution evidence is separate:** at audited HEAD `07b56cd`, both Unit Tests and Android APK Build workflows were still in progress. The preceding `aadcbd3` Unit Tests run succeeded, but that is not a result for `07b56cd`; its APK workflow was cancelled. No successful APK, Android instrumentation success or physical-device acceptance is claimed by this source audit.

## Item-by-item audit

Status meanings:
- **Source/test present:** implementation and named source-level test coverage match the checked item.
- **Historical evidence:** a real-device or runtime result is recorded in the TODO; it proves only the stated build/model/device/configuration.
- **Source-only / runtime open:** implementation or test harness exists, but execution/device acceptance is explicitly still open.
- **Follow-up:** an additional mismatch or regression gap was found and is added to the active TODO.

### P0-01 — Output correctness and native text boundaries

| Checked item | Audit result | Evidence / notes |
|---|---|---|
| Generation-path trace | Source/test present | `docs/active-todos/P0-01-generation-path-audit.md` records the request-to-finish path and unresolved root-cause questions. |
| JNI UTF-16 role/content conversion | Source/test present | `java_string_to_utf8()` uses `GetStringLength/GetStringRegion` and shared `utf16_to_utf8()`; host tests cover BMP, surrogate pairs, malformed surrogates and embedded NUL. |
| Invalid sampler token-ID guard | Source/test present | `nativeGenerateNextToken()` validates the sampler ID against vocabulary size before EOG lookup/decode; host-native tests cover negative, boundary and invalid vocabulary cases. |
| Incremental token-stream UTF-8 normalization | Source/test present | Shared `normalize_utf8_incremental()` handles split multibyte sequences and malformed encodings; host-native tests exercise split 3/4-byte sequences and invalid ranges. |
| Native token-ID budget replaces Kotlin visible-chunk budget | Source/test present | Native generation controls the max-token boundary; Kotlin no longer counts visible chunks as the stop condition. |
| Pending UTF-8 finalization on terminal paths | Source/test present | Native terminal helper flushes retained suffix as U+FFFD; Kotlin cancellation/error paths perform the final native drain; helper tests cover 2/3/4-byte incomplete prefixes. |
| Tiny-GGUF recovery visible output | Source-only / runtime open | Android instrumentation source requires nonblank output and STOP/MAX_TOKENS; this audit does not claim the instrumentation ran on hardware. |
| Real-GGUF second inference after recovery | Source-only / runtime open | Instrumentation source checks consecutive recovered-process inference and distinct native run IDs; runtime acceptance remains scoped to the exact test build/device. |
| Content-safe output classification | Source/test present | JVM tests cover EMPTY, PREFIX_TOO_SHORT, EXPECTED_PREFIX, EXACT_SEQUENCE, SEQUENCE_MISMATCH, NON_SEQUENCE_TEXT and EXTRA_SUFFIX; only shape/count are persisted. |
| Terminal EOG trace attribution | Source/test present | Native trace is cleared before each token attempt and Kotlin reads it before the null terminal check. |
| 32-token correctness safety budget / natural EOG | Source/test present | Gate uses shared 32-token ceiling and requires the complete exact answer; tests cover truncation, natural stop and CPU/Vulkan trace-length mismatch. |
| Gate compared-step accounting | Source/test present (current-main recheck) | Paired CPU/Vulkan PASS returns `candidateSteps.size`; `reportsActualStepCountWhenCpuAndVulkanPassWithNaturalEarlyEog` verifies a 25-step paired early-EOG pass (`9f9afb6`, `c5131fa`). The earlier follow-up text was stale. |
| Gate terminal finish reason | Source/test present (current-main recheck) | The correctness gate requires `FinishReason.STOP` and rejects every other enum value, even with exact output and a valid trace; `rejectsEveryNonStopFinishReasonEvenWhenTheExpectedAnswerAndTraceAreExact` covers all non-STOP values (`a73240f`). The actual finish reason is included in the content-free gate result. |

**Boundary:** P0-01 remains open overall. The checked sub-items are not proof of model-family output correctness; current LFM3 CPU and Vulkan acceptance evidence still fails the synthetic expected-output check.

### P0-02 — Mali Q4_K selector and fallback

| Checked item | Audit result | Evidence / notes |
|---|---|---|
| Compare selector with pinned upstream and preserve Q8_1 eligibility | Source/test present | CMake overlay retains Q8_1 type/layout/contiguity/alignment/precision guards and configure-time anchors. |
| Runtime MMVQ Integer-Dot proof | Historical evidence | 2026-11-14 Mali-G57 logs record the selected MMVQ path and Q8_1 metadata for that run. |
| Repeated MMVQ-vs-BASE DMMV sweep | Historical evidence | 2026-11-14 LFM3 1.7B sweep is recorded with run-correlated throughput and dispatch evidence; it is model/device-specific and not output-correctness acceptance. |
| Q8_1 pipeline-unavailable fallback validation | Source/test present; device open | Runner predicate validates explicit BASE fallback evidence; actual driver fallback remains open. |
| Extracted fallback predicate tests | Source/test present | JVM cases cover valid fallback, missing eligibility, contradictory quantization/kernel, wrong selected path and ordinary non-fallback dispatch. |
| Configure-time fallback marker/order guard | Source/test present | CMake checks the explicit fallback marker and that it is recorded before `quantize_y` is cleared. |
| End-to-end upstream/overlay selector trace | Source audit present | The pinned upstream heuristic, guarded override and null-pipeline fallback are traced in CMake overlay logic. |
| Non-Mali AUTO retains upstream heuristic | Source/test present | AUTO override is scoped to vendor ID `0x13B5`; configure-time checks require vendor-scope markers. |
| Type/batch scope enforcement | Source audit present | Q4_K prefill/batch and Q4_K/Q5_K/Q6_K decode override scopes are constrained in generated overlay source; submodule is a gitlink at the pinned revision. |
| Deterministic overlay scope guards and decode-path policy tests | Source/test present | Latest P0-02 addition introduces `Q4kDecodePathPolicy` plus JVM cases for non-Mali upstream AUTO reason, Mali MMVQ reason, explicit BASE/MMVQ overrides, missing-pipeline fallback and Mali AUTO base-reason selection. The policy is evidence-driven: AUTO's observed path is checked against its recorded reason/eligibility, not hard-coded to one universal kernel. |

**Correction:** the current Mali AUTO default is BASE DMMV, not MMVQ Integer-Dot. The previous “Current source finding” text has been corrected in MASTER_TODO. Re-run the actual AUTO dispatch on the device after the latest source change; the historical MMVQ run does not satisfy that check.

### P0-03 — Vulkan synchronization diagnostics

| Checked item | Audit result | Evidence / notes |
|---|---|---|
| Scheduler graph-node/backend accounting | Source-only / runtime open | Generated context overlay contains run-correlated graph layout accounting, including unassigned/other classes. |
| Vulkan capability and memory heap diagnostics | Source-only / runtime open | Native backend diagnostics report device features and heap/budget support; unsupported budget extension is explicitly distinguished from estimates. |
| Q4_K prefill/decode dispatch evidence | Source/test present | Generated Vulkan overlay emits Q8_1 eligibility, quantizeY, selected path and fallback reason. |
| Prompt/prefill vs token/sync metrics separation | Source/test present | Native timing events distinguish prompt-stage and per-token sample/decode/sync envelopes. |
| Logits readback timing and opt-in GPU profiler | Source/test present | Native callbacks collect map/copy/sync phases; operation profiler is opt-in and separate from normal throughput runs. |
| Mali blocking fence wait | Source-only / runtime open | Generated overlay uses `waitForFences` for Mali and preserves non-Mali polling. No isolated before/after device result is claimed. |
| Queue-submit and fence-wait phase timing | Source/test present; device open | Four submit sites and the listed fence wait sites are instrumented in the generated overlay; CMake anchors guard them. Values are host-side call/wait durations, not GPU shader time. |

### P0-04 — Cancellation, timeout and recovery

| Checked item | Audit result | Evidence / notes |
|---|---|---|
| End-to-end cancellation trace | Source audit present | Client → request-ID Binder call → service → engine → JNI atomic cancel is implemented; Vulkan limitation is documented accurately. |
| Request PID captured at dispatch | Source/test present | Immutable request PID is stored separately from mutable reconnect PID and used for escalation. |
| PID replacement regression | Source/test present | JVM fake-service test suite includes replacement-PID cancellation/kill assertions. |
| Prefill timeout after PID replacement | Source/test present | Dedicated fake-service prefill-timeout replacement coverage is present. |
| Cooperative cancel before hard kill | Source-only / runtime open | Blocked-native Android instrumentation asserts persisted event ordering and original PID. Test source exists; this audit does not claim a fresh instrumentation run. |
| Exactly-once terminal event and stream close | Source/test present | `duplicateAndLateTerminalCallbacksAreIgnoredAfterCompletion` checks late error/cancel/duplicate completion and final state. |
| Collector-close native cancellation | Source/test present | Client retains request identity during cooperative unwind and has non-cooperative fake-service regression coverage. |
| Reconnect/EAGER same-model reload/follow-up inference | Source/test present | `eagerReconnectReloadsSameModelAndAllowsFollowUpInference` is present. |
| Follow-up inference after cooperative cancel | Source/test present | `followUpInferenceSucceedsAfterCooperativeCancellation` is present. |

**Boundary:** escalation-follow-up, native-crash and repeated real process-death/reconnect cycles remain open in MASTER_TODO.

### P0-05 — Original SAF descriptor loading

| Checked item | Audit result | Evidence / notes |
|---|---|---|
| Direct ContentUri descriptor handoff | Source/test present | Client opens the provider PFD and sends it through existing Binder API; normal selected-model load does not stage a private copy. |
| URI identity/display name retained | Source/test present | Public model identity remains the original URI; provider display name is retained; bulk import remains separate. |
| Unknown-size/non-mmap descriptors rejected | Source/test present | Resolver checks descriptor metadata/regular seekable file capability and reports no-copy limitation. |
| Correct descriptor-size assertion and test fixture staging | Source/test present | Instrumentation compares provider PFD stat size with staged GGUF bytes and stages fixture bytes in provider-visible cache contexts. |

**Boundary:** Android end-to-end descriptor ownership and representative cloud/document providers remain open. Service code retains the service-owned PFD while native model mapping is active, closes it after successful unload, closes untransferred descriptors on load failure, and closes retained descriptors during service destruction; the ownership lifecycle still needs execution evidence for timeout/process-death paths.

### P0-06 — Cache state consistency

| Checked item | Audit result | Evidence / notes |
|---|---|---|
| Cached token count equals native KV position | Source/test present | Native prefix reuse and context shift check nonnegative position and exact cached-token count; mismatch clears KV and cached tokens. Host tests cover shorter, longer and negative positions. |

**Boundary:** architecture-specific state semantics, cache identity, cancellation/reload invalidation and cache-on/off output equivalence remain open. Hybrid/recurrent models are currently excluded from incremental reuse.

### P1-01 — Privacy-safe diagnostics

| Checked item | Audit result | Evidence / notes |
|---|---|---|
| Debug/Release content-safe defaults | Source/test present | `localLlmSyntheticTokenDiagnostics` defaults false; Release forces text diagnostics off. |
| Remove raw prompt/template/token-piece persistence | Source/test present | Legacy `testText()` is a no-op; prompt/template/token-piece log sites were removed from the native path. |
| Numeric-only opt-in token trace | Source/test present | Trace method is gated by Debug + explicit build property, validates run ID/backend and numeric trace syntax. |
| Caller-text sentinel test | Source/test present | JVM test asserts sentinel text is absent from in-memory logs and compact export. |
| Native log flush markers/checkpoints | Source-only / runtime open | Native startup and phase/terminal flush points are present; byte-identical non-empty provider export and both-process scan remain open. |

**Boundary:** explicit opt-in must be restricted to synthetic prompts operationally; the TODO correctly keeps this open. A source-level default does not prevent an engineer from building an opt-in APK and using it with real user prompts.

### P1-03 — Model import cancellation

| Checked item | Audit result | Evidence / notes |
|---|---|---|
| Rethrow cancellation in per-file bulk loop | Source/test gap | Per-file import catches and rethrows `CancellationException` before recoverable `Exception`. |
| Stop later files, retain completed imports, refresh and cleanup | Source audit present; regression tests open | Cancellation handler refreshes after partial success; resolver deletes the temporary `.part` file in `finally`; UI importing state resets once in the outer `finally`. The metadata query uses `runCatching`, then cancellation is observed by the following `ensureActive()`; add deterministic coverage as recorded in MASTER_TODO. |

### P2-05 — Diagnostics decomposition

| Checked item | Audit result | Evidence / notes |
|---|---|---|
| Preserve privacy-safe event contract | Source/test present | Facade discards raw text; numeric trace remains separately gated and validated. |
| Extract storage/rotation/cross-process merge/export | Source/test present | `EngineDiagnosticsStorage` owns per-process files, bounded rotation, deterministic merged reads and provider write/read-back byte verification. |
| Extract metrics parsing/statistics | Source/test present | `EngineDiagnosticsMetrics` parses correlated native/Kotlin/Vulkan rows and timing percentiles. |
| Parser tests | Source/test present | JVM tests cover current/legacy sync fields, run/PID/backend correlation, Vulkan metrics and malformed rows. |
| Extract report builder/export bounding | Source/test present | `EngineDiagnosticsReportBuilder` owns report composition/resource aggregation/128 KiB bound; `EngineDiagnostics.kt` remains a 143-line facade. |

**Boundary:** bounded versioned event schema and request/run/PID correlation remains open in MASTER_TODO.

## Current-main recheck — 2026-11-15

- Rechecked current main at `a73240fd09efe2bce4631788e6515ffd536f5ba0`; the two P0-01 findings in the original audit are resolved in source and regression tests. Paired early-EOG comparison reports the actual 25 compared steps, and the correctness gate now requires a natural `STOP`/EOG finish rather than accepting a token-budget or error terminal state.
- The `MASTER_TODO.md` Mali AUTO statement matches the current selector: BASE DMMV is the source default for Mali Q4_K AUTO. The historical MMVQ sweep remains useful for A/B analysis but is not evidence of current AUTO dispatch.
- P0 acceptance across model families, current Mali runtime dispatch, Vulkan synchronization attribution, real-device timeout/cancellation/reconnect cycles, SAF cloud-provider behavior and cache-on/cache-off equivalence remain separate execution/device requirements. They are deliberately not marked complete by this source recheck.

## P0 scope reduction — 2026-11-15

The P0 queue was re-scoped around user-visible inference blockers rather than accumulating separate evidence runs:

- Dropped the obsolete LFM3 16-token exact-continuation result from active investigation; it cannot diagnose the current fixture/build and is only reopened on a real current-app failure.
- Removed mandatory all-family fixture, CPU/Vulkan parity and output-shape matrices. The exact-copy gate remains a targeted diagnostic utility, not a required run for every change.
- Removed Q5_K/Q6_K, multiple Q4_K models, separate prefill/CPU/base-override A/B and old-vs-new fence strategy as P0 device matrices. The current Qwen3-4B Q4_K app run supplies the relevant decode/offload/synchronization evidence once.
- Combined Vulkan baseline, sampler/readback, synchronization, prefill/TTFT, graph/offload and Q4_K dispatch into one user-device export from build 7c986e1; no separate profiler run is requested initially.
- Removed historical abort-grace inspection and repeated kill/reconnect cycles as standalone tests. Revisit only if a new real failure makes their result actionable.
- Removed broad cloud-provider loading acceptance from P0. Local document-picker loading remains a core user-facing behavior; test only if it actually fails.
- Closed duplicate cache lifecycle/reuse test matrices using the current source audit and existing host invariants. Hybrid/recurrent architectures remain on the conservative full-clear path.

No physical-device pass is implied by this scope reduction. The single current-build user run is still pending and is recorded in MASTER_TODO.

## Workflow evidence at audit time

- **Unit Tests:** run `43671275805`, commit `07b56cd6a3d7417016b1771ae84d11202419ef38`, status **in progress** at audit time. The immediately preceding `aadcbd3` run `43670909807` succeeded, but does not verify the newer commit.
- **Android APK Build:** run `43671275810`, commit `07b56cd6a3d7417016b1771ae84d11202419ef38`, status **in progress** at audit time. The preceding `aadcbd3` APK run `43670909723` was cancelled. No APK artifact or successful APK build is claimed here.
- **Android instrumentation / physical device:** no new successful run is claimed by this source audit.
- **Post-audit update:** Unit Tests run `43671275805` for audited code HEAD `07b56cd` has since completed with **success**. Android APK Build run `43671275810` remains **in progress**; no APK artifact is claimed.

## Required follow-up

1. Capture current Mali Q4_K AUTO dispatch evidence on the target device; the source/documentation alignment is already corrected, but the 2026-11-14 MMVQ run does not verify current AUTO.
2. Add the P1-03 blocked metadata-query cancellation regression and direct cancellation propagation.
3. Keep P0/P1 hardware and end-to-end acceptance items open until exact device/build/run evidence is attached to the TODO.
