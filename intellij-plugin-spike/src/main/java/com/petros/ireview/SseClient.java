package com.petros.ireview;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Minimal Server-Sent Events consumer.
 *
 * Use {@link Parser} for unit testing the line-to-event state machine.
 * Use {@link #connect} for live HTTP consumption.
 */
public final class SseClient {

    public record Event(String name, String data) {}

    /** Pure state machine. Feed it lines (without trailing newline). */
    public static final class Parser {
        private final Consumer<Event> sink;
        private String eventName = "message";
        private StringBuilder dataBuf = new StringBuilder();
        private boolean hasData = false;

        public Parser(Consumer<Event> sink) { this.sink = sink; }

        public void feed(String line) {
            if (line.isEmpty()) {
                if (hasData) {
                    sink.accept(new Event(eventName, dataBuf.toString()));
                }
                eventName = "message";
                dataBuf = new StringBuilder();
                hasData = false;
                return;
            }
            if (line.startsWith(":")) return; // comment
            int colon = line.indexOf(':');
            String field;
            String value;
            if (colon < 0) { field = line; value = ""; }
            else { field = line.substring(0, colon); value = line.substring(colon + 1); }
            if (value.startsWith(" ")) value = value.substring(1);
            switch (field) {
                case "event" -> eventName = value;
                case "data" -> {
                    if (hasData) dataBuf.append('\n');
                    dataBuf.append(value);
                    hasData = true;
                }
                default -> { /* ignore id, retry, etc. */ }
            }
        }
    }

    /** Open an SSE stream. Returns a CompletableFuture that completes when the stream ends. */
    public static CompletableFuture<Void> connect(
            URI uri,
            Duration connectTimeout,
            Consumer<Event> onEvent,
            Consumer<Throwable> onError) {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .build();
        HttpRequest req = HttpRequest.newBuilder(uri)
            .header("Accept", "text/event-stream")
            .GET()
            .build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofLines())
            .thenAccept(resp -> {
                Parser parser = new Parser(onEvent);
                try {
                    resp.body().forEach(parser::feed);
                } catch (Exception e) {
                    onError.accept(e);
                }
            })
            .exceptionally(t -> { onError.accept(t); return null; });
    }

    private SseClient() {}
}
