#pragma once

namespace local_llm {

// Construct llama.cpp chat-template inputs through one shared path so the
// caller's thinking choice is never silently dropped between Jinja/legacy modes.
template <typename Inputs, typename Messages>
Inputs make_chat_template_inputs(
        const Messages& messages,
        bool add_generation_prompt,
        bool use_jinja,
        bool enable_thinking) {
    Inputs inputs;
    inputs.messages = messages;
    inputs.add_generation_prompt = add_generation_prompt;
    inputs.use_jinja = use_jinja;
    inputs.enable_thinking = enable_thinking;
    return inputs;
}

} // namespace local_llm
