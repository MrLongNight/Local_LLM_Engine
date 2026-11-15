# Master TODO — Local LLM Engine

**Purpose:** Single active execution queue. The order is deliberate: first make the standalone LLM engine correct and reliable, then clean up its code and architecture, and only after that adapt/freeze the myDocAnizer-Mobile integration.  
**Repository:** `MrLongNight/Local_LLM_Engine` · **Branch:** `main`  
**Current HEAD at reordering:** `529008a90f0c578af74be53bbfad0fd18bf80d30`  
**Target consumer (final phase only):** `MrLongNight/myDocAnizer_Mobile`

> **Single source of truth:** This is the only active task list. Architecture references, integration guides and historical reports may document facts and contracts, but must not create parallel implementation queues.

## Audit control — 2026-11-15

- [x] Source-audit all 55 pre-existing implementation checklist items marked `[x]` against the implementation and named tests at code HEAD `07b56cd6a3d7417016b1771ae84d11202419ef38` (excluding this audit-control checkbox); findings: `docs/active-todos/COMPLETED_ITEMS_AUDIT.md`.
- [x] Resolve the P0-02 Mali Q4_K AUTO documentation mismatch: current source selects BASE DMMV. The paired natural-EOG PASS accounting gap is fixed and covered by JVM tests (`9f9afb6`, `c5131fa`); the finish-reason gate gap is fixed and covered by JVM tests (`a73240f`).
- [ ] Complete the separate P1-03 metadata-query cancellation regression and direct cancellation propagation recorded in the audit.
- [x] Re-run and record the current-head JVM and Android APK/instrumentation workflows separately: Unit Tests run `43679806775` passed; APK + process-isolation instrumentation run `43679806820` passed on commit `5a87d8f90b24f36c5ba893ba14eea27e50cc73f1`. Both ARM64 and x86_64 CMake configure/build tasks succeeded. Debug APK artifact `14148461029` (37,010,982 bytes; SHA-256 `821f4aaa60bf6034dcca1aa203c7201dcf244655a8e3f6665c3fe439b28b018e`) and instrumentation APK artifact `14148281622` (24,341,340 bytes; SHA-256 `a9f44b1a2311a024093598f9754e4adecb55b35b7bfc13e66f88f51d38f9e77c`) are uploaded. Host-native tests remain a separately tracked verification item where applicable.
- [ ] Complete the physical-device checks that remain open in P0/P1; source review and CI do not substitute for hardware acceptance.

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

### Scope reset — 2026-11-15

P0 is an engineering-fix queue, not a standing test programme. Do not add or run broad model-family fixtures, repeated benchmark matrices, profiler variants or multiple process-kill cycles unless a concrete observed failure makes that specific result necessary for a code decision. Use existing source diagnostics and one current in-app reproduction. A test/result with no decision it can change is not a P0 deliverable.

- The 2026-11-14 LFM3 exact-continuation run (16-token ceiling and arithmetic-style fixture) is **obsolete/inconclusive**. The fixture and budget have since changed; do not rerun it as a separate investigation. Reopen LFM3 only if the same model still produces a user-visible failure with the current app.
- Per-family correctness fixtures for Qwen3, Qwen3.5, Gemma 4 and every ordinary-attention architecture are not a P0 requirement. Investigate only the actual GGUF/model path that reproduces a defect.
- CPU/Vulkan parity and output-shape gates remain available as targeted developer diagnostics, not a mandatory matrix to run before every performance change. Preserve normal output correctness in the same real app run used to assess an optimization.

### Investigation update — 2026-11-15

- The uploaded 2026-11-14 LFM3 1.7B Q4_K_M acceptance evidence repeatedly reports `EXPECTED_OUTPUT_MISMATCH` for both CPU reference and Vulkan candidate. This is not evidence of a Vulkan-only defect; it does not yet distinguish a model-response issue from an unsuitable exact-answer fixture.
- The final 2026-11-14 device run used `maxPredictTokens=16` on both CPU and Vulkan (16 generated tokens each). The current source gate has since been raised to a 32-token safety budget; therefore those older mismatch results do not validate the current gate. That export also predates the 2026-11-15 content-free output-shape diagnostics, so it cannot reveal whether the model returned an empty answer, a truncated prefix, malformed text or a wrong sequence.
- The shared gate prompt is now an ASCII-only exact-copy fixture (`Copy exactly this text and output nothing else: 322, 324, 326, 328, 330`) rather than an arithmetic continuation task. This removes arithmetic/reasoning ability as a confounder when checking backend inference correctness; it is a fixture-isolation improvement, **not** a model-output fix or proof of semantic correctness (`63b5770`, `24a162e`).
- The latest 2026-11-15 user-device log records two completed CPU chat runs (17 generated tokens each, `STOP`, approximately 5.56 tok/s). It proves completed inference for those runs, but the exported summary does not contain the answer text, so semantic correctness cannot be inferred from throughput/finish reason alone.
- The previous prompt metadata trace recorded template mode, BOS/EOS and token count but omitted model architecture, model name, tokenizer model and thinking mode. These fields are now added to content-free native prompt metadata; failed acceptance rows also carry output-shape/length/repetition classification so the next log directly distinguishes empty, short-prefix, malformed and extra-text answers without persisting response text.
- **Root cause remains open.** Do not interpret this instrumentation commit as a model-output fix or mark P0-01 complete.


**Problem:** There have been incorrect/garbled outputs across Qwen3.5 and other model paths. A successful load or non-empty token stream is not sufficient evidence of correct inference.

- [x] Trace the complete generation path: request/messages → chat-template selection/rendering → tokenization/BOS/EOS → prompt evaluation → sampler/token selection → detokenization/streaming → finish reason. Source trace and unresolved findings: `docs/active-todos/P0-01-generation-path-audit.md`.
- [ ] Identify why affected models emit garbage or lose output quality. Inspect model metadata, architecture detection, tokenizer/template choice, special-token handling, sampler configuration, context state and CPU/Vulkan differences. Do not mask defects with prompt wording or arbitrary parameter changes.
- [x] Route JNI role/content conversion through the shared host-testable UTF-16→UTF-8 utility; cover BMP characters, valid/unpaired surrogates and embedded NUL bytes in host-native tests.
- [x] Reject null/out-of-range sampler token IDs before EOG lookup or `llama_decode`; log only the numeric ID, vocabulary size and run ID, with host-native boundary tests (`d2ac290`). This is defensive validation, not a confirmed explanation for the current empty-output symptom.
- [x] Move token-stream UTF-8 normalization into the host-testable native utility and cover multibyte code points split between token callbacks plus malformed/overlong UTF-8.
- [x] Remove the competing Kotlin-side visible-chunk limit; native token-ID accounting now sets `MAX_TOKENS` and flushes a pending UTF-8 suffix before stream completion (`8f94fe3`).
- [x] Finalize pending UTF-8 as U+FFFD on token-limit, cooperative-cancel, Kotlin coroutine cancellation and terminal error paths; Kotlin performs one final native step before every terminal stream exit; add host-native tests and keep the JNI/Kotlin terminal handoff aligned; add local helper compile smoke-check for 2/3/4-byte incomplete prefixes.
- [x] Strengthen the Android tiny-GGUF recovery smoke test to require non-blank visible text and a normal `STOP`/`MAX_TOKENS` finish after process death/reload (`23a65a9`). This is lifecycle/output-presence coverage only, not model-family correctness acceptance.
- [x] Extend the real-GGUF recovery test with a second consecutive inference on the recovered process; require visible output and a distinct native run ID to catch stale sampler/KV/terminal state (`34f330f`). This remains lifecycle/repeated-request coverage, not model-family correctness acceptance.
- [x] Add content-safe correctness-output classification for the synthetic gate: `EMPTY`, `PREFIX_TOO_SHORT`, `EXPECTED_PREFIX`, `EXACT_SEQUENCE`, `SEQUENCE_MISMATCH`, `NON_SEQUENCE_TEXT` and `EXTRA_SUFFIX`, plus a non-whitespace character count. Only the category/count are persisted; generated response text remains in-memory and is never written to diagnostics. JVM regression tests cover every category (`4c68e28`).
- [x] Capture the terminal EOG token-selection trace before the Kotlin null/terminal check, and clear the per-step trace before every native token attempt so non-sampling exits cannot replay a stale trace as a duplicate (`9bf5f3e`, `d460522`). This fixes trace attribution only; it does not establish model-output correctness.
- [x] Fix the correctness gate's self-imposed truncation: 16 generated tokens could not reliably complete the five-number answer on digit-per-token Qwen/LFM tokenizers. Increase the safety budget to 32, require the complete exact answer, and accept the model's natural early EOG after the answer; CPU/Vulkan trace lengths must match. JVM tests cover truncation rejection, early stop and paired trace-length mismatch (`2279d37`, `faeea29`, `752e26e`, `11db00b`, `dcd85d7`, `dcb6b4f`, `1f02efb`).
- [x] Correct gate accounting after allowing natural early EOG: `Evaluation.comparedSteps` reports the actual trace length on CPU-only pass and CPU/Vulkan comparison failures, rather than the 32-token safety ceiling. The acceptance report also derives its displayed gate budget from the shared constant instead of retaining a stale `16-token` label (`2c6b1ae`, `77b0407`, `5f7ce1e`).
- [x] Fix paired CPU/Vulkan **PASS** accounting to report `candidateSteps.size` when both traces stop naturally before the 32-token safety limit; add a regression test for a 25-step paired early-EOG PASS (`9f9afb6`, `c5131fa`).
- [x] Require natural EOG for correctness-gate acceptance: pass only when `InferenceResult.finishReason == STOP`; reject `MAX_TOKENS`, `CANCELLED`, `ERROR` and every timeout reason even when the answer and token trace otherwise match. Persist the actual finish reason in the content-free gate result. JVM regression covers every non-`STOP` enum value (`a73240f`).
**Historical test disposition:** The 2026-11-14 LFM3 mismatch is closed as inconclusive/obsolete, not as a model correctness pass. Its 16-token budget and earlier fixture cannot diagnose the current source. Reopen only if LFM3 still fails in normal app use; use its current content-free output-shape and prompt metadata from that real run.

**Acceptance:** Every intended model family passes repeatable output-correctness tests on CPU; Vulkan passes the same correctness contract on supported devices. Root causes and remaining unsupported combinations are explicit.

### P0-02 — Verify and correct the Mali-G57 Q4_K decode kernel path

**Current source finding (2026-11-15 source audit):** Mali Q4_K batch-1 AUTO currently selects the base DMMV path: `local_llm_mali_auto_prefers_mmvq` is explicitly `false` in the generated Vulkan overlay. The guarded Q8_1/integer-dot MMVQ path remains available for explicit diagnostic A/B selection; non-Mali vendors retain the pinned upstream `ggml_vk_should_use_mmvq()` AUTO heuristic. The 2026-11-14 runtime MMVQ evidence is historical and must not be described as proof of the current Mali AUTO selection. The pinned llama.cpp submodule remains pristine.

**Device evidence (2026-11-14):**
- Mali-G57 reports `int dot=1`, `fp16=1`, `bf16=0`, warp size 16, shared memory 32 KiB and no matrix cores.
- Runtime Q4_K decode log records `selected=MMVQ_INTEGER_DOT`, `reason=intDot_q4k_batch1`, `q8_1Eligible=1`, `quantizeY=1` and `yKernelType=q8_1`; prefill records `Q8_1_MATMUL`.
- A six-run Q4_K path sweep on LFM3 1.7B Q4_K_M (20 measured tokens/run, context 2048, batch 128, 2 threads) recorded BASE_DMMV at 4.14 and 3.92 tok/s, MMVQ_INTEGER_DOT at 2.71 and 2.80 tok/s, and AUTO repeat at 3.65 tok/s. The runtime path was marked verified. In these runs MMVQ was about 40–48% slower than BASE_DMMV. This is a strong model/device-specific performance result, not yet output-correctness acceptance for all Q4_K models.
- The LFM3 runtime load reported 25/25 model layers offloaded to Vulkan. Vulkan memory-budget extension support was unavailable, so the 7.78 GB heap value is a heap-size/free estimate, not a reliable driver-reported free budget.

- [x] Compare the selector against pinned llama.cpp `a4d4f2f`; preserve Q8_1 input/layout/alignment/precision eligibility while bypassing only the upstream shape heuristic (`a0f1cee`). CMake now fails configuration if these guards disappear.
- [x] On the target integer-dot-capable Mali-G57, capture runtime proof for Q4_K batch-1 `MMVQ_INTEGER_DOT` with `reason=intDot_q4k_batch1`, `quantizeY=1` and `yKernelType=q8_1`.
- [x] Perform repeated, run-correlated Q4_K MMVQ-vs-BASE_DMMV throughput comparisons with actual dispatch verification; MMVQ is slower in the current LFM3 1.7B sweep. Keep the production default unchanged until correctness/output parity is proven.
- [x] Validate Q4_K×Q8_1 pipeline-unavailable fallback evidence in the Vulkan A/B runner: accept only an explicitly reported BASE execution with integer-dot/Q8_1 eligibility, `quantizeY=0` and a non-Q8_1 fallback kernel; reject contradictory or falsely labeled MMVQ evidence. Runtime/device proof that the pinned driver actually exercises this fallback remains open.
- [x] Extract the fallback evidence predicate into `Q4kPipelineFallbackPolicy` and add JVM regression cases for valid fallback, missing integer-dot/Q8_1 eligibility, contradictory quantization/kernel evidence, wrong selected path and normal non-fallback dispatch.
- [x] Extend the generated Vulkan-overlay configure gate to require the explicit `q8_1_pipeline_unavailable_fallback` marker and verify that it is recorded before `quantize_y` is cleared; upstream `llama.cpp` remains untouched.
- [x] Trace the pinned upstream Vulkan decode selection and generated overlay end to end. In the pinned `ggml_vk_mul_mat_vec` path, `ggml_vk_should_use_mmvq()` only participates in the initial `quantize_y` heuristic; the overlay then applies the guarded Q4_K batch-1 policy. The only later change to `quantize_y` is the null-Q8_1-pipeline fallback, which is explicitly recorded before switching to the base kernel. Runtime evidence is emitted after that fallback decision.
- [x] Restore the pinned upstream MMVQ heuristic for AUTO on non-Mali vendors. The Q4_K overlay now applies its AUTO override only when `vendor_id == 0x13B5` (Mali) and Q8_1/integer-dot prerequisites pass; non-Mali AUTO retains upstream `quantize_y`. Explicit MMVQ A/B remains available, explicit BASE remains separate, and the generated-source configure gate requires the vendor-scope marker (`2f1fa1e`).
- [x] Source-audit and enforce the intended path scopes: Q4_K F16 prefill only for batch>1; Q4_K base/Mali AUTO decode override only for batch-1; Q5_K/Q6_K base overrides only for their own batch-1 tensor types; explicit MMVQ remains guarded by Q8_1 prerequisites; the pinned upstream MMVQ heuristic remains present for AUTO on non-Mali vendors. The Android overlay patches only generated `ggml-vulkan.cpp`; CPU code and the pinned llama.cpp submodule remain untouched.
- [x] Add deterministic CMake source-overlay scope guards and JVM policy tests for AUTO, explicit BASE, explicit MMVQ, missing-pipeline fallback and Mali/non-Mali reason handling. Fix the validator so non-Mali AUTO accepts the pinned upstream reason `upstream` instead of incorrectly requiring Mali-only `intDot_q4k_batch1`; actual device path proof remains open. Keep upstream llama.cpp untouched.
- [x] Reject unknown Q4_K dispatch path labels even in AUTO mode; AUTO remains evidence-driven only among the two recognized native paths (BASE DMMV or MMVQ Integer-Dot). The JVM regression test explicitly rejects unknown path labels.
**Scope:** The historical LFM3 A/B already establishes that the forced MMVQ path is slower for that specific model/device; Mali AUTO remains BASE DMMV. Do not repeat a multi-model A/B matrix. Capture the selected Q4_K path from the single current Qwen3-4B Q4_K run recorded under P0-03.

**Acceptance:** The intended path is proven by actual runtime selection and correctness evidence, or the selector is corrected based on a reproducible failing case. Requested configuration or source-string matches alone do not count.

### P0-03 — Root-cause the Vulkan throughput and synchronization regression

**Problem:** Current Mali-G57 measurements show Vulkan decode/prefill is not delivering the expected benefit and can be close to or slower than CPU. The source already contains substantial diagnosis; the latest uploaded device report now supplies a strong synchronization bottleneck signal and a repeatable Q4_K kernel-path performance difference.

**Measured device evidence (2026-11-14):**
- Native samples for PID 10321 show CPU decode p50 around 181–185 ms with `backendSyncWait` p50 1 µs; Vulkan decode p50 around 354–361 ms with `backendSyncWait` p50 around 339–345 ms. Thus roughly 95–96% of the measured Vulkan decode envelope is inside `llama_synchronize()`, while the CPU synchronization wait is negligible.
- Vulkan `decodeSubmit` p50 is around 15–16 ms in those samples; it does not account for the 339–345 ms synchronization envelope. This narrows the issue substantially, but `llama_synchronize()` still includes more than a pure fence wait: submission completion, fence waiting/polling and deferred copies can contribute.
- The report confirms the Mali-G57 device feature line and 25/25 layer offload for the LFM3 1.7B run. One older graph snapshot records 72 Vulkan nodes, 0 CPU nodes and 2 unassigned nodes out of 74; this graph snapshot is not yet correlated to the newest Q4_K sweep run.
- The Q4_K sweep provides measured evidence that MMVQ Integer-Dot is slower than BASE_DMMV in LFM3 1.7B. See P0-02 for the exact repeated throughput values.
- The latest 23:03 CPU/Vulkan acceptance gate failed its expected-output check on both backends. Its 5.51 vs 3.37 tok/s figures are therefore not accepted as a controlled performance baseline.
- The Vulkan GPU operation profiler was disabled in the reported Release runs; no per-operation GPU timestamps were present. The Vulkan memory-budget extension was not supported, so heap values are estimates.

**Implemented in source (not yet fully device-accepted):**
- [x] Add run-correlated scheduler graph-node accounting for CPU/Vulkan/other/unassigned backends, including GDN/SSM op classes. The generated `llama-context.cpp` overlay records distinct graph layouts.
- [x] Preserve compact Vulkan capability output (FP16, integer-dot, subgroup and related device features) and query Vulkan memory heap/budget information.
- [x] Add run-correlated Q4_K prefill/decode path evidence, including Q8_1 eligibility, `quantizeY`, selected kernel family and explicit pipeline-unavailable fallback reason.
- [x] Separate prompt template/tokenization/prefill metrics from per-token sampling, decode-submit and `llama_synchronize()` envelope metrics. The sync envelope is explicitly not labeled as pure GPU execution.
- [x] Add decode logits-readback map/copy/sync timing and opt-in per-operation GPU timestamp profiling. The GPU profiler is build/runtime opt-in and must remain separate from profiler-free throughput.
- [x] Replace the upstream Mali vendor-ID fence busy-poll loop with blocking `waitForFences` in the generated Android overlay; retain upstream polling for non-Mali devices. This is a candidate fix only; the uploaded report does not isolate its own before/after effect.
- [x] Add run-correlated Vulkan queue-submit and fence-wait timing callbacks to the generated overlay and native token-step metrics. Source audit against pinned upstream `a4d4f2f` found four queue-submit call sites: the two `ggml_vk_submit()` branches and two direct compute-queue submissions in `ggml_vk_synchronize()`; all four are instrumented. Fence timing covers the almost-ready fence, normal graph fence (including Mali blocking wait/non-Mali poll), serialized synchronize wait, serialized graph-split wait and profiler-only fence wait. The CMake configure gate requires the added path markers. Fields `vkQueueSubmitUs` and `vkFenceWaitUs` are host-side call/wait durations, not GPU shader execution time. Physical-device validation remains open (`5c578fb`, `b2c339a`, `246b54c`, `0afa383`).
- [x] Register the existing pinned llama.cpp sampler chain with the Vulkan context for normal non-diagnostic inference, allowing supported sampler stages to execute in the backend graph and avoid a full-vocabulary logits readback to CPU on each token. Detach the chain before replacement/unload. Keep CPU sampling on CPU and in `TEXT_DIAGNOSTICS` builds so Top-5 traces retain full logits. Runtime Mali speed impact is not yet measured; this removes a known transfer path but does not claim to solve the separate GPU synchronization envelope.

**One remaining device decision — no separate benchmark matrix:**
- [ ] On the current installable build 7c986e1 (APK artifact 14153973027), run the user's normal Qwen3-4B-Instruct Q4_K_M Vulkan chat once with the established settings (context 2048, batch 128, microbatch 128, 37 GPU layers). Read one compact diagnostics export for tokens/s, prefill/TTFT, backendSyncWaitUs, vkQueueSubmitUs, vkFenceWaitUs, logits readback phases, actual graph/offload and Q4_K dispatch, plus LOCAL_LLM_BACKEND_SAMPLING. Check the actual answer for obvious quality regression in that same interaction. This one run replaces the old separate baseline, fence A/B, prefill, graph/offload and Q4_K path requests. Choose the next code change directly from the measured dominant cost; do not run a separate profiler or multi-model matrix unless the current data leaves two plausible fixes.


**Acceptance:** A measured root cause explains the regression. Any fix is backed by repeatable profiler-free on-device CPU/Vulkan measurements and unchanged output correctness; otherwise retain the safe behavior and document the unresolved driver/backend limit.

### P0-04 — Make cancellation, timeout, abort and recovery deterministic

- [x] Trace cancellation end to end: collector/coroutine cancellation reaches `InferenceClient`, request-ID-checked Binder/AIDL `cancelInference`, `InferenceService`, `LocalLlmEngineImpl`, `NativeEngineHandle.nativeCancel` and the JNI atomic flag. The service rejects stale request IDs; llama.cpp's `abort_callback` is installed only for CPU because the pinned `llama.h` documents it as CPU-only. Vulkan therefore observes cancellation between token steps and relies on the isolated-process watchdog/hard-kill if an in-flight decode does not unwind.
**Timeout evidence disposition:** The missing INFERENCE_TIMEOUT_POLICY row in the 2026-11-14 export is historical and does not justify another stand-alone test. Current requests persist the effective values and their source. Inspect that row only if a new timeout/abort failure occurs; do not tune grace from an old token-latency sample.
- [x] Capture the actual service PID when the Binder request is dispatched and use that immutable request PID for timeout, explicit cancel and collector-close escalation; a reconnect cannot redirect an old request's abort to a replacement service.
- [x] Add a regression test that changes the reported service PID mid-request and asserts cancel/kill is not sent to the replacement process.
- [x] Cover prefill-timeout escalation after a service-PID replacement; liveness checks remain bound to the original request PID.
- [x] Confirm cooperative cancellation is attempted first, then hard-kill targets only the original inference-service PID after the configured grace period; never kill a replacement process. The blocked-native Android instrumentation test now asserts persisted cooperative-cancel and escalation events in order and checks the original PID.
- [x] Verify terminal events are emitted exactly once and streams close: `duplicateAndLateTerminalCallbacksAreIgnoredAfterCompletion` injects late error/cancel/duplicate-completion callbacks after the first completion and asserts exactly one terminal event plus a non-Inferring final state. Existing timeout tests continue to assert timeout failures are never successful completions; process-death/stale-PID coverage remains separate.
- [x] Treat `streamChat` collector cancellation as a real native cancellation: retain the request ID through cooperative unwind and escalate to the original service PID after the configured grace period; add a non-cooperative fake-service regression test.
- [x] Add fake-service regression coverage for reconnect to a replacement PID, EAGER same-model reload and a successful follow-up inference (`eagerReconnectReloadsSameModelAndAllowsFollowUpInference`).
- [x] Add fake-service regression coverage proving a successful follow-up inference after cooperative cancellation (`followUpInferenceSucceedsAfterCooperativeCancellation`).
- [x] Add fake-service regression coverage for cancellation during model load, prefill (including explicit cancellation before `Started`), decode, stream collection and reconnect; verify follow-up inference after escalated cancellation. The focused coverage is in `InferenceIpcTest` and the process-kill Android test.
**Device escalation rule:** Do not schedule repeated kill/reconnect cycles. The real-GGUF recovery instrumentation already covers a non-cooperative abort, process replacement, model reload and two follow-up inferences. Revisit a physical-device recovery run only if an actual app timeout/cancel/crash fails to recover.

**Acceptance:** No stuck request, duplicate terminal event, stale-PID kill or poisoned post-cancel model state. A following inference succeeds after every tested recovery path.

### P0-05 — Correct selected-model loading and descriptor ownership

**Source audit:** The service already accepts and retains a Binder-transferred descriptor, validates regular-file/seek/mmap capability in `ModelSourceResolver`, and passes the service-local `/proc/self/fd/N` to JNI. The defect was in `InferenceClient.loadModelInternal()`: the `ContentUri` branch copied the document into private storage before Binder handoff, so ordinary single-file loading never reached the descriptor route.

- [x] Replace the normal `ContentUri` load branch with `ContentResolver.openFileDescriptor(uri, "r")` and transfer that original descriptor through the existing Binder method.
- [x] Keep the provider URI as the in-memory model identity/recovery source and use the provider display name; explicit bulk/model-library import remains a separate copy operation.
- [x] Reject descriptors without a known size before the memory guard; show an explicit provider/mmap limitation and do not silently copy.
**Descriptor test disposition:** The Android instrumentation source now covers descriptor ownership across failed load, replacement, unload and service death/recovery. Do not request a separate descriptor-only matrix. Treat local document-picker loading as a user-visible P0 issue only if it fails in normal use; the source test is not represented as a target-phone pass.
- [x] Correct the instrumentation size assertion: compare the provider PFD's `statSize` to the staged GGUF bytes; do not compare container-file size to `llama_model_size` (tensor/model payload accounting). Stage the same fixture in both possible test-provider cache contexts.

**Acceptance:** Original-source loading works end to end where supported; unsupported descriptors fail visibly and deterministically; no leaked descriptors or hidden copies.

### P0-06 — Prove model-aware cache and context-state correctness

**Current implementation finding:** Ordinary attention uses common-token-prefix reuse where supported. Hybrid/recurrent models currently fall back to clearing state. Keep this safe fallback until all relevant state components are proven reusable.

- [x] Audit the exact pinned llama.cpp revision for ordinary attention, sliding-window, recurrent/SSM and convolution state behavior; classify LFM3 and Qwen3.5 as hybrid/recurrent-safe-clear paths and ordinary/SWA-only models as conditional KV-prefix paths. Evidence: `docs/active-todos/P0-06-memory-state-audit.md`.
- [x] Define cache identity/invalidation for loaded model/file identity, adapters, tokenizer/vocabulary, rendered chat template, BOS/EOS, system prompt, context settings and session. The audit documents current invalidation boundaries and the remaining non-leading-system-message context-shift edge case.
- [x] Add host-native token-prefix tests for appended turns and invalidation when system-prompt, rendered-template/BOS-boundary or tool-message tokens change. Exact-prefix comparison also covers rewrites/deletions at the first changed token.
- [x] Source-audit lifecycle invalidation instead of adding a duplicate lifecycle matrix: unload/reload resets model state; prefill/decode cancellation and errors clear KV plus the token mirror; process restart creates a fresh EngineInstance; memory-pressure unload uses normal unload and closes the descriptor. See P0-06-memory-state-audit.md.
- [x] Guard non-leading/interleaved system messages: when system messages are not a contiguous leading prefix, context shifting preserves the complete current prompt instead of risking removal of a protected system message. Host-native tests cover leading, absent and interleaved system-role sequences.
- [x] Enforce `cached_tokens.size() == current_position` before prefix reuse and context shift; mismatch forces a full KV/cache clear, with host tests for shorter, longer and negative-position states.
- [x] Keep incremental reuse conditional: only non-hybrid/non-recurrent memory may reuse an exact token prefix; negative/count mismatch clears KV, failed partial seq_rm falls back to full clear, and hybrid/recurrent paths always clear. Host tests cover the prefix and state invariants.
**Scope:** No standalone cache benchmark or cache-on/cache-off test matrix is needed for P0. Incremental reuse is not being tuned as a performance workstream; retain the conservative architecture gates and full-clear fallbacks above. Reopen only if a real multi-turn output/state defect is observed.

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
- [ ] Add tests for cancellation during permission/metadata lookup, copy, verification and between files. Audit note: the bulk-import display-name query currently wraps `ContentResolver.query()` in `runCatching`; a cancellation exception is temporarily converted to the fallback name, then observed by the immediately following `ensureActive()`. Add a deterministic test for cancellation while the metadata query is blocked and consider replacing the broad catch with explicit `CancellationException` rethrow for direct propagation.

**Acceptance:** A cancelled import cannot continue as a sequence of ordinary file failures or report success.

### P1-04 — Complete standalone engine regression and device acceptance

**Nachgetragene Nutzer-Gerätetests (aus bisherigen Rückmeldungen; nicht als ungetestet behandeln):**
- **2026-10-31, Build `0f469db`, nubia Z2462N:** Nutzer meldete 7 Testläufe. `TEST_1_OK`, `TEST_2_DMS`, `TEST_3_JSON` und CPU-Chat erfolgreich; Extraktion und ein CPU-Chat-Lauf liefen in TIMEOUT; Vulkan-Chat erfolgreich mit 3,53 tok/s. Das belegt funktionierende CPU-/Vulkan-Inferenz für diese konkreten Läufe, nicht fehlerfreie Unterstützung aller Profile.
- **2026-10-31, Build `6f9b94f`, nubia Z2462N:** Nutzer meldete 9 Profiltests. Vulkan-Extraktion erfolgreich (88 Tokens, JSON-Inhalt); weitere Vulkan-Tests waren inhaltlich bzw. durch MAX_TOKENS fehlerhaft, CPU-Profile überwiegend TIMEOUT. Diese Fehler sind konkrete, bereits bekannte Einschränkungen und dürfen nicht als fehlende Testdaten geführt werden.
- **2026-11-07:** Nutzer meldete zwei Modellladefehler beim SAF-Quellmodell `msf%3A1000012405`: `/proc/self/fd/123` nicht vorhanden/nicht lesbar; Mali-G57 wurde erkannt, kein RAM-Engpass.
- **2026-11-08:** Nutzer meldete, dass der Ladevorgang anschließend `MODEL_LOAD_STARTED` und 70 % erreichte; danach Diensttrennung bei ca. 4,3 GB Native Heap und 274 MB verfügbarem RAM. CPU-Auswahl lud 0/37 Layer auf die GPU; Recovery startete erneut, Abschluss war nicht bestätigt.
- **2026-11-09:** Nutzer bestätigte ausdrücklich, dass die App auf dem nubia Z2462N sowohl mit CPU als auch Vulkan lief. Das ist ein positiver physischer Gerätetest und widerlegt die pauschale Einstufung „keine Nutzer-Gerätetests“. Es ersetzt nicht automatisch die noch offenen Modellfamilien-, Kernel-, Recovery- und Mehrfachzyklus-Abnahmen.
- **2026-11-14:** Die aktuelle TODO enthält bereits konkrete Mali-G57/LFM3 Runtime- und Kernel-Sweep-Messwerte in P0-02/P0-03. Diese sind physische Gerätedaten; die CPU/Vulkan-Korrektheits-Gates dieses Laufs scheiterten jedoch auf beiden Backends und sind daher keine bestandene Output-Abnahme.

**Statusregel:** Eine vom Nutzer ausgeführte Prüfung mit anschließender Rückmeldung zählt als Testnachweis. Wenn der Nutzer für einen konkreten Build/Funktionsumfang keine Probleme meldet oder ausdrücklich Erfolg bestätigt, ist dieser Umfang als nutzerseitig bestanden zu dokumentieren; nicht getestete Randfälle bleiben separat offen. Bereits gemeldete Fehler werden als bekannte Fehler geführt und nicht erneut als „fehlender Test“ etikettiert. Keine pauschale Hochstufung auf vollständige Modell-/Backend-Kompatibilität ohne entsprechenden Testumfang.

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

**Additional source finding (2026-11-15):** `ChatViewModel.sendMessage()` correctly builds each request from the complete in-memory conversation (`system` + all prior user/assistant messages + the new user message); `LocalLlmEngineImpl.streamChat()` forwards the ordered roles and contents to JNI, and the native prompt path can discard oldest history only when the model context limit requires it. The chat transcript itself is a plain `MutableStateFlow` owned by the ViewModel; no durable session persistence/restore is present in this path. This is a real multi-turn context path during the ViewModel lifetime, but not a persistent conversation/session feature. Include explicit chat/session ownership and a regression test for ordered multi-turn request construction in the composition refactor; do not conflate this with the separate model-output correctness work in P0-01.

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

**Evidence (2026-11-15):** The original root `CMakeLists.txt` was 1,707 lines / 91,953 bytes. Commit `7440933` reduces it to a 27-line orchestrator and moves the existing configuration/overlay/target blocks unchanged into six responsibility-specific modules under `cmake/`.

- [x] Split Android toolchain flags/ABI selection, upstream build/Vulkan configuration, Android Vulkan compatibility, graph/GDN diagnostics, native target wiring and scheduler-race verification into focused CMake modules. The Vulkan GPU profiler configuration remains inside the Android Vulkan module.
- [x] Keep the root `CMakeLists.txt` as ordered orchestration only; preserve the existing CMake function bodies, targets, compile flags and ABI settings during extraction.
- [x] Retain fail-fast upstream anchors and build-directory-only generated files. The pinned llama.cpp submodule is unchanged.
- [x] Verify CMake configure/build and APK packaging for ARM64 and x86_64. Run `43679806820` passed both `configureCMakeDebug[arm64-v8a]`, `configureCMakeDebug[x86_64]`, `buildCMakeDebug[arm64-v8a]` and `buildCMakeDebug[x86_64]`, then built the installable Debug APK and instrumentation APK; emulator process-isolation instrumentation also passed. Artifacts: Debug `14148461029`, instrumentation `14148281622`. The pinned llama.cpp submodule check passed.

**Acceptance:** Same outputs/compile configuration with smaller, reviewable CMake components; clean submodule remains unchanged.

### P2-05 — Split diagnostics implementation

**Evidence (2026-11-15):** `EngineDiagnostics.kt` is now a 143-line API facade. Storage/rotation/multi-process merge/export, metric parsing and compact report composition are owned by separate components.

- [x] Keep the privacy-safe event contract established in P1: raw prompt/document/token-piece/response text is discarded; numeric token traces remain opt-in and validated.
- [x] Extract process-log storage, per-process rotation, deterministic cross-process log merge and verified URI export into `EngineDiagnosticsStorage`.
- [x] Extract native/Kotlin token-step parsing, Vulkan operation parsing and timing/percentile helpers into `EngineDiagnosticsMetrics`.
- [x] Add direct parser tests for current vs legacy sync fields, run correlation, Vulkan metrics and malformed rows. Existing Android process instrumentation continues to verify app-process file persistence.
- [x] Move compact report composition, resource aggregation and UTF-8 export bounding into `EngineDiagnosticsReportBuilder`; `EngineDiagnostics` remains the API facade (143 lines after extraction).
- [x] Define bounded version-1 Kotlin diagnostic event envelopes with process-local event IDs, PID and automatic run/request correlation. Cap event details at 4,096 characters and normalize multiline values; retain legacy/native parsing for historical and native-format records. Add tests for run/request correlation, zero/unknown test PID, detail bounds and single-line persistence.

**Acceptance:** Cohesive diagnostics components with bounded, privacy-safe exports and independent tests.

### P2-06 — Organize demo and library packages by ownership

**Evidence (2026-11-15):** The app now has dedicated `navigation`, `chat`, `modelimport`, `settings` and `diagnostics` packages. Screens, `AppTab`, feature ViewModels, model-library/import state, lifecycle/benchmark ViewModels and benchmark plans/policies were moved in commits `f76e6f5` and `964ba94`. The shared `EngineViewModel` remains the app facade. The `com.example` namespace and library-internal package organization are still open.

- [x] Split demo chat, model library/import, settings, diagnostics/benchmarks and navigation into feature packages. Screens/navigation moved in `f76e6f5`; progress/model-card imports corrected in `e8944f4` and `7365270`; ViewModels/plans moved in `964ba94`. JVM tests passed in `43679806775`; Android APK build and emulator process-isolation instrumentation passed in `43679806820` (artifacts `14148461029` and `14148281622`).
- [ ] Replace `com.example` only after auditing manifests, generated BuildConfig, instrumentation, saved state and deep links.
- [ ] Organize library internals by lifecycle, model source, memory/resource policy, diagnostics and IPC; keep consumer-facing types separate.
- [x] Enforce dependency direction `:app → :llm-engine`; the library Gradle module depends only on Android core/coroutines and has no app/UI/benchmark/DMS module dependency.

**Acceptance:** Package and module boundaries express ownership; the library remains independently consumable.

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
