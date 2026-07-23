package com.petros.ireview;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
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

    @Test
    void connectionStillReceivesEventsBeforeClose() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build();
            List<SseClient.Event> events = new CopyOnWriteArrayList<>();
            SseClient.Connection conn = SseClient.connect(http,
                URI.create(server.baseUrl() + "/s/abc/stream"), events::add, t -> {});
            server.pushSseEvent("thread-changed", "{\"anchor\":\"foo:R:1\"}");
            waitFor(() -> !events.isEmpty(), 3000, "event should arrive over the live stream");
            assertEquals("thread-changed", events.get(0).name());
            conn.close();
        }
    }

    @Test
    void closeTerminatesServerSideConnection() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build();
            SseClient.Connection conn = SseClient.connect(http,
                URI.create(server.baseUrl() + "/s/abc/stream"), ev -> {}, t -> {});
            waitFor(() -> server.streamOpens.get() >= 1, 3000,
                "server should see the stream open");
            assertEquals(0, server.streamCloses.get(),
                "connection must still be up before close()");

            conn.close();

            // The server heartbeats every ~20ms; a write to a connection the
            // client actually closed fails, which is the server seeing EOF.
            // Cancelling only a future would leave the TCP connection alive
            // and this would time out.
            waitFor(() -> server.streamCloses.get() >= 1, 3000,
                "close() must terminate the server-side connection");
            assertTrue(conn.done().isDone(), "done() completes once closed");
        }
    }

    private static void waitFor(BooleanSupplier cond, long timeoutMs, String what)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("timed out: " + what);
            Thread.sleep(25);
        }
    }
}
