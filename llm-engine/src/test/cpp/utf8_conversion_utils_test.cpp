#include "utf8_conversion_utils.h"

#include <cassert>
#include <string>

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
        // A four-byte code point split over two model tokens must remain pending,
        // then be emitted once the final continuation bytes arrive.
        std::string pending;
        const std::string first("\xF0\x9F", 2);
        assert(local_llm::normalize_utf8_incremental(first, pending).empty());
        assert(pending == first);
        const std::string second("\x98\x80", 2);
        const std::string expected("\xF0\x9F\x98\x80", 4);
        assert(local_llm::normalize_utf8_incremental(second, pending) == expected);
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
        assert(local_llm::normalize_utf8_incremental(invalid, pending) ==
               std::string("\xEF\xBF\xBD", 3));
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
            assert(local_llm::finalize_utf8_incremental(pending) == replacement);
            assert(pending.empty());
        }
        std::string empty_pending;
        assert(local_llm::finalize_utf8_incremental(empty_pending).empty());
        assert(empty_pending.empty());
    }
    return 0;
}
