#pragma once

#include <cstdint>

namespace local_llm {

// Normal text and byte-fallback entries are renderable. Control, unknown and
// unused vocabulary entries are forbidden; all other non-renderable attrs fail closed.
inline bool is_renderable_output_token(
        uint32_t attributes,
        uint32_t renderable_mask,
        uint32_t forbidden_mask) {
    return (attributes & forbidden_mask) == 0 &&
           (attributes & renderable_mask) != 0;
}

} // namespace local_llm
