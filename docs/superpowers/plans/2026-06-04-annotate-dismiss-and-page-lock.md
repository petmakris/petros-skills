# Annotate Dismiss + Page-Wide Single-Flight Lock — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-block "dismiss" (delete-as-irrelevant) action to the annotate browser view, and make the whole page single-flight — at most one open editor and one in-flight submission at a time — with a server-authoritative busy lock.

**Architecture:** Bottom-up. (1) `blocks.remove_block` model helper. (2) Server `/api/submit` accepts `type: "dismiss"`. (3) Server `/poll` reports a `busy` flag derived from unacked events. (4) `SKILL.md` documents the new event + Claude's dismiss-handling contract. (5) CSS for the busy banner and disabled-affordance states. (6) Client dismiss × affordance. (7) Client BUSY lock driven by `data.busy`. (8) Client single-open-editor (EDITING) rule.

**Tech Stack:** Python 3 (stdlib `http.server`), vanilla JS client, pytest (unit + structural smoke), Playwright `.cjs` (manual e2e). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-06-04-annotate-dismiss-and-page-lock-design.md`

**Test commands:**
- Python: `python3 -m pytest skills/annotate/tests/ -v` (run from the repo root)
- Single test: `python3 -m pytest skills/annotate/tests/test_blocks.py::test_name -v`

---

## Task 1: `blocks.remove_block` model helper

Pure function that removes a block by id. No-op when the id is absent (watcher re-apply safety). Version-chain pruning for the removed id is already handled by `versions.derive_versions` on the next read — nothing to do there.

**Files:**
- Modify: `skills/annotate/blocks.py` (add function after `convert_block_to_markdown`, around line 177)
- Test: `skills/annotate/tests/test_blocks.py`

- [ ] **Step 1: Write the failing tests**

Add to `skills/annotate/tests/test_blocks.py`. First extend the import at the top of the file (it currently imports from `skills.annotate.blocks`) to include `remove_block`:

```python
from skills.annotate.blocks import (
    BlocksDoc, load, save_atomic, update_block, next_block_id,
    update_spec_block, next_step_id, drop_unused_terms, remove_block,
)
```

Then append these tests:

```python
def test_remove_block_removes_present_block():
    doc = BlocksDoc(blocks=[
        {"id": "section-1", "markdown": "a"},
        {"id": "section-2", "markdown": "b"},
        {"id": "section-3", "markdown": "c"},
    ])
    assert remove_block(doc, "section-2") is True
    assert [b["id"] for b in doc.blocks] == ["section-1", "section-3"]


def test_remove_block_absent_id_is_noop():
    doc = BlocksDoc(blocks=[{"id": "section-1", "markdown": "a"}])
    assert remove_block(doc, "section-9") is False
    assert [b["id"] for b in doc.blocks] == ["section-1"]


def test_remove_block_removes_non_markdown_block():
    doc = BlocksDoc(blocks=[
        {"id": "section-1", "markdown": "a"},
        {"id": "section-2", "kind": "choice", "spec": {"question": "q", "options": []}},
    ])
    assert remove_block(doc, "section-2") is True
    assert [b["id"] for b in doc.blocks] == ["section-1"]
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python3 -m pytest skills/annotate/tests/test_blocks.py -k remove_block -v`
Expected: FAIL — `ImportError: cannot import name 'remove_block'`.

- [ ] **Step 3: Implement `remove_block`**

Add to `skills/annotate/blocks.py` after `convert_block_to_markdown` (after line 176):

```python
def remove_block(doc: BlocksDoc, block_id: str) -> bool:
    """Remove a block by id. Returns True if a block was removed.

    No-op (returns False) when the id is absent — required for watcher
    re-apply safety, where a dismiss event may be re-emitted after the
    block is already gone. Version chains for removed ids are pruned by
    versions.derive_versions on the next read, so a later reused id starts
    fresh at v1.
    """
    before = len(doc.blocks)
    doc.blocks = [b for b in doc.blocks if b.get("id") != block_id]
    return len(doc.blocks) != before
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `python3 -m pytest skills/annotate/tests/test_blocks.py -k remove_block -v`
Expected: PASS (3 passed).

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/blocks.py skills/annotate/tests/test_blocks.py
git commit -m "feat(annotate): add blocks.remove_block model helper"
```

---

## Task 2: Server `/api/submit` accepts `type: "dismiss"`

A dismiss is a new submission type. It requires a non-null `block_id`, ignores `text`, and (like `choice`) is rejected with 422 if the target block does not exist.

**Files:**
- Modify: `skills/annotate/server.py:147-212` (`handle_submit`)
- Test: `skills/annotate/tests/test_server.py` (add to `ServerStartupTests`)

- [ ] **Step 1: Write the failing tests**

Append these methods to the `ServerStartupTests` class in `skills/annotate/tests/test_server.py`:

```python
    def test_submit_dismiss_writes_event(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-dis", "T", [
            {"id": "section-1", "markdown": "irrelevant section"},
        ])
        payload = {"block_id": "section-1", "type": "dismiss", "text": "ignored"}
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit", body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 202)
        conn.close()
        events_dir = Path(self.sess["events_dir"])
        evt = json.loads(list(events_dir.glob("*.json"))[0].read_text())
        self.assertEqual(evt["type"], "dismiss")
        self.assertEqual(evt["block_id"], "section-1")

    def test_submit_dismiss_requires_block_id(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-dis2", "T", [])
        payload = {"type": "dismiss", "text": ""}
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit", body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 422)
        conn.close()

    def test_submit_dismiss_unknown_block_is_422(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-dis3", "T", [
            {"id": "section-1", "markdown": "x"},
        ])
        payload = {"block_id": "section-9", "type": "dismiss"}
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit", body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 422)
        conn.close()
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python3 -m pytest skills/annotate/tests/test_server.py -k dismiss -v`
Expected: FAIL — `test_submit_dismiss_writes_event` gets 400 (type not allowed) instead of 202.

- [ ] **Step 3: Implement the dismiss branch in `handle_submit`**

In `skills/annotate/server.py`, change the type guard at line 157 from:

```python
        if comment_type not in ("comment", "reject", "choice"):
            _send_text(h, 400, "bad type")
            return
```

to:

```python
        if comment_type not in ("comment", "reject", "choice", "dismiss"):
            _send_text(h, 400, "bad type")
            return
```

Then, immediately after the `if not isinstance(text, str):` block (after line 162, before the `selected_options = ...` line), add the dismiss validation:

```python
        if comment_type == "dismiss":
            if block_id is None:
                _send_text(h, 422, "dismiss requires block_id")
                return
            blocks_path = Path(dirs["response_dir"]) / "blocks.json"
            doc = blocks_model.load(blocks_path)
            try:
                blocks_model.find_block(doc, block_id)
            except KeyError:
                _send_text(h, 422, f"unknown block_id {block_id!r}")
                return
```

No change is needed to the event-dict construction at line 201 — `type` is already carried through from `comment_type`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `python3 -m pytest skills/annotate/tests/test_server.py -k dismiss -v`
Expected: PASS (3 passed).

- [ ] **Step 5: Run the full server suite to check nothing regressed**

Run: `python3 -m pytest skills/annotate/tests/test_server.py -v`
Expected: all PASS (existing `test_submit_rejects_bad_type` with `type:"approve"` still returns 400).

- [ ] **Step 6: Commit**

```bash
git add skills/annotate/server.py skills/annotate/tests/test_server.py
git commit -m "feat(annotate): accept type=dismiss in /api/submit"
```

---

## Task 3: Server `/poll` reports `busy`

`busy` is true when at least one queued event has not been acked. Reuse the `consumed_set` the handler already builds; count queued events from `events_dir`.

**Files:**
- Modify: `skills/annotate/server.py:214-258` (`serve_poll`)
- Test: `skills/annotate/tests/test_server.py` (add to `ServerStartupTests`)

- [ ] **Step 1: Write the failing test**

Append to `ServerStartupTests` in `skills/annotate/tests/test_server.py`:

```python
    def test_poll_busy_tracks_unacked_events(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-busy", "T", [
            {"id": "section-1", "markdown": "hi"},
        ])

        # No events yet → not busy.
        status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
        self.assertEqual(status, 200)
        self.assertFalse(json.loads(body)["busy"])

        # Submit a dismiss → an unacked event exists → busy.
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/submit",
                     body=json.dumps({"block_id": "section-1", "type": "dismiss"}),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 202)
        event_id = json.loads(resp.read().decode("utf-8"))["event_id"]
        conn.close()

        status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
        self.assertTrue(json.loads(body)["busy"])

        # Ack it (simulate Claude) → not busy. serve_poll reads acks from
        # state_dir/"consumed", so write the marker there.
        consumed = Path(self.sess["state_dir"]) / "consumed"
        consumed.mkdir(parents=True, exist_ok=True)
        (consumed / f"{event_id}.ack").write_text("")

        status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
        self.assertFalse(json.loads(body)["busy"])
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `python3 -m pytest skills/annotate/tests/test_server.py -k busy -v`
Expected: FAIL — `KeyError: 'busy'` (the poll response has no `busy` field yet).

- [ ] **Step 3: Implement `busy` in `serve_poll`**

In `skills/annotate/server.py`, inside `serve_poll`, the handler already computes `consumed_set = set(consumed)` (line 239). After that line, add:

```python
        events_dir = Path(dirs["events_dir"])
        try:
            queued_ids = {p.stem for p in events_dir.glob("*.json")}
        except OSError:
            queued_ids = set()
        busy = bool(queued_ids - consumed_set)
```

Then add `"busy": busy,` to the `_send_json` response dict at the end of `serve_poll` (the dict starting at line 251):

```python
        _send_json(h, 200, {
            "blocks": versions,
            "watcher_seen_at": hb,
            "finished": _is_terminal(state_dir),
            "response_id": doc.response_id,
            "consumed_events": consumed,
            "progress": progress,
            "busy": busy,
        })
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `python3 -m pytest skills/annotate/tests/test_server.py -k busy -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/server.py skills/annotate/tests/test_server.py
git commit -m "feat(annotate): /poll reports busy when events are unacked"
```

---

## Task 4: SKILL.md — document the dismiss event and Claude's contract

Document the new `type: "dismiss"` so Claude handles the watcher event correctly: remove the block, smart-drop/re-thread survivors, treat as out-of-scope, and keep delete distinct from reject.

**Files:**
- Modify: `skills/annotate/SKILL.md`

- [ ] **Step 1: Add `dismiss` to the Mode D event field reference**

In `SKILL.md`, in the `### `WEBCOMPANION_EVENT` (per-comment submission)` section, the `type` field bullet (around line 341) currently reads:

```markdown
   - `type` — `"comment"`, `"reject"`, or `"choice"`.
```

Change it to:

```markdown
   - `type` — `"comment"`, `"reject"`, `"choice"`, or `"dismiss"`.
```

And in the same numbered list, after the `selected_text` bullet, add:

```markdown
   - For `type == "dismiss"`: `block_id` is the block to remove; `text` is empty and ignored. Jump to the `dismiss` subsection below.
```

- [ ] **Step 2: Add the dismiss-handling subsection**

In `SKILL.md`, after the `### `WEBCOMPANION_EVENT` with `type: "choice"`` subsection (after line 361, before `### `WEBCOMPANION_FINISHED``), insert:

```markdown
### `WEBCOMPANION_EVENT` with `type: "dismiss"`

The user removed a block. **Delete is not reject.** A reject means "I disagree" — you soften, withdraw, or defend the claim. A dismiss means "this block is *irrelevant*" — you remove it and stop carrying it forward; do not argue, defend, or re-add it.

1. Read `<response_dir>/blocks.json`.
2. `blocks.remove_block(doc, block_id)` — deletes the block. It is a no-op if the block is already gone (watcher re-apply safety).
3. **Smart-drop:** scan the surviving blocks. Re-thread any that referenced the removed one — renumber steps, cut or rewrite dangling references — so the document still reads coherently without it. Use `blocks.update_block` / `blocks.update_spec_block` per touched block; touch only blocks that actually referenced the removed one.
4. `blocks.drop_unused_terms(doc)` — drop any glossary entry whose term was last used by the removed block.
5. Treat the removed content as **out of scope** for the rest of this turn and going forward: do not reintroduce it, and exclude it when acting on the plan.
6. `save_atomic` the doc, write `<consumed_dir>/<event_id>.ack`, end the turn. No terminal output; the watcher stays armed.

A dismissed `choice` or `sequence` block is removed whole-block the same way — there is no step-level dismiss.
```

- [ ] **Step 3: Note the page-wide lock behavior in the rewrite contract**

In `SKILL.md`, at the end of the `## Token budget` section (after line 443), append a short note:

```markdown

## Page-wide single-flight lock

The browser page is single-flight: while any submitted event is unacked, the page is locked (all comment / reject / dismiss affordances disabled, a "Claude is updating…" banner shown), and only one comment editor can be open at a time. The lock is server-authoritative — `/poll` reports `busy: true` until you write the event's `.ack`. Practical consequence for you: **always write the `<consumed_dir>/<event_id>.ack` when you finish handling an event**, even on a no-op or malformed payload — otherwise the page stays locked forever.
```

- [ ] **Step 4: Verify the edits landed**

Run: `grep -n "dismiss" skills/annotate/SKILL.md`
Expected: matches in the field reference, the new subsection heading, and the delete-is-not-reject line.

Run: `grep -n "single-flight" skills/annotate/SKILL.md`
Expected: the new section heading + note.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/SKILL.md
git commit -m "docs(annotate): document dismiss event + page-wide lock contract"
```

---

## Task 5: CSS — busy banner, disabled affordances, dismiss button

All the "disable while busy/editing" behavior is centralized in CSS, gated on body classes `is-busy` and `is-editing` that the client toggles (Tasks 6–7). This task adds the rules and the dismiss button styling.

**Files:**
- Modify: `skills/annotate/static/style.css` (append a new section at the end)
- Test: `skills/annotate/tests/test_smoke_dismiss_lock.py` (new structural smoke test)

- [ ] **Step 1: Write the failing smoke test**

Create `skills/annotate/tests/test_smoke_dismiss_lock.py`:

```python
"""Structural guards for the dismiss + page-lock feature.

Source-string checks matching the repo's other smoke tests; live behavior
is exercised by tests/e2e/dismiss.e2e.cjs (manual).
"""
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
STYLE_CSS = REPO / "skills" / "annotate" / "static" / "style.css"
SCRIPT_JS = REPO / "skills" / "annotate" / "static" / "script.js"


def test_busy_and_editing_css_present():
    css = STYLE_CSS.read_text()
    for needle in ("body.is-busy", "body.is-editing", ".busy-banner",
                   ".hover-actions button[data-type=\"dismiss\"]"):
        assert needle in css, f"style.css missing {needle!r}"


def test_dismiss_affordance_wired_in_script():
    src = SCRIPT_JS.read_text()
    assert 'type: "dismiss"' in src, "script.js never submits a dismiss event"
    assert "onDismiss" in src, "script.js missing onDismiss handler"


def test_busy_lock_consumed_in_script():
    src = SCRIPT_JS.read_text()
    assert "data.busy" in src, "script.js does not read data.busy from poll"
    assert "is-busy" in src, "script.js does not toggle the is-busy lock"


def test_single_editor_guard_in_script():
    src = SCRIPT_JS.read_text()
    assert "is-editing" in src, "script.js does not toggle the is-editing state"
```

- [ ] **Step 2: Run the smoke test to verify it fails**

Run: `python3 -m pytest skills/annotate/tests/test_smoke_dismiss_lock.py -v`
Expected: FAIL — all four assertions fail (nothing implemented yet).

- [ ] **Step 3: Append the CSS**

Append to `skills/annotate/static/style.css`. Reuse the existing palette variables (`--accent`, `--surface`, `--border`, `--text-dim`):

```css
/* ── Dismiss affordance ───────────────────────────────────────────────── */
.hover-actions button[data-type="dismiss"] {
  color: var(--text-dim);
}
.hover-actions button[data-type="dismiss"]:hover {
  color: #c1432f; /* matches the diagram error-pill red used elsewhere */
}

/* ── Page-wide single-flight lock ─────────────────────────────────────── */
/* BUSY: an event is in flight. EDITING: one comment card is open. In both,
   every affordance that can submit to Claude is disabled. Passive reading,
   scrolling, and block search stay live (they are not gated here). */
.busy-banner {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 0 0 12px;
  padding: 10px 14px;
  border: 1px solid color-mix(in srgb, var(--accent) 45%, var(--border));
  border-radius: 9px;
  background: color-mix(in srgb, var(--accent) 12%, var(--surface));
  color: var(--text);
  font-size: 14px;
}
.busy-banner .busy-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--accent);
  border-top-color: transparent;
  border-radius: 50%;
  animation: busy-spin 0.8s linear infinite;
  flex: none;
}
@keyframes busy-spin { to { transform: rotate(360deg); } }

body.is-busy .hover-actions,
body.is-busy .general-composer,
body.is-busy .choice-submit-btn,
body.is-busy .card-submit-btn,
body.is-editing .hover-actions {
  pointer-events: none;
  opacity: 0.4;
}
/* The currently-open editor stays usable while EDITING (only OTHER
   affordances are frozen); BUSY freezes everything including open cards. */
body.is-busy .comment-card {
  pointer-events: none;
  opacity: 0.5;
}
```

- [ ] **Step 4: Re-run the smoke test (CSS assertions now pass, JS ones still fail)**

Run: `python3 -m pytest skills/annotate/tests/test_smoke_dismiss_lock.py::test_busy_and_editing_css_present -v`
Expected: PASS. (The three JS-assertion tests still fail — implemented in Tasks 6–7.)

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/static/style.css skills/annotate/tests/test_smoke_dismiss_lock.py
git commit -m "feat(annotate): CSS for busy/editing lock + dismiss affordance"
```

---

## Task 6: Client — dismiss × affordance

Add a third hover button (`×`) that submits a `dismiss` event immediately (no editor). The block is removed from the DOM later by `reconcile()` when Claude acks; an updating overlay gives immediate feedback in the meantime.

**Files:**
- Modify: `skills/annotate/static/script.js` (`renderHoverActions` ~line 51, add `onDismiss` near `onHoverAction` ~line 145)
- Test: `skills/annotate/tests/test_smoke_dismiss_lock.py::test_dismiss_affordance_wired_in_script` (from Task 5)

- [ ] **Step 1: Confirm the target test is currently failing**

Run: `python3 -m pytest skills/annotate/tests/test_smoke_dismiss_lock.py::test_dismiss_affordance_wired_in_script -v`
Expected: FAIL (`onDismiss` / `type: "dismiss"` not in script.js yet).

- [ ] **Step 2: Add the dismiss button in `renderHoverActions`**

In `skills/annotate/static/script.js`, inside `renderHoverActions`, after the `for (const t of ACTION_TYPES) { ... }` loop and before `(block.querySelector(".card-body") || block).appendChild(wrap);` (around line 95), insert:

```javascript
      // Dismiss (delete-as-irrelevant). Unlike comment/reject it opens no
      // editor — it submits a dismiss event straight away.
      const del = document.createElement("button");
      del.type = "button";
      del.dataset.type = "dismiss";
      del.textContent = "×";
      del.title = "Remove section (irrelevant)";
      del.addEventListener("click", (ev) => {
        ev.stopPropagation();
        onDismiss(block);
      });
      wrap.appendChild(del);
```

- [ ] **Step 3: Add the `onDismiss` handler**

In `skills/annotate/static/script.js`, add this function immediately before `onHoverAction` (before line 145):

```javascript
  function onDismiss(block) {
    // Guard: the lock states make dismiss a no-op while another submission is
    // in flight or an editor is open. (CSS also disables the button, but the
    // guard covers programmatic/edge calls.)
    if (document.body.classList.contains("is-busy") ||
        document.body.classList.contains("is-editing")) return;
    const blockId = block.dataset.blockId;
    if (!blockId) return;
    const payload = {
      block_id: blockId,
      step_id: null,
      type: "dismiss",
      text: "",
      selected_text: "",
      images: [],
    };
    WebCompanion.api.submit(payload).then((res) => {
      const eventId = res && res.event_id;
      if (eventId) pendingEvents.set(String(eventId), { blockId });
      // Immediate feedback; the block is actually removed by reconcile() once
      // Claude acks and /raw no longer lists it.
      startUpdatingOverlay(block);
    }).catch(() => { /* swallow — page stays usable */ });
  }
```

- [ ] **Step 4: Run the target smoke test to verify it passes**

Run: `python3 -m pytest skills/annotate/tests/test_smoke_dismiss_lock.py::test_dismiss_affordance_wired_in_script -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/static/script.js
git commit -m "feat(annotate): dismiss × affordance submits a dismiss event"
```

---

## Task 7: Client — BUSY lock + single open editor (EDITING)

Drive the two body-class locks from poll data and editor state. `is-busy` comes from `data.busy`; `is-editing` is set whenever a comment card is open; opening a second editor is blocked while one is open.

**Files:**
- Modify: `skills/annotate/static/script.js` (`onPollDelta` ~line 976, `onHoverAction` ~line 145, `renderComments` ~line 821)
- Test: `skills/annotate/tests/test_smoke_dismiss_lock.py::test_busy_lock_consumed_in_script` and `::test_single_editor_guard_in_script` (from Task 5)

- [ ] **Step 1: Confirm the target tests are failing**

Run: `python3 -m pytest skills/annotate/tests/test_smoke_dismiss_lock.py -k "busy_lock_consumed or single_editor_guard" -v`
Expected: FAIL (neither `data.busy` nor `is-editing` is wired yet).

- [ ] **Step 2: Add the `setBusy` helper and call it from `onPollDelta`**

In `skills/annotate/static/script.js`, add this function just before `onPollDelta` (before line 976):

```javascript
  // Server-authoritative page lock. `data.busy` is true while any submitted
  // event is unacked; reflect it as body.is-busy + a banner. Survives reload
  // and is consistent across devices because it is recomputed each poll.
  function setBusy(busy) {
    document.body.classList.toggle("is-busy", !!busy);
    let banner = document.getElementById("busy-banner");
    if (busy) {
      if (!banner) {
        banner = document.createElement("div");
        banner.id = "busy-banner";
        banner.className = "busy-banner";
        const spin = document.createElement("span");
        spin.className = "busy-spinner";
        const label = document.createElement("span");
        label.textContent = "Claude is updating the plan… the page is locked until it replies.";
        banner.append(spin, label);
        proseEl?.parentNode?.insertBefore(banner, proseEl);
      }
    } else if (banner) {
      banner.remove();
    }
  }
```

Then, inside `onPollDelta`, add a call as the first line of the function body (after the opening `function onPollDelta(data) {`, before the `handleConsumedEvents` call at line 978):

```javascript
    setBusy(data.busy);
```

- [ ] **Step 3: Toggle `is-editing` from `renderComments`**

In `skills/annotate/static/script.js`, at the end of `renderComments` (after the final `for (const [blockId, items] ...)` loop, after line 853), add:

```javascript
    // EDITING lock: any open comment card means one editor is active.
    document.body.classList.toggle("is-editing", Object.keys(annotations).length > 0);
```

- [ ] **Step 4: Block opening a second editor in `onHoverAction`**

In `skills/annotate/static/script.js`, in `onHoverAction`, right after `existingId` is computed (after line 179, before the `const id = existingId || ...` line at 181), add:

```javascript
    // Single-flight: refuse to open a second editor while one is already open
    // for a different target, and refuse entirely while the page is BUSY.
    if (document.body.classList.contains("is-busy")) return;
    if (!existingId && Object.keys(annotations).length > 0) return;
```

- [ ] **Step 5: Run the target smoke tests to verify they pass**

Run: `python3 -m pytest skills/annotate/tests/test_smoke_dismiss_lock.py -v`
Expected: PASS (all four — CSS from Task 5, dismiss from Task 6, busy + editing now).

- [ ] **Step 6: Commit**

```bash
git add skills/annotate/static/script.js
git commit -m "feat(annotate): page-wide busy lock + single open editor"
```

---

## Task 8: Manual e2e + full-suite verification

Add a Playwright e2e mirroring the existing `tests/e2e/*.e2e.cjs` pattern, and run the whole Python suite to confirm no regressions.

**Files:**
- Create: `skills/annotate/tests/e2e/dismiss.e2e.cjs`

- [ ] **Step 1: Write the e2e script**

Create `skills/annotate/tests/e2e/dismiss.e2e.cjs`, modeled on `skills/annotate/tests/e2e/search.e2e.cjs` (reuse its `startServer`, `postJSON`, `log`, `fail` helpers — copy that file's top-of-file scaffolding verbatim, then replace the scenario body). The scenario:

```javascript
/*
 * Playwright e2e for annotate dismiss + page lock.
 *
 * Seeds 3 markdown blocks, then:
 *  - hovers block 2, clicks the × (dismiss) affordance
 *  - asserts /poll reports busy:true while the event is unacked
 *  - asserts the page shows the .busy-banner and body has class is-busy
 *  - asserts other blocks' hover-actions are pointer-events:none while busy
 *  - simulates Claude: remove block 2 from blocks.json + write the .ack
 *  - asserts the banner clears, body loses is-busy, and block 2 is gone
 *
 * Run:
 *   NODE_PATH=$(npm root -g) node skills/annotate/tests/e2e/dismiss.e2e.cjs
 * (requires the global `playwright` package + an installed chromium)
 */
```

Implement the steps using the same primitives `search.e2e.cjs` uses: `postJSON(port, "/api/sessions", {cwd})` to create the session, write `blocks.json` into `response_dir` with `fs.writeFileSync`, `page.goto(info.url + "s/" + sid + "/")`, `page.hover('section.block[data-block-id="section-2"]')`, `page.click('section.block[data-block-id="section-2"] .hover-actions button[data-type="dismiss"]')`. Simulate Claude's ack by writing the updated `blocks.json` (block-2 removed) and creating `<state_dir>/consumed/<event_id>.ack` — read the event id from the single `*.json` file in `events_dir`. Use `page.waitForSelector('section.block[data-block-id="section-2"]', { state: "detached" })` for the final assertion and `page.waitForSelector(".busy-banner", { state: "detached" })` for the unlock.

- [ ] **Step 2: Run the e2e (requires global playwright + chromium)**

Run: `NODE_PATH=$(npm root -g) node skills/annotate/tests/e2e/dismiss.e2e.cjs`
Expected: prints per-assertion log lines and exits 0. If `playwright` is not installed globally, note that this e2e is manual (same constraint as `search.e2e.cjs`) and proceed — the structural smoke tests (Task 5) are the CI-level guard.

- [ ] **Step 3: Run the full Python test suite**

Run: `python3 -m pytest skills/annotate/tests/ -v`
Expected: all PASS, including the new `test_blocks.py`, `test_server.py`, and `test_smoke_dismiss_lock.py` cases.

- [ ] **Step 4: Commit**

```bash
git add skills/annotate/tests/e2e/dismiss.e2e.cjs
git commit -m "test(annotate): e2e for dismiss + page lock round-trip"
```

---

## Self-review notes (author checklist — already applied)

- **Spec coverage:** dismiss action (Tasks 1,2,4,5,6) · single-flight lock (Tasks 3,5,7) · server-authoritative busy via `/poll` (Task 3,7) · delete≠reject instruction (Task 4) · smart-drop (Task 4 contract) · no undo/no trace (Task 6 removes via reconcile, no restore UI) · glossary drop on dismiss (Task 4). All spec sections map to a task.
- **Type consistency:** `remove_block(doc, block_id) -> bool` used identically in Task 1 and referenced in Task 4. Event `type: "dismiss"` consistent across server (Task 2), docs (Task 4), client (Task 6). Body classes `is-busy` / `is-editing` and `.busy-banner` consistent across CSS (Task 5) and JS (Tasks 6,7). `data.busy` field consistent across server (Task 3) and client (Task 7).
- **Placeholder scan:** every code step shows real code; the only prose-described step is the e2e scenario (Task 8), which points at a concrete existing template (`search.e2e.cjs`) and lists exact selectors and primitives.

## Open implementation questions (carried from spec — resolved here)

- *Per-block updating overlay vs. global lock:* kept the existing per-block overlay for immediate feedback (Task 6 calls `startUpdatingOverlay`) **and** layered the global lock on top (Task 7). They compose; no removal of the overlay code needed.
- *`busy` in shared poll contract vs. annotate-specific:* implemented annotate-specific (in `skills/annotate/server.py:serve_poll`). If `interactive_review` later wants the same guarantee, promote it to the shared `web_companion` poll contract then — out of scope here.
