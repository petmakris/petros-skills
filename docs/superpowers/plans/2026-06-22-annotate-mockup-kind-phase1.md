# Annotate `mockup` Block Kind — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `kind: "mockup"` block that renders Claude-authored HTML/CSS/JS at full fidelity inside annotate, in a sandboxed iframe, commentable at whole-block granularity.

**Architecture:** The mockup's HTML lives in `spec.html`. The server forwards the spec to the client (no server-side rendering, mirroring `choice`). The client renders it into a `<iframe srcdoc sandbox="allow-scripts">` (no `allow-same-origin`), deliberately bypassing `sanitizeFreeHtml`, with a host-injected bridge script that reports content height back via `postMessage`. Whole-mockup comments already work through the default hover-actions strip (`step_id: null`). Per-region annotation is **Phase 2, out of scope here.**

**Tech Stack:** Python 3 stdlib (`http.server`-based server, `unittest`), vanilla JS client (`static/script.js`), Playwright (Node, `.cjs`) for client e2e.

## Global Constraints

- iframe attribute is exactly `sandbox="allow-scripts"` — **never** add `allow-same-origin` or `allow-top-navigation`.
- The host **must** authenticate iframe messages by object identity: `event.source === iframe.contentWindow`. Origin is the literal string `"null"` and must not be trusted.
- The host **must** clamp the reported height and **must not** `eval`/`innerHTML`/`fetch` any message field.
- Mockup HTML is carried in `spec.html` (a string). The server never parses or renders it.
- The frame CSP injected into every srcdoc is exactly: `default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; font-src data:; connect-src 'none'; form-action 'none'; base-uri 'none'`.
- `SKILL.md` must stay under 120 lines (enforced by `tests/test_skill_structure.py::test_skill_md_exists_and_is_lean`).
- Every `references/block-kinds/<kind>.md` file must be wired into the SKILL.md menu and vice-versa (enforced by `test_block_kind_menu_matches_reference_files`). The docs task must add the menu row and the file together.
- Run Python tests from the repo root `~/projects/petros-skills` with `PYTHONPATH=.`.

## File Structure

- `skills/annotate/versions.py` — modify: add `"mockup"` to `_SPEC_KINDS` so the version hash keys on the canonical spec.
- `skills/annotate/server.py` — modify: add a `mockup` branch in `_render_block_for_raw` that forwards `spec`.
- `skills/annotate/static/script.js` — modify: add `renderMockup`, the boot-level height-message listener, and the `updateBlockContent` rebuild guard.
- `skills/annotate/static/style.css` — modify: minimal `.mockup-frame` / `.mockup-missing` styling.
- `skills/annotate/SKILL.md` — modify: one menu row.
- `skills/annotate/references/pushing.md` — modify: ~3-sentence cross-reference.
- `skills/annotate/references/block-kinds/mockup.md` — create: the kind reference (~200 words).
- `skills/annotate/tests/test_versions.py` — modify: mockup spec-hashing tests.
- `skills/annotate/tests/test_server.py` — modify: `/raw` forwards mockup spec.
- `skills/annotate/tests/e2e/mockup.e2e.cjs` — create: Playwright client render + height-bridge e2e.

---

### Task 1: `mockup` is a spec-backed kind in the version hash

**Files:**
- Modify: `skills/annotate/versions.py:42`
- Test: `skills/annotate/tests/test_versions.py`

**Interfaces:**
- Consumes: `derive_versions(versions_path, blocks)` (existing).
- Produces: nothing new — extends existing behavior so `kind: "mockup"` blocks hash on `spec`, not `markdown`.

- [ ] **Step 1: Write the failing tests**

Append to `skills/annotate/tests/test_versions.py`:

```python
def test_mockup_spec_change_bumps(tmp_path):
    """Mockup content lives in spec.html, not markdown. A spec edit must bump
    the version, or the client never refetches the updated iframe."""
    vp = tmp_path / "versions.json"
    derive_versions(vp, [{"id": "b-0", "kind": "mockup", "spec": {"html": "<h1>A</h1>"}}])
    versions = derive_versions(
        vp, [{"id": "b-0", "kind": "mockup", "spec": {"html": "<h1>B</h1>"}}]
    )
    assert versions == {"b-0": 2}


def test_mockup_canonical_spec_no_bump_on_reorder(tmp_path):
    vp = tmp_path / "versions.json"
    derive_versions(vp, [{"id": "b-0", "kind": "mockup",
                          "spec": {"title": "T", "html": "<h1>A</h1>"}}])
    versions = derive_versions(vp, [{"id": "b-0", "kind": "mockup",
                                     "spec": {"html": "<h1>A</h1>", "title": "T"}}])
    assert versions == {"b-0": 1}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd ~/projects/petros-skills && PYTHONPATH=. python3 -m pytest skills/annotate/tests/test_versions.py::test_mockup_spec_change_bumps -v`
Expected: FAIL — `assert {"b-0": 1} == {"b-0": 2}` (mockup currently hashes on empty `markdown`, so the spec edit is invisible and the version never bumps).

- [ ] **Step 3: Add `"mockup"` to the spec-kinds tuple**

In `skills/annotate/versions.py:42`, change:

```python
_SPEC_KINDS = ("sequence", "diagram", "choice")
```
to:
```python
_SPEC_KINDS = ("sequence", "diagram", "choice", "mockup")
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd ~/projects/petros-skills && PYTHONPATH=. python3 -m pytest skills/annotate/tests/test_versions.py -v`
Expected: PASS (all, including the two new tests).

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/versions.py skills/annotate/tests/test_versions.py
git commit -m "feat(annotate): treat mockup as a spec-backed kind in version hash"
```

---

### Task 2: Server forwards `mockup` spec on `/raw`

**Files:**
- Modify: `skills/annotate/server.py` (`_render_block_for_raw`, the kind branch ~line 525, beside `choice`)
- Test: `skills/annotate/tests/test_server.py`

**Interfaces:**
- Consumes: the existing `/raw` endpoint and `_write_blocks(response_dir, response_id, title, blocks)` test helper.
- Produces: a `/raw` block payload for a mockup that includes `kind: "mockup"` and `spec` (with `html`), and **no** `svg` field.

- [ ] **Step 1: Write the failing test**

Add to the round-trip test class in `skills/annotate/tests/test_server.py` (same class as `test_raw_returns_svg_for_sequence_block`, near line 583):

```python
    def test_raw_forwards_spec_for_mockup_block(self):
        """A kind=mockup block round-trips with its spec (html) and no svg —
        the server forwards but does not render it."""
        response_dir = Path(self.sess["response_dir"])
        spec = {"title": "Dashboard", "html": "<h1 data-annotate-id='hd'>Hi</h1>"}
        _write_blocks(response_dir, "resp-mock", "T", [
            {"id": "b-0", "markdown": "intro", "version": 1},
            {"id": "b-1", "kind": "mockup", "spec": spec, "version": 1},
        ])
        status, body = _http_get("localhost", self.info["port"], self.base + "/raw")
        self.assertEqual(status, 200)
        data = json.loads(body)
        mock_blk = next(b for b in data["blocks"] if b["id"] == "b-1")
        self.assertEqual(mock_blk["kind"], "mockup")
        self.assertEqual(mock_blk["spec"]["html"], "<h1 data-annotate-id='hd'>Hi</h1>")
        self.assertNotIn("svg", mock_blk)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ~/projects/petros-skills && PYTHONPATH=. python3 -m pytest skills/annotate/tests/test_server.py -k test_raw_forwards_spec_for_mockup_block -v`
Expected: FAIL with `KeyError: 'spec'` — the mockup currently falls into the markdown `else` branch, which emits `markdown` (absent) and no `spec`.

- [ ] **Step 3: Add the mockup branch in `_render_block_for_raw`**

In `skills/annotate/server.py`, find the `elif kind == "choice":` branch (it reads `base["spec"] = blk.get("spec") or {}`) and add immediately after it, before the final `else:`:

```python
    elif kind == "mockup":
        # Trusted Claude HTML rendered client-side in a sandboxed iframe.
        # Server forwards the spec verbatim; it never parses or renders the HTML.
        base["spec"] = blk.get("spec") or {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd ~/projects/petros-skills && PYTHONPATH=. python3 -m pytest skills/annotate/tests/test_server.py -k mockup -v`
Expected: PASS.

- [ ] **Step 5: Run the full server + versions suites for regressions**

Run: `cd ~/projects/petros-skills && PYTHONPATH=. python3 -m pytest skills/annotate/tests/test_server.py skills/annotate/tests/test_versions.py -q`
Expected: PASS (no regressions; the `step_id`-against-markdown 422 test is untouched — Phase 2 owns that).

- [ ] **Step 6: Commit**

```bash
git add skills/annotate/server.py skills/annotate/tests/test_server.py
git commit -m "feat(annotate): forward mockup spec on /raw (no server render)"
```

---

### Task 3: Client renders a mockup in a sandboxed iframe with a height bridge

**Files:**
- Modify: `skills/annotate/static/script.js` (`createBlockSection` kind branch ~487–512; `updateBlockContent` guard ~1308; add module-level `renderMockup`, `mockupFrames`, and the boot message listener near `WebCompanion.init`)
- Modify: `skills/annotate/static/style.css`
- Test: `skills/annotate/tests/e2e/mockup.e2e.cjs` (create)

**Interfaces:**
- Consumes: `blk.spec.html` from Task 2's `/raw` payload; existing `createBlockSection(blk)` / `updateBlockContent(section, blk, srvVer)`.
- Produces: a `<iframe class="mockup-frame" sandbox="allow-scripts">` inside `.block-content` whose height is set from `annotate:height` postMessages.

- [ ] **Step 1: Write the failing e2e test**

Create `skills/annotate/tests/e2e/mockup.e2e.cjs`. Reuse the harness shape from `reconcile.e2e.cjs` (same `startServer`, `writeBlocks`, `createSession` helpers — copy them verbatim from that file's top section, they are self-contained), then the assertions:

```javascript
#!/usr/bin/env node
/*
 * Playwright e2e: a kind=mockup block renders in a sandboxed iframe and the
 * injected bridge sizes it.
 *   Run: NODE_PATH=$(npm root -g) node skills/annotate/tests/e2e/mockup.e2e.cjs
 *   (requires the global `playwright` package + an installed chromium)
 */
const { chromium } = require("playwright");
const { spawn } = require("child_process");
const readline = require("readline");
const http = require("http");
const fs = require("fs");
const os = require("os");
const path = require("path");
const REPO_ROOT = path.resolve(__dirname, "..", "..", "..", "..");
function log(m){ process.stdout.write(m + "\n"); }
function fail(m){ throw new Error("ASSERTION FAILED: " + m); }

// --- copy startServer(), postJSON(), writeBlocks() verbatim from reconcile.e2e.cjs ---
// --- copy createSession() (POST /api/session) verbatim from reconcile.e2e.cjs ---

async function main() {
  const { proc, info, fakeHome } = await startServer();
  let browser;
  try {
    const project = fs.mkdtempSync(path.join(os.tmpdir(), "annotate-mockup-"));
    const sess = await createSession(info.port, project);
    const responseDir = sess.response_dir;
    writeBlocks(responseDir, [
      { id: "b-0", kind: "mockup", version: 1, spec: {
          title: "Mock",
          html: "<div data-annotate-id='hero' style='height:240px'>HELLO_MOCKUP</div>" } },
    ]);

    browser = await chromium.launch();
    const page = await browser.newPage();
    await page.goto(`http://localhost:${info.port}/s/${sess.sid}/`);

    const frame = page.locator("section[data-block-id='b-0'] iframe.mockup-frame");
    await frame.waitFor({ state: "attached", timeout: 5000 });

    // 1. Sandbox is exactly allow-scripts; never allow-same-origin.
    const sandbox = await frame.getAttribute("sandbox");
    if (sandbox !== "allow-scripts") fail("sandbox must be exactly 'allow-scripts', got: " + sandbox);

    // 2. The mockup HTML is inside the frame document.
    const fl = page.frameLocator("section[data-block-id='b-0'] iframe.mockup-frame");
    await fl.locator("text=HELLO_MOCKUP").waitFor({ timeout: 5000 });

    // 3. The bridge sized the frame to its content (>200px, our hero is 240px).
    await page.waitForFunction(() => {
      const f = document.querySelector("section[data-block-id='b-0'] iframe.mockup-frame");
      return f && parseInt(f.style.height || "0", 10) > 200;
    }, { timeout: 5000 });

    log("PASS: mockup renders in sandboxed iframe and is sized by the bridge");
  } finally {
    if (browser) await browser.close();
    try { proc.kill(); } catch (_) {}
    fs.rmSync(fakeHome, { recursive: true, force: true });
  }
}
main().catch((e) => { log(e.stack || String(e)); process.exit(1); });
```

- [ ] **Step 2: Run the e2e to verify it fails**

Run: `cd ~/projects/petros-skills && NODE_PATH=$(npm root -g) node skills/annotate/tests/e2e/mockup.e2e.cjs`
Expected: FAIL — no `iframe.mockup-frame` is found (mockup falls through to the markdown path today and renders nothing useful). If chromium/playwright is not installed, install per the repo's existing e2e setup (`npm i -g playwright && npx playwright install chromium`) before proceeding.

- [ ] **Step 3: Add the height-message listener and frame registry (boot level)**

In `skills/annotate/static/script.js`, near the top of the IIFE module scope (beside other module-level state), add:

```javascript
  // Live registry of mockup iframes, so the single boot-level message handler
  // can match an inbound postMessage to the iframe that sent it by object
  // identity (the frame's origin is the string "null" and must NOT be trusted).
  const mockupFrames = new Set();

  window.addEventListener("message", (ev) => {
    const d = ev.data;
    if (!d || d.type !== "annotate:height") return;
    let target = null;
    for (const f of Array.from(mockupFrames)) {
      if (!f.isConnected) { mockupFrames.delete(f); continue; }  // prune stale
      if (f.contentWindow === ev.source) target = f;             // identity gate
    }
    if (!target) return;
    const h = Number(d.h);
    if (!Number.isFinite(h)) return;                             // ignore garbage
    target.style.height = Math.min(Math.max(h, 20), 20000) + "px"; // clamp
  });
```

- [ ] **Step 4: Add the `renderMockup` function and its constants**

In `skills/annotate/static/script.js`, add (module scope, near `createBlockSection`):

```javascript
  const MOCKUP_CSP =
    '<meta http-equiv="Content-Security-Policy" content="' +
    "default-src 'none'; img-src data:; style-src 'unsafe-inline'; " +
    "script-src 'unsafe-inline'; font-src data:; connect-src 'none'; " +
    "form-action 'none'; base-uri 'none'\">";

  // Trusted, host-injected. Reports content height up so the host can size the
  // iframe. Posts on observe, DOMContentLoaded, load, and late <img> loads.
  const MOCKUP_BRIDGE =
    "<scr" + "ipt>(function(){function p(){parent.postMessage(" +
    "{type:'annotate:height',h:document.documentElement.scrollHeight},'*');}" +
    "try{new ResizeObserver(p).observe(document.documentElement);}catch(e){}" +
    "document.addEventListener('DOMContentLoaded',p);" +
    "window.addEventListener('load',p,true);p();})();</scr" + "ipt>";

  function renderMockup(content, blk) {
    const html = (blk.spec && blk.spec.html) || "";
    if (!html) {
      content.innerHTML = '<div class="mockup-missing">mockup unavailable</div>';
      return;
    }
    const iframe = document.createElement("iframe");
    iframe.className = "mockup-frame";
    iframe.setAttribute("sandbox", "allow-scripts");   // NEVER allow-same-origin
    iframe.setAttribute("scrolling", "no");
    iframe.style.height = "60px";                       // placeholder until bridge reports
    iframe.srcdoc =
      '<!DOCTYPE html><html><head><meta charset="utf-8">' + MOCKUP_CSP +
      "<style>html,body{margin:0;padding:0}</style></head><body>" +
      html + MOCKUP_BRIDGE + "</body></html>";
    mockupFrames.add(iframe);
    content.appendChild(iframe);
  }
```

- [ ] **Step 5: Wire the kind branch in `createBlockSection`**

In `skills/annotate/static/script.js`, in `createBlockSection`, add a branch before the markdown `else` (after the `choice` branch ~line 503):

```javascript
    } else if (kind === "mockup") {
      renderMockup(content, blk);
```

- [ ] **Step 6: Make `updateBlockContent` rebuild a mockup fresh on change**

In `skills/annotate/static/script.js`, in `updateBlockContent` (~line 1308), extend the fresh-section guard so a mockup rebuilds (a new iframe + srcdoc), mirroring `choice`:

```javascript
    if (newKind !== oldKind || newKind === "choice" || newKind === "mockup") {
```

- [ ] **Step 7: Add minimal styling**

Append to `skills/annotate/static/style.css`:

```css
.mockup-frame { width: 100%; border: 0; display: block; background: #fff; border-radius: 8px; }
.mockup-missing { padding: 16px; color: var(--text-dim); font-style: italic; }
```

- [ ] **Step 8: Run the e2e to verify it passes**

Run: `cd ~/projects/petros-skills && NODE_PATH=$(npm root -g) node skills/annotate/tests/e2e/mockup.e2e.cjs`
Expected: `PASS: mockup renders in sandboxed iframe and is sized by the bridge`.

- [ ] **Step 9: Run the existing sanitizer + card-structure smoke tests for regressions**

Run: `cd ~/projects/petros-skills && PYTHONPATH=. python3 -m pytest skills/annotate/tests/test_smoke_sanitizer.py skills/annotate/tests/test_smoke_card_structure.py -q`
Expected: PASS (mockup leaves the markdown sanitizer path untouched).

- [ ] **Step 10: Commit**

```bash
git add skills/annotate/static/script.js skills/annotate/static/style.css skills/annotate/tests/e2e/mockup.e2e.cjs
git commit -m "feat(annotate): render mockup kind in sandboxed iframe with height bridge"
```

---

### Task 4: Skill docs — menu row, push cross-reference, kind reference

**Files:**
- Modify: `skills/annotate/SKILL.md` (block-kind menu table)
- Modify: `skills/annotate/references/pushing.md` (end of the "Inline HTML inside markdown blocks" section)
- Create: `skills/annotate/references/block-kinds/mockup.md`
- Test: `skills/annotate/tests/test_skill_structure.py` (existing — must stay green)

**Interfaces:**
- Consumes: the structural invariants in `test_skill_structure.py` (menu ⇔ files, SKILL.md < 120 lines, no broken/orphan links).
- Produces: a discoverable `mockup` kind wired into progressive disclosure.

- [ ] **Step 1: Run the structure test to confirm the starting state is green**

Run: `cd ~/projects/petros-skills && PYTHONPATH=. python3 -m pytest skills/annotate/tests/test_skill_structure.py -v`
Expected: PASS. (After Step 2 adds the menu row but before Step 3 creates the file, `test_block_kind_menu_matches_reference_files` will FAIL — that is expected and fixed by Step 3.)

- [ ] **Step 2: Add the menu row to `SKILL.md`**

In `skills/annotate/SKILL.md`, in the "Block-kind menu" table, add after the `diagram` row:

```markdown
| `mockup` | A high-fidelity, interactive UI mock is clearer than prose or a static diagram — real `<style>`/`<script>`/Tailwind, hover, interaction. Renders in a sandboxed iframe. | `references/block-kinds/mockup.md` |
```

- [ ] **Step 3: Create `references/block-kinds/mockup.md`**

Create `skills/annotate/references/block-kinds/mockup.md`:

```markdown
# Block kind: `mockup`

A full-fidelity UI mock rendered in a **sandboxed iframe**. Use it when a real
interactive interface communicates better than prose or a static diagram.

## When to use
- You want to show an application screen with real CSS: hover/focus states,
  Tailwind, gradients, a working toggle or tab.
- A picture of the UI is the point, and it benefits from interaction.

## When NOT to use
- Anything an inline-`style="…"` markdown block already does (a colored
  callout, a small table). Don't escalate to a sandbox for static styling.
- Static structure / architecture → use `kind: "diagram"`.
- More than ~one screen of UI. Keep a mock focused.

## Block shape

    {"id": "section-N", "kind": "mockup", "spec": {
      "title": "<short title>",
      "html": "<self-contained fragment; may use <style>/<script>/Tailwind>"
    }}

## What the sandbox changes
Because this renders in a sandboxed iframe, the page sanitizer does **not** run
on this block — you may use `<style>`, `<script>`, and CDN Tailwind. In return:
emit a **self-contained** fragment. The frame is isolated, so it does **not**
inherit the page's CSS variables (`var(--accent)` etc.) — bring your own
styling. (This is the one place mockups differ from inline-HTML markdown, which
reuses the page palette — see `references/pushing.md` § Inline HTML.)

## Commenting and rewriting
Whole-mockup comments arrive with `step_id: null` — rewrite `spec.html` via
`update_spec_block` (same helper as a diagram's `spec.source`; see
`references/handling-events.md`). Per-region commenting (clicking a
`data-annotate-id` region) is not yet wired — mark regions with
`data-annotate-id` anyway so it works when enabled, and preserve surviving
slugs on rewrite.
```

- [ ] **Step 4: Add the cross-reference to `pushing.md`**

In `skills/annotate/references/pushing.md`, at the end of the "Inline HTML inside markdown blocks" section, add:

```markdown
For a **high-fidelity** mock that needs `<style>`/`<script>`/Tailwind, hover, or
interaction, use `kind: "mockup"` instead — it renders in a sandboxed iframe with
the sanitizer lifted. The `data-annotate-id` region convention above is unchanged.
See `references/block-kinds/mockup.md`.
```

- [ ] **Step 5: Run the structure test to verify it passes**

Run: `cd ~/projects/petros-skills && PYTHONPATH=. python3 -m pytest skills/annotate/tests/test_skill_structure.py -v`
Expected: PASS — menu now matches the on-disk `mockup.md`, the link resolves, SKILL.md is still under 120 lines.

- [ ] **Step 6: Commit**

```bash
git add skills/annotate/SKILL.md skills/annotate/references/pushing.md skills/annotate/references/block-kinds/mockup.md
git commit -m "docs(annotate): wire mockup kind into the skill menu and references"
```

---

## Final verification

- [ ] **Run the full annotate Python suite**

Run: `cd ~/projects/petros-skills && PYTHONPATH=. python3 -m pytest skills/annotate/tests -q --ignore=skills/annotate/tests/e2e`
Expected: all pass.

- [ ] **Run the mockup e2e once more**

Run: `cd ~/projects/petros-skills && NODE_PATH=$(npm root -g) node skills/annotate/tests/e2e/mockup.e2e.cjs`
Expected: PASS.

- [ ] **Manual smoke (optional but recommended):** push a real mockup block through annotate, confirm it renders at full fidelity, sizes correctly, survives a comment + rewrite (version bumps, iframe refreshes), and that a comment on it arrives with `step_id: null`.

## Out of scope (Phase 2 — separate plan)

Per-region annotation: the iframe click-forwarding branch of the bridge, the `onHoverAction → openAnnotation` refactor, relaxing the `server.py` `step_id` kind-gate (currently 422 for non-sequence, locked by `test_submit_step_id_against_markdown_block_returns_422`), and the region hover-glow round-trip.
