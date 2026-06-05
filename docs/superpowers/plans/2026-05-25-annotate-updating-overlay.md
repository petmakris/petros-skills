# Annotate updating-overlay implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the static "updating…" label on annotate blocks with an animated centered pill (spinner + elapsed timer) over a warm-tinted veil, and enforce the "disabled" half of the contract by suppressing hover actions while a block is mid-rewrite.

**Architecture:** Frontend-only change inside `skills/annotate/static/`. CSS gets a new block of `.is-updating` rules (veil + dim + hover-action suppression + pill chrome). JS gets two touch-ups: the submit-success branch mounts an `.updating-overlay` node and starts a per-block timer interval; the poll-replace branch (which already removes `.is-updating`) gains symmetric cleanup of the interval and the overlay node.

**Tech Stack:** Vanilla JS, CSS (no framework). `setInterval` for the 1-second timer tick.

**Spec:** `docs/superpowers/specs/2026-05-25-annotate-updating-overlay-design.md`

---

## File Structure

- **Modify** `skills/annotate/static/style.css` — replace the `.is-updating` opacity rule (current lines 231-237) with the full overlay treatment (veil, dim, pill chrome, hover-action suppression).
- **Modify** `skills/annotate/static/script.js`:
  - Submit-success branch (currently `~lines 478-489`) — replace the `updating-indicator` text-div with overlay-pill construction + timer start.
  - Poll-replace branch (currently `~lines 686-688`) — clear the timer interval and remove the overlay alongside removing `is-updating`.
- **No changes** to `server.py`, `blocks.py`, `__init__.py`, or any Python tests. The existing E2E (`tests/test_smoke_e2e_diagram.py`) does not assert on `.updating-indicator`, so it remains untouched.

There is no browser-side unit test harness in this codebase; this is a vanilla-JS DOM module driven manually. The plan therefore uses manual verification rather than fake TDD steps. Each task is self-contained and committed independently.

---

## Task 1: CSS — replace `.is-updating` with overlay treatment

**Files:**
- Modify: `skills/annotate/static/style.css` (replace lines 231-237; the existing `Block updating indicator` section)

The current rule is just:

```css
/* === Block updating indicator ======================================= */
section.block {
  position: relative;
}
section.block.is-updating {
  opacity: 0.7;
}
```

We need to keep `section.block { position: relative; }` (used elsewhere, including the version-badge positioning at line 240), and replace the `.is-updating` opacity rule with the full treatment.

- [ ] **Step 1: Replace the `.is-updating` rule block**

Find these lines in `skills/annotate/static/style.css`:

```css
/* === Block updating indicator ======================================= */
section.block {
  position: relative;
}
section.block.is-updating {
  opacity: 0.7;
}
```

Replace with:

```css
/* === Block updating overlay ========================================= */
section.block {
  position: relative;
}

/* Dim + disable the underlying content while a rewrite is in flight. */
section.block.is-updating > *:not(.updating-overlay) {
  opacity: 0.42;
  filter: saturate(0.7);
  pointer-events: none;
  user-select: none;
  transition: opacity 180ms ease;
}

/* Suppress the floating hover-actions (✗ / 💬) for the block being rewritten. */
section.block.is-updating .hover-actions {
  display: none !important;
}

/* Warm tinted veil covering the block content, sits below the pill. */
.updating-overlay {
  position: absolute;
  inset: 0;
  background: rgba(253, 243, 234, 0.55);
  border-radius: inherit;
  pointer-events: none;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* Centered pill: spinner + label + elapsed timer. */
.updating-overlay .updating-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 12px;
  font-weight: 500;
  color: #8a4d1e;
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid color-mix(in srgb, var(--accent) 40%, transparent);
  border-radius: 999px;
  box-shadow:
    0 2px 10px rgba(0, 0, 0, 0.06),
    0 0 0 4px color-mix(in srgb, var(--accent) 8%, transparent);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
  z-index: 2;
}

.updating-overlay .updating-spinner {
  width: 12px;
  height: 12px;
  border: 1.5px solid color-mix(in srgb, var(--accent) 25%, transparent);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: updating-spin 0.85s linear infinite;
}

.updating-overlay .updating-timer {
  color: color-mix(in srgb, var(--accent) 85%, #000);
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

@keyframes updating-spin {
  to { transform: rotate(360deg); }
}
```

Notes:
- The dim rule targets `> *:not(.updating-overlay)` so the overlay itself isn't dimmed.
- `color-mix(in srgb, var(--accent) X%, ...)` references the existing `--accent` token used throughout `style.css` (e.g. line 254). This keeps the warm tint synced with whatever the accent palette is.
- `display: none !important` on `.hover-actions` overrides the existing `[data-block-id]:hover .hover-actions { opacity: 1; pointer-events: auto; }` rule at line 151.

- [ ] **Step 2: Commit**

```bash
git add skills/annotate/static/style.css
git commit -m "annotate: replace updating-indicator opacity with veil + centered pill styles"
```

---

## Task 2: JS — mount overlay + start timer on submit

**Files:**
- Modify: `skills/annotate/static/script.js` (replace the updating-indicator construction inside the submit-success branch; currently `~lines 478-489`)

The current code in the submit handler reads:

```js
        if (a.block_id) {
          const section = document.querySelector(`section.block[data-block-id="${a.block_id}"]`);
          if (section) {
            section.classList.add("is-updating");
            if (!section.querySelector(".updating-indicator")) {
              const ind = document.createElement("div");
              ind.className = "updating-indicator";
              ind.textContent = "updating…";
              section.appendChild(ind);
            }
          }
        }
```

This needs to (a) build a structured overlay (`.updating-overlay > .updating-pill > spinner + label + timer`), and (b) start a `setInterval` that updates the timer text every second. The interval id is stored on the section element so the poll-replace branch can clear exactly the right one.

- [ ] **Step 1: Replace the updating-indicator block with overlay mounting**

In `skills/annotate/static/script.js`, find the block above (inside the `WebCompanion.api.submit(payload).then(() => { ... })` callback) and replace it with:

```js
        if (a.block_id) {
          const section = document.querySelector(`section.block[data-block-id="${a.block_id}"]`);
          if (section) {
            section.classList.add("is-updating");
            if (!section.querySelector(".updating-overlay")) {
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
          }
        }
```

Notes:
- The interval id is stashed on the section as `section._updatingTimerId` so the cleanup branch can clear it without searching the DOM.
- `pointer-events: none` on the overlay is handled in CSS, no inline JS needed.
- The existing `.updating-indicator` selector is no longer produced anywhere. (No other code searches for it — verified via the grep referenced in the spec.)

- [ ] **Step 2: Sanity-check that no other JS references the old class**

Run:

```bash
grep -rn "updating-indicator" skills/annotate/
```

Expected: zero matches in `static/`, `tests/`, `server.py`, `blocks.py`, etc. If a match remains, remove it as part of this task (it's left-over from the rule we just replaced).

- [ ] **Step 3: Commit**

```bash
git add skills/annotate/static/script.js
git commit -m "annotate: mount overlay + start elapsed timer on comment submit"
```

---

## Task 3: JS — clear overlay + timer when the rewrite arrives

**Files:**
- Modify: `skills/annotate/static/script.js` (extend the poll-replace cleanup currently at `~lines 686-688`)

The current cleanup reads:

```js
      section.dataset.version = String(blk.version ?? newVersion);
      renderVersionBadge(section, blk.version ?? newVersion);
      // Remove updating indicator if present.
      section.classList.remove("is-updating");
      section.querySelector(".updating-indicator")?.remove();
      // Re-wire hover actions in case the content was replaced.
      renderHoverActions();
```

We replace the `.updating-indicator` removal with overlay + interval cleanup.

- [ ] **Step 1: Replace the cleanup lines**

Find the snippet above in `skills/annotate/static/script.js` and replace the two cleanup lines (the comment + classList.remove + the querySelector.remove) with:

```js
      // Remove updating overlay if present.
      section.classList.remove("is-updating");
      if (section._updatingTimerId) {
        clearInterval(section._updatingTimerId);
        section._updatingTimerId = null;
      }
      section.querySelector(".updating-overlay")?.remove();
```

The final block should read:

```js
      section.dataset.version = String(blk.version ?? newVersion);
      renderVersionBadge(section, blk.version ?? newVersion);
      // Remove updating overlay if present.
      section.classList.remove("is-updating");
      if (section._updatingTimerId) {
        clearInterval(section._updatingTimerId);
        section._updatingTimerId = null;
      }
      section.querySelector(".updating-overlay")?.remove();
      // Re-wire hover actions in case the content was replaced.
      renderHoverActions();
```

- [ ] **Step 2: Commit**

```bash
git add skills/annotate/static/script.js
git commit -m "annotate: clear updating overlay + timer when block rewrite lands"
```

---

## Task 4: Manual verification

**Files:** none modified — verification only.

The annotate skill ships a smoke E2E that hits the server but does not assert on DOM details of the updating treatment, so it requires no change. UI behaviour must be verified by hand.

- [ ] **Step 1: Start the annotate server with a fixture response**

Run the existing dev workflow for the annotate page (the `ensure_server.sh` script in `skills/annotate/`, or whatever the user normally uses). Open a session that has at least one prose paragraph block and one sequence-diagram block.

- [ ] **Step 2: Verify the "enter updating" path**

1. Click into a paragraph block and submit a comment.
2. Confirm:
   - The veil + centered pill appear immediately on that block.
   - The spinner spins continuously.
   - The timer ticks `0:01`, `0:02`, … (mm:ss, monospace).
   - Hover-actions (✗, 💬) do **not** appear when hovering the block being updated.
   - Hover-actions still work on other, unaffected blocks.
   - Text under the veil reads as visibly dimmed (~42% opacity) and cannot be selected.

- [ ] **Step 3: Verify the "exit updating" path**

1. Simulate a block rewrite (either by waiting for a real Claude update, or by manually updating `blocks.json` and waiting for the poll loop, the same path the E2E uses).
2. Confirm:
   - At the moment the new content swaps in, the veil + pill disappear.
   - Hover-actions reappear on the now-fresh block.
   - The version badge ticks up to the new version (existing behaviour, should be undisturbed).

- [ ] **Step 4: Verify concurrent updates**

1. Submit a comment on block A.
2. Without waiting for A to finish, submit a comment on block B.
3. Confirm:
   - Both blocks show independent overlays and independently-ticking timers.
   - Resolving A's rewrite clears only A's overlay; B keeps animating.
   - Resolving B's rewrite then clears B's overlay.

- [ ] **Step 5: Verify sequence-diagram blocks are also locked**

1. Submit a step-scoped comment on a sequence-diagram block (the existing `step_id`-aware path exercised by `test_smoke_e2e_diagram.py`).
2. Confirm:
   - Overlay covers the SVG block with the same veil + pill.
   - Clicks on SVG steps during the updating state do not open a new comment card (the inner SVG inherits `pointer-events: none` via the dim rule).

- [ ] **Step 6: Commit verification notes (if you want a paper trail)**

Optional — if you keep a verification log file, append a dated entry. Otherwise nothing to commit at this step.

---

## Out of scope (explicit)

These appeared during brainstorming and were deferred:

- Determinate progress fraction (we don't have a meaningful one).
- A "stuck-too-long" / timeout / error escalation. Pill keeps ticking forever today.
- Persisting the elapsed timer across page reload.
- Sticky positioning of the pill on very tall blocks.

Add these only if real-world use produces a complaint.
