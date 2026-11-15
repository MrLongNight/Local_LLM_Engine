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
| Gemma 4 assistant | `gemma4-assistant`; `src/models/gemma4-assistant.cpp` | Specialized assistant graph/shared-memory path; do not assume it is interchangeable with the ordinary Gemma 4 text architecture. | **Not verified** |

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
6. Export diagnostics without prompts/responses. Record load time, prompt-token count, prefill time, time-to-first-token, decode tokens/s, peak process PSS, available RAM and thermal status.
7. Repeat on the actual target nubia Z2462N / Mali-G57. Emulator results are not a substitute.

## Qwen3.5 chat-template and GDN policy

`InferenceRequest.enableThinking` is passed to llama.cpp's Jinja and legacy chat-template inputs and defaults to `false`. Keep the default unless a model-specific test proves otherwise; do not strip reasoning tokens as a substitute for correct template configuration.

Qwen3.5/Qwen3.5-MoE have an architecture-aware `GdnPolicy` with four AR/CH fusion configurations. The current default uses the conservative unfused Qwen3.5 path. The Android Vulkan overlay routes `GGML_OP_GATED_DELTA_NET` to CPU regardless of Vulkan selection. The four-way policy matrix remains useful for correctness testing, but it must not be described as Vulkan GDN execution.

## Performance reporting rules

- Report prefill and autoregressive decode separately.
- Compare CPU and Vulkan on identical GGUF bytes, prompt, context, batch, micro-batch, sampler and output-token count.
- Keep the Vulkan per-operation profiler disabled for throughput acceptance; timestamp queries and readback add overhead.
- Repeat AUTO between experimental kernel profiles to expose thermal/frequency drift.
- Do not promote experimental workgroup, MMVQ/DMMV, Flash Attention, GDN fusion or quantization paths to defaults without correctness gates and repeatable target-device measurements.

## Pinned-source update constraint

The Android CMake overlay targets llama.cpp revision `a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5`. Upstream commit `ccd360f060a0f17b87ee0d4c5d91ee84cfdff484` (2026-11-11) removes the non-fused GDN graph builders and `fused_gdn_ar` / `fused_gdn_ch` context parameters. That change invalidates the current overlay anchors and prevents the existing four-way fusion matrix from running unchanged. Do not move the submodule pointer alone: first design and validate a replacement correctness strategy, update the checked overlay anchors, then run the CPU/Vulkan acceptance matrix.
