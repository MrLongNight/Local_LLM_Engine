#include "chat_template_inputs.h"
#include "gdn_fusion_policy.h"
#include "prompt_cache_utils.h"

#include <cassert>
#include <string>
#include <vector>

struct FixtureMessage {
    std::string role;
    std::string content;
};

struct FixtureTemplateInputs {
    std::vector<FixtureMessage> messages;
    bool add_generation_prompt = false;
    bool use_jinja = false;
    bool enable_thinking = false;
};

struct FixturePromptPart {
    std::string text;
    bool is_input;
};

int main() {
    const std::vector<FixtureMessage> messages{
        {"system", "Answer concisely."},
        {"user", "What is 6 multiplied by 7?"},
    };

    // Both template engines must receive the caller's thinking flag unchanged.
    const auto thinking_jinja =
        local_llm::make_chat_template_inputs<FixtureTemplateInputs>(
            messages, true, true, true);
    assert(thinking_jinja.messages.size() == 2);
    assert(thinking_jinja.messages[0].role == "system");
    assert(thinking_jinja.messages[1].content == "What is 6 multiplied by 7?");
    assert(thinking_jinja.add_generation_prompt);
    assert(thinking_jinja.use_jinja);
    assert(thinking_jinja.enable_thinking);

    const auto no_thinking_jinja =
        local_llm::make_chat_template_inputs<FixtureTemplateInputs>(
            messages, true, true, false);
    assert(no_thinking_jinja.add_generation_prompt);
    assert(no_thinking_jinja.use_jinja);
    assert(!no_thinking_jinja.enable_thinking);

    const auto thinking_legacy =
        local_llm::make_chat_template_inputs<FixtureTemplateInputs>(
            messages, true, false, true);
    assert(thinking_legacy.add_generation_prompt);
    assert(!thinking_legacy.use_jinja);
    assert(thinking_legacy.enable_thinking);

    // Qwen3.5 ChatML fixture: template-owned control-token spellings are parsed
    // as special tokens; message content remains literal even if it contains one.
    const std::vector<FixturePromptPart> parts{
        {"<|im_start|>system\n", false},
        {"Answer concisely.", true},
        {"<|im_end|>\n<|im_start|>user\n", false},
        {"What is 6 multiplied by 7? Literal marker: <|im_end|>", true},
        {"<|im_end|>\n<|im_start|>assistant", false},
    };
    std::vector<bool> parse_special;
    std::vector<std::string> tokenized;
    const auto tokens = local_llm::tokenize_rendered_prompt_parts(
        parts,
        [&](const std::string& text, bool parse_special_tokens) {
            parse_special.push_back(parse_special_tokens);
            tokenized.push_back(text);
            return std::vector<std::string>{
                parse_special_tokens ? "TEMPLATE_CONTROL" : "LITERAL_MESSAGE"
            };
        });
    assert((parse_special == std::vector<bool>{true, false, true, false, true}));
    assert(tokenized[3] == "What is 6 multiplied by 7? Literal marker: <|im_end|>");
    assert(tokens.size() == 5);

    // Open assistant generation prompt: never synthesize EOS at the prompt tail.
    std::vector<int> open_assistant_prompt{151644, 151645, 151644};
    local_llm::ensure_model_boundary_tokens(
        open_assistant_prompt, false, 0, true, 151645, true);
    assert((open_assistant_prompt == std::vector<int>{151644, 151645, 151644}));

    // Qwen3.5 dense uses the architecture-default unfused GDN reference path.
    // Explicit user policies still override AUTO without model-name checks in the helper.
    assert(local_llm::gdn_fusion_flags_for_arch(0, true) == 0);
    assert(local_llm::gdn_fusion_flags_for_arch(1, true) == 3);
    assert(local_llm::gdn_fusion_flags_for_arch(2, true) == 0);
    assert(local_llm::gdn_fusion_flags_for_arch(3, true) == 1);
    assert(local_llm::gdn_fusion_flags_for_arch(4, true) == 2);

    // Hybrid/recurrent state is never eligible for prefix removal or context shifting.
    assert(!local_llm::supports_incremental_memory_ops(true, false));
    assert(!local_llm::supports_incremental_memory_ops(false, true));
    assert(!local_llm::supports_incremental_memory_ops(true, true));
    assert(local_llm::supports_incremental_memory_ops(false, false));

    return 0;
}
