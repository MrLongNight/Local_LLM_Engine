# P0-06 — Model memory and prompt-cache audit

**Pinned upstream:** llama.cpp `a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5` (the repository's pinned submodule). This is a source audit, not a claim of physical-device cache equivalence.

## 1. Upstream architecture classification

At this revision, `llama_model_is_hybrid()` and `llama_model_is_recurrent()` in `src/llama-model.cpp` delegate to `llm_arch_is_hybrid()` and `llm_arch_is_recurrent()` in `src/llama-arch.cpp`.

- **Hybrid architectures:** the upstream hybrid switch explicitly includes `LLM_ARCH_LFM3`, `LLM_ARCH_LFM2`, `LLM_ARCH_LFM2MOE`, `LLM_ARCH_QWEN35` and `LLM_ARCH_QWEN35MOE` (among others). The current engine therefore treats LFM3 and Qwen3.5 dense/MoE as hybrid, disables incremental prefix reuse/context shifting and clears memory before each new prompt.
- **Recurrent/SSM architectures:** upstream's recurrent switch includes Mamba, Mamba2, RWKV6, RWKV6QWEN2, RWKV7 and ARWKV7. The engine applies the same full-clear policy.
- **Ordinary attention:** architectures not flagged hybrid/recurrent may use token-prefix reuse, but only after the cached-token count/current-position invariant passes. The engine removes the old KV suffix with `llama_memory_seq_rm()`; if that partial removal is refused, it clears the entire memory and evaluates from zero.
- **Sliding-window attention:** the pinned public API documents `llama_memory_seq_pos_min()` as typically non-zero for SWA caches. The `llama_kv_cache_iswa` implementation combines base and SWA caches; its `seq_rm()` applies removal to both, and its `get_can_shift()` requires both caches to support shifting and to have equal sizes. The engine does not assume shifting is safe: it checks `llama_memory_can_shift()` before `seq_add()`. A failed prefix removal falls back to a full clear.
- **Hybrid attention + recurrent/convolution state:** `llama_memory_hybrid` and `llama_memory_hybrid_iswa` combine attention KV with recurrent memory. Their `clear()` methods clear both components. Their `seq_rm()` first asks recurrent memory to roll back; it can return false when a partial recurrent rollback is unsupported. The recurrent implementation explicitly notes that Mamba/RWKV state cannot generally be erased partially at the end of a sequence. This is why LFM3 and Qwen3.5 must stay on the full-clear path unless upstream exposes and the engine proves a complete state rollback contract.

### Architecture caveat: Gemma 4 and other SWA-only models

The pinned `llm_arch_is_hybrid()` / `llm_arch_is_recurrent()` switches do not list the ordinary `gemma4` architecture as hybrid/recurrent. It is therefore eligible for the ordinary KV path, but the actual GGUF architecture and memory backend must be confirmed from `LOCAL_LLM_PROMPT_METADATA` and the runtime memory diagnostics for each tested model. SWA support is governed by the memory backend's `seq_rm` and `get_can_shift` results, not by assuming every attention cache is a simple full-context KV cache.

## 2. Cache identity and invalidation contract

The current native engine has one loaded model/context per `EngineInstance` and no adapter/LoRA selection API.

| Identity component | Current protection |
|---|---|
| Model file / architecture / vocabulary | Loading a model resets long-term state and creates a new model/context. The cache is not carried across model loads. |
| Adapters | No adapter mutation API exists in the current public engine contract. If adapters are added, adapter identity must become part of cache identity or force a full clear/reload. |
| Tokenizer / vocabulary | Bound to the loaded model; model reload resets the cache. |
| Rendered chat template / role boundaries / tool messages | The complete request is rendered and tokenized; exact token IDs determine the reusable common prefix. A changed tool message or role marker breaks the prefix at the first changed token. |
| BOS/EOS and generation prompt | Boundary tokens are normalized before the token sequence is compared; template-owned boundaries are preserved and deduplicated. |
| System prompt | Included in the rendered prompt token sequence. The initial contiguous system prefix is separately measured for context-shift protection. |
| Context size and other load-time parameters | Applied when the model/context is loaded; changing them requires a model reload, which resets memory and cached tokens. |
| Conversation/session | The request supplies the full message history. Appended turns can reuse the exact prior prefix; edits/deletions/branch changes stop reuse at the first token divergence. Identical token prefixes are semantically equivalent input, even if they originate from different UI sessions. |

### Remaining edge case: non-leading system messages

Prompt truncation protects every message whose role is `system`, but the context-shift boundary is currently computed only from the contiguous leading system-message prefix. If a caller supplies a later/interleaved system message, prompt truncation preserves it but a future context shift could discard it. The app's normal conversation shape places system messages first, but the engine should either disable context shifting for non-leading system messages or preserve all protected ranges explicitly. Keep this as an open P0-06 item until a code guard and host test land.

## 3. Failure and lifecycle invalidation

- Model load/unload resets the long-term token mirror and clears/frees the memory context.
- Prefill cancellation and prefill/decode errors clear llama memory, `cached_tokens` and `current_position`.
- Decode cancellation/error also clears the memory and resets the cached-token mirror.
- A process restart creates a new `EngineInstance`; no KV state is persisted across process death.
- Memory-pressure unload uses the normal engine unload path; the service then releases its retained model descriptor.
- Before prefix reuse and context shifting, the engine requires `cached_tokens.size() == current_position`. A mismatch clears the memory and disables reuse for that request.

## 4. What is not yet proven

1. The source-level safety policy does not prove cache-on/cache-off semantic equivalence on hardware.
2. Sliding-window and hybrid architecture behavior still needs device runs with the exact GGUF architecture, `modelHybrid`/`modelRecurrent` flags, memory capabilities, and prefix-cache plan recorded.
3. Appended turns, tool-message edits, system-prompt changes, context shifts, cancellation/failure, model reload, process restart and memory-pressure eviction need a focused regression matrix.
4. The non-leading-system-message edge case above must be guarded before claiming complete context-shift safety.

**Conclusion:** Keep incremental reuse limited to ordinary attention/SWA memory where llama.cpp confirms partial sequence removal; keep LFM3, Qwen3.5, recurrent/SSM and any uncertain state layout on full-clear. Do not benchmark cache reuse until output equivalence is demonstrated.
