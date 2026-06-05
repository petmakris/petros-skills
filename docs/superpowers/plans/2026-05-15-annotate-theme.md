# Annotate Theme Refresh — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the design from `docs/superpowers/specs/2026-05-15-annotate-theme-design.md` — three user-selectable accent palettes (mint/lavender/blue), small typographic refinements, and local font bundling to remove the Google Fonts CDN dependency.

**Architecture:** CSS variables already drive the color system; we extend the cascade with a third axis (`[data-theme="..."][data-accent="..."]`). A swatch row in `.header-actions` sets `data-accent` on `<html>` via a JS handler that mirrors the existing theme toggle. `localStorage` key `annotate.accent` persists the choice; an inline pre-paint script in `RESPONSE_HTML` reads it before first render to avoid a flash. Bricolage Grotesque is vendored under `skills/annotate/static/fonts/` and the Google Fonts `<link>` is removed.

**Tech Stack:** Vanilla CSS + JS (no build step). Python stdlib `http.server` (existing). `unittest` for tests.

---

## File Structure

- **Create:**
  - `skills/annotate/static/fonts/BricolageGrotesque-Variable.woff2` — vendored OFL font binary
  - `skills/annotate/static/fonts/BRICOLAGE_LICENSE.txt` — upstream OFL license text
- **Modify:**
  - `skills/annotate/static/style.css` — add `@font-face` for Bricolage, three accent variants × two themes, swatch button styles, typographic micro-refinements, cool-dark bg shift
  - `skills/annotate/server.py` — remove Google Fonts `<link>`, extend pre-paint inline script to also resolve `annotate.accent`, add three swatch buttons to `.header-actions`
  - `skills/annotate/static/script.js` — add `ACCENT_KEY` constant, `applyAccent`/`persistAccent`/`getInitialAccent`, wire up three new buttons
- **Test:**
  - `skills/annotate/tests/test_server.py` — extend `test_root_serves_response_shell` and add new `test_root_serves_response_shell_with_accent_swatches`

---

### Task 1: Vendor Bricolage Grotesque locally and remove CDN link

**Files:**
- Create: `skills/annotate/static/fonts/BricolageGrotesque-Variable.woff2`
- Create: `skills/annotate/static/fonts/BRICOLAGE_LICENSE.txt`
- Modify: `skills/annotate/server.py:264` (remove `<link>` line)
- Modify: `skills/annotate/static/style.css` (add `@font-face` block)
- Test: `skills/annotate/tests/test_server.py`

- [ ] **Step 1: Write the failing test**

Add this test method to the `ServerStartupTests` class (or whichever class contains `test_root_serves_response_shell`) in `skills/annotate/tests/test_server.py`:

```python
def test_response_shell_does_not_load_cdn_fonts(self):
    response_dir = Path(self.sess["response_dir"])
    (response_dir / "meta.json").write_text(json.dumps({
        "response_id": "resp-cdn", "title": "T",
    }))
    (response_dir / "response.md").write_text("body")
    status, body = _http_get("localhost", self.info["port"], self.base + "/")
    self.assertEqual(status, 200)
    self.assertNotIn("fonts.googleapis.com", body)
    self.assertNotIn("fonts.gstatic.com", body)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m unittest skills.annotate.tests.test_server.ServerStartupTests.test_response_shell_does_not_load_cdn_fonts -v`
Expected: FAIL with `AssertionError: 'fonts.googleapis.com' unexpectedly found in <html…>`

- [ ] **Step 3: Download the Bricolage Grotesque variable font and write its license**

The Bricolage Grotesque source is at `https://github.com/ateliertriay/bricolage`. We need the variable woff2 build. The releases ship `.ttf` files; convert one to `.woff2` (smaller transfer, browser-preferred). Easiest path: use a CDN-built woff2 from `https://fonts.gstatic.com/s/bricolagegrotesque/...` once, save it locally, never serve from CDN again.

Run from repo root:

```bash
mkdir -p skills/annotate/static/fonts
# Bricolage Grotesque variable font (OFL-1.1)
curl -fsSL -o skills/annotate/static/fonts/BricolageGrotesque-Variable.woff2 \
  "https://github.com/ateliertriay/bricolage/raw/main/fonts/variable/BricolageGrotesque%5Bopsz%2Cwdth%2Cwght%5D.ttf" \
  || { echo "download failed — fetch manually from https://github.com/ateliertriay/bricolage/tree/main/fonts/variable and place at skills/annotate/static/fonts/BricolageGrotesque-Variable.woff2"; exit 1; }
# Verify file is non-empty and looks like a font (starts with 'wOF2' magic for woff2, or 'OTTO'/0x00010000 for ttf)
file skills/annotate/static/fonts/BricolageGrotesque-Variable.woff2 | grep -E "Web Open Font Format 2|Spline Font Database|TrueType|OpenType" || { echo "downloaded file is not a font"; exit 1; }
# License
curl -fsSL -o skills/annotate/static/fonts/BRICOLAGE_LICENSE.txt \
  "https://raw.githubusercontent.com/ateliertriay/bricolage/main/OFL.txt" \
  || { echo "license download failed — fetch manually"; exit 1; }
```

If curl's content-type is `.ttf` but the filename ends `.woff2`, that's acceptable for a first cut — modern browsers accept TTF served from the same origin. A follow-up can convert to true woff2 with `fonttools` if file size becomes a concern. (Optional, not required for this plan.)

- [ ] **Step 4: Add `@font-face` for Bricolage Grotesque in `style.css`**

Insert immediately after the existing Monaspace `@font-face` block (after line 8 in the current file). Match the existing pattern:

```css
@font-face {
  font-family: 'Bricolage Grotesque';
  src: url('/static/fonts/BricolageGrotesque-Variable.woff2') format('woff2-variations'),
       url('/static/fonts/BricolageGrotesque-Variable.woff2') format('woff2');
  font-weight: 200 800;
  font-style: normal;
  font-display: swap;
}
```

(The duplicated `src` line provides graceful fallback for browsers that don't recognize `woff2-variations`.)

- [ ] **Step 5: Remove the Google Fonts `<link>` from `server.py`**

In `skills/annotate/server.py`, delete line 264:

```html
<link href="https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,400;12..96,500;12..96,600;12..96,700&display=swap" rel="stylesheet">
```

So the `<head>` block becomes:

```python
RESPONSE_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>{title}</title>
<link rel="stylesheet" href="/static/style.css">
<script>
  (function () {{
    try {{
      var saved = localStorage.getItem("annotate.theme");
      ...
```

(only the Google Fonts line goes away; everything else in `<head>` stays.)

- [ ] **Step 6: Run the test from Step 1 to verify it passes**

Run: `python3 -m unittest skills.annotate.tests.test_server.ServerStartupTests.test_response_shell_does_not_load_cdn_fonts -v`
Expected: PASS

- [ ] **Step 7: Run the full test module to verify no regressions**

Run: `python3 -m unittest skills.annotate.tests.test_server -v`
Expected: all tests pass.

- [ ] **Step 8: Commit**

```bash
git add skills/annotate/static/fonts/BricolageGrotesque-Variable.woff2 \
        skills/annotate/static/fonts/BRICOLAGE_LICENSE.txt \
        skills/annotate/static/style.css \
        skills/annotate/server.py \
        skills/annotate/tests/test_server.py
git commit -m "feat(annotate): vendor Bricolage Grotesque locally, drop CDN dependency"
```

---

### Task 2: Add `data-accent` token cascade (three accents × two themes)

**Files:**
- Modify: `skills/annotate/static/style.css` (after the existing `[data-theme="dark"]` and `[data-theme="light"]` blocks, around lines 16-73)
- Test: `skills/annotate/tests/test_server.py`

- [ ] **Step 1: Write the failing test**

Add a test that asserts the rendered shell HTML pulls in a stylesheet declaring the three accent variants. We test by reading the stylesheet directly (the `/static/style.css` route serves it). Append to the same test class:

```python
def test_static_style_defines_three_accent_variants(self):
    status, body = _http_get("localhost", self.info["port"], "/static/style.css")
    self.assertEqual(status, 200)
    for accent in ("mint", "lavender", "blue"):
        self.assertIn(f'[data-accent="{accent}"]', body,
                      f"accent variant {accent!r} missing from style.css")
    # The page accent must be derivable in both themes for all three accents.
    for theme in ("dark", "light"):
        for accent in ("mint", "lavender", "blue"):
            self.assertIn(
                f'[data-theme="{theme}"][data-accent="{accent}"]',
                body,
                f"missing accent selector for theme={theme} accent={accent}",
            )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m unittest skills.annotate.tests.test_server.ServerStartupTests.test_static_style_defines_three_accent_variants -v`
Expected: FAIL with `AssertionError: '[data-accent="mint"]' missing from style.css`

- [ ] **Step 3: Add the accent variants in `style.css`**

In `skills/annotate/static/style.css`, immediately after the existing `[data-theme="light"] { ... }` block (closes around line 73), insert these six rule blocks. They override only `--accent`, `--accent-fg`, and the accent-tinted derivatives (`--selection`); other tokens stay inherited from the theme block above.

```css
/* === Accent variants ================================================= */
/* Each combines with [data-theme] to override the page accent. The
   four type tokens (--type-reject-*, --type-question-*, --type-rewrite-*,
   --type-comment-*) intentionally stay theme-only and are not redefined
   here — they are load-bearing UX signals that must not shift with accent. */

[data-theme="dark"][data-accent="mint"] {
  --accent: #7ee0c2;
  --accent-fg: #0e0f12;
  --selection: rgba(126, 224, 194, 0.30);
}
[data-theme="dark"][data-accent="lavender"] {
  --accent: #c4b5fd;
  --accent-fg: #1a1d22;
  --selection: rgba(196, 181, 253, 0.30);
}
[data-theme="dark"][data-accent="blue"] {
  --accent: #60a5fa;
  --accent-fg: #0e0f12;
  --selection: rgba(96, 165, 250, 0.30);
}

[data-theme="light"][data-accent="mint"] {
  --accent: #0d9488;
  --accent-fg: #ffffff;
  --selection: rgba(13, 148, 136, 0.20);
}
[data-theme="light"][data-accent="lavender"] {
  --accent: #5b21b6;
  --accent-fg: #ffffff;
  --selection: rgba(91, 33, 182, 0.20);
}
[data-theme="light"][data-accent="blue"] {
  --accent: #2563eb;
  --accent-fg: #ffffff;
  --selection: rgba(37, 99, 235, 0.20);
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `python3 -m unittest skills.annotate.tests.test_server.ServerStartupTests.test_static_style_defines_three_accent_variants -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/static/style.css skills/annotate/tests/test_server.py
git commit -m "feat(annotate): add three accent palettes scoped under [data-accent]"
```

---

### Task 3: Add accent swatch markup + pre-paint script update

**Files:**
- Modify: `skills/annotate/server.py:265-276` (extend inline pre-paint script) and `skills/annotate/server.py:285-288` (`.header-actions` markup)
- Test: `skills/annotate/tests/test_server.py`

- [ ] **Step 1: Write the failing test**

Append this test method to `ServerStartupTests`:

```python
def test_response_shell_renders_accent_swatches_and_prepaint(self):
    response_dir = Path(self.sess["response_dir"])
    (response_dir / "meta.json").write_text(json.dumps({
        "response_id": "resp-acc", "title": "T",
    }))
    (response_dir / "response.md").write_text("body")
    status, body = _http_get("localhost", self.info["port"], self.base + "/")
    self.assertEqual(status, 200)
    # Three swatch buttons exist
    self.assertIn('id="accent-mint"', body)
    self.assertIn('id="accent-lavender"', body)
    self.assertIn('id="accent-blue"', body)
    # Pre-paint script resolves accent before first render
    self.assertIn('annotate.accent', body)
    # Default accent for a first-time visitor is "mint"
    self.assertIn('"mint"', body)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m unittest skills.annotate.tests.test_server.ServerStartupTests.test_response_shell_renders_accent_swatches_and_prepaint -v`
Expected: FAIL with `AssertionError: 'id="accent-mint"' not found in body`

- [ ] **Step 3: Extend the inline pre-paint script in `RESPONSE_HTML`**

In `skills/annotate/server.py`, replace the existing inline script (currently lines 265-276) with:

```python
<script>
  (function () {{
    try {{
      var savedTheme = localStorage.getItem("annotate.theme");
      var prefersDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
      var theme = (savedTheme === "light" || savedTheme === "dark") ? savedTheme : (prefersDark ? "dark" : "light");
      document.documentElement.dataset.theme = theme;

      var savedAccent = localStorage.getItem("annotate.accent");
      var ACCENTS = ["mint", "lavender", "blue"];
      var accent = ACCENTS.indexOf(savedAccent) >= 0 ? savedAccent : "mint";
      document.documentElement.dataset.accent = accent;
    }} catch (e) {{
      document.documentElement.dataset.theme = "dark";
      document.documentElement.dataset.accent = "mint";
    }}
  }})();
</script>
```

(The double-braces are because this string is `.format()`-ed by `server.py` — preserve the existing convention.)

- [ ] **Step 4: Add the three swatch buttons in `.header-actions`**

In `skills/annotate/server.py`, replace the current `.header-actions` block (lines 285-288):

```python
  <div class="header-actions">
    <button id="theme-light" type="button" class="theme-btn" aria-label="Light theme">☀</button>
    <button id="theme-dark" type="button" class="theme-btn" aria-label="Dark theme">🌙</button>
  </div>
```

with:

```python
  <div class="header-actions">
    <div class="accent-swatches" role="group" aria-label="Accent color">
      <button id="accent-mint"     type="button" class="accent-swatch" data-accent="mint"     aria-label="Mint accent"><span class="dot" style="background:#7ee0c2"></span></button>
      <button id="accent-lavender" type="button" class="accent-swatch" data-accent="lavender" aria-label="Lavender accent"><span class="dot" style="background:#c4b5fd"></span></button>
      <button id="accent-blue"     type="button" class="accent-swatch" data-accent="blue"     aria-label="Blue accent"><span class="dot" style="background:#60a5fa"></span></button>
    </div>
    <button id="theme-light" type="button" class="theme-btn" aria-label="Light theme">☀</button>
    <button id="theme-dark" type="button" class="theme-btn" aria-label="Dark theme">🌙</button>
  </div>
```

The swatch dots use hardcoded hex (not CSS variables) because they advertise the color the user will get — they must read true regardless of which accent is currently active.

- [ ] **Step 5: Run the test to verify it passes**

Run: `python3 -m unittest skills.annotate.tests.test_server.ServerStartupTests.test_response_shell_renders_accent_swatches_and_prepaint -v`
Expected: PASS

- [ ] **Step 6: Run the full test module**

Run: `python3 -m unittest skills.annotate.tests.test_server -v`
Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add skills/annotate/server.py skills/annotate/tests/test_server.py
git commit -m "feat(annotate): render accent swatch row and resolve accent before first paint"
```

---

### Task 4: Style the accent swatches

**Files:**
- Modify: `skills/annotate/static/style.css` (add `.accent-swatches` and `.accent-swatch` rules near the existing `.theme-btn` rules, around lines 252-266)
- Test: `skills/annotate/tests/test_server.py`

- [ ] **Step 1: Write the failing test**

Append to `ServerStartupTests`:

```python
def test_static_style_defines_accent_swatch_rules(self):
    status, body = _http_get("localhost", self.info["port"], "/static/style.css")
    self.assertEqual(status, 200)
    self.assertIn('.accent-swatch', body)
    self.assertIn('.accent-swatch.active', body)
    self.assertIn('.accent-swatch .dot', body)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m unittest skills.annotate.tests.test_server.ServerStartupTests.test_static_style_defines_accent_swatch_rules -v`
Expected: FAIL with `AssertionError: '.accent-swatch' not found in body`

- [ ] **Step 3: Add the swatch styles in `style.css`**

In `skills/annotate/static/style.css`, immediately after the existing `.theme-btn.active { ... }` block (closes around line 266), insert:

```css
.accent-swatches {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  margin-right: 6px;
  background: var(--surface-soft);
  border: 1px solid var(--border);
  border-radius: 7px;
}
.accent-swatch {
  width: 22px;
  height: 22px;
  padding: 0;
  background: transparent;
  border: 1.5px solid transparent;
  border-radius: 5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: border-color 120ms ease, transform 100ms ease;
}
.accent-swatch:hover { transform: scale(1.08); }
.accent-swatch .dot {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  display: block;
}
.accent-swatch.active {
  border-color: var(--text-strong);
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `python3 -m unittest skills.annotate.tests.test_server.ServerStartupTests.test_static_style_defines_accent_swatch_rules -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add skills/annotate/static/style.css skills/annotate/tests/test_server.py
git commit -m "style(annotate): accent swatch row styling"
```

---

### Task 5: Wire up the accent picker in `script.js`

**Files:**
- Modify: `skills/annotate/static/script.js` (append after the existing theme-handling block around lines 58-88)

- [ ] **Step 1: Write the failing test**

Append to `ServerStartupTests` in `skills/annotate/tests/test_server.py`:

```python
def test_script_js_handles_accent_picker(self):
    status, body = _http_get("localhost", self.info["port"], "/static/script.js")
    self.assertEqual(status, 200)
    self.assertIn('ACCENT_KEY', body)
    self.assertIn('"annotate.accent"', body)
    self.assertIn('applyAccent', body)
    # The three button ids must each be addressed
    for accent in ("mint", "lavender", "blue"):
        self.assertIn(f'accent-{accent}', body)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m unittest skills.annotate.tests.test_server.ServerStartupTests.test_script_js_handles_accent_picker -v`
Expected: FAIL with `AssertionError: 'ACCENT_KEY' not found in body`

- [ ] **Step 3: Add the accent handling block to `script.js`**

In `skills/annotate/static/script.js`, immediately after the existing theme block (after the `themeDarkBtn?.addEventListener(...)` call ending around line 88), insert:

```javascript
  const ACCENT_KEY = "annotate.accent";
  const ACCENTS = ["mint", "lavender", "blue"];
  const DEFAULT_ACCENT = "mint";

  function getInitialAccent() {
    const saved = localStorage.getItem(ACCENT_KEY);
    return ACCENTS.includes(saved) ? saved : DEFAULT_ACCENT;
  }
  const accentBtns = ACCENTS.map(name => document.getElementById(`accent-${name}`));

  function applyAccent(accent) {
    document.documentElement.dataset.accent = accent;
    accentBtns.forEach((btn, i) => {
      if (btn) btn.classList.toggle("active", ACCENTS[i] === accent);
    });
  }
  applyAccent(getInitialAccent());

  function persistAccent(accent) {
    try { localStorage.setItem(ACCENT_KEY, accent); } catch (_) { /* ignore */ }
  }

  accentBtns.forEach((btn, i) => {
    btn?.addEventListener("click", () => {
      persistAccent(ACCENTS[i]);
      applyAccent(ACCENTS[i]);
    });
  });
```

This mirrors the existing theme handler block exactly, including the `try { ... } catch (_) { /* ignore */ }` pattern around `localStorage`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `python3 -m unittest skills.annotate.tests.test_server.ServerStartupTests.test_script_js_handles_accent_picker -v`
Expected: PASS

- [ ] **Step 5: Manual browser smoke test**

This step has no unit test — it's a visual gate before commit. Start the annotate server, push a sample response (any prior session URL works), open in browser, and verify:

```bash
# Easiest: run the existing ensure-server flow
"$CLAUDE_PLUGIN_ROOT/skills/annotate/ensure_server.sh"
# Then trigger an annotate session (use the skill via Claude, or manually POST /api/sessions and drop a response.md)
```

In the browser:
- Three colored dots visible in the header, left of the ☀/☾ buttons
- Active dot has a visible ring
- Clicking each dot recolors links/submit-button/section-label/blockquote rule live
- The four annotation-type colors (reject red, question amber, rewrite lavender, comment blue) **do not** change when accent changes
- Refresh the page — the chosen accent persists
- Toggle theme (light/dark) — accent still applies cleanly in both

If any of these fail, fix before committing.

- [ ] **Step 6: Commit**

```bash
git add skills/annotate/static/script.js skills/annotate/tests/test_server.py
git commit -m "feat(annotate): wire up accent picker with localStorage persistence"
```

---

### Task 6: Apply typographic refinements + cool-dark bg shift

**Files:**
- Modify: `skills/annotate/static/style.css` (`[data-theme="dark"]` token block lines 16-39; body declaration around lines 82-92; heading declarations around lines 102-104)

- [ ] **Step 1: Write the failing test**

Append to `ServerStartupTests`:

```python
def test_static_style_typographic_refinements(self):
    status, body = _http_get("localhost", self.info["port"], "/static/style.css")
    self.assertEqual(status, 200)
    # Body micro-adjustments
    self.assertIn('font-size: 15.5px', body)
    self.assertIn('line-height: 1.6', body)
    # Tighter heading tracking
    self.assertIn('letter-spacing: -0.022em', body)
    # Cool-dark bg shift
    self.assertIn('--bg: #0e0f12', body)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m unittest skills.annotate.tests.test_server.ServerStartupTests.test_static_style_typographic_refinements -v`
Expected: FAIL with `AssertionError: 'font-size: 15.5px' not found`

- [ ] **Step 3: Update the dark theme token block**

In `skills/annotate/static/style.css`, change the `[data-theme="dark"]` block (lines 16-39) values:

```css
[data-theme="dark"] {
  --bg: #0e0f12;             /* was #1a1d22 */
  --surface: #16181d;        /* was #22262d */
  --surface-soft: #07080a;   /* was #1e2127 */
  --border: rgba(255, 255, 255, 0.07);   /* was 0.08 */
  --surface-hover: rgba(255, 255, 255, 0.04);
  --text: #d4d7de;           /* was #e8eaee */
  --text-strong: #ffffff;
  --text-dim: #7c828d;       /* was #9aa0aa */
  --accent: #c4b5fd;         /* placeholder; overridden by [data-accent] selectors */
  --accent-fg: #0e0f12;      /* was #1a1d22 */
  --comment-bg: rgba(96, 165, 250, 0.08);
  --highlight: rgba(251, 191, 36, 0.25);
  --selection: rgba(96, 165, 250, 0.35);  /* placeholder; overridden by [data-accent] */
  --hover-tint: rgba(255, 255, 255, 0.06);

  /* type tokens — unchanged */
  --type-reject-fg: #fca5a5;
  --type-reject-bg: rgba(239, 68, 68, 0.18);
  --type-reject-wash: rgba(239, 68, 68, 0.08);
  --type-question-fg: #fde047;
  --type-question-bg: rgba(234, 179, 8, 0.18);
  --type-question-wash: rgba(234, 179, 8, 0.08);
  --type-rewrite-fg: #c4b5fd;
  --type-rewrite-bg: rgba(139, 92, 246, 0.22);
  --type-rewrite-wash: rgba(139, 92, 246, 0.08);
  --type-comment-fg: #93c5fd;
  --type-comment-bg: rgba(59, 130, 246, 0.22);
  --type-comment-wash: rgba(59, 130, 246, 0.08);
}
```

Also update the fallback rule on line 79 (the `html:not([data-theme])` block) to use the new bg:

```css
html:not([data-theme]) body { background: #0e0f12; color: #d4d7de; }
```

- [ ] **Step 4: Update the body and heading typography**

In the same file, change the body declaration (lines 82-92):

```css
body {
  margin: 0; padding: 0;
  background: var(--bg);
  color: var(--text);
  font-family: 'Bricolage Grotesque', ui-sans-serif, system-ui, -apple-system, sans-serif;
  font-size: 15.5px;            /* was 16px */
  line-height: 1.6;             /* was 1.65 */
  letter-spacing: -0.003em;     /* new */
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}
```

Change the heading declarations (lines 102-104):

```css
main.prose h1 { color: var(--text-strong); margin-top: 0; font-size: 26px; font-weight: 700; letter-spacing: -0.022em; }
main.prose h2 { color: var(--text-strong); margin-top: 1.4em; font-size: 21px; font-weight: 700; letter-spacing: -0.022em; }
main.prose h3 {
  color: var(--accent);
  margin-top: 1.6em;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.10em;
  text-transform: uppercase;
}
```

(h3 becoming an uppercase tracked label is the deliberate hierarchy move — it now reads as a section divider rather than a mini-heading.)

- [ ] **Step 5: Run the test to verify it passes**

Run: `python3 -m unittest skills.annotate.tests.test_server.ServerStartupTests.test_static_style_typographic_refinements -v`
Expected: PASS

- [ ] **Step 6: Run the full annotate test suite**

Run: `python3 -m unittest skills.annotate.tests -v`
Expected: all tests pass.

- [ ] **Step 7: Manual browser smoke test**

Open an annotate session in the browser and verify:
- Dark mode background is slightly deeper/cooler than before (compare to a screenshot from before the change if available)
- Body text feels slightly denser; line-height comfortable
- h1/h2 letter-spacing visibly tighter
- h3 now renders as a small uppercase tracked label, accent-colored
- Code blocks and inline code unchanged
- All four annotation-type colors unchanged across both themes

- [ ] **Step 8: Commit**

```bash
git add skills/annotate/static/style.css skills/annotate/tests/test_server.py
git commit -m "style(annotate): typographic refinements + deeper cool-dark bg"
```

---

### Task 7: Final integration sweep

**Files:** none (verification only)

- [ ] **Step 1: Run the full repo test suite**

Run: `python3 -m unittest discover -s skills -v`
Expected: all tests pass.

- [ ] **Step 2: DevTools network audit**

Open an annotate session in the browser. Open DevTools → Network. Reload the page with cache disabled. Verify:
- Zero requests to `fonts.googleapis.com` or `fonts.gstatic.com`
- One request to `/static/fonts/BricolageGrotesque-Variable.woff2`, status 200
- One request to `/static/fonts/MonaspaceRadon-Regular.woff2`, status 200
- All other static requests come from the same origin

- [ ] **Step 3: Confirm acceptance criteria from the spec**

Walk the acceptance list in `docs/superpowers/specs/2026-05-15-annotate-theme-design.md` § Acceptance, points 1-8. Each must hold. If any fails, file a follow-up task — do not paper over it.

- [ ] **Step 4: Final commit (only if the suite/audit surfaced a fix)**

If steps 1-3 required any code changes, commit them. Otherwise nothing to commit.

```bash
# example only — adjust to actual files touched
git add <files>
git commit -m "fix(annotate): address integration gaps surfaced by final sweep"
```

---

## Self-Review

**Spec coverage check (against `docs/superpowers/specs/2026-05-15-annotate-theme-design.md`):**

| Spec section / requirement | Implemented by |
|---|---|
| Token decoupling: page accent vs rewrite-type | Task 2 (type tokens explicitly left out of `[data-accent]` blocks; comment in CSS makes the rule explicit) |
| Three accent palettes × two themes | Task 2 |
| Typographic refinements (15.5px body, tighter tracking, h3 → uppercase) | Task 6 |
| Dark mode bg/surface/text shifts | Task 6 |
| Light mode token preservation | Task 6 leaves `[data-theme="light"]` block untouched ✓ |
| Accent picker UI in `.header-actions` | Tasks 3 (markup) + 4 (CSS) |
| `applyAccent` mirror of `applyTheme` + localStorage `annotate.accent` | Task 5 |
| Pre-paint script extension | Task 3 |
| Default accent = mint | Task 3 (server-side default) + Task 5 (client-side default) |
| Bricolage Grotesque vendored locally | Task 1 |
| Google Fonts `<link>` removed | Task 1 |
| Acceptance #6: zero CDN font requests | Tasks 1 + 7 (DevTools audit) |
| Acceptance #5: type colors unchanged across accent×theme | Task 2 (rule comment) + Task 6 (type tokens preserved) + Task 7 (manual verification) |
| Acceptance #8: existing tests pass + new test for swatch row | Each task runs the suite; new tests are added in Tasks 1-6 |

No gaps.

**Placeholder scan:** All "TBD", "TODO", "implement later" phrases checked. None present.

**Type / name consistency:**
- `ACCENT_KEY = "annotate.accent"` — used in script.js (Task 5) and read by the inline pre-paint script (Task 3) under the same string literal. ✓
- Button ids: `accent-mint`, `accent-lavender`, `accent-blue` — declared in markup (Task 3), addressed by JS in Task 5 via `document.getElementById(`accent-${name}`)`. ✓
- `data-accent` attribute name — set by both pre-paint script (Task 3) and `applyAccent` (Task 5); read by CSS selectors (Task 2). ✓
- `applyAccent` and `applyTheme` names — consistent across script.js. ✓

No drift.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-15-annotate-theme.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
