#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace local_llm {

// The KV/token mirror is reusable only while every state-affecting input remains
// identical. Prompt token IDs are still compared separately to find the exact prefix.
struct PromptCacheIdentity {
    std::string model_signature;
    std::string prompt_signature;
    std::string sampler_signature;
    std::string context_signature;
};

inline bool prompt_cache_identity_matches(
        const PromptCacheIdentity& cached,
        const PromptCacheIdentity& requested) {
    return cached.model_signature == requested.model_signature &&
           cached.prompt_signature == requested.prompt_signature &&
           cached.sampler_signature == requested.sampler_signature &&
           cached.context_signature == requested.context_signature;
}

// Reject sentinel/out-of-range sampler results before vocabulary lookups or
// decode. This is intentionally independent of llama.cpp types for host tests.
template <typename Token>
inline bool is_valid_token_id(Token token, std::int64_t vocabulary_size) noexcept {
    return vocabulary_size > 0 &&
           token >= 0 &&
           static_cast<std::int64_t>(token) < vocabulary_size;
}

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

// Tokenize llama.cpp rendered chat-template parts without reinterpreting user content.
// Template-owned parts may contain control-token spellings; input parts must stay literal.
// This is shared by the JNI path and host fixtures so the special-token policy is testable.
template <typename Parts, typename Tokenize>
auto tokenize_rendered_prompt_parts(const Parts& parts, Tokenize&& tokenize)
        -> decltype(tokenize(std::declval<const std::string&>(), false)) {
    using TokenList = decltype(tokenize(std::declval<const std::string&>(), false));
    TokenList result;
    for (const auto& part : parts) {
        if (part.text.empty()) continue;
        auto part_tokens = tokenize(part.text, !part.is_input);
        result.insert(result.end(), part_tokens.begin(), part_tokens.end());
    }
    return result;
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


 // Return true only when every system message is in the leading prompt prefix.
// Context shifting can then preserve that immutable prefix with one boundary position.
inline bool system_messages_are_prefix_only(const std::vector<bool>& system_message_flags) noexcept {
    bool non_system_seen = false;
    for (const bool is_system : system_message_flags) {
        if (is_system && non_system_seen) return false;
        if (!is_system) non_system_seen = true;
    }
    return true;
}

// Hybrid/recurrent memory includes tensor-backed recurrent state in addition to
// sequence metadata. Those models require llama_memory_clear(..., true) so the
// attention and recurrent backing buffers are cleared together.
inline bool requires_full_memory_data_clear(bool is_hybrid, bool is_recurrent) noexcept {
    return is_hybrid || is_recurrent;
}

// Incremental KV/prefix reuse and context shifts are safe only for ordinary attention
// models. Hybrid/recurrent architectures must always clear their full memory state.
inline bool supports_incremental_memory_ops(bool is_hybrid, bool is_recurrent) noexcept {
    return !requires_full_memory_data_clear(is_hybrid, is_recurrent);
}

// The cached token vector mirrors the exact length of sequence-0 KV state.
 // A mismatch (including a negative native position) makes prefix reuse unsafe.
inline bool prompt_cache_state_is_consistent(
        std::size_t cached_token_count,
        std::int64_t current_position) {
    return current_position >= 0 &&
           static_cast<std::size_t>(current_position) == cached_token_count;
}

// The token mirror represents the complete logical sequence. Incremental reuse or
// shifting is safe only while native memory still contains that complete range.
// A non-zero minimum (for example, an SWA window that evicted old positions) cannot
// be treated as a complete reusable prefix.
inline bool prompt_cache_memory_positions_are_consistent(
        std::size_t cached_token_count,
        std::int64_t current_position,
        std::int64_t memory_position_min,
        std::int64_t memory_position_max) noexcept {
    if (!prompt_cache_state_is_consistent(cached_token_count, current_position)) {
        return false;
    }
    if (current_position == 0) {
        return memory_position_min == -1 && memory_position_max == -1;
    }
    return memory_position_min == 0 &&
           memory_position_max == current_position - 1;
}

struct PromptCacheContextShiftPlan {
    std::int64_t tokens_to_discard = 0;
    std::int64_t next_position = 0;
    std::int64_t next_stop_generation_position = 0;
};

// Keep the immutable system prefix, discard half of the remaining history, and
// shift the generation ceiling by exactly the same amount as the KV positions.
inline bool make_prompt_cache_context_shift_plan(
        std::size_t cached_token_count,
        std::int64_t current_position,
        std::int64_t system_prompt_position,
        std::int64_t stop_generation_position,
        PromptCacheContextShiftPlan& plan) noexcept {
    if (!prompt_cache_state_is_consistent(cached_token_count, current_position) ||
        system_prompt_position < 0 ||
        system_prompt_position > current_position ||
        stop_generation_position < 0) {
        return false;
    }

    const std::int64_t discard = (current_position - system_prompt_position) / 2;
    if (discard <= 0) return false;

    plan.tokens_to_discard = discard;
    plan.next_position = current_position - discard;
    plan.next_stop_generation_position =
        stop_generation_position > system_prompt_position
            ? std::max(system_prompt_position, stop_generation_position - discard)
            : stop_generation_position;
    return true;
}

} // namespace local_llm
