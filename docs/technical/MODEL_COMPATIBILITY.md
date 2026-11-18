# Model compatibility catalogue

**Pinned upstream:** `ggml-org/llama.cpp` commit `a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5`.

This catalogue separates three independent compatibility gates:

1. **Architecture recognized:** GGUF architecture is registered and the model factory can construct its graph.
2. **Backend coverage in this Android build:** required operators, tensor types and graph shapes have a usable implementation in the configured backend or an explicit CPU fallback.
3. **Device verified:** the exact GGUF loads and generates correct output on the target phone, with actual backend/layer assignment confirmed by runtime diagnostics.

Do not promote a model from source-recognized to device-verified based only on an upstream registration or a successful emulator build.

## Model-load memory estimate

The current `ModelMemoryGuard` estimates:

`model file bytes + n_ctx × max(128 KiB, model bytes / 10,000) + 256 MiB runtime headroom`.

The hard refusal boundary is 85% of total system RAM. The guard reduces context, down to 256 tokens, while the estimate exceeds that boundary. It can further reduce context when available RAM is lower; available RAM and Android's low-memory flag are pressure signals rather than the sole hard boundary. A one-load user acknowledgement can override the hard-limit refusal. The values below are approximate estimates at `n_ctx=2048`, using the published decimal file sizes; they are not measured peak RAM or guaranteed fit values.

| Model | HF GGUF repository / file | Published size | Guard estimate at ctx 2048 | Hybrid / recurrent state |
|---|---|---:|---:|---|
| Qwen3-4B-Instruct-2507 Q4_K_M | [DhruvalLabs/Qwen3-4B-Instruct-2507-GGUF](https://huggingface.co/DhruvalLabs/Qwen3-4B-Instruct-2507-GGUF) · `Qwen3-4B-Instruct-2507-Q4_K_M.gguf` | 2.33 GB | ≈2.86 GiB | No |
| Qwen3.5-4B Q4_K_M | [bartowski/Qwen_Qwen3.5-4B-GGUF](https://huggingface.co/bartowski/Qwen_Qwen3.5-4B-GGUF) · `Qwen3.5-4B-Q4_K_M.gguf` | 3.01 GB | ≈3.63 GiB | Yes |
| Qwen3.5-4B Q5_K_M | same repository · `Qwen3.5-4B-Q5_K_M.gguf` | 3.44 GB | ≈4.11 GiB | Yes |
| Qwen3.5-4B Q6_K | same repository · `Qwen3.5-4B-Q6_K.gguf` | 3.81 GB | ≈4.53 GiB | Yes |
| Gemma 4 E2B-it Q4_K_M | [Jessylg27/gemma-4-E2B-it-GGUF](https://huggingface.co/Jessylg27/gemma-4-E2B-it-GGUF) · `gemma-4-E2B-it-Q4_K_M.gguf` | 3.43 GB | ≈4.10 GiB | No Gated Delta Net |
| Gemma 4 E4B-it Q4_K_M | [lmstudio-community/gemma-4-E4B-it-GGUF](https://huggingface.co/lmstudio-community/gemma-4-E4B-it-GGUF) · `gemma-4-E4B-it-Q4_K_M.gguf` | 5.34 GB | ≈6.24 GiB | No Gated Delta Net |

The estimates are admission-policy calculations only. They do not account precisely for allocator fragmentation, driver allocations, graphics memory pressure, other running apps or every architecture-specific state tensor.

## Current source/backend catalogue

| Model family | Upstream architecture / graph | Android CPU/Vulkan build behavior | Mali-G57 device status |
|---|---|---|---|
| Qwen3-4B Instruct Q4_K_M | `qwen3`; `src/models/qwen3.cpp` | General GGML CPU and Vulkan paths. Exact Q4_K kernel selection and graph split still require runtime evidence. | Existing user baseline; retest against current engine code is required. |
| Qwen3.5 dense | `qwen35`; `src/models/qwen35.cpp`; hybrid attention/recurrent state | CPU GDN path is available. The Android Vulkan `supports_op` overlay explicitly rejects `GGML_OP_GATED_DELTA_NET`, routing that operator to CPU. Other eligible graph operations may still run on Vulkan. | **Not verified** |
| Qwen3.5 MoE | `qwen35moe`; `src/models/qwen35moe.cpp` | Same explicit CPU fallback for GDN; MoE tensor routing and memory use require separate validation. | **Not verified** |
| Gemma 4 text | `gemma4`; `src/models/gemma4.cpp` | Architecture and graph are present upstream; exact tensor/operator coverage and Android offload split remain to be validated. | **Not verified** |
| Gemma 4 assistant | `gemma4-assistant`; `src/models/gemma4-assistant.cpp` | Specialized assistant draft graph. Upstream requires `ctx_other`, a paired target-model context and shared KV state; the graph reads the target model's token embeddings and follows its Flash Attention setting. This single-model chat engine rejects standalone loads early with native result 107; paired speculative decoding is not implemented here. | **Not supported as standalone chat / not device verified** |

The architecture names are registered in upstream `src/llama-arch.cpp`; model factory dispatch is in `src/llama-model.cpp`. Upstream Vulkan implementation presence is not equivalent to support in this Android build: the CMake overlay can deliberately alter `supports_op` policy, as it currently does for GDN.

## Runtime CPU-fallback evidence

The Android build overlays the pinned `llama-context.cpp` in the build directory and retains the upstream submodule unchanged. For each distinct inference graph layout, the scheduler callback records `LOCAL_LLM_GRAPH_BACKEND_DISTRIBUTION` with the run ID, graph-split count and node counts assigned to `CPU`, `Vulkan*`, other backends or no backend.

`cpuNodes > 0` proves that the sampled graph contains CPU-assigned operations even when Vulkan offload is requested. `graphSplits` alone does not identify backend ownership. A missing distribution row is not evidence that no CPU fallback occurred.

Qwen3.5 also exports `LOCAL_LLM_QWEN35_GDN_POLICY`, including the Android policy `vulkanGdn=cpu-fallback`. This fallback is a correctness policy, not a measured performance optimization.

## Acceptance checklist for a new model

Before marking an exact GGUF device-verified:

1. Record exact filename, bytes, SHA-256, architecture metadata, quantization, source and context request.
2. Load with explicit CPU and explicit Vulkan. A Vulkan request must fail visibly rather than silently succeed as CPU.
3. Confirm actual loaded backend, actual GPU layer count, model-load result, native backend diagnostics and per-run graph distribution.
4. Exercise the GGUF's own chat template with a deterministic short chat. Confirm roles, stop behavior and output correctness.
5. Run a longer prompt to exercise prefill and hybrid/recurrent state; test cancellation, a follow-up chat, unload and reload.
6. Export diagnostics with generated assistant responses correlated to their inference run. Debug diagnostic builds also include bounded input messages, rendered prompts, prompt token IDs and sampled token traces; Release builds omit raw text. Explicit test runs include test input and expected/actual output. Record load time, prompt-token count, prefill time, time-to-first-token, decode tokens/s, peak process PSS, available RAM and thermal status.
7. Repeat on the actual target nubia Z2462N / Mali-G57. Emulator results are not a substitute.

## Qwen3.5 chat-template and GDN policy

`InferenceRequest.enableThinking` is passed to llama.cpp's Jinja and legacy chat-template inputs and defaults to `false`. Keep the default unless a model-specific test proves otherwise; do not strip reasoning tokens as a substitute for correct template configuration.

Qwen3.5/Qwen3.5-MoE have an architecture-aware `GdnPolicy` with four AR/CH fusion configurations. The current default uses the conservative unfused Qwen3.5 path. The Android Vulkan overlay routes `GGML_OP_GATED_DELTA_NET` to CPU regardless of Vulkan selection. The four-way policy matrix remains useful for correctness testing, but it must not be described as Vulkan GDN execution.

## Mali-G57 Q4_K batch-1 Vulkan dispatch (pinned source audit)

**Audited code:** Android overlay at `llm-engine/src/main/cpp/cmake/LocalLlmAndroidVulkan.cmake`; pristine submodule `ggml-org/llama.cpp` commit `a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5`, source `ggml/src/ggml-vulkan/ggml-vulkan.cpp`. The overlay creates a build-directory copy of `ggml-vulkan.cpp`; it does not patch the submodule.

### Dispatch path

- The upstream `ggml_vk_mul_mat` routes eligible single-output-row / vector-shaped matrix operations into `ggml_vk_mul_mat_vec_q_f16` (pinned source around lines 7470–7488). The Q4_K selector audited here only changes the **batch-1 decode** branch (`ne11 == 1)); prefill (`ne11 > 1`) is separately instrumented and is not covered by this AUTO override.
- In upstream `ggml_vk_mul_mat_vec_q_f16`, MMVQ/Q8_1 is initially eligible only if the device reports `integer_dot_product`, `src1` is F32 and contiguous, the vector is not non-contiguous, `(ne11 * ne10) % 4 == 0), output precision allows Q8_1 (unless inputs are swapped), and `ggml_vk_should_use_mmvq(...)` accepts the shape/vendor heuristic (around lines 6790–6815). The upstream default branch of that heuristic returns true for unlisted vendors; it is not a Mali performance measurement.
- The checked overlay recognizes Mali by Vulkan vendor ID `0x13B5`. For Q4_K, batch-1 and AUTO (override 0), it deliberately sets `quantize_y = false`. This is the selected **BASE_TYPE_MMV / base DMMV** path, including when integer-dot exists and Q8_1 prerequisites are otherwise met. The runtime reason is `mali_auto_base_measured_faster`; if integer-dot is absent it reports `mali_auto_base_without_intDot`, and if the Q8_1 input/layout/precision requirements fail it reports `mali_auto_base_q8_1_ineligible`.
- Therefore the expected Mali-G57 AUTO decode path is **base DMMV, not MMVQ integer-dot/Q8_1**. The explicit diagnostic override 7 can request MMVQ only when integer-dot, F32 contiguous input, layout/alignment and precision prerequisites pass. It bypasses the upstream shape heuristic, not the hardware/input prerequisites.
- The overlay first looks up the Q4_K×Q8_1 DMMV pipeline. If that lookup returns null, upstream's existing fallback looks up the ordinary activation-type pipeline and clears `quantize_y`; the overlay records `q8_1_pipeline_unavailable_fallback` before emitting the selected-path trace. The checked CMake guards verify selector → Q8_1 lookup → fallback → runtime-log ordering and ensure the selector remains inside the exact upstream decode function.
- The upstream shader path for the base Q4_K vector kernel is `ggml/src/ggml-vulkan/vulkan-shaders/mul_mat_vec_q4_k.comp`. This is source-level implementation evidence, not proof that a particular device compiled, selected or executed it.

### What the existing measurement does and does not prove

The overlay comment records repeated runtime-verified Mali-G57 measurements for **LFM3 1.7B Q4_K_M**: base DMMV 4.14/3.92 tok/s versus MMVQ 2.71/2.80 tok/s (about 40–48% slower for MMVQ). That is the stated reason for the Mali AUTO override. It is not a Qwen3-4B measurement and must not be generalized as a measured speedup for the current Qwen3-4B Q4_K_M baseline. Re-run both paths with the same model bytes, prompt, context, batch/micro-batch and sampler before making a Qwen3-specific performance claim.

**No source change or performance-default change was made in this audit.** The actual target-device dispatch still needs confirmation from the run-correlated `LOCAL_LLM_VULKAN_Q4_K_DECODE_PATH` trace on the nubia Mali-G57. A CI build or the presence of Vulkan shaders does not prove GPU execution. The separate P0-04b task must validate the missing-Q8_1-pipeline fallback with automated tests; this audit does not claim that fallback behavior is fully tested.

## Standard short device functional check

Use this single, deterministic smoke test after changes that affect inference, prompt/template handling, token output, backend selection or native runtime behavior. It is a **functional regression check**, not a throughput benchmark and not a substitute for D1a/D2.

### Fixed model and configuration

- **Model:** Qwen3-4B-Instruct-2507, architecture `qwen3`, quantization `Q4_K_M`. Use the same unchanged GGUF file for every repeat. The catalogue entry above links the reference GGUF source; do not silently switch to Qwen3.5, Qwen3-VL or a different community conversion.
- **Conversation:** start a new/empty chat for every run. Keep the engine's default German system message (`Du bist ein hilfreicher, präziser KI-Assistent.`) and do not include previous user/assistant messages.
- **Backend:** request Vulkan; request 99 GPU layers so the backend can clamp to the model/device limit. A CPU selection is a failure for this test, not a successful fallback.
- **Memory-conscious fixed shape:** context 256, batch 128, micro-batch 64, threads 2, batch threads 2. Keep `enableThinking=false`.
- **Sampling:** temperature 0 / greedy; retain top-K 20, top-P 1.0 and min-P 0.05 if the UI requires explicit values. Limit the answer to 16 generated tokens. Do not change sampler values between repeated runs.
- Record the actual model file size and SHA-256. A direct SAF descriptor that reports SHA as `UNAVAILABLE` can still provide a one-off functional smoke result, but it is **not** an identity-verified or comparable baseline. D1a and D2 require verified identical model bytes.

### Fixed prompt

Send this exact text as the only user message:

~~~text
Ein Lager hat 8 Kisten mit je 24 Flaschen. 37 Flaschen werden entnommen. Wie viele Flaschen bleiben? Antworte ausschließlich im Format ERGEBNIS=Zahl.
~~~

Expected output after trimming leading/trailing whitespace:

`ERGEBNIS=155`

### Pass criteria

1. The GGUF identity is Qwen3-4B-Instruct-2507 / `qwen3` / Q4_K_M; record actual bytes and SHA status.
2. Model load completes and diagnostics identify Vulkan as the **actual** active backend. Record actual GPU layers and the run-correlated graph/backend distribution; a requested Vulkan backend or registered Vulkan device alone is insufficient.
3. The trimmed response is exactly `ERGEBNIS=155`, the finish reason is `STOP` and `OUTPUT_GATE=PASS`. `MAX_TOKENS`, cancel, error, missing output or CPU execution fails the smoke test.
4. Save the diagnostic export and its run ID. Do not compare tokens/s from this short prompt; use the dedicated D2 performance scenario for that.

The 256-token context is intentional: the 18.11.2026 report showed the Memory Guard reducing a requested 2048-token context to 256 under the then-current available-memory pressure. The smoke test fixes the effective context at 256 so ordinary memory variation does not change the test shape.

## Performance reporting rules

- Report prefill and autoregressive decode separately.
- Compare CPU and Vulkan on identical GGUF bytes, prompt, context, batch, micro-batch, sampler and output-token count.
- Keep the Vulkan per-operation profiler disabled for throughput acceptance; timestamp queries and readback add overhead.
- Repeat AUTO between experimental kernel profiles to expose thermal/frequency drift.
- Do not promote experimental workgroup, MMVQ/DMMV, Flash Attention, GDN fusion or quantization paths to defaults without correctness gates and repeatable target-device measurements.

## Pinned-source update constraint

The Android CMake overlay targets llama.cpp revision `a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5`. Upstream commit `ccd360f060a0f17b87ee0d4c5d91ee84cfdff484` (2026-11-11) removes the non-fused GDN graph builders and `fused_gdn_ar` / `fused_gdn_ch` context parameters. That change invalidates the current overlay anchors and prevents the existing four-way fusion matrix from running unchanged. Do not move the submodule pointer alone: first design and validate a replacement correctness strategy, update the checked overlay anchors, then run the CPU/Vulkan acceptance matrix.
