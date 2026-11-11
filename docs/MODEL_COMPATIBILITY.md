# Model compatibility catalogue

**Pinned upstream:** `ggml-org/llama.cpp` commit
`a4d4f2fd4ec85ec6a0a9ece4870416c1b6cc24f5`.

This catalogue records source-level compatibility only. Three independent gates must not be conflated:

1. **Architecture recognized:** GGUF architecture name is registered and the model factory constructs a graph.
2. **Backend operator coverage:** CPU/Vulkan has an implementation and reports support for the graph operators used by that model. A source-level operator implementation is not proof that every tensor shape, quantization, or graph split is supported.
3. **Android device verified:** the exact GGUF loads and generates output on the target phone, with the requested backend and layer offload confirmed in diagnostics. Only this gate justifies a device-compatibility claim.

## Current catalogue

| Model family / example | Upstream architecture registration | Graph implementation | CPU / Vulkan source coverage | Mali-G57 device status |
|---|---|---|---|---|
| Qwen3-4B Instruct Q4_K_M | `qwen3` | `src/models/qwen3.cpp` | General GGML CPU and Vulkan paths; exact quantization/kernel path still requires measurement | Existing user baseline; retest required after current engine changes |
| Qwen3.5 dense (e.g. Qwen3.5-4B Q4_K_M) | `qwen35` | `src/models/qwen35.cpp` | Hybrid/recurrent graph is present; `GGML_OP_GATED_DELTA_NET` has CPU implementation and Vulkan dispatch/support checks | **Not verified** |
| Qwen3.5 MoE | `qwen35moe` | `src/models/qwen35moe.cpp` | Same gated-delta operator family; MoE tensor routing and memory footprint need separate validation | **Not verified** |
| Gemma 4 text | `gemma4` | `src/models/gemma4.cpp` | Architecture and graph are present; exact graph/tensor support and offload split need validation | **Not verified** |
| Gemma 4 assistant | `gemma4-assistant` | `src/models/gemma4-assistant.cpp` | Specialized assistant graph / shared memory path; do not treat as ordinary Gemma 4 text model | **Not verified** |

The architecture names above are registered in `src/llama-arch.cpp`; the model factory dispatch is in `src/llama-model.cpp`. The pinned source includes dedicated Qwen3.5 and Gemma 4 graph implementations. Qwen3.5 uses hybrid attention/recurrent memory handling; the upstream model loader contains explicit architecture-specific recurrent-layer and KV-memory handling. The GGML CPU backend dispatches `GGML_OP_GATED_DELTA_NET` to its CPU implementation, while the Vulkan backend contains a corresponding implementation and operator-support checks.

These facts establish that the pinned upstream source knows these architectures and has relevant backend code. They do **not** establish that the Android build's selected shader variants, the Mali-G57 driver, the selected quantization, or every model-specific graph shape will run successfully or efficiently.

## Required acceptance for a new model

Before adding a model to the device-verified column:

1. Record the exact GGUF filename, byte size, SHA-256, architecture metadata, quantization, context request and model source.
2. Load it with explicit CPU and explicit Vulkan requests. A Vulkan request must fail visibly rather than silently pass as CPU.
3. Confirm active backend, actual GPU-layer count, model-load result and native backend diagnostics. Requested GPU layers are not evidence of actual offload.
4. Run a short deterministic chat with the model's own GGUF chat template. Verify that system and user roles are retained, generation terminates correctly and no template fallback corrupts the prompt.
5. Run at least one longer prompt to exercise prefill and hybrid/recurrent state. Check cancellation, follow-up chat and unload/reload.
6. Export diagnostics with prompt/response contents excluded. Record prefill tokens/s, time to first token, decode tokens/s, model-load time, peak process PSS, available system RAM and thermal status.
7. Repeat on the target nubia Z2462N / Mali-G57. Emulator success is not a substitute.

## Qwen3.5 chat-template policy

The engine already passes the request's `enableThinking` flag through JNI to llama.cpp's Jinja and legacy chat-template inputs. `InferenceRequest.enableThinking` defaults to `false`. Keep this default for ordinary chat unless a model-specific test demonstrates that its template ignores the flag or requires a different explicit setting. Do not strip reasoning tokens from generated text as a substitute for correct template configuration.

## Performance reporting rules

- Report prefill and autoregressive decode separately.
- Keep CPU and Vulkan comparisons on the same GGUF bytes, prompt, context, batch, micro-batch, sampler and output-token count.
- Repeat AUTO between kernel profiles to expose thermal/frequency drift.
- Do not promote an experimental workgroup, MMVQ/DMMV, Flash Attention or quantization path to the default without repeated target-device measurements.
- A benchmark run under the Debug Vulkan operation profiler is diagnostic only; use profiler-disabled builds for throughput acceptance.
