#include "prompt_cache_utils.h"

#include <cassert>
#include <vector>

int main() {
    // Incremental KV reuse/context shifting stays disabled for either hybrid
    // or recurrent memory. Only ordinary-attention models may enter the prefix cache.
    assert(local_llm::supports_incremental_memory_ops(false, false));
    assert(!local_llm::supports_incremental_memory_ops(true, false));
    assert(!local_llm::supports_incremental_memory_ops(false, true));
    assert(!local_llm::supports_incremental_memory_ops(true, true));

    {
        const std::vector<int> cached{1, 2, 3, 4, 5};
        const std::vector<int> prompt{1, 2, 3, 9, 10};
        assert(local_llm::common_prefix_length(cached, prompt) == 3);
        assert(local_llm::reusable_prefix_length(cached, prompt) == 3);
    }
    {
        const std::vector<int> cached{1, 2, 3};
        const std::vector<int> prompt{1, 2, 3};
        // A fully matching prompt still re-evaluates its final token for logits.
        assert(local_llm::reusable_prefix_length(cached, prompt) == 2);
    }
    {
        const std::vector<int> cached{1, 2, 3};
        const std::vector<int> prompt{8, 9, 10};
        assert(local_llm::reusable_prefix_length(cached, prompt) == 0);
    }
    {
        const std::vector<int> cached{1, 2};
        const std::vector<int> prompt{1};
        assert(local_llm::reusable_prefix_length(cached, prompt) == 0);
    }
    {
        std::vector<int> tokens{11, 22};
        local_llm::ensure_model_boundary_tokens(tokens, true, 1, true, 2);
        assert((tokens == std::vector<int>{1, 11, 22, 2}));
    }
    {
        // Explicit template boundaries must not be duplicated.
        std::vector<int> tokens{1, 11, 22, 2};
        local_llm::ensure_model_boundary_tokens(tokens, true, 1, true, 2);
        assert((tokens == std::vector<int>{1, 11, 22, 2}));
    }
    {
        // Qwen: the model does not request BOS or EOS; preserve template text.
        std::vector<int> tokens{11, 22};
        local_llm::ensure_model_boundary_tokens(tokens, false, 1, false, 2, true);
        assert((tokens == std::vector<int>{11, 22}));
    }
    {
        // LFM3: BOS is already emitted by the template; do not duplicate it.
        std::vector<int> tokens{1, 11, 22};
        local_llm::ensure_model_boundary_tokens(tokens, true, 1, false, 2, true);
        assert((tokens == std::vector<int>{1, 11, 22}));
    }
    {
        // Llama-3: duplicate template BOS fragments collapse to one leading BOS.
        std::vector<int> tokens{1, 11, 1, 22};
        local_llm::ensure_model_boundary_tokens(tokens, true, 1, false, 2, true);
        assert((tokens == std::vector<int>{1, 11, 22}));
    }
    {
        // Gemma: add a required BOS when it is absent from template token parts.
        std::vector<int> tokens{11, 22};
        local_llm::ensure_model_boundary_tokens(tokens, true, 1, false, 2, true);
        assert((tokens == std::vector<int>{1, 11, 22}));
    }
    {
        // add_bos=true with no BOS in the template adds it exactly once.
        std::vector<int> tokens{11, 22};
        local_llm::ensure_model_boundary_tokens(tokens, true, 1, false, 2, true);
        assert((tokens == std::vector<int>{1, 11, 22}));
    }
    {
        // A generation prompt remains open: add_eos=true must not append EOS.
        std::vector<int> tokens{1, 11, 22};
        local_llm::ensure_model_boundary_tokens(tokens, true, 1, true, 2, true);
        assert((tokens == std::vector<int>{1, 11, 22}));
    }
    {
        // A completed sequence gets one EOS, even if an earlier fragment had one.
        std::vector<int> tokens{1, 11, 2, 22};
        local_llm::ensure_model_boundary_tokens(tokens, true, 1, true, 2, false);
        assert((tokens == std::vector<int>{1, 11, 22, 2}));
    }
    {
        // An already-closed sequence is not given a second EOS.
        std::vector<int> tokens{1, 11, 22, 2};
        local_llm::ensure_model_boundary_tokens(tokens, true, 1, true, 2, false);
        assert((tokens == std::vector<int>{1, 11, 22, 2}));
    }
    {
        // system, old user/assistant turns, newest user
        const auto plan = local_llm::build_prompt_truncation_plan(
            6, std::vector<std::size_t>{0}, 5);
        // Erases the oldest non-system message each time; system and final user survive.
        assert((plan == std::vector<std::size_t>{1, 1, 1, 1}));
    }
    {
        const auto plan = local_llm::build_prompt_truncation_plan(
            2, std::vector<std::size_t>{0}, 1);
        assert(plan.empty());
    }
    {
        assert(local_llm::prompt_cache_state_is_consistent(0, 0));
        assert(local_llm::prompt_cache_state_is_consistent(4, 4));
        assert(!local_llm::prompt_cache_state_is_consistent(3, 4));
        assert(!local_llm::prompt_cache_state_is_consistent(5, 4));
        assert(!local_llm::prompt_cache_state_is_consistent(0, -1));
    }
    return 0;
}
