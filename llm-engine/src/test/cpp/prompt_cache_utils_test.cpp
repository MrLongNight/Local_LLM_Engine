#include "prompt_cache_utils.h"

#include <cassert>
#include <vector>

int main() {
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
        std::vector<int> tokens{11, 22};
        local_llm::ensure_model_boundary_tokens(tokens, false, 1, false, 2);
        assert((tokens == std::vector<int>{11, 22}));
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
    return 0;
}
