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
- Debug APKs enable full text diagnostics by default (override with `-PlocalLlmSyntheticTokenDiagnostics=false`). This diagnostic mode persists bounded, run-correlated input messages, rendered prompts, prompt token IDs, sampled token pieces, sampler parameters and generated assistant responses in app-private logs and exports. These traces can contain private conversation/model content. Release builds must keep text diagnostics disabled. Do not add an independent release/distribution checklist to this engine repository.

## Project structure and change discipline

- Inspect the existing module/package tree and the closest related implementation before editing. Decide the final file, package and module for the change before writing code.
- Place every new class, function, composable, test and helper in the package/module that owns that responsibility. Follow the existing conventions documented in [`technical/ARCHITECTURE.md`](technical/ARCHITECTURE.md).
- Prefer a small, cohesive extension of the existing owner over moving code around. Check for an existing implementation/helper first; reuse it instead of creating a parallel path.
- Avoid refactoring as a side effect of feature work. Do not rename, relocate, split, merge, broadly reformat or reorganize unrelated code merely because it could be cleaner.
- Do not create temporary implementations in a convenient file and plan to move them later. Start in the correct final location.
- Create a new class/file only when it has a clear, cohesive responsibility or is required by an existing package/module boundary. Do not split files just to reduce line count; do not allow one file/class to accumulate unrelated responsibilities.
- Preserve the dependency direction: `:app` may depend on `:llm-engine`; the reusable library must never depend on the app or its UI/DMS types. Keep public contracts separate from `internal` implementation details.
- Current work scope: improve, correct, optimize and harden the reusable `:llm-engine` itself. The prototype `:app` may be used only as a test harness; its demo UI is not a development target. Do not analyse or plan integration with another repository, including `myDocAnizer_Mobile`, until the user explicitly gives GO.
- Keep tests beside the responsibility they verify, using the matching `test` or `androidTest` source set. Do not duplicate existing test helpers, fake services or fixtures.
- Keep each task's code diff limited to the requested behavior and necessary tests. If a broader refactor is genuinely unavoidable, explain why and isolate it into a separate, explicitly tracked commit.

## Agent session and context rules

- The only active work queue is [`active-todos/MASTER_TODO.md`](active-todos/MASTER_TODO.md). Each checkbox there is an independently verifiable outcome, not automatically a separate agent/device session, build or test run. Closely related checkboxes may be completed together when shared setup and batched tests preserve each checkbox's evidence. A section heading is only a grouping, never a request to execute every checkbox in that section at once.
- One agent session should solve one coherent outcome and normally take no more than about 1–4 hours. It may close several closely related checkboxes together when they share code, setup, target device or a compatible test matrix; keep evidence and completion state separate per checkbox. If diagnosis reveals multiple independent causes or the required work exceeds that size, stop before broad implementation, add separately scoped checkboxes to the Master TODO, then execute only the related scope that fits the session.
- At session start, read the root `AGENTS.md`, this file and the Master TODO. Then read only the technical documents and source files directly relevant to the selected checkbox.
- Do **not** load `docs/archive/` by default. Archived audits, plans and refactoring reports are historical records only, not current specifications, task lists or a substitute for checking current source. Open an archived item only when the selected task explicitly needs historical evidence.
- Do not create session plans, parallel roadmaps, scratch task registers or additional active checklists. Update the selected checkbox and, when needed, split follow-up tasks in the Master TODO itself.

## Mermaid architecture documentation

- [`technical/ARCHITECTURE.md`](technical/ARCHITECTURE.md) is the single canonical, version-controlled Mermaid architecture document. Do not create competing diagrams in README, archived notes or status files.
- Review the Mermaid diagrams whenever a code change affects modules, package ownership, component responsibility, dependency direction, public API, model import/persistence flow, AIDL/Binder communication, process lifecycle, JNI/native flow or backend selection.
- If architecture or control flow changes, update the affected Mermaid diagram(s) and source-layout table in the same commit as the code change. Never knowingly leave the diagrams stale.
- If a change does not alter the architecture or control flow, keep the diagrams unchanged; do not add decorative or duplicate diagrams.

## Build and verification rules

- No Gradle wrapper launcher scripts/JAR are checked in. CI uses Gradle 9.3.1 and JDK 21; Java source/target compatibility remains 17.
- Unit CI covers JVM tests and host-only C++ prompt-cache/truncation tests. APK CI builds the native app/test APKs and runs Android emulator process-isolation instrumentation.
- Record exact code SHA, workflow run ID, conclusion and artifact IDs. APK build, emulator instrumentation and real-device acceptance are separate results.
- SAF-provider behavior, Mali/Vulkan execution, actual GPU-layer offload, resource release and target-phone performance require target-device evidence.

## Documentation ownership

- `README.md` is the concise repository entry page; `docs/README.md` is the documentation index.
- Keep technical references in `docs/technical/`, the sole active task register in `docs/active-todos/MASTER_TODO.md`, and historical material in `docs/archive/`.
- `docs/active-todos/MASTER_TODO.md` is the only active task register for the entire repository. Sort every new measure by priority, merge duplicates, and do not create another active TODO, roadmap, planning order, status or cache checklist.
- Do not maintain a separate implementation plan or parallel execution sequence. Every current and future task belongs in the Master TODO, in its appropriate priority section.
- `docs/archive/` contains completed refactoring records and historical audits only. Archived documents are not task sources; any still-relevant follow-up must be expressed once in the Master TODO.
- Do not create or maintain a separate consumer-integration plan. Analysis or planning for integration with another repository, including `myDocAnizer_Mobile`, remains deferred until the user explicitly gives GO.
- Update the canonical document in place. Do not duplicate content across README, changelog, archive and status documents; use links to the canonical location.
- Keep links relative to the final file location and update all inbound references when moving a document.
