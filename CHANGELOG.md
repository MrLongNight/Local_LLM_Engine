# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

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
