package com.petros.ireview;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP server for tests. Lets us script /api/sessions, /threads.json,
 * and /stream responses without a real Python server.
 */
public final class FakeReviewServer implements AutoCloseable {
    private final HttpServer server;
    private final int port;
    public final List<HttpExchange> requests = new ArrayList<>();
    public final ConcurrentLinkedQueue<String> sseQueue = new ConcurrentLinkedQueue<>();
    public volatile String sessionsJson = "[]";
    public volatile String threadsJson = "{}";
    /** Epoch seconds of the last watcher heartbeat returned by /poll; null → none yet (0). */
    public volatile Long watcherSeenAt = null;
    /** Count of POSTs that reached /api/submit. */
    public final java.util.concurrent.atomic.AtomicInteger submitCount =
        new java.util.concurrent.atomic.AtomicInteger();
    /** Count of POSTs that reached /api/cancel. */
    public final java.util.concurrent.atomic.AtomicInteger cancelCount =
        new java.util.concurrent.atomic.AtomicInteger();

    public FakeReviewServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/sessions", this::handleSessions);
        server.createContext("/s/", this::handleSession);
        server.start();
    }

    public String baseUrl() { return "http://127.0.0.1:" + port; }

    private void handleSessions(HttpExchange ex) throws IOException {
        requests.add(ex);
        byte[] body = sessionsJson.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private void handleSession(HttpExchange ex) throws IOException {
        requests.add(ex);
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/threads.json")) {
            byte[] body = threadsJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
            return;
        }
        if (path.endsWith("/poll")) {
            long seen = watcherSeenAt != null ? watcherSeenAt : 0;
            byte[] body = ("{\"threads\":{},\"watcher_seen_at\":" + seen
                + ",\"finished\":false}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
            return;
        }
        if (path.endsWith("/api/submit")) {
            submitCount.incrementAndGet();
            ex.sendResponseHeaders(202, -1);
            ex.close();
            return;
        }
        if (path.endsWith("/api/cancel")) {
            cancelCount.incrementAndGet();
            // A cancelled session goes terminal — the real server drops it
            // from /api/sessions, so mirror that here.
            sessionsJson = "[]";
            ex.sendResponseHeaders(200, -1);
            ex.close();
            return;
        }
        if (path.endsWith("/stream")) {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                while (!Thread.currentThread().isInterrupted()) {
                    String chunk;
                    while ((chunk = sseQueue.poll()) != null) {
                        os.write(chunk.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }
                    try { Thread.sleep(20); } catch (InterruptedException e) { break; }
                }
            } catch (IOException ignored) {
                // Client disconnected — fine.
            }
            return;
        }
        ex.sendResponseHeaders(404, -1);
    }

    public void pushSseEvent(String name, String data) {
        sseQueue.offer("event: " + name + "\ndata: " + data + "\n\n");
    }

    @Override public void close() {
        server.stop(0);
    }
}
