# Documentation index

Project documentation is grouped by purpose. Each topic has one canonical document; this index is navigation, not a second copy of the underlying content.

## Technical documentation

Located in [`technical/`](technical/):

- [`ARCHITECTURE.md`](technical/ARCHITECTURE.md) — canonical Mermaid diagrams for module ownership, process boundaries and model/inference flow.
- [`MODEL_COMPATIBILITY.md`](technical/MODEL_COMPATIBILITY.md) — architecture recognition, backend/operator coverage, Android overlay behavior, model test catalogue and device-acceptance criteria.
- [`CI_DEBUG_SIGNING.md`](technical/CI_DEBUG_SIGNING.md) — persistent signing setup for the prototype's side-by-side Debug APK and the temporary-key fallback.
- [`CHANGELOG.md`](technical/CHANGELOG.md) — implementation changes and verified historical milestones.

## Active todos

Located in [`active-todos/`](active-todos/):

- [`MASTER_TODO.md`](active-todos/MASTER_TODO.md) — the only active, priority-sorted implementation queue for migrating the engine into myDocAnizer-Mobile.

## Planning

Located in [`planning/`](planning/):

- [`IMPLEMENTATION_PLAN.md`](planning/IMPLEMENTATION_PLAN.md) — overall engine implementation plan, technical decisions and dated historical CI/device snapshots.
- [`INTEGRATION_MYDOCANIZER.md`](planning/INTEGRATION_MYDOCANIZER.md) — active migration contract for replacing the old inference runtime in myDocAnizer-Mobile with `:llm-engine`.

## Audits

- [`2026-11-17-myDocAnizer-migration-audit.md`](audits/2026-11-17-myDocAnizer-migration-audit.md) — individual review of all 89 previously open checklist items, app impact/agent effort, code leftovers and the revised device-test packages.

## Completed implementation history

- [`REFACTORING_COMPLETED.md`](history/REFACTORING_COMPLETED.md) — historical evidence for completed UI, Model Import and instrumentation test refactors.

## Refactoring analyses

- [`REFACTORING_MODEL_IMPORT.md`](REFACTORING_MODEL_IMPORT.md) — extraction record and verification for `ModelImportScreen`.
- [`REFACTORING_TESTS.md`](REFACTORING_TESTS.md) — method-to-class map and lifecycle analysis for `InferenceServiceProcessTest`.

## Repository rules

- [`AGENTS.md`](AGENTS.md) — contributor/agent rules and documentation ownership.
- [`../AGENTS.md`](../AGENTS.md) — short root entry point to the agent rules.
