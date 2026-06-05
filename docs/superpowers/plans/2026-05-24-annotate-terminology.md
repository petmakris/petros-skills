# Annotate Terminology Glossary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an inline-pill glossary surface to the annotate skill so project-specific identifiers get a hover popover with a 1-line definition + role in the current response.

**Architecture:** `blocks.json` grows a sibling `glossary` array (`[{term, definition, role}]`). The server ships it to the client unchanged; the client decorates rendered block HTML by wrapping case-sensitive whole-word matches in `<span class="gloss-term">`. A new `/api/glossary_refresh` endpoint queues a custom watcher event that wakes Claude to regenerate the array on demand. Composition rules and rewrite rules are documented in `SKILL.md`; no separate LLM pass.

**Note on spec deviation:** The spec describes the term decoration as *server-side* ("In `_render_block_for_raw`, after the block's markdown is rendered to HTML…"). The actual codebase renders markdown to HTML on the *client* via `markdown-it`. The plan therefore implements decoration client-side — same architectural intent (decorate after markdown→HTML), different side of the wire. No server-side HTML rendering is added.

**Tech Stack:** Python 3 stdlib HTTP server, vanilla JS + `markdown-it` on the client, pytest, Playwright (existing e2e harness).

**Spec:** [docs/superpowers/specs/2026-05-24-annotate-terminology-design.md](../specs/2026-05-24-annotate-terminology-design.md)

---

## File Structure

**Modified:**
- `skills/annotate/blocks.py` — add `glossary` field to `BlocksDoc`, persist via `save_atomic`/`load`, expose a `drop_unused_terms()` helper.
- `skills/annotate/server.py` — extend `_render_block_for_raw`-adjacent code to include `glossary` in the `/raw` API response; add `serve_glossary_refresh` handler wired into `serve_data`; add the "Decode more" button to `serve_root` body and load new static assets.
- `skills/annotate/SKILL.md` — add the composition self-check (which terms qualify), the rewrite-time term-set diff rule, and a Mode D case for `type: "glossary_refresh"` events.
- `skills/annotate/tests/test_blocks.py` — extend with glossary load/save and `drop_unused_terms` tests.
- `skills/annotate/tests/test_server.py` — extend with `/raw` glossary echo + `/glossary_refresh` endpoint tests.
- `skills/annotate/static/script.js` — invoke `decorateGlossary()` after each block render; hook the "Decode more" click.

**Created:**
- `skills/annotate/static/popover.js` — `decorateGlossary(rootEl, glossary)` + popover open/close behavior.
- `skills/annotate/static/popover.css` — pill underline + popover styling.
- `skills/annotate/tests/test_smoke_e2e_glossary.py` — Playwright e2e mirroring `test_smoke_e2e_diagram.py`.

---

## Task 1: Extend `BlocksDoc` with `glossary` field

**Files:**
- Modify: `skills/annotate/blocks.py:22-50`
- Test: `skills/annotate/tests/test_blocks.py`

- [ ] **Step 1: Write failing tests for glossary load/save**

Append to `skills/annotate/tests/test_blocks.py`:

```python
def test_load_includes_glossary(tmp_path):
    path = tmp_path / "blocks.json"
    path.write_text(json.dumps({
        "response_id": "r-1", "title": "t",
        "blocks": [{"id": "b-0", "markdown": "hi", "version": 1}],
        "glossary": [
            {"term": "OnboardingOrchestrator",
             "definition": "Internal service coordinating new-user signup.",
             "role": "Upstream that emits the payload too early."},
        ],
    }))
    doc = load(path)
    assert doc.glossary == [
        {"term": "OnboardingOrchestrator",
         "definition": "Internal service coordinating new-user signup.",
         "role": "Upstream that emits the payload too early."},
    ]


def test_load_missing_glossary_defaults_to_empty():
    from pathlib import Path
    doc = load(Path("/nonexistent.json"))
    assert doc.glossary == []


def test_save_round_trips_glossary(tmp_path):
    path = tmp_path / "blocks.json"
    doc = BlocksDoc(
        response_id="r-1", title="t",
        blocks=[{"id": "b-0", "markdown": "hi", "version": 1}],
        glossary=[{"term": "Foo", "definition": "a foo", "role": "the bar"}],
    )
    save_atomic(path, doc)
    raw = json.loads(path.read_text())
    assert raw["glossary"] == [{"term": "Foo", "definition": "a foo", "role": "the bar"}]
    doc2 = load(path)
    assert doc2.glossary == doc.glossary


def test_save_omits_glossary_key_when_empty(tmp_path):
    path = tmp_path / "blocks.json"
    doc = BlocksDoc(response_id="r-1", title="t",
                    blocks=[{"id": "b-0", "markdown": "hi", "version": 1}])
    save_atomic(path, doc)
    raw = json.loads(path.read_text())
    assert "glossary" not in raw
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest skills/annotate/tests/test_blocks.py -v -k glossary`
Expected: 4 FAIL with `AttributeError: 'BlocksDoc' object has no attribute 'glossary'` (or `KeyError` in the save round-trip).

- [ ] **Step 3: Add `glossary` to `BlocksDoc` and persistence**

Edit `skills/annotate/blocks.py`. Replace the `BlocksDoc` dataclass:

```python
@dataclass
class BlocksDoc:
    response_id: str = ""
    title: str = ""
    blocks: list[dict[str, Any]] = field(default_factory=list)
    glossary: list[dict[str, Any]] = field(default_factory=list)
```

Update `load` to read it:

```python
def load(path: Path) -> BlocksDoc:
    path = Path(path)
    if not path.exists():
        return BlocksDoc()
    try:
        raw = json.loads(path.read_text())
    except (json.JSONDecodeError, OSError):
        return BlocksDoc()
    return BlocksDoc(
        response_id=raw.get("response_id", ""),
        title=raw.get("title", ""),
        blocks=list(raw.get("blocks") or []),
        glossary=list(raw.get("glossary") or []),
    )
```

Update `save_atomic` to write it (omit when empty to keep existing JSON minimal):

```python
def save_atomic(path: Path, doc: BlocksDoc) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    out: dict[str, Any] = {
        "response_id": doc.response_id,
        "title": doc.title,
        "blocks": doc.blocks,
    }
    if doc.glossary:
        out["glossary"] = doc.glossary
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(out, indent=2))
    tmp.replace(path)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest skills/annotate/tests/test_blocks.py -v`
Expected: PASS (all 4 new tests plus existing 13).

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/blocks.py skills/annotate/tests/test_blocks.py
git commit -m "annotate: add glossary field to BlocksDoc"
```

---

## Task 2: Add `drop_unused_terms` helper

**Files:**
- Modify: `skills/annotate/blocks.py` (append helper)
- Test: `skills/annotate/tests/test_blocks.py` (append tests)

Used by Mode D rewrites to drop glossary entries whose term no longer appears in any block. Case-sensitive whole-word match. Mirrors what the client uses to decide which spans to decorate, so server-side cleanup and client-side decoration stay aligned.

- [ ] **Step 1: Write failing tests**

Append to `skills/annotate/tests/test_blocks.py`:

```python
from skills.annotate.blocks import drop_unused_terms


def test_drop_unused_terms_removes_orphan_entries():
    doc = BlocksDoc(
        response_id="r-1", title="t",
        blocks=[
            {"id": "b-0", "markdown": "Use OnboardingOrchestrator here.", "version": 1},
        ],
        glossary=[
            {"term": "OnboardingOrchestrator", "definition": "...", "role": "..."},
            {"term": "InsightsAggregator", "definition": "...", "role": "..."},  # orphan
        ],
    )
    changed = drop_unused_terms(doc)
    assert changed is True
    assert [g["term"] for g in doc.glossary] == ["OnboardingOrchestrator"]


def test_drop_unused_terms_no_op_when_all_referenced():
    doc = BlocksDoc(
        response_id="r-1", title="t",
        blocks=[{"id": "b-0", "markdown": "Foo and Bar.", "version": 1}],
        glossary=[
            {"term": "Foo", "definition": "...", "role": "..."},
            {"term": "Bar", "definition": "...", "role": "..."},
        ],
    )
    changed = drop_unused_terms(doc)
    assert changed is False
    assert len(doc.glossary) == 2


def test_drop_unused_terms_case_sensitive():
    # "foo" lowercase should NOT match the entry for "Foo".
    doc = BlocksDoc(
        response_id="r-1", title="t",
        blocks=[{"id": "b-0", "markdown": "the foo bar", "version": 1}],
        glossary=[{"term": "Foo", "definition": "...", "role": "..."}],
    )
    changed = drop_unused_terms(doc)
    assert changed is True
    assert doc.glossary == []


def test_drop_unused_terms_whole_word_only():
    # "Aggregator" should NOT match inside "Aggregators".
    doc = BlocksDoc(
        response_id="r-1", title="t",
        blocks=[{"id": "b-0", "markdown": "We have many Aggregators here.", "version": 1}],
        glossary=[{"term": "Aggregator", "definition": "...", "role": "..."}],
    )
    changed = drop_unused_terms(doc)
    assert changed is True
    assert doc.glossary == []


def test_drop_unused_terms_scans_all_blocks():
    doc = BlocksDoc(
        response_id="r-1", title="t",
        blocks=[
            {"id": "b-0", "markdown": "intro", "version": 1},
            {"id": "b-1", "markdown": "see Foo for details", "version": 1},
        ],
        glossary=[{"term": "Foo", "definition": "...", "role": "..."}],
    )
    changed = drop_unused_terms(doc)
    assert changed is False
    assert len(doc.glossary) == 1
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest skills/annotate/tests/test_blocks.py -v -k drop_unused`
Expected: 5 FAIL with `ImportError: cannot import name 'drop_unused_terms'`.

- [ ] **Step 3: Implement `drop_unused_terms`**

Append to `skills/annotate/blocks.py`:

```python
import re as _re


def _term_appears(term: str, text: str) -> bool:
    """Case-sensitive whole-word match for `term` in `text`."""
    if not term:
        return False
    pattern = r"(?<![A-Za-z0-9_])" + _re.escape(term) + r"(?![A-Za-z0-9_])"
    return _re.search(pattern, text) is not None


def drop_unused_terms(doc: BlocksDoc) -> bool:
    """Remove glossary entries whose `term` does not appear in any block.

    Returns True if any entries were dropped.
    """
    # Concatenate markdown from all markdown blocks; sequence-block specs
    # are skipped (terms in spec labels are out of scope for v1).
    haystack = "\n".join(b.get("markdown", "") for b in doc.blocks
                         if (b.get("kind") or "markdown") == "markdown")
    kept = [g for g in doc.glossary if _term_appears(g.get("term", ""), haystack)]
    if len(kept) == len(doc.glossary):
        return False
    doc.glossary = kept
    return True
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest skills/annotate/tests/test_blocks.py -v`
Expected: PASS (5 new + 17 existing).

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/blocks.py skills/annotate/tests/test_blocks.py
git commit -m "annotate: add drop_unused_terms helper for glossary cleanup"
```

---

## Task 3: Echo `glossary` in `/raw` and `/raw?block=…` API responses

**Files:**
- Modify: `skills/annotate/server.py:96-119`
- Test: `skills/annotate/tests/test_server.py`

The `/raw` endpoint (no query) currently returns `{response_id, title, blocks}`. Extend it to also return `glossary` so the client can fetch it once at load and on every refresh.

- [ ] **Step 1: Write failing test**

The existing `/raw` tests live in `test_server.py` in the same `TestCase` that uses `self.sess`, `self.info`, `self.base`, and helper `_write_blocks(response_dir, response_id, title, blocks)`. Add two methods to that same class (right after `test_raw_returns_full_blocks_json`):

```python
    def test_raw_includes_glossary(self):
        response_dir = Path(self.sess["response_dir"])
        # _write_blocks does not handle glossary — use BlocksDoc directly.
        from skills.annotate.blocks import BlocksDoc, save_atomic
        doc = BlocksDoc(
            response_id="r-gloss", title="T",
            blocks=[{"id": "b-0", "markdown": "x", "version": 1}],
            glossary=[{"term": "Foo", "definition": "a foo", "role": "the bar"}],
        )
        save_atomic(response_dir / "blocks.json", doc)
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("GET", self.base + "/raw")
        resp = conn.getresponse()
        self.assertEqual(resp.status, 200)
        data = json.loads(resp.read().decode("utf-8"))
        conn.close()
        self.assertEqual(data["glossary"], [
            {"term": "Foo", "definition": "a foo", "role": "the bar"}
        ])

    def test_raw_empty_glossary_returns_empty_list(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-noglos", "T", [
            {"id": "b-0", "markdown": "x", "version": 1},
        ])
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("GET", self.base + "/raw")
        resp = conn.getresponse()
        self.assertEqual(resp.status, 200)
        data = json.loads(resp.read().decode("utf-8"))
        conn.close()
        self.assertEqual(data["glossary"], [])
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest skills/annotate/tests/test_server.py -v -k glossary`
Expected: 2 FAIL with `KeyError: 'glossary'`.

- [ ] **Step 3: Add glossary to the API response**

Edit `skills/annotate/server.py:113-117`. Change:

```python
            _send_json(h, 200, {
                "response_id": doc.response_id,
                "title": doc.title,
                "blocks": [_render_block_for_raw(b) for b in doc.blocks],
            })
```

To:

```python
            _send_json(h, 200, {
                "response_id": doc.response_id,
                "title": doc.title,
                "blocks": [_render_block_for_raw(b) for b in doc.blocks],
                "glossary": list(doc.glossary),
            })
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `pytest skills/annotate/tests/test_server.py -v -k glossary`
Expected: 2 PASS.

Also confirm no existing tests broke: `pytest skills/annotate/tests/test_server.py -v`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/server.py skills/annotate/tests/test_server.py
git commit -m "annotate: echo glossary array in /raw API response"
```

---

## Task 4: Add `/api/glossary_refresh` endpoint

**Files:**
- Modify: `skills/annotate/server.py` (add `handle_glossary_refresh`, wire into routing alongside `handle_submit` / `handle_cancel`)
- Test: `skills/annotate/tests/test_server.py`

The "Decode more" button POSTs here. Server appends an event with `type: "glossary_refresh"` into the session's `events_dir`. The watcher emits `WEBCOMPANION_EVENT` and Claude handles it via the existing Mode D path (updated in Task 7).

Existing per-action endpoints live at `<base>/api/submit` and `<base>/api/cancel`. Add `/api/glossary_refresh` to match.

- [ ] **Step 1: Confirm the dispatch contract**

Open `skills/_shared/web_companion/server.py` and locate where `/api/submit` and `/api/cancel` are dispatched. Pattern is likely a method-name lookup (e.g. `handle_<action>(h, dirs, payload)`). Note the exact dispatch mechanism — you'll mirror it.

- [ ] **Step 2: Write failing tests**

Append two methods to the same `TestCase` class in `test_server.py` (right after `test_submit_returns_409_when_terminal`):

```python
    def test_glossary_refresh_appends_event(self):
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/glossary_refresh", body="",
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 202)
        body = json.loads(resp.read().decode("utf-8"))
        conn.close()
        self.assertIn("event_id", body)
        self.assertEqual(body["status"], "queued")
        events_dir = Path(self.sess["events_dir"])
        event_files = list(events_dir.glob("*.json"))
        self.assertEqual(len(event_files), 1)
        evt = json.loads(event_files[0].read_text())
        self.assertEqual(evt["type"], "glossary_refresh")
        self.assertIsNone(evt["block_id"])

    def test_glossary_refresh_returns_409_when_terminal(self):
        (Path(self.sess["state_dir"]) / "finished").write_text("")
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", self.base + "/api/glossary_refresh", body="",
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        self.assertEqual(resp.status, 409)
        conn.close()
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `pytest skills/annotate/tests/test_server.py -v -k glossary_refresh`
Expected: 2 FAIL — most likely 404 (unknown route).

- [ ] **Step 4: Wire the endpoint in `Handlers`**

In `skills/annotate/server.py`, add a method to the `Handlers` class. Place it alongside `handle_submit`:

```python
    def handle_glossary_refresh(self, h: BaseHTTPRequestHandler, dirs: dict, payload: dict) -> None:
        if _is_terminal(Path(dirs["state_dir"])):
            _send_text(h, 409, "session closed")
            return
        evt = {
            "type": "glossary_refresh",
            "block_id": None,
            "step_id": None,
            "text": "",
            "selected_text": None,
            "images": [],
        }
        eid = events_module.append(Path(dirs["events_dir"]), evt)
        _send_json(h, 202, {"event_id": eid, "status": "queued"})
```

If `skills/_shared/web_companion/server.py` auto-dispatches by `handle_<action>` method name (confirmed in step 1), this is enough. If it uses an explicit registry/dict, also add a line registering `"glossary_refresh": self.handle_glossary_refresh` wherever `"submit"`/`"cancel"` are registered (likely in `server.py` or in a routing setup at the top of `Handlers`).

- [ ] **Step 5: Run tests to verify they pass**

Run: `pytest skills/annotate/tests/test_server.py -v -k glossary_refresh`
Expected: 2 PASS.

Also: `pytest skills/annotate/tests/test_server.py -v`
Expected: ALL PASS.

- [ ] **Step 6: Commit**

```bash
git add skills/annotate/server.py skills/annotate/tests/test_server.py
git commit -m "annotate: add /api/glossary_refresh endpoint that queues a watcher event"
```

---

## Task 5: Client-side glossary decoration + popover

**Files:**
- Create: `skills/annotate/static/popover.js`
- Create: `skills/annotate/static/popover.css`
- Modify: `skills/annotate/static/script.js` (call `decorateGlossary` after each block render; cache glossary on fetch)
- Modify: `skills/annotate/server.py:85-87` (load the new CSS + JS in `<head>`)

`decorateGlossary(rootEl, glossary)` walks text nodes inside `rootEl`, skipping any node whose ancestor chain includes `<code>` or `<pre>`. For each remaining text node, it scans for case-sensitive whole-word matches of any glossary `term` and replaces those substrings with `<span class="gloss-term" data-term="…">…</span>`.

The popover is a single absolutely-positioned `<div id="gloss-popover">` injected into `document.body` at module load. Mouseenter on a `.gloss-term` populates and positions it; mouseleave hides it.

- [ ] **Step 1: Create `popover.css`**

Write `skills/annotate/static/popover.css`:

```css
.gloss-term {
  border-bottom: 1.5px dotted var(--accent, #2563eb);
  cursor: help;
  background: rgba(37, 99, 235, 0.08);
  padding: 0 2px;
  border-radius: 2px;
}

#gloss-popover {
  position: absolute;
  display: none;
  max-width: 320px;
  background: #1f2937;
  color: #fff;
  padding: 10px 12px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.45;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.25);
  z-index: 1000;
  pointer-events: none;
}

#gloss-popover .gp-term { font-weight: 700; display: block; margin-bottom: 4px; }
#gloss-popover .gp-def  { display: block; margin-bottom: 6px; }
#gloss-popover .gp-role { display: block; font-style: italic; opacity: 0.85; }

.decode-more-btn {
  font-size: 12px;
  border: 1px solid var(--border, #d4d4d4);
  background: #fff;
  color: var(--accent, #2563eb);
  padding: 4px 10px;
  border-radius: 14px;
  cursor: pointer;
  margin-right: 8px;
}
.decode-more-btn:hover { background: #f5f5f5; }
.decode-more-btn:disabled { opacity: 0.5; cursor: wait; }
```

- [ ] **Step 2: Create `popover.js`**

Write `skills/annotate/static/popover.js`:

```javascript
// Glossary decoration and hover popover.
// Exposes window.AnnotateGlossary = { setGlossary, decorate, refreshAll }.

(function () {
  let glossary = [];
  let popoverEl = null;

  function ensurePopover() {
    if (popoverEl) return popoverEl;
    popoverEl = document.createElement("div");
    popoverEl.id = "gloss-popover";
    popoverEl.innerHTML =
      '<span class="gp-term"></span><span class="gp-def"></span><span class="gp-role"></span>';
    document.body.appendChild(popoverEl);
    return popoverEl;
  }

  function escapeForRegex(s) {
    return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }

  function buildPattern(terms) {
    if (!terms.length) return null;
    // Sort longest-first so multi-word terms match before any substring would.
    const sorted = terms.slice().sort((a, b) => b.length - a.length);
    const alts = sorted.map(escapeForRegex).join("|");
    // Word boundaries that treat [A-Za-z0-9_] as word chars.
    return new RegExp("(?<![A-Za-z0-9_])(" + alts + ")(?![A-Za-z0-9_])");
  }

  function isInsideSkipped(node) {
    let n = node.parentNode;
    while (n && n !== document.body) {
      const tag = n.nodeName;
      if (tag === "CODE" || tag === "PRE") return true;
      if (n.classList && n.classList.contains("gloss-term")) return true;
      n = n.parentNode;
    }
    return false;
  }

  function decorateTextNode(node, pattern, termSet) {
    const text = node.nodeValue;
    pattern.lastIndex = 0;
    if (!pattern.test(text)) return;
    // Build replacement nodes.
    const frag = document.createDocumentFragment();
    let remaining = text;
    // Re-run with /g for iteration.
    const gPattern = new RegExp(pattern.source, "g");
    let lastIdx = 0;
    let m;
    while ((m = gPattern.exec(text)) !== null) {
      const term = m[1];
      if (!termSet.has(term)) continue;
      if (m.index > lastIdx) {
        frag.appendChild(document.createTextNode(text.slice(lastIdx, m.index)));
      }
      const span = document.createElement("span");
      span.className = "gloss-term";
      span.setAttribute("data-term", term);
      span.textContent = term;
      frag.appendChild(span);
      lastIdx = m.index + term.length;
    }
    if (lastIdx < text.length) {
      frag.appendChild(document.createTextNode(text.slice(lastIdx)));
    }
    if (lastIdx > 0) node.parentNode.replaceChild(frag, node);
  }

  function decorate(rootEl) {
    if (!rootEl || !glossary.length) return;
    const terms = glossary.map((g) => g.term).filter(Boolean);
    if (!terms.length) return;
    const pattern = buildPattern(terms);
    const termSet = new Set(terms);
    const walker = document.createTreeWalker(rootEl, NodeFilter.SHOW_TEXT, {
      acceptNode: (n) =>
        isInsideSkipped(n) || !n.nodeValue.trim()
          ? NodeFilter.FILTER_REJECT
          : NodeFilter.FILTER_ACCEPT,
    });
    const targets = [];
    let n;
    while ((n = walker.nextNode())) targets.push(n);
    targets.forEach((tn) => decorateTextNode(tn, pattern, termSet));
  }

  function refreshAll() {
    document.querySelectorAll(".block-content").forEach((el) => {
      // Strip prior decorations (gloss-term spans → plain text) before redecorating.
      el.querySelectorAll(".gloss-term").forEach((span) => {
        span.replaceWith(document.createTextNode(span.textContent));
      });
      el.normalize();
      decorate(el);
    });
  }

  function setGlossary(newGlossary) {
    glossary = Array.isArray(newGlossary) ? newGlossary : [];
  }

  function lookup(term) {
    return glossary.find((g) => g.term === term) || null;
  }

  function showPopover(target) {
    const term = target.getAttribute("data-term");
    const entry = lookup(term);
    if (!entry) return;
    const el = ensurePopover();
    el.querySelector(".gp-term").textContent = entry.term;
    el.querySelector(".gp-def").textContent = entry.definition || "";
    el.querySelector(".gp-role").textContent = entry.role || "";
    const rect = target.getBoundingClientRect();
    el.style.display = "block";
    el.style.left = (window.scrollX + rect.left) + "px";
    el.style.top = (window.scrollY + rect.bottom + 6) + "px";
  }

  function hidePopover() {
    if (popoverEl) popoverEl.style.display = "none";
  }

  document.addEventListener("mouseover", (e) => {
    const t = e.target;
    if (t && t.classList && t.classList.contains("gloss-term")) showPopover(t);
  });
  document.addEventListener("mouseout", (e) => {
    const t = e.target;
    if (t && t.classList && t.classList.contains("gloss-term")) hidePopover();
  });

  window.AnnotateGlossary = { setGlossary, decorate, refreshAll, lookup };
})();
```

- [ ] **Step 3: Wire the new assets in `serve_root`**

Edit `skills/annotate/server.py:85-87`. Change:

```python
        head = ('<link rel="stylesheet" href="/static/style.css">'
                '<link rel="stylesheet" href="/static/diagram.css">'
                '<script src="/static/script.js" defer></script>')
```

To:

```python
        head = ('<link rel="stylesheet" href="/static/style.css">'
                '<link rel="stylesheet" href="/static/diagram.css">'
                '<link rel="stylesheet" href="/static/popover.css">'
                '<script src="/static/popover.js" defer></script>'
                '<script src="/static/script.js" defer></script>')
```

`popover.js` must load before `script.js` so the global is defined when `script.js` calls into it.

- [ ] **Step 4: Hook `decorateGlossary` into `script.js` block rendering**

Edit `skills/annotate/static/script.js`. Search for the two call sites that set `content.innerHTML = blockMd.render(blk.markdown || "")` (currently around lines 248 and 640). After each `innerHTML` assignment for the markdown branch, add:

```javascript
      if (window.AnnotateGlossary) window.AnnotateGlossary.decorate(content);
```

Also: locate the function that fetches `/raw` (search for `fetch("/raw")` or `/raw?` — there will be at least one full-doc fetch path that returns `{response_id, title, blocks}`). After parsing the response, before iterating blocks, add:

```javascript
      if (window.AnnotateGlossary) window.AnnotateGlossary.setGlossary(data.glossary || []);
```

If the full-doc fetch happens in more than one place, update each.

- [ ] **Step 5: Manual sanity check**

Build a fixture session and verify decoration end-to-end. From the repo root:

```bash
# Start the annotate server (or rely on a session you already pushed).
bash skills/annotate/ensure_server.sh
SERVER_URL=$(python3 -c 'import json,os; print(json.load(open(os.path.expanduser("~/.claude/annotate/server.json")))["url"])')
RESP=$(curl -sf -X POST "$SERVER_URL/api/sessions" -H 'Content-Type: application/json' -d "{\"cwd\": \"$PWD\"}")
RESP_DIR=$(python3 -c "import json,sys; print(json.loads('''$RESP''')['response_dir'])")
URL=$(python3 -c "import json,sys; print(json.loads('''$RESP''')['url'])")

cat > "$RESP_DIR/meta.json" <<'JSON'
{"response_id": "resp-glossary-test", "title": "glossary smoke", "claude_session_id": "test"}
JSON
cat > "$RESP_DIR/blocks.json" <<'JSON'
{
  "response_id": "resp-glossary-test",
  "title": "glossary smoke",
  "blocks": [
    {"id": "b-0", "markdown": "The bug is in OnboardingOrchestrator before InsightsAggregator runs.", "version": 1},
    {"id": "b-1", "markdown": "Look at `OnboardingOrchestrator` (should not be pilled because it's inside a code span).", "version": 1}
  ],
  "glossary": [
    {"term": "OnboardingOrchestrator", "definition": "Internal service coordinating new-user signup.", "role": "Upstream that emits too early."},
    {"term": "InsightsAggregator", "definition": "Service that batches user events.", "role": "Downstream that fails."}
  ]
}
JSON
echo "Open: $URL"
```

Open the URL in a browser. Expected:

- "OnboardingOrchestrator" and "InsightsAggregator" in block 0 have dotted underlines.
- "OnboardingOrchestrator" inside the code span in block 1 is NOT underlined.
- Hovering either pill shows a dark popover with term + definition + italic role.

- [ ] **Step 6: Commit**

```bash
git add skills/annotate/static/popover.js skills/annotate/static/popover.css \
        skills/annotate/static/script.js skills/annotate/server.py
git commit -m "annotate: render glossary as inline pills with hover popover"
```

---

## Task 6: Add "Decode more" header button

**Files:**
- Modify: `skills/annotate/server.py:73-76` (insert the button into the header HTML)
- Modify: `skills/annotate/static/script.js` (wire click → POST `/api/sessions/<sid>/glossary_refresh`)

- [ ] **Step 1: Inject the button into the header**

Edit `skills/annotate/server.py:73-76`. Change:

```python
            f'</div><div class="header-actions">'
            f'{render_theme_picker()}'
            f'<button id="done-btn" type="button" class="done-btn">Done</button>'
            f'</div></header>'
```

To:

```python
            f'</div><div class="header-actions">'
            f'{render_theme_picker()}'
            f'<button id="decode-more-btn" type="button" class="decode-more-btn" '
            f'  title="Regenerate the glossary for the current response">Decode more</button>'
            f'<button id="done-btn" type="button" class="done-btn">Done</button>'
            f'</div></header>'
```

- [ ] **Step 2: Wire the click handler in `script.js`**

Open `skills/annotate/static/script.js`. Locate where `done-btn` is wired (search for `done-btn`). Mirror the pattern. Add a handler that POSTs to the relative `glossary_refresh` path (sid is part of the page URL — the existing fetch helpers should already handle relative paths):

```javascript
  const decodeBtn = document.getElementById("decode-more-btn");
  if (decodeBtn) {
    decodeBtn.addEventListener("click", async () => {
      decodeBtn.disabled = true;
      decodeBtn.textContent = "Decoding…";
      try {
        const resp = await fetch("api/glossary_refresh", { method: "POST" });
        if (!resp.ok) throw new Error("refresh failed: " + resp.status);
      } catch (e) {
        console.error(e);
      } finally {
        // Re-enable after a short delay; the watcher will push an updated
        // blocks.json + glossary via the existing poll loop, which redecorates.
        setTimeout(() => {
          decodeBtn.disabled = false;
          decodeBtn.textContent = "Decode more";
        }, 1500);
      }
    });
  }
```

Confirm the relative URL works against the existing fetch paths. If other POSTs use a session-scoped prefix (e.g. `api/`), match that pattern instead.

- [ ] **Step 3: Re-decorate on poll-driven refresh**

Find the existing poll/refresh path in `script.js` that re-renders a block on version change (search around line 636-640). After it re-renders a block, ensure `decorateGlossary(content)` runs on the new content (already covered by Task 5 if you placed the decorate call right after every `innerHTML = blockMd.render(...)` assignment — double-check it's wired on the refresh branch too).

Then, when a full-doc refetch happens (after `glossary_refresh` should land an updated `blocks.json` that bumps the version of any glossary-only changes — actually the watcher doesn't bump block versions for a glossary refresh, so the existing version-check won't trip). Add a periodic glossary refetch:

In the poll-loop callback, after the existing version-diff handling, add:

```javascript
        // Always refetch glossary on poll — it's small and may have changed
        // without any block version bumping (Decode-more replaces glossary only).
        try {
          const docResp = await fetch("raw");  // existing `/raw` endpoint, relative to the session base
          if (docResp.ok) {
            const doc = await docResp.json();
            const prev = JSON.stringify(window.AnnotateGlossary ? (window.AnnotateGlossary._lastGlossary || []) : []);
            const next = JSON.stringify(doc.glossary || []);
            if (next !== prev && window.AnnotateGlossary) {
              window.AnnotateGlossary.setGlossary(doc.glossary || []);
              window.AnnotateGlossary._lastGlossary = doc.glossary || [];
              window.AnnotateGlossary.refreshAll();
            }
          }
        } catch (_) { /* swallow — next tick retries */ }
```

(`_lastGlossary` is a tiny convenience field on the global to avoid redecorating when nothing changed. Add it to the `AnnotateGlossary` IIFE state in Task 5 if you want it as a real property; otherwise the inline field as shown is fine.)

- [ ] **Step 4: Manual sanity check**

Re-run the fixture from Task 5 step 5. Then manually edit `blocks.json` (or write a small Python snippet) to *remove* one of the glossary entries while the page is open. Within a poll tick, the corresponding pill should lose its underline (because `refreshAll` is called and the term is no longer in the glossary).

Also click "Decode more". The watcher will emit a `WEBCOMPANION_EVENT` — without Task 7 yet, Claude won't act on it, but the network tab should show a 202 from the POST.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/server.py skills/annotate/static/script.js
git commit -m "annotate: add 'Decode more' header button + poll-driven glossary refresh"
```

---

## Task 7: Update `SKILL.md` — composition, rewrite, Mode D case

**Files:**
- Modify: `skills/annotate/SKILL.md`

Documentation-only task. Adds three pieces of guidance so future invocations of the skill compose and rewrite the glossary correctly. No code, no tests.

- [ ] **Step 1: Add a "Glossary" section after "Diagram block shape"**

Open `skills/annotate/SKILL.md`. After the existing "Diagram block shape" section (ends around line 164, just before step 4 of the "How to push a response" enumeration), insert a new top-level section:

````markdown
## Glossary (terminology surface)

`blocks.json` may include a sibling `glossary` array next to `blocks`:

```json
{
  "response_id": "...",
  "title": "...",
  "blocks": [...],
  "glossary": [
    {"term": "OnboardingOrchestrator",
     "definition": "Internal service coordinating new-user signup.",
     "role": "Upstream that emits the payload too early — the trigger of the bug."}
  ]
}
```

The client decorates matching terms in rendered block prose with a hover popover. Omit the field when no terms qualify.

### When to emit a glossary entry

While composing the blocks, ask yourself, for each project- or context-specific identifier that appears:

> If the reader didn't know this term, could they still follow this response?

Emit an entry **only when the answer is no**. Exclude any term that a competent engineer would resolve by Googling — `SQL`, `idempotent`, `mutex`, `hydration`, framework names, standard protocols, common patterns. Include identifiers that are unique to the user's project or that name a concept introduced by the current conversation.

Each entry has three fields:

- `term` — the exact string as it appears in the prose. Case-sensitive.
- `definition` — one line, generic (what this thing is).
- `role` — one line, contextual (what this thing does in *this specific response*).

The `role` field is what makes the glossary useful for debugging — it tells the reader why the term matters here, not just what it generically is.

### Term-set diff at rewrite time

When you handle a `WEBCOMPANION_EVENT` that targets a markdown block:

1. After composing the rewritten block markdown, apply the **drop rule**: any glossary entry whose `term` no longer appears (case-sensitive whole-word) in any block is dropped. Use `blocks.drop_unused_terms(doc)` — it does this in one call.
2. Apply the **add rule**: if the rewrite introduces a new project-specific identifier that wasn't already in the glossary and that meets the comprehension-blocker test above, append a new entry.

Do not re-extract the whole glossary on every rewrite. The common case — a rewrite that doesn't touch the term set — produces no glossary mutation.
````

- [ ] **Step 2: Add the Mode D case for `glossary_refresh`**

In the "Mode D — handling a watcher event" section, after the `WEBCOMPANION_EVENT` subsection, append a new sub-section:

````markdown
### `WEBCOMPANION_EVENT` with `type: "glossary_refresh"`

Fired when the user clicks the "Decode more" button. `block_id` is `null`.

1. Read `<response_dir>/blocks.json`.
2. Regenerate the `glossary` array wholesale: scan all markdown blocks, apply the comprehension-blocker test, and produce a fresh list of `{term, definition, role}` entries.
3. Replace `doc.glossary` with the new list. Do **not** touch `blocks` or bump any block versions.
4. `save_atomic` the doc, write the `<consumed_dir>/<event_id>.ack`, end your turn.

The client polls and will pick up the new glossary on the next tick without needing a block version bump.
````

- [ ] **Step 3: Update token-budget section**

Find the "Token budget" section at the bottom of `SKILL.md`. Add this paragraph after the existing prose:

```markdown
**Glossary additions.** When a response includes a glossary, each entry adds roughly 45–80 tokens. A typical response with 2–4 entries adds 100–320 tokens (≈25–50% on top of a 600-token response). Rewrites that don't change the term set add nothing. A `glossary_refresh` event costs one focused pass — 150–400 tokens.
```

- [ ] **Step 4: Sanity check**

Read the diff: `git diff skills/annotate/SKILL.md`. Confirm the three insertions are present and well-formed. No code to run.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/SKILL.md
git commit -m "annotate: document glossary composition, rewrite diff, and refresh event"
```

---

## Task 8: Playwright e2e smoke test

**Files:**
- Create: `skills/annotate/tests/test_smoke_e2e_glossary.py`

Mirrors the structure of `test_smoke_e2e_diagram.py` (which already exercises a full browser-driven round trip against the annotate server). Asserts pills appear, the popover shows the right content, and the "Decode more" button posts a glossary_refresh event.

- [ ] **Step 1: Read the existing e2e harness**

Read `skills/annotate/tests/test_smoke_e2e_diagram.py` end-to-end. Note the fixtures it uses (server startup, session creation, browser harness — likely Playwright sync), how it writes a `blocks.json`, and how it asserts on rendered output.

- [ ] **Step 2: Write the failing test**

Create `skills/annotate/tests/test_smoke_e2e_glossary.py`. Mirror the diagram smoke test's setup. Skeleton (adapt the imports + fixture names to match the diagram test):

```python
import json
from pathlib import Path

import pytest


pytestmark = pytest.mark.e2e


def _write_session(response_dir: Path) -> None:
    (response_dir / "meta.json").write_text(json.dumps({
        "response_id": "resp-glossary-e2e",
        "title": "glossary e2e",
        "claude_session_id": "test",
    }))
    (response_dir / "blocks.json").write_text(json.dumps({
        "response_id": "resp-glossary-e2e",
        "title": "glossary e2e",
        "blocks": [
            {"id": "b-0",
             "markdown": "The OnboardingOrchestrator emits to InsightsAggregator.",
             "version": 1},
            {"id": "b-1",
             "markdown": "See `OnboardingOrchestrator` (this one is in code, no pill).",
             "version": 1},
        ],
        "glossary": [
            {"term": "OnboardingOrchestrator",
             "definition": "Internal service coordinating new-user signup.",
             "role": "Upstream that emits too early."},
            {"term": "InsightsAggregator",
             "definition": "Service batching user events for the warehouse.",
             "role": "Downstream that fails because the user row isn't visible yet."},
        ],
    }))


def test_glossary_pills_render_and_popover_works(annotate_session, page):
    # `annotate_session` is the fixture from test_smoke_e2e_diagram.py that
    # spins up the server and gives you {url, response_dir, events_dir, ...}.
    # `page` is a Playwright page fixture (also reused).
    _write_session(Path(annotate_session["response_dir"]))
    page.goto(annotate_session["url"])
    page.wait_for_selector(".gloss-term")
    pills = page.locator(".gloss-term").all_text_contents()
    assert "OnboardingOrchestrator" in pills
    assert "InsightsAggregator" in pills
    # The code-span occurrence in b-1 should NOT have produced a pill.
    code_span_term = page.locator("code:has-text('OnboardingOrchestrator') .gloss-term")
    assert code_span_term.count() == 0
    # Hover the first pill, expect the popover with the role text.
    page.locator(".gloss-term").first.hover()
    page.wait_for_selector("#gloss-popover", state="visible")
    assert "emits too early" in page.locator("#gloss-popover").inner_text()


def test_decode_more_button_posts_glossary_refresh(annotate_session, page):
    _write_session(Path(annotate_session["response_dir"]))
    page.goto(annotate_session["url"])
    page.wait_for_selector("#decode-more-btn")
    page.click("#decode-more-btn")
    # Watcher pickup is async; poll the events dir.
    events_dir = Path(annotate_session["events_dir"])
    for _ in range(40):  # up to ~4 s
        files = list(events_dir.glob("*.json"))
        if files:
            payload = json.loads(files[0].read_text())
            assert payload["type"] == "glossary_refresh"
            return
        page.wait_for_timeout(100)
    pytest.fail("no glossary_refresh event written within 4 s")
```

If the fixture name from the diagram smoke test differs (e.g. `live_session` instead of `annotate_session`), substitute it. If the diagram smoke test uses a different async model (e.g. `pytest-asyncio` + Playwright async), match that pattern instead.

- [ ] **Step 3: Run the test to verify it fails**

Run: `pytest skills/annotate/tests/test_smoke_e2e_glossary.py -v -m e2e`
Expected: the test runs (don't skip) and FAILS only on the assertions, not on import or fixture errors. If it fails on fixture errors, revisit step 2 — you didn't pick up the right fixture name.

- [ ] **Step 4: Make it pass**

If the assertions fail, the failure points at a real bug in Tasks 5/6. Debug it. Common causes:

- Pills missing → check `popover.js` actually loaded (look at `<head>` in the served HTML).
- Code-span pill leaked → check `isInsideSkipped` in `popover.js`.
- Popover empty → check that `setGlossary` was called from `script.js`.
- `decode_more` event not written → check the relative URL the button POSTs to matches the server route.

Once both tests pass, the implementation is done.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/tests/test_smoke_e2e_glossary.py
git commit -m "annotate: e2e smoke test for glossary pills + decode-more button"
```

---

## Final verification

- [ ] **Step 1: Run the full test suite**

Run: `pytest skills/annotate/ -v`
Expected: everything passes.

- [ ] **Step 2: One end-to-end manual round trip**

Push a real response through the annotate skill in a fresh Claude Code session — pick something that should trigger the glossary (a response mentioning a project-specific name from this repo, e.g. `BlocksDoc` or `_render_block_for_raw`). Verify:

- The response renders in the browser.
- The project-specific identifiers have dotted underlines.
- Hover surfaces a popover with definition + role.
- Click "Decode more" → after a short pause, the page picks up an updated glossary (verify by removing an entry by hand and seeing the pill disappear, or adding one).

- [ ] **Step 3: Confirm the diff**

Run: `git log --oneline annotate-improvements --not main`
Expected: 8 new commits, one per task.
