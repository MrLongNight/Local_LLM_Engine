#include "model_architecture_policy.h"

#include <cassert>

int main() {
    // Ordinary Gemma 4 is a standalone text architecture.
    assert(!local_llm::requires_target_model_context("gemma4"));

    // Gemma 4 assistant is a distinct draft-head graph requiring ctx_other.
    assert(local_llm::requires_target_model_context("gemma4-assistant"));

    // Architecture matching is exact: do not collapse names by prefix or case.
    assert(!local_llm::requires_target_model_context("gemma4-assistant-extra"));
    assert(!local_llm::requires_target_model_context("Gemma4-assistant"));
    return 0;
}
