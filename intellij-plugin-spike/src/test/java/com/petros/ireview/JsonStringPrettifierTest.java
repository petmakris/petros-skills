package com.petros.ireview;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JsonStringPrettifierTest {

    @Test
    void formatsNestedObjectsAtTwoSpacesPerDepth() {
        Optional<String> out = JsonStringPrettifier.prettify(
            "{\"orders\":[{\"id\":1,\"origin\":{\"id\":\"Client\"}}]}");
        assertEquals(String.join("\n",
            "{",
            "  \"orders\": [",
            "    {",
            "      \"id\": 1,",
            "      \"origin\": {",
            "        \"id\": \"Client\"",
            "      }",
            "    }",
            "  ]",
            "}"), out.orElseThrow());
    }

    @Test
    void quotedPlaceholderStaysQuoted() {
        Optional<String> out = JsonStringPrettifier.prettify(
            "{ \"assetId\": \"{{assetId}}\" }");
        assertEquals(String.join("\n",
            "{",
            "  \"assetId\": \"{{assetId}}\"",
            "}"), out.orElseThrow());
    }

    @Test
    void unquotedValuePlaceholderStaysUnquoted() {
        Optional<String> out = JsonStringPrettifier.prettify(
            "{ \"id\": {{orderId}}, \"limitPrice\": 100 }");
        assertEquals(String.join("\n",
            "{",
            "  \"id\": {{orderId}},",
            "  \"limitPrice\": 100",
            "}"), out.orElseThrow());
    }

    @Test
    void placeholderInsideStringIsPreserved() {
        Optional<String> out = JsonStringPrettifier.prettify(
            "{ \"note\": \"use {{token}} here\" }");
        assertEquals(String.join("\n",
            "{",
            "  \"note\": \"use {{token}} here\"",
            "}"), out.orElseThrow());
    }

    @Test
    void emptyObjectsAndArraysStayInline() {
        Optional<String> out = JsonStringPrettifier.prettify(
            "{ \"recommendation\": {}, \"tags\": [] }");
        assertEquals(String.join("\n",
            "{",
            "  \"recommendation\": {},",
            "  \"tags\": []",
            "}"), out.orElseThrow());
    }

    @Test
    void formattingIsIdempotent() {
        String once = JsonStringPrettifier.prettify(
            "{\"a\":{\"b\":[1,2,{{x}}]}}").orElseThrow();
        String twice = JsonStringPrettifier.prettify(once).orElseThrow();
        assertEquals(once, twice);
    }

    @Test
    void stringValueContainingSentinelLikeTextIsNotCorrupted() {
        Optional<String> out = JsonStringPrettifier.prettify(
            "{ \"note\": \"@@IREVIEW_PH_0@@\", \"id\": {{orderId}} }");
        assertEquals(String.join("\n",
            "{",
            "  \"note\": \"@@IREVIEW_PH_0@@\",",
            "  \"id\": {{orderId}}",
            "}"), out.orElseThrow());
    }

    @Test
    void unbalancedBracesReturnEmpty() {
        assertTrue(JsonStringPrettifier.prettify("{ \"a\": 1 ").isEmpty());
    }

    @Test
    void unterminatedStringReturnsEmpty() {
        assertTrue(JsonStringPrettifier.prettify("{ \"a\": \"oops }").isEmpty());
    }

    @Test
    void preservesStringContentVerbatimIncludingBracesAndColons() {
        Optional<String> out = JsonStringPrettifier.prettify(
            "{ \"u\": \"http://x/{a}:b,c\" }");
        assertEquals(String.join("\n",
            "{",
            "  \"u\": \"http://x/{a}:b,c\"",
            "}"), out.orElseThrow());
    }

    @Test
    void escapedQuoteInsideStringDoesNotEndString() {
        Optional<String> out = JsonStringPrettifier.prettify(
            "{ \"msg\": \"say \\\"hi\\\" now\" }");
        assertEquals(String.join("\n",
            "{",
            "  \"msg\": \"say \\\"hi\\\" now\"",
            "}"), out.orElseThrow());
    }
}
