# /walkthrough — guided code tours in IntelliJ

**Date:** 2026-07-22
**Status:** approved design, not yet implemented

## Problem

Asking Claude "how is X implemented?" or "how would I add Y?" produces terminal
paragraphs. The answer names files and line numbers the reader must then chase by
hand, losing the thread between the prose and the code. For a question like
*"how to add a precondition in workflow v2 so a proposal is not shareable when
mandatory documents are missing?"* the useful answer is a **path through the
codebase**, walked in the IDE where the code already is.

## Solution

A new skill, `/walkthrough <question>`, that produces an ordered sequence of
steps — each anchored to a real `file:line` with an explanation — and a new
extension in the existing IntelliJ plugin that walks the user through them
step by step. The user can ask a question on any step; Claude answers into that
step in place.

The terminal is not the surface. It says one sentence and goes quiet.

## Scope

**v1 includes**

- `explain` tours over existing code (both "how does X work" and "how would I add X").
- `diff` tours over an already-made change (uncommitted working copy, or `<ref>..HEAD`).
- Live sessions: Claude stays armed; asking on a step wakes it.
- Two IDE renderers, mutually exclusive, toggleable at runtime.

**v1 excludes (non-goals)**

- Claude editing code as part of the walkthrough.
- Tour persistence or resume — tours are ephemeral, they die with the terminal session.
- Multiple concurrent tours per project.
- Any web-page surface. IDE only.
- Branching or nonlinear tours.

## Decisions

| Decision | Choice | Why |
| --- | --- | --- |
| Invocation | `/walkthrough <question>` | Reads naturally; leaves `/tour` free. |
| Tour content | Change-oriented when asked that way | "How to add X" ends at concrete edit sites; "how does X work" ends at the mechanism. Same generator. |
| Liveness | Live — watcher armed | Questions arrive while walking; a static tour cannot answer them. |
| Ask effect | Answer only, in place | Steps frozen at generation. No renumbering, no path shifting under the reader. |
| Lifetime | One active tour per project, ephemeral | Matches the plugin's one-session-per-`cwd` model; nothing to garbage-collect. |
| IDE surface | Both rail and inline, one active | Each has real benefits; they share everything but the renderer, so building both is cheap. |

## Architecture

### Server side — `skills/walkthrough/`

Mirrors `skills/interactive_review/`:

- `ensure_server.sh` — delegates to `skills/_shared/web_companion/ensure_server.sh`
  with skill name `walkthrough`. Own port range, own `~/.claude/walkthrough/server.json`.
- `server.py` — implements `HandlersProtocol` (`serve_root`, `serve_data`,
  `handle_submit`, `serve_poll`, `create_session_extra`, `comment_count`).
  `create_session_extra` does no expensive work: unlike interactive-review there is
  no `gh pr diff` to fetch. It seeds directories and returns.
- `steps.py` — new module, sibling of `threads.py`. Atomic write/read of
  `steps.json` (via `_shared/web_companion/atomic.py`), step lookup, anchor
  encoding, schema validation.
- `threads.py` — reused from interactive-review by import, not copied. One thread
  per step anchor, `source_event_id` dedup unchanged.

### Flow

1. `/walkthrough <question>` → `ensure_server.sh` → `POST /api/sessions {cwd, title}`
   → `sid`, `state_dir`, `events_dir`, `consumed_dir`.
2. Claude explores the repo (`Grep`, `Glob`, `Read`; plus `git diff` for diff tours),
   drafts steps, runs the cross-block re-pass, then `Write`s `state_dir/steps.json`.
3. The plugin — already polling by `cwd` — sees a walkthrough session, renders step 1
   and navigates the editor to its anchor.
4. Claude arms `skills/_shared/web_companion/watcher.sh` via `Monitor`
   (`persistent: true`) and ends its turn.
5. User walks the steps. Asking on a step posts
   `{anchor: "step:3", type: "comment", text}` to `/s/<sid>/api/submit`. The watcher
   emits `WEBCOMPANION_EVENT`; Claude appends to that step's thread via
   `append_message`, writes the `.ack`, ends the turn silently. The plugin refreshes
   step 3 only.
6. Done or cancel → `WEBCOMPANION_FINISHED` / `WEBCOMPANION_CANCELLED` → the state
   directory is dropped.

### Data model — `state_dir/steps.json`

```json
{
  "question": "how to add a precondition in workflow v2 so a proposal is not shareable when mandatory documents are missing",
  "kind": "explain",
  "generated_ts": 1784720471,
  "steps": [
    {
      "id": 3,
      "title": "How evaluate() fans out",
      "file": "src/main/java/com/montblanc/workflow/PreconditionRegistry.java",
      "line": 30,
      "snippet": "var failures = preconditions.evaluate(p, Phase.SHARE);",
      "role": "seam",
      "markdown": "Every bean implementing `Precondition` with `phase() == SHARE` runs here ..."
    }
  ]
}
```

- `snippet` is load-bearing. `line` is a hint; the plugin re-resolves the snippet
  with `AnchorResolver` when the file has shifted.
- `role` is one of `context` (grey badge), `seam` (blue), `edit-site` (green).
- Thread anchors are `step:<id>`. Step ids never change after generation.

### Plugin side — `intellij-plugin-spike`

New classes, none modifying the review path:

- `WalkthroughSessionClient` — poll + SSE against `/s/<sid>/…`, same shape as
  `ReviewSessionClient` (session discovery by `cwd`, liveness poll, pending tracking).
- `WalkthroughService` — project service holding the controller, disposed with the project.
- `WalkthroughController` — step list, current index, mode. Owns next/prev/jump,
  editor navigation (`OpenFileDescriptor` to the re-resolved anchor plus line
  highlight), and notifies whichever renderer is subscribed.
- `WalkthroughPanel` — mode A renderer: tool window "Walkthrough", right anchor.
- `WalkthroughInlay` + `WalkthroughHud` — mode B renderer: `EditorCustomElementRenderer`
  inlay under the active anchor, floating position/keys bar.
- `WalkthroughStatusBarWidget` — mode toggle.

Reused as-is: `AnchorResolver`, `GutterAnchorIndex`, `MarkdownLinkRenderer`,
`SynthesisLinkRouter`, `IdeKeymapCatalog`.

All interactive UI is Swing. JCEF is display-only in this plugin — the
`window.cefQuery` / `JBCefJSQuery` JS→Java bridge does not work under
out-of-process JCEF in IU-261.

### The two renderers

Both draw from the same controller; exactly one is subscribed at a time. Gutter
badges (colored by `role`) render in both modes.

**Mode A — rail.** Tool window on the right. Whole step list visible; the active
step is expanded with its markdown, its Q&A thread, an ask box, and Back/Next.
Pending asks show a `waiting for Claude` indicator driven by the existing
`onPendingChanged` mechanic.

**Mode B — inline.** No panel. A single editor inlay under the active anchor holds
the same markdown and thread. A floating HUD shows `3 / 7 · title` and the key
bindings. Ask opens a Swing popup at the caret.

**Toggle.** Status-bar widget (`Walkthrough: rail ▾` / `inline ▾`) plus a keyboard
shortcut. Mode is persisted per project in `PropertiesComponent`. Switching disposes
the inactive renderer's UI; controller state is untouched, so the current step is
preserved across the switch.

**Shortcuts.** Four actions: next step, previous step, ask, toggle mode. Bindings are
chosen only after dumping the live keymap through `IdeKeymapCatalog` — the user runs a
custom "Petros Makris (Mac)" keymap, and `$default` `control` shortcuts are rewritten
to Cmd on macOS. Nothing is bound blind.

## Generation contract

These are hard rules in `SKILL.md`, not suggestions.

- **5–12 steps.** Fewer than 5 means the question deserved a paragraph. More than 12
  means the question is too broad.
- **Every step is a real anchor.** `file` + `line` + a `snippet` copied verbatim from
  the file. No step may anchor to a file Claude did not `Read`. No invented line numbers.
- **Execution order, not file order.** Steps follow how control and data actually flow:
  entry point → gate → dispatch → implementation → data model → seam. Grouping by
  package is a failure mode.
- **Each step earns its place.** The markdown answers *what happens here* and *why it
  matters for the question asked* — 2–5 sentences. Not a file summary.
- **The last step answers the question.** For "how to add X", the final steps carry
  `role: "edit-site"` and name the exact file or directory for the new code, the
  registration point, and the test that would prove it. Concretely named, never
  "somewhere in the workflow package".
- **Inline links.** Same convention as interactive-review:
  `[evaluate](src/main/java/.../PreconditionRegistry.java:30)`, rendered clickable by
  `MarkdownLinkRenderer`.
- **Titles ≤ 6 words**, noun phrases — they appear as rail rows and HUD text.
- **Cross-block re-pass.** After drafting every step, Claude re-reads them together and
  fixes what only appears in aggregate: a step repeating its neighbor, a jump missing a
  bridge, a title that no longer matches its body. This runs before `steps.json` is
  written.

## Answering an ask

Same posture as interactive-review: synthesis, not chat.

- The reply absorbs every question asked on that step so far and stands on its own.
- 2–4 sentences, code-aware, links inline, may cite other steps by number.
- Other steps and their threads are read-only background context; Claude writes only
  to the asked step's thread.
- No terminal output. The watcher stays armed.

## Terminal output

One sentence on session creation:

> Walkthrough ready — 7 steps for "how to add a precondition…". Open montblanc in
> IntelliJ; step forward with the walkthrough shortcut, ask on any step.

Then silence until the session ends.

## Edge cases

| Case | Behavior |
| --- | --- |
| Anchor rots mid-tour (user edits while walking) | `AnchorResolver` re-resolves the snippet within ±25 lines: `EXACT` / `MOVED` (follow silently) / `STALE` (step marked "code changed here", still navigable to the recorded line). |
| File deleted or renamed mid-tour | Step renders stale, badge greys, navigation is a no-op with a tooltip. The rest of the tour keeps working. |
| Wrong project open in the IDE | The plugin matches sessions by `cwd`. No session for this project means no tool window content and no HUD. |
| Question too broad | The generator would exceed 12 steps. Claude asks in terminal for a narrower question; after one retry it builds the best 12-step spine anyway. |
| Zero anchors found | No `steps.json` is written, the session is cancelled, and the terminal reports what was searched. An empty tour never reaches the IDE. |
| `--diff` with an empty diff | Reported in terminal; no session is created. |
| Server down or restarted | `ensure_server.sh` restarts it; the plugin's poll recovers. Tours are ephemeral, so a lost server means a lost tour — said plainly. |
| Two Claude sessions on one project | The second `/walkthrough` replaces the active tour. The plugin switches; the old watcher is cancelled. |
| Terminal cancellation ("scrap it") | Cancellation marker written to `state_dir`; the watcher emits `WEBCOMPANION_CANCELLED`; the IDE surface clears. |
| Malformed event payload | No-op, but the `.ack` is still written so the event is not re-emitted forever. |

## Testing

Python — `skills/walkthrough/tests/`:

- `steps.py` round-trip and atomic write.
- Schema validation: a step without a snippet, or with a negative line, is rejected.
- Handler tests against a fake session directory: `serve_data` shape, `serve_poll`
  liveness, `handle_submit` writing an event.
- Submit → event → `append_message` → thread contents; ack dedup on replay.

Java — `intellij-plugin-spike/src/test/`:

- `WalkthroughSessionClientTest` against `FakeReviewServer` (extended, not forked).
- `WalkthroughControllerTest`: next/prev/jump, bounds at first and last step, mode
  switch preserves the current index.
- Anchor re-resolution against a shifted document.
- Markdown rendering of a step plus its thread.

No UI-driving tests — same posture as the existing plugin suite.

## Relationship to interactive-review

They share plumbing and must not merge. `interactive-review` is *ad-hoc*: the user
picks the line, Claude answers there. `/walkthrough` is *ordered*: Claude picks the
path, the user walks it. Shared: the web-companion server, session model, thread
storage, anchor resolution, and the annotation panel idioms. Separate: skill,
handlers, plugin service, renderers.
