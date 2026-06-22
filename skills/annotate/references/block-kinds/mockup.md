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
