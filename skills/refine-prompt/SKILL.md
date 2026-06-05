---
name: refine-prompt
description: Use when the user invokes /refine-prompt, or when a messy user message (likely a speech-to-text transcript) arrives with session consent granted. Two modes — Console mode (explicit invocation) refines clipboard/file/inline text inside a subagent and hands back a bare, copy-pasteable prompt without polluting the session or executing anything; Auto-refine mode (in-chat messy message) refines in place and executes after approval. Both ground references to the codebase and correct likely transcription errors.
user-invocable: true
argument-hint: optional — bare invocation refines clipboard contents; pass a file path or inline text to refine that instead
---

# Refine Prompt Skill

Transforms low-quality transcripts (typically speech-to-text output) into high-quality agentic coding prompts. It grounds references to the real codebase and corrects likely mistranscriptions.

The skill has **two modes**, selected by how it was invoked:

| Mode | Trigger | What it does |
|---|---|---|
| **Console mode** | Explicit `/refine-prompt [arg]` | Refines clipboard / file / inline text **inside a subagent**, returns the **bare** refined prompt to your clipboard via `pbcopy`. **Does not execute.** You paste it yourself as a fresh message. Keeps the messy input and all probing noise out of the main session. |
| **Auto-refine mode** | A messy in-chat message + session consent | Refines the in-chat message **in place** and, after your `go`, runs it as the task. The original behavior. |

The dividing line: **explicit invocation = "hand me clean text to paste"; auto-trigger = "I rambled in chat, clean it up and run it."** They never mix in one turn.

---

# Console mode (explicit `/refine-prompt`)

This is the path for "I have a big messy dictation, give me a clean prompt to paste, without dumping the mess into this session."

## Why it stays clean

The messy input never enters the main session. The skill dispatches a **subagent** that reads the input itself (runs `pbpaste`, or `Read`s the file) and does the whole pipeline in its own context. The main session only ever holds the short `/refine-prompt` trigger and a one-line confirmation. The refined prompt is written back to the clipboard, so you paste it as your next message.

Requires macOS (`pbpaste` / `pbcopy`).

## Input resolution

| Invocation | Input source |
|---|---|
| `/refine-prompt` (no arg) | The **clipboard** — the subagent runs `pbpaste`. This is the default, lowest-friction path. |
| `/refine-prompt <path>` | The file at that path — the subagent `Read`s it. |
| `/refine-prompt <inline text>` | The inline text itself (note: inline text is already in the chat, so it does not get the no-pollution benefit; it's a convenience fallback). |

## Subagent dispatch

Dispatch one **general-purpose** subagent. Hand it the pipeline below and this contract. Do **not** read the clipboard or file from the main loop — that would pull the mess into the main context and defeat the entire purpose.

The subagent prompt must instruct it to:

1. **Acquire the input.**
   - Clipboard mode: run `pbpaste`. If it returns empty/whitespace only, stop and report `{ "empty": true }`.
   - File mode: `Read` the given path. If missing, report `{ "error": "<path> not found" }`.
   - Inline mode: use the text passed in the dispatch prompt.
2. **Run the Pipeline** (normalize → segment → probe → classify → compose) against the codebase. Probing budget and the word-ambiguity rules apply exactly as written below.
3. **Return one of two structured results:**
   - **Success:** the **bare** refined prompt (S1 prose or S2 structured brief, per the shape rule) with **no** `**Refined prompt:**` header, **no** Assumptions block, **no** `Reply go` footer — just the prompt text — AND run `printf '%s' "<refined prompt>" | pbcopy` to place it on the clipboard. Confirm the pbcopy succeeded.
   - **Blocking questions:** if the pipeline hit any blocking ambiguity (see Step 4 — almost always an unresolvable word/name/term), return the list of questions **and** the normalized input text, so the main loop can re-dispatch with answers without a second `pbpaste` (the clipboard may have changed by then).

## Phase A — blocking clarifications (lean, conditional)

If the subagent returns blocking questions, surface them to the user in a single batched message (numbered list), exactly as in the original Phase A. Wait for the answer, then **re-dispatch the subagent** passing the normalized input text it returned plus the user's answers, instructing it to skip re-reading the source and go straight to compose. Repeat only if something is still ambiguous.

Blocking questions are rare and reserved for word/name/term ambiguity a wrong guess would silently poison (see the Pipeline). Everything non-blocking is resolved silently — there is no Assumptions block to review in console mode.

## Output

On success, the refined prompt is already on the clipboard. Emit a single confirmation line and nothing else:

> ✓ Refined prompt copied to your clipboard — paste it as your next message.

Do **not** print the refined prompt in the main session (you reviewed nothing; the user reviews it in the input box before sending — that keeps the session cleanest). Do **not** execute it. The user's subsequent paste is a normal, clean message; the auto-refine heuristic will correctly ignore it.

If the subagent reported empty clipboard / missing file, relay that plainly and stop.

## Console-mode invariants

1. **The mess never enters the main loop.** The subagent acquires and processes the input; the main loop never runs `pbpaste` / `Read` on the source itself.
2. **No execution.** Console mode produces text and stops. There is no `go` gate and no task hand-off — the user runs the prompt by pasting it.
3. **Bare output only.** No header, no Assumptions, no footer. The clipboard holds exactly what the user would want as a prompt.
4. **Word guesses are still blocking.** Console mode drops the *visible* Assumptions block, not the safety it represented: an unresolvable word/name/term still becomes a Phase A question, because a silent wrong substitution would poison a prompt the user is about to paste and run.

---

# Auto-refine mode (in-chat messy message)

Unchanged original behavior, for when a rambling message arrives in the chat itself. This mode refines in place and executes after approval. It does **not** use a subagent and **does** show the full Phase B approval gate with Assumptions.

## When auto-refine triggers

| Trigger | Action |
|---|---|
| A user message matches the **Messiness heuristic** AND session consent is `always` | Silently run auto-refine on that message |
| Messiness heuristic match AND consent is `ask-each-time` | Ask "refine this first? (yes/no)" before running |
| Messiness heuristic match AND consent is `unset` | Emit the **First-messy-message prompt** (see Consent) |

Do **not** auto-run on: clean prose, short confirmations (`go`, `yes`, `ok`), or messages that are part of an in-progress refine turn (clarification answers, approval replies).

## Messiness heuristic

A message qualifies as "messy" when **at least two** of the following are true. Evaluate qualitatively — guidance for judgment, not a scorer.

1. **Long without punctuation.** Length > 200 characters and fewer than 2 sentence-ending marks.
2. **Filler words.** `um`, `uh`, `like` (filler), `kind of`, `sort of`, `you know`, `basically`, `so basically`, `I mean`.
3. **Word-level duplicates.** `the the`, `fix fix`, `we we should`, `and and`.
4. **Vague references without antecedent.** `that thing`, `the stuff we talked about`, `the one from before`, no clear referent.
5. **Transcription candidates.** Tokens that look like phonetic near-misses of plausible domain terms.
6. **Missing verb or direct object.** Imperative-shaped request lacking the action or the target.

A clean, well-formed request — even a long one — does **not** trigger the heuristic.

## Consent and auto-trigger

Consent is **session-scoped** — tracked in conversation context, not persisted to auto-memory. Resets to `unset` each new conversation.

| State | Behavior |
|---|---|
| `unset` | On the first messy message, emit the First-messy-message prompt. |
| `always` | Run auto-refine silently on every messy message. |
| `ask-each-time` | Ask "refine this first? (`yes`/`no`)" before each auto-run. |
| `never` | Never auto-run. Only explicit `/refine-prompt` (console mode) executes. |

### First-messy-message prompt

When consent is `unset` and a messy message arrives, emit verbatim:

> That message looks like a rough transcript. I can run `/refine-prompt` on messages like this automatically so you don't have to invoke it each time.
>
> - **`yes`** — auto-refine messy messages going forward
> - **`ask`** — ask me each time before refining
> - **`no`** — never, I'll invoke it myself when I want it
>
> For this message specifically, want me to refine it now? (`go` / `skip`)

Maps `yes`/`ask`/`no` to consent `always`/`ask-each-time`/`never`; `go` runs auto-refine now on the triggering message, `skip` leaves it. If the reply addresses only one decision, act on it and leave consent `unset`.

## Auto-refine interactive flow

### Phase A — Clarifications (conditional)
Skip if no blocking ambiguities. Otherwise emit a single batched, numbered list of all blocking questions and wait. Do not proceed until resolved.

### Phase B — Show refined prompt (approval gate)

> **Refined prompt:**
>
> <refined content — S1 or S2>
>
> **Assumptions:**
> - <each mild ambiguity, one per line>
>
> Reply `go` to proceed, or edit above.

Omit the Assumptions block entirely if there are none.

### Phase C — Execution

| Reply | Action |
|---|---|
| `go` | Proceed with the refined prompt as the task. Downstream skills trigger naturally. Do not re-invoke refine-prompt. |
| Edited prompt | Replace with the edit, proceed without re-refining. |
| `cancel` / `scrap it` / `stop` | Exit cleanly. No work started. |

### Source-of-truth rule
After `go`, the refined prompt **is** the task. Do not reinterpret the original transcript later. New scope = a fresh message.

---

# Pipeline (shared by both modes)

Run these five steps in order. In console mode they run inside the subagent; in auto-refine mode they run in the main loop.

## 1. Normalize
Strip mechanical noise without interpretation: filler tokens, word-level duplicates (`the the` → `the`), false starts, run-on punctuation. Do **not** change meaning or rephrase.

## 2. Segment intents
Split into distinct asks. `and also`, `oh and`, `plus`, `another thing` typically signal boundaries; short connectives (`and then`) within one task do not.

## 3. Probe for domain grounding (budgeted)
For each intent, identify concrete references and verify against the codebase.

**References:** identifiers (component/class/function/method/module/variable names), file paths, design-doc/ADR/RFC/ticket IDs, project-specific domain terms.

**How:** `Grep` for identifiers, `Glob` for file-name patterns, `Read` a small file only when grep/glob is ambiguous.

**Budget:** ~3–5 total tool calls per invocation. Targeted disambiguation, never a codebase tour.

**Resolution outcomes:**
- **Clean match** → anchor the reference with its real path or fully-qualified identifier.
- **Transcription near-miss, single clear candidate** → correct the term; in auto-refine mode note it under Assumptions, in console mode apply it silently.
- **Transcription near-miss, multiple plausible candidates** → **blocking** Phase A question listing the candidates.
- **Multiple plausible matches** → **blocking** Phase A question.
- **No match for a named term** (identifier, proper noun, framework/library, project term, or any token used *as a name*) → **blocking** Phase A question. Do not guess.
- **No match for a peripheral word** (fuzzy verb, vague scope qualifier, hedge — not a name) → clarification candidate for Step 4.

## 4. Classify ambiguities (tiered)

- **Mild ambiguity** (wrong guess costs at most a review round): in auto-refine mode include under `**Assumptions:**`; in console mode resolve silently (no Assumptions block exists there).
- **Blocking ambiguity** (wrong guess wastes real work): becomes a Phase A question in either mode.

**Word/name/term ambiguity is always blocking — never an assumption, in either mode.** If a specific word, name, or term appears and you cannot determine which word or referent was meant — from the conversation, codebase, or sentence — ask. A wrong guess about a *word* silently poisons the prompt; a missed word is a miscommunication, not a stylistic choice.

For **non-word** ambiguities (one PR or three? include migration tests? scope boundaries) — err toward silent resolution / Assumptions over Questions. Escalate to a Question only when a wrong guess would be genuinely expensive.

## 5. Compose refined prompt (adaptive shape)

| Condition | Shape |
|---|---|
| 1 intent, ≤ ~60 words of substance | **S1 — prose** |
| > 1 intent, OR 1 intent with > ~60 words | **S2 — structured brief** |

**Voice:** in S1 keep the user's clear phrasing (`fix the bug where X`, not `rectify the aforementioned defect`); in S2 use neutral task language.

**Output wrapping differs by mode:**
- **Console mode:** emit the **bare** prompt only — no header, no Assumptions, no footer.
- **Auto-refine mode:** wrap in the Phase B shell (header + optional Assumptions + `Reply go` footer).

### S1 — Prose
One to three sentences describing the task in the user's voice, with concrete references where probing resolved them.

### S2 — Structured brief
```
**Goal:** <one-sentence summary>

**Tasks:**
1. <first intent>
2. <second intent>

**Context / anchors:**
- Task N: <resolved path/reference, or "TBD during implementation">

**Acceptance:**
- Task N: <one-line "done" criterion>
```
(In auto-refine mode, an `**Assumptions:**` block is added before `Acceptance`. In console mode it is omitted.)

### Rules for both shapes
- Never invent references. Unresolved → Assumptions (mild, non-word, auto-refine only) or Phase A question (blocking).
- Omit empty sections. Never write "None" / "N/A".
- Preserve voice in S1; neutral language in S2.

---

# Invariants (all paths)

1. **Blocking ambiguities batch** into one Phase A message, never streamed.
2. **Probing is budgeted** — ~3–5 tool calls total per invocation. Never a codebase tour.
3. **No fabricated references, no guessed words.** An unresolved word/name/term is always a Phase A question, never a silent guess or a buried assumption.
4. **Consent is session-scoped.** Never written to auto-memory; resets each session.
5. **Don't refine clean input** in auto-refine mode. If the heuristic doesn't match and the user didn't invoke explicitly, don't run.
6. **Console mode never executes; auto-refine never skips the `go` gate.** The mode is fixed at invocation and the two never blend within a turn.
