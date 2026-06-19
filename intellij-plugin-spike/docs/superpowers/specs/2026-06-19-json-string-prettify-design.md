# Prettify JSON in Java string — design

**Date:** 2026-06-19
**Status:** Approved (brainstorming)

## Problem

JSON embedded in Java string literals — especially text blocks annotated with
`@Language("JSON")` or passed to helpers like `jsonBody(@Language("JSON") String …)` —
cannot be reliably reformatted by IntelliJ's native tooling. The "Edit JSON Fragment"
and reformat-injection paths fail in practice because the templates contain
`{{placeholder}}` tokens that make the content **invalid JSON**:

- `"assetId": "{{assetId}}"` — token inside quotes → valid JSON string, fine.
- `"id": {{orderId}}` — token in **value position, unquoted** → invalid JSON → the
  JSON formatter bails.

We want a reusable, in-IDE way to prettify the JSON inside such strings without
breaking the placeholders.

## Goal

Add a `Prettify JSON String` action to the existing `Interactive Review (spike)`
plugin. With the caret inside a Java **text block**, the user invokes the action
(menu + keyboard shortcut) and the JSON content is reformatted in place —
placeholder-aware, undo as a single step.

## Non-goals

- Single-line string literals (`"…"`). Prettified JSON is multi-line and cannot fit a
  single-line literal; the action is disabled there. (Text blocks only.)
- Validating that the JSON is *semantically* correct. We only need structural
  reformatting.
- Matching the IDE's configurable JSON code style. We emit fixed 2-space indentation.

## Approach

Approach **A** (chosen): a plugin `AnAction` with placeholder-aware formatting, built
on a pure, IDE-independent core so the hard logic is unit-testable with the existing
JUnit 5 setup.

Two pieces:

### 1. `JsonStringPrettifier` — pure core (no IntelliJ deps)

`Optional<String> prettify(String content)` — returns the reformatted JSON, or
`Optional.empty()` if `content` is not structurally valid JSON (so the action can
quietly skip). Pipeline:

1. **Tokenize `{{…}}`.** Walk the characters tracking string-vs-structure context
   (with `\` escape handling inside strings). For each `{{…}}` run:
   - Record the original token text (including the braces).
   - Replace it with a unique sentinel built from Unicode private-use chars
     (`` + index + ``) that won't collide with real content.
   - **In string context** → bare sentinel (it's already inside quotes).
   - **In value / key / array context** → quoted sentinel (`"N"`) so the
     text becomes valid JSON.
   - Remember, per token, whether quotes were added.
2. **Reformat.** Run a string-aware structural re-indenter over the sentinel text:
   emit a newline + 2-space-per-depth indent after `{` `[` and after value-separating
   `,`, and before `}` `]`; collapse whitespace outside strings; leave string contents
   byte-for-byte. If brackets/quotes don't balance, return `Optional.empty()`.
3. **Restore tokens.** Replace each sentinel with its original token text. For
   quoted-sentinel tokens, replace the **whole** `"N"` (including the quotes
   we added) → original token, so an unquoted `{{orderId}}` comes back unquoted.
   **Original form is preserved.**

This handles both example shapes: quoted tokens stay quoted, unquoted value/key tokens
stay unquoted.

### 2. `PrettifyJsonStringAction extends AnAction` — IDE glue

- **`update`:** enabled only when the caret sits inside a `PsiLiteralExpression` that
  `isTextBlock()`. Otherwise disabled.
- **`actionPerformed`:**
  1. Resolve the text block PSI element at the caret.
  2. Read its decoded content and its existing base indentation (the indentation of
     its content lines).
  3. `prettify(content)`; if empty, show a quiet hint balloon ("Not valid JSON —
     nothing to format") and stop.
  4. Re-indent the formatted JSON: prefix every line with the block's base indent,
     keep the opening `"""` / closing `"""` placement intact.
  5. Replace the text block's content range via the Document inside a single
     `WriteCommandAction` (one undo).

## Registration

Add to `META-INF/plugin.xml`:

```xml
<actions>
  <action id="com.petros.ireview.PrettifyJsonStringAction"
          class="com.petros.ireview.PrettifyJsonStringAction"
          text="Prettify JSON String"
          description="Reformat JSON inside the surrounding Java text block, preserving {{placeholders}}.">
    <add-to-group group-id="EditMenu" anchor="last"/>
    <keyboard-shortcut keymap="$default" first-keystroke="control alt J"/>
  </action>
</actions>
```

(Shortcut tentative — adjust if it collides on macOS.)

## Testing

`JsonStringPrettifierTest` (JUnit 5, pure, no IDE harness needed):

- Quoted token round-trips unchanged in form: `"assetId": "{{assetId}}"`.
- Unquoted value token stays unquoted: `"id": {{orderId}}` → still `{{orderId}}`,
  not `"{{orderId}}"`.
- Nested objects/arrays indent at 2 spaces per depth.
- Already-pretty input is idempotent (format twice == format once).
- Invalid input (unbalanced braces) → `Optional.empty()`.
- Both example payloads from the request reformat to the expected canonical layout.

The action's IDE glue (`update` enablement, document write) is exercised manually in
the `runIde` sandbox — not unit-tested, consistent with the rest of the spike.

## Files

- `src/main/java/com/petros/ireview/JsonStringPrettifier.java` — new, pure core.
- `src/main/java/com/petros/ireview/PrettifyJsonStringAction.java` — new, IDE glue.
- `src/test/java/com/petros/ireview/JsonStringPrettifierTest.java` — new tests.
- `src/main/resources/META-INF/plugin.xml` — add `<actions>` block.

## Risks / open points

- **Text-block re-indentation** in IntelliJ is fiddly (incidental-whitespace rules
  tied to the closing delimiter). Mitigation: re-indent to the existing block's
  detected base indent rather than computing from scratch.
- **Keyboard-shortcut collision** on macOS — verify in the sandbox, change if needed.
- The structural re-indenter is not a full JSON parser; it validates by bracket/quote
  balance only. Acceptable given the sentinel text is otherwise well-formed.
