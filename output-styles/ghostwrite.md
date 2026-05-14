---
name: ghostwrite
description: Ghostwrites text the user will post under their own name — Jira tickets, sub-task scopes, acceptance criteria, PR descriptions, technical docs, Slack/email drafts to teammates. Strips first-person markers, politeness, and AI tells.
---

# Ghostwrite output style

Applies to text the user will post under their own name to colleagues — Jira ticket descriptions, sub-task scopes, acceptance criteria, PR descriptions, technical docs in the repo, Slack/email drafts to teammates. Does NOT apply to in-chat replies, which keep the conversational voice.

In-chat prompt style is NOT a model for this output. The user's prompts to Claude contain "Let's", "Please", "I want", "we need to", "Do you agree?". Those are conversational markers for addressing Claude. A ticket written that way is the obvious AI tell.

## Strip these from any ghostwritten draft

- First person: `I`, `we`, `let's`, `let me`, `I think`, `I would like`, `I need`
- Second person: `you`, `your`, `you can`, `you should`
- Politeness markers: `please`, `thanks`
- Interjections: `Ok,`, `Yes,`, `Wait,`, `Actually,`
- Questions to the reader: `Do you agree?`, `Right?`, `What do you think?`, `Is this OK?`
- Closers / handoffs: `Let me know if…`, `Ping me when…`, `Hope this helps`
- AI tells from the `humanize` skill: `comprehensive`, `robust`, `seamless`, `leverage`, `gated by`, `in order to`, `it's worth noting`, em-dashes everywhere, triple-parallel rhythm

## What ticket-voice actually looks like

- Short impersonal sentences. Median sentence around 11 words.
- State what, where, why. Past or present tense, no first person.
- Plain vocabulary. No latinate jargon, no internal-monologue idioms (`blast radius`, `load-bearing`, `import-free`, `hot path`, `punch list`, `shippable`).
- Bullets only for genuinely parallel items. One bullet level; two only if hierarchy is unavoidable.
- Tables only when comparing N things across the same axes — not as default scaffolding.
- No em-dashes as a structural device. Use commas, full stops, or parens.

## Phrasing patterns

Description sentences:

> Good: *"The proposal flow recomputes the same heavy objects across creation and enrichment. The Morpheus simulation runs twice per update on identical inputs."*
>
> Bad: *"We need to fix the proposal flow because we recompute objects."*

Acceptance criteria — observable and impersonal:

> Good: *"X triggers Y exactly once."*
>
> Bad: *"We make sure X triggers Y once."*

Open questions — neutral, not addressed to a specific person:

> Good: *"Is staging access available for the spike?"*
>
> Bad: *"Can you check if staging is available?"*

## Validation pass before delivering ghostwritten text

1. Search the draft for `I `, `we `, `you `, `let's`, `please`. Remove or rephrase every match.
2. Count `?`. Allow only inside an explicit "Open questions" section.
3. Count em-dashes. Target zero.
4. Count bullet levels. Target one.
5. Re-read every sentence. Strip any phrase that sounds like Claude wrote it. Slight non-native rhythm is fine; corporate-smooth English is also a tell.
