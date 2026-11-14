#pragma once

#include <algorithm>
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


// Normalize token-piece bytes while retaining only a valid incomplete UTF-8 suffix.
// This prevents a multi-byte code point split across successive llama tokens from
// being replaced prematurely, and rejects overlong/surrogate/out-of-range sequences.
inline std::string normalize_utf8_incremental(
        const std::string& incoming,
        std::string& pending) {
    static constexpr char replacement[] = "\xEF\xBF\xBD";
    std::string input = pending;
    input.append(incoming);
    pending.clear();

    std::string output;
    output.reserve(input.size());
    std::size_t offset = 0;

    while (offset < input.size()) {
        const auto lead = static_cast<unsigned char>(input[offset]);
        if (lead <= 0x7F) {
            output.push_back(static_cast<char>(lead));
            ++offset;
            continue;
        }

        std::size_t sequence_length = 0;
        if (lead >= 0xC2 && lead <= 0xDF) {
            sequence_length = 2;
        } else if (lead >= 0xE0 && lead <= 0xEF) {
            sequence_length = 3;
        } else if (lead >= 0xF0 && lead <= 0xF4) {
            sequence_length = 4;
        } else {
            output.append(replacement, sizeof(replacement) - 1);
            ++offset;
            continue;
        }

        const std::size_t available = input.size() - offset;
        const std::size_t checked = std::min(sequence_length, available);
        bool malformed = false;
        for (std::size_t index = 1; index < checked; ++index) {
            const auto continuation =
                static_cast<unsigned char>(input[offset + index]);
            if ((continuation & 0xC0) != 0x80) {
                malformed = true;
                break;
            }

            if (index == 1 &&
                ((lead == 0xE0 && continuation < 0xA0) ||
                 (lead == 0xED && continuation > 0x9F) ||
                 (lead == 0xF0 && continuation < 0x90) ||
                 (lead == 0xF4 && continuation > 0x8F))) {
                malformed = true;
                break;
            }
        }

        if (malformed) {
            output.append(replacement, sizeof(replacement) - 1);
            ++offset;
            continue;
        }

        if (available < sequence_length) {
            pending.assign(input, offset, available);
            break;
        }

        output.append(input, offset, sequence_length);
        offset += sequence_length;
    }
    return output;
}

} // namespace local_llm
