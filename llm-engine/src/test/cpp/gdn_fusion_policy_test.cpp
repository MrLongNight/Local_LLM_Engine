#include "gdn_fusion_policy.h"

#include <cassert>

int main() {
    // AUTO is architecture-specific: Qwen3.5 AR+CH remain unfused while
    // other architectures retain the upstream fused defaults.
    assert(local_llm::gdn_fusion_flags_for_arch(0, true) == 0);
    assert(local_llm::gdn_fusion_flags_for_arch(0, false) == 3);

    // Explicit settings override architecture defaults.
    assert(local_llm::gdn_fusion_flags_for_arch(1, true) == 3);
    assert(local_llm::gdn_fusion_flags_for_arch(1, false) == 3);
    assert(local_llm::gdn_fusion_flags_for_arch(2, true) == 0);
    assert(local_llm::gdn_fusion_flags_for_arch(2, false) == 0);
    assert(local_llm::gdn_fusion_flags_for_arch(3, true) == 1);
    assert(local_llm::gdn_fusion_flags_for_arch(3, false) == 1);
    assert(local_llm::gdn_fusion_flags_for_arch(4, true) == 2);
    assert(local_llm::gdn_fusion_flags_for_arch(4, false) == 2);

    // Unknown policy IDs safely use the architecture default.
    assert(local_llm::gdn_fusion_flags_for_arch(-1, true) == 0);
    assert(local_llm::gdn_fusion_flags_for_arch(99, false) == 3);
    return 0;
}
