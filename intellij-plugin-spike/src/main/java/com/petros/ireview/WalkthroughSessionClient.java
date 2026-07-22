package com.petros.ireview;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Talks to the walkthrough server: discovers a session by cwd, loads steps.json,
 * opens an SSE stream for per-step threads, and posts questions.
 *
 * <p>Same lifecycle model as {@link ReviewSessionClient} — DORMANT → CONNECTING →
 * ACTIVE, with PAUSED when the watcher heartbeat goes stale and ENDED as a
 * one-way latch once the server reports the session terminal. Listeners fire on
 * the SSE / poll threads; bridge to the EDT in UI code.
 */
public final class WalkthroughSessionClient {

    public record SessionInfo(String sid, String title, String stateDir) {}
    public record ThreadState(String synthesis, int version, String title, String question) {}

    public enum State { DORMANT, CONNECTING, ACTIVE, DISCONNECTED, PAUSED, ENDED }

    public interface Listener {
        default void onAttached(SessionInfo info) {}
        default void onDetached() {}
        default void onStepsChanged(WalkthroughDoc doc) {}
        default void onThreadChanged(String anchor, ThreadState thread) {}
        default void onPendingChanged(String anchor, boolean pending) {}
        default void onStateChanged(State state) {}
    }

    private static final Duration STALE_AFTER = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    private final String baseUrl;
    private final String projectCwd;
    private final Duration pollInterval;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).build();
    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(2);
    private final ExecutorService sseExec = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "walkthrough-sse");
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong sseGen = new AtomicLong();
    private final AtomicLong submitSeq = new AtomicLong();
    private final Object stateLock = new Object();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, ThreadState> threads = new ConcurrentHashMap<>();
    private final Map<String, Long> pending = new ConcurrentHashMap<>();
    private final AtomicReference<WalkthroughDoc> doc = new AtomicReference<>(WalkthroughDoc.EMPTY);

    private volatile boolean closed = false;
    private volatile boolean endedLatched = false;
    private volatile State state = State.DORMANT;
    private volatile SessionInfo current = null;
    private volatile Future<?> sseTask = null;
    private volatile ScheduledFuture<?> discoverTask = null;

    public WalkthroughSessionClient(String baseUrl, String projectCwd, Duration pollInterval) {
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
        sseGen.incrementAndGet();
        if (discoverTask != null) discoverTask.cancel(true);
        if (sseTask != null) sseTask.cancel(true);
        exec.shutdownNow();
        sseExec.shutdownNow();
        setState(State.DORMANT);
    }

    public void addListener(Listener l) { listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    public Optional<SessionInfo> currentSession() { return Optional.ofNullable(current); }

    public State state() { return state; }

    public WalkthroughDoc doc() { return doc.get(); }

    public Optional<ThreadState> threadFor(String anchor) {
        return Optional.ofNullable(threads.get(anchor));
    }

    public boolean isPending(String anchor) { return pending.containsKey(anchor); }

    /** POST a question on a step to /s/&lt;sid&gt;/api/submit. */
    public CompletableFuture<Void> postAsk(int stepId, String text) {
        SessionInfo s = current;
        if (s == null) return CompletableFuture.failedFuture(new IllegalStateException("no session"));
        if (state == State.PAUSED || state == State.ENDED) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "Claude session is gone — re-run /walkthrough to resume"));
        }
        String anchor = "step:" + stepId;
        long token = submitSeq.incrementAndGet();
        markPending(anchor, token);
        Map<String, String> payload = new java.util.LinkedHashMap<>();
        payload.put("anchor", anchor);
        payload.put("type", "comment");
        payload.put("text", text);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + s.sid() + "/api/submit"))
            .header("Content-Type", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
            .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .whenComplete((resp, err) -> {
                if (err != null || (resp != null && resp.statusCode() / 100 != 2)) {
                    clearPendingIfToken(anchor, token);
                }
            })
            .thenAccept(resp -> {
                if (resp.statusCode() / 100 != 2) {
                    throw new RuntimeException("ask failed: HTTP " + resp.statusCode());
                }
            });
    }

    /** POST to /s/&lt;sid&gt;/api/cancel — ends the walkthrough. */
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

    // --- internal ---

    private void markPending(String anchor, long token) {
        if (pending.put(anchor, token) == null) {
            for (Listener l : listeners) l.onPendingChanged(anchor, true);
        }
    }

    private void clearPending(String anchor) {
        if (pending.remove(anchor) != null) {
            for (Listener l : listeners) l.onPendingChanged(anchor, false);
        }
    }

    private void clearPendingIfToken(String anchor, long token) {
        if (pending.remove(anchor, token)) {
            for (Listener l : listeners) l.onPendingChanged(anchor, false);
        }
    }

    private void pollDiscover() {
        SessionInfo found;
        try {
            found = fetchNewestSession();
        } catch (Exception e) {
            if (!endedLatched) handleNoSession();
            return;
        }
        if (endedLatched) {
            if (found != null && (current == null || !current.sid().equals(found.sid()))) attach(found);
            return;
        }
        if (found == null) {
            if (current != null) {
                pollLiveness(current.sid());
                if (!endedLatched) handleNoSession();
            } else {
                handleNoSession();
            }
            return;
        }
        if (current == null || !current.sid().equals(found.sid())) attach(found);
        pollLiveness(found.sid());
    }

    private SessionInfo fetchNewestSession() throws Exception {
        String url = baseUrl + "/api/sessions?cwd="
            + URLEncoder.encode(projectCwd, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;
        var root = JsonParser.parseString(resp.body());
        if (!root.isJsonArray() || root.getAsJsonArray().isEmpty()) return null;
        JsonObject o = root.getAsJsonArray().get(0).getAsJsonObject();
        return new SessionInfo(str(o, "sid"), str(o, "title"), str(o, "state_dir"));
    }

    private void pollLiveness(String sid) {
        if (endedLatched) return;
        long seenAt;
        boolean ended;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + sid + "/poll"))
                .timeout(REQUEST_TIMEOUT).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return;
            JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
            seenAt = o.has("watcher_seen_at") && !o.get("watcher_seen_at").isJsonNull()
                ? o.get("watcher_seen_at").getAsLong() : 0;
            ended = o.has("ended") && !o.get("ended").isJsonNull() && o.get("ended").getAsBoolean();
        } catch (Exception e) {
            return;
        }
        if (ended) { latchEnded(); return; }
        if (seenAt <= 0) return;
        long ageMs = System.currentTimeMillis() - seenAt * 1000;
        if (ageMs > STALE_AFTER.toMillis()) {
            if (state != State.PAUSED) {
                for (String a : new java.util.ArrayList<>(pending.keySet())) clearPending(a);
                setState(State.PAUSED);
            }
        } else if (state == State.PAUSED) {
            setState(State.ACTIVE);
        }
    }

    private void latchEnded() {
        endedLatched = true;
        sseGen.incrementAndGet();
        for (String a : new java.util.ArrayList<>(pending.keySet())) clearPending(a);
        if (sseTask != null) { sseTask.cancel(true); sseTask = null; }
        setState(State.ENDED);
    }

    private void handleNoSession() {
        endedLatched = false;
        if (current != null) {
            current = null;
            threads.clear();
            pending.clear();
            doc.set(WalkthroughDoc.EMPTY);
            sseGen.incrementAndGet();
            if (sseTask != null) { sseTask.cancel(true); sseTask = null; }
            for (Listener l : listeners) l.onDetached();
            for (Listener l : listeners) l.onStepsChanged(WalkthroughDoc.EMPTY);
        }
        setState(State.DORMANT);
    }

    private void attach(SessionInfo s) {
        endedLatched = false;
        current = s;
        threads.clear();
        pending.clear();
        doc.set(WalkthroughDoc.EMPTY);
        setState(State.CONNECTING);
        for (Listener l : listeners) l.onAttached(s);
        openSse(s.sid());
    }

    /** GET steps.json and publish it if it actually changed. */
    private void loadSteps(String sid) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + sid + "/steps.json"))
                .timeout(REQUEST_TIMEOUT).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return;
            WalkthroughDoc next = WalkthroughDoc.parse(resp.body());
            WalkthroughDoc prev = doc.get();
            if (prev.generatedTs() == next.generatedTs()
                    && prev.steps().size() == next.steps().size()) {
                return;
            }
            doc.set(next);
            for (Listener l : listeners) l.onStepsChanged(next);
        } catch (Exception ignored) {
            // transient — the next steps-changed event or reconnect retries
        }
    }

    private void seedThreads(String sid) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + sid + "/threads.json"))
                .timeout(REQUEST_TIMEOUT).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return;
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            for (var e : root.entrySet()) {
                JsonObject t = e.getValue().getAsJsonObject();
                applyThread(e.getKey(), toThreadState(t));
            }
        } catch (Exception ignored) {
        }
    }

    private void openSse(String sid) {
        if (closed || sseExec.isShutdown()) return;
        URI uri = URI.create(baseUrl + "/s/" + sid + "/stream");
        long gen = sseGen.incrementAndGet();
        Future<?> prev = sseTask;
        if (prev != null) prev.cancel(true);
        try {
            sseTask = sseExec.submit(() -> runSse(sid, uri, gen));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void runSse(String sid, URI uri, long gen) {
        loadSteps(sid);
        seedThreads(sid);
        if (gen != sseGen.get() || closed) return;
        if (!endedLatched) setState(State.ACTIVE);
        try {
            SseClient.connect(http, uri,
                ev -> { if (gen == sseGen.get()) handleSseEvent(sid, ev); },
                t -> { if (gen == sseGen.get() && !endedLatched && state == State.ACTIVE)
                           setState(State.DISCONNECTED); }
            ).join();
        } catch (Throwable ignored) {
        }
        if (gen == sseGen.get() && !closed && !endedLatched) {
            if (state == State.ACTIVE) setState(State.DISCONNECTED);
            scheduleReconnect(sid, gen);
        }
    }

    private void scheduleReconnect(String sid, long gen) {
        if (closed || exec.isShutdown() || gen != sseGen.get()) return;
        try {
            exec.schedule(() -> { if (gen == sseGen.get() && !closed) openSse(sid); },
                2, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void handleSseEvent(String sid, SseClient.Event e) {
        String name = e.name();
        if ("steps-changed".equals(name)) {
            loadSteps(sid);
            return;
        }
        JsonObject data;
        try {
            data = JsonParser.parseString(e.data()).getAsJsonObject();
        } catch (Exception ex) {
            return;
        }
        String anchor = str(data, "anchor");
        if (anchor.isEmpty()) return;
        if ("thread-deleted".equals(name)) {
            threads.remove(anchor);
            clearPending(anchor);
            for (Listener l : listeners) l.onThreadChanged(anchor, null);
            return;
        }
        if (!"thread-changed".equals(name)) return;
        applyThread(anchor, toThreadState(data));
    }

    private ThreadState toThreadState(JsonObject o) {
        int version = o.has("version") && !o.get("version").isJsonNull()
            ? o.get("version").getAsInt() : 0;
        return new ThreadState(str(o, "latest_synthesis"), version,
            str(o, "title"), str(o, "question"));
    }

    private void applyThread(String anchor, ThreadState next) {
        ThreadState existing = threads.get(anchor);
        if (existing != null && existing.version() == next.version()
                && existing.synthesis().equals(next.synthesis())) {
            return;
        }
        threads.put(anchor, next);
        clearPending(anchor);
        for (Listener l : listeners) l.onThreadChanged(anchor, next);
    }

    private void setState(State s) {
        synchronized (stateLock) {
            if (state == s) return;
            state = s;
        }
        for (Listener l : listeners) l.onStateChanged(s);
    }

    private static String str(JsonObject o, String key) {
        var v = o.get(key);
        return (v == null || v.isJsonNull()) ? "" : v.getAsString();
    }
}
