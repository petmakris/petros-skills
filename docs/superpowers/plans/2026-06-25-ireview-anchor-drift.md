# Anchor Drift Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop interactive-review annotations from silently pointing at the wrong line after edits/rebase — re-locate when the line text is found uniquely nearby, otherwise show a visible "stale" marker.

**Architecture:** The thread gains a stable `anchor_text` field (the annotated line's text, captured once at creation by the server). The IntelliJ client resolves each annotation against the live editor document via a pure `AnchorResolver` (EXACT / MOVED / STALE), indexes the results per diff side via a pure `GutterAnchorIndex`, and the gutter + side-panel render from that index. JSON transport moves from hand-rolled regex to Gson so arbitrary line text round-trips safely.

**Tech Stack:** Python 3 stdlib (server), Java 25 + IntelliJ Platform plugin SDK, Gson, JUnit 5, pytest.

## Global Constraints

- Server runs on Python 3 stdlib only — no new Python dependencies.
- Java language level is **25** (`gradle.properties` `javaVersion=25`, `build.gradle.kts` `JavaLanguageVersion.of(25)`). Do not change it.
- Anchor format is `<project-relative-path>:<L|R>:<line>` (1-based line). Unchanged — `anchor_text` is additive; the anchor key never changes.
- `anchor_text` is **first-write-wins**: set once on the thread's first user submit, never overwritten.
- Re-location compares line text **trimmed** (`String.strip()`); window radius **K = 25** lines (single named constant).
- Legacy threads with no `anchor_text` and the general/no-line anchor are treated as **EXACT** (no drift logic, no migration).
- Tests: server via `pytest` from the repo root; IntelliJ via `./gradlew test` in `intellij-plugin-spike/`.
- Build/stage the plugin with `./reload` (runs `prepareSandbox`); the user restarts IntelliJ to load it.

---

### Task 1: Server — persist `anchor_text` once and expose it

**Files:**
- Modify: `skills/interactive_review/threads.py` (add `set_anchor_text_if_absent`)
- Modify: `skills/interactive_review/server.py` (`handle_submit` ~line 184-220, `threads_bulk` ~line 84-88)
- Test: `skills/interactive_review/tests/test_threads.py`, `skills/interactive_review/tests/test_server.py`

**Interfaces:**
- Produces: `threads.set_anchor_text_if_absent(threads_dir: Path, anchor: str, text: str) -> None`; thread JSON gains top-level `"anchor_text": str`; `threads_bulk` result rows gain `"anchor_text"`.

- [ ] **Step 1: Write the failing test for the threads helper**

In `skills/interactive_review/tests/test_threads.py`, add:

```python
from skills.interactive_review.threads import set_anchor_text_if_absent


def test_set_anchor_text_first_write_wins(tmp_path):
    threads_dir = tmp_path / "threads"
    threads_dir.mkdir()
    append_message(threads_dir, "src/x.py:R:42",
                   {"role": "user", "ts": 1, "text": "why?", "source_event_id": "e1"})
    set_anchor_text_if_absent(threads_dir, "src/x.py:R:42", "    return foo(bar)")
    set_anchor_text_if_absent(threads_dir, "src/x.py:R:42", "DIFFERENT LINE")
    t = load(threads_dir, "src/x.py:R:42")
    assert t["anchor_text"] == "    return foo(bar)"
```

- [ ] **Step 2: Run it to verify it fails**

Run (from the repo root): `python -m pytest skills/interactive_review/tests/test_threads.py::test_set_anchor_text_first_write_wins -q`
Expected: FAIL — `ImportError: cannot import name 'set_anchor_text_if_absent'`.

- [ ] **Step 3: Implement the helper**

In `skills/interactive_review/threads.py`, after `append_message` (after line 66), add:

```python
def set_anchor_text_if_absent(threads_dir: Path, anchor: str, text: str) -> None:
    """Record the anchored line's text once, on first creation (first-write-wins).

    No-op if the thread already has a non-empty anchor_text, or if `text` is
    empty. Used to re-locate a drifted annotation later, client-side.
    """
    if not text:
        return
    t = load(threads_dir, anchor)
    if t.get("anchor_text"):
        return
    t["anchor_text"] = text
    save_atomic(threads_dir, t)
```

- [ ] **Step 4: Run it to verify it passes**

Run: `python -m pytest skills/interactive_review/tests/test_threads.py::test_set_anchor_text_first_write_wins -q`
Expected: PASS.

- [ ] **Step 5: Write the failing server tests**

In `skills/interactive_review/tests/test_server.py`, add:

```python
def test_handle_submit_stores_anchor_text(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    handler = make_handler()
    h.handle_submit(handler, dirs, {"anchor": "src/x.py:R:42", "type": "comment",
                                    "text": "why?", "anchor_text": "  return foo()"})
    t = json.loads(next((dirs["state_dir"] / "threads").iterdir()).read_text())
    assert t["anchor_text"] == "  return foo()"


def test_threads_bulk_returns_anchor_text(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    handler = make_handler()
    h.handle_submit(handler, dirs, {"anchor": "src/x.py:R:42", "type": "comment",
                                    "text": "why?", "anchor_text": "  return foo()"})
    # Give the thread a claude reply so threads_bulk surfaces it.
    from skills.interactive_review import threads as tm
    tm.append_message(dirs["state_dir"] / "threads", "src/x.py:R:42",
                      {"role": "claude", "ts": 2, "text": "because.", "source_event_id": "c1"})
    bulk = h.threads_bulk(dirs)
    assert bulk["src/x.py:R:42"]["anchor_text"] == "  return foo()"
```

- [ ] **Step 6: Run them to verify they fail**

Run: `python -m pytest skills/interactive_review/tests/test_server.py -k anchor_text -q`
Expected: FAIL — submit ignores `anchor_text`; `threads_bulk` row has no `anchor_text` key.

- [ ] **Step 7: Wire `anchor_text` into the server**

In `skills/interactive_review/server.py`, inside `handle_submit`, immediately after the `threads_module.append_message(threads_dir, anchor, {...})` call (after line ~219, before `_send_json(h, 202, ...)`), add:

```python
        anchor_text = payload.get("anchor_text")
        if isinstance(anchor_text, str):
            threads_module.set_anchor_text_if_absent(threads_dir, anchor, anchor_text)
```

In `threads_bulk`, extend the `result[anchor]` dict (currently lines ~84-88) to:

```python
            result[anchor] = {
                "latest_synthesis": last.get("text", ""),
                "version": t.get("version", 0),
                "updated_at": last.get("ts", 0),
                "anchor_text": t.get("anchor_text", ""),
            }
```

- [ ] **Step 8: Run the full server suite**

Run: `python -m pytest skills/interactive_review/tests/ -q`
Expected: PASS (all prior tests + the 3 new ones).

- [ ] **Step 9: Commit**

```bash
git add skills/interactive_review/threads.py skills/interactive_review/server.py skills/interactive_review/tests/
git commit -m "feat(ireview): persist anchor_text on threads for drift detection"
```

---

### Task 2: Client — Gson transport + `anchor_text` end-to-end

**Files:**
- Modify: `intellij-plugin-spike/build.gradle.kts` (add Gson dep, deps block ~line 61-63)
- Modify: `intellij-plugin-spike/src/main/java/com/petros/ireview/ReviewSessionClient.java`
- Modify: `intellij-plugin-spike/src/main/java/com/petros/ireview/SynthesisPopup.java` (submit lambda ~line 190-209)
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/ReviewSessionClientTest.java`
- Test support: `intellij-plugin-spike/src/test/java/com/petros/ireview/FakeReviewServer.java`

**Interfaces:**
- Consumes: server `anchor_text` from Task 1 (in `threads.json` bulk + `thread-changed` SSE payloads).
- Produces: `ReviewSessionClient.ThreadState(String synthesis, int version, String anchorText)`; `ReviewSessionClient.postComment(String anchor, String text, String anchorText)`; submit JSON body now includes `"anchor_text"`.

- [ ] **Step 1: Add the Gson dependency**

In `intellij-plugin-spike/build.gradle.kts`, in the `dependencies { ... }` block at line 61, add a third line:

```kotlin
dependencies {
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("com.google.code.gson:gson:2.11.0")
}
```

- [ ] **Step 2: Add submit-body capture to FakeReviewServer**

In `FakeReviewServer.java`, add a field next to `submitCount`:

```java
    /** Raw body of the last POST that reached /api/submit. */
    public volatile String lastSubmitBody = null;
```

Replace the `/api/submit` handler block so it reads the body before responding:

```java
        if (path.endsWith("/api/submit")) {
            lastSubmitBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            submitCount.incrementAndGet();
            ex.sendResponseHeaders(202, -1);
            ex.close();
            return;
        }
```

- [ ] **Step 3: Write the failing client tests**

In `ReviewSessionClientTest.java`, add:

```java
    @Test
    void exposesAnchorTextAndParsesTrickySynthesis() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PR1\",\"title\":\"t\",\"state_dir\":\"/tmp/x\"}]";
            // Synthesis text full of JSON-hostile characters; anchor_text present.
            server.threadsJson =
                "{\"foo:R:1\":{\"latest_synthesis\":\"a \\\"quote\\\" and {brace}\\nline2\","
              + "\"version\":3,\"anchor_text\":\"  return foo(bar);\"}}";
            CountDownLatch seeded = new CountDownLatch(1);
            ReviewSessionClient client = new ReviewSessionClient(
                server.baseUrl(), "/proj/montblanc", Duration.ofMillis(100));
            client.addListener(new ReviewSessionClient.Listener() {
                @Override public void onThreadChanged(String anchor, String synthesis, int version) {
                    if ("foo:R:1".equals(anchor)) seeded.countDown();
                }
            });
            client.start();
            assertTrue(seeded.await(2, TimeUnit.SECONDS));
            var ts = client.threadFor("foo:R:1").orElseThrow();
            assertEquals("  return foo(bar);", ts.anchorText());
            assertEquals("a \"quote\" and {brace}\nline2", ts.synthesis());
            assertEquals(3, ts.version());
            client.stop();
        }
    }

    @Test
    void postCommentSendsAnchorText() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PR1\",\"title\":\"t\",\"state_dir\":\"/tmp/x\"}]";
            server.watcherSeenAt = System.currentTimeMillis() / 1000;
            CountDownLatch attached = new CountDownLatch(1);
            ReviewSessionClient client = new ReviewSessionClient(
                server.baseUrl(), "/proj/montblanc", Duration.ofMillis(100));
            client.addListener(new ReviewSessionClient.Listener() {
                @Override public void onAttached(ReviewSessionClient.SessionInfo info) {
                    attached.countDown();
                }
            });
            client.start();
            assertTrue(attached.await(2, TimeUnit.SECONDS));
            client.postComment("foo:R:1", "why?", "  return foo(bar);").get(2, TimeUnit.SECONDS);
            assertNotNull(server.lastSubmitBody);
            assertTrue(server.lastSubmitBody.contains("\"anchor_text\""),
                "submit body must carry anchor_text");
            assertTrue(server.lastSubmitBody.contains("return foo(bar);"));
            client.stop();
        }
    }
```

- [ ] **Step 4: Run them to verify they fail**

Run: `cd intellij-plugin-spike && ./gradlew test --tests 'com.petros.ireview.ReviewSessionClientTest' 2>&1 | tail -20`
Expected: FAIL to compile — `anchorText()` and 3-arg `postComment` don't exist.

- [ ] **Step 5: Convert ReviewSessionClient to Gson + anchorText**

In `ReviewSessionClient.java`:

(a) Add a Gson instance field near the other fields (after the `http` client field, ~line 52):

```java
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
```

(b) Change the `ThreadState` record (line ~35) to:

```java
    public record ThreadState(String synthesis, int version, String anchorText) {}
```

(c) Replace `postComment` (current signature `postComment(String anchor, String text)`) with the 3-arg form, building the body with Gson:

```java
    public CompletableFuture<Void> postComment(String anchor, String text, String anchorText) {
        SessionInfo s = current;
        if (s == null) return CompletableFuture.failedFuture(new IllegalStateException("no session"));
        if (state == State.STALE) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "Claude session is gone — re-run /interactive-review to resume"));
        }
        markPending(anchor, true);
        java.util.Map<String, String> payload = new java.util.LinkedHashMap<>();
        payload.put("anchor", anchor);
        payload.put("type", "comment");
        payload.put("text", text);
        payload.put("anchor_text", anchorText == null ? "" : anchorText);
        String body = GSON.toJson(payload);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/s/" + s.sid() + "/api/submit"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .whenComplete((resp, err) -> {
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
```

(d) Replace `parseFirstSession` and `parseThreadsBulk` (lines ~318-336) with Gson versions, and delete the now-unused `jsonField`, `unescapeJsonString` regex helpers:

```java
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
                str(t, "anchor_text")));
        }
        return out;
    }

    /** Null-safe string field read; returns "" when absent or null. */
    private static String str(com.google.gson.JsonObject o, String key) {
        com.google.gson.JsonElement v = o.get(key);
        return (v == null || v.isJsonNull()) ? "" : v.getAsString();
    }
```

(e) Replace the body of `handleSseEvent` parsing (lines ~224-262) to parse the SSE `data` with Gson and carry `anchor_text`:

```java
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
            markPending(anchor, false);
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

        ThreadState existing = cache.get(anchor);
        if (existing != null
                && existing.synthesis().equals(synthesis)
                && existing.version() == version) {
            return;
        }
        if (existing != null && existing.synthesis().equals(synthesis)) {
            cache.put(anchor, new ThreadState(synthesis, version, preferText(anchorText, existing)));
            return;
        }
        cache.put(anchor, new ThreadState(synthesis, version, preferText(anchorText, existing)));
        markPending(anchor, false);
        for (Listener l : listeners) l.onThreadChanged(anchor, synthesis, version);
    }

    /** Keep a previously-seen anchor_text if a later event omits it. */
    private static String preferText(String incoming, ThreadState existing) {
        if (incoming != null && !incoming.isEmpty()) return incoming;
        return existing != null ? existing.anchorText() : "";
    }
```

(f) In `checkWatcherHeartbeat` (added in the prior stale-session work), replace the `jsonField(resp.body(), "watcher_seen_at")` line with Gson:

```java
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
            seenAt = o.has("watcher_seen_at") && !o.get("watcher_seen_at").isJsonNull()
                ? o.get("watcher_seen_at").getAsLong() : 0;
```

- [ ] **Step 6: Update the SynthesisPopup submit call to capture line text**

In `SynthesisPopup.java`, add imports near the top:

```java
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
```

In the `submit` Runnable (line ~196), change the `postComment` call to pass the line's text, and add a helper method:

```java
            String anchorText = lineTextAt(editor.getDocument(), visualLine);
            client.postComment(anchor, q, anchorText).whenComplete((v, t) -> SwingUtilities.invokeLater(() -> {
```

Add near the other private static helpers (e.g. before `wrapHtml`):

```java
    private static String lineTextAt(Document doc, int line0) {
        if (line0 < 0 || line0 >= doc.getLineCount()) return "";
        int s = doc.getLineStartOffset(line0);
        int en = doc.getLineEndOffset(line0);
        return doc.getText(new TextRange(s, en));
    }
```

- [ ] **Step 7: Run the client suite**

Run: `cd intellij-plugin-spike && ./gradlew test 2>&1 | tail -15`
Expected: PASS — all prior tests still green (they use threads/SSE without `anchor_text`, which now parses to `""`), plus the 2 new tests.

- [ ] **Step 8: Commit**

```bash
git add intellij-plugin-spike/build.gradle.kts intellij-plugin-spike/src/main/java/com/petros/ireview/ReviewSessionClient.java intellij-plugin-spike/src/main/java/com/petros/ireview/SynthesisPopup.java intellij-plugin-spike/src/test/java/com/petros/ireview/
git commit -m "feat(ireview): Gson transport + carry anchor_text end-to-end"
```

---

### Task 3: Client — `AnchorResolver` pure function

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/AnchorResolver.java`
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/AnchorResolverTest.java`

**Interfaces:**
- Produces: `AnchorResolver.resolve(List<String> lines, int recordedLine1Based, String anchorText, int k) -> AnchorResolver.Resolution`; `Resolution(Kind kind, int line)` with `Kind {EXACT, MOVED, STALE}` and `line` 1-based (the resolved line for EXACT/MOVED, `-1` for STALE); `int DEFAULT_K = 25`.

- [ ] **Step 1: Write the failing tests**

Create `AnchorResolverTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;
import java.util.List;
import static com.petros.ireview.AnchorResolver.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class AnchorResolverTest {
    private static final int K = 25;

    @Test void exactWhenLineUnchanged() {
        var lines = List.of("a", "  return foo();", "b");
        var r = AnchorResolver.resolve(lines, 2, "return foo();", K);
        assertEquals(EXACT, r.kind());
        assertEquals(2, r.line());
    }

    @Test void movedWhenUniqueNearby() {
        var lines = List.of("new", "a", "  return foo();", "b");
        var r = AnchorResolver.resolve(lines, 2, "return foo();", K);
        assertEquals(MOVED, r.kind());
        assertEquals(3, r.line());
    }

    @Test void staleWhenGone() {
        var lines = List.of("a", "b", "c");
        var r = AnchorResolver.resolve(lines, 2, "return foo();", K);
        assertEquals(STALE, r.kind());
        assertEquals(-1, r.line());
    }

    @Test void staleWhenAmbiguous() {
        var lines = List.of("x", "}", "y", "}", "z");
        var r = AnchorResolver.resolve(lines, 1, "}", K);
        assertEquals(STALE, r.kind());
    }

    @Test void exactWhenAnchorTextBlank() {
        var lines = List.of("a", "b");
        var r = AnchorResolver.resolve(lines, 1, "   ", K);
        assertEquals(EXACT, r.kind());
        assertEquals(1, r.line());
    }

    @Test void staleWhenOutsideWindow() {
        var lines = new java.util.ArrayList<String>();
        for (int i = 0; i < 100; i++) lines.add("filler" + i);
        lines.set(80, "target();"); // 1-based line 81
        var r = AnchorResolver.resolve(lines, 1, "target();", 25); // window 1..26 — out of range
        assertEquals(STALE, r.kind());
    }

    @Test void recordedLinePastEndStillSearches() {
        var lines = List.of("a", "  return foo();", "b");
        var r = AnchorResolver.resolve(lines, 999, "return foo();", K);
        assertEquals(MOVED, r.kind());
        assertEquals(2, r.line());
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd intellij-plugin-spike && ./gradlew test --tests 'com.petros.ireview.AnchorResolverTest' 2>&1 | tail -10`
Expected: FAIL to compile — `AnchorResolver` does not exist.

- [ ] **Step 3: Implement AnchorResolver**

Create `AnchorResolver.java`:

```java
package com.petros.ireview;

import java.util.List;

/**
 * Resolves a recorded annotation anchor against the live document. The anchor's
 * line number drifts when code is edited/rebased; we re-locate it by the line's
 * stored text. Pure and side-effect free so it can be unit-tested in isolation.
 */
public final class AnchorResolver {

    public static final int DEFAULT_K = 25;

    public enum Kind { EXACT, MOVED, STALE }

    /** @param line 1-based resolved line for EXACT/MOVED, -1 for STALE. */
    public record Resolution(Kind kind, int line) {}

    private AnchorResolver() {}

    /**
     * @param lines             document lines (0-indexed list, each without newline)
     * @param recordedLine1Based the anchor's recorded line number (1-based)
     * @param anchorText        the line text captured when the annotation was created
     * @param k                 search radius in lines around the recorded line
     */
    public static Resolution resolve(List<String> lines, int recordedLine1Based,
                                     String anchorText, int k) {
        String needle = anchorText == null ? "" : anchorText.strip();
        // Blank/unknown text isn't matchable — keep today's behavior.
        if (needle.isEmpty()) return new Resolution(Kind.EXACT, recordedLine1Based);

        int idx = recordedLine1Based - 1; // to 0-based
        if (idx >= 0 && idx < lines.size() && lines.get(idx).strip().equals(needle)) {
            return new Resolution(Kind.EXACT, recordedLine1Based);
        }
        int lo = Math.max(0, idx - k);
        int hi = Math.min(lines.size() - 1, idx + k);
        int match = -1;
        for (int i = lo; i <= hi; i++) {
            if (lines.get(i).strip().equals(needle)) {
                if (match != -1) return new Resolution(Kind.STALE, -1); // ambiguous
                match = i;
            }
        }
        if (match == -1) return new Resolution(Kind.STALE, -1);
        return new Resolution(Kind.MOVED, match + 1);
    }
}
```

- [ ] **Step 4: Run to verify they pass**

Run: `cd intellij-plugin-spike && ./gradlew test --tests 'com.petros.ireview.AnchorResolverTest' 2>&1 | tail -10`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add intellij-plugin-spike/src/main/java/com/petros/ireview/AnchorResolver.java intellij-plugin-spike/src/test/java/com/petros/ireview/AnchorResolverTest.java
git commit -m "feat(ireview): AnchorResolver — exact/moved/stale drift resolution"
```

---

### Task 4: Client — `GutterAnchorIndex` pure per-side index

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/GutterAnchorIndex.java`
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/GutterAnchorIndexTest.java`

**Interfaces:**
- Consumes: `AnchorResolver` from Task 3; `ReviewSessionClient.ThreadState` (has `anchorText()`).
- Produces: `GutterAnchorIndex.build(List<String> lines, Map<String,ThreadState> cache, String label, String side, int k) -> Map<Integer, LineAnchor>` keyed by 1-based display line; `record LineAnchor(boolean stale, String ownerAnchor)`.

The index maps a display line → which thread should render there. EXACT/MOVED threads render at their resolved line (`stale=false`); STALE threads render at their **recorded** line (`stale=true`) so the user sees something changed. The `ownerAnchor` is the thread's recorded anchor, so clicks open the right thread regardless of where it's painted.

- [ ] **Step 1: Write the failing tests**

Create `GutterAnchorIndexTest.java`:

```java
package com.petros.ireview;

import com.petros.ireview.ReviewSessionClient.ThreadState;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GutterAnchorIndexTest {
    private static final int K = 25;

    @Test void exactThreadIndexesAtRecordedLine() {
        var lines = List.of("a", "  return foo();", "b");
        var cache = Map.of("file.java:R:2", new ThreadState("syn", 1, "return foo();"));
        var idx = GutterAnchorIndex.build(lines, cache, "file.java", "R", K);
        assertTrue(idx.containsKey(2));
        assertFalse(idx.get(2).stale());
        assertEquals("file.java:R:2", idx.get(2).ownerAnchor());
    }

    @Test void movedThreadIndexesAtNewLine() {
        var lines = List.of("inserted", "a", "  return foo();", "b");
        var cache = Map.of("file.java:R:2", new ThreadState("syn", 1, "return foo();"));
        var idx = GutterAnchorIndex.build(lines, cache, "file.java", "R", K);
        assertTrue(idx.containsKey(3));
        assertFalse(idx.get(3).stale());
        assertFalse(idx.containsKey(2)); // not painted at the old line
    }

    @Test void staleThreadIndexesAtRecordedLineMarkedStale() {
        var lines = List.of("a", "b", "c");
        var cache = Map.of("file.java:R:2", new ThreadState("syn", 1, "return foo();"));
        var idx = GutterAnchorIndex.build(lines, cache, "file.java", "R", K);
        assertTrue(idx.get(2).stale());
    }

    @Test void ignoresOtherFilesAndSides() {
        var lines = List.of("  return foo();");
        var cache = Map.of(
            "other.java:R:1", new ThreadState("s", 1, "return foo();"),
            "file.java:L:1", new ThreadState("s", 1, "return foo();"));
        var idx = GutterAnchorIndex.build(lines, cache, "file.java", "R", K);
        assertTrue(idx.isEmpty());
    }

    @Test void legacyNullAnchorTextTreatedAsExact() {
        var lines = List.of("x", "y");
        var cache = Map.of("file.java:R:1", new ThreadState("s", 1, ""));
        var idx = GutterAnchorIndex.build(lines, cache, "file.java", "R", K);
        assertTrue(idx.containsKey(1));
        assertFalse(idx.get(1).stale());
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd intellij-plugin-spike && ./gradlew test --tests 'com.petros.ireview.GutterAnchorIndexTest' 2>&1 | tail -10`
Expected: FAIL to compile — `GutterAnchorIndex` does not exist.

- [ ] **Step 3: Implement GutterAnchorIndex**

Create `GutterAnchorIndex.java`:

```java
package com.petros.ireview;

import com.petros.ireview.ReviewSessionClient.ThreadState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure index from a display line (1-based) to the annotation thread that should
 * render there, for one diff side of one file. Built fresh from the live
 * document lines + the client's thread cache; consulted by the gutter renderer.
 */
public final class GutterAnchorIndex {

    /** @param ownerAnchor the thread's recorded anchor (for click/tooltip). */
    public record LineAnchor(boolean stale, String ownerAnchor) {}

    private GutterAnchorIndex() {}

    public static Map<Integer, LineAnchor> build(List<String> lines,
                                                 Map<String, ThreadState> cache,
                                                 String label, String side, int k) {
        Map<Integer, LineAnchor> out = new HashMap<>();
        String prefix = label + ":" + side + ":";
        for (var e : cache.entrySet()) {
            String anchor = e.getKey();
            if (!anchor.startsWith(prefix)) continue;
            int recorded;
            try {
                recorded = Integer.parseInt(anchor.substring(prefix.length()));
            } catch (NumberFormatException nfe) {
                continue; // general/non-line anchor
            }
            var res = AnchorResolver.resolve(lines, recorded, e.getValue().anchorText(), k);
            switch (res.kind()) {
                case EXACT, MOVED -> out.put(res.line(), new LineAnchor(false, anchor));
                case STALE -> out.put(recorded, new LineAnchor(true, anchor));
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: Run to verify they pass**

Run: `cd intellij-plugin-spike && ./gradlew test --tests 'com.petros.ireview.GutterAnchorIndexTest' 2>&1 | tail -10`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add intellij-plugin-spike/src/main/java/com/petros/ireview/GutterAnchorIndex.java intellij-plugin-spike/src/test/java/com/petros/ireview/GutterAnchorIndexTest.java
git commit -m "feat(ireview): GutterAnchorIndex — per-side display-line index"
```

---

### Task 5: Client — render drift in the gutter + panel; stage & verify

**Files:**
- Modify: `intellij-plugin-spike/src/main/java/com/petros/ireview/SpikeDiffExtension.java` (`AskGutterRenderer` ~line 172-226)
- Modify: `intellij-plugin-spike/src/main/resources/icons/` (add `annotation_stale.svg`)
- Modify: `intellij-plugin-spike/src/main/java/com/petros/ireview/AnnotationsPanel.java` (`renderCell` ~line 292)
- No new unit test (IDE-bound rendering); verified by build + manual smoke.

**Interfaces:**
- Consumes: `GutterAnchorIndex.build(...)` and `LineAnchor` from Task 4.

This task has no automated test because it touches IntelliJ `Editor`/Swing rendering; the logic it depends on is already covered by Tasks 3–4. Verification is a clean build plus a manual smoke check.

- [ ] **Step 1: Add a stale gutter icon**

Create `intellij-plugin-spike/src/main/resources/icons/annotation_stale.svg` — a greyed (`#9aa0a6`) speech-bubble, the muted sibling of `annotation_yellow.svg`:

```xml
<svg width="16" height="16" viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg">
  <path fill="#9aa0a6" d="M3 3h10a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H7l-3 3v-3H3a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z"/>
</svg>
```

- [ ] **Step 2: Add an editor→lines helper and consult the index in the renderer**

In `SpikeDiffExtension.java`, add a stale icon constant next to `ANNOTATED_ICON` (line ~39):

```java
    private static final Icon STALE_ICON =
            IconLoader.getIcon("/icons/annotation_stale.svg", SpikeDiffExtension.class);
```

Add a private helper to read the editor's lines (place near `isHovered`):

```java
    private static java.util.List<String> documentLines(@NotNull EditorEx editor) {
        Document doc = editor.getDocument();
        int n = doc.getLineCount();
        java.util.List<String> out = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(doc.getText(new com.intellij.openapi.util.TextRange(
                doc.getLineStartOffset(i), doc.getLineEndOffset(i))));
        }
        return out;
    }

    /** The thread (if any) that should render at this 0-based line. */
    private static GutterAnchorIndex.@Nullable LineAnchor lineAnchorFor(
            @NotNull EditorEx editor, @NotNull String label, @NotNull String side,
            int line0, @NotNull Project project) {
        var cache = ReviewSessionService.get(project).client().snapshotCache();
        var index = GutterAnchorIndex.build(documentLines(editor), cache, label, side,
            AnchorResolver.DEFAULT_K);
        return index.get(line0 + 1); // index is 1-based
    }
```

In `AskGutterRenderer`, replace `isAnnotated()` usage. Change `getIcon()`, `getTooltipText()`, and `getClickAction()` to:

```java
        @Override public @NotNull Icon getIcon() {
            var la = lineAnchorFor(editor, label, side, lineZeroBased, project);
            if (la != null) return la.stale() ? STALE_ICON : ANNOTATED_ICON;
            if (isHovered(editor, lineZeroBased)) return ASK_ICON;
            return HIDDEN_ICON;
        }

        @Override public @NotNull String getTooltipText() {
            var la = lineAnchorFor(editor, label, side, lineZeroBased, project);
            if (la != null) {
                return (la.stale() ? "Annotation stale (line changed) · " : "Annotated · ")
                    + la.ownerAnchor();
            }
            return "Comment on " + anchor();
        }

        @Override
        public @Nullable AnAction getClickAction() {
            return new AnAction() {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    var la = lineAnchorFor(editor, label, side, lineZeroBased, project);
                    // Open the thread that lives here (recorded anchor), else a
                    // fresh thread for this line.
                    String target = la != null ? la.ownerAnchor() : anchor();
                    SynthesisPopup.show(project, editor, target, lineZeroBased);
                }
            };
        }
```

Delete the now-unused `isAnnotated()` method.

- [ ] **Step 3: Grey stale rows in the side panel**

In `AnnotationsPanel.java`, the panel can only judge staleness for files whose diff editor is currently open. Add a helper and use it in `renderCell` to grey the row when stale. Add this method to `AnnotationsPanel`:

```java
    /** True if this anchor's thread is currently stale in an open diff editor. */
    private boolean isStale(String anchor) {
        String[] p = anchor.split(":", 3);
        if (p.length < 3) return false;
        var editor = SpikeDiffExtension.editorFor(p[0] + ":" + p[1]);
        if (editor == null) return false;
        var ts = client.threadFor(anchor).orElse(null);
        if (ts == null) return false;
        int recorded;
        try { recorded = Integer.parseInt(p[2]); } catch (NumberFormatException e) { return false; }
        var lines = new ArrayList<String>();
        var doc = editor.getDocument();
        for (int i = 0; i < doc.getLineCount(); i++) {
            lines.add(doc.getText(new com.intellij.openapi.util.TextRange(
                doc.getLineStartOffset(i), doc.getLineEndOffset(i))));
        }
        return AnchorResolver.resolve(lines, recorded, ts.anchorText(), AnchorResolver.DEFAULT_K)
            .kind() == AnchorResolver.Kind.STALE;
    }
```

In `renderCell`, after `pathLbl` is created (line ~312), grey it and append a marker when stale:

```java
        if (isStale(entry.anchor())) {
            pathLbl.setForeground(JBColor.GRAY);
            lineLbl.setText(lineRef + "  ⚠ stale");
        }
```

- [ ] **Step 4: Build, run all tests, and stage the plugin**

Run: `cd intellij-plugin-spike && ./gradlew test 2>&1 | tail -8 && ./reload 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`; all suites green; `✓ Sandbox updated.`

- [ ] **Step 5: Manual smoke verification**

Restart IntelliJ. With an active `/interactive-review` session:
1. Annotate a line, confirm the yellow icon appears (EXACT).
2. Insert a few blank lines above it in the working tree; confirm the icon follows the code line (MOVED) and the thread still opens.
3. Delete the annotated line's content; confirm a greyed stale icon at the original line and a `⚠ stale` row in the panel — no icon on an unrelated line.

Record the result (pass/fail per step) in the commit message.

- [ ] **Step 6: Commit**

```bash
git add intellij-plugin-spike/src/main/java/com/petros/ireview/SpikeDiffExtension.java intellij-plugin-spike/src/main/java/com/petros/ireview/AnnotationsPanel.java intellij-plugin-spike/src/main/resources/icons/annotation_stale.svg
git commit -m "feat(ireview): render drift in gutter + panel (relocate/stale)"
```

---

## Notes for the implementer

- **`HANDOFF.md` line 149** ("End the session: type `scrap it`") is now also doable via the End-review button shipped earlier; if you touch the handoff, update it, but that's out of scope here.
- **Display-only re-location:** never change the thread key. Follow-ups, deletes, and the popup all address the recorded anchor; only the painted line moves.
- **Performance:** `lineAnchorFor` rebuilds the index per `getIcon()` call. For the expected handful of annotations per file this is negligible; if a file ever carries dozens, cache the index per repaint. Not needed now — don't pre-optimize.
