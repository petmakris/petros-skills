---
name: humanize
description: Rewrite Claude-generated markdown (PR analyses, ticket breakdowns, stacktrace diagnoses, design notes, status updates — anything from a programmer's day) so it reads like a long-time colleague wrote it. Less formatting, no AI tells, professional but informal. ONLY use when the user explicitly invokes this skill — they type /humanize or ask to "humanize", "derobotize", "rephrase to sound human", "make this less AI-y", "rewrite this so it doesn't sound like Claude wrote it", etc. Do NOT trigger automatically just because the conversation contains AI-toned text — only on explicit invocation. The skill is allowed to read files in the current working directory to spot-check factual claims before rewriting.
---

# Humanize

Rewrite markdown produced by Claude so it reads like a long-time colleague wrote it. Same content, professional, but with the AI tells stripped out.

## When to use

Manual only. The user invokes this skill explicitly — `/humanize`, "humanize this", "derobotize", "rewrite this so it doesn't sound like Claude". Don't invoke it on your own initiative even when the conversation has obviously AI-toned text.

## What "long-time colleague" tone means here

The audience is a teammate the writer has worked with for years. A senior programmer writing about programmer things — a PR they reviewed, a ticket they investigated, a stacktrace they tracked down, a system they're explaining.

How they actually write:
- Direct. Skips the throat-clearing intro and the recap of the question.
- Active voice with named subjects ("the buyer service swallows the exception" beats "the exception is being swallowed").
- Casual connectors are fine: "turns out", "looks like", "fwiw", "imo", "tldr", "the gist".
- Doesn't hedge what it's confident about. Marks real uncertainty plainly ("not 100% on this", "haven't verified") instead of with formal disclaimers.
- Skips formal closers — no "Hope this helps", no "Let me know if you have any questions".
- One short paragraph beats three bullets when the items are tightly related thought.
- Bullets only when the structure genuinely is a list — separate independent items, not consecutive sentences forced into bullet shape.
- Shorter than the AI version. Humans write less.

## Tells to remove

The issue isn't any single one — it's the cumulative load. Hit a combination.

### Filler vocabulary
Words people don't actually say in chat, on Slack, or in PR descriptions, but AI text leans on heavily:
- "comprehensive", "robust", "seamless", "leverage" / "leverages" / "leveraging"
- "facilitate", "utilize" (just say "use"), "delve into"
- "gated by" — huge tell. Say "depends on", "blocked by", "needs", "behind \<flag\>", "requires"
- "in order to" → "to"
- "due to the fact that" → "because"
- "at the time of writing", "as of this writing"
- "it is worth noting that", "it's important to note that", "notably"
- "in essence", "fundamentally", "essentially"
- "a wide range of", "a variety of"

These don't all need to die in every text — but if any of them slipped in unnecessarily, drop them.

### Hedging boilerplate
- "It's worth noting that X" → just say X
- "It's important to mention that X" → just say X
- "There are several considerations" → list them concretely or skip

### Triple-parallel rhythm
AI loves three-item parallel patterns ("clear, concise, and effective" / "fast, reliable, and scalable" / "robust, comprehensive, and maintainable"). Cut to one or two — pick the one doing the work.

### Em-dash overuse
Em dashes are fine — sparingly. Three or more in a paragraph means most are doing comma's job. Replace with commas, periods, or restructure.

### Bold-everywhere emphasis
Bolding every other phrase deadens emphasis. Reserve bold for things the reader genuinely needs to spot at a glance (file names, the term being defined). Default: no bold.

### Closers
- "Hope this helps!"
- "Let me know if you have any questions"
- "Feel free to reach out"
- "I hope this clarifies things"

Just stop writing when you're done.

### "In conclusion" / recap sections
If the doc is short, the reader doesn't need a summary of what they just read. Drop the conclusion section unless it adds something the body didn't.

## Structural moves

You can do more than swap words. Restructure when it helps:

- **Flatten over-structured docs.** Five H3 headings each containing two sentences isn't a structured doc — it's prose pretending to be one. Merge into paragraphs or fewer headings.
- **Drop bullets that should be prose.** "The PR adds X. The PR refactors Y. The PR removes Z." → "The PR adds X, refactors Y, drops Z."
- **Cut sections that don't earn their space.** Generic "Background" sections that restate what the reader already knows. "Risks" sections that say "deploy carefully and test thoroughly".
- **Cut restatements of the prompt.** AI text often opens by paraphrasing the question. Drop it.
- **Aim for shorter.** If the rewrite isn't meaningfully shorter than the input, you probably haven't cut enough.

## What NOT to change

The rewrite is about tone, not content. Preserve technical fidelity:
- File paths, line numbers, class names, method names, package names
- Code blocks and inline `code` snippets verbatim
- Command examples (CLI invocations, SQL, etc.)
- Specific numbers (counts, durations, error codes, line numbers)
- IDE-style markdown links like `[Foo.java:42](jetbrains://...)` — keep them
- Domain terms used in the codebase
- The factual conclusion (unless validation reveals it's wrong — see below)

## Validating claims before rewriting

The input may make specific claims about code: "the `FooService.bar()` method swallows the exception", "the property `foo.bar` defaults to 30 seconds", "the bug is in line 47 of `X.java`".

If the current working directory contains the code being discussed:
- Spot-check load-bearing claims with Read/Grep before rewriting them. You don't need to verify every fact — only the ones that would be embarrassing or misleading if wrong (specific line numbers, claimed defaults, claimed method behavior).
- If a claim is wrong, fix it in the rewrite. Don't preserve incorrect content just because it was in the input.
- If a claim looks suspicious but you can't verify quickly, soften it ("looks like X" rather than asserting X) or flag it to the user briefly.
- Don't go overboard — this is a sanity check, not a full code audit. Keep it under a couple of minutes.

## Output

Return only the rewritten markdown. No "Here's the rewrite:" preamble, no diff, no change notes.

If validation surfaced a factual issue you fixed or softened, mention it briefly after a `---` separator at the end. Default: no commentary, just the rewrite.

## Examples

### Example 1: Stacktrace analysis

**Input:**
```markdown
# Stacktrace Analysis

## Overview
The provided stacktrace indicates a `NullPointerException` originating from the `OrderService` class. This is a comprehensive analysis of the root cause.

## Root Cause
It's worth noting that the exception is being thrown because the `customer` field is null at the time of the `processOrder()` invocation. This is gated by the upstream `CustomerLookupService` returning an empty result.

## Recommendation
We should add a null check, log the customer ID, and propagate a meaningful exception. This will be a robust, comprehensive, and maintainable solution.

Hope this helps!
```

**Output:**
```markdown
NPE in `OrderService.processOrder()` — `customer` is null when it gets there. `CustomerLookupService` returns empty for this customer ID and we don't handle that case downstream.

Fix: null-check on the lookup result, log the customer ID we couldn't find, throw a typed exception instead of letting the NPE bubble.
```

### Example 2: PR summary

**Input:**
```markdown
## Summary
This pull request introduces a comprehensive set of changes to the authentication middleware in order to facilitate improved session token handling.

## Key Changes
- **Refactored** the `SessionTokenValidator` to leverage the new `TokenStore` API
- **Added** comprehensive test coverage for edge cases
- **Removed** legacy code paths that are no longer in use

## Impact
The changes are gated by the `feature.new-auth` flag, so the rollout will be controlled and reversible.
```

**Output:**
```markdown
Reworks the auth middleware so `SessionTokenValidator` uses the new `TokenStore` API. Tests added for the edge cases, legacy paths gone. Behind the `feature.new-auth` flag, so we can roll back if it goes sideways.
```

### Example 3: Ticket investigation

**Input:**
```markdown
# Investigation Findings

## Background
The reported bug describes intermittent failures when users attempt to save their portfolio settings. After a comprehensive investigation of the relevant code paths, I have identified the root cause.

## Findings
- The issue is gated by the timing of two concurrent requests
- The `PortfolioSettingsService` does not implement appropriate locking
- It is worth noting that this only manifests under high load

## Conclusion
In conclusion, we need to add proper concurrency control to resolve this issue.
```

**Output:**
```markdown
Race condition in `PortfolioSettingsService` — when two save requests land at the same time, they step on each other. No locking around the read-modify-write. Only shows up under load, which is why it's been intermittent.

Needs proper concurrency control on the save path.
```

## A note on going too far

Don't swing past colleague-tone into sloppy or curt. The goal is a senior engineer being concise, not a teenager texting. Some context warrants more structure than others — a security advisory, an architecture decision record, or a post-mortem keeps more headings and care than a quick Slack-bound summary. Use judgment about how casual is appropriate for the apparent audience of the text.
