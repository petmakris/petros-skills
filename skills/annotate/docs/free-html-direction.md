# Free-HTML direction — tradeoffs and minimal mechanism

Discussion document, not a final plan. We're considering reversing the typed-kinds plan (`richer-block-kinds-plan.md`) in favor of letting Claude emit free-form HTML. The motivation: typed kinds bake our 2026 imagination of "what visuals matter" into the tool, and that constraint gets more expensive as Claude's HTML/CSS authoring improves.

## 1. Tradeoffs

| Concern | Typed kinds (`comparison`, `table`, `callout`, ...) | Free HTML |
|---|---|---|
| **Expressive ceiling** | Capped at the kinds we've designed. Each new visual = validator + renderer + CSS + tests + docs. | Anything Claude can write today, plus everything Claude learns to write. Zero per-visual infrastructure. |
| **Rewrite round-trip** | Trivial — `update_spec_block` with canonical-JSON dedup. | Trivial — `update_block` on the existing `markdown` field, content-hash dedup. Claude is the only writer, so source = render. |
| **Click target / annotation surface** | Unambiguous — every sub-unit has a spec id (`c1`, `r3`, `s2`). Client knows what's clickable from the schema. | Needs a convention. Proposal: `data-annotate-id="..."` on any commentable element. No attribute → not commentable. Sub-unit ids = whatever Claude wrote, preserved across rewrites for surviving elements. |
| **Visual consistency** | Server-side renderer applies one stylesheet — Claude can't pick clashing colors. | Claude can mismatch the design. Mitigation: a tight `.annotate-html { ... }` reset/defaults block in `style.css`, scoped to free-HTML blocks. Claude is told to lean on existing CSS variables (`--accent`, `--surface`, etc.) rather than invent palettes. |
| **Security / XSS** | Server controls every HTML byte. Zero injection surface. | Threat model is "trust Claude, but defend against accidents." Sanitize on render — strip `<script>`, `on*` attributes, `javascript:` URLs, untrusted `<iframe src>`. Allow `data-*`, `class`, `id`, safe `style`. Use a vetted sanitizer (DOMPurify client-side, or bleach server-side) — don't roll our own. |
| **Glossary extraction** | Per-kind text extractor — clean but per-kind. | Client extracts `textContent` from rendered HTML. One extractor, no per-kind branching. |
| **Debug UX when output is bad** | Validator rejects malformed specs at write time, with a clear error message. Block never renders broken. | Broken HTML renders broken. Mitigation: render-error fallback that catches DOM parse errors and shows "block render failed — see source" with a copy button. |
| **Cost to ship today** | 3 new kinds × (spec + validator + renderer + tests + CSS + SKILL.md) ≈ a multi-day effort. | One-line markdown-it config flip + sanitizer + ~30 lines of convention docs ≈ a half-day effort. |

**The actual trade:** typed kinds buy predictability and a clean annotation contract at the cost of forever lagging behind Claude's capabilities. Free HTML buys flexibility and forward-compatibility at the cost of needing a safety net (sanitization, error fallback) and a light contract (`data-annotate-id`).

**My read:** the user's argument carries weight. Locking the expressive surface to typed kinds is a constraint that gets more expensive over time, while the safety-net work is a one-time investment. Recommend going free-HTML.

## 2. Minimal mechanism

Smallest change set that gets us free HTML without a new kind:

1. **Flip `html: true` on the markdown-it instance** in `static/script.js:202`. Markdown blocks now allow inline HTML.
2. **Sanitize on render.** Add a client-side sanitization pass before `content.innerHTML = ...`. Use DOMPurify (single vendored JS file, no build step needed). Allow:
   - All standard layout/text tags (`div`, `span`, `table`, `aside`, `figure`, `details`, etc.)
   - `class`, `id`, `style` (with `style` filtered for `expression(...)` and `url(javascript:...)`)
   - All `data-*` attributes
   - Disallow: `<script>`, `<iframe>` (unless we whitelist a few), `on*` event handlers, `javascript:` / `data:` URLs in `href`/`src`
3. **Add `data-annotate-id` to the click handler.** In `static/script.js` (the existing hover-action wiring), when computing `step_id` for a click inside a block, check for `ev.target.closest("[data-annotate-id]")` first. If found, `step_id = element.dataset.annotateId`. Otherwise `step_id = null` (whole-block).
4. **Document the convention in SKILL.md.** Concise: "Use real HTML when prose isn't enough. Add `data-annotate-id='<slug>'` to anything you want users to be able to click and comment on. Reuse existing CSS variables instead of inventing colors."

No new `kind`. No new spec shape. No per-visual renderer. The `kind: "sequence"` block stays as-is — it predates this and its SVG generator is too specific to fold into free HTML.

Two changes to `blocks.py` are not needed:
- `update_block` already handles the markdown field with content-hash dedup. HTML embedded in markdown rides along.
- `drop_unused_terms` already scans block markdown for glossary terms. HTML tags inside the markdown are tolerated (the term check is a regex on the whole string; tags don't accidentally match identifiers).

## 3. Annotation surface contract

```
Element with data-annotate-id="my-slug" → commentable sub-unit
  └─ user click → step_id = "my-slug"
  └─ rewrite event payload: {block_id, step_id: "my-slug", text, ...}
  └─ Claude rewrites the block, preserving data-annotate-id="my-slug"
     on the element that still represents the commented sub-unit
     (or omits it if that element no longer exists — same logic
     as today for diagram steps that get restructured)

Element without data-annotate-id → not clickable as a sub-unit;
  click bubbles to whole-block (step_id: null)
```

Slug rules: kebab-case, scoped within the block (uniqueness only required within one block — `b-3:row-1` and `b-7:row-1` is fine because the (block_id, step_id) pair is the key). Claude picks slugs that describe the element ("verdict-row", "auth-column", "rate-limit-cell") rather than positional indices.

This mirrors how sequence steps work today (`s1`, `s2`, ...), but lifts the constraint that the renderer mints ids.

## 4. What to do with existing work on this branch

| Artifact | Recommendation |
|---|---|
| Badge fix (commit `d07f8b3`) | **Keep.** Orthogonal to the kinds question. |
| Collapsible blocks + submitted-card (commit `956c633`) | **Keep.** Orthogonal. |
| `richer-block-kinds-plan.md` (commit `9c586fd`) | **Archive in place.** Rename to `richer-block-kinds-plan-archived.md`, add a one-paragraph header explaining why we reversed. Keeps the record of the decision. |
| `gallery.html` (commit `559d6ab`) | **Repurpose.** It still demonstrates good visuals; under the new model these are now "examples of HTML Claude could emit", not previews of typed kinds. Update the note text at the top. |
| `comparison` / `table` / `callout` as `kind`s | **Drop.** Under free HTML, Claude just writes the same HTML directly in a markdown block. |

## What I want from you

Push back on anything in §1–§3. Specifically:
- Is the sanitization allowlist right, or do you want a stricter / looser starting point?
- Is `data-annotate-id` the right attribute name, or do you prefer something shorter (`data-acid`?) / scoped (`data-annotate-step`?)?
- Are you fine with HTML inside `kind: "markdown"` blocks, or do you want a distinct `kind: "html"` for clarity?
- The §4 decisions — anything you want to flip?

After we converge, I rewrite the plan doc as the implementation spec and we ship.
