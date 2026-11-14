# Refactoring Baseline

**Status: PARTIAL — STOP before structural refactoring**

This document records the verifiable baseline before any structure-only moves. It does not mark the full baseline green because the requested wrapper commands could not be executed as written and a clean root build is not covered by the available CI runs.

## Snapshot

- **Repository:** `MrLongNight/Local_LLM_Engine`
- **Branch:** `main` (already exists; no branch was created)
- **Baseline commit:** `68660746716655fe8135bed3500e2a16430054d1`
- **Snapshot date:** 2026-11-14 (UTC; commit timestamp 2026-11-14T03:46:21Z)
- **Scope:** documentation-only baseline; no application, Kotlin, Java, C++, Gradle, manifest, AIDL or native build code changed for this baseline.

## Requested commands and verified results

| Requested command | Result | Evidence / limitation |
|---|---|---|
| `./gradlew clean build` | **NOT EXECUTED — NOT VERIFIED** | The repository root does not contain a `gradlew` wrapper script. The available execution environment also has no `gradle` executable and no Android SDK/NDK. A local clone attempt failed because `github.com` could not be resolved. The successful CI APK task below is not a substitute for a clean root `build`. |
| `./gradlew test` | **NOT EXECUTED literally; CI-equivalent JVM test tasks GREEN** | The repository has no `gradlew`. GitHub Actions run [43553332153](https://github.com/MrLongNight/Local_LLM_Engine/actions/runs/43553332153), commit `68660746716655fe8135bed3500e2a16430054d1`, completed successfully. It ran `:llm-engine:testDebugUnitTest` and `:app:testDebugUnitTest`, followed by the host-only C++ prompt-cache/truncation test. |
| `./gradlew assembleDebug` | **NOT EXECUTED literally; CI task GREEN** | GitHub Actions run [43553332180](https://github.com/MrLongNight/Local_LLM_Engine/actions/runs/43553332180), same commit, completed successfully. It ran `:app:assembleDebug` and `:llm-engine:assembleDebugAndroidTest`; APK and instrumentation APK artifacts were uploaded. |

### Test count

- **JVM tests declared in source:** 132 methods annotated with JUnit `@Test` across `app/src/test` and `llm-engine/src/test/java` at the baseline commit.
- **JVM execution result:** both Gradle test tasks completed successfully in run `43553332153`. The job log does not print the aggregate executed-test count. JUnit XML reports were uploaded as artifact `unit-test-reports-417`; the artifact was not unpacked for this baseline, so 132 is the source-declared count, not a claim that 132 executions were independently counted from XML.
- **Android instrumentation:** 16 tests, 0 skipped, 0 failed, as shown by the emulator test step in run `43553332180`.
- **Native host test:** the C++ prompt-cache and truncation test executable completed successfully in run `43553332153`.

### Build result

- The Android Debug APK task succeeded in run `43553332180`; the APK and instrumentation APK were verified and uploaded.
- The available CI job did **not** run `clean build` or the root `build` lifecycle task. Therefore a successful clean root build is **not established** by this baseline.

## Lint and formatting rules relevant to moves

- No repository configuration or plugin for **Detekt**, **ktlint**, **Spotless** or **Checkstyle** was found.
- No custom Android Lint configuration file or custom `lint { ... }` / `lintOptions { ... }` block was found in the inspected Gradle files.
- Android Lint tasks are available through the Android Gradle Plugin, but the inspected CI workflows do not run a dedicated `lintDebug` task.
- `.pre-commit-config.yaml` contains only `check-yaml`, `check-toml` and `check-merge-conflict`; it does not enforce Kotlin/Java/C++ formatting or package/file layout.
- The `pre-commit.ci - push` status for the baseline commit is successful.

### Move-specific risks to preserve

- Keep Kotlin package declarations and public class/interface names unchanged when moving files; update imports and source-set paths only when required.
- Preserve AIDL/Parcelable declarations, Android manifest component names and process attributes.
- Preserve JNI-mapped class/package/method names and native registration assumptions.
- Preserve CMake relative paths, generated overlay paths, submodule locations, include directories and Android ABI filters.
- Re-run JVM tests, host C++ tests, Android Lint and the Debug APK/instrumentation build after each structural move batch.

## Baseline gate decision

**STOP — do not begin structural refactoring yet.** The existing CI JVM tests, instrumentation tests and Debug APK build are green, but the required `./gradlew clean build` and literal `./gradlew test` commands have not been run. The repository also lacks the Gradle Wrapper required by those exact commands. Re-open this gate only after the project has an agreed, reproducible Gradle invocation and a successful clean root build plus full test run are recorded.
