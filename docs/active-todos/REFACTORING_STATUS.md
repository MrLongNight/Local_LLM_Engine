# Refactoring status — Local_LLM_Engine

**Live tracker:** InferenceServiceProcessTest split (Task 5).
**Repository:** `MrLongNight/Local_LLM_Engine` · **Branch:** `main`
**Latest inspected code SHA:** `5c6caea4c508a073ff245dd118bd3b1844066575` (2026-11-14).

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

<badge color="success">Extracted; CI build and emulator verification passed</badge>

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

**Compiler fixes and verification:**

- Unit Tests run `43565763556` on code SHA `e1e3f5634a9ed19399980bbbeb81dedc10839693`: completed / success.
- Android APK Build run `43565763512` on the same code SHA: completed / success. Debug APK and instrumentation APK were uploaded; emulator process-isolation instrumentation completed successfully.
- The earlier errors in `BenchmarkResultSection.kt`, `InferenceSettingsSection.kt` and `SamplingParametersSection.kt` were corrected with the minimal changes required to restore the original LazyList item boundary and Compose imports. Public SettingsScreen signature and section order remain unchanged.

## Task 4 — ModelImportScreen sections

<badge color="warning">Code extraction complete; Android build / emulator verification in progress</badge>

The public `ModelImportScreen(viewModel, modifier)` signature, six StateFlow collections, three SAF launchers and LazyColumn remain in the main file. The new files under `ui/modelimport/` are:

- `ImportErrorBanner.kt`, `ActiveModelSection.kt`, `ActiveModelDetails.kt`
- `FilePickerSection.kt`, `FilePickerActions.kt`, `ImportProgressSection.kt`
- `ImportedModelsSection.kt`, `ImportedModelEmptyState.kt`, `ImportedModelCard.kt`

Sizes: main screen 3,957 bytes; every new section below 4.4 KB. No `@Preview` existed in the original screen. Existing shared `ModelLoadProgressIndicator` and `ImportedModelEntry` remain in place.

Code commits: extraction `ecffd68be8608356d0c2f3dfefbfd5b0ae1fe383`; import correction `5c6caea4c508a073ff245dd118bd3b1844066575`. Unit Tests run `43567003341` on `5c6caea`: completed / success, including host-only native tests. Android APK Build run `43567003311` for `5c6caea` is in progress at this update.

Detailed mapping and file sizes: [ModelImportScreen extraction report](../REFACTORING_MODEL_IMPORT.md).

## Task 5 — InferenceServiceProcessTest split

<badge color="warning">Code split complete; unit CI green; Android instrumentation build pending</badge>

The 30,405-byte, 629-line `InferenceServiceProcessTest.kt` has been split into five concrete instrumentation test classes:

| Test class | Tests | Bytes |
|---|---:|---:|
| `NativeBoundaryAndIpcTest.kt` | 2 | 2,248 |
| `ModelSourceDescriptorTest.kt` | 2 | 2,820 |
| `ProcessLifecycleTest.kt` | 2 | 4,769 |
| `InferenceCancellationTest.kt` | 1 | 7,338 |
| `GgufRecoveryTest.kt` | 1 | 11,360 |
| `InferenceServiceProcessTestBase.kt` | 0 | 4,868 |

All eight original method names and complete test blocks are byte-identical to the baseline. Count before/after: **8 = 8**. There were no JUnit `@Before`/`@After` lifecycle hooks. The base class only shares the original binding type, four process helpers and four timeout constants; helper visibility changed from private to protected, constant names/values stayed identical.

The one >10 KB class is unavoidable under the 1:1 rule: the complete GGUF recovery method alone is 10,034 bytes. It was not split or modified.

Commits: `319ff84`, `3988702`, `f2d3947`, `2b6b5d2`, `b526616`.

Final Unit Tests run `43572193823` on code SHA `b526616ab54c7fa75ac552eef04502c3404650e8`: completed / success, including host-only native tests. Android APK Build run `43572193817` on the same SHA was still running at this update, before APK upload and emulator instrumentation.

Detailed method map, byte comparisons and class sizes: [InferenceServiceProcessTest split report](../REFACTORING_TESTS.md).

## Remaining refactoring work

- ModelImportScreen sections have been extracted; Unit Tests run `43567003341` on code SHA `5c6caea4c508a073ff245dd118bd3b1844066575` completed successfully. Android APK Build run `43567003311` for that SHA is in progress at this update; artifact and emulator conclusions are not yet claimed.
- Complete the APK build / emulator instrumentation and record the artifact IDs for `5c6caea`.
- Keep physical-device smoke testing separate from CI: Model Import success, picker cancellation and error handling still require a target Android device.
- The `InferenceServiceProcessTest` split is complete at code level; final Android APK/emulator verification is pending. `:app` package moves remain deferred.
- Use-case/repository extraction remains optional; do not add abstractions without a concrete reuse/ownership reason.

Update this document in place after each verified task/CI transition. It is the only live refactoring status tracker.
