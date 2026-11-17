# P0-01 Generation-path audit

**Audited revision:** `d64bb22b8affa873e0f69d4667dc8c83cb40552b`  
**Scope:** Source trace from Kotlin request through Binder/service, JNI prompt preparation, llama.cpp sampling, token-piece conversion and terminal result mapping.  
**Result:** The complete source path is now mapped. This audit does **not** identify a confirmed root cause for the reported model-output regressions; those remain open.

## End-to-end path

1. **Request construction (app/API).** `InferenceRequest` carries ordered role/content messages, max generated token count, optional temperature/top-p overrides, thinking flag and timeout policy. The app's chat view model passes the request to `LocalLlmEngine.streamChat()`.
2. **Engine stream entry.** `LocalLlmEngineImpl.streamChat()` creates a native run ID, maps message roles/content to parallel arrays, resets cancellation, and calls `NativeEngineHandle.nativeProcessChat()` on the engine's native lane. It then repeatedly calls `nativeGenerateNextToken()`.
3. **IPC/service.** `InferenceClient` assigns a request ID and sends `streamChat(request)` to `InferenceService`. The service rejects concurrent requests, collects engine events, forwards prompt/token/progress callbacks, and returns a completed result with separate IPC diagnostic and native run IDs. The client validates request IDs, tracks timeouts and maps callbacks back into the public Flow.
4. **Model template initialization.** During model load, native code creates `common_chat_templates_init(model, "")`; the empty override means template selection comes from the loaded GGUF/model metadata and llama.cpp's template resolver. Model architecture is separately read from `general.architecture` for metadata/architecture-specific policy.
5. **Message conversion and template rendering.** JNI converts Java UTF-16 role/content strings to UTF-8, builds `common_chat_msg` values, then attempts Jinja rendering with `add_generation_prompt=true` and the request's thinking flag. If Jinja throws, it tries llama.cpp's legacy renderer. If both fail and the model has an explicit template, the request fails; a plain `role: content\n` fallback is allowed only when no explicit template exists.
6. **Tokenization and boundaries.** For template output, each `common_chat_prompt.parts` segment is tokenized with template-owned special-token parsing enabled only for non-input parts; message/input text is treated literally. Without a template, the fallback prompt is tokenized as ordinary input. The native helper restores the model-required BOS once and does not append EOS to an open assistant generation prompt. Context overflow removes oldest unprotected history messages and re-renders/re-tokenizes; the system messages and latest user message are retained.
7. **Prompt evaluation and cache.** Native code validates the prompt token count, checks cached-token/KV position consistency, reuses only an exact ordinary-attention prefix, and fully clears hybrid/recurrent memory. Prompt tokens are decoded in micro-batches with cancellation checks; Vulkan work is synchronized before prefill completion.
8. **Sampler construction.** A fresh sampler is created per request. Temperature/top-p may be overridden by the request; top-k/min-p come from loaded defaults. Repeat/frequency/presence penalties are disabled. Temperature `<= 0` selects the greedy chain; otherwise top-k → top-p → min-p → temperature is configured.
9. **Token selection and decode.** Each native step samples from the final prompt/decode logits, captures optional numeric-only trace, validates the token ID against vocabulary bounds, accepts the token into sampler history, and checks EOG before decoding. EOG sets `STOP`; non-EOG tokens are decoded, synchronized, counted and added to KV/cache state.
10. **Detokenization and streaming.** `common_token_to_piece()` produces each token piece. Incremental UTF-8 normalization retains split multibyte suffixes; the JNI bridge returns valid UTF-8 strings. Kotlin appends visible pieces and forwards token events. A final native step flushes pending UTF-8 on cancellation/terminal paths.
11. **Finish/result.** Native finish reasons map to `STOP`, `MAX_TOKENS`, `CANCELLED` or `ERROR`. Blank visible output is promoted to an inference error rather than successful empty completion. Service/client preserve request correlation and forward the result to the caller.

## Source anchors at audited revision

- `LocalLlmEngineImpl.kt`: request setup and native call (around lines 366–540); token loop, UTF-8 finalization and finish mapping (around lines 576–720).
- `InferenceService.kt`: serialized service request and event forwarding (around lines 366–455).
- `InferenceClient.kt`: timeout policy and request lifecycle (around lines 850–950); token and prompt-progress callback handling (around lines 1180–1275).
- `NativeEngineHandle.kt`: JNI contract for model metadata, prompt processing, token generation, finish reason and token counters.
- `local_llm_engine_jni.cpp`: sampler configuration (around lines 128–158); template initialization during model load (around lines 1330–1360); request sampler setup (around lines 1520–1585); rendering/tokenization/boundary handling (around lines 1590–1800); cache/prefill (around lines 1810–1990); sampling, EOG, decode and detokenization (around lines 1990–2170).
- `prompt_cache_utils.h`: token-ID validation, prefix reuse, BOS/EOS boundary handling and prompt truncation policy.

## Findings and unresolved questions

- The source has a clear single path for template rendering, prompt tokenization, sampler construction, token selection and finish mapping. There is no second Kotlin-side visible-token cutoff.
- Explicit GGUF templates are not silently replaced by plain-text fallback when rendering fails; the request fails. This is intentional fail-closed behavior.
- Architecture detection currently reads `general.architecture`; Qwen3.5 GDN policy selection is handled separately. A model-family fixture must prove that the selected architecture/policy and the actual GGUF metadata agree.
- The 2026-11-14 LFM3 compact gate failed its expected-output check on **both** CPU and Vulkan. That points first to validating the fixture's prompt/expected-output contract and actual model response shape; it does not establish a Vulkan-only defect.
- The trace is source-based. It does not prove that every supported model's embedded chat template is correct, nor that runtime output is semantically correct. Those require per-model fixtures and correlated device logs.

## Next P0-01 work

1. Validate the correctness-gate prompt and expected sequence against actual supported model families; retain only fixtures that are unambiguous and independently verified.
2. Add deterministic fixture coverage for Qwen3, Qwen3.5 dense/MoE, Gemma 4 and ordinary attention.
3. Correlate CPU/Vulkan runs using the same GGUF, rendered-template metadata, token boundaries, sampler parameters, context and generation limit. Persist no prompt/response text in normal diagnostics.
4. Root-cause any mismatch before changing production defaults.
