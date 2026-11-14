# TODO — Model-aware llama.cpp Cache Strategy

**Type:** investigation and implementation plan  
**Status:** OPEN — no implementation in this task  
**Scope:** in-memory prompt-prefix reuse and model-specific recurrent/hybrid state reuse for the Kotlin/Android engine. No new dependency is planned.

## Current implementation findings

- `llm-engine/src/main/cpp/local_llm_engine_jni.cpp` keeps `EngineInstance::cached_tokens` as a mirror of the tokens in native memory sequence 0.
- For models that support the current incremental-memory path, `nativeProcessChat` computes the reusable common token prefix, removes the stale suffix with `llama_memory_seq_rm()`, and evaluates only the remaining prompt suffix.
- If prefix removal is unavailable or fails, the current implementation clears native memory with `llama_memory_clear()`.
- Model loading currently sets `supports_incremental_memory_ops` to false when `llama_model_is_hybrid(model)` or `llama_model_is_recurrent(model)` is true.
- The hybrid/recurrent path therefore clears memory for each new prompt. The current `shift_context()` path also refuses models without incremental memory operations.
- This is a safe fallback, not a complete cache strategy for hybrid/recurrent models. Do **not** remove the hybrid/recurrent guard as a shortcut.
- The existing cache is process-memory state. It is not a persistent on-disk prompt/KV cache.

## Work items

### 1. Audit supported memory/state APIs at the pinned llama.cpp revision

- [ ] Use the exact submodule revision pinned by the repository build; record the revision in the implementation notes.
- [ ] Trace the memory layout and supported operations for ordinary attention, sliding-window attention, recurrent/SSM state and convolution/short-convolution state.
- [ ] Verify how `llama_model_is_hybrid()` and `llama_model_is_recurrent()` classify every supported target model at that pinned revision.
- [ ] Specifically verify LFM3 and Qwen3.5 model architecture/state behavior from the pinned source; do not assume both use identical state semantics.
- [ ] Identify which public llama.cpp memory/state operations can safely preserve, copy, restore, truncate or shift each component.
- [ ] Record unsupported cases and retain a full-clear fallback wherever safe partial reuse cannot be proven.

### 2. Design a model-aware cache/session boundary

- [ ] Separate cache ownership by loaded model instance and conversation/session; never reuse one conversation's state for another.
- [ ] Define cache identity/invalidation inputs: model file identity and loaded adapters, tokenizer/vocabulary, fully rendered chat-template tokens, BOS/EOS handling, context settings and any model-specific state parameters.
- [ ] Preserve the exact token sequence and native memory-state correspondence. A token-prefix match alone is insufficient when the model also has recurrent or convolution state.
- [ ] Define behavior for prompt edits before the cached suffix, system-prompt changes, tool messages, message truncation, context shifts and sampler/session resets.
- [ ] Keep cache policy internal to the engine; do not change public API/AIDL/Parcelable contracts without a separate compatibility plan.

### 3. Implement safe cache tiers

- [ ] Keep the existing longest-common-token-prefix path for architectures where native sequence removal is supported and verified.
- [ ] Add hybrid/recurrent state reuse only for architectures where all state components can be preserved and restored consistently (attention KV, recurrent/SSM state, convolution state and sliding-window state as applicable).
- [ ] Prefer documented/public llama.cpp APIs from the pinned revision; no speculative assumptions about internal tensor layouts or backend-specific pointers.
- [ ] If a model/backend cannot safely restore a matching state, invalidate it and use the current full-clear/re-prefill path.
- [ ] Keep context shifting consistent across token mirrors and every state component; ensure generation limits and position counters remain correct after a shift.
- [ ] Ensure CPU and Vulkan use the same correctness contract. Handle asynchronous Vulkan work and synchronization before snapshot/copy/restore operations where the API requires it.

### 4. Bound memory and define eviction

- [ ] Measure cache-state memory by model, session and context length; account for KV plus non-KV hybrid state.
- [ ] Add a bounded per-engine/session cache budget and deterministic eviction policy (for example, least-recently-used state only after proving ownership and restore correctness).
- [ ] Integrate the existing memory-pressure guard so cache growth cannot cause OOM or destabilize inference.
- [ ] Invalidate cache entries on model unload/reload, native process restart, failed inference, cancelled prefill/decode or any state-integrity uncertainty.
- [ ] Do not add persistent disk serialization until in-memory state restore is correct and its format/versioning/security requirements are understood.

### 5. Add observable cache diagnostics

- [ ] Log cache eligibility and a bounded reason code for hit, partial hit, miss, fallback and invalidation.
- [ ] Record prompt token count, matched/reused prefix tokens, tokens actually re-evaluated, cache-state size, model architecture, backend and context position.
- [ ] Separate prefix-match time, native state operation time, prefill time and Vulkan synchronization time.
- [ ] Ensure each metric is correlated with both the native inference run ID and the IPC/RAM diagnostic request ID.
- [ ] Avoid per-token or unbounded logging in performance-sensitive paths.

### 6. Add correctness tests before performance tuning

- [ ] Repeated identical prompt in one session reuses state and produces the same result as a cache-disabled reference.
- [ ] Appending a new user turn reuses only the valid common prefix.
- [ ] Editing/deleting an earlier user or assistant turn invalidates the affected suffix.
- [ ] Changing the system prompt, chat template, model, adapter or tokenizer invalidates incompatible state.
- [ ] Tool-call/tool-result messages retain correct ordering and invalidate the proper suffix.
- [ ] Cancellation or failure during prefill/decode cannot leave a partially valid cache advertised as reusable.
- [ ] Context overflow and context shifting preserve state and do not exceed the requested generation limit.
- [ ] Hybrid/recurrent tests cover attention plus recurrent/convolution state, including repeated prompts, prefix extensions and prompt rewrites.
- [ ] Model reload, process death/reconnect and memory-pressure eviction always fall back safely.
- [ ] Run cache-on/cache-off correctness comparisons for every supported target architecture before enabling a cache policy by default.

### 7. Benchmark only after correctness passes

- [ ] Compare cold prompt vs repeated identical prompt vs append-only conversation turns.
- [ ] Measure prompt tokens, reused tokens, evaluated tokens, prefill latency, TTFT, decode tokens/s, state-operation overhead and peak memory.
- [ ] Run CPU and Vulkan separately on the same model, prompt, context and generation settings.
- [ ] Include an uncached baseline and repeat runs to detect thermal/cache-order effects.
- [ ] Report improvements only when measured on-device and repeatable; do not infer decode speed gains from prefill cache hits.

## Acceptance criteria

- No stale or cross-session state reuse; cache-on and cache-off outputs pass the same correctness gates.
- All supported model architectures either have verified state reuse or an explicit safe full-clear fallback.
- Hybrid/recurrent cache reuse is enabled only after every relevant state component is covered by tests.
- No regression in cancellation, timeouts, context shifting, model reload, process isolation or memory-pressure behavior.
- Cache metrics distinguish real reuse from a requested-but-fallback path.
- CPU and Vulkan correctness passes; performance claims are backed by repeated on-device measurements.
- No new dependency is introduced for this work.
