# Granular Annotate (sub-unit marks + batched review rounds) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user mark individual sub-units (list items, paragraphs, table rows, code blocks) of any markdown block with ✓ agree / ✕ dismiss / 💬 comment, accumulate marks locally, and submit them as ONE `type: "round"` event that Claude applies in a single pass.

**Architecture:** Pure client feature plus one new event type. A new `static/subunits.js` decorates rendered markdown blocks with per-unit hover strips and a local (localStorage) round store; a docked pill submits the whole round to the existing `/api/submit` endpoint as `{type: "round", reactions: [...]}`. `server.py` validates and queues it exactly like today's events. The Claude-side contract (how to apply a round) is a new section in `references/handling-events.md`. No new block kind, no watcher change, no change to existing immediate paths (block hover strip, span selection, choice, general composer).

**Tech Stack:** Vanilla JS (no deps), Python stdlib HTTP server, pytest (unittest-style + structural smoke tests). Spec: `docs/superpowers/specs/2026-07-23-granular-annotate-design.md`.

## Global Constraints

- No new JS or Python dependencies. Vanilla DOM APIs only.
- Sub-units are: top-level `li` (`:scope > ul > li`, `:scope > ol > li`), top-level `p`, top-level `pre`, and `table tbody tr` — inside `kind: "markdown"` blocks only.
- Elements already carrying `data-annotate-id` are skipped (authored sub-units keep their existing immediate-comment path).
- Marks are local until Submit round: nothing wakes Claude per-mark.
- Wire identity for a unit is `selected_text` (+ `prefix`/`suffix` when the text repeats in the block) — the existing span format. No new identity scheme.
- The single-flight lock applies only from round-submit until ack (server `busy` already does this — do not add a second lock).
- Existing submit paths and payload shapes are untouched; `type: "round"` is additive.
- Run tests from repo root: `python3 -m pytest skills/annotate/tests -x -q`.
- Commits: conventional style used by this repo, e.g. `feat(annotate): …`.

## File Structure

- `skills/annotate/server.py` — accept + validate `type: "round"` in `handle_submit` (server.py:293).
- `skills/annotate/static/subunits.js` — NEW; all sub-unit logic: decoration, mark store, unit composer, round dock, poll integration. Exposes `window.AnnotateSubunits = { decorate, onPoll }`.
- `skills/annotate/static/script.js` — 3 one-line integration hooks (2 render sites + 1 poll site).
- `skills/annotate/static/style.css` — styles for `.sub-unit`, `.unit-strip`, mark states, `.unit-chip`, `.unit-composer`, `#round-dock`.
- `skills/annotate/references/handling-events.md` — new "Round events" contract section.
- `skills/annotate/tests/test_server_round.py` — NEW server tests.
- `skills/annotate/tests/test_smoke_subunits.py` — NEW structural smoke tests.

---

### Task 1: Server accepts `type: "round"`

**Files:**
- Modify: `skills/annotate/server.py:293-388` (`handle_submit`) and the module docstring line `server.py:5`
- Test: `skills/annotate/tests/test_server_round.py` (create)

**Interfaces:**
- Consumes: `_send_text`, `_send_json`, `blocks_model.load/find_block`, `events_module.append`, `_images_ok` — all already imported in server.py.
- Produces: `/api/submit` accepts `{"type": "round", "reactions": [{kind, block_id, selected_text, prefix?, suffix?, text?, images?}, ...]}` → 202 `{"event_id", "status": "queued"}`; event file content `{"type": "round", "reactions": [...]}`. Reaction `kind` ∈ `agree|dismiss|comment`. Task 2's client builds exactly this payload; Task 3's contract documents the event Claude receives.

- [ ] **Step 1: Write the failing tests**

Create `skills/annotate/tests/test_server_round.py`:

```python
"""`type: "round"` submit — one event carrying many sub-unit reactions.

A round is the batched form of granular feedback: the client accumulates
agree/dismiss/comment marks on sub-units locally and submits them all at
once. The server validates shape + block existence and queues ONE event.
"""
import json
import shutil
import tempfile
import unittest
from http.client import HTTPConnection
from pathlib import Path

from skills.annotate.tests.test_server import (  # noqa: E402
    _start_server, _create_session, _write_blocks,
)


def _reaction(kind="agree", block_id="section-1", **kw):
    r = {"kind": kind, "block_id": block_id, "selected_text": "alpha one"}
    r.update(kw)
    return r


class SubmitRoundTests(unittest.TestCase):
    def setUp(self):
        self.project = Path(tempfile.mkdtemp(prefix="rd-proj-"))
        self.home = Path(tempfile.mkdtemp(prefix="rd-home-"))
        self.proc, self.info = _start_server(self.home)
        self.sess = _create_session(self.info["port"], self.project)
        _write_blocks(Path(self.sess["response_dir"]), "resp-rd", "T", [
            {"id": "section-1", "title": "A",
             "markdown": "- alpha one\n- alpha two\n- alpha one"},
            {"id": "section-2", "title": "B", "markdown": "beta"},
        ])

    def tearDown(self):
        try:
            self.proc.terminate(); self.proc.wait(timeout=2)
        except Exception:
            self.proc.kill()
        shutil.rmtree(self.project, ignore_errors=True)
        shutil.rmtree(self.home, ignore_errors=True)

    def _submit(self, payload: dict):
        conn = HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", f"/s/{self.sess['sid']}/api/submit",
                     body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        return resp.status, resp.read().decode()

    def _event(self, event_id: str) -> dict:
        path = Path(self.sess["events_dir"]) / f"{event_id}.json"
        return json.loads(path.read_text())

    def test_round_queues_single_event_with_reactions(self):
        status, body = self._submit({"type": "round", "reactions": [
            _reaction("agree"),
            _reaction("dismiss", selected_text="alpha two"),
            _reaction("comment", block_id="section-2", selected_text="beta",
                      text="why beta?"),
        ]})
        self.assertEqual(status, 202, body)
        evt = self._event(json.loads(body)["event_id"])
        self.assertEqual(evt["type"], "round")
        self.assertEqual(len(evt["reactions"]), 3)
        self.assertEqual(evt["reactions"][2]["text"], "why beta?")

    def test_round_passes_prefix_suffix_through(self):
        status, body = self._submit({"type": "round", "reactions": [
            _reaction("dismiss", prefix="", suffix=" alpha two"),
        ]})
        self.assertEqual(status, 202, body)
        evt = self._event(json.loads(body)["event_id"])
        self.assertEqual(evt["reactions"][0]["suffix"], " alpha two")

    def test_round_rejects_empty_reactions(self):
        status, _ = self._submit({"type": "round", "reactions": []})
        self.assertEqual(status, 422)

    def test_round_rejects_missing_reactions(self):
        status, _ = self._submit({"type": "round"})
        self.assertEqual(status, 422)

    def test_round_rejects_bad_kind(self):
        status, _ = self._submit(
            {"type": "round", "reactions": [_reaction("reject")]})
        self.assertEqual(status, 422)

    def test_round_rejects_unknown_block(self):
        status, _ = self._submit(
            {"type": "round", "reactions": [_reaction(block_id="section-99")]})
        self.assertEqual(status, 422)

    def test_round_rejects_comment_without_text(self):
        status, _ = self._submit(
            {"type": "round", "reactions": [_reaction("comment")]})
        self.assertEqual(status, 422)

    def test_round_rejects_empty_selected_text(self):
        status, _ = self._submit(
            {"type": "round", "reactions": [_reaction(selected_text="")]})
        self.assertEqual(status, 422)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 -m pytest skills/annotate/tests/test_server_round.py -x -q`
Expected: FAIL — the 202-path tests get `400 bad type` (round not accepted yet); the 422 tests may accidentally pass, that's fine.

- [ ] **Step 3: Implement round handling in `handle_submit`**

In `skills/annotate/server.py`, immediately after the `_is_terminal` guard at the top of `handle_submit` (after line 296), add the branch:

```python
        if payload.get("type") == "round":
            self._handle_round(h, dirs, payload)
            return
```

Then add the method right after `handle_submit` (before `serve_poll`):

```python
    _ROUND_KINDS = ("agree", "dismiss", "comment")
    _ROUND_MAX_REACTIONS = 200

    def _handle_round(self, h: BaseHTTPRequestHandler, dirs: dict, payload: dict) -> None:
        """Validate and queue a batched round of sub-unit reactions.

        One event carries the whole round; Claude applies it in a single
        pass (see references/handling-events.md § Round events).
        """
        reactions = payload.get("reactions")
        if not isinstance(reactions, list) or not reactions:
            _send_text(h, 422, "round requires a non-empty reactions list")
            return
        if len(reactions) > self._ROUND_MAX_REACTIONS:
            _send_text(h, 422, "too many reactions")
            return
        blocks_path = Path(dirs["response_dir"]) / "blocks.json"
        doc = blocks_model.load(blocks_path)
        cleaned = []
        for i, r in enumerate(reactions):
            if not isinstance(r, dict):
                _send_text(h, 422, f"reaction {i}: not an object")
                return
            kind = r.get("kind")
            if kind not in self._ROUND_KINDS:
                _send_text(h, 422, f"reaction {i}: bad kind {kind!r}")
                return
            block_id = r.get("block_id")
            try:
                blocks_model.find_block(doc, block_id)
            except KeyError:
                _send_text(h, 422, f"reaction {i}: unknown block_id {block_id!r}")
                return
            selected_text = r.get("selected_text")
            if not isinstance(selected_text, str) or not selected_text.strip():
                _send_text(h, 422, f"reaction {i}: selected_text required")
                return
            text = r.get("text", "")
            if not isinstance(text, str):
                _send_text(h, 422, f"reaction {i}: bad text")
                return
            if kind == "comment" and not text.strip():
                _send_text(h, 422, f"reaction {i}: comment requires text")
                return
            images = r.get("images", [])
            if not _images_ok(images, Path(dirs["state_dir"])):
                _send_text(h, 422, f"reaction {i}: invalid image path(s)")
                return
            out = {"kind": kind, "block_id": block_id,
                   "selected_text": selected_text, "text": text,
                   "images": images}
            for key in ("prefix", "suffix"):
                if isinstance(r.get(key), str):
                    out[key] = r[key]
            cleaned.append(out)
        evt = {"type": "round", "reactions": cleaned}
        eid = events_module.append(Path(dirs["events_dir"]), evt)
        _send_json(h, 202, {"event_id": eid, "status": "queued"})
```

Also update the module docstring line `server.py:5` from
`- /api/submit queues one event per block-comment` to
`- /api/submit queues one event per block-comment (or one per batched round)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 -m pytest skills/annotate/tests/test_server_round.py -x -q`
Expected: 8 passed.

- [ ] **Step 5: Run the whole annotate server suite (regression)**

Run: `python3 -m pytest skills/annotate/tests/test_server.py skills/annotate/tests/test_submit_passthrough.py -q`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add skills/annotate/server.py skills/annotate/tests/test_server_round.py
git commit -m "feat(annotate): accept batched type=round submit events"
```

---

### Task 2: `subunits.js` — decoration, marks, unit composer, round dock

**Files:**
- Create: `skills/annotate/static/subunits.js`
- Modify: `skills/annotate/server.py:248` (add script tag), `skills/annotate/static/script.js:636-639` and `script.js:1469-1473` (decorate hooks), `script.js:1367-1391` (poll hook)
- Modify: `skills/annotate/static/style.css` (append styles)
- Test: `skills/annotate/tests/test_smoke_subunits.py` (create)

**Interfaces:**
- Consumes: `WebCompanion.api.submit(payload)` (promise resolving `{event_id}`), `document.body.dataset.responseId`, body classes `is-busy`, poll delta object (`data.busy`, `data.consumed_events`) — all existing.
- Produces: `window.AnnotateSubunits.decorate(content, section)` — call after rendering a markdown block's content; `window.AnnotateSubunits.onPoll(data)` — call each poll tick. Round payload sent: exactly the Task 1 shape.

- [ ] **Step 1: Write the failing structural smoke test**

Create `skills/annotate/tests/test_smoke_subunits.py`:

```python
"""Structural guards for granular sub-unit marks + batched review rounds.

Source-string checks matching the repo's other smoke tests (see
test_smoke_dismiss_lock.py). Live behavior is manual via the demo push.
"""
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
STATIC = REPO / "skills" / "annotate" / "static"
SUBUNITS_JS = STATIC / "subunits.js"
SCRIPT_JS = STATIC / "script.js"
STYLE_CSS = STATIC / "style.css"
SERVER_PY = REPO / "skills" / "annotate" / "server.py"


def test_subunits_js_exists_with_public_api():
    src = SUBUNITS_JS.read_text()
    for needle in ("window.AnnotateSubunits", "decorate", "onPoll",
                   '"round"', "reactions", "localStorage"):
        assert needle in src, f"subunits.js missing {needle!r}"


def test_subunits_selectors_cover_all_four_unit_types():
    src = SUBUNITS_JS.read_text()
    for needle in (":scope > ul > li", ":scope > ol > li",
                   ":scope > p", ":scope > pre", "tbody tr"):
        assert needle in src, f"subunits.js missing unit selector {needle!r}"


def test_subunits_skips_authored_annotate_ids():
    assert "data-annotate-id" in SUBUNITS_JS.read_text()


def test_script_js_calls_decorate_on_both_render_paths():
    src = SCRIPT_JS.read_text()
    assert src.count("AnnotateSubunits.decorate") >= 2, \
        "script.js must decorate in createBlockSection AND updateBlockContent"
    assert "AnnotateSubunits.onPoll" in src


def test_server_page_includes_subunits_script():
    assert "subunits.js" in SERVER_PY.read_text()


def test_style_css_has_subunit_styles():
    css = STYLE_CSS.read_text()
    for needle in (".sub-unit", ".unit-strip", '[data-mark="dismiss"]',
                   '[data-mark="agree"]', ".unit-chip", "#round-dock",
                   "body.is-busy .unit-strip"):
        assert needle in css, f"style.css missing {needle!r}"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest skills/annotate/tests/test_smoke_subunits.py -x -q`
Expected: FAIL — `FileNotFoundError` on subunits.js.

- [ ] **Step 3: Create `skills/annotate/static/subunits.js`**

```javascript
// annotate skill — granular sub-unit marks + batched review rounds.
//
// Decorates rendered markdown blocks with per-unit hover strips
// (✓ agree / ✕ dismiss / 💬 comment). Marks are LOCAL — stored in
// localStorage, nothing wakes Claude — until the user hits the round
// dock's Submit, which sends ONE {type:"round", reactions:[...]} event.
// Claude applies the whole round in a single pass and acks once.
//
// Unit identity on the wire is the existing span format: the unit's
// plain text as selected_text, plus prefix/suffix disambiguation when
// that text occurs more than once in the block.
(function () {
  const RID = document.body.dataset.responseId || "";
  const KEY = `annotate.round.${RID}`;

  // marks: { [markKey]: {block_id, kind, selected_text, prefix?, suffix?, text?} }
  // markKey = `${block_id} ${selected_text}` — recomputable from the DOM,
  // so a re-render (reconcile/version bump) re-applies surviving marks.
  let marks = loadMarks();
  // event_id of the in-flight submitted round, if any.
  let pendingRound = null;

  function loadMarks() {
    try { return JSON.parse(localStorage.getItem(KEY) || "{}"); }
    catch { return {}; }
  }
  function saveMarks() {
    try { localStorage.setItem(KEY, JSON.stringify(marks)); } catch {}
  }

  const cssEsc = (s) => (window.CSS && CSS.escape)
    ? CSS.escape(String(s))
    : String(s).replace(/["\\\]]/g, "\\$&");

  function unitText(el) {
    return (el.textContent || "").replace(/\s+/g, " ").trim();
  }
  function markKey(blockId, text) { return blockId + " " + text; }

  function occurrences(haystack, needle) {
    if (!needle) return 0;
    let n = 0, i = 0;
    while ((i = haystack.indexOf(needle, i)) !== -1) { n++; i += needle.length; }
    return n;
  }

  // The four sub-unit types (spec decision: all four, one DOM walk).
  // Top-level only: a nested list belongs to its parent bullet's unit.
  const UNIT_SELECTOR = [
    ":scope > ul > li",
    ":scope > ol > li",
    ":scope > p",
    ":scope > pre",
    ":scope > table > tbody > tr",
    // markdown-it wraps tables bare (no wrapper div); some blocks nest the
    // table under a figure/div via free HTML — cover one level down too.
    ":scope > div > table > tbody > tr",
  ].join(", ");

  function decorate(content, section) {
    const blockId = section && section.dataset ? section.dataset.blockId : null;
    if (!blockId || !content) return;
    let units;
    try { units = content.querySelectorAll(UNIT_SELECTOR); }
    catch { return; }
    units.forEach((el) => {
      // Authored sub-units keep their existing immediate-comment path.
      if (el.closest("[data-annotate-id]")) return;
      if (el.classList.contains("sub-unit")) { applyMarkState(el, blockId); return; }
      const text = unitText(el);
      if (!text) return;
      el.classList.add("sub-unit");
      const strip = document.createElement("span");
      strip.className = "unit-strip";
      strip.setAttribute("aria-hidden", "true");
      for (const [kind, glyph, title] of [
        ["agree", "✓", "Agree (no rewrite)"],
        ["dismiss", "✕", "Remove this item"],
        ["comment", "💬", "Comment on this item"],
      ]) {
        const b = document.createElement("button");
        b.type = "button";
        b.dataset.kind = kind;
        b.textContent = glyph;
        b.title = title;
        b.addEventListener("click", (ev) => {
          ev.stopPropagation();
          ev.preventDefault();
          if (document.body.classList.contains("is-busy")) return;
          if (kind === "comment") openComposer(el, blockId);
          else toggleMark(el, blockId, kind);
        });
        strip.appendChild(b);
      }
      el.appendChild(strip);
      applyMarkState(el, blockId);
    });
    renderDock();
  }

  function toggleMark(el, blockId, kind) {
    const text = unitText(stripClone(el));
    const key = markKey(blockId, text);
    const existing = marks[key];
    if (existing && existing.kind === kind) {
      delete marks[key];                      // undo
    } else {
      marks[key] = buildMark(el, blockId, kind,
        existing && existing.kind === "comment" ? existing.text : "");
    }
    saveMarks();
    applyMarkState(el, blockId);
    renderDock();
  }

  // textContent of the unit minus our own UI (strip, chip, composer).
  function stripClone(el) {
    const c = el.cloneNode(true);
    c.querySelectorAll(".unit-strip, .unit-chip, .unit-composer")
      .forEach(n => n.remove());
    return c;
  }

  function buildMark(el, blockId, kind, text) {
    const selected = unitText(stripClone(el));
    const mark = { block_id: blockId, kind, selected_text: selected };
    if (text) mark.text = text;
    const section = el.closest("section.block");
    if (section) {
      const blockText = unitText(stripClone(section));
      if (occurrences(blockText, selected) > 1) {
        const idx = blockText.indexOf(selected);
        mark.prefix = blockText.slice(Math.max(0, idx - 20), idx);
        mark.suffix = blockText.slice(idx + selected.length,
                                      idx + selected.length + 20);
      }
    }
    return mark;
  }

  function applyMarkState(el, blockId) {
    const text = unitText(stripClone(el));
    const m = marks[markKey(blockId, text)];
    if (m) el.dataset.mark = m.kind;
    else delete el.dataset.mark;
    const chip = el.querySelector(".unit-chip");
    if (m && m.kind === "comment" && m.text) {
      if (chip) { chip.textContent = "💬 " + m.text; }
      else {
        const c = document.createElement("span");
        c.className = "unit-chip";
        c.textContent = "💬 " + m.text;
        el.appendChild(c);
      }
    } else if (chip) chip.remove();
  }

  // ── Inline per-unit composer (local pin, not a submit) ────────────────────
  let openComposerEl = null;

  function openComposer(el, blockId) {
    closeComposer();
    const text = unitText(stripClone(el));
    const existing = marks[markKey(blockId, text)];
    const wrap = document.createElement("span");
    wrap.className = "unit-composer";
    const input = document.createElement("input");
    input.type = "text";
    input.placeholder = "Ask or push back on this item…";
    input.value = (existing && existing.kind === "comment" && existing.text) || "";
    const pin = document.createElement("button");
    pin.type = "button";
    pin.textContent = "Pin";
    const commit = () => {
      const v = input.value.trim();
      const key = markKey(blockId, text);
      if (v) marks[key] = Object.assign(buildMark(el, blockId, "comment", v));
      else delete marks[key];
      saveMarks();
      closeComposer();
      applyMarkState(el, blockId);
      renderDock();
    };
    pin.addEventListener("click", commit);
    input.addEventListener("keydown", (ev) => {
      if (ev.key === "Enter") { ev.preventDefault(); commit(); }
      if (ev.key === "Escape") { ev.preventDefault(); closeComposer(); }
    });
    wrap.append(input, pin);
    el.appendChild(wrap);
    openComposerEl = wrap;
    input.focus();
  }

  function closeComposer() {
    if (openComposerEl) { openComposerEl.remove(); openComposerEl = null; }
  }

  // ── Round dock ─────────────────────────────────────────────────────────────
  function renderDock() {
    let dock = document.getElementById("round-dock");
    const count = Object.keys(marks).length;
    if (!count) { if (dock) dock.remove(); return; }
    if (!dock) {
      dock = document.createElement("div");
      dock.id = "round-dock";
      const btn = document.createElement("button");
      btn.type = "button";
      btn.id = "round-submit";
      btn.addEventListener("click", submitRound);
      dock.appendChild(btn);
      document.body.appendChild(dock);
    }
    const btn = dock.querySelector("#round-submit");
    btn.textContent = pendingRound
      ? "Applying round…"
      : `Submit round (${count})`;
    btn.disabled = !!pendingRound || document.body.classList.contains("is-busy");
  }

  function submitRound() {
    const reactions = Object.values(marks).map((m) => {
      const r = { kind: m.kind, block_id: m.block_id,
                  selected_text: m.selected_text,
                  text: m.text || "", images: [] };
      if (m.prefix !== undefined) r.prefix = m.prefix;
      if (m.suffix !== undefined) r.suffix = m.suffix;
      return r;
    });
    if (!reactions.length || pendingRound) return;
    WebCompanion.api.submit({ type: "round", reactions }).then((res) => {
      pendingRound = res && res.event_id ? String(res.event_id) : null;
      renderDock();
    }).catch(() => { renderDock(); });
  }

  function clearRound() {
    marks = {};
    pendingRound = null;
    saveMarks();
    document.querySelectorAll(".sub-unit[data-mark]").forEach(el => {
      delete el.dataset.mark;
      el.querySelector(".unit-chip")?.remove();
    });
    renderDock();
  }

  // Poll integration: disable the dock while busy; clear marks when our
  // round's event id shows up acked in consumed_events (dismissed units
  // disappear via the normal reconcile pass — version bump / block rewrite).
  function onPoll(data) {
    if (pendingRound && Array.isArray(data.consumed_events) &&
        data.consumed_events.map(String).includes(pendingRound)) {
      clearRound();
      return;
    }
    renderDock();
  }

  window.AnnotateSubunits = { decorate, onPoll };
})();
```

- [ ] **Step 4: Wire the script tag into the page**

In `skills/annotate/server.py:245` (the `head` string in `serve_root`), after the `script.js` line, add:

```python
                '<script src="/static/subunits.js" defer></script>'
```

(Defer order: subunits.js after script.js is fine — script.js only calls `window.AnnotateSubunits?.…` optionally, and decoration happens on fetch callbacks that run after both scripts execute.)

- [ ] **Step 5: Hook decorate + onPoll into script.js**

Three edits in `skills/annotate/static/script.js`:

(a) In `createBlockSection`, markdown branch (after `if (window.AnnotateGlossary) window.AnnotateGlossary.decorate(content);` at script.js:638), add:

```javascript
      if (window.AnnotateSubunits) window.AnnotateSubunits.decorate(content, section);
```

(b) In `updateBlockContent`, markdown branch (after the same glossary decorate call at script.js:1472), add:

```javascript
        if (window.AnnotateSubunits) window.AnnotateSubunits.decorate(content, section);
```

(c) In `onPollDelta` (after `applyProgress(data.progress);` at script.js:1379), add:

```javascript
    if (window.AnnotateSubunits) window.AnnotateSubunits.onPoll(data);
```

- [ ] **Step 6: Append styles to `skills/annotate/static/style.css`**

Match the existing palette (the file uses `var(--accent)`, `var(--surface)`, `var(--border)`, `var(--text-dim)` etc. — reuse them):

```css
/* ── Granular sub-units (subunits.js) ────────────────────────────────── */
.sub-unit { position: relative; border-radius: 6px; }
.sub-unit:hover { background: color-mix(in srgb, var(--accent) 5%, transparent); }

.unit-strip {
  position: absolute; right: 4px; top: 2px;
  display: inline-flex; gap: 3px;
  opacity: 0; transition: opacity .12s ease;
}
.sub-unit:hover .unit-strip { opacity: 1; }
.unit-strip button {
  width: 22px; height: 22px; padding: 0;
  border: 1px solid var(--border); border-radius: 5px;
  background: var(--surface); color: var(--text-dim);
  font-size: 11px; line-height: 1; cursor: pointer;
}
.unit-strip button:hover { border-color: var(--accent); color: var(--accent); }
body.is-busy .unit-strip { display: none; }

.sub-unit[data-mark="agree"] {
  background: color-mix(in srgb, #16a34a 8%, transparent);
}
.sub-unit[data-mark="agree"] .unit-strip button[data-kind="agree"] {
  background: #16a34a; border-color: #16a34a; color: #fff;
}
.sub-unit[data-mark="dismiss"] { opacity: .45; text-decoration: line-through; }
.sub-unit[data-mark="dismiss"] .unit-strip,
.sub-unit[data-mark="dismiss"] .unit-chip { text-decoration: none; }
.sub-unit[data-mark="dismiss"] .unit-strip button[data-kind="dismiss"] {
  background: #dc2626; border-color: #dc2626; color: #fff;
}
.sub-unit[data-mark="comment"] {
  background: color-mix(in srgb, var(--accent) 7%, transparent);
}

.unit-chip {
  display: inline-block; margin-left: 8px; padding: 1px 8px;
  font-size: 12px; border-radius: 6px;
  background: color-mix(in srgb, var(--accent) 10%, var(--surface));
  border: 1px solid color-mix(in srgb, var(--accent) 30%, var(--border));
  color: var(--accent);
}

.unit-composer { display: flex; gap: 6px; margin-top: 4px; }
.unit-composer input {
  flex: 1; padding: 4px 10px; font-size: 12.5px;
  border: 1px solid var(--border); border-radius: 6px;
  background: var(--surface); color: var(--text);
}
.unit-composer input:focus { outline: none; border-color: var(--accent); }
.unit-composer button {
  padding: 2px 12px; font-size: 12px; font-weight: 600;
  border: none; border-radius: 6px; cursor: pointer;
  background: var(--accent); color: #fff;
}

#round-dock {
  position: fixed; bottom: 18px; left: 50%; transform: translateX(-50%);
  z-index: 40;
}
#round-dock button {
  border: none; border-radius: 999px; padding: 10px 20px;
  font-size: 13px; font-weight: 650; cursor: pointer;
  background: var(--text-strong, #0f172a); color: #fff;
  box-shadow: 0 6px 18px rgba(15, 23, 42, .25);
}
#round-dock button:disabled { opacity: .5; cursor: default; }
```

- [ ] **Step 7: Run the smoke test to verify it passes**

Run: `python3 -m pytest skills/annotate/tests/test_smoke_subunits.py -x -q`
Expected: 6 passed.

- [ ] **Step 8: Regression — full annotate suite**

Run: `python3 -m pytest skills/annotate/tests -q`
Expected: all pass (structural tests over script.js/style.css still hold).

- [ ] **Step 9: Commit**

```bash
git add skills/annotate/static/subunits.js skills/annotate/static/script.js \
        skills/annotate/static/style.css skills/annotate/server.py \
        skills/annotate/tests/test_smoke_subunits.py
git commit -m "feat(annotate): granular sub-unit marks with batched review rounds"
```

---

### Task 3: Claude-side round contract (docs)

**Files:**
- Modify: `skills/annotate/references/handling-events.md` (new section after the `dismiss` subsection, i.e. after line 56)
- Modify: `skills/annotate/README.md` (one feature line in the capability list)

**Interfaces:**
- Consumes: the Task 1 event shape `{"type": "round", "reactions": [...]}`.
- Produces: the procedure a future Claude session follows when a round event arrives. Uses existing helpers only (`blocks.update_block`, `blocks.drop_unused_terms`, `blocks.save_atomic`).

- [ ] **Step 1: Add the round section to `handling-events.md`**

Insert after the `### WEBCOMPANION_EVENT with type: "dismiss"` subsection (after current line 56), before `### WEBCOMPANION_FINISHED`:

```markdown
### `WEBCOMPANION_EVENT` with `type: "round"`

The user swept the document marking sub-units (list items, paragraphs, table
rows, code blocks) and submitted them all at once. The payload carries the
whole batch:

- `reactions` — a list of `{kind, block_id, selected_text, text, images,
  prefix?, suffix?}`. `kind` is `"agree"`, `"dismiss"`, or `"comment"`.
  `selected_text` is the sub-unit's plain text; `prefix`/`suffix` pin down
  which occurrence when it repeats inside the block (same convention as span
  comments).

Apply the WHOLE round in one pass — this is the entire point of batching:

1. Read `<response_dir>/blocks.json`. Group reactions by `block_id`.
2. For each touched block, compose ONE new markdown that applies all of its
   reactions together:
   - **`dismiss`** — cut that sub-unit (the bullet / paragraph / row / fence
     matching `selected_text`) from the block's markdown, then re-thread the
     remainder (renumber, fix dangling references) so the block still reads
     coherently. This is the sub-unit form of dismiss: do not remove the
     whole block. Dismissed content is out of scope going forward — do not
     reintroduce it (same rule as whole-block dismiss).
   - **`comment`** — the block-rewrite contract scoped to that sub-unit: fold
     the answer or clarification into the sub-unit's prose. `Read` any
     `images` paths first.
   - **`agree`** — no rewrite for this sub-unit. Never re-emit a block whose
     only reactions are agrees.
3. Persist each changed block via `blocks.update_block(doc, block_id,
   new_markdown)` (content-hash-safe), then `blocks.drop_unused_terms(doc)`,
   then ONE `blocks.save_atomic`.
4. Write ONE `<consumed_dir>/<event_id>.ack`. End your turn. No terminal
   output; the watcher stays armed.

Cross-item coherence is required: if a round dismisses two bullets and
questions a third in the same block, the single rewrite resolves all three
together. A `selected_text` that no longer matches the current block content
(concurrent rewrite) is historical context — same rule as span comments.
Re-apply safety is unchanged: re-processing the round is a content-hash
no-op.
```

- [ ] **Step 2: Update the payload field list**

In the same file, `### WEBCOMPANION_EVENT (per-comment submission)` step 2 (line 18-28): add to the field list, after the `selected_options` bullet:

```markdown
   - `reactions` — for `type: "round"`: the batched sub-unit reactions. Jump to the `round` subsection below.
```

And extend the `type` bullet (line 21) from
`` `type` — `"comment"`, `"reject"`, `"choice"`, or `"dismiss"`. `` to
`` `type` — `"comment"`, `"reject"`, `"choice"`, `"dismiss"`, or `"round"`. ``

- [ ] **Step 3: Add one line to `README.md`**

In `skills/annotate/README.md`, find the feature/capability list (the bullet list near the top describing what the page does) and add:

```markdown
- **Granular review rounds** — hover any list item, paragraph, table row, or code block; mark ✓ agree / ✕ dismiss / 💬 comment locally, then submit the whole round as one event Claude applies in a single pass.
```

- [ ] **Step 4: Run skill-structure guard**

Run: `python3 -m pytest skills/annotate/tests/test_skill_structure.py -q`
Expected: pass (reference files still well-formed).

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/references/handling-events.md skills/annotate/README.md
git commit -m "docs(annotate): round-event contract for granular review rounds"
```

---

### Task 4: Live verification against the real server

**Files:**
- None created (manual verification round; scratch workspace only).

**Interfaces:**
- Consumes: everything above, running end-to-end.

- [ ] **Step 1: Run the full annotate test suite**

Run: `python3 -m pytest skills/annotate/tests -q`
Expected: all pass, including the two new files.

- [ ] **Step 2: Restart the shared server so it picks up server.py + static changes**

```bash
pkill -f "web_companion/server.py" 2>/dev/null; sleep 1
PLUGIN_ROOT="${CLAUDE_PLUGIN_ROOT:-$HOME/projects/petros-skills}"
"$PLUGIN_ROOT/skills/annotate/ensure_server.sh"
```

Expected: server restarts; `~/.claude/annotate/server.json` has a fresh URL.

(Note: the annotate server is shared across sessions and self-heals via `ensure_server.sh`; a restart while no event is mid-flight is safe. Check `curl -s <url>/` responds before proceeding.)

- [ ] **Step 3: Push a scratch workspace and verify in the browser**

Create a workspace titled "subunit-demo" with one markdown block containing a 5-bullet list, a paragraph, a table, and a code fence (follow `references/pushing.md` create + push flow). Open the URL and verify:

- Hovering a bullet shows the ✓ ✕ 💬 strip; clicking toggles states; re-click undoes.
- 💬 pins a chip; Enter commits; Escape closes.
- The round dock appears with a live count; Submit posts and the page goes busy.
- The queued event file under the workspace's `state/events/` contains `"type": "round"` with the expected reactions.

- [ ] **Step 4: Verify a round applies end-to-end (contract dry-run)**

Acting as the event handler (per the new handling-events § round): apply the queued round to the scratch workspace's `blocks.json` (cut the dismissed bullet, answer the comment inline), `save_atomic`, write the `.ack`. Reload the page: dismissed bullet gone, marks cleared, dock gone, page unlocked.

- [ ] **Step 5: Commit anything the verification shook out; push**

```bash
git status --short   # expect clean unless fixes were needed
git push
```

---

## Self-Review (done at plan time)

- **Spec coverage:** client decoration (Task 2), round event + server validation (Task 1), Claude contract incl. dismiss-cut/re-thread/agree-no-op (Task 3), compatibility (untouched existing paths — enforced by regression runs in Tasks 1-2), edge cases (stale text → historical-context rule in contract; duplicates → prefix/suffix in `buildMark`; empty round → 422 + dock hidden at 0; re-apply → content-hash no-op), tests (Tasks 1-2), live verification (Task 4).
- **Type consistency:** payload field names (`kind`, `block_id`, `selected_text`, `prefix`, `suffix`, `text`, `images`, `reactions`) identical across subunits.js `submitRound`, server `_handle_round`, tests, and the contract doc.
- **Placeholders:** none — all code complete.
