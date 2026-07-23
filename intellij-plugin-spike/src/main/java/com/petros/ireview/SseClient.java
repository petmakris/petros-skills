package com.petros.ireview;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

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

    /**
     * Handle on one live SSE stream. {@link #done()} completes when the stream
     * ends (server close, error, or {@link #close()}). {@code close()} closes
     * the underlying lines {@code Stream}, which cancels the HTTP body
     * subscription and tears down the TCP connection — merely cancelling the
     * future would leave the connection and its consumer thread alive until
     * the server happened to hang up.
     */
    public static final class Connection {
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final AtomicReference<Stream<String>> lines = new AtomicReference<>();
        private volatile boolean closed = false;

        /** Completes (normally) when the stream ends, however it ends. */
        public CompletableFuture<Void> done() { return done; }

        /** Idempotent. Cancels the HTTP subscription so the server sees EOF. */
        public void close() {
            closed = true;
            Stream<String> s = lines.getAndSet(null);
            if (s != null) {
                try { s.close(); } catch (RuntimeException ignored) { }
            }
            done.complete(null);
        }
    }

    /**
     * Open an SSE stream on the supplied (shared) client. Returns a
     * {@link Connection} whose {@code done()} future completes when the stream
     * ends, and whose {@code close()} actually terminates the connection.
     * Reusing the caller's {@link HttpClient} avoids leaking one IO-thread
     * pool per reconnect — Java 17's {@code HttpClient} has no {@code close()},
     * so a fresh client per connect is only reclaimed by GC.
     */
    public static Connection connect(
            HttpClient client,
            URI uri,
            Consumer<Event> onEvent,
            Consumer<Throwable> onError) {
        Connection conn = new Connection();
        HttpRequest req = HttpRequest.newBuilder(uri)
            .header("Accept", "text/event-stream")
            .GET()
            .build();
        client.sendAsync(req, HttpResponse.BodyHandlers.ofLines())
            .thenAccept(resp -> {
                Stream<String> body = resp.body();
                conn.lines.set(body);
                if (conn.closed) {
                    // close() raced the response arriving: it may have missed
                    // the stream we just published, so close it here too
                    // (Stream.close is idempotent).
                    Stream<String> s = conn.lines.getAndSet(null);
                    if (s != null) {
                        try { s.close(); } catch (RuntimeException ignored) { }
                    }
                    return;
                }
                Parser parser = new Parser(onEvent);
                try {
                    body.forEach(parser::feed);
                } catch (Exception e) {
                    if (!conn.closed) onError.accept(e);
                }
            })
            .exceptionally(t -> { if (!conn.closed) onError.accept(t); return null; })
            .whenComplete((v, t) -> conn.done.complete(null));
        return conn;
    }

    private SseClient() {}
}
