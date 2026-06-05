# Annotate Block Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fuzzy search box to the annotate web page that hides non-matching blocks, shows a "Showing X of Y blocks" count, and highlights matched terms — ported from the dashboard app's Fuse.js search.

**Architecture:** 100% client-side. A vendored Fuse.js indexes the plain text of each rendered block (`section.block` in `main.prose`). Typing in the header search box hides non-matching blocks via a `.search-hidden` class and highlights literal matches with `<mark class="search-hit">`. A `MutationObserver` rebuilds the index after the live-poll reconciles the DOM. No server endpoint, block model, versioning, or annotation-pipeline code changes.

**Tech Stack:** Vanilla JS (IIFE, like `static/script.js`), Fuse.js 7.0.0 (vendored UMD), CSS using existing design tokens, Python `http.server` (server-side markup injection), Playwright (e2e), pytest (smoke).

---

## File Structure

| File | Responsibility |
|------|----------------|
| `skills/annotate/static/fuse.min.js` | **New** — vendored Fuse.js 7.0.0 UMD build. Provides the global `Fuse`. |
| `skills/annotate/static/search.js` | **New** — all search behavior: index build, filter, count line, highlight, keyboard shortcuts, observer-driven re-index. |
| `skills/annotate/static/style.css` | **Modify** — append search-box and search-state CSS. |
| `skills/annotate/server.py` | **Modify** — inject the search-box markup into the header (`serve_root`, ~lines 70–76) and add the two `<script>` tags to `head` (~lines 87–92). |
| `skills/annotate/tests/test_smoke_block_search.py` | **New** — structural smoke test (source/string assertions, matching the repo's existing smoke-test style). |
| `skills/annotate/tests/e2e/search.e2e.cjs` | **New** — Playwright e2e driving the live filter behavior. |

---

### Task 1: Vendor Fuse.js

**Files:**
- Create: `skills/annotate/static/fuse.min.js`

- [ ] **Step 1: Download the pinned UMD build**

Run:
```bash
curl -fL https://unpkg.com/fuse.js@7.0.0/dist/fuse.min.js \
  -o skills/annotate/static/fuse.min.js
```

- [ ] **Step 2: Verify it downloaded and exposes the global**

Run:
```bash
test -s skills/annotate/static/fuse.min.js && \
grep -q "Fuse" skills/annotate/static/fuse.min.js && \
head -c 80 skills/annotate/static/fuse.min.js
```
Expected: prints the file's opening bytes (a minified UMD header mentioning `Fuse`), exit code 0.

- [ ] **Step 3: Commit**

```bash
git add skills/annotate/static/fuse.min.js
git commit -m "feat(annotate): vendor Fuse.js 7.0.0 for block search"
```

---

### Task 2: Inject the search box markup and scripts (server-side)

The page is rendered by `serve_root` in `server.py`. We add the search-box markup inside `.header-actions` (before the Done button) and two `<script>` tags to `head`. A smoke test guards both.

**Files:**
- Test: `skills/annotate/tests/test_smoke_block_search.py`
- Modify: `skills/annotate/server.py` (header f-string ~70–76; `head` assets ~87–92)

- [ ] **Step 1: Write the failing smoke test**

Create `skills/annotate/tests/test_smoke_block_search.py`:
```python
"""Structural guard for the client-side block-search feature.

Asserts the search box markup and its script/style hooks exist. These are
string/source checks (matching the repo's other smoke tests) — the live
behavior is covered by tests/e2e/search.e2e.cjs.
"""
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SERVER_PY = REPO / "skills" / "annotate" / "server.py"
STYLE_CSS = REPO / "skills" / "annotate" / "static" / "style.css"
SEARCH_JS = REPO / "skills" / "annotate" / "static" / "search.js"
FUSE_JS = REPO / "skills" / "annotate" / "static" / "fuse.min.js"


def test_search_box_markup_in_server():
    src = SERVER_PY.read_text()
    assert 'id="block-search"' in src, "search input missing from rendered header"
    assert "header-search" in src, "search wrapper missing from header"


def test_search_scripts_included():
    src = SERVER_PY.read_text()
    assert "/static/fuse.min.js" in src, "fuse.min.js not included in page head"
    assert "/static/search.js" in src, "search.js not included in page head"


def test_search_static_files_exist():
    assert FUSE_JS.exists(), "vendored fuse.min.js missing"
    assert SEARCH_JS.exists(), "search.js missing"


def test_search_css_present():
    css = STYLE_CSS.read_text()
    for needle in (".header-search", ".search-input", ".search-hidden",
                   "mark.search-hit", ".search-count"):
        assert needle in css, f"style.css missing {needle!r}"
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `python3 -m pytest skills/annotate/tests/test_smoke_block_search.py -v`
Expected: FAIL — `test_search_box_markup_in_server` (and others) fail because the markup/scripts/files do not exist yet.

- [ ] **Step 3: Add the search-box markup to the header**

In `skills/annotate/server.py`, find the header block in `serve_root` (the `.header-actions` div) and insert the search wrapper before the Done button. Change:
```python
            f'</div><div class="header-actions">'
            f'<button id="done-btn" type="button" class="done-btn">Done</button>'
            f'</div></header>'
```
to:
```python
            f'</div><div class="header-actions">'
            f'<div class="header-search">'
            f'<svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor"'
            f' stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
            f'<circle cx="11" cy="11" r="8"></circle>'
            f'<line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>'
            f'<input id="block-search" class="search-input" type="text"'
            f' placeholder="Search blocks…" autocomplete="off" spellcheck="false"'
            f' aria-label="Search blocks">'
            f'<span class="search-kbd">/</span>'
            f'</div>'
            f'<button id="done-btn" type="button" class="done-btn">Done</button>'
            f'</div></header>'
```

- [ ] **Step 4: Add the two script tags to `head`**

In the same method, change the `head` assignment:
```python
        head = ('<link rel="stylesheet" href="/static/style.css">'
                '<link rel="stylesheet" href="/static/diagram.css">'
                '<link rel="stylesheet" href="/static/popover.css">'
                '<script src="/static/popover.js" defer></script>'
                '<script src="/static/script.js" defer></script>'
                '<script src="/static/voice.js" defer></script>')
```
to (add the two search lines after `script.js`):
```python
        head = ('<link rel="stylesheet" href="/static/style.css">'
                '<link rel="stylesheet" href="/static/diagram.css">'
                '<link rel="stylesheet" href="/static/popover.css">'
                '<script src="/static/popover.js" defer></script>'
                '<script src="/static/script.js" defer></script>'
                '<script src="/static/fuse.min.js" defer></script>'
                '<script src="/static/search.js" defer></script>'
                '<script src="/static/voice.js" defer></script>')
```

- [ ] **Step 5: Create a placeholder `search.js` and an empty CSS marker so the file/CSS assertions can pass once their tasks run**

This task's test also asserts `search.js` exists and CSS classes are present — those are produced in Tasks 3 and 4. To keep tasks independently committable, create a minimal stub now and flesh it out later:

Create `skills/annotate/static/search.js`:
```javascript
// Block search — implemented in Task 4.
(function () { "use strict"; })();
```

Append to `skills/annotate/static/style.css`:
```css
/* === Block search (placeholder — styled in Task 3) ==================== */
.header-search {}
.search-input {}
.search-hidden { display: none !important; }
mark.search-hit {}
.search-count {}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `python3 -m pytest skills/annotate/tests/test_smoke_block_search.py -v`
Expected: PASS (all four tests).

- [ ] **Step 7: Commit**

```bash
git add skills/annotate/server.py skills/annotate/static/search.js \
  skills/annotate/static/style.css skills/annotate/tests/test_smoke_block_search.py
git commit -m "feat(annotate): add search box markup + script hooks to page header"
```

---

### Task 3: Style the search box and search states

Replace the placeholder CSS with real styling using existing tokens (`--surface`, `--border`, `--accent`, `--text-dim`, `--highlight`).

**Files:**
- Modify: `skills/annotate/static/style.css`

- [ ] **Step 1: Replace the placeholder CSS block**

In `skills/annotate/static/style.css`, replace the placeholder block from Task 2 with:
```css
/* === Block search ===================================================== */
.header-search {
  position: relative;
  width: 220px;
}
.header-search .search-icon {
  position: absolute;
  left: 9px;
  top: 50%;
  transform: translateY(-50%);
  width: 13px;
  height: 13px;
  color: var(--text-dim);
  pointer-events: none;
}
.search-input {
  width: 100%;
  padding: 5px 28px 5px 28px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 7px;
  color: var(--text);
  font-family: inherit;
  font-size: 12.5px;
  line-height: 1.4;
  outline: none;
  transition: border-color 140ms ease, box-shadow 140ms ease;
}
.search-input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px rgba(0, 113, 227, 0.15);
}
.search-input::placeholder { color: var(--text-dim); }
.search-kbd {
  position: absolute;
  right: 7px;
  top: 50%;
  transform: translateY(-50%);
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 1px 6px;
  font-family: 'Monaspace Radon', ui-monospace, Menlo, monospace;
  font-size: 10px;
  color: var(--text-dim);
  pointer-events: none;
}
/* Hide the kbd hint once the user is typing (input has focus). */
.search-input:focus + .search-kbd { opacity: 0; }

.search-hidden { display: none !important; }

.search-count {
  font-size: 12px;
  color: var(--text-dim);
  margin: 0 6px 14px;
}

main.prose mark.search-hit {
  background: var(--highlight);
  color: var(--text-strong);
  border-radius: 2px;
  padding: 0 1px;
}
```

- [ ] **Step 2: Verify the smoke test still passes**

Run: `python3 -m pytest skills/annotate/tests/test_smoke_block_search.py::test_search_css_present -v`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add skills/annotate/static/style.css
git commit -m "feat(annotate): style the block-search box and match states"
```

---

### Task 4: Implement the search behavior (search.js)

Replace the stub with the full implementation, driven by a Playwright e2e that mirrors the existing `reconcile.e2e.cjs` harness.

**Files:**
- Test: `skills/annotate/tests/e2e/search.e2e.cjs`
- Modify: `skills/annotate/static/search.js`

- [ ] **Step 1: Write the failing e2e test**

Create `skills/annotate/tests/e2e/search.e2e.cjs`:
```javascript
#!/usr/bin/env node
/*
 * Playwright e2e for the annotate block-search feature.
 *
 * Seeds 3 blocks (one mentions "proposal", two do not), then drives the
 * header search box and asserts:
 *   - typing "proposal" hides the two non-matching blocks and keeps the match
 *   - the count line reads "Showing 1 of 3 blocks"
 *   - the matched term is wrapped in <mark class="search-hit">
 *   - "/" focuses the box from anywhere; "Escape" clears + restores all blocks
 *
 * Run:
 *   NODE_PATH=$(npm root -g) node skills/annotate/tests/e2e/search.e2e.cjs
 * (requires the global `playwright` package + an installed chromium)
 */
const { chromium } = require("playwright");
const { spawn } = require("child_process");
const readline = require("readline");
const http = require("http");
const fs = require("fs");
const os = require("os");
const path = require("path");

const REPO_ROOT = path.resolve(__dirname, "..", "..", "..", "..");

function log(msg) { process.stdout.write(msg + "\n"); }
function fail(msg) { throw new Error("ASSERTION FAILED: " + msg); }

function startServer() {
  const fakeHome = fs.mkdtempSync(path.join(os.tmpdir(), "annotate-search-home-"));
  const proc = spawn("python3", ["-m", "skills.annotate.server"], {
    cwd: REPO_ROOT,
    env: {
      ...process.env,
      PYTHONPATH: REPO_ROOT,
      HOME: fakeHome,
      ANNOTATE_PUBLIC_HOST: "localhost",
      ANNOTATE_SHUTDOWN_SECONDS: "120",
    },
  });
  return new Promise((resolve, reject) => {
    const rl = readline.createInterface({ input: proc.stdout });
    rl.on("line", (line) => {
      try {
        const info = JSON.parse(line);
        if (info.type === "server-started") resolve({ proc, info, rl, fakeHome });
      } catch (_) { /* http log lines */ }
    });
    proc.stderr.on("data", () => {});
    proc.on("exit", (code) => reject(new Error("server exited early: " + code)));
    setTimeout(() => reject(new Error("server start timeout")), 8000);
  });
}

function postJSON(port, urlPath, body) {
  return new Promise((resolve, reject) => {
    const data = Buffer.from(JSON.stringify(body));
    const req = http.request(
      { host: "localhost", port, path: urlPath, method: "POST",
        headers: { "Content-Type": "application/json", "Content-Length": data.length } },
      (res) => {
        let buf = "";
        res.on("data", (c) => (buf += c));
        res.on("end", () => resolve({ status: res.statusCode, body: buf }));
      });
    req.on("error", reject);
    req.write(data);
    req.end();
  });
}

function writeBlocks(responseDir, blocks) {
  const doc = { response_id: "resp-search", title: "search-e2e", blocks };
  const tmp = path.join(responseDir, "blocks.json.tmp");
  fs.writeFileSync(tmp, JSON.stringify(doc));
  fs.renameSync(tmp, path.join(responseDir, "blocks.json"));
}

(async () => {
  const { proc, info, fakeHome } = await startServer();
  let browser;
  const cleanup = () => {
    try { browser && browser.close(); } catch (_) {}
    try { proc.kill(); } catch (_) {}
    try { fs.rmSync(fakeHome, { recursive: true, force: true }); } catch (_) {}
  };
  try {
    const project = fs.mkdtempSync(path.join(os.tmpdir(), "annotate-search-proj-"));
    const sess = JSON.parse((await postJSON(info.port, "/api/sessions", { cwd: project })).body);
    writeBlocks(sess.response_dir, [
      { id: "b-0", markdown: "# Proposal validation\n\nChecks the advisor proposal draft." },
      { id: "b-1", markdown: "# Docker builds\n\nDo not build images without tests." },
      { id: "b-2", markdown: "# Parallel tests\n\nEnable parallel integration tests." },
    ]);

    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    await page.goto(sess.url, { waitUntil: "domcontentloaded" });
    await page.waitForSelector('section.block[data-block-id="b-0"]', { timeout: 8000 });
    log("✓ blocks rendered");

    // Type the query.
    await page.fill("#block-search", "proposal");

    // Matching block stays visible; non-matching blocks hidden.
    await page.waitForSelector('section.block[data-block-id="b-1"].search-hidden', { timeout: 5000 });
    const b0hidden = await page.locator('section.block[data-block-id="b-0"].search-hidden').count();
    if (b0hidden !== 0) fail("matching block b-0 was hidden");
    const b2hidden = await page.locator('section.block[data-block-id="b-2"].search-hidden').count();
    if (b2hidden !== 1) fail("non-matching block b-2 not hidden");
    log("✓ non-matching blocks hidden, match kept");

    // Count line.
    const countText = (await page.locator(".search-count").innerText()).trim();
    if (countText !== "Showing 1 of 3 blocks") fail("count line wrong: " + JSON.stringify(countText));
    log("✓ count line correct");

    // Highlight present in the matching block.
    const marks = await page.locator('section.block[data-block-id="b-0"] mark.search-hit').count();
    if (marks < 1) fail("matched term not highlighted");
    log("✓ matched term highlighted");

    // Escape clears and restores all blocks.
    await page.focus("#block-search");
    await page.keyboard.press("Escape");
    const stillHidden = await page.locator("section.block.search-hidden").count();
    if (stillHidden !== 0) fail("Escape did not restore hidden blocks");
    const countAfter = await page.locator(".search-count").count();
    if (countAfter !== 0) fail("count line not removed after clear");
    log("✓ Escape clears query and restores all blocks");

    // "/" focuses the box from the body.
    await page.locator("body").click();
    await page.keyboard.press("/");
    const focusedId = await page.evaluate(() => document.activeElement && document.activeElement.id);
    if (focusedId !== "block-search") fail('"/" did not focus the search box');
    log('✓ "/" focuses the search box');

    log("\nSEARCH E2E PASSED");
    cleanup();
    process.exit(0);
  } catch (err) {
    log("\nSEARCH E2E FAILED: " + (err && err.stack ? err.stack : err));
    cleanup();
    process.exit(1);
  }
})();
```

- [ ] **Step 2: Run the e2e to verify it fails**

Run: `NODE_PATH=$(npm root -g) node skills/annotate/tests/e2e/search.e2e.cjs`
Expected: FAIL at "non-matching blocks hidden" — the stub `search.js` does nothing, so no `.search-hidden` class is applied.

- [ ] **Step 3: Implement `search.js`**

Replace the entire contents of `skills/annotate/static/search.js` with:
```javascript
// Block search — client-side fuzzy filter over rendered annotate blocks.
// Zero server interaction: indexes section.block textContent, hides
// non-matches, highlights literal hits, rebuilds on DOM reconcile.
(function () {
  "use strict";

  const SEARCH_ID = "block-search";
  let fuse = null;
  let countEl = null;
  let observer = null;

  function prose() { return document.querySelector("main.prose"); }

  function blockSections() {
    return Array.from(document.querySelectorAll("main.prose section.block[data-block-id]"));
  }

  function escapeRe(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"); }

  function buildIndex() {
    const items = blockSections().map((s) => ({ id: s.dataset.blockId, text: s.textContent || "" }));
    fuse = new Fuse(items, {
      keys: ["text"],
      threshold: 0.3,
      ignoreLocation: true,
      includeMatches: true,
    });
  }

  function ensureCountEl() {
    const root = prose();
    if (!root) return null;
    if (!countEl || !countEl.isConnected) {
      countEl = document.createElement("div");
      countEl.className = "search-count";
      root.insertBefore(countEl, root.firstChild);
    }
    return countEl;
  }

  function removeCountEl() {
    if (countEl && countEl.isConnected) countEl.remove();
    countEl = null;
  }

  function clearHighlights(root) {
    root.querySelectorAll("mark.search-hit").forEach((m) => {
      const parent = m.parentNode;
      parent.replaceChild(document.createTextNode(m.textContent), m);
      parent.normalize();
    });
  }

  function highlight(section, terms) {
    if (!terms.length) return;
    const re = new RegExp("(" + terms.map(escapeRe).join("|") + ")", "gi");
    const walker = document.createTreeWalker(section, NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        if (!node.nodeValue || !node.nodeValue.trim()) return NodeFilter.FILTER_REJECT;
        const el = node.parentElement;
        if (!el) return NodeFilter.FILTER_REJECT;
        if (el.closest("svg")) return NodeFilter.FILTER_REJECT;        // skip diagram SVG text
        if (el.closest("mark.search-hit")) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      },
    });
    const targets = [];
    let n;
    while ((n = walker.nextNode())) targets.push(n);
    targets.forEach((node) => {
      const text = node.nodeValue;
      const local = new RegExp(re.source, "gi");
      if (!local.test(text)) return;
      local.lastIndex = 0;
      const frag = document.createDocumentFragment();
      let last = 0;
      let m;
      while ((m = local.exec(text))) {
        if (m.index > last) frag.appendChild(document.createTextNode(text.slice(last, m.index)));
        const mark = document.createElement("mark");
        mark.className = "search-hit";
        mark.textContent = m[0];
        frag.appendChild(mark);
        last = m.index + m[0].length;
        if (m.index === local.lastIndex) local.lastIndex++; // guard against zero-width
      }
      if (last < text.length) frag.appendChild(document.createTextNode(text.slice(last)));
      node.parentNode.replaceChild(frag, node);
    });
  }

  function pauseObserver() { if (observer) observer.disconnect(); }
  function resumeObserver() {
    const root = prose();
    if (observer && root) observer.observe(root, { childList: true });
  }

  function applyFilter(query) {
    pauseObserver();
    try {
      const root = prose();
      if (root) clearHighlights(root);
      const sections = blockSections();
      const q = (query || "").trim();

      if (!q) {
        sections.forEach((s) => {
          s.classList.remove("search-hidden");
          const ic = s.nextElementSibling;
          if (ic && ic.classList.contains("inline-comments")) ic.classList.remove("search-hidden");
        });
        removeCountEl();
        return;
      }

      const matched = new Set((fuse ? fuse.search(q) : []).map((r) => r.item.id));
      sections.forEach((s) => {
        const hide = !matched.has(s.dataset.blockId);
        s.classList.toggle("search-hidden", hide);
        const ic = s.nextElementSibling;
        if (ic && ic.classList.contains("inline-comments")) ic.classList.toggle("search-hidden", hide);
      });

      const el = ensureCountEl();
      if (el) el.textContent = "Showing " + matched.size + " of " + sections.length + " blocks";

      const terms = q.split(/\s+/).filter(Boolean);
      sections.forEach((s) => { if (matched.has(s.dataset.blockId)) highlight(s, terms); });
    } finally {
      resumeObserver();
    }
  }

  function init() {
    const input = document.getElementById(SEARCH_ID);
    if (!input) return;
    buildIndex();

    input.addEventListener("input", () => applyFilter(input.value));

    document.addEventListener("keydown", (e) => {
      const active = document.activeElement;
      const inField = active instanceof HTMLInputElement || active instanceof HTMLTextAreaElement;
      if (e.key === "/" && !inField) {
        e.preventDefault();
        input.focus();
      } else if (e.key === "Escape" && active === input) {
        input.value = "";
        applyFilter("");
        input.blur();
      }
    });

    const root = prose();
    if (root) {
      let t = null;
      observer = new MutationObserver(() => {
        clearTimeout(t);
        t = setTimeout(() => {
          buildIndex();
          applyFilter(input.value);
        }, 120);
      });
      observer.observe(root, { childList: true });
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
```

- [ ] **Step 4: Run the e2e to verify it passes**

Run: `NODE_PATH=$(npm root -g) node skills/annotate/tests/e2e/search.e2e.cjs`
Expected: PASS — prints each `✓` line and "SEARCH E2E PASSED", exit code 0.

If `playwright`/chromium is not installed globally, install per the existing e2e's requirement:
```bash
npm install -g playwright && npx playwright install chromium
```

- [ ] **Step 5: Run the python smoke test again (regression)**

Run: `python3 -m pytest skills/annotate/tests/test_smoke_block_search.py -v`
Expected: PASS (search.js now real, CSS present).

- [ ] **Step 6: Commit**

```bash
git add skills/annotate/static/search.js skills/annotate/tests/e2e/search.e2e.cjs
git commit -m "feat(annotate): client-side fuzzy block search with highlight"
```

---

### Task 5: Manual verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full annotate test suite for regressions**

Run: `python3 -m pytest skills/annotate/tests -v`
Expected: PASS (no existing test broken by the header/script changes).

- [ ] **Step 2: Drive the real skill**

Push a multi-block response through the annotate skill, open the page, and confirm by eye:
- The search box sits in the header, left of Done, with a `/` hint.
- Pressing `/` focuses it; typing filters to matching blocks; the count line is correct.
- A typo (e.g. "porposal") still matches via fuzzy.
- Matched terms show a yellow highlight.
- `Esc` clears and the full page returns; the annotation buttons/comments still work.

- [ ] **Step 3: Commit (only if Step 2 surfaced a tweak)**

```bash
git add -A && git commit -m "fix(annotate): block-search polish from manual verification"
```

---

## Self-Review

**Spec coverage:**
- Client-side only, no server/model/pipeline changes → Tasks 2–4 touch only `server.py` markup + `static/` ✓
- Hide non-matching (behavior A) → Task 4 `.search-hidden` toggle + e2e assertion ✓
- Fuzzy via vendored Fuse.js, threshold 0.3, ignoreLocation, includeMatches → Tasks 1 & 4 ✓
- Count line "Showing X of Y blocks" → Task 4 `ensureCountEl` + e2e assertion ✓
- Best-effort highlight via text-node walk, SVG skipped → Task 4 `highlight()` ✓
- Index rebuilt on poll via MutationObserver → Task 4 `observer` ✓
- `/` focus, `Esc` clear → Task 4 keydown + e2e assertions ✓
- Search box in `.header-actions`, dashboard styling → Tasks 2 & 3 ✓
- `inline-comments` hidden with their block → Task 4 sibling toggle ✓

**Placeholder scan:** Task 2 Step 5 intentionally creates a *stub* `search.js` and *placeholder* CSS so the task is independently committable; both are fully replaced in Tasks 3–4. No "TBD"/"implement later" left in shipped code.

**Type/name consistency:** `block-search` (id), `.search-hidden`, `.search-count`, `mark.search-hit`, `.header-search`, `.search-input`, `.search-kbd`, `.search-icon` are used identically across server.py markup, CSS, search.js, the smoke test, and the e2e. `buildIndex`/`applyFilter`/`ensureCountEl`/`highlight` are defined and called consistently within search.js.
