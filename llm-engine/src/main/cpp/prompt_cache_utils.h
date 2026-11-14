#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
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
// add_generation_prompt means the sequence is still open: EOS must never be
// synthesized there. An EOS marker is appended only when explicitly closing a
// completed sequence. Template-owned BOS markers are preserved and deduplicated.
template <typename Token>
inline void ensure_model_boundary_tokens(
        std::vector<Token>& tokens,
        bool add_bos,
        Token bos_token,
        bool add_eos,
        Token eos_token,
        bool add_generation_prompt = false) {
    if (add_bos) {
        if (tokens.empty() || tokens.front() != bos_token) {
            tokens.insert(tokens.begin(), bos_token);
        }
        if (tokens.size() > 1) {
            const auto first_after_boundary = tokens.begin() + 1;
            tokens.erase(
                std::remove(first_after_boundary, tokens.end(), bos_token),
                tokens.end());
        }
    }

    if (add_eos && !add_generation_prompt) {
        if (tokens.empty() || tokens.back() != eos_token) {
            tokens.erase(std::remove(tokens.begin(), tokens.end(), eos_token), tokens.end());
            tokens.push_back(eos_token);
        } else if (tokens.size() > 1) {
            tokens.erase(
                std::remove(tokens.begin(), tokens.end() - 1, eos_token),
                tokens.end() - 1);
        }
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


 // Incremental KV/prefix reuse and context shifts are safe only for ordinary attention
// models. Hybrid/recurrent architectures must always clear their full memory state.
inline bool supports_incremental_memory_ops(bool is_hybrid, bool is_recurrent) noexcept {
    return !is_hybrid && !is_recurrent;
}

// The cached token vector mirrors the exact length of sequence-0 KV state.
 // A mismatch (including a negative native position) makes prefix reuse unsafe.
inline bool prompt_cache_state_is_consistent(
        std::size_t cached_token_count,
        std::int64_t current_position) {
    return current_position >= 0 &&
           static_cast<std::size_t>(current_position) == cached_token_count;
}

} // namespace local_llm
