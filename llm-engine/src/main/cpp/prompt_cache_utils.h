#pragma once

#include <algorithm>
#include <cstddef>
#include <vector>

namespace local_llm {

// Exact token prefix shared by the last evaluated sequence and the new prompt.
template <typename Token>
inline std::size_t common_prefix_length(
        const std::vector<Token>& cached,
        const std::vector<Token>& prompt) {
    const std::size_t limit = std::min(cached.size(), prompt.size());
    std::size_t matched = 0;
    while (matched < limit && cached[matched] == prompt[matched]) {
        ++matched;
    }
    return matched;
}

// Reuse the longest prefix, but evaluate at least one prompt token to obtain logits.
template <typename Token>
inline std::size_t reusable_prefix_length(
        const std::vector<Token>& cached,
        const std::vector<Token>& prompt) {
    if (prompt.size() <= 1) return 0;
    return std::min(common_prefix_length(cached, prompt), prompt.size() - 1);
}

// Ensure model-required BOS/EOS markers exist exactly once at sequence boundaries.
// Template renderers may strip these markers before returning prompt parts, while
// tokenizing every part with add_special=false would otherwise lose them.
template <typename Token>
inline void ensure_model_boundary_tokens(
        std::vector<Token>& tokens,
        bool add_bos,
        Token bos_token,
        bool add_eos,
        Token eos_token) {
    if (add_bos && (tokens.empty() || tokens.front() != bos_token)) {
        tokens.insert(tokens.begin(), bos_token);
    }
    if (add_eos && (tokens.empty() || tokens.back() != eos_token)) {
        tokens.push_back(eos_token);
    }
}

// Return erase indices in execution order. Indices account for each preceding erase,
// so removing oldest history messages does not invalidate later indices. Protected
// messages (all system messages and the last user message) are never included.
inline std::vector<std::size_t> build_prompt_truncation_plan(
        std::size_t message_count,
        const std::vector<std::size_t>& protected_indices,
        std::size_t last_user_index) {
    std::vector<bool> keep(message_count, false);
    for (const std::size_t index : protected_indices) {
        if (index < message_count) keep[index] = true;
    }
    if (last_user_index < message_count) keep[last_user_index] = true;

    std::vector<std::size_t> plan;
    plan.reserve(message_count);
    std::size_t removed_before = 0;
    for (std::size_t index = 0; index < message_count; ++index) {
        if (keep[index]) continue;
        plan.push_back(index - removed_before);
        ++removed_before;
    }
    return plan;
}

} // namespace local_llm
