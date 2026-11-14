# Agent and contributor rules — Local_LLM_Engine

## Repository invariants

- Work only on the existing `main` branch. Do not create feature branches.
- Before every write, re-read the current `main` HEAD and the blob SHAs of every affected file. Update the branch with an expected-HEAD lease; never overwrite a concurrent commit.
- Make small, focused commits. Read CI logs before correcting a failure; change only the evidenced cause.
- Keep the llama.cpp submodule pinned and pristine unless an explicit upstream update is requested. Do not patch upstream source files directly; Android compatibility changes belong in the checked build overlay.
- Preserve public Kotlin/Java APIs, AIDL and Parcelable layouts, manifest registrations, process names, JNI symbols, ABIs, CMake paths and source sets.
- Do not add dependencies, NPU backends, DI frameworks, speculative abstractions or performance-default changes without explicit approval.
- Correctness and reproducibility take priority over speed. Never claim device performance, GPU execution or production readiness without the corresponding physical-device evidence.
- Reuse the single `EngineViewModel` instance across demo screens. Do not create parallel engine/ViewModel instances for separate screens.
- Keep prompts and model responses out of persistent diagnostic logs.

## Build and verification rules

- No Gradle wrapper launcher scripts/JAR are checked in. CI uses Gradle 9.3.1 and JDK 21; Java source/target compatibility remains 17.
- Unit CI covers JVM tests and host-only C++ prompt-cache/truncation tests. APK CI builds the native app/test APKs and runs Android emulator process-isolation instrumentation.
- Record exact code SHA, workflow run ID, conclusion and artifact IDs. APK build, emulator instrumentation and real-device acceptance are separate results.
- SAF-provider behavior, Mali/Vulkan execution, actual GPU-layer offload, resource release and target-phone performance require target-device evidence.

## Documentation ownership

- `README.md` is the concise repository entry page; `docs/README.md` is the documentation index.
- Keep technical references in `docs/technical/`, active task trackers in `docs/active-todos/`, and broader plans/integration strategy in `docs/planning/`.
- `docs/active-todos/REFACTORING_STATUS.md` is the only live refactoring status register. Do not create a second status/roadmap document for the same work.
- `docs/planning/IMPLEMENTATION_PLAN.md` owns the overall engine implementation plan and dated technical history.
- `docs/planning/INTEGRATION_MYDOCANIZER.md` is an important downstream integration plan. Maintain it against the current public library API; do not remove it because the engine prototype is a separate repository.
- Update the canonical document in place. Do not duplicate content across README, changelog, planning and status documents; use links to the canonical location.
- Keep links relative to the final file location and update all inbound references when moving a document.
