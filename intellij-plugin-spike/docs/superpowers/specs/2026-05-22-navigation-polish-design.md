# Navigation polish — side panel + clickable identifiers

Follow-up to the IDE backend automation spec
(`2026-05-22-ide-backend-automation-design.md`). Same plugin, two new
navigation affordances that surfaced as pain points the first time the
plugin was used against a real PR.

## Goal

Make it easy to move around inside a review:

1. **From outside an annotation to it**: a right-side tool window lists
   every annotated line in the current session. Click a row to jump to
   that file/line in the diff editor and open its popup.
2. **From inside an annotation outward to the code it discusses**: code
   identifiers inside the synthesis text become clickable. Clicking
   navigates to the symbol in the project via IntelliJ's symbol index.

The motivating observation: gutter icons are small (12 px) and easy to
miss in a long diff. With 5+ annotations on a PR, you need a separate
list to find them again. And once you're reading a synthesis, every
class/method name is a navigation candidate — making them clickable
turns the synthesis into a hub for code exploration.

## Non-goals

- Search ranking, fuzzy match, or recency weighting in the panel.
  Substring filter is enough for the volumes we expect.
- Cross-session persistence of "what I've already seen." The yellow
  "updated" dot lives in plugin memory only and resets on IDE restart.
- Auto-resolving identifiers that aren't unique symbols. If
  `PsiShortNamesCache` returns multiple classes named `Foo`, we show a
  small chooser popup; we don't try to guess from context.
- Touching the synthesis prompt in `interactive_review/SKILL.md`. The
  symbol-lookup mechanism works regardless of whether Claude emits
  proper `[name](path:line)` links — backticks become navigable too.

## Architecture

```
ReviewSessionClient  (already exists — discovery + SSE + cache)
        │
        │ onThreadChanged, onStateChanged
        │
        ├──────────────────────┐
        │                      │
SynthesisPopup           AnnotationsPanel
(one per anchor)         (project-wide, tool-window)
        │                      │
        │                      ▼
        │              JBList<AnnotationEntry>
        │              custom renderer (path · :line · 2-line snippet · meta)
        │              click → navigate + open popup
        │
        ▼
JEditorPane with HTML body produced by MarkdownLinkRenderer:
   `Foo`           →  <a href="ireview-sym://Foo" class="ref-sym">Foo</a>
   [Foo](p:18)     →  <a href="ireview-nav://p:18" class="ref-code">Foo</a>
   [PMP-1](url)    →  <a href="url" class="ref-ticket">PMP-1</a>

HyperlinkListener routes by URL scheme:
   ireview-nav://  →  OpenFileDescriptor (path-based jump)
   ireview-sym://  →  PsiShortNamesCache lookup → navigate or chooser
   http(s)://       →  BrowserUtil.browse
```

## Side panel — components

### Files

| Path | Purpose |
|---|---|
| `src/main/java/com/petros/ireview/AnnotationsToolWindowFactory.java` | Registers the tool window, builds an `AnnotationsPanel`. |
| `src/main/java/com/petros/ireview/AnnotationsPanel.java` | Swing component. Header (title + count + search) / list / footer. Subscribes to `ReviewSessionClient` and renders the live cache. |
| `src/main/java/com/petros/ireview/AnnotationEntry.java` | Tiny record `(anchor, synthesisSnippet, version, updatedAt, isNew)`. |
| `src/main/resources/META-INF/plugin.xml` | New `<toolWindow>` registration. |

### Tool window registration

```xml
<toolWindow id="Review Annotations"
            anchor="right"
            icon="/icons/annotation_yellow.svg"
            factoryClass="com.petros.ireview.AnnotationsToolWindowFactory"/>
```

The tool window is always *available* (registered) but only made
*visible* (`activate(...)`) the first time the plugin transitions to
ACTIVE. After that the user controls visibility via IDEA's normal
tool-window UI.

### Cell rendering

A custom `ListCellRenderer<AnnotationEntry>` returns a small
`JPanel` per row, structured as in the mockup:

```
┌─────────────────────────────────────────┐
│ AdvisorDashboardController.java   :R:37 │   ← path (truncated) · line
│ Line 37 adds proposalListService as     │   ← 2-line synthesis snippet
│ a Lombok-injected final dependency…     │     (ellipsized)
│ 3 questions · 2 min ago        ● new    │   ← meta (left) · dot (right, if isNew)
└─────────────────────────────────────────┘
```

Selected row: background `#1a3a5e`.
Hover row: background `#2b2d30`.

The "new" yellow dot is per-anchor: shows whenever
`entry.version > seenVersions.getOrDefault(anchor, 0)`. Click clears it
by recording the version. The map lives in `AnnotationsPanel` instance
state; not persisted across IDE restarts.

### Click behavior

`MouseAdapter.mouseClicked` on the JList:

1. Get the clicked `AnnotationEntry`.
2. Parse the anchor (`<path>:<L|R>:<line>`).
3. Resolve `VirtualFile` via
   `LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + "/" + path)`.
4. If found: `new OpenFileDescriptor(project, file, line-1, 0).navigate(true)` —
   this opens the file in the regular editor.
5. Also open `SynthesisPopup.show(project, editor, anchor, line-1)`. We
   need to fetch the editor for the newly opened file; use
   `FileEditorManager.getInstance(project).getSelectedTextEditor()` after
   the `OpenFileDescriptor.navigate` call.
6. Record `seenVersions.put(anchor, entry.version())`. Repaint the row to
   clear the new-dot.

### Search input

Thin `JBTextField` at the top of the panel. On each keystroke, filter
the list model: keep entries where
`entry.anchor().toLowerCase().contains(query.toLowerCase())`. No
debounce — pure in-memory work.

## Clickable identifiers — components

### `MarkdownLinkRenderer` update

The inline-pattern stays the same shape (link OR backtick), but
backticks now produce a link, not a `<code>` tag:

```java
out.append("<a href=\"ireview-sym://").append(escape(m.group(3)))
   .append("\" class=\"ref-sym\">")
   .append(escape(m.group(3))).append("</a>");
```

`MarkdownLinkRenderer.toHtml` test gets an additional case:
`` toHtml("call `Foo` here") `` →
`call <a href="ireview-sym://Foo" class="ref-sym">Foo</a> here`.

### `SynthesisPopup` HyperlinkListener

A new `ireview-sym://` branch is added. Implementation, in order of
preference:

1. **`PsiShortNamesCache` lookup** (preferred). Inside a
   `ReadAction.compute(() -> ...)` (PSI access requires read action):

   ```java
   PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
   GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
   PsiClass[] classes = cache.getClassesByName(identifier, scope);
   if (classes.length == 1) {
       classes[0].navigate(true);
   } else if (classes.length > 1) {
       showChooserPopup(classes); // small JBList popup, click to navigate
   } else {
       // Try methods too:
       PsiMethod[] methods = cache.getMethodsByName(identifier, scope);
       // ... same logic
   }
   ```

   If neither classes nor methods resolve, silently no-op. (A small
   toast notification could be added later, but for v1 silent failure
   is acceptable — clicking a `false` or `null` shouldn't be noisy.)

2. **Fallback if `PsiShortNamesCache` proves insufficient**: use
   `GotoClassAction` or `GotoSymbolAction` programmatically without
   prefill. User then sees the empty search popup and types the
   identifier. Less magical but uses only public API.

The CSS class `.ref-sym` is added to the popup's stylesheet:

```css
a.ref-sym {
  color: #ce9178;
  text-decoration: none;
  font-family: monospace;
  font-size: 11.5px;
}
a.ref-sym:hover {
  text-decoration: underline dashed;
}
```

Identical to `inline-code` styling, plus the hover hint.

## Data flow examples

### Annotation lands while panel is open

1. Browser surface POSTs `/api/submit` for `Foo.java:R:10`.
2. Server appends message, fires `note_change`.
3. SSE emits `thread-changed`.
4. `ReviewSessionClient.handleSseEvent` updates cache, notifies
   `Listener.onThreadChanged("Foo.java:R:10", synthesis, version)`.
5. `AnnotationsPanel`'s listener fires on the SSE consumer thread, calls
   `SwingUtilities.invokeLater(this::rebuildList)`.
6. Rebuild reads `client.cache.entrySet()`, computes the new entry list,
   compares each `version` against `seenVersions`, sets `isNew=true` if
   greater. Model is replaced; JList repaints.
7. User sees the new row with the yellow dot.

### Click a clickable identifier

1. User reads synthesis: "... `forDashboard` ...".
2. Clicks `forDashboard`.
3. `HyperlinkListener` receives `ACTIVATED` event for
   `ireview-sym://forDashboard`.
4. `PsiShortNamesCache.getMethodsByName("forDashboard", projectScope)`
   returns one `PsiMethod` (the new one in `ProposalListService.java`).
5. `method.navigate(true)` opens the file at the method declaration.

## Failure modes

| Failure | Behavior |
|---|---|
| `findFileByPath` returns null on row click (file moved/deleted) | Show a brief notification "File not found: <path>"; do nothing else. |
| Click on identifier with no matching symbol | Silent no-op. Hover treatment makes clickability discoverable but failed clicks are quiet. |
| Multiple classes match the identifier | Show a small `JBList` popup; user picks one. Cancel = no-op. |
| Panel reaches 100+ entries | List virtualizes via JBList scroll; we don't add pagination. The search input handles "I can't find it in the list." |
| Tool window hidden by the user, new annotation lands | Panel updates in background; no UI thrash. User opens the tool window when they want; the latest state is there. |

## Testing plan

### Java

- **`MarkdownLinkRendererTest`** gets one new test:
  `` toHtml("call `Foo` here") `` produces an `<a href="ireview-sym://Foo">`
  anchor with class `ref-sym`. Existing tests for path/url links stay
  unchanged.
- **`AnnotationEntry`** is a record; no test.
- **`AnnotationsPanel`** is hard to unit-test in isolation (depends on
  `ReviewSessionClient`, IDEA Swing setup, PSI). Manual smoke test
  instead, documented in `HANDOFF.md`.

### End-to-end smoke (manual addendum to existing recipe)

1. Start a review with 3+ annotated lines across 2+ files.
2. Open the right-side `Review Annotations` tool window. Verify the list
   shows all 3 with correct synthesis snippets.
3. Type a file name fragment in the search box. Verify the list filters
   live.
4. Click a row. Verify IDEA opens the file at the line and the popup
   opens on that anchor.
5. From the browser surface, ask a follow-up question on a different
   annotation. Verify the IDE panel adds a yellow dot on that row
   within a few seconds.
6. In the IDE popup, click a backtick identifier. Verify IDEA navigates
   to the symbol's declaration.

## Out of scope (filed as future work)

- "Why doesn't `\`null\`` resolve?" — UX for non-symbol backticks. v1
  treats them as silent no-ops.
- Resolving symbols that live in dependencies (not project sources).
  `GlobalSearchScope.allScope(project)` would do this; risk is too many
  unrelated matches. Defer.
- A "show all threads, including from prior sessions on this PR" view
  in the panel. The orphan-thread risk from `scrap it → start fresh`
  noted in the original spec stays.
- Persisting the "seen versions" map across IDE restart. YAGNI for v1.
