# Panel Summary Titles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the unreadable raw-markdown excerpt in each annotations-panel row with a clean, agent-written summary title (with a fallback chain), and redesign the row to lead with that title.

**Architecture:** The server stores an agent-written `title` on the thread and exposes `title` + `question` (first user message) in `threads_bulk`. The IntelliJ client carries both on `ThreadState`, resolves a row label through a pure fallback chain (`title → question → plain-text(synthesis) → anchor`), and the panel renders title-primary + muted meta. Markdown is flattened to text with the already-bundled commonmark `TextContentRenderer`.

**Tech Stack:** Python 3 stdlib (server), Java 25 + IntelliJ Platform plugin SDK, commonmark (already a dependency), Gson, JUnit 5, pytest.

## Global Constraints

- Server runs on Python 3 standard library only — no new Python dependencies.
- Java language level is **25** (`gradle.properties`, `build.gradle.kts`). Do not change it. No new Java dependency — commonmark (`org.commonmark`) and Gson are already on the classpath.
- `title` is **last-write-wins**: each Claude answer refreshes it; a non-empty `title` overwrites a prior one; an absent/empty `title` never creates or clears the field.
- `threads_bulk` rows gain `"title"` (agent headline, `""` if unset) and `"question"` (text of the FIRST `role=="user"` message, `""` if none).
- Panel row label resolves through the chain **`title → question → first line of plain-text(synthesis) → anchor`**; whitespace-only rungs are skipped.
- Markdown→plain-text uses `org.commonmark.renderer.text.TextContentRenderer` with the GFM `TablesExtension` (already bundled); no new dependency.
- Anchor format `<path>:<L|R>:<line>` is unchanged; `title`/`question` are additive. Legacy threads (no title) and the general anchor degrade via the fallback chain. No migration.
- Tests: server via `pytest` from the repo root; IntelliJ via `./gradlew test` in `intellij-plugin-spike/`. Stage the plugin with `./reload`.
- Commits are trailer-free (no "Co-Authored-By"/"Claude-Session"/"Generated with Claude"); a pre-commit scanner runs — address real flags rather than blanket `--no-verify`.

---

### Task 1: Server — store `title`, expose `title` + `question`, instruct the agent

**Files:**
- Modify: `skills/interactive_review/threads.py` (`append_message`, ~line 55-66)
- Modify: `skills/interactive_review/server.py` (`threads_bulk`, ~line 80-89)
- Modify: `skills/interactive_review/SKILL.md` (Mode D step 4 ~line 152-166; response style guide ~line 167-200)
- Test: `skills/interactive_review/tests/test_threads.py`, `skills/interactive_review/tests/test_server.py`

**Interfaces:**
- Produces: `append_message(threads_dir, anchor, msg, title=None)`; thread JSON gains top-level `"title"`; `threads_bulk` rows gain `"title"` and `"question"`.

- [ ] **Step 1: Write the failing threads test**

In `skills/interactive_review/tests/test_threads.py`, add:

```python
def test_append_message_sets_title_last_write_wins(tmp_path):
    threads_dir = tmp_path / "threads"
    threads_dir.mkdir()
    append_message(threads_dir, "src/x.py:R:42",
                   {"role": "claude", "ts": 1, "text": "a", "source_event_id": "c1"},
                   title="First headline")
    append_message(threads_dir, "src/x.py:R:42",
                   {"role": "claude", "ts": 2, "text": "b", "source_event_id": "c2"},
                   title="Second headline")
    assert load(threads_dir, "src/x.py:R:42")["title"] == "Second headline"


def test_append_message_without_title_leaves_it_untouched(tmp_path):
    threads_dir = tmp_path / "threads"
    threads_dir.mkdir()
    append_message(threads_dir, "src/x.py:R:42",
                   {"role": "claude", "ts": 1, "text": "a", "source_event_id": "c1"},
                   title="Keep me")
    append_message(threads_dir, "src/x.py:R:42",
                   {"role": "user", "ts": 2, "text": "follow-up?", "source_event_id": "u1"})
    t = load(threads_dir, "src/x.py:R:42")
    assert t["title"] == "Keep me"
```

- [ ] **Step 2: Run to verify failure**

Run (from the repo root): `python -m pytest skills/interactive_review/tests/test_threads.py -k title -q`
Expected: FAIL — `append_message()` got an unexpected keyword argument `title`.

- [ ] **Step 3: Add the `title` kwarg to `append_message`**

In `skills/interactive_review/threads.py`, change `append_message` (lines 55-66) to:

```python
def append_message(threads_dir: Path, anchor: str, msg: dict, title: str | None = None) -> bool:
    """Append a message; dedup by source_event_id.  Returns True if appended.

    If `title` is a non-empty string, set the thread's top-level `title`
    (last-write-wins) — the agent's short headline shown in the IDE panel.
    """
    t = load(threads_dir, anchor)
    seid = msg.get("source_event_id")
    if seid is not None:
        for existing in t["messages"]:
            if existing.get("source_event_id") == seid:
                return False
    t["messages"].append(msg)
    t["version"] = int(t.get("version", 0)) + 1
    if title:
        t["title"] = title
    save_atomic(threads_dir, t)
    return True
```

Note: dedup still returns early (no title write) when the event was already processed — correct, the title rides with the message that carries it.

- [ ] **Step 4: Run to verify pass**

Run: `python -m pytest skills/interactive_review/tests/test_threads.py -k title -q`
Expected: PASS.

- [ ] **Step 5: Write the failing server test**

In `skills/interactive_review/tests/test_server.py`, add:

```python
def test_threads_bulk_returns_title_and_question(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    handler = make_handler()
    # User asks (creates the thread with a question), then Claude answers with a title.
    h.handle_submit(handler, dirs, {"anchor": "src/x.py:R:42", "type": "comment",
                                    "text": "why is this null-checked?"})
    from skills.interactive_review import threads as tm
    tm.append_message(dirs["state_dir"] / "threads", "src/x.py:R:42",
                      {"role": "claude", "ts": 9, "text": "Because foo() can return null.",
                       "source_event_id": "c1"},
                      title="Null check on foo()")
    bulk = h.threads_bulk(dirs)
    row = bulk["src/x.py:R:42"]
    assert row["title"] == "Null check on foo()"
    assert row["question"] == "why is this null-checked?"


def test_threads_bulk_defaults_title_and_question_empty(tmp_path):
    dirs = make_dirs(tmp_path)
    h = Handlers()
    threads_dir = dirs["state_dir"] / "threads"
    from skills.interactive_review import threads as tm
    # Claude-origin thread: a claude message, no user message, no title.
    tm.append_message(threads_dir, "src/x.py:R:7",
                      {"role": "claude", "ts": 1, "text": "finding", "source_event_id": "c1"})
    row = h.threads_bulk(dirs)["src/x.py:R:7"]
    assert row["title"] == ""
    assert row["question"] == ""
```

- [ ] **Step 6: Run to verify failure**

Run: `python -m pytest skills/interactive_review/tests/test_server.py -k "title_and_question or title_and_question_empty" -q`
Expected: FAIL — `threads_bulk` rows have no `title`/`question` keys.

- [ ] **Step 7: Add `title` + `question` to `threads_bulk`**

In `skills/interactive_review/server.py`, in `threads_bulk`, replace the `result[anchor] = {...}` block (currently with `latest_synthesis`/`version`/`updated_at`/`anchor_text`) so it also computes and includes `title` and `question`:

```python
            user_msgs = [m for m in t.get("messages", []) if m.get("role") == "user"]
            result[anchor] = {
                "latest_synthesis": last.get("text", ""),
                "version": t.get("version", 0),
                "updated_at": last.get("ts", 0),
                "anchor_text": t.get("anchor_text", ""),
                "title": t.get("title", ""),
                "question": user_msgs[0].get("text", "") if user_msgs else "",
            }
```

- [ ] **Step 8: Run the full server suite**

Run: `python -m pytest skills/interactive_review/tests/ -q`
Expected: PASS (all prior tests + the 4 new ones).

- [ ] **Step 9: Instruct the agent to write a title (SKILL.md)**

In `skills/interactive_review/SKILL.md`, Mode D step 4, change the `append_message(...)` snippet so the call passes a `title` (add the kwarg after the message dict):

```python
   append_message(Path('$STATE_DIR/threads'), '$ANCHOR', {
       'role': 'claude',
       'ts': $(date +%s),
       'text': '''<your answer>''',
       'source_event_id': '$EVENT_ID',
   }, title='''<short headline>''')
```

In the "Response style guide" section, add one bullet:

```markdown
- **Headline title.** Pass a `title` to `append_message`: plain text (no
  markdown), ≤ ~6 words / 60 chars, a noun phrase naming the thread's topic
  (e.g. "Null check on portfolio lookup", "Why the fee branch is skipped").
  Refresh it each answer so it stays accurate as the synthesis absorbs new
  questions. The IDE panel shows this as the row's title.
```

- [ ] **Step 10: Commit**

```bash
git add skills/interactive_review/threads.py skills/interactive_review/server.py skills/interactive_review/SKILL.md skills/interactive_review/tests/
git commit -m "feat(ireview): agent-written thread title + question in threads_bulk"
```

---

### Task 2: Client — carry `title` + `question` on `ThreadState`

**Files:**
- Modify: `intellij-plugin-spike/src/main/java/com/petros/ireview/ReviewSessionClient.java` (`ThreadState` line 33; `parseThreadsBulk` ~363-374; `handleSseEvent` ~314-335; `preferText` ~337-341)
- Modify: `intellij-plugin-spike/src/test/java/com/petros/ireview/GutterAnchorIndexTest.java` (8 `new ThreadState(...)` sites)
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/ReviewSessionClientTest.java`

**Interfaces:**
- Consumes: server `title`/`question` from Task 1 (in `threads.json` bulk + `thread-changed` SSE).
- Produces: `ReviewSessionClient.ThreadState(String synthesis, int version, String anchorText, String title, String question)` with accessors `title()` and `question()`.

- [ ] **Step 1: Write the failing client test**

In `ReviewSessionClientTest.java`, add:

```java
    @Test
    void exposesTitleAndQuestion() throws Exception {
        try (FakeReviewServer server = new FakeReviewServer()) {
            server.sessionsJson =
                "[{\"sid\":\"abc\",\"pr_ref\":\"PR1\",\"title\":\"t\",\"state_dir\":\"/tmp/x\"}]";
            server.threadsJson =
                "{\"foo:R:1\":{\"latest_synthesis\":\"because **foo** is null\",\"version\":2,"
              + "\"anchor_text\":\"return foo();\",\"title\":\"Null check on foo\","
              + "\"question\":\"why null-checked?\"}}";
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
            assertEquals("Null check on foo", ts.title());
            assertEquals("why null-checked?", ts.question());
            client.stop();
        }
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `cd intellij-plugin-spike && ./gradlew test --tests 'com.petros.ireview.ReviewSessionClientTest' 2>&1 | tail -15`
Expected: FAIL to compile — `ThreadState` has no `title()`/`question()`.

- [ ] **Step 3: Extend `ThreadState` and the parsers**

In `ReviewSessionClient.java`:

(a) Change the record (line 33):

```java
    public record ThreadState(String synthesis, int version, String anchorText,
                              String title, String question) {}
```

(b) Replace `parseThreadsBulk` (lines 363-374) body's `out.put(...)`:

```java
            out.put(e.getKey(), new ThreadState(
                str(t, "latest_synthesis"),
                t.has("version") && !t.get("version").isJsonNull() ? t.get("version").getAsInt() : 0,
                str(t, "anchor_text"),
                str(t, "title"),
                str(t, "question")));
```

(c) Replace the `handleSseEvent` tail (from `String anchorText = str(data, "anchor_text");` through the second `cache.put(...)`/notify, lines 320-334) with:

```java
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
            return;
        }
        cache.put(anchor, next);
        markPending(anchor, false);
        for (Listener l : listeners) l.onThreadChanged(anchor, synthesis, version);
    }
```

(d) Replace `preferText` (lines 337-341) with the generic `prefer`:

```java
    /** Keep a previously-seen value if a later event omits the field. */
    private static String prefer(String incoming, String prior) {
        return (incoming != null && !incoming.isEmpty()) ? incoming : prior;
    }
```

- [ ] **Step 4: Update the `ThreadState` construction sites in `GutterAnchorIndexTest`**

In `GutterAnchorIndexTest.java`, append `, "", ""` to every `new ThreadState(...)` (title/question are irrelevant to that test). The 8 sites become, e.g.:

```java
new ThreadState("syn", 1, "return foo();", "", "")
new ThreadState("s", 1, "return foo();", "", "")
new ThreadState("s", 1, "", "", "")
new ThreadState("s", 1, "exact text here", "", "")
new ThreadState("s", 1, "no such line text", "", "")
```

Apply to all 8 occurrences (lines 14, 23, 32, 40, 41, 48, 60, 61, 75, 76 — match each existing arg list and add the two empty strings).

- [ ] **Step 5: Run the full client suite**

Run: `cd intellij-plugin-spike && ./gradlew test 2>&1 | tail -15`
Expected: PASS — prior tests green (they omit `title`/`question`, which parse to `""`) plus `exposesTitleAndQuestion`.

- [ ] **Step 6: Commit**

```bash
git add intellij-plugin-spike/src/main/java/com/petros/ireview/ReviewSessionClient.java intellij-plugin-spike/src/test/java/com/petros/ireview/
git commit -m "feat(ireview): carry title + question on ThreadState"
```

---

### Task 3: Client — `PanelRowTitle` (fallback resolver + markdown→plain-text)

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/PanelRowTitle.java`
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/PanelRowTitleTest.java`

**Interfaces:**
- Produces: `PanelRowTitle.resolve(String title, String question, String synthesis, String anchor) -> String`; `PanelRowTitle.firstLinePlainText(String markdown) -> String`.

- [ ] **Step 1: Write the failing tests**

Create `PanelRowTitleTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PanelRowTitleTest {

    @Test void titleWinsWhenPresent() {
        assertEquals("My Title",
            PanelRowTitle.resolve("My Title", "the question", "the **synthesis**", "a:R:1"));
    }

    @Test void questionWhenNoTitle() {
        assertEquals("the question",
            PanelRowTitle.resolve("", "the question", "the synthesis", "a:R:1"));
    }

    @Test void synthesisFirstLineWhenNoTitleOrQuestion() {
        assertEquals("Because foo is null",
            PanelRowTitle.resolve("  ", "", "Because **foo** is null\n\nmore detail", "a:R:1"));
    }

    @Test void anchorWhenEverythingBlank() {
        assertEquals("a:R:1", PanelRowTitle.resolve("", "", "   ", "a:R:1"));
    }

    @Test void whitespaceOnlyRungsAreSkipped() {
        assertEquals("real", PanelRowTitle.resolve("\t\n", "  ", "real", "a:R:1"));
    }

    @Test void firstLineStripsBoldCodeAndLinks() {
        String md = "Use `foo()` and **bar**, see [the file](src/X.java:10).";
        String out = PanelRowTitle.firstLinePlainText(md);
        assertFalse(out.contains("**"));
        assertFalse(out.contains("`"));
        assertFalse(out.contains("src/X.java"));   // link target (url) gone
        assertFalse(out.contains("]("));           // link markup gone
        assertTrue(out.contains("foo()"));         // code text (with its parens) kept
        assertTrue(out.contains("bar"));
        assertTrue(out.contains("the file"));  // link label kept
    }

    @Test void firstLineTakesHeadingThenStops() {
        assertEquals("Heading", PanelRowTitle.firstLinePlainText("# Heading\n\nbody text"));
    }

    @Test void firstLineOfBlankIsEmpty() {
        assertEquals("", PanelRowTitle.firstLinePlainText("   \n  "));
        assertEquals("", PanelRowTitle.firstLinePlainText(null));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd intellij-plugin-spike && ./gradlew test --tests 'com.petros.ireview.PanelRowTitleTest' 2>&1 | tail -10`
Expected: FAIL to compile — `PanelRowTitle` does not exist.

- [ ] **Step 3: Implement `PanelRowTitle`**

Create `PanelRowTitle.java`:

```java
package com.petros.ireview;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;

import java.util.List;

/**
 * Resolves the single readable label for an annotations-panel row.
 *
 * Pure and Swing-free. A thread's row leads with a clean title; this picks it
 * via a fallback chain and flattens markdown to plain text for the synthesis
 * rung. Uses the commonmark dependency already bundled in the plugin.
 */
public final class PanelRowTitle {

    private static final List<TablesExtension> EXT = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXT).build();
    private static final TextContentRenderer TEXT =
        TextContentRenderer.builder().extensions(EXT).build();

    private PanelRowTitle() {}

    /**
     * Row label fallback chain: title → question → first line of plain-text
     * synthesis → anchor. Whitespace-only rungs are skipped.
     */
    public static String resolve(String title, String question, String synthesis, String anchor) {
        String t = collapse(title);
        if (!t.isEmpty()) return t;
        String q = collapse(question);
        if (!q.isEmpty()) return q;
        String s = firstLinePlainText(synthesis);
        if (!s.isEmpty()) return s;
        return anchor == null ? "" : anchor;
    }

    /** Flatten markdown to plain text and return its first non-blank line. */
    public static String firstLinePlainText(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        String text = TEXT.render(PARSER.parse(markdown));
        for (String line : text.split("\n")) {
            String t = collapse(line);
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    private static String collapse(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `cd intellij-plugin-spike && ./gradlew test --tests 'com.petros.ireview.PanelRowTitleTest' 2>&1 | tail -10`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add intellij-plugin-spike/src/main/java/com/petros/ireview/PanelRowTitle.java intellij-plugin-spike/src/test/java/com/petros/ireview/PanelRowTitleTest.java
git commit -m "feat(ireview): PanelRowTitle — row-label fallback chain + markdown→text"
```

---

### Task 4: Client — redesign the panel row (title-primary), stage & verify

**Files:**
- Modify: `intellij-plugin-spike/src/main/java/com/petros/ireview/AnnotationEntry.java` (rename `snippet` → `title`)
- Modify: `intellij-plugin-spike/src/main/java/com/petros/ireview/AnnotationsPanel.java` (`rebuild` ~270-290; `renderCell` ~292-365; remove `snippet()` ~530 and `escapeHtml()` ~545)
- No new automated unit test (Swing-bound); verified by compile + full suite green + `./reload` + manual smoke.

**Interfaces:**
- Consumes: `ThreadState.title()/question()/synthesis()` (Task 2); `PanelRowTitle.resolve(...)` (Task 3).

- [ ] **Step 1: Rename `AnnotationEntry.snippet` → `title`**

Replace `AnnotationEntry.java` body with (doc updated, field renamed):

```java
package com.petros.ireview;

/**
 * One row in the annotations side panel.
 *
 * @param anchor    Full anchor string, e.g. "src/.../Foo.java:R:37".
 * @param title     The resolved row label (agent title, or a fallback).
 * @param version   Thread version (monotonically increasing per anchor).
 * @param updatedAt Server-side updated_at timestamp (epoch seconds).
 * @param isNew     True if this row's version exceeds the panel's last-seen
 *                  version. Drives the "updated" dot.
 */
public record AnnotationEntry(
    String anchor,
    String title,
    int version,
    long updatedAt,
    boolean isNew
) {}
```

- [ ] **Step 2: Build the row label in `rebuild`**

In `AnnotationsPanel.java`, in `rebuild`, replace the `rows.add(new AnnotationEntry(...))` block (lines 278-284) with:

```java
            rows.add(new AnnotationEntry(
                anchor,
                PanelRowTitle.resolve(thread.title(), thread.question(), thread.synthesis(), anchor),
                thread.version(),
                0L,
                thread.version() > last
            ));
```

- [ ] **Step 3: Redesign `renderCell`**

Replace the entire `renderCell` method (lines 292-365) with:

```java
    private Component renderCell(JList<? extends AnnotationEntry> jbList,
                                 AnnotationEntry entry,
                                 int index,
                                 boolean selected,
                                 boolean focused) {
        JPanel row = new JPanel(new BorderLayout(0, 3));
        row.setBorder(JBUI.Borders.empty(8, 10));
        row.setOpaque(true);
        row.setBackground(selected
            ? new JBColor(new Color(0x1a, 0x3a, 0x5e), new Color(0x1a, 0x3a, 0x5e))
            : new JBColor(new Color(0xf0, 0xf0, 0xf0), new Color(0x23, 0x25, 0x27)));

        boolean stale = isStale(entry.anchor());

        // Title line: clean summary title (WEST) + ×/spinner (EAST).
        JPanel titleLine = new JPanel(new BorderLayout());
        titleLine.setOpaque(false);
        JLabel titleLbl = new JLabel(truncate(entry.title(), 64));
        titleLbl.setFont(titleLbl.getFont().deriveFont(Font.BOLD, 12.5f));
        titleLbl.setForeground(stale ? JBColor.GRAY
            : (selected ? new Color(0xe8, 0xe8, 0xe8) : new Color(0xd0, 0xd2, 0xd6)));
        titleLine.add(titleLbl, BorderLayout.WEST);

        JPanel rightCluster = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightCluster.setOpaque(false);
        if (deleting.contains(entry.anchor())) {
            JLabel s = new JLabel(SPINNER_FRAMES[spinFrame]);
            s.setBorder(JBUI.Borders.empty(2, 6));
            rightCluster.add(s);
        } else if (index == hoveredIndex && !client.isPending(entry.anchor())) {
            rightCluster.add(makeDeleteButtonVisual(hoveringDeleteButton));
        }
        titleLine.add(rightCluster, BorderLayout.EAST);

        // Meta line: file:side:line (muted, WEST) + v# / state (EAST).
        String[] parts = entry.anchor().split(":", 3);
        String pathOnly = parts.length >= 1 ? lastSegment(parts[0]) : entry.anchor();
        String lineRef = parts.length >= 3 ? ":" + parts[1] + ":" + parts[2] : "";
        JLabel locLbl = new JLabel(pathOnly + lineRef);
        locLbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10.5f));
        locLbl.setForeground(stale ? JBColor.GRAY : new Color(0x8a, 0x8d, 0x93));

        JPanel rightMeta = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightMeta.setOpaque(false);
        JLabel verLbl = new JLabel("v" + entry.version());
        verLbl.setForeground(new Color(0x80, 0x80, 0x80));
        verLbl.setFont(verLbl.getFont().deriveFont(10f));
        rightMeta.add(verLbl);
        if (stale) {
            JLabel st = new JLabel("⚠ stale");
            st.setForeground(JBColor.GRAY);
            st.setFont(st.getFont().deriveFont(10f));
            rightMeta.add(st);
        } else if (entry.isNew()) {
            JLabel dot = new JLabel("●");
            dot.setForeground(new Color(0xf1, 0xc4, 0x0f));
            dot.setFont(dot.getFont().deriveFont(12f));
            rightMeta.add(dot);
        }

        JPanel metaLine = new JPanel(new BorderLayout());
        metaLine.setOpaque(false);
        metaLine.add(locLbl, BorderLayout.WEST);
        metaLine.add(rightMeta, BorderLayout.EAST);

        row.add(titleLine, BorderLayout.NORTH);
        row.add(metaLine, BorderLayout.SOUTH);
        return row;
    }
```

- [ ] **Step 4: Remove the now-orphaned helpers**

In `AnnotationsPanel.java`, delete the `snippet(String synthesis)` method (~line 530, no longer called) and the `escapeHtml(String s)` method (~line 545, was only used by the old snippet label). Keep `truncate` (still used by `refreshTitle` and the new title line).

- [ ] **Step 5: Build, run the full suite, and stage**

Run: `cd intellij-plugin-spike && ./gradlew test 2>&1 | tail -8 && ./reload 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`; all suites green; `✓ Sandbox updated.`

- [ ] **Step 6: Manual smoke verification**

Restart IntelliJ. With an active `/interactive-review` session and at least one answered annotation:
1. Each row leads with a clean, readable **title** (no `**`, backticks, or `[..](..)` source visible).
2. `file:side:line` and `v#` appear as a muted second line.
3. A thread answered before this feature still shows a readable line (plain-text first line of the synthesis), not raw markdown.
Record pass/fail per check in the commit message.

- [ ] **Step 7: Commit**

```bash
git add intellij-plugin-spike/src/main/java/com/petros/ireview/AnnotationEntry.java intellij-plugin-spike/src/main/java/com/petros/ireview/AnnotationsPanel.java
git commit -m "feat(ireview): panel row leads with clean title + muted meta"
```

---

## Notes for the implementer

- **Display-only / no anchor change:** this task touches only how rows are *labeled and laid out*; thread keys, navigation, and delete are unchanged.
- **`question` is mostly a fallback for legacy threads** (answered before titles existed): they have a user question but no `title`, so the chain shows the question. New threads get the agent title; future Claude-seeded findings get a title with an empty question — both handled.
- **No new dependency:** commonmark + Gson are already on the classpath. If a commonmark import doesn't resolve, stop and report — do not add a dependency without checking.
