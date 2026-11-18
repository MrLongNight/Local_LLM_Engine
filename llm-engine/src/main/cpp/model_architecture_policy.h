#pragma once

#include <string_view>

namespace local_llm {

// The pinned Gemma 4 assistant graph is a speculative draft head, not a
// standalone text model. It requires llama_context_params::ctx_other to point
// at the target model context whose token embeddings and KV state it shares.
inline bool requires_target_model_context(std::string_view architecture) noexcept {
    return architecture == "gemma4-assistant";
}

}  // namespace local_llm
