# Documentation index

Project documentation is grouped by purpose. Each topic has one canonical document; this index is navigation, not a second copy of the underlying content.

## Technical documentation

Located in [`technical/`](technical/):

- [`MODEL_COMPATIBILITY.md`](technical/MODEL_COMPATIBILITY.md) — architecture recognition, backend/operator coverage, Android overlay behavior, model test catalogue and device-acceptance criteria.
- [`CI_DEBUG_SIGNING.md`](technical/CI_DEBUG_SIGNING.md) — persistent signing setup for the prototype's side-by-side Debug APK and the temporary-key fallback.
- [`CHANGELOG.md`](technical/CHANGELOG.md) — implementation changes and verified historical milestones.

## Active todos

Located in [`active-todos/`](active-todos/):

- [`REFACTORING_STATUS.md`](active-todos/REFACTORING_STATUS.md) — the live status of the current UI/ViewModel refactoring, including exact code SHA and CI evidence.
- [`TODO_CACHE_STRATEGY.md`](active-todos/TODO_CACHE_STRATEGY.md) — open investigation and implementation work for safe model-aware prompt/state caching.

## Planning

Located in [`planning/`](planning/):

- [`IMPLEMENTATION_PLAN.md`](planning/IMPLEMENTATION_PLAN.md) — overall engine implementation plan, technical decisions and dated historical CI/device snapshots.
- [`INTEGRATION_MYDOCANIZER.md`](planning/INTEGRATION_MYDOCANIZER.md) — downstream integration plan for embedding `:llm-engine` in myDocAnizer-Mobile. This is a key planned consumer integration, not a statement that this repository is myDocAnizer-Mobile.

## Repository rules

- [`AGENTS.md`](AGENTS.md) — contributor/agent rules and documentation ownership.
- [`../AGENTS.md`](../AGENTS.md) — short root entry point to the agent rules.
