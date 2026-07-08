# Interactive Review — IntelliJ plugin spike

## What this proves

A `com.intellij.diff.DiffExtension` registered in `plugin.xml` is invoked for every
diff viewer that opens. When the viewer is a `TwosideTextDiffViewer`, we attach a
range highlighter to line 1 of the right-hand editor and give it a
`GutterIconRenderer` whose click action pops `Messages.showInputDialog` and logs
the result via `Logger.getInstance().info(...)`.

If this works end-to-end, the same hook is enough to drive the real
interactive-review flow (one icon per changed line, click → emit event).

## Run it

```
cd intellij-plugin-spike
./gradlew runIde
```

`runIde` (provided by the IntelliJ Platform Gradle Plugin 2.x) downloads
IDEA Community matching `platformVersion` in `gradle.properties` and launches
it as a sandboxed dev IDE with this plugin installed.

> Note: there is no `gradle/wrapper` checked in here. Run `gradle wrapper`
> once with a system Gradle (8.5+) to generate `gradlew` / `gradle-wrapper.jar`,
> or invoke `gradle runIde` directly.

## Test

1. In the dev IDE, open any project.
2. Right-click two files in the Project view → **Compare Files**. (Or
   View → Recent Changes → pick a changeset.) You need a **two-side** text
   diff — unified and three-way viewers are intentionally skipped.
3. Look at the right-hand gutter on line 1: there should be a small blue
   speech-bubble icon.
4. Click it. An input dialog "Comment on line 1" appears.
5. Type anything, click OK.
6. Open **Help → Show Log in Finder**, tail `idea.log`, grep for
   `SpikeDiffExtension` — you should see `Spike gutter click; user input: ...`.

## Shortcut cheat-sheet

Press **⌃⇧/** (Ctrl+Shift+/) to open a read-only cheat-sheet of curated IDE
shortcuts. The set lives in `src/main/resources/shortcuts/shortcuts.yml` —
each entry names an IntelliJ action ID and the keys are read live from your
active keymap. Toggle `enabled:` on an entry to show/hide it, then rebuild
(`./gradlew prepareSandbox`) and restart the IDE.

> **Note:** The ⌃⇧/ keystroke is macOS-only; on Windows/Linux the `$default` binding was dropped (it collides with Comment with Block Comment). Open the cheat-sheet from the **Help** menu or via **Find Action** instead.

## Caveats discovered while writing

- `DiffExtension.onViewerCreated` is the documented hook; signature in 2024.2
  is `(FrameDiffTool.DiffViewer, DiffContext, DiffRequest)`. Earlier feasibility
  notes sometimes show it as `(DiffViewer, ...)` without the `FrameDiffTool.`
  qualifier — they're the same type.
- `TwosideTextDiffViewer.getEditor(Side)` returns `EditorEx` directly in 2024.2;
  no cast needed.
- `RangeHighlighter#setGutterIconRenderer` is the right call — the
  highlighter must exist first, and the icon is attached afterward.
- The icon path passed to `IconLoader.getIcon` must start with `/` and be
  resolved against the classloader of the second argument; that's why we pass
  `SpikeDiffExtension.class`.
- `GutterIconRenderer` requires `equals`/`hashCode` overrides or IntelliJ
  complains at runtime; we use class-identity equality, which is fine for a
  spike but will need a real implementation once we have per-line state.
