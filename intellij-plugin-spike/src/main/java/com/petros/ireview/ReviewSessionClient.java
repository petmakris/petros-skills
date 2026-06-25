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
    public record ThreadState(String synthesis, int version, String anchorText,
                              String title, String question) {}

    public interface Listener {
        default void onAttached(SessionInfo info) {}
        default void onDetached() {}
        default void onThreadChanged(String anchor, String synthesis, int version) {}
        default void onThreadDeleted(String anchor) {}
        default void onPendingChanged(String anchor, boolean pending) {}
        default void onStateChanged(State state) {}
    }

    /**
     * Session lifecycle, in precedence order ENDED > PAUSED > DISCONNECTED >
     * ACTIVE. PAUSED = watcher silent past STALE_AFTER but recoverable (the
     * user may re-arm). ENDED = the server reported the session terminal
     * (cancelled/finished) or watcher-dead past the reap threshold; it is a
     * one-way latch — the panel freezes read-only and never un-freezes for the
     * same sid.
     */
    public enum State { DORMANT, CONNECTING, ACTIVE, DISCONNECTED, PAUSED, ENDED }

    /**
     * How long the watcher heartbeat may age before we treat the Claude
     * session as merely PAUSED. The watcher rewrites it every ~1s (even while
     * blocked on an ack), so anything past this is gone, not slow. The
     * authoritative ENDED decision (reap threshold) is made by the server and
     * delivered via the /poll "ended" flag.
     */
    private static final Duration STALE_AFTER = Duration.ofSeconds(15);

    /**
     * One-way latch: once the server says the attached session is ENDED, the
     * panel freezes read-only. Reset only when we attach a different session
     * or fully detach. A returning heartbeat must never un-freeze it.
     */
    private volatile boolean endedLatched = false;

    private final String baseUrl;
    private final String projectCwd;
    private final Duration pollInterval;
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    /** Per-request read timeout for the synchronous polls — without it a
     *  server that accepts the socket but stalls pins a discovery-pool thread
     *  indefinitely and survives project close. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).build();
    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(2);
    private final java.util.List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, ThreadState> cache = new ConcurrentHashMap<>();
    /** Bumped on every cache mutation so consumers (e.g. the gutter index) can
     *  memoize against a cheap version stamp instead of rebuilding each paint. */
    private final java.util.concurrent.atomic.AtomicLong cacheVersion =
        new java.util.concurrent.atomic.AtomicLong();
    /** Anchors with an in-flight Claude reply (post-submit, pre-SSE-confirmation),
     *  mapped to the submit token that set them. A later submit on the same
     *  anchor supersedes the token, so a stale failure can't clear a newer
     *  in-flight reply's spinner. */
    private final Map<String, Long> pending = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong submitSeq =
        new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean closed = false;
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
        closed = true;
        if (discoverTask != null) discoverTask.cancel(true);
        if (sseTask != null) sseTask.cancel(true);
        exec.shutdownNow();
        setState(State.DORMANT);
    }

    public void addListener(Listener l) { listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    /** Monotonic counter bumped on every cache mutation; use to memoize. */
    public long cacheVersion() { return cacheVersion.get(); }

    public Optional<SessionInfo> currentSession() { return Optional.ofNullable(current); }

    public State state() { return state; }

    public Optional<ThreadState> threadFor(String anchor) {
        return Optional.ofNullable(cache.get(anchor));
    }

    public Map<String, ThreadState> snapshotCache() {
        return new java.util.HashMap<>(cache);
    }

    public boolean isPending(String anchor) { return pending.containsKey(anchor); }

    /** POST a comment event to /s/<sid>/api/submit. */
    public CompletableFuture<Void> postComment(String anchor, String text, String anchorText) {
        SessionInfo s = current;
        if (s == null) return CompletableFuture.failedFuture(new IllegalStateException("no session"));
        if (state == State.PAUSED || state == State.ENDED) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "Claude session is gone — re-run /interactive-review to resume"));
        }
        long token = submitSeq.incrementAndGet();
        markPending(anchor, token);
        java.util.Map<String, String> payload = new java.util.LinkedHashMap<>();
        payload.put("anchor", anchor);
        payload.put("type", "comment");
        payload.put("text", text);
        payload.put("anchor_text", anchorText == null ? "" : anchorText);
        String body = GSON.toJson(payload);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + s.sid() + "/api/submit"))
            .header("Content-Type", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .whenComplete((resp, err) -> {
                if (err != null || (resp != null && resp.statusCode() / 100 != 2)) {
                    clearPendingIfToken(anchor, token);
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
            .timeout(REQUEST_TIMEOUT)
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
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .thenAccept(resp -> {
                if (resp.statusCode() / 100 != 2) {
                    throw new RuntimeException("delete failed: HTTP " + resp.statusCode());
                }
            });
    }

    /** Mark an anchor pending under a submit token; notify only on the
     *  not-pending → pending transition. */
    private void markPending(String anchor, long token) {
        if (pending.put(anchor, token) == null) {
            for (Listener l : listeners) l.onPendingChanged(anchor, true);
        }
    }

    /** Clear pending regardless of token — used when the reply is confirmed or
     *  the session freezes/pauses. */
    private void clearPending(String anchor) {
        if (pending.remove(anchor) != null) {
            for (Listener l : listeners) l.onPendingChanged(anchor, false);
        }
    }

    /** Clear pending only if this exact submit is still the latest one — a
     *  newer submit on the same anchor must keep its spinner. */
    private void clearPendingIfToken(String anchor, long token) {
        if (pending.remove(anchor, token)) {
            for (Listener l : listeners) l.onPendingChanged(anchor, false);
        }
    }

    // --- internal ---

    private void pollDiscover() {
        SessionInfo found;
        try {
            found = fetchNewestSession();
        } catch (Exception e) {
            // Server unreachable. A transient blip must not wipe a frozen
            // (ENDED) panel; for a live session this preserves the prior
            // behaviour of detaching.
            if (!endedLatched) handleNoSession();
            return;
        }
        if (endedLatched) {
            // Frozen read-only. Discovery only reaps dead sessions, so the
            // ONLY thing that replaces a frozen panel is a genuinely new, LIVE
            // session (a different sid). Never fall back to a zombie, never
            // clear on our own.
            if (found != null && (current == null || !current.sid().equals(found.sid()))) {
                attach(found);
            }
            return;
        }
        if (found == null) {
            // Discovery has nothing for this cwd. If we were attached, the
            // session most likely just ended (terminal/dead → reaped) — freeze
            // it on its own findings rather than blanking the panel. Only blank
            // when the session is genuinely gone (poll fails / not ended).
            if (current != null) {
                pollLiveness(current.sid());
                if (!endedLatched) handleNoSession();
            } else {
                handleNoSession();
            }
            return;
        }
        if (current == null || !current.sid().equals(found.sid())) {
            attach(found);
        }
        pollLiveness(found.sid());
    }

    private SessionInfo fetchNewestSession() throws Exception {
        String url = baseUrl + "/api/sessions?cwd=" + URLEncoder.encode(projectCwd, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;
        return parseFirstSession(resp.body());
    }

    /** Freeze the current session read-only. One-way: only attach() clears it. */
    private void latchEnded() {
        endedLatched = true;
        for (String a : new java.util.ArrayList<>(pending.keySet())) clearPending(a);
        if (sseTask != null) { sseTask.cancel(true); sseTask = null; }
        setState(State.ENDED);
    }

    /**
     * Reachability of the HTTP server (it answers /api/sessions and keeps the
     * SSE stream open) does NOT mean the Claude session is alive — the server
     * is a long-lived process that outlives the session. The only liveness
     * signal is the watcher heartbeat, which the session rewrites every ~1s.
     * Poll it; if it has gone stale, flip to STALE so the UI stops claiming
     * "live" and submissions are refused.
     */
    private void pollLiveness(String sid) {
        if (endedLatched) return; // frozen; nothing un-freezes the same sid
        long seenAt;
        boolean ended;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + sid + "/poll")).timeout(REQUEST_TIMEOUT).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return;
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
            seenAt = o.has("watcher_seen_at") && !o.get("watcher_seen_at").isJsonNull()
                ? o.get("watcher_seen_at").getAsLong() : 0;
            ended = o.has("ended") && !o.get("ended").isJsonNull() && o.get("ended").getAsBoolean();
        } catch (Exception e) {
            return; // transient — leave state as-is, next poll retries
        }
        // Authoritative end (cancelled/finished, or watcher-dead past the
        // server's reap threshold) → freeze read-only, one-way.
        if (ended) { latchEnded(); return; }
        // No heartbeat written yet (session just armed) → not dead, leave alone.
        if (seenAt <= 0) return;
        long ageMs = System.currentTimeMillis() - seenAt * 1000;
        if (ageMs > STALE_AFTER.toMillis()) {
            if (state != State.PAUSED) {
                // Clear pending so spinners and the side-panel × recover —
                // no ack is coming until the watcher returns.
                for (String a : new java.util.ArrayList<>(pending.keySet())) clearPending(a);
                setState(State.PAUSED);
            }
        } else if (state == State.PAUSED) {
            // Watcher came back (user re-ran /interactive-review).
            setState(State.ACTIVE);
        }
    }

    private void handleNoSession() {
        endedLatched = false;
        if (current != null) {
            current = null;
            cache.clear();
            cacheVersion.incrementAndGet();
            pending.clear();
            if (sseTask != null) { sseTask.cancel(true); sseTask = null; }
            for (Listener l : listeners) l.onDetached();
        }
        setState(State.DORMANT);
    }

    private void attach(SessionInfo s) {
        endedLatched = false;
        current = s;
        // Switching sessions: drop any cached state from the previous SID.
        // Otherwise the side panel keeps showing dead threads from the old
        // session and × clicks on them return HTTP 409 from the server.
        cache.clear();
        cacheVersion.incrementAndGet();
        pending.clear();
        setState(State.CONNECTING);
        for (Listener l : listeners) l.onAttached(s);
        openSse(s.sid());
    }

    /**
     * Seed (or re-seed) the per-anchor cache from the bulk threads endpoint.
     * Retries a few times so a transient blip on attach doesn't leave the panel
     * empty until the next SSE event; dedups against the current cache so a
     * re-seed on reconnect doesn't churn listeners for unchanged threads.
     */
    private void seedCache(String sid) {
        for (int attempt = 0; attempt < 3 && !closed; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + sid + "/threads.json"))
                    .timeout(REQUEST_TIMEOUT).GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    applySeed(parseThreadsBulk(resp.body()));
                    return;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void applySeed(Map<String, ThreadState> seeded) {
        for (var e : seeded.entrySet()) {
            ThreadState existing = cache.get(e.getKey());
            ThreadState incoming = e.getValue();
            if (existing != null
                    && existing.synthesis().equals(incoming.synthesis())
                    && existing.version() == incoming.version()) {
                continue; // unchanged — don't re-fire listeners on a reconnect re-seed
            }
            cache.put(e.getKey(), incoming);
            cacheVersion.incrementAndGet();
            for (Listener l : listeners) {
                l.onThreadChanged(e.getKey(), incoming.synthesis(), incoming.version());
            }
        }
    }

    private void openSse(String sid) {
        URI uri = URI.create(baseUrl + "/s/" + sid + "/stream");
        sseTask = exec.submit(() -> {
            // Seed on every (re)connect: covers a failed initial seed and an
            // outage where SSE events were missed while disconnected.
            seedCache(sid);
            try {
                SseClient.connect(http, uri, this::handleSseEvent, t -> {
                    // Don't churn DISCONNECTED/reconnect for a frozen session,
                    // and don't let an SSE drop override PAUSED/ENDED.
                    if (endedLatched) return;
                    if (state == State.ACTIVE) setState(State.DISCONNECTED);
                    scheduleReconnect(sid);
                }).join();
            } catch (Exception e) {
                if (!endedLatched && state == State.ACTIVE) setState(State.DISCONNECTED);
            }
        });
        if (!endedLatched) setState(State.ACTIVE);
    }

    /** Reschedule a reconnect unless we're shutting down — guards against an
     *  in-flight SSE error callback hitting the executor after stop(). */
    private void scheduleReconnect(String sid) {
        if (closed || exec.isShutdown()) return;
        try {
            exec.schedule(() -> openSse(sid), 2, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // stop() raced us between the guard and the schedule — nothing to do.
        }
    }

    private void handleSseEvent(SseClient.Event e) {
        String name = e.name();
        com.google.gson.JsonObject data;
        try {
            data = com.google.gson.JsonParser.parseString(e.data()).getAsJsonObject();
        } catch (Exception ex) {
            return; // non-JSON heartbeat/connected frames
        }
        if ("thread-deleted".equals(name)) {
            String anchor = str(data, "anchor");
            if (anchor.isEmpty()) return;
            cache.remove(anchor);
            cacheVersion.incrementAndGet();
            clearPending(anchor);
            for (Listener l : listeners) l.onThreadDeleted(anchor);
            return;
        }
        if (!"thread-changed".equals(name)) return;
        String anchor = str(data, "anchor");
        if (anchor.isEmpty()) return;
        String synthesis = str(data, "latest_synthesis");
        int version = data.has("version") && !data.get("version").isJsonNull()
            ? data.get("version").getAsInt() : 0;
        String anchorText = str(data, "anchor_text");
        String title = str(data, "title");
        String question = str(data, "question");

        ThreadState existing = cache.get(anchor);
        if (existing != null
                && existing.synthesis().equals(synthesis)
                && existing.version() == version) {
            return;
        }
        String priorAnchorText = existing != null ? existing.anchorText() : "";
        String priorTitle = existing != null ? existing.title() : "";
        String priorQuestion = existing != null ? existing.question() : "";
        ThreadState next = new ThreadState(synthesis, version,
            prefer(anchorText, priorAnchorText),
            prefer(title, priorTitle),
            prefer(question, priorQuestion));
        if (existing != null && existing.synthesis().equals(synthesis)) {
            cache.put(anchor, next);
            cacheVersion.incrementAndGet();
            return;
        }
        cache.put(anchor, next);
        cacheVersion.incrementAndGet();
        clearPending(anchor);
        for (Listener l : listeners) l.onThreadChanged(anchor, synthesis, version);
    }

    /** Keep a previously-seen value if a later event omits the field. */
    private static String prefer(String incoming, String prior) {
        return (incoming != null && !incoming.isEmpty()) ? incoming : prior;
    }

    private void setState(State s) {
        if (state == s) return;
        state = s;
        for (Listener l : listeners) l.onStateChanged(s);
    }

    // --- json helpers ---

    private static String jsonEscape(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static SessionInfo parseFirstSession(String json) {
        com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(json);
        if (!root.isJsonArray() || root.getAsJsonArray().isEmpty()) return null;
        com.google.gson.JsonObject o = root.getAsJsonArray().get(0).getAsJsonObject();
        return new SessionInfo(
            str(o, "sid"), str(o, "pr_ref"), str(o, "title"), str(o, "state_dir"));
    }

    private static Map<String, ThreadState> parseThreadsBulk(String json) {
        Map<String, ThreadState> out = new HashMap<>();
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        for (var e : root.entrySet()) {
            com.google.gson.JsonObject t = e.getValue().getAsJsonObject();
            out.put(e.getKey(), new ThreadState(
                str(t, "latest_synthesis"),
                t.has("version") && !t.get("version").isJsonNull() ? t.get("version").getAsInt() : 0,
                str(t, "anchor_text"),
                str(t, "title"),
                str(t, "question")));
        }
        return out;
    }

    /** Null-safe string field read; returns "" when absent or null. */
    private static String str(com.google.gson.JsonObject o, String key) {
        com.google.gson.JsonElement v = o.get(key);
        return (v == null || v.isJsonNull()) ? "" : v.getAsString();
    }
}
