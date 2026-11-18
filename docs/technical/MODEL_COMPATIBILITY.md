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

## Standard paired device functional and performance smoke test

Use the app's **Settings → Kurzer CPU/Vulkan-Funktionstest → CPU + Vulkan prüfen** after changes that may affect inference correctness, prompt/template handling, token output, backend selection, memory behavior or native execution. CPU and Vulkan are **both mandatory**. This built-in test is the repeatable short regression baseline between TODO tasks; it does not replace deeper D1a/D2 acceptance runs.

### Fixed model and conversation

- **Model:** Qwen3-4B-Instruct-2507, architecture `qwen3`, quantization `Q4_K_M`. Load the exact same unchanged GGUF before every comparison. Reference file: [DhruvalLabs/Qwen3-4B-Instruct-2507-GGUF / Qwen3-4B-Instruct-2507-Q4_K_M.gguf](https://huggingface.co/DhruvalLabs/Qwen3-4B-Instruct-2507-GGUF).
- Start with the model loaded and no inference running. The test itself creates a fresh single-message conversation for each backend profile, reloads the same model file, and restores the original model configuration afterwards.
- The built-in test uses the fixed prompt and expected output below. Do not replace it with previous chat history or a different prompt when collecting the regression baseline.

### Fixed paired parameters

The app's `InferenceConfigSmokePlan` enforces the same effective values for both profiles:

| Parameter | CPU run | Vulkan run |
|---|---:|---:|
| Requested backend | CPU | Vulkan |
| GPU layers | 0 | 99 (backend clamps to device/model limit) |
| Context | 256 | 256 |
| Batch | 128 | 128 |
| Micro-batch | 64 | 64 |
| Threads | min(6, available CPU cores) | same |
| Batch threads | same as threads | same |
| Temperature | 0 / greedy | 0 / greedy |
| Top-K | 20 | 20 |
| Top-P | 1.0 | 1.0 |
| Min-P | 0.05 | 0.05 |
| Maximum generated tokens | 96 | 96 |
| Vulkan workgroup / Flash Attention / GDN | AUTO / AUTO / architecture default | same |

The only intentional profile difference is backend preference and GPU-layer offload. The test temporarily overrides the user's current context/batch/thread/sampler and experimental-kernel settings for comparability, then restores the original model configuration. The underlying user configuration is not mutated.

### Fixed prompt and exact expected output

The app sends this exact user prompt independently to CPU and Vulkan:

~~~text
Berechne 8 * 24 - 37. Gib genau zwei Zeilen aus: In Zeile 1 ERGEBNIS=<berechnetes Ergebnis>. In Zeile 2 FOLGE=1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32. Keine Einleitung, keine Erklärung und kein weiterer Text.
~~~

Expected output after normalizing CRLF/CR to LF and trimming only leading/trailing whitespace:

~~~text
ERGEBNIS=155
FOLGE=1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32
~~~

Interior whitespace or any other text is a mismatch. The 96-token ceiling is intentionally above the expected sequence length so the test must end naturally with `STOP`, not `MAX_TOKENS`.

### Pass criteria — check each backend independently

1. The exact model file is Qwen3-4B-Instruct-2507 / `qwen3` / Q4_K_M. Record filename, actual byte count and SHA-256. Both runs must use the same identity. A missing SHA (`UNAVAILABLE`) permits a one-off functional check but **does not establish a comparable baseline**.
2. Model load completes, effective context is 256, finish reason is `STOP`, and `OUTPUT_GATE=PASS`. The trimmed output must match the two lines above exactly. `MAX_TOKENS`, cancel, error, partial output or any text mismatch fails that backend run.
3. **CPU run:** diagnostics must prove CPU was the actual execution backend and GPU offload was zero. A requested CPU mode alone is insufficient.
4. **Vulkan run:** diagnostics must prove actual Vulkan node/layer execution and record actual GPU layers plus the run-correlated graph/backend distribution. A registered Vulkan device or requested Vulkan mode alone is insufficient. CPU nodes are allowed only when the graph/operator policy explicitly assigns them to CPU; record their count and reasons.
5. A silent CPU fallback during the Vulkan run is a failure, not a successful Vulkan test. A silent Vulkan assignment during the CPU run is likewise a failure.
6. The paired CPU/Vulkan correctness gate must pass, including identical measured top-1 token choices and the configured top-5 overlap threshold. A passing text output alone does not waive a failed numeric comparison gate.

### Comparison record and repeat cadence

Save a diagnostic export for each comparison and record the CPU/Vulkan native Run-IDs together as one pair. For each backend capture:

- model-load time and effective context;
- prompt-token count, prefill time and time-to-first-token;
- generated-token count, decode tokens/s and total generation time;
- actual CPU/Vulkan graph distribution and GPU-layer count;
- process PSS, available system RAM, memory-guard decisions and Android thermal status.

The initial D0 baseline should run the built-in paired test three times; calculate the median CPU and Vulkan values separately. Between TODO tasks, run one paired comparison after any change that can affect inference, model loading, CPU/Vulkan selection, prompt handling, memory or native execution. Treat a single-pair throughput delta as a warning only; repeat three pairs under comparable thermal and memory conditions before calling it a performance regression or improvement. The current built-in flow runs CPU first and Vulkan second; keep the order consistent when comparing repeated baselines.

The paired result is the engine's CPU/Vulkan regression baseline. Keep functional pass/fail separate from performance interpretation: a faster run with wrong output, changed model bytes or incorrect backend selection is still a failure. Do not use the short smoke result as final model-performance acceptance; D2 remains the dedicated longer benchmark.

The 256-token context is intentional: the 18.11.2026 device report showed the Memory Guard reducing a requested 2048-token context to 256 under the then-current memory pressure. Fixing the effective context at 256 keeps this regression test within the observed device budget; the separate D1a/D2 runs retain their own explicitly matched context settings.

## Performance reporting rules

- Report prefill and autoregressive decode separately.
- Compare CPU and Vulkan on identical GGUF bytes, prompt, context, batch, micro-batch, sampler and output-token count.
- Keep the Vulkan per-operation profiler disabled for throughput acceptance; timestamp queries and readback add overhead.
- Repeat AUTO between experimental kernel profiles to expose thermal/frequency drift.
- Do not promote experimental workgroup, MMVQ/DMMV, Flash Attention, GDN fusion or quantization paths to defaults without correctness gates and repeatable target-device measurements.

## Pinned-source update constraint

The Android CMake overlay targets llama.cpp revision `a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5`. Upstream commit `ccd360f060a0f17b87ee0d4c5d91ee84cfdff484` (2026-11-11) removes the non-fused GDN graph builders and `fused_gdn_ar` / `fused_gdn_ch` context parameters. That change invalidates the current overlay anchors and prevents the existing four-way fusion matrix from running unchanged. Do not move the submodule pointer alone: first design and validate a replacement correctness strategy, update the checked overlay anchors, then run the CPU/Vulkan acceptance matrix.
