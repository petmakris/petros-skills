# Annotate Choice Block Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `kind: "choice"` block to the annotate skill so Claude can pose a single multiple-choice decision (single- or multi-select) that the user answers by *picking*, after which Claude folds the decision into the block and continues the task.

**Architecture:** A third block kind alongside `markdown` and `sequence`. Spec lives in `blocks.json`; the server validates the submitted option ids in `/api/submit` and passes the spec through `/raw`; the client renders radio/checkbox options and submits the selection; on the watcher wake Claude resolves the block (choice → markdown) and continues. Reuses the existing event queue, watcher, poll loop, updating-overlay, and content-hash version chain unchanged.

**Tech Stack:** Python 3 (stdlib `http.server`, `unittest`/`pytest`), vanilla browser JS, no new dependencies.

**Spec:** `docs/superpowers/specs/2026-06-03-annotate-choice-block-design.md`

---

## File structure

| File | Responsibility | Change |
|------|----------------|--------|
| `skills/annotate/blocks.py` | doc model | Add choice validation + choice→markdown conversion helpers |
| `skills/annotate/server.py` | HTTP handlers | `handle_submit` choice branch; `_render_block_for_raw` choice pass-through |
| `skills/annotate/static/script.js` | client render/interaction | `createBlockSection`, `blockTitle`, `updateBlockContent` choice branches; submit wiring |
| `skills/annotate/static/style.css` | styling | Choice block CSS (reuses existing CSS vars) |
| `skills/annotate/tests/test_blocks.py` | model tests | Helper unit tests |
| `skills/annotate/tests/test_server.py` | server tests | Submit + raw choice tests |
| `skills/annotate/tests/test_smoke_e2e_choice.py` | e2e smoke | Full pick → resolve round-trip |
| `skills/annotate/SKILL.md` | skill instructions | When-to-use, spec shape, Mode D choice, rewrite contract |

Test commands run from the repo root (the `petros-skills` checkout).

---

## Task 1: Model helpers — choice validation + conversion

**Files:**
- Modify: `skills/annotate/blocks.py`
- Test: `skills/annotate/tests/test_blocks.py`

- [ ] **Step 1: Write the failing tests**

Append to `skills/annotate/tests/test_blocks.py`:

```python
from skills.annotate.blocks import (
    choice_option_ids, validate_choice_selection, convert_block_to_markdown,
)


def _choice_spec(multi=False):
    return {
        "question": "Pick one",
        "multiSelect": multi,
        "options": [
            {"id": "o1", "label": "A"},
            {"id": "o2", "label": "B"},
            {"id": "o3", "label": "C"},
        ],
    }


def test_choice_option_ids_lists_ids_in_order():
    assert choice_option_ids(_choice_spec()) == ["o1", "o2", "o3"]


def test_choice_option_ids_empty_for_no_options():
    assert choice_option_ids({}) == []


def test_validate_single_select_accepts_one_known_id():
    assert validate_choice_selection(_choice_spec(), ["o2"]) is None


def test_validate_single_select_rejects_empty():
    err = validate_choice_selection(_choice_spec(), [])
    assert err is not None and "empty" in err.lower()


def test_validate_single_select_rejects_two_picks():
    err = validate_choice_selection(_choice_spec(multi=False), ["o1", "o2"])
    assert err is not None and "one" in err.lower()


def test_validate_rejects_unknown_id():
    err = validate_choice_selection(_choice_spec(), ["o9"])
    assert err is not None and "option" in err.lower()


def test_validate_rejects_non_list():
    err = validate_choice_selection(_choice_spec(), "o1")
    assert err is not None


def test_validate_multi_select_accepts_several():
    assert validate_choice_selection(_choice_spec(multi=True), ["o1", "o3"]) is None


def test_validate_multi_select_rejects_empty():
    err = validate_choice_selection(_choice_spec(multi=True), [])
    assert err is not None and "empty" in err.lower()


def test_convert_block_to_markdown_flips_kind_and_drops_spec():
    doc = BlocksDoc(blocks=[
        {"id": "section-1", "kind": "choice", "spec": _choice_spec()},
    ])
    changed = convert_block_to_markdown(doc, "section-1", "Decision: A.")
    assert changed is True
    blk = doc.blocks[0]
    assert blk.get("kind", "markdown") == "markdown"
    assert blk["markdown"] == "Decision: A."
    assert "spec" not in blk


def test_convert_block_to_markdown_is_noop_when_already_equal():
    doc = BlocksDoc(blocks=[
        {"id": "section-1", "markdown": "Decision: A."},
    ])
    assert convert_block_to_markdown(doc, "section-1", "Decision: A.") is False
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python3 -m pytest skills/annotate/tests/test_blocks.py -k "choice or convert_block" -v`
Expected: FAIL — `ImportError: cannot import name 'choice_option_ids'`.

- [ ] **Step 3: Implement the helpers**

Append to `skills/annotate/blocks.py` (after `next_step_id`, before `_term_appears`):

```python
def choice_option_ids(spec: dict[str, Any]) -> list[str]:
    """Return the option ids of a choice block's spec, in order."""
    return [o.get("id") for o in (spec.get("options") or []) if o.get("id")]


def validate_choice_selection(spec: dict[str, Any], selected: Any) -> str | None:
    """Validate a submitted selection against a choice spec.

    Returns None when valid, else a short human-readable error string.
    """
    if not isinstance(selected, list) or not all(isinstance(s, str) for s in selected):
        return "selected_options must be a list of strings"
    if not selected:
        return "selected_options must not be empty"
    valid = set(choice_option_ids(spec))
    unknown = [s for s in selected if s not in valid]
    if unknown:
        return f"unknown option id(s): {', '.join(unknown)}"
    if not spec.get("multiSelect") and len(selected) != 1:
        return "single-select choice requires exactly one option"
    return None


def convert_block_to_markdown(doc: BlocksDoc, block_id: str, new_markdown: str) -> bool:
    """Resolve a block to a markdown block: set markdown, drop kind/spec.

    Returns True if the block changed. Used when a choice block is resolved
    into a decision paragraph after the user picks.
    """
    b = find_block(doc, block_id)
    already_md = (b.get("kind") or "markdown") == "markdown"
    if already_md and b.get("markdown") == new_markdown:
        return False
    b.pop("kind", None)
    b.pop("spec", None)
    b["markdown"] = new_markdown
    return True
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `python3 -m pytest skills/annotate/tests/test_blocks.py -k "choice or convert_block" -v`
Expected: PASS (10 tests).

- [ ] **Step 5: Run the full blocks test file to confirm no regression**

Run: `python3 -m pytest skills/annotate/tests/test_blocks.py -v`
Expected: PASS (all).

- [ ] **Step 6: Commit**

```bash
git add skills/annotate/blocks.py skills/annotate/tests/test_blocks.py
git commit -m "feat(annotate): choice block model helpers (validate + resolve)"
```

---

## Task 2: Server — submit validation + raw pass-through

**Files:**
- Modify: `skills/annotate/server.py` — `handle_submit` (around `server.py:147-191`), `_render_block_for_raw` (around `server.py:262-292`)
- Test: `skills/annotate/tests/test_server.py`

- [ ] **Step 1: Write the failing tests**

Add these methods inside the test class that already defines `_post_json` and `_seq_blocks` (the class containing `test_submit_with_step_id_succeeds_when_step_exists`, around `test_server.py:602`):

```python
    def _choice_blocks(self, multi=False):
        spec = {
            "question": "Pick one",
            "multiSelect": multi,
            "options": [
                {"id": "o1", "label": "A"},
                {"id": "o2", "label": "B"},
            ],
        }
        return [{"id": "b-0", "kind": "choice", "spec": spec, "version": 1}]

    def test_submit_choice_single_select_succeeds(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-ch", "T", self._choice_blocks())
        status, _ = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "type": "choice", "selected_options": ["o2"],
        })
        self.assertEqual(status, 202)
        events_dir = Path(self.sess["events_dir"])
        evt = json.loads(list(events_dir.glob("*.json"))[0].read_text())
        self.assertEqual(evt["type"], "choice")
        self.assertEqual(evt["block_id"], "b-0")
        self.assertEqual(evt["selected_options"], ["o2"])

    def test_submit_choice_multi_select_succeeds(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-chm", "T", self._choice_blocks(multi=True))
        status, _ = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "type": "choice", "selected_options": ["o1", "o2"],
        })
        self.assertEqual(status, 202)
        events_dir = Path(self.sess["events_dir"])
        evt = json.loads(list(events_dir.glob("*.json"))[0].read_text())
        self.assertEqual(evt["selected_options"], ["o1", "o2"])

    def test_submit_choice_unknown_option_returns_422(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-chu", "T", self._choice_blocks())
        status, body = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "type": "choice", "selected_options": ["o9"],
        })
        self.assertEqual(status, 422)
        self.assertIn("option", body.lower())

    def test_submit_choice_empty_selection_returns_422(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-che", "T", self._choice_blocks())
        status, _ = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "type": "choice", "selected_options": [],
        })
        self.assertEqual(status, 422)

    def test_submit_choice_single_select_two_picks_returns_422(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-ch2", "T", self._choice_blocks(multi=False))
        status, _ = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "type": "choice", "selected_options": ["o1", "o2"],
        })
        self.assertEqual(status, 422)

    def test_submit_choice_against_markdown_block_returns_422(self):
        response_dir = Path(self.sess["response_dir"])
        _write_blocks(response_dir, "resp-chmd", "T", [
            {"id": "b-0", "markdown": "hello", "version": 1},
        ])
        status, _ = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "type": "choice", "selected_options": ["o1"],
        })
        self.assertEqual(status, 422)

    def test_submit_choice_returns_409_when_terminal(self):
        (Path(self.sess["state_dir"]) / "finished").write_text("")
        status, _ = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "type": "choice", "selected_options": ["o1"],
        })
        self.assertEqual(status, 409)

    def test_raw_passes_spec_through_for_choice_block(self):
        response_dir = Path(self.sess["response_dir"])
        blocks = self._choice_blocks()
        _write_blocks(response_dir, "resp-chraw", "T", blocks)
        status, body = _http_get("localhost", self.info["port"], self.base + "/raw")
        self.assertEqual(status, 200)
        data = json.loads(body)
        blk = next(b for b in data["blocks"] if b["id"] == "b-0")
        self.assertEqual(blk["kind"], "choice")
        self.assertEqual(blk["spec"], blocks[0]["spec"])
        self.assertNotIn("svg", blk)
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python3 -m pytest skills/annotate/tests/test_server.py -k "choice" -v`
Expected: FAIL — choice submits return 400 ("bad type", since `choice` is not yet an allowed type) and raw lacks `spec`.

- [ ] **Step 3: Add the choice branch to `handle_submit`**

In `skills/annotate/server.py`, `handle_submit`. First widen the type guard. Replace:

```python
        if comment_type not in ("comment", "reject"):
            _send_text(h, 400, "bad type")
            return
```

with:

```python
        if comment_type not in ("comment", "reject", "choice"):
            _send_text(h, 400, "bad type")
            return
        selected_options = payload.get("selected_options")
        if comment_type == "choice":
            if block_id is None:
                _send_text(h, 422, "choice requires block_id")
                return
            blocks_path = Path(dirs["response_dir"]) / "blocks.json"
            doc = blocks_model.load(blocks_path)
            try:
                blk = blocks_model.find_block(doc, block_id)
            except KeyError:
                _send_text(h, 422, f"unknown block_id {block_id!r}")
                return
            if (blk.get("kind") or "markdown") != "choice":
                _send_text(h, 422, "type=choice only valid for kind=choice blocks")
                return
            err = blocks_model.validate_choice_selection(blk.get("spec") or {}, selected_options)
            if err is not None:
                _send_text(h, 422, err)
                return
```

Then extend the event dict built at the end of `handle_submit`. Replace:

```python
        evt = {
            "block_id": block_id,
            "step_id": step_id,
            "type": comment_type,
            "text": text,
            "selected_text": selected_text,
            "images": images,
        }
```

with:

```python
        evt = {
            "block_id": block_id,
            "step_id": step_id,
            "type": comment_type,
            "text": text,
            "selected_text": selected_text,
            "images": images,
        }
        if comment_type == "choice":
            evt["selected_options"] = list(selected_options)
```

(The existing `step_id is not None` validation block stays as-is; a choice submit sends no `step_id`, so it is skipped.)

- [ ] **Step 4: Add the choice branch to `_render_block_for_raw`**

In `skills/annotate/server.py`, `_render_block_for_raw`. Replace the trailing dispatch:

```python
    if kind == "sequence":
        spec = blk.get("spec") or {}
        try:
            svg = render(spec, block_id=blk["id"])
        except Exception as e:
```

…leave the `sequence` branch body unchanged, but change the final `else` so choice passes its spec through. Replace:

```python
        base["spec"] = spec
        base["svg"] = svg
    else:
        base["markdown"] = blk.get("markdown", "")
    return base
```

with:

```python
        base["spec"] = spec
        base["svg"] = svg
    elif kind == "choice":
        base["spec"] = blk.get("spec") or {}
    else:
        base["markdown"] = blk.get("markdown", "")
    return base
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `python3 -m pytest skills/annotate/tests/test_server.py -k "choice" -v`
Expected: PASS (8 tests).

- [ ] **Step 6: Run the full server test file**

Run: `python3 -m pytest skills/annotate/tests/test_server.py -v`
Expected: PASS (all — no regression to existing comment/step_id paths).

- [ ] **Step 7: Commit**

```bash
git add skills/annotate/server.py skills/annotate/tests/test_server.py
git commit -m "feat(annotate): server validates choice submits, passes choice spec through /raw"
```

---

## Task 3: Client — render choice options and submit the pick

**Files:**
- Modify: `skills/annotate/static/script.js` — `blockTitle` (`script.js:274`), `createBlockSection` (`script.js:295`), `updateBlockContent` (`script.js:996`)
- Modify: `skills/annotate/static/style.css`

This task is verified manually (browser interaction) plus an e2e smoke test in Task 4. No JS unit harness exists in this repo; follow the existing client convention (sequence rendering is verified the same way).

- [ ] **Step 1: Add a choice branch to `blockTitle`**

In `skills/annotate/static/script.js`, `blockTitle`. After the `sequence` branch (`script.js:276-279`), insert before the markdown handling:

```javascript
    if ((blk.kind || "markdown") === "choice") {
      const q = blk.spec && blk.spec.question;
      return (q && String(q).trim()) || "Decision";
    }
```

- [ ] **Step 2: Add a `renderChoice` helper and wire it into `createBlockSection`**

In `skills/annotate/static/script.js`, add this function just above `createBlockSection` (`script.js:295`):

```javascript
  // Render a choice block's interactive body: question, radio/checkbox
  // options, and a Submit button. On submit, POST the selection and show the
  // same "updating" overlay the comment path uses.
  function renderChoice(section, content, blk) {
    const spec = blk.spec || {};
    const multi = !!spec.multiSelect;
    const options = Array.isArray(spec.options) ? spec.options : [];
    const groupName = `choice-${blk.id}`;

    const wrap = document.createElement("div");
    wrap.className = "choice-block";

    if (spec.question) {
      const q = document.createElement("p");
      q.className = "choice-question";
      q.textContent = spec.question;
      wrap.appendChild(q);
    }

    const list = document.createElement("div");
    list.className = "choice-options";
    const inputs = [];
    for (const opt of options) {
      const row = document.createElement("label");
      row.className = "choice-option";
      const input = document.createElement("input");
      input.type = multi ? "checkbox" : "radio";
      input.name = groupName;
      input.value = opt.id;
      inputs.push(input);
      const textWrap = document.createElement("span");
      textWrap.className = "choice-option-text";
      const label = document.createElement("span");
      label.className = "choice-option-label";
      label.textContent = opt.label || opt.id;
      textWrap.appendChild(label);
      if (opt.description) {
        const desc = document.createElement("span");
        desc.className = "choice-option-desc";
        desc.textContent = opt.description;
        textWrap.appendChild(desc);
      }
      row.append(input, textWrap);
      list.appendChild(row);
    }
    wrap.appendChild(list);

    const submitBtn = document.createElement("button");
    submitBtn.type = "button";
    submitBtn.className = "choice-submit-btn";
    submitBtn.textContent = "Submit";
    submitBtn.disabled = true;
    const refreshDisabled = () => {
      submitBtn.disabled = !inputs.some(i => i.checked);
    };
    inputs.forEach(i => i.addEventListener("change", refreshDisabled));

    submitBtn.addEventListener("click", () => {
      const selected = inputs.filter(i => i.checked).map(i => i.value);
      if (!selected.length) return;
      submitBtn.disabled = true;
      const payload = {
        block_id: blk.id,
        step_id: null,
        type: "choice",
        selected_options: selected,
        text: "",
        selected_text: "",
        images: [],
      };
      WebCompanion.api.submit(payload).then((res) => {
        const eventId = res && res.event_id;
        if (eventId) pendingEvents.set(String(eventId), { blockId: blk.id, cardId: null });
        startUpdatingOverlay(section);
      }).catch(() => {
        refreshDisabled();
      });
    });
    wrap.appendChild(submitBtn);
    content.appendChild(wrap);
  }
```

- [ ] **Step 3: Extract the updating-overlay into a reusable `startUpdatingOverlay`**

The overlay logic currently lives inline in the comment Submit handler (`script.js:650-686`). Extract it so the choice path reuses it. Add this function just above `buildCard` (`script.js:423`):

```javascript
  // Add the "updating" spinner overlay + timer to a block section. Idempotent:
  // a section already overlaid is left alone. Mirrors the inline logic the
  // comment-submit path uses.
  function startUpdatingOverlay(section) {
    if (!section) return;
    section.classList.add("is-updating");
    if (section.querySelector(".updating-overlay")) return;
    const overlay = document.createElement("div");
    overlay.className = "updating-overlay";
    const pill = document.createElement("div");
    pill.className = "updating-pill";
    const spinner = document.createElement("span");
    spinner.className = "updating-spinner";
    pill.appendChild(spinner);
    const label = document.createElement("span");
    label.textContent = "updating";
    pill.appendChild(label);
    const timer = document.createElement("span");
    timer.className = "updating-timer";
    timer.textContent = "0:00";
    pill.appendChild(timer);
    overlay.appendChild(pill);
    section.appendChild(overlay);
    const startedAt = Date.now();
    section._updatingTimerId = setInterval(() => {
      const elapsed = Math.floor((Date.now() - startedAt) / 1000);
      const m = Math.floor(elapsed / 60);
      const s = String(elapsed % 60).padStart(2, "0");
      timer.textContent = `${m}:${s}`;
    }, 1000);
  }
```

Then in the comment Submit handler, replace the inline overlay block (`script.js:650-686`, the `if (a.block_id) { const section = ...; if (section) { section.classList.add("is-updating"); ... } }`) with:

```javascript
        if (a.block_id) {
          const section = document.querySelector(`section.block[data-block-id="${cssEsc(a.block_id)}"]`);
          startUpdatingOverlay(section);
        }
```

- [ ] **Step 4: Dispatch `choice` in `createBlockSection`**

In `createBlockSection` (`script.js:324`), extend the content dispatch. Replace:

```javascript
    if (kind === "sequence") {
      // Server pre-rendered the SVG; inject as-is.
      content.innerHTML = blk.svg || "";
```

…leave the sequence branch intact, but add a `choice` branch before the `else`. Change the `} else {` that begins the markdown path (`script.js:335`) to:

```javascript
    } else if (kind === "choice") {
      renderChoice(section, content, blk);
    } else {
```

- [ ] **Step 5: Handle `choice` in `updateBlockContent`**

A choice block always carries interactive wiring, so a poll-update that keeps it a choice must rebuild a fresh section (same reason sequence/markdown flips do). In `updateBlockContent` (`script.js:996`), widen the fresh-section condition. Replace:

```javascript
    if (newKind !== oldKind) {
      const fresh = createBlockSection(blk);
      clearUpdatingOverlay(section);
      section.replaceWith(fresh);
      return fresh;
    }
```

with:

```javascript
    if (newKind !== oldKind || newKind === "choice") {
      const fresh = createBlockSection(blk);
      clearUpdatingOverlay(section);
      section.replaceWith(fresh);
      return fresh;
    }
```

(Resolution flips choice → markdown, which already takes the `newKind !== oldKind` path; this extra clause only matters if a choice block is ever re-rendered as a choice.)

- [ ] **Step 6: Add choice CSS**

Append to `skills/annotate/static/style.css`:

```css
/* ── Choice blocks ──────────────────────────────────────────────────────── */
.choice-block {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.choice-question {
  margin: 0;
  font-weight: 600;
  color: var(--text-strong);
}
.choice-options {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
.choice-option {
  display: flex;
  align-items: flex-start;
  gap: 0.6rem;
  padding: 0.6rem 0.75rem;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface);
  cursor: pointer;
  transition: border-color 0.12s ease, background 0.12s ease;
}
.choice-option:hover {
  border-color: var(--accent);
  background: var(--surface-soft);
}
.choice-option input {
  margin-top: 0.2rem;
  accent-color: var(--accent);
  flex: none;
}
.choice-option-text {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}
.choice-option-label {
  color: var(--text-strong);
  font-weight: 500;
}
.choice-option-desc {
  color: var(--text-dim);
  font-size: 0.85em;
}
.choice-submit-btn {
  align-self: flex-start;
  padding: 0.45rem 1rem;
  border: none;
  border-radius: 8px;
  background: var(--accent);
  color: #fff;
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}
.choice-submit-btn:disabled {
  opacity: 0.45;
  cursor: default;
}
```

- [ ] **Step 7: Manual browser verification**

Start a session and push a choice block by hand, then open the URL:

```bash
"$CLAUDE_PLUGIN_ROOT/skills/annotate/ensure_server.sh"
SERVER_URL=$(python3 -c 'import json,os; print(json.load(open(os.path.expanduser("~/.claude/annotate/server.json")))["url"])')
SESS=$(curl -sf -X POST "$SERVER_URL/api/sessions" -H 'Content-Type: application/json' -d "$(printf '{"cwd": "%s"}' "$PWD")")
RESP_DIR=$(python3 -c "import json,sys; print(json.loads('''$SESS''')['response_dir'])")
URL=$(python3 -c "import json,sys; print(json.loads('''$SESS''')['url'])")
python3 - "$RESP_DIR" <<'PY'
import json, sys, pathlib
d = pathlib.Path(sys.argv[1])
meta = {"response_id": "resp-manual", "title": "Choice demo"}
blocks = {"response_id": "resp-manual", "title": "Choice demo", "blocks": [
  {"id": "section-1", "kind": "choice", "spec": {
    "question": "How should we cut over?", "multiSelect": False,
    "options": [
      {"id": "o1", "label": "Big-bang", "description": "Single cutover window"},
      {"id": "o2", "label": "Incremental", "description": "Phase by read/write path"},
      {"id": "o3", "label": "Dual-write", "description": "Write both, migrate reads"}
    ]}}]}
(d / "meta.json").write_text(json.dumps(meta))
(d / "blocks.json").write_text(json.dumps(blocks))
PY
echo "Open: $URL"
```

Open the URL in a browser. Expected:
- The block shows the question and three radio options with descriptions, styled to match the page.
- Submit is disabled until one option is selected.
- After clicking Submit, the block shows the "updating" spinner overlay.
- Repeat with `"multiSelect": True` → options render as checkboxes; multiple can be selected.

- [ ] **Step 8: Commit**

```bash
git add skills/annotate/static/script.js skills/annotate/static/style.css
git commit -m "feat(annotate): client renders choice blocks and submits the pick"
```

---

## Task 4: End-to-end smoke test (pick → resolve round-trip)

**Files:**
- Create: `skills/annotate/tests/test_smoke_e2e_choice.py`

- [ ] **Step 1: Write the smoke test**

Create `skills/annotate/tests/test_smoke_e2e_choice.py`:

```python
"""End-to-end smoke: a choice block, a pick submit, and resolution to markdown."""
import http.client
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from skills.annotate import blocks as blocks_model
from skills.annotate.tests.test_server import (
    _create_session, _http_get, _start_server, _write_blocks,
)


class ChoiceSmokeTests(unittest.TestCase):
    def setUp(self):
        self.project = Path(tempfile.mkdtemp(prefix="annotate-choice-"))
        self.fake_home = Path(tempfile.mkdtemp(prefix="annotate-choice-home-"))
        self.proc, self.info = _start_server(self.fake_home)
        self.sess = _create_session(self.info["port"], self.project)
        self.base = f"/s/{self.sess['sid']}"

    def tearDown(self):
        try:
            self.proc.terminate()
            self.proc.wait(timeout=2)
        except Exception:
            self.proc.kill()
        shutil.rmtree(self.project, ignore_errors=True)
        shutil.rmtree(self.fake_home, ignore_errors=True)

    def _post_json(self, path, payload):
        conn = http.client.HTTPConnection("localhost", self.info["port"], timeout=2)
        conn.request("POST", path, body=json.dumps(payload),
                     headers={"Content-Type": "application/json"})
        resp = conn.getresponse()
        body = resp.read().decode("utf-8")
        status = resp.status
        conn.close()
        return status, body

    def test_e2e_choice_pick_then_resolve(self):
        response_dir = Path(self.sess["response_dir"])
        spec = {
            "question": "How should we cut over?",
            "multiSelect": False,
            "options": [
                {"id": "o1", "label": "Big-bang"},
                {"id": "o2", "label": "Incremental"},
            ],
        }
        _write_blocks(response_dir, "resp-choice", "choice-smoke", [
            {"id": "b-0", "kind": "choice", "spec": spec, "version": 1},
        ])

        # 1. /raw exposes the choice block with spec, no svg/markdown.
        status, body = _http_get("localhost", self.info["port"], self.base + "/raw")
        self.assertEqual(status, 200)
        blk = next(b for b in json.loads(body)["blocks"] if b["id"] == "b-0")
        self.assertEqual(blk["kind"], "choice")
        self.assertEqual(blk["spec"], spec)
        self.assertEqual(blk["version"], 1)

        # 2. POST the pick → 202, event stores selected_options.
        status, _ = self._post_json(self.base + "/api/submit", {
            "block_id": "b-0", "type": "choice", "selected_options": ["o2"],
        })
        self.assertEqual(status, 202)
        events_dir = Path(self.sess["events_dir"])
        evt = json.loads(list(events_dir.glob("*.json"))[0].read_text())
        self.assertEqual(evt["type"], "choice")
        self.assertEqual(evt["selected_options"], ["o2"])

        # 3. Simulate Claude resolving the choice into a markdown decision.
        blocks_path = response_dir / "blocks.json"
        doc = blocks_model.load(blocks_path)
        changed = blocks_model.convert_block_to_markdown(
            doc, "b-0", "Decision: incremental cutover.")
        self.assertTrue(changed)
        blocks_model.save_atomic(blocks_path, doc)

        # 4. /raw now shows a markdown block (kind flipped, version bumped).
        status, body = _http_get("localhost", self.info["port"], self.base + "/raw")
        self.assertEqual(status, 200)
        blk2 = next(b for b in json.loads(body)["blocks"] if b["id"] == "b-0")
        self.assertEqual(blk2["kind"], "markdown")
        self.assertEqual(blk2["markdown"], "Decision: incremental cutover.")
        self.assertNotIn("spec", blk2)
        self.assertEqual(blk2["version"], 2)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the smoke test**

Run: `python3 -m pytest skills/annotate/tests/test_smoke_e2e_choice.py -v`
Expected: PASS (1 test).

- [ ] **Step 3: Run the full annotate test suite**

Run: `python3 -m pytest skills/annotate/tests/ -v`
Expected: PASS (all — new choice tests plus every pre-existing test).

- [ ] **Step 4: Commit**

```bash
git add skills/annotate/tests/test_smoke_e2e_choice.py
git commit -m "test(annotate): e2e smoke for choice pick → resolve round-trip"
```

---

## Task 5: SKILL.md — document the choice block

**Files:**
- Modify: `skills/annotate/SKILL.md`

- [ ] **Step 1: Add the "When to use a choice block" section**

In `skills/annotate/SKILL.md`, after the sequence-diagram section "When to use a `kind: "sequence"` diagram block (Mode A extension)" and before "When in doubt, prefer the annotation view." (around `SKILL.md:52`), insert:

```markdown
### When to use a `kind: "choice"` block (Mode A extension)

Emit a choice block when the response reaches a **decision point** and the next step depends on the user's preference, with ALL of:

- 2–4 discrete, comparable options (or, for multi-select, a set the user picks a subset from).
- A closed answer space — picking beats free-text.
- The choice genuinely drives what you do next.

Typical fits: "which migration strategy", "which datastores to provision", "scope this to A, B, or both".

**Do NOT use a choice block for:**

- Open-ended questions where the answer isn't one of a few options (use prose + let the user comment).
- A hard gate where you must block before doing anything else — annotate is async; use the terminal `AskUserQuestion` tool for that.
- More than ~4 options, or options needing paragraphs to explain (that's prose).

One question per choice block. Need several questions? Emit several blocks.
```

- [ ] **Step 2: Add the choice block shape**

After the "Diagram block shape" subsection (ends around `SKILL.md:191`), add:

```markdown
### Choice block shape

A choice block looks like this in `blocks.json`:

    {"id": "section-N", "kind": "choice", "spec": {
      "question": "<the decision, one line>",
      "multiSelect": false,                    // true ⇒ checkboxes (pick ≥1)
      "options": [
        {"id": "o1", "label": "<terse choice>", "description": "<optional sub-text>"},
        {"id": "o2", "label": "...", "description": "..."}
      ]
    }}

Block id is `section-N` (assigned by `next_block_id`). Option ids are `o1`, `o2`, … minted by hand, stable across rewrites. `multiSelect: false` renders radio (exactly one); `true` renders checkboxes. `description` is optional. Use 2–4 options.

A choice block carries no `markdown`. The user picks in the browser; you resolve the block on the watcher event (see Mode D).
```

- [ ] **Step 3: Document the choice event in Mode D**

In `skills/annotate/SKILL.md`, in "Mode D — handling a watcher event", the `WEBCOMPANION_EVENT` payload field list (around `SKILL.md:300-306`) mentions `type` as `"comment"` or `"reject"`. Update that line and add the choice field. Replace:

```markdown
   - `type` — `"comment"` or `"reject"`.
```

with:

```markdown
   - `type` — `"comment"`, `"reject"`, or `"choice"`.
   - `selected_options` — for `type: "choice"`: the option id(s) the user picked (a list). Absent otherwise.
```

Then add a subsection after the "`WEBCOMPANION_EVENT` with `type: "glossary_refresh"`" subsection (around `SKILL.md:321`):

```markdown
### `WEBCOMPANION_EVENT` with `type: "choice"`

The user picked option(s) on a choice block. `selected_options` holds the picked id(s); map them to labels via the block's `spec.options`.

1. Read `<response_dir>/blocks.json`, find the block by `block_id`.
2. **Resolve the choice into a decision** — convert the block from `kind: "choice"` to a markdown block whose prose states the decision and folds in the reasoning (e.g. *"Decision: incremental cutover — phase 1 ships the read path…"*). The options disappear; the answer is final. Use `blocks.convert_block_to_markdown(doc, block_id, markdown)` — it sets the markdown, drops `kind`/`spec`, and is content-hash-safe.
3. **Continue the task** — the pick drives the next step. Append follow-up blocks to `blocks.json` and/or take the implied action, as the decision warrants.
4. `save_atomic` the doc, write the `<consumed_dir>/<event_id>.ack`, end your turn. No terminal output; the watcher stays armed.

Multi-select: the decision prose names all picked options. There is no `reject` on a choice — a pick is a pick.
```

- [ ] **Step 4: Sanity-check SKILL.md renders (no broken fences)**

Run: `python3 -c "import pathlib; t=pathlib.Path('skills/annotate/SKILL.md').read_text(); f=chr(96)*3; assert t.count(f) % 2 == 0, 'unbalanced fences'; print('fences balanced')"`
Expected: `fences balanced`.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/SKILL.md
git commit -m "docs(annotate): document choice block (when-to-use, shape, Mode D)"
```

---

## Task 6: Final verification

- [ ] **Step 1: Run the whole annotate suite once more**

Run: `python3 -m pytest skills/annotate/tests/ -q`
Expected: all pass, zero failures.

- [ ] **Step 2: Confirm the working tree is clean and review the branch**

Run: `git status --short && git log --oneline main..HEAD`
Expected: clean tree; commits for model, server, client, smoke test, and docs.

---

## Self-review notes

- **Spec coverage:** spec schema → Task 1/2/3; submit validation (unknown id, empty, single-vs-multi, non-choice block, closed session) → Task 2; raw pass-through → Task 2; client radio/checkbox + submit + overlay → Task 3; resolution choice→markdown + version bump → Task 1 helper + Task 4 smoke; SKILL.md when-to-use/shape/Mode D/contract → Task 5. No "Other" free-text, no multi-question panel, no answer-changing — matches the non-goals.
- **Type/name consistency:** `choice_option_ids`, `validate_choice_selection`, `convert_block_to_markdown`, `startUpdatingOverlay`, `renderChoice` are defined once and referenced consistently across tasks. Event field is `selected_options` everywhere (server, client, tests, SKILL.md). Block kind string is `"choice"` everywhere.
- **No placeholders:** every code step carries complete code; every run step states the command and expected result.
```
