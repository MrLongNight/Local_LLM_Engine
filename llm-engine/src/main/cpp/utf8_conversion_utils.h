#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace local_llm {

inline void append_utf8_code_point(std::string& output, uint32_t code_point) {
    if (code_point <= 0x7F) {
        output.push_back(static_cast<char>(code_point));
    } else if (code_point <= 0x7FF) {
        output.push_back(static_cast<char>(0xC0 | (code_point >> 6)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
    } else if (code_point <= 0xFFFF) {
        output.push_back(static_cast<char>(0xE0 | (code_point >> 12)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
    } else {
        output.push_back(static_cast<char>(0xF0 | (code_point >> 18)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3F)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3F)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3F)));
    }
}

// Convert UTF-16 code units to standard UTF-8. Valid surrogate pairs become one
// supplementary code point; unpaired surrogates are replaced with U+FFFD.
// The result is length-aware and preserves embedded U+0000 bytes.
template <typename CodeUnit>
inline std::string utf16_to_utf8(const CodeUnit* data, std::size_t length) {
    static_assert(sizeof(CodeUnit) == sizeof(uint16_t), "UTF-16 code units must be 16 bits");
    std::string output;
    output.reserve(length * 3);

    for (std::size_t index = 0; index < length; ++index) {
        uint32_t code_point = static_cast<uint16_t>(data[index]);
        if (code_point >= 0xD800 && code_point <= 0xDBFF) {
            if (index + 1 < length) {
                const uint32_t low = static_cast<uint16_t>(data[index + 1]);
                if (low >= 0xDC00 && low <= 0xDFFF) {
                    code_point = 0x10000 + ((code_point - 0xD800) << 10) + (low - 0xDC00);
                    ++index;
                } else {
                    code_point = 0xFFFD;
                }
            } else {
                code_point = 0xFFFD;
            }
        } else if (code_point >= 0xDC00 && code_point <= 0xDFFF) {
            code_point = 0xFFFD;
        }
        append_utf8_code_point(output, code_point);
    }
    return output;
}

} // namespace local_llm
