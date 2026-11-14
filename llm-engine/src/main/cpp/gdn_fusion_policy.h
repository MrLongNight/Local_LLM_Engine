#pragma once

namespace local_llm {

// Returns bit 0 for fused autoregressive GDN and bit 1 for fused chunked GDN.
// Keep this policy independent of JNI so architecture routing can be host-tested.
inline int gdn_fusion_flags_for_arch(int policy, bool is_qwen35_arch) noexcept {
    // GdnPolicy.nativeId: 0=architecture default, 1=both fused, 2=both unfused,
    // 3=AR fused/CH unfused, 4=AR unfused/CH fused.
    switch (policy) {
        case 1: return 3;
        case 2: return 0;
        case 3: return 1;
        case 4: return 2;
        case 0:
        default: return is_qwen35_arch ? 0 : 3;
    }
}

} // namespace local_llm
