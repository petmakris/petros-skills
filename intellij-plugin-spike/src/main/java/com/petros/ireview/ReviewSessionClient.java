package com.petros.ireview;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to the interactive_review server: discovers a session by cwd,
 * opens an SSE stream, caches per-anchor syntheses, and pushes events
 * to listeners.
 *
 * Thread-safe. Listeners are invoked on the SSE consumer thread; bridge
 * to the EDT in the UI components.
 */
public final class ReviewSessionClient {

    public record SessionInfo(String sid, String prRef, String title, String stateDir) {}
    public record ThreadState(String synthesis, int version) {}

    public interface Listener {
        default void onAttached(SessionInfo info) {}
        default void onDetached() {}
        default void onThreadChanged(String anchor, String synthesis, int version) {}
        default void onThreadDeleted(String anchor) {}
        default void onPendingChanged(String anchor, boolean pending) {}
        default void onStateChanged(State state) {}
    }

    public enum State { DORMANT, CONNECTING, ACTIVE, DISCONNECTED, STALE }

    /**
     * How long the watcher heartbeat may age before we treat the Claude
     * session as gone. The watcher rewrites it every ~1s (even while blocked
     * on an ack), so anything past this is dead, not slow. Mirrors the
     * annotate web client's WATCHER_DEAD_AFTER_S.
     */
    private static final Duration STALE_AFTER = Duration.ofSeconds(15);

    private final String baseUrl;
    private final String projectCwd;
    private final Duration pollInterval;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).build();
    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(2);
    private final java.util.List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, ThreadState> cache = new ConcurrentHashMap<>();
    /** Anchors with an in-flight Claude reply (post-submit, pre-SSE-confirmation). */
    private final java.util.Set<String> pending = ConcurrentHashMap.newKeySet();
    private volatile State state = State.DORMANT;
    private volatile SessionInfo current = null;
    private volatile Future<?> sseTask = null;
    private volatile ScheduledFuture<?> discoverTask = null;

    public ReviewSessionClient(String baseUrl, String projectCwd, Duration pollInterval) {
        this.baseUrl = baseUrl;
        this.projectCwd = projectCwd;
        this.pollInterval = pollInterval;
    }

    public void start() {
        discoverTask = exec.scheduleWithFixedDelay(this::pollDiscover,
            0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (discoverTask != null) discoverTask.cancel(true);
        if (sseTask != null) sseTask.cancel(true);
        exec.shutdownNow();
        setState(State.DORMANT);
    }

    public void addListener(Listener l) { listeners.add(l); }

    public Optional<SessionInfo> currentSession() { return Optional.ofNullable(current); }

    public State state() { return state; }

    public Optional<ThreadState> threadFor(String anchor) {
        return Optional.ofNullable(cache.get(anchor));
    }

    public Map<String, ThreadState> snapshotCache() {
        return new java.util.HashMap<>(cache);
    }

    public boolean isPending(String anchor) { return pending.contains(anchor); }

    /** POST a comment event to /s/<sid>/api/submit. */
    public CompletableFuture<Void> postComment(String anchor, String text) {
        SessionInfo s = current;
        if (s == null) return CompletableFuture.failedFuture(new IllegalStateException("no session"));
        // The Claude session is gone — the server would 202 the submit but no
        // watcher will ever process it. Reject so the user isn't fooled into
        // thinking the question was asked.
        if (state == State.STALE) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "Claude session is gone — re-run /interactive-review to resume"));
        }
        markPending(anchor, true);
        String body = "{\"anchor\":" + jsonEscape(anchor)
                    + ",\"type\":\"comment\",\"text\":" + jsonEscape(text) + "}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + s.sid() + "/api/submit"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .whenComplete((resp, err) -> {
                // On HTTP failure or transport error, clear pending so the
                // user (and the side-panel × button) can recover.
                if (err != null || (resp != null && resp.statusCode() / 100 != 2)) {
                    markPending(anchor, false);
                }
            })
            .thenAccept(resp -> {
                if (resp.statusCode() / 100 != 2) {
                    throw new RuntimeException("submit failed: HTTP " + resp.statusCode());
                }
            });
    }

    /**
     * POST to /s/<sid>/api/cancel — ends the review session. The server marks
     * it terminal; a live watcher picks up the marker and emits
     * WEBCOMPANION_CANCELLED. On success we detach immediately rather than
     * waiting for the next discovery poll to notice the session is gone.
     */
    public CompletableFuture<Void> cancelSession() {
        SessionInfo s = current;
        if (s == null) return CompletableFuture.failedFuture(new IllegalStateException("no session"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + s.sid() + "/api/cancel"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .thenAccept(resp -> {
                if (resp.statusCode() / 100 != 2) {
                    throw new RuntimeException("cancel failed: HTTP " + resp.statusCode());
                }
                handleNoSession();
            });
    }

    /** POST a delete request to /s/<sid>/api/threads/delete. */
    public CompletableFuture<Void> deleteThread(String anchor) {
        SessionInfo s = current;
        if (s == null) return CompletableFuture.failedFuture(new IllegalStateException("no session"));
        String body = "{\"anchor\":" + jsonEscape(anchor) + "}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + s.sid() + "/api/threads/delete"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .thenAccept(resp -> {
                if (resp.statusCode() / 100 != 2) {
                    throw new RuntimeException("delete failed: HTTP " + resp.statusCode());
                }
            });
    }

    private void markPending(String anchor, boolean isPending) {
        boolean changed = isPending ? pending.add(anchor) : pending.remove(anchor);
        if (!changed) return;
        for (Listener l : listeners) l.onPendingChanged(anchor, isPending);
    }

    // --- internal ---

    private void pollDiscover() {
        try {
            String url = baseUrl + "/api/sessions?cwd=" + URLEncoder.encode(projectCwd, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                handleNoSession();
                return;
            }
            SessionInfo found = parseFirstSession(resp.body());
            if (found == null) {
                handleNoSession();
                return;
            }
            if (current == null || !current.sid().equals(found.sid())) {
                attach(found);
            }
            checkWatcherHeartbeat(found.sid());
        } catch (Exception e) {
            handleNoSession();
        }
    }

    /**
     * Reachability of the HTTP server (it answers /api/sessions and keeps the
     * SSE stream open) does NOT mean the Claude session is alive — the server
     * is a long-lived process that outlives the session. The only liveness
     * signal is the watcher heartbeat, which the session rewrites every ~1s.
     * Poll it; if it has gone stale, flip to STALE so the UI stops claiming
     * "live" and submissions are refused.
     */
    private void checkWatcherHeartbeat(String sid) {
        long seenAt;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + sid + "/poll")).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return;
            String v = jsonField(resp.body(), "watcher_seen_at");
            seenAt = v.isEmpty() ? 0 : Long.parseLong(v);
        } catch (Exception e) {
            return; // transient — leave state as-is, next poll retries
        }
        // No heartbeat written yet (session just armed) → not dead, leave alone.
        if (seenAt <= 0) return;
        long ageMs = System.currentTimeMillis() - seenAt * 1000;
        if (ageMs > STALE_AFTER.toMillis()) {
            if (state != State.STALE) {
                // Clear pending so spinners and the side-panel × recover —
                // no ack is ever coming for these.
                for (String a : new java.util.ArrayList<>(pending)) markPending(a, false);
                setState(State.STALE);
            }
        } else if (state == State.STALE) {
            // Watcher came back (user re-ran /interactive-review).
            setState(State.ACTIVE);
        }
    }

    private void handleNoSession() {
        if (current != null) {
            current = null;
            cache.clear();
            pending.clear();
            if (sseTask != null) { sseTask.cancel(true); sseTask = null; }
            for (Listener l : listeners) l.onDetached();
        }
        setState(State.DORMANT);
    }

    private void attach(SessionInfo s) {
        current = s;
        // Switching sessions: drop any cached state from the previous SID.
        // Otherwise the side panel keeps showing dead threads from the old
        // session and × clicks on them return HTTP 409 from the server.
        cache.clear();
        pending.clear();
        setState(State.CONNECTING);
        seedCache(s.sid());
        for (Listener l : listeners) l.onAttached(s);
        openSse(s.sid());
    }

    private void seedCache(String sid) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + sid + "/threads.json")).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                Map<String, ThreadState> seeded = parseThreadsBulk(resp.body());
                cache.putAll(seeded);
                for (var e : seeded.entrySet()) {
                    for (Listener l : listeners) {
                        l.onThreadChanged(e.getKey(), e.getValue().synthesis(), e.getValue().version());
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void openSse(String sid) {
        URI uri = URI.create(baseUrl + "/s/" + sid + "/stream");
        sseTask = exec.submit(() -> {
            try {
                SseClient.connect(uri, Duration.ofSeconds(2), this::handleSseEvent, t -> {
                    setState(State.DISCONNECTED);
                    exec.schedule(() -> openSse(sid), 2, TimeUnit.SECONDS);
                }).join();
            } catch (Exception e) {
                setState(State.DISCONNECTED);
            }
        });
        setState(State.ACTIVE);
    }

    private void handleSseEvent(SseClient.Event e) {
        String name = e.name();
        String data = e.data();
        if ("thread-deleted".equals(name)) {
            String anchor = jsonField(data, "anchor");
            if (anchor.isEmpty()) return;
            cache.remove(anchor);
            markPending(anchor, false);
            for (Listener l : listeners) l.onThreadDeleted(anchor);
            return;
        }
        if (!"thread-changed".equals(name)) return;
        String anchor = jsonField(data, "anchor");
        String synthesis = jsonField(data, "latest_synthesis");
        String versionStr = jsonField(data, "version");
        int version = versionStr.isEmpty() ? 0 : Integer.parseInt(versionStr);

        // Filter: the server fires `thread-changed` on EVERY thread mutation
        // including the user's own appended question (which arrives via HTTP
        // submit). For those, the version bumps but `latest_synthesis` is
        // unchanged (Claude hasn't replied yet). Treat unchanged-text events
        // as a no-op so the popup's "thinking…" spinner stays up.
        ThreadState existing = cache.get(anchor);
        if (existing != null
                && existing.synthesis().equals(synthesis)
                && existing.version() == version) {
            return;
        }
        if (existing != null && existing.synthesis().equals(synthesis)) {
            // Version bumped but synthesis unchanged → just update the cache,
            // don't notify listeners (no user-visible change).
            cache.put(anchor, new ThreadState(synthesis, version));
            return;
        }
        cache.put(anchor, new ThreadState(synthesis, version));
        // New synthesis text landed → Claude has replied. Clear pending.
        markPending(anchor, false);
        for (Listener l : listeners) l.onThreadChanged(anchor, synthesis, version);
    }

    private void setState(State s) {
        if (state == s) return;
        state = s;
        for (Listener l : listeners) l.onStateChanged(s);
    }

    // --- small json helpers (avoiding a dependency) ---

    private static String jsonEscape(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String jsonField(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|(\\d+))");
        Matcher m = p.matcher(json);
        if (!m.find()) return "";
        return m.group(1) != null ? unescapeJsonString(m.group(1)) : m.group(2);
    }

    /**
     * Decode the contents of a JSON string literal. Handles the standard
     * escape sequences (n, r, t, quote, backslash, slash, b, f, and the
     * 4-hex-digit unicode form). Input is the body of the string (between
     * the outer quotes), not the quoted form.
     */
    private static String unescapeJsonString(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { out.append(c); continue; }
            char next = s.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    } else {
                        out.append('\\').append(next);
                    }
                }
                default -> out.append('\\').append(next);
            }
        }
        return out.toString();
    }

    private static SessionInfo parseFirstSession(String json) {
        if (json == null || !json.contains("\"sid\"")) return null;
        return new SessionInfo(
            jsonField(json, "sid"),
            jsonField(json, "pr_ref"),
            jsonField(json, "title"),
            jsonField(json, "state_dir"));
    }

    private static Map<String, ThreadState> parseThreadsBulk(String json) {
        Map<String, ThreadState> out = new HashMap<>();
        Pattern p = Pattern.compile(
            "\"([^\"]+)\"\\s*:\\s*\\{[^}]*\"latest_synthesis\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"[^}]*\"version\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(json);
        while (m.find()) {
            out.put(m.group(1), new ThreadState(unescapeJsonString(m.group(2)), Integer.parseInt(m.group(3))));
        }
        return out;
    }
}
