---
name: refine-prompt
description: Use when the user invokes /refine-prompt, or when a messy user message (likely a speech-to-text transcript) arrives with session consent granted. Transforms it into a high-quality coding prompt via normalize → probe → classify → compose, with explicit user approval before execution.
user-invocable: true
argument-hint: pasted transcript text (optional — if omitted, operates on the previous user message, or asks the user to paste one)
---

# Refine Prompt Skill

Transforms low-quality transcripts (typically speech-to-text output) into high-quality agentic coding prompts. Runs a 5-step pipeline (normalize → segment → probe → classify → compose) followed by a 3-phase interactive flow (clarify → approve → execute).

## When to invoke

Run this skill in any of these situations:

| Trigger | Action |
|---|---|
| User runs `/refine-prompt <transcript text>` | Refine the inline argument |
| User runs `/refine-prompt` with no argument | Refine the immediately preceding user message |
| User runs `/refine-prompt` with no argument AND there is no prior user message to target | Ask the user to paste the transcript, then refine what they paste |
| A user message matches the **Messiness heuristic** AND session consent is `always` | Silently run the skill on that message |
| A user message matches the **Messiness heuristic** AND session consent is `ask-each-time` | Ask the user "refine this first? (yes/no)" before running |
| A user message matches the **Messiness heuristic** AND session consent is `unset` | Emit the **First-messy-message prompt** (see Consent section) |

Do **not** auto-run the skill on:
- Messages that are clearly clean prose (no heuristic hits)
- Messages that are themselves commands or short confirmations (`go`, `yes`, `ok`, etc.)
- Messages that are part of an in-progress `/refine-prompt` turn (clarification answers, approval replies)

If the user explicitly invokes `/refine-prompt` on clean input, run it anyway — the pipeline usually no-ops and the output mirrors the input. The user asked.

## Messiness heuristic

A message qualifies as "messy" when **at least two** of the following are true. Evaluate qualitatively — this is guidance for judgment, not a scorer to run literally.

1. **Long without punctuation.** Length > 200 characters and contains fewer than 2 sentence-ending marks (`.`, `!`, `?`).
2. **Filler words.** Contains tokens like `um`, `uh`, `like` (as filler, not comparative), `kind of`, `sort of`, `you know`, `basically`, `so basically`, `I mean`.
3. **Word-level duplicates.** Immediate repetitions such as `the the`, `fix fix`, `we we should`, `and and`.
4. **Vague references without antecedent.** Phrases like `that thing`, `the stuff we talked about`, `the one from before`, with no clear referent in the current conversation.
5. **Transcription candidates.** Tokens that look like phonetic near-misses of plausible domain terms — a word whose sound is close to, but not exactly, an identifier, library, or framework name likely to appear in the project.
6. **Missing verb or direct object.** An imperative-shaped request that lacks either the action or the target.

A clean, well-formed request — even a long one — does **not** trigger the heuristic. The goal is to catch dictated streams of thought, not to second-guess careful writing.

## Consent and auto-trigger

Consent is **session-scoped** — tracked in the conversation context, not persisted via the auto-memory system. It resets to `unset` at the start of every new conversation.

### Consent states

| State | Behavior |
|---|---|
| `unset` | Default at session start. On the first messy user message, emit the First-messy-message prompt below. |
| `always` | Run the skill silently on every messy user message. |
| `ask-each-time` | Ask "refine this first? (`yes`/`no`)" before each auto-run. |
| `never` | Do not auto-run. The skill only executes on explicit `/refine-prompt` invocations. |

### First-messy-message prompt

When consent is `unset` and a messy message arrives, emit this message verbatim (substituting nothing):

> That message looks like a rough transcript. I can run `/refine-prompt` on messages like this automatically so you don't have to invoke it each time.
>
> - **`yes`** — auto-refine messy messages going forward
> - **`ask`** — ask me each time before refining
> - **`no`** — never, I'll invoke it myself when I want it
>
> For this message specifically, want me to refine it now? (`go` / `skip`)

Two decisions are captured at once:

1. **Session policy** — maps the user's reply (`yes` / `ask` / `no`) to consent state `always` / `ask-each-time` / `never`.
2. **One-off for the current message** — `go` runs the skill now on the triggering message; `skip` leaves it alone.

If the reply addresses only one of the two (e.g., says `go` without picking a policy), act on what they answered and leave the other unchanged. Consent stays `unset` and the prompt will fire again on the next messy message.

### Invariant

`silently` in the `always` state refers only to skipping the *consent gate*. The **Phase B approval gate** in the Interactive Flow is never skipped. No refined prompt is ever executed without the user's explicit `go`.

## Pipeline

Run these five steps in order on every invocation (explicit or auto).

### 1. Normalize

Strip mechanical noise without interpretation:
- Filler tokens (`um`, `uh`, `like`, `basically`, etc.)
- Word-level duplicates (`the the` → `the`)
- Repeated false starts (`I want to— actually let's—` → `let's`)
- Run-on punctuation (`???` → `?`, missing capitalization after periods)

Do **not** change meaning or rephrase at this step. This is purely mechanical.

### 2. Segment intents

Split the normalized text into distinct asks. A single request is one intent; conjunctions like `and also`, `oh and`, `plus`, `another thing` typically signal intent boundaries. Short connectives (`and then`) within a single logical task do not.

### 3. Probe for domain grounding (budgeted)

For each intent, identify concrete references and verify them against the codebase.

**What counts as a reference:**
- Identifiers: component, class, function, method, module, or variable names
- File paths or path fragments
- Design / decision document references (ADR numbers, RFC numbers, ticket IDs)
- Domain terms drawn from the project's own vocabulary (subsystems, feature names, architectural modules)

**How to verify:**
- `Grep` for class/function/identifier names
- `Glob` for file-name patterns
- `Read` a small file only when the grep/glob result is ambiguous

**Budget:** ~3–5 total tool calls per invocation (explicit or auto). Not per intent — per invocation. Probing is targeted disambiguation, never a codebase tour.

**Resolution outcomes:**
- **Clean match** → anchor the reference in the refined prompt with the real path or fully-qualified identifier it resolves to.
- **Transcription near-miss, single clear candidate** (the transcript token is close to, but not exactly, exactly one identifier that actually exists) → correct the term in the refined prompt, note the correction in Assumptions.
- **Transcription near-miss, multiple plausible candidates** (two or more identifiers sound close to the transcript token) → **blocking** clarification for Phase A. List the candidates and ask the user which word they meant.
- **Multiple plausible matches** (e.g., a glob returns several candidates, a name is used by several modules) → **blocking** clarification for Phase A: list the candidates and ask the user to pick.
- **No match for a named term** (identifier, proper noun, framework/library name, project-specific domain term, or any transcript token that is being used *as a name*) → **blocking** clarification for Phase A. Ask the user what word or name they meant. Do not guess.
- **No match for a peripheral word** (a fuzzy verb, a vague scope qualifier, a hedge — not functioning as a name) → flag as a clarification candidate for Step 4.

### 4. Classify ambiguities (tiered)

For each unresolved thing from Step 3 or from the original transcript:

- **Mild ambiguity** (safe to guess): the wrong guess would cost at most a round of review. → Include as a bullet under the `**Assumptions:**` block in the refined prompt. The user catches wrong guesses at the approval gate.
- **Blocking ambiguity** (wrong guess would waste real work): → Becomes a clarification question in Phase A of the Interactive Flow.

**Word/name/term ambiguity is always blocking — never an assumption.** If a specific word, name, or term appears in the transcript and you cannot determine which word or referent the user meant — from the conversation, the codebase, or the surrounding sentence — ask. Covers:
- Transcription tokens where you can't tell what was actually said (near-misses with no clear winner, or unfamiliar tokens with no codebase match)
- Domain terms, proper nouns, or feature names used without a clear antecedent in the conversation
- Identifier-shaped tokens that don't resolve anywhere in the repo
- Shorthand like "the service", "that thing", "the one from before" when the referent cannot be pinned down

The reason is asymmetric cost: a wrong guess about a *word* poisons the refined prompt silently (the user may miss the substitution in Assumptions and discover it only after work is wasted). A missed word is a miscommunication, not a stylistic choice.

For **non-word** ambiguities (e.g., "should this be one PR or three?", "include migration tests?", scope boundaries) — err toward Assumptions over Questions. The point of the skill is to reduce friction; peppering the user with six questions defeats it. Escalate a non-word ambiguity to a Question only when a wrong guess would be genuinely expensive.

### 5. Compose refined prompt (adaptive shape)

Pick the output shape based on intent count and length:

| Condition | Shape |
|---|---|
| 1 intent, ≤ ~60 words of substantive content | **S1 — prose** |
| > 1 intent, OR 1 intent with > ~60 words of substance | **S2 — structured brief** |

See the Output Format section for exact templates.

**Voice preservation:**
- In **S1**, keep the user's phrasing where it's already clear. Good: `fix the bug where X`. Bad: `rectify the aforementioned defect`.
- In **S2**, use neutral task language — the original prose doesn't survive decomposition anyway.

## Interactive flow

Three phases inside a single `/refine-prompt` turn (or a single auto-triggered invocation).

### Phase A — Clarifications (conditional)

Skip entirely if Step 4 of the Pipeline produced no blocking ambiguities.

Otherwise, emit a **single batched message** with a numbered list of all blocking questions. Example:

> I need to clarify a couple things before I refine this:
>
> 1. *"the service"* — do you mean the general service module, or a specific variant used by a particular subsystem?
> 2. *"fix it"* — fix the known issue we discussed earlier, or something else?
>
> Reply with a short answer to each.

Wait for the user's reply. Do **not** proceed to Phase B until all blocking ambiguities are resolved. If the user's reply leaves one still ambiguous, ask again — but consolidate into the next batch rather than streaming one-off questions.

### Phase B — Show refined prompt (approval gate)

Emit the refined prompt in this exact shell:

> **Refined prompt:**
>
> <refined content — S1 or S2 — see Output Format>
>
> **Assumptions:**
> - <each mild ambiguity, one per line>
>
> Reply `go` to proceed, or edit above.

If there are no assumptions, omit the `Assumptions:` block entirely. Don't say "None" — just leave it out.

### Phase C — Execution

Three possible replies:

| Reply | Action |
|---|---|
| `go` | Proceed with the refined prompt as the effective task. Downstream skills (brainstorming, debugging, TDD, etc.) trigger naturally from its content. Do **not** re-invoke refine-prompt. |
| Edited version of the refined prompt | Replace the prompt with the user's edit. Proceed to execution without re-refining. |
| `cancel` / `scrap it` / `stop` | Exit cleanly. No work started. Consent state is unchanged. |

**Edit vs. scope change:** if the reply looks like a targeted correction to the existing refined prompt (fixing a value, swapping an identifier, tightening a criterion), treat it as an edit and proceed. If it introduces new scope not present in the refined prompt, treat it as a fresh message — potentially a new `/refine-prompt` candidate — rather than silently folding it into the current task.

### Source-of-truth rule

After `go`, the **refined prompt is the task**. Do not silently reinterpret the original messy transcript later in the session. If the user reopens scope, treat their new message as its own input — potentially another refine-prompt candidate.

## Output format

Two templates. The Pipeline's Step 5 decision rule selects which to use.

### S1 — Prose

Use when: 1 intent, ≤ ~60 words of substantive content.

Template:

```
**Refined prompt:**

<one to three sentences describing the task in the user's voice, with concrete references where probing resolved them>

**Assumptions:**
- <bullet per mild ambiguity; omit entire block if none>

Reply `go` to proceed, or edit above.
```

### S2 — Structured brief

Use when: > 1 intent, OR 1 intent with > ~60 words of substance.

Template:

```
**Refined prompt:**

**Goal:** <one-sentence summary of the overall ask>

**Tasks:**
1. <first distinct intent, neutral task language>
2. <second distinct intent>
3. <etc.>

**Context / anchors:**
- Task N: <file path or codebase reference resolved by probing; "TBD during implementation" if not resolvable without ambiguity>

**Assumptions:**
- <bullet per mild ambiguity; omit entire block if none>

**Acceptance:**
- Task N: <one-line criterion per task — what "done" looks like>

Reply `go` to proceed, or edit above.
```

### Rules that apply to both shapes

- Always begin with the literal string `**Refined prompt:**` on its own line. This is the anchor downstream logic looks for.
- Omit empty sections entirely. Never write "None" or "N/A".
- Never invent references. If probing could not resolve something, either put it in Assumptions (mild) or raise it as a Question in Phase A (blocking) — do not fabricate a plausible-looking path.
- Preserve user's voice in S1. Use neutral task language in S2.

## Examples

### Example A — single intent → S1 prose

Input:

> um so the thing with the the data load when you navigate away it keeps going and doesn't stop can you like fix that

Probing (Step 3): check whether the area under discussion has a "data load" identifier and a clear navigation / teardown seam. Quick confirmation, no deep navigation.

Clarifications (Phase A): none — "keeps running after navigate-away" has a familiar missing-cleanup shape.

Output (Phase B):

```
**Refined prompt:**

Fix the bug where a data load keeps running after the user navigates away — the cleanup step isn't firing. Expected: work started by the view should stop when the user leaves it.

**Assumptions:**
- Refers to the component most recently discussed in this session; confirm if that's wrong

Reply `go` to proceed, or edit above.
```

### Example B — multiple intents → S2 structured brief

Input:

> ok so a bunch of things, first the card spacing looks weird on mobile too tight, and also we should probably add a loading state while we're waiting for the server, and oh also can you check why the event logs don't show some entries sometimes

Probing (Step 3): resolve which "card" — glob for likely matches → pick the single clear resolution and anchor its path. Skim the event-logging pipeline for Task 3 context.

Clarifications (Phase A): none — three independent asks, all with clear actions.

Output (Phase B):

```
**Refined prompt:**

**Goal:** Three unrelated fixes.

**Tasks:**
1. Tighten the card spacing on mobile — currently too tight per user observation
2. Add a loading state while waiting for a server response
3. Investigate why some entries are intermittently missing from the event logs

**Context / anchors:**
- Task 1: resolved card-component path (located via glob)
- Task 2: the view/container making the server request — specific target TBD during implementation
- Task 3: event-logging pipeline

**Assumptions:**
- Tasks are independent — can be done in any order or separate PRs
- Task 1 is mobile visual only, not desktop

**Acceptance:**
- Task 1: visual confirmation + no regression on desktop
- Task 2: loading indicator visible between request dispatch and response
- Task 3: reproducible test case identified, then fixed

Reply `go` to proceed, or edit above.
```

### Example C — blocking ambiguity → Phase A clarification first

Input:

> deploy the thing we were working on

Probing (Step 3): no concrete references in the transcript to anchor.

Clarifications (Phase A, emitted because ambiguity is blocking):

> I need to clarify before refining:
>
> 1. *"the thing we were working on"* — which project/service? (I don't have a reference from this turn.)
> 2. *"deploy"* — to which environment (staging / production)?
>
> Reply with short answers and I'll refine.

Phase B and C proceed only after the user answers.

## Invariants

These hold regardless of invocation path (explicit vs. auto) or consent state.

1. **The approval gate is never skipped.** No refined prompt is executed without the user's explicit `go` (or edited-and-confirmed equivalent).
2. **Probing is budgeted.** ~3–5 tool calls total per invocation (explicit or auto). Never a codebase tour.
3. **No fabricated references, no guessed words.** If probing can't resolve a reference, it goes in Assumptions (mild, non-word only) or Phase A questions (blocking). Never invent a plausible-looking path. An unresolved word, name, or term always becomes a Phase A question — it is never filed under Assumptions as a guess.
4. **Blocking ambiguities batch.** All Phase A questions appear in one message, not streamed one-by-one.
5. **Consent is session-scoped.** Tracked only in conversation context. Never write it to auto-memory. Resets at every new session.
6. **The refined prompt is the task.** After `go`, downstream behavior operates on the refined prompt — not on the original transcript.
7. **Don't refine clean input.** If the Messiness heuristic doesn't match and the user didn't invoke explicitly, don't run.
