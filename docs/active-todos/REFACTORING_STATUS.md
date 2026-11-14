# Refactoring status — Local_LLM_Engine

**Live tracker:** SettingsScreen extraction (Task 3).
**Repository:** `MrLongNight/Local_LLM_Engine` · **Branch:** `main`
**Latest inspected code SHA:** `af594b8fe80147ce4a544555a9af683ebc20782b` (2026-11-14).

## Task 2 — EngineViewModel split

<badge>Structurally complete; CI verified</badge>

The `EngineViewModel` facade is reduced to navigation/shared state and delegates feature behavior to focused ViewModels. All screens continue to receive the same `EngineViewModel` instance; the public constructor and screen call sites are preserved.

| Responsibility | Extracted owner |
|---|---|
| Chat | `ChatViewModel.kt` |
| Model load, restore, unload and model library | `ModelManagementViewModel.kt` |
| SAF import | `ModelImportViewModel.kt` |
| Settings, configuration and system prompt | `SettingsViewModel.kt` |
| Shared benchmark diagnostics and safety | `BenchmarkDiagnosticsViewModel.kt` |
| CPU/Vulkan backend acceptance | `BackendBenchmarkViewModel.kt` |
| Vulkan layer and Q4_K diagnostics | `VulkanDiagnosticsViewModel.kt` |
| Configuration smoke test | `ConfigurationSmokeViewModel.kt` |
| Startup, lifecycle, status and close | `EngineLifecycleViewModel.kt` |

**Verified at code SHA `c48090423ae985226f92e1c7d805d308023504cc`:**

- Unit Tests run `43561524744`: completed / success.
- Android APK Build run `43561524767`: completed / success. APK and instrumentation APK built, uploaded and emulator process-isolation suite completed successfully.
- Uploaded artifacts: Debug APK `14100747596` (36,938,722 bytes); instrumentation APK `14100338808` (24,297,890 bytes).
- A previous compile failure at `d86b2cf` was due to `_benchmarkReport` visibility; `c480904` corrected it without changing the public `benchmarkReport` StateFlow.

## Task 3 — SettingsScreen sections

<badge color="warning">Extracted; CI compile verification currently failing</badge>

At `af594b8`, the existing `SettingsScreen.kt` UI sections have been mechanically moved into these composables:

| Section | File | Current source state |
|---|---|---|
| System prompt | `ui/settings/sections/SystemPromptSection.kt` | Extracted |
| Backend and GPU layer selection | `ui/settings/sections/BackendSettingsSection.kt` | Extracted |
| Vulkan tuning and GDN policy | `ui/settings/sections/VulkanTuningSection.kt` | Extracted |
| Context and inference settings | `ui/settings/sections/InferenceSettingsSection.kt` | Extracted |
| Sampling parameters | `ui/settings/sections/SamplingParametersSection.kt` | Extracted |
| CPU/Vulkan acceptance test | `ui/settings/sections/BackendAcceptanceTestSection.kt` | Extracted |
| Targeted Q4_K Vulkan diagnosis | `ui/settings/sections/VulkanQ4kDiagnosticsSection.kt` | Extracted |
| Benchmark result / diagnostic note | `ui/settings/sections/BenchmarkResultSection.kt` | Extracted |

`SettingsScreen.kt` now composes these sections in the existing order. The commit chain includes import corrections after the mechanical moves.

**Latest CI evidence at this snapshot:**

- Unit Tests run `43564336179` for `af594b8`: **failure** during `:app:compileDebugKotlin`. The host-only native prompt-cache/truncation test passed; the Gradle job did not reach a green test run.
- Android APK Build run `43564336177` for the same SHA: **failure** during `:app:compileDebugKotlin`; no installable APK or instrumentation APK was uploaded for this SHA. The ARM64 native CMake build progressed, but Kotlin compilation failed before APK packaging.
- Exact compiler findings shared by both runs: `BenchmarkResultSection.kt` has an `item` call outside a `LazyListScope` and consequent invalid composable context; `InferenceSettingsSection.kt` is missing `testTag` and `Slider` references (and consequently its slider lambda parameter `it`); `SamplingParametersSection.kt` is missing `padding` and `MaterialTheme` imports. These are source compile issues, not test assertion failures. They remain open and must be corrected before Task 3 can be considered CI-green.

## Remaining refactoring work

- Fix the exact Task 3 compile errors listed above, then rerun Unit Tests and Android APK Build on the resulting final code SHA.
- Confirm APK and instrumentation APK artifact IDs and the emulator instrumentation conclusion for that same SHA.
- Keep physical-device smoke testing separate from CI: Settings behavior, model load, GPU backend/offload and real Mali-G57 execution still require the target phone.
- ModelImportScreen extraction, InferenceServiceProcessTest class split and `:app` package moves remain deferred; only resume them as separate focused tasks after Task 3 is green.
- Use-case/repository extraction remains optional; do not add abstractions without a concrete reuse/ownership reason.

Update this document in place after each verified task/CI transition. It is the only live refactoring status tracker.
