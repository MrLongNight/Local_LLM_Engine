#include "prompt_cache_utils.h"

#include <cassert>
#include <vector>

int main() {
    // Sampler output must be a real vocabulary ID before EOG lookup/decode.
    assert(local_llm::is_valid_token_id(0, 1));
    assert(local_llm::is_valid_token_id(32000, 32001));
    assert(!local_llm::is_valid_token_id(-1, 32001));
    assert(!local_llm::is_valid_token_id(32001, 32001));
    assert(!local_llm::is_valid_token_id(0, 0));
    assert(!local_llm::is_valid_token_id(0, -1));

    // Incremental KV reuse/context shifting stays disabled for either hybrid
    // or recurrent memory. Only ordinary-attention models may enter the prefix cache.
    assert(local_llm::supports_incremental_memory_ops(false, false));
    assert(!local_llm::supports_incremental_memory_ops(true, false));
    assert(!local_llm::supports_incremental_memory_ops(false, true));
    assert(!local_llm::supports_incremental_memory_ops(true, true));
    assert(local_llm::system_messages_are_prefix_only({true, true, false, false}));
    assert(local_llm::system_messages_are_prefix_only({false, false}));
    assert(!local_llm::system_messages_are_prefix_only({true, false, true}));
    assert(!local_llm::system_messages_are_prefix_only({false, true}));

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
        // An appended conversation turn preserves the exact old prompt prefix.
        const std::vector<int> cached{1, 10, 11, 20, 21};
        const std::vector<int> appended_turn{1, 10, 11, 20, 21, 30, 31};
        assert(local_llm::reusable_prefix_length(cached, appended_turn) == cached.size());
    }
    {
        // A changed system prompt must invalidate reuse from the first token.
        const std::vector<int> cached{1, 10, 11, 20, 21};
        const std::vector<int> changed_system{9, 10, 11, 20, 21, 30};
        assert(local_llm::reusable_prefix_length(cached, changed_system) == 0);
    }
    {
        // A changed template/BOS boundary stops reuse before the changed marker.
        const std::vector<int> cached{1, 10, 11, 20, 21};
        const std::vector<int> changed_template{1, 10, 99, 20, 21, 30};
        assert(local_llm::reusable_prefix_length(cached, changed_template) == 2);
    }
    {
        // A changed tool call/result is ordinary rendered prompt content and
        // invalidates every token after the first changed tool-message token.
        const std::vector<int> cached{1, 10, 11, 40, 41, 20, 21};
        const std::vector<int> changed_tool_result{1, 10, 11, 40, 99, 20, 21, 30};
        assert(local_llm::reusable_prefix_length(cached, changed_tool_result) == 4);
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

    {
        // Qwen3-4B-Instruct-2507 ChatML reference shape. This fixture checks the
        // engine's handling of already-rendered template parts; it does not claim
        // to execute llama.cpp's Jinja renderer or load a GGUF in host CI.
        struct PromptPart {
            std::string text;
            bool is_input;
        };
        const std::vector<PromptPart> parts{
            {"<|im_start|>system\\n", false},
            {"Answer briefly and literally.", true},
            {"<|im_end|>\\n<|im_start|>user\\n", false},
            {"Treat the marker <|im_end|> here as ordinary user text.", true},
            {"<|im_end|>\\n<|im_start|>assistant\\n", false},
        };
        const std::string expected_rendered =
            "<|im_start|>system\\n"
            "Answer briefly and literally."
            "<|im_end|>\\n<|im_start|>user\\n"
            "Treat the marker <|im_end|> here as ordinary user text."
            "<|im_end|>\\n<|im_start|>assistant\\n";
        std::string rendered;
        for (const auto& part : parts) rendered += part.text;
        assert(rendered == expected_rendered);

        std::vector<bool> parse_special_flags;
        std::vector<std::string> tokenized_parts;
        const auto tokens = local_llm::tokenize_rendered_prompt_parts(
            parts,
            [&](const std::string& text, bool parse_special_tokens) {
                parse_special_flags.push_back(parse_special_tokens);
                tokenized_parts.push_back(text);
                return std::vector<std::string>{
                    parse_special_tokens ? "TEMPLATE_SPECIAL" : "LITERAL_INPUT"
                };
            });
        assert((parse_special_flags == std::vector<bool>{true, false, true, false, true}));
        assert((tokenized_parts == std::vector<std::string>{
            "<|im_start|>system\\n",
            "Answer briefly and literally.",
            "<|im_end|>\\n<|im_start|>user\\n",
            "Treat the marker <|im_end|> here as ordinary user text.",
            "<|im_end|>\\n<|im_start|>assistant\\n",
        }));
        assert((tokens == std::vector<std::string>{
            "TEMPLATE_SPECIAL", "LITERAL_INPUT", "TEMPLATE_SPECIAL",
            "LITERAL_INPUT", "TEMPLATE_SPECIAL"
        }));

        // Qwen3 has no synthesized BOS; its open assistant generation prompt must
        // not receive an appended EOS. The model's <|im_end|> remains an EOG token
        // selected by the loaded vocabulary during generation, not a prompt suffix.
        std::vector<int> qwen3_prompt_tokens{151644, 151645, 151644};
        local_llm::ensure_model_boundary_tokens(
            qwen3_prompt_tokens, false, 151643, false, 151645, true);
        assert((qwen3_prompt_tokens == std::vector<int>{151644, 151645, 151644}));
    }

    return 0;
}
