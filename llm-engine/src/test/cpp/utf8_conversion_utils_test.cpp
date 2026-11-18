#include "utf8_conversion_utils.h"
#include "output_token_validation_utils.h"

#include <cassert>
#include <string>
#include <vector>

int main() {
    {
        const char16_t input[] = u"Local LLM";
        assert(local_llm::utf16_to_utf8(input, sizeof(input) / sizeof(input[0]) - 1) == "Local LLM");
    }
    {
        const char16_t input[] = {static_cast<char16_t>(0x00E4), static_cast<char16_t>(0x20AC)};
        const std::string expected("\xC3\xA4\xE2\x82\xAC", 5);
        assert(local_llm::utf16_to_utf8(input, 2) == expected);
    }
    {
        // U+1F600 GRINNING FACE: one valid UTF-16 surrogate pair.
        const char16_t input[] = {static_cast<char16_t>(0xD83D), static_cast<char16_t>(0xDE00)};
        const std::string expected("\xF0\x9F\x98\x80", 4);
        assert(local_llm::utf16_to_utf8(input, 2) == expected);
    }
    {
        // Replace a malformed high surrogate, then preserve the following code unit.
        const char16_t input[] = {static_cast<char16_t>(0xD800), u'A'};
        const std::string expected("\xEF\xBF\xBD" "A", 4);
        assert(local_llm::utf16_to_utf8(input, 2) == expected);
    }
    {
        const char16_t input[] = {static_cast<char16_t>(0xD800)};
        const std::string expected("\xEF\xBF\xBD", 3);
        assert(local_llm::utf16_to_utf8(input, 1) == expected);
    }
    {
        const char16_t input[] = {static_cast<char16_t>(0xDC00)};
        const std::string expected("\xEF\xBF\xBD", 3);
        assert(local_llm::utf16_to_utf8(input, 1) == expected);
    }
    {
        // JNI strings are length-aware; embedded NUL must not truncate input.
        const char16_t input[] = {u'A', u'\0', u'B'};
        const std::string expected("A\0B", 3);
        const auto converted = local_llm::utf16_to_utf8(input, 3);
        assert(converted.size() == 3);
        assert(converted == expected);
    }
    {
        const char16_t* input = nullptr;
        assert(local_llm::utf16_to_utf8(input, 0).empty());
    }
    {
        const std::string input("ASCII \xC3\xA4 \xE2\x82\xAC \xF0\x9F\x98\x80", 17);
        const std::u16string expected = {
            u'A', u'S', u'C', u'I', u'I', u' ', static_cast<char16_t>(0x00E4), u' ',
            static_cast<char16_t>(0x20AC), u' ', static_cast<char16_t>(0xD83D),
            static_cast<char16_t>(0xDE00)
        };
        assert(local_llm::utf8_to_utf16(input) == expected);
    }
    {
        // Standard UTF-8 NUL is length-aware and becomes a Java U+0000 code unit.
        const std::string input("A\0B", 3);
        const std::u16string expected = {u'A', u'\0', u'B'};
        assert(local_llm::utf8_to_utf16(input) == expected);
    }
    {
        // Malformed, overlong, surrogate and out-of-range sequences are replaced.
        const std::string invalid("\xC0\xAF\xED\xA0\x80\xF4\x90\x80\x80", 9);
        const std::u16string converted = local_llm::utf8_to_utf16(invalid);
        assert(converted.size() == 9);
        for (const char16_t code_unit : converted) {
            assert(code_unit == static_cast<char16_t>(0xFFFD));
        }
    }
    {
        // A four-byte code point split over two model tokens must remain pending,
        // then be emitted once the final continuation bytes arrive.
        std::string pending;
        const std::string first("\xF0\x9F", 2);
        assert(local_llm::normalize_utf8_incremental(first, pending).empty());
        assert(pending == first);
        const std::string second("\x98\x80", 2);
        const std::string expected("\xF0\x9F\x98\x80", 4);
        bool malformed = false;
        assert(local_llm::normalize_utf8_incremental(second, pending, &malformed) == expected);
        assert(!malformed);
        assert(pending.empty());
    }
    {
        // A valid ASCII prefix is returned immediately while an incomplete
        // three-byte suffix is retained for the next token callback.
        std::string pending;
        const std::string piece("A\xE2\x82", 3);
        assert(local_llm::normalize_utf8_incremental(piece, pending) == "A");
        assert(pending == std::string("\xE2\x82", 2));
        const std::string continuation("\xAC", 1);
        assert(local_llm::normalize_utf8_incremental(continuation, pending) ==
               std::string("\xE2\x82\xAC", 3));
        assert(pending.empty());
    }
    {
        // A non-continuation after a pending lead byte emits U+FFFD and
        // preserves the ordinary following byte.
        std::string pending("\xE2", 1);
        const auto output = local_llm::normalize_utf8_incremental("A", pending);
        assert(output == std::string("\xEF\xBF\xBD" "A", 4));
        assert(pending.empty());
    }
    {
        // Reject UTF-8 encodings of surrogate values and code points > U+10FFFF.
        std::string pending;
        const std::string invalidSurrogate("\xED\xA0\x80", 3);
        const auto surrogateOutput =
            local_llm::normalize_utf8_incremental(invalidSurrogate, pending);
        assert(surrogateOutput == std::string("\xEF\xBF\xBD", 3) +
                                  std::string("\xEF\xBF\xBD", 3) +
                                  std::string("\xEF\xBF\xBD", 3));
        const std::string invalidRange("\xF4\x90\x80\x80", 4);
        const auto rangeOutput =
            local_llm::normalize_utf8_incremental(invalidRange, pending);
        assert(rangeOutput.size() == 12);
        assert(pending.empty());
    }
    {
        // A lone invalid lead byte is replaced instead of leaking malformed UTF-8.
        std::string pending;
        const std::string invalid("\xC0", 1);
        bool malformed = false;
        assert(local_llm::normalize_utf8_incremental(invalid, pending, &malformed) ==
               std::string("\xEF\xBF\xBD", 3));
        assert(malformed);
        assert(pending.empty());
    }
    {
        // A stream terminated while a valid UTF-8 sequence is pending must emit
        // one replacement character, then clear the pending bytes.
        const std::string replacement("\xEF\xBF\xBD", 3);
        const std::vector<std::string> incomplete_sequences = {
            std::string("\xC2", 1),
            std::string("\xE2", 1),
            std::string("\xE2\x82", 2),
            std::string("\xF0", 1),
            std::string("\xF0\x9F", 2),
            std::string("\xF0\x9F\x98", 3),
        };
        for (const auto& sequence : incomplete_sequences) {
            std::string pending = sequence;
            bool incomplete = false;
            assert(local_llm::finalize_utf8_incremental(pending, &incomplete) == replacement);
            assert(incomplete);
            assert(pending.empty());
        }
        std::string empty_pending;
        bool incomplete = true;
        assert(local_llm::finalize_utf8_incremental(empty_pending, &incomplete).empty());
        assert(!incomplete);
        assert(empty_pending.empty());
    }
    {
        constexpr uint32_t normal = 1u << 2;
        constexpr uint32_t unknown = 1u << 0;
        constexpr uint32_t unused = 1u << 1;
        constexpr uint32_t control = 1u << 3;
        constexpr uint32_t byte = 1u << 5;
        constexpr uint32_t normalized = 1u << 6;
        constexpr uint32_t renderable = normal | byte;
        constexpr uint32_t forbidden = unknown | unused | control;
        assert(local_llm::is_renderable_output_token(normal, renderable, forbidden));
        assert(local_llm::is_renderable_output_token(byte, renderable, forbidden));
        assert(local_llm::is_renderable_output_token(normal | normalized, renderable, forbidden));
        assert(!local_llm::is_renderable_output_token(0, renderable, forbidden));
        assert(!local_llm::is_renderable_output_token(control, renderable, forbidden));
        assert(!local_llm::is_renderable_output_token(normal | control, renderable, forbidden));
        assert(!local_llm::is_renderable_output_token(unknown, renderable, forbidden));
        assert(!local_llm::is_renderable_output_token(unused, renderable, forbidden));
        assert(!local_llm::is_renderable_output_token(1u << 4, renderable, forbidden));
    }

    return 0;

}
