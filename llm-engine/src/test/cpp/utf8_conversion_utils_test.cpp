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
    return 0;
}
