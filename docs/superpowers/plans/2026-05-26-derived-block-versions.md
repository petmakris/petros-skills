# Derived Block Versions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make per-block `version` a server-derived value computed from a content-hash chain stored in a sidecar file, so Claude never authors version numbers and over-rewriting cannot inflate them.

**Architecture:** Add `skills/annotate/versions.py` with a single `derive_versions(versions_path, blocks)` function that owns a `versions.json` sidecar in the response dir. The sidecar shape is `{"<block_id>": ["<hash1>", "<hash2>", ...]}`. On every render and on every poll, the server reads `blocks.json`, computes a normalized hash per block (kind-aware: canonical-spec JSON for sequence blocks, stripped markdown for everything else), appends to the chain only when the tail hash differs, and atomically writes the sidecar back. The returned `version` is `len(chain[block_id])`. Claude stops writing the `version` field; `update_block` / `update_spec_block` simplify to "just update content"; the on-disk `version` field becomes ignored legacy.

**Tech Stack:** Python 3 stdlib (`hashlib`, `json`, `pathlib`), pytest. No new dependencies.

---

## File Structure

**Create:**
- `skills/annotate/versions.py` — chain logic, ~70 lines
- `skills/annotate/tests/test_versions.py` — unit tests for the chain

**Modify:**
- `skills/annotate/blocks.py` — remove `version` field assignments; `update_block` and `update_spec_block` keep their content-hash dedup but no longer mutate `version`
- `skills/annotate/server.py:188-201` (`serve_poll`) — derive versions from sidecar
- `skills/annotate/server.py:234-258` (`_render_block_for_raw`) — derive version from sidecar (replaces `int(blk.get("version", 1))`)
- `skills/annotate/SKILL.md` — drop `version: 1` from the documented `blocks.json` schema and remove the "Bump the version" instruction in the rewrite contract
- `skills/annotate/tests/test_blocks.py` — drop `version`-bump assertions; keep content-change return-value assertions
- `skills/annotate/tests/test_server.py:392-405` — `test_poll_returns_version_vector` rewritten to drive the chain by writing changes, not by setting fields
- `skills/annotate/tests/test_server.py:219-230` — `test_get_block_returns_specific_block` no longer asserts a literal `version: 2`; assert the derived value after a known mutation
- `skills/annotate/tests/test_smoke_e2e_diagram.py:106` — `seq2["version"] == 2` becomes "after a spec change and re-render, version increments by 1"

---

## Task 1: New `versions.py` module with derive_versions

**Files:**
- Create: `skills/annotate/versions.py`
- Create: `skills/annotate/tests/test_versions.py`

- [ ] **Step 1: Write the failing test file**

Create `skills/annotate/tests/test_versions.py`:

```python
import json
from pathlib import Path

from skills.annotate.versions import derive_versions


def test_first_call_assigns_v1_to_every_block(tmp_path):
    vp = tmp_path / "versions.json"
    blocks = [
        {"id": "b-0", "markdown": "hello"},
        {"id": "b-1", "markdown": "world"},
    ]
    versions = derive_versions(vp, blocks)
    assert versions == {"b-0": 1, "b-1": 1}
    assert vp.exists()


def test_repeated_call_same_content_no_bump(tmp_path):
    vp = tmp_path / "versions.json"
    blocks = [{"id": "b-0", "markdown": "hi"}]
    derive_versions(vp, blocks)
    versions = derive_versions(vp, blocks)
    assert versions == {"b-0": 1}


def test_content_change_bumps_only_that_block(tmp_path):
    vp = tmp_path / "versions.json"
    derive_versions(vp, [
        {"id": "b-0", "markdown": "alpha"},
        {"id": "b-1", "markdown": "beta"},
    ])
    versions = derive_versions(vp, [
        {"id": "b-0", "markdown": "alpha-edited"},
        {"id": "b-1", "markdown": "beta"},
    ])
    assert versions == {"b-0": 2, "b-1": 1}


def test_whitespace_only_change_does_not_bump(tmp_path):
    vp = tmp_path / "versions.json"
    derive_versions(vp, [{"id": "b-0", "markdown": "same"}])
    versions = derive_versions(vp, [{"id": "b-0", "markdown": "  same  \n"}])
    assert versions == {"b-0": 1}


def test_sequence_block_uses_canonical_spec(tmp_path):
    vp = tmp_path / "versions.json"
    spec_a = {"actors": [{"id": "a", "label": "A"}], "steps": []}
    spec_a_reordered = {"steps": [], "actors": [{"label": "A", "id": "a"}]}
    derive_versions(vp, [{"id": "b-0", "kind": "sequence", "spec": spec_a}])
    versions = derive_versions(vp, [{"id": "b-0", "kind": "sequence", "spec": spec_a_reordered}])
    assert versions == {"b-0": 1}


def test_kind_change_bumps(tmp_path):
    vp = tmp_path / "versions.json"
    derive_versions(vp, [{"id": "b-0", "markdown": "hi"}])
    versions = derive_versions(vp, [
        {"id": "b-0", "kind": "sequence", "spec": {"actors": [], "steps": []}}
    ])
    assert versions == {"b-0": 2}


def test_new_block_starts_at_v1(tmp_path):
    vp = tmp_path / "versions.json"
    derive_versions(vp, [{"id": "b-0", "markdown": "alpha"}])
    versions = derive_versions(vp, [
        {"id": "b-0", "markdown": "alpha"},
        {"id": "b-1", "markdown": "fresh"},
    ])
    assert versions == {"b-0": 1, "b-1": 1}


def test_sidecar_format_is_chain_per_block(tmp_path):
    vp = tmp_path / "versions.json"
    derive_versions(vp, [{"id": "b-0", "markdown": "v1"}])
    derive_versions(vp, [{"id": "b-0", "markdown": "v2"}])
    chain = json.loads(vp.read_text())
    assert set(chain.keys()) == {"b-0"}
    assert isinstance(chain["b-0"], list)
    assert len(chain["b-0"]) == 2
    assert all(isinstance(h, str) and len(h) == 40 for h in chain["b-0"])  # sha1 hex


def test_corrupt_sidecar_is_treated_as_empty(tmp_path):
    vp = tmp_path / "versions.json"
    vp.write_text("{not json")
    versions = derive_versions(vp, [{"id": "b-0", "markdown": "hi"}])
    assert versions == {"b-0": 1}


def test_html_inter_tag_whitespace_collapses(tmp_path):
    vp = tmp_path / "versions.json"
    derive_versions(vp, [{"id": "b-0", "markdown": "<div><p>hi</p></div>"}])
    # Same visual structure, different inter-tag whitespace — should not bump.
    versions = derive_versions(vp, [{
        "id": "b-0",
        "markdown": "<div>\n  <p>hi</p>\n</div>",
    }])
    assert versions == {"b-0": 1}


def test_over_rewrite_regression(tmp_path):
    """Simulate Claude rewriting the whole document twice with identical
    content. Every block's chain must have exactly one entry."""
    vp = tmp_path / "versions.json"
    doc = [
        {"id": "b-0", "markdown": "alpha"},
        {"id": "b-1", "markdown": "beta"},
        {"id": "b-2", "markdown": "gamma"},
        {"id": "b-3", "markdown": "delta"},
    ]
    derive_versions(vp, doc)
    derive_versions(vp, doc)
    versions = derive_versions(vp, doc)
    assert versions == {"b-0": 1, "b-1": 1, "b-2": 1, "b-3": 1}
    chain = json.loads(vp.read_text())
    for bid in ("b-0", "b-1", "b-2", "b-3"):
        assert len(chain[bid]) == 1
```

- [ ] **Step 2: Run tests to confirm they fail with ImportError**

Run: `cd ~/projects/petros-skills && python -m pytest skills/annotate/tests/test_versions.py -v`
Expected: collection error / ImportError on `skills.annotate.versions`.

- [ ] **Step 3: Create `versions.py`**

Create `skills/annotate/versions.py`:

```python
"""Per-block version chain — server-derived versioning.

A block's `version` is *not* something Claude writes into blocks.json. It is
computed on every read by hashing the block's content (normalized for
cosmetic noise) and comparing against a per-block hash chain stored in a
sidecar `versions.json` alongside `blocks.json`. The reported version is
the length of that chain — a value that can only grow when the content
actually changes.

This kills two failure modes at once:
  (a) Claude rewriting unrelated blocks bumps their versions for no reason.
  (b) Cosmetic byte-churn (trailing whitespace, re-flowed lines) bumps
      versions even though the prose is unchanged.

The chain is the single source of truth. The on-disk `version` field on
blocks (if present from older data) is ignored.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any


def _canonical_spec(spec: dict[str, Any]) -> str:
    """Stable JSON serialization — must match blocks._canonical_spec."""
    return json.dumps(spec, sort_keys=True, separators=(",", ":"))


_INTER_TAG_WS = __import__("re").compile(r">\s+<")
_EDGE_WS = __import__("re").compile(r"[ \t]+$", __import__("re").MULTILINE)


def _normalize_markdown(s: str) -> str:
    """Cosmetic-noise filter applied before hashing markdown/HTML blocks.

    Strip leading/trailing edge whitespace, collapse trailing line whitespace,
    and collapse any whitespace between `>` and `<` to a single space so that
    a re-flowed HTML block with identical visual structure hashes identically.
    Plain markdown without inline HTML is unaffected.
    """
    s = s.strip()
    s = _EDGE_WS.sub("", s)
    s = _INTER_TAG_WS.sub("> <", s)
    return s


def _block_hash(blk: dict[str, Any]) -> str:
    """SHA1 of (kind, normalized-content). Whitespace + inter-tag tolerant."""
    kind = blk.get("kind") or "markdown"
    if kind == "sequence":
        body = _canonical_spec(blk.get("spec") or {})
    else:
        body = _normalize_markdown(blk.get("markdown") or "")
    h = hashlib.sha1()
    h.update(kind.encode("utf-8") + b"\x00" + body.encode("utf-8"))
    return h.hexdigest()


def _load_chain(path: Path) -> dict[str, list[str]]:
    if not path.exists():
        return {}
    try:
        raw = json.loads(path.read_text())
    except (json.JSONDecodeError, OSError):
        return {}
    if not isinstance(raw, dict):
        return {}
    out: dict[str, list[str]] = {}
    for k, v in raw.items():
        if isinstance(k, str) and isinstance(v, list) and all(isinstance(x, str) for x in v):
            out[k] = list(v)
    return out


def _save_chain_atomic(path: Path, chain: dict[str, list[str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(chain, indent=2))
    tmp.replace(path)


def derive_versions(versions_path: Path, blocks: list[dict[str, Any]]) -> dict[str, int]:
    """Return {block_id: version} derived from the chain at versions_path.

    Appends a new hash to a block's chain when the block's current hash
    differs from the chain's tail. Writes the sidecar atomically only if
    any chain grew. Concurrent calls converge: both see the same tail,
    both append the same hash, last-writer-wins yields identical state.
    """
    chain = _load_chain(versions_path)
    changed = False
    for blk in blocks:
        bid = blk.get("id")
        if not isinstance(bid, str):
            continue
        h = _block_hash(blk)
        history = chain.setdefault(bid, [])
        if not history or history[-1] != h:
            history.append(h)
            changed = True
    if changed:
        _save_chain_atomic(versions_path, chain)
    out: dict[str, int] = {}
    for blk in blocks:
        bid = blk.get("id")
        if isinstance(bid, str):
            out[bid] = len(chain.get(bid, [])) or 1
    return out
```

- [ ] **Step 4: Run tests to confirm they pass**

Run: `cd ~/projects/petros-skills && python -m pytest skills/annotate/tests/test_versions.py -v`
Expected: all 9 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/versions.py skills/annotate/tests/test_versions.py
git commit -m "annotate: add derive_versions chain module"
```

---

## Task 2: Wire `serve_poll` to derive versions from the sidecar

**Files:**
- Modify: `skills/annotate/server.py:187-201`
- Modify: `skills/annotate/tests/test_server.py:392-405` (`test_poll_returns_version_vector`)
- Modify: `skills/annotate/tests/test_server.py` — also any other tests that asserted versions in `/poll`

- [ ] **Step 1: Update the poll test to drive versions by content change, not by field**

Replace `test_poll_returns_version_vector` (currently at `test_server.py:392-405`):

```python
def test_poll_returns_version_vector(self):
    """/poll derives {id: version} from observed content changes."""
    response_dir = Path(self.sess["response_dir"])
    # Initial write — both blocks at v1 after first poll.
    _write_blocks(response_dir, "resp-poll", "T", [
        {"id": "b-0", "markdown": "alpha"},
        {"id": "b-1", "markdown": "beta"},
    ])
    status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
    self.assertEqual(status, 200)
    data = json.loads(body)
    self.assertEqual(data["response_id"], "resp-poll")
    self.assertEqual(data["blocks"], {"b-0": 1, "b-1": 1})

    # Edit b-1 twice — only b-1 should bump.
    _write_blocks(response_dir, "resp-poll", "T", [
        {"id": "b-0", "markdown": "alpha"},
        {"id": "b-1", "markdown": "beta-edited"},
    ])
    _http_get("localhost", self.info["port"], self.base + "/poll")
    _write_blocks(response_dir, "resp-poll", "T", [
        {"id": "b-0", "markdown": "alpha"},
        {"id": "b-1", "markdown": "beta-edited-again"},
    ])
    status, body = _http_get("localhost", self.info["port"], self.base + "/poll")
    data = json.loads(body)
    self.assertEqual(data["blocks"], {"b-0": 1, "b-1": 3})
    self.assertFalse(data["finished"])
    self.assertIsInstance(data["watcher_seen_at"], int)
```

- [ ] **Step 2: Run the test to confirm it fails**

Run: `cd ~/projects/petros-skills && python -m pytest skills/annotate/tests/test_server.py::TestServerRoutes::test_poll_returns_version_vector -v`
Expected: FAIL — the server still reads `b.get("version", 1)` so b-1 reports 1, not 3.

- [ ] **Step 3: Update `serve_poll` to derive versions**

In `skills/annotate/server.py`, replace the `serve_poll` body (currently lines 187-201):

```python
    def serve_poll(self, h: BaseHTTPRequestHandler, dirs: dict) -> None:
        response_dir = Path(dirs["response_dir"])
        blocks_path = response_dir / "blocks.json"
        versions_path = response_dir / "versions.json"
        doc = blocks_model.load(blocks_path)
        versions = versions_module.derive_versions(versions_path, doc.blocks)
        hb_path = Path(dirs["state_dir"]) / "watcher_heartbeat"
        try:
            hb = int(hb_path.read_text().strip())
        except (FileNotFoundError, ValueError):
            hb = 0
        _send_json(h, 200, {
            "blocks": versions,
            "watcher_seen_at": hb,
            "finished": _is_terminal(Path(dirs["state_dir"])),
            "response_id": doc.response_id,
        })
```

And add the import at the top of `server.py` (next to `from skills.annotate.blocks import ...`):

```python
from skills.annotate import versions as versions_module
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `cd ~/projects/petros-skills && python -m pytest skills/annotate/tests/test_server.py::TestServerRoutes::test_poll_returns_version_vector -v`
Expected: PASS.

- [ ] **Step 5: Run the full test_server.py to find collateral damage**

Run: `cd ~/projects/petros-skills && python -m pytest skills/annotate/tests/test_server.py -v`
Expected: any test that asserted a literal `version` value from `/poll` may now fail. Note them — they're fixed in Task 3.

- [ ] **Step 6: Commit**

```bash
git add skills/annotate/server.py skills/annotate/tests/test_server.py
git commit -m "annotate: derive /poll versions from chain sidecar"
```

---

## Task 3: Wire `_render_block_for_raw` to derive versions

**Files:**
- Modify: `skills/annotate/server.py:234-258` (`_render_block_for_raw`) and its caller
- Modify: `skills/annotate/tests/test_server.py:219-230` (`test_get_block_returns_specific_block`)
- Modify: `skills/annotate/tests/test_smoke_e2e_diagram.py:106`

- [ ] **Step 1: Find the caller of `_render_block_for_raw` and how it receives the response_dir**

Run: `grep -n "_render_block_for_raw\|response_dir" skills/annotate/server.py | head -30`
Read the surrounding code to see where to thread the versions dict in. The cleanest approach is to compute `versions = derive_versions(...)` once in the route handler that calls `_render_block_for_raw`, then pass the relevant version int down as an argument.

- [ ] **Step 2: Update the failing E2E test first**

In `skills/annotate/tests/test_smoke_e2e_diagram.py`, change the assertion at line 106 from:

```python
self.assertEqual(seq2["version"], 2)
```

to (full surrounding context — read lines 80-110 first to get the right diff):

```python
# After update_spec_block changes the spec, the next render derives version 2
# from the hash chain rather than a stored field.
self.assertEqual(seq2["version"], 2)
```

The literal value stays the same (`2`) because this test does drive a real content change. But the *reason* it works changes, so add the comment. If the test still passes after Tasks 1-2, no code change is needed beyond the comment.

In `skills/annotate/tests/test_server.py` `test_get_block_returns_specific_block` (line 219-230), change:

```python
def test_get_block_returns_specific_block(self):
    response_dir = Path(self.sess["response_dir"])
    _write_blocks(response_dir, "resp-1", "T", [
        {"id": "b-0", "markdown": "first"},
        {"id": "b-1", "markdown": "second"},
    ])
    # Modify b-1 once so its derived version is 2.
    _http_get("localhost", self.info["port"], self.base + "/poll")
    _write_blocks(response_dir, "resp-1", "T", [
        {"id": "b-0", "markdown": "first"},
        {"id": "b-1", "markdown": "second-v2"},
    ])
    status, body = _http_get("localhost", self.info["port"], self.base + "/blocks/b-1")
    self.assertEqual(status, 200)
    data = json.loads(body)
    self.assertEqual(data["id"], "b-1")
    self.assertEqual(data["version"], 2)
```

- [ ] **Step 3: Run both tests to confirm they fail**

Run: `cd ~/projects/petros-skills && python -m pytest skills/annotate/tests/test_server.py::TestServerRoutes::test_get_block_returns_specific_block skills/annotate/tests/test_smoke_e2e_diagram.py -v`
Expected: `test_get_block_returns_specific_block` FAILS because `_render_block_for_raw` still uses the on-disk `version` field (which the test no longer sets).

- [ ] **Step 4: Update `_render_block_for_raw` to accept a derived version and update its caller**

In `skills/annotate/server.py`, change `_render_block_for_raw` to take an explicit version argument:

```python
def _render_block_for_raw(blk: dict, version: int) -> dict:
    """Return a dict shape the client expects for one block.

    `version` is derived externally from the chain sidecar — not read off
    the block dict.
    """
    kind = blk.get("kind") or "markdown"
    base = {"id": blk["id"], "kind": kind, "version": version}
    if kind == "sequence":
        ...  # unchanged body
```

In the route handler that calls `_render_block_for_raw` (find via `grep -n "_render_block_for_raw" skills/annotate/server.py`), compute versions once and pass through:

```python
response_dir = Path(dirs["response_dir"])
versions = versions_module.derive_versions(response_dir / "versions.json", doc.blocks)
# ... when rendering one block:
view = _render_block_for_raw(blk, versions.get(blk["id"], 1))
# ... when rendering all blocks:
views = [_render_block_for_raw(b, versions.get(b["id"], 1)) for b in doc.blocks]
```

(Adjust to whatever the actual caller shape is — read the file before editing.)

- [ ] **Step 5: Run the targeted tests**

Run: `cd ~/projects/petros-skills && python -m pytest skills/annotate/tests/test_server.py::TestServerRoutes::test_get_block_returns_specific_block skills/annotate/tests/test_smoke_e2e_diagram.py -v`
Expected: both PASS.

- [ ] **Step 6: Run the full annotate test suite**

Run: `cd ~/projects/petros-skills && python -m pytest skills/annotate/ -v`
Expected: any remaining test that hard-codes a `version` field in `_write_blocks` calls may pass coincidentally (because version=1 is the derived value for any first-write block). Note any failures — they belong to Task 4.

- [ ] **Step 7: Commit**

```bash
git add skills/annotate/server.py skills/annotate/tests/test_server.py skills/annotate/tests/test_smoke_e2e_diagram.py
git commit -m "annotate: derive block render version from chain"
```

---

## Task 4: Simplify `blocks.py` — stop writing version fields

**Files:**
- Modify: `skills/annotate/blocks.py:69-76` (`update_block`) and `:104-111` (`update_spec_block`)
- Modify: `skills/annotate/blocks.py:1-13` (docstring)
- Modify: `skills/annotate/tests/test_blocks.py` — drop version-bump assertions

- [ ] **Step 1: Update tests to reflect new contract**

In `skills/annotate/tests/test_blocks.py`, replace `test_update_block_bumps_version` (lines 30-39):

```python
def test_update_block_returns_true_on_change(tmp_path):
    doc = BlocksDoc(response_id="r-1", title="t", blocks=[
        {"id": "b-0", "markdown": "old"},
        {"id": "b-1", "markdown": "x"},
    ])
    changed = update_block(doc, "b-0", "new")
    assert changed is True
    assert doc.blocks[0]["markdown"] == "new"
    # No version field is mutated — versions live in versions.json now.
    assert "version" not in doc.blocks[0] or doc.blocks[0].get("version") in (None, 1, 2)
```

Replace `test_update_block_no_op_when_unchanged` (lines 42-48):

```python
def test_update_block_no_op_when_unchanged(tmp_path):
    doc = BlocksDoc(response_id="r-1", title="t", blocks=[
        {"id": "b-0", "markdown": "same"},
    ])
    changed = update_block(doc, "b-0", "same")
    assert changed is False
```

Replace `test_update_spec_block_bumps_version_on_change` (lines 75-83):

```python
def test_update_spec_block_returns_true_on_change():
    spec_old = {"actors": [{"id": "a", "label": "A"}], "steps": []}
    spec_new = {"actors": [{"id": "a", "label": "A2"}], "steps": []}
    doc = BlocksDoc(response_id="r-1", title="t",
                    blocks=[_seq_block("b-0", spec_old)])
    changed = update_spec_block(doc, "b-0", spec_new)
    assert changed is True
    assert doc.blocks[0]["spec"] == spec_new
```

Replace `test_update_spec_block_no_op_when_equivalent` (lines 86-94):

```python
def test_update_spec_block_no_op_when_equivalent():
    spec = {"actors": [{"id": "a", "label": "A"}], "steps": []}
    doc = BlocksDoc(response_id="r-1", title="t",
                    blocks=[_seq_block("b-0", spec)])
    equivalent = {"steps": [], "actors": [{"label": "A", "id": "a"}]}
    changed = update_spec_block(doc, "b-0", equivalent)
    assert changed is False
```

Update `_seq_block` helper (line 71-72) to drop the version argument:

```python
def _seq_block(bid: str, spec: dict):
    return {"id": bid, "kind": "sequence", "spec": spec}
```

(Search the rest of `test_blocks.py` for any other `"version": N` literal in fixture data — leave them alone if the test doesn't read them back, but search-and-confirm with: `grep -n version skills/annotate/tests/test_blocks.py`.)

- [ ] **Step 2: Run tests to confirm they fail**

Run: `cd ~/projects/petros-skills && python -m pytest skills/annotate/tests/test_blocks.py -v`
Expected: a few FAILs because `update_block` still writes `version` and `_seq_block` is called with three args elsewhere.

- [ ] **Step 3: Update `blocks.py` — drop version writes + strip on save**

In `skills/annotate/blocks.py`:

Update `save_atomic` to strip the legacy `version` field from each block before serialising. Locate the function (around lines 47-59) and replace it:

```python
def save_atomic(path: Path, doc: BlocksDoc) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    cleaned: list[dict[str, Any]] = []
    for b in doc.blocks:
        cb = {k: v for k, v in b.items() if k != "version"}
        cleaned.append(cb)
    out: dict[str, Any] = {
        "response_id": doc.response_id,
        "title": doc.title,
        "blocks": cleaned,
    }
    if doc.glossary:
        out["glossary"] = doc.glossary
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(out, indent=2))
    tmp.replace(path)
```

Replace `update_block` (lines 69-76):

```python
def update_block(doc: BlocksDoc, block_id: str, new_markdown: str) -> bool:
    """Update a block's markdown. Returns True if content changed.

    The content-hash dedup makes a no-op rewrite a no-op. No version
    field is touched — versions are derived in versions.py.
    """
    b = find_block(doc, block_id)
    if b.get("markdown") == new_markdown:
        return False
    b["markdown"] = new_markdown
    return True
```

Replace `update_spec_block` (lines 104-111):

```python
def update_spec_block(doc: BlocksDoc, block_id: str, new_spec: dict[str, Any]) -> bool:
    """Update a sequence block's spec. Returns True if content changed.

    Canonical-JSON compare so reordered keys are a no-op. No version
    field is touched — versions are derived in versions.py.
    """
    b = find_block(doc, block_id)
    if _canonical_spec(b.get("spec") or {}) == _canonical_spec(new_spec):
        return False
    b["spec"] = new_spec
    return True
```

Update the module docstring (lines 1-13):

```python
"""blocks.json document model — annotate's canonical doc structure.

Schema:
    {
      "response_id": str,
      "title": str,
      "blocks": [{"id": "b-N", "markdown": str, ...}, ...]
    }

Per-block versions are *not* stored here. They are derived from a
per-block content-hash chain in `versions.json` (see versions.py).
The legacy `version` field on blocks (if present from older data) is
ignored on read and never written.

Block ids are stable for the session — minted once via next_block_id(),
never reassigned. Updating a block via update_block() is a no-op when
content is unchanged (content-hash dedup for re-apply safety).
"""
```

- [ ] **Step 4: Run the blocks tests**

Run: `cd ~/projects/petros-skills && python -m pytest skills/annotate/tests/test_blocks.py -v`
Expected: all PASS.

- [ ] **Step 5: Run the full annotate suite**

Run: `cd ~/projects/petros-skills && python -m pytest skills/annotate/ -v`
Expected: all PASS. If any test still fails because it asserted a `version` field on a saved block, change that test to assert via the derived poll/render path instead.

- [ ] **Step 6: Commit**

```bash
git add skills/annotate/blocks.py skills/annotate/tests/test_blocks.py
git commit -m "annotate: drop version field writes from blocks.py"
```

---

## Task 5: Update SKILL.md so Claude stops writing the version field

**Files:**
- Modify: `skills/annotate/SKILL.md` — lines 128-135 (schema), line 172 (sequence-block shape), line 347 (rewrite contract step 4)

- [ ] **Step 1: Read the three regions**

Run: `cd ~/projects/petros-skills && sed -n '125,140p;170,185p;337,360p' skills/annotate/SKILL.md`

- [ ] **Step 2: Edit the schema block (around line 128)**

Find:

```markdown
    "title": "<same as meta>",
    "blocks": [
      {"id": "b-0", "markdown": "<first block's markdown>", "version": 1},
      {"id": "b-1", "markdown": "<second block's markdown>", "version": 1},
      ...
    ]}
   ```
   Block ids are sequential `b-0`, `b-1`, `b-2`, ... starting from 0.  Each block starts at `version: 1`.
```

Replace with:

```markdown
    "title": "<same as meta>",
    "blocks": [
      {"id": "b-0", "markdown": "<first block's markdown>"},
      {"id": "b-1", "markdown": "<second block's markdown>"},
      ...
    ]}
   ```
   Block ids are sequential `b-0`, `b-1`, `b-2`, ... starting from 0. **Do not write a `version` field** — the server derives per-block versions from a content-hash chain stored separately. Whatever you put in a `version` field is ignored.
```

- [ ] **Step 3: Edit the sequence-block shape (around line 172)**

Find:

```markdown
    {"id": "b-N", "kind": "sequence", "version": 1, "spec": {
```

Replace with:

```markdown
    {"id": "b-N", "kind": "sequence", "spec": {
```

- [ ] **Step 4: Edit the rewrite contract (around line 347)**

Find:

```markdown
4. **Bump the version** of the block you rewrote (or of every block you changed, if multiple).  Block ids stay the same.  The server's content-hash dedup makes a no-op rewrite a no-op (safe under watcher re-emit).
```

Replace with:

```markdown
4. **Touch only the blocks you actually need to change.** Do not re-emit unchanged blocks "for completeness" — the server now derives `version` from a content-hash chain, so re-writing identical content is a true no-op but rewriting the same prose with cosmetic differences (a swapped synonym, a re-flowed sentence) inflates the version of a block the user didn't ask you to touch. Block ids stay the same; versions take care of themselves.
```

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/SKILL.md
git commit -m "annotate: SKILL.md — versions are derived, not authored"
```

---

## Task 6: Manual verification end-to-end

**Files:** none

- [ ] **Step 1: Run the full annotate suite**

Run: `cd ~/projects/petros-skills && python -m pytest skills/annotate/ -v`
Expected: all PASS.

- [ ] **Step 2: Walk an existing session through the new path**

Pick one of the existing session dirs (e.g. `~/projects/.claude/annotate/260526-103222-4bf9941453e2b28b/`) and remove any stale `versions.json` if present:

Run: `rm -f ~/projects/.claude/annotate/260526-103222-4bf9941453e2b28b/response/versions.json`

Then start the server (use `skills/annotate/ensure_server.sh`) and hit `/poll` for that session. Confirm every block reports `version: 1` (chain is fresh) and that `versions.json` materializes alongside `blocks.json`.

- [ ] **Step 3: Drive a content change manually**

Edit one block's `markdown` in the session's `blocks.json` (just append a word), poll again, confirm only that block's version goes to 2 and others stay at 1.

- [ ] **Step 4: Drive a cosmetic change**

Re-write the same block adding only trailing whitespace, poll again, confirm version stays at 2 (whitespace-tolerant hash).

- [ ] **Step 5: Final commit (if any cleanup landed)**

```bash
git status
# only commit if there are leftover unstaged changes from the verification
```

---

## Self-Review

**Spec coverage:**
- "version is derived, not authored" → Task 1 creates the chain; Tasks 2+3 wire it into both server entry points (`/poll` and per-block render); Task 4 drops the writes; Task 5 tells Claude.
- "single hash-function knob for future variants" → `_block_hash` in `versions.py` is the single place; whitespace-normalize for markdown and canonical-JSON for sequence are already in.
- "kills over-rewriting bug" → covered by `test_content_change_bumps_only_that_block` (Task 1) and the rewritten `test_poll_returns_version_vector` (Task 2).
- "kills cosmetic-churn bug" → covered by `test_whitespace_only_change_does_not_bump` (Task 1) and verified end-to-end in Task 6 Step 4.

**Placeholder scan:** Task 3 Step 1 says "find the caller … read the file before editing" — that's a real instruction, not a placeholder. Task 3 Step 4 says "Adjust to whatever the actual caller shape is" — this is because I haven't read the route handler that wraps `_render_block_for_raw`; the executor must read it before edits. Acceptable for a TDD plan since the failing test (Step 3) pins the behavior precisely.

**Type consistency:** `derive_versions(versions_path: Path, blocks: list[dict]) -> dict[str, int]` is used identically in Task 2 Step 3 and Task 3 Step 4. `_render_block_for_raw(blk, version)` signature in Task 3 Step 4 matches its single caller update in the same step.
