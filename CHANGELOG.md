# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

### Changed
- ModelInfo now reads `general.architecture` from GGUF metadata while preserving the legacy JNI metadata format and semicolon-containing backend description. The chat status card explicitly warns when the Qwen3.5 GDN CPU fallback is active.
- The correctness-gated full matrix keeps Flash Attention ON/OFF coverage and adds matching CPU/Vulkan AUTO profiles at `n_ubatch=64`, so the Mali reference setting is measured without changing runtime defaults.

### Fixed
- Tightened the 16-step greedy correctness gate to reject malformed/extra trace rows, mixed run/backend metadata, non-finite or unsorted Top-5 logits, and reused CPU-reference run IDs; synthetic regression tests cover each fail-closed condition.
- Qwen3.5/Qwen3.5-MoE now disable both fused autoregressive and chunked Gated Delta Net paths. The Android Vulkan `supports_op` overlay also rejects `GGML_OP_GATED_DELTA_NET`, forcing that operator to the CPU reference backend while leaving unrelated Vulkan operations enabled. CMake validates both source anchors and the exported `vulkanGdn=cpu-fallback` policy line; this is a correctness fallback, not a measured performance optimization.
- Restricted TEXT_DIAGNOSTICS and native prompt/token tracing to Debug builds only. The standard compact export now excludes rendered prompts, token-piece text and generated test responses while retaining GDN policy, graph split/CPU-Vulkan node shares and per-profile correctness-gate statuses.
- Reworked ModelMemoryGuard to hard-fail only when GGUF + estimated KV cache + 256 MiB exceeds 85% of total RAM, after context reduction. Available RAM and Android low-memory status are soft warnings; Qwen3-4B (2381 MiB) with 2770 MiB available now loads with a reduced context instead of being blocked. Added one-shot “Trotzdem laden” acknowledgement for the hard-limit case.
- Native prompt boundary handling now preserves/deduplicates template BOS markers and suppresses model-requested EOS while `add_generation_prompt=true`; EOS is appended only when explicitly closing a completed sequence. Host tests cover Qwen, LFM3, Llama-3, Gemma, missing/duplicate BOS and EOS sequence closure.
- Diagnostic log entries now escape embedded newlines, and compact-export start/end times are derived only from valid timestamp-prefixed rows; multiline exceptions can no longer corrupt the report header.
- Binder stream early exits now pass through `awaitClose` cleanup, and the greedy gate requires exactly the five expected numbers with no extra answer text.

### Added
- Added a Debug/TEXT_DIAGNOSTICS correctness gate to the G1 matrix. It captures the actual selected token and pre-decode top-five logits for 16 greedy steps, checks the fixed 2..320 continuation, and compares every Vulkan profile against its matching CPU reference (same GGUF, micro-batch and Flash Attention setting). The result now preserves the native run ID; missing CPU references, incomplete gates and measurement failures fail closed, and invalid rows do not report tok/s.

### Changed
- Qwen3.5 (qwen35/qwen35moe) uses the unfused autoregressive Gated Delta Net path while retaining fused chunked prefill. This correctness-first fallback isolates malformed decode logits to the fused single-token path without changing other model architectures.

### Verified
- 12.11.2026, commit `528e1d3` (APK run `#264`, unit-test run `#344`): Android Debug APK CI passed, including the 7/7 Android-36 x86_64 process-isolation instrumentation tests, native Vulkan/graph-overlay checks, APK manifest validation and explicit profiler-disabled BuildConfig assertion. Unit tests, native host prompt-cache/truncation tests and pre-commit checks also passed.
- Documentation-only follow-up commit `d5fdb1b` also passed unit-test run `#346`. The unit-test push trigger is now path-filtered so prose-only commits do not rerun Android builds; pull requests remain fully checked.
- The path-filtered unit-test workflow itself passed run `#347` (commit `fc1d3fc`), including the SDK setup and host-side native prompt-cache/truncation tests.
- Retrieved and inspected the uploaded ARM64-v8a/x86_64 Debug APK (79,063,814 bytes; SHA-256 `55166f9bb1c17f4f39ff9f01240098f8d0bde49d78d86eefb69c4e7646442c5e`). This CI artifact is a profiler-disabled Debug APK, not a release build; target-device Mali-G57 acceptance remains open.


### Changed
- The Android APK workflow exposes the Vulkan per-operation profiler as an explicit manual boolean input and verifies the generated Debug BuildConfig value; normal push builds keep it disabled.


### Added
- Added a separate Debug-only opt-in for Vulkan per-operation profiling via `-PlocalLlmVulkanPerfProfiler=true`; normal builds remain profiler-free and Release forcibly disables it.
- Added an explicit logger diagnostic recording whether the profiler opt-in is active.

### Fixed
- Android APK CI now verifies the generated graph-diagnostics overlay whether CMake compiles it directly or includes it in a Unity translation unit; marker checks still inspect the actual generated overlay source.


### Added
- Added per-run CPU/Vulkan graph-node assignment diagnostics from the pinned llama.cpp scheduler callback. The compact report distinguishes CPU fallback nodes from Vulkan nodes instead of relying on aggregate graph-split totals alone; CI verifies the generated source overlay is compiled.


### Fixed
- Compact diagnostics now include per-inference thermal peak and observed thermal states alongside peak PSS and minimum available RAM; the matrix run ID remains the join key.


### Fixed
- Configuration screening now resolves AUTO n_ubatch from the actually loaded backend (Vulkan 64 or CPU n_batch), rather than assuming Vulkan for an AUTO model that may have fallen back to CPU.


### Fixed
- The configuration smoke-test baseline now preserves n_ubatch AUTO (Vulkan 64, CPU n_batch). The n_batch candidate pins the baseline's resolved ubatch so each profile still changes only one effective parameter.


### Fixed
- Kept the raw n_ubatch AUTO value at the JNI boundary so Vulkan uses its 64-token mobile default instead of accidentally receiving n_batch=512; resource snapshots separate effective and requested micro-batch values.
- Native prompt evaluation now calls llama_decode in n_ubatch-sized chunks, reporting progress and checking cancellation at each completed micro-batch. The Kotlin prefill start performs a final cancellation handshake before JNI.
- Native INFO/DEBUG log writes are buffered and flushed at most every 500 ms; WARN/ERROR still flush immediately. Fixed native handle destruction logging to capture its address before delete.
- Corrected README documentation for the opt-in Vulkan profiler and the private SAF import/recovery path.


### Fixed
- Replaced the stale remembered-model GPU-layer fallback of 37 with the explicit full-offload request value 99.
- Native CPU thread fallback now uses the performance-core frequency policy; Vulkan offload caps host and batch submission pools to 1–2 threads.
- Added explicit NEON, DOTPROD, I8MM and FP16 runtime-feature diagnostics. Pinned llama.cpp's CPU-only abort-callback limitation is logged for Vulkan rather than implying cooperative Vulkan abort.
- Added scheduler graph-split metrics through an internal bridge compiled into the pinned llama target without changing llama.cpp's public C API.
- Added critical-memory-pressure handling in :inference: after cooperative cancellation when necessary, the service unloads the model and closes its retained model descriptor. Per-inference RAM/thermal snapshots are grouped by run ID for peak-PSS reporting.

### Build
- CI verifies that the arm64 KleidiAI runtime-dispatch translation unit is compiled with GGML_USE_CPU_KLEIDIAI; upstream KleidiAI performs runtime HWCAP feature selection for DOTPROD/I8MM with compatible NEON fallbacks.

### Added
- Added an executable model-quantization-aware benchmark matrix covering Vulkan/CPU, AUTO/SUBGROUP/LARGE, matching Q4_K/Q5_K/Q6_K A/B profiles, n_ubatch 32/64/128 and Flash Attention disabled/enabled. Each profile reloads and validates the same GGUF and records model load, prefill, TTFT, decode speed and graph splits.
- Appended graphSplits and diagnosticRunId to InferenceResult with backward-compatible Parcelable defaults. Each matrix row now joins directly to the matching INFERENCE_<runId> Peak-PSS, minimum available RAM and thermal summary.
- Added a concrete Qwen3/Gemma 4 GGUF catalogue with HF repositories, filenames, published sizes, Gated DeltaNet flags and explicitly labelled ModelMemoryGuard estimates at ctx 2048.

### Fixed
- Updated the app initialization test for the explicit 99-layer full-offload request (actual Vulkan offload remains determined by native backend diagnostics).
- Made InferenceRequest Parcelable reading backward-compatible when older clients omit the appended enableThinking field, and use the typed Parcelable reader on Android 13+.
- Kept token events lossless with an unlimited output buffer, added coroutine cancellation checks during decode, and moved prompt progress through a conflated channel; a closed token collector now requests native cancellation.

### Changed
- Default Vulkan configuration now requests full layer offload and uses two host threads; configuration diagnostics include sampler and context/batch/thread settings.

### Documentation
- Added a pinned-revision model compatibility catalogue. Qwen3.5 and Gemma 4 architecture/model graph support is distinguished from Vulkan/CPU operator coverage and real-device verification; neither is advertised as device-validated without a matching GGUF acceptance run.

### Added
- Added model-specific topK/minP sampler defaults as backwards-compatible EngineConfig/Parcelable settings with persistent storage and UI overrides.

### Changed
- Replaced llama.cpp's broad default sampler stack with a minimal chain: greedy-only at temperature <= 0, otherwise top_k/top_p/min_p/temperature. DRY, XTC, typical-p, penalties and top-n-sigma are explicitly disabled.
- Removed a stale fixed GPU-layer recommendation from Settings; the UI now labels the selected value as requested offload rather than claiming one layer count fits a model.

### Added
- ContentUri/SAF model sources are imported into the private models directory before Binder handoff. Import checks available storage, verifies GGUF magic, hashes content, reports progress, and reuses a cached copy when provider size/mtime metadata is stable.
- Added a pre-load RAM guard that estimates model + KV-cache + native headroom, reduces context when possible, and rejects unsafe loads before native allocation.

### Changed
- Reconnect recovery for imported SAF models now remembers the private file path rather than reopening the provider-backed FUSE URI.
- Model-load progress combines SAF copy progress and native model-load progress.


### Added
- Added host-testable CPU performance-core thread selection using sysfs maximum frequencies (>=70% of device maximum, clamped to 2..4), shared by Kotlin recommendations and JNI defaults.
- Added per-run available-RAM and Android thermal-status diagnostics, plus periodic thermal status in resource snapshots.

### Changed
- Vulkan offload defaults to a 2-thread CPU submission pool when the caller does not set an explicit thread count; CPU defaults use the detected performance-core count.
- Native INFO/DEBUG log lines are buffered and flushed at WARN/ERROR or on a one-second interval; rotation still flushes before truncation.

### Changed
- Increased the default cooperative abort grace period from 300 ms to 3,000 ms; test-only `fast()` timing remains intentionally short.
- Prefill timeout is now a stall deadline reset by each native prompt-progress callback, while the existing total-inference deadline remains the hard overall cap.
- Added persisted `reloadAfterAbort` policy (`LAZY` by default, `EAGER` optional) so reconnect does not automatically reload a large model unless selected.
- Added cooperative-cancel and process-kill request counters to IPC diagnostics.

### Added
- Added host-JVM timeout policy tests covering long prompts with regular progress and true no-progress stalls.
- Added persisted Flash Attention and reload-after-abort settings to the remembered-model configuration.

### Added
- Added host-testable native helpers for exact prompt-prefix reuse and protected-message truncation; host C++ tests run in GitHub Actions.
- Added native incremental KV-prefix reuse for ordinary attention models, with automatic full-clear fallback for recurrent/hybrid models and failed sequence-removal operations.
- Added system-prefix-aware context shifting guarded by `llama_memory_can_shift`, and a controlled `PROMPT_TOO_LONG` result instead of cutting off the prompt tail.

### Changed
- Prompt progress and prefill metrics now count only tokens actually evaluated after prefix reuse; diagnostics also report reused token counts.
- Prompt truncation removes oldest history messages first while retaining system messages and the latest user message.

### Added
- Added explicit Vulkan A/B selectors for Q5_K/Q6_K base-MMV decode and Q4_K F16/F32 prefill; original overlay source-anchor guards remain mandatory.
- Added benchmark profile generation for Vulkan n_ubatch 32/64/128 and Flash Attention ON/OFF on Vulkan and CPU.

### Changed
- Mali vendor ID 0x13B5 selects base-type Q4_K decode in Vulkan AUTO; Q5_K/Q6_K remain measurement-only overrides.
- Vulkan n_ubatch defaults to 64 when no explicit value is set.
- Native Release flags require -O3 and -DNDEBUG; optional LTO is opt-in.
- Vulkan per-operation profiling is no longer automatically enabled in Debug builds; it requires explicit opt-in.

### Fixed
- EngineConfig uses safe enum parsing, typed Parcelable reads on API 33+, and backward-compatible appended Flash Attention parcel data.
- Flash Attention mode is forwarded explicitly to llama.cpp context parameters.

### Fixed
- Fixed the Android CI build failure caused by the Vulkan backend not finding the installed SPIRV-Headers CMake package during native configuration.
- Hardened the GitHub Actions workflow to build and install SPIRV-Headers before invoking CMake, and to expose the install prefix to the Android native build.
