package com.petros.ireview;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SseClientTest {

    @Test
    void parsesOneEvent() {
        List<SseClient.Event> events = new ArrayList<>();
        SseClient.Parser p = new SseClient.Parser(events::add);
        p.feed("event: thread-changed");
        p.feed("data: {\"anchor\":\"foo:R:1\"}");
        p.feed("");
        assertEquals(1, events.size());
        assertEquals("thread-changed", events.get(0).name());
        assertEquals("{\"anchor\":\"foo:R:1\"}", events.get(0).data());
    }

    @Test
    void parsesMultipleEventsSeparatedByBlankLines() {
        List<SseClient.Event> events = new ArrayList<>();
        SseClient.Parser p = new SseClient.Parser(events::add);
        for (String line : new String[]{
            "event: a", "data: 1", "",
            "event: b", "data: 2", "",
        }) p.feed(line);
        assertEquals(2, events.size());
        assertEquals("a", events.get(0).name());
        assertEquals("1", events.get(0).data());
        assertEquals("b", events.get(1).name());
        assertEquals("2", events.get(1).data());
    }

    @Test
    void multilineDataIsJoinedWithNewlines() {
        List<SseClient.Event> events = new ArrayList<>();
        SseClient.Parser p = new SseClient.Parser(events::add);
        p.feed("event: x");
        p.feed("data: line1");
        p.feed("data: line2");
        p.feed("");
        assertEquals(1, events.size());
        assertEquals("line1\nline2", events.get(0).data());
    }

    @Test
    void ignoresCommentLines() {
        List<SseClient.Event> events = new ArrayList<>();
        SseClient.Parser p = new SseClient.Parser(events::add);
        p.feed(": this is a comment");
        p.feed("event: x");
        p.feed("data: y");
        p.feed("");
        assertEquals(1, events.size());
    }
}
