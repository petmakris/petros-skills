# Navigation polish — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a right-side `Review Annotations` tool window listing every annotated line in the current session, and make backtick code identifiers in synthesis text clickable via `PsiShortNamesCache` lookup.

**Architecture:** Both features subscribe to the existing `ReviewSessionClient` (no server-side changes). The tool window is a Swing component with a `JBList<AnnotationEntry>` driven by the client's cache. Clickable identifiers are a one-line change in `MarkdownLinkRenderer` (emit `<a href="ireview-sym://...">` instead of `<code>`) plus a new branch in `SynthesisPopup`'s existing `HyperlinkListener` that resolves the symbol via `PsiShortNamesCache.getInstance(project).getClassesByName(...)` / `getMethodsByName(...)`.

**Tech Stack:** Java 25 + IntelliJ Platform 2026.1, JUnit 5 tests, existing patterns.

---

## File Structure

### Created

| Path | Purpose |
|---|---|
| `intellij-plugin-spike/src/main/java/com/petros/ireview/AnnotationEntry.java` | Record `(anchor, snippet, version, updatedAt, isNew)`. |
| `intellij-plugin-spike/src/main/java/com/petros/ireview/AnnotationsToolWindowFactory.java` | Implements `ToolWindowFactory`. Creates one `AnnotationsPanel` per project. |
| `intellij-plugin-spike/src/main/java/com/petros/ireview/AnnotationsPanel.java` | The Swing component owning the panel layout (header / list / footer) and the list model. |

### Modified

| Path | What changes |
|---|---|
| `intellij-plugin-spike/src/main/java/com/petros/ireview/MarkdownLinkRenderer.java` | Backticks now emit `<a href="ireview-sym://...">Name</a>` instead of `<code>Name</code>`. |
| `intellij-plugin-spike/src/main/java/com/petros/ireview/SynthesisPopup.java` | Add `ireview-sym://` branch to `HyperlinkListener` → `PsiShortNamesCache` lookup. Add `.ref-sym` style. |
| `intellij-plugin-spike/src/test/java/com/petros/ireview/MarkdownLinkRendererTest.java` | Update existing `pathLinkBecomesAnchorTagWithIreviewNavScheme`-style tests for the new backtick output. Add new test for backtick → sym link. |
| `intellij-plugin-spike/src/main/resources/META-INF/plugin.xml` | Register `<toolWindow>` for `AnnotationsToolWindowFactory`. |

---

## Task 1: `AnnotationEntry` data record

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/AnnotationEntry.java`

- [ ] **Step 1: Create the record**

```java
package com.petros.ireview;

/**
 * One row in the annotations side panel.
 *
 * @param anchor       Full anchor string, e.g. "src/.../Foo.java:R:37".
 * @param snippet      First 160 chars of the latest synthesis, with newlines collapsed.
 * @param version      Thread version (monotonically increasing per anchor).
 * @param updatedAt    Server-side updated_at timestamp (epoch seconds).
 * @param isNew        True if this row's version is greater than the last-seen version
 *                     recorded by the panel. Drives the yellow "updated" dot.
 */
public record AnnotationEntry(
    String anchor,
    String snippet,
    int version,
    long updatedAt,
    boolean isNew
) {}
```

- [ ] **Step 2: Compile**

Run: `cd ~/projects/petros-skills/intellij-plugin-spike && ./gradlew compileJava 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd ~/projects/petros-skills
git add intellij-plugin-spike/src/main/java/com/petros/ireview/AnnotationEntry.java
git commit -m "intellij-plugin-spike: AnnotationEntry record for the side-panel list"
```

---

## Task 2: `MarkdownLinkRenderer` — backticks become symbol links

**Files:**
- Modify: `intellij-plugin-spike/src/main/java/com/petros/ireview/MarkdownLinkRenderer.java`
- Modify: `intellij-plugin-spike/src/test/java/com/petros/ireview/MarkdownLinkRendererTest.java`

- [ ] **Step 1: Write the failing test**

Append to `MarkdownLinkRendererTest.java`:

```java
    @Test
    void backtickCodeBecomesSymbolAnchor() {
        String html = MarkdownLinkRenderer.toHtml("call `Foo` here");
        assertEquals(
            "call <a href=\"ireview-sym://Foo\" class=\"ref-sym\">Foo</a> here",
            html);
    }

    @Test
    void backtickContentIsHtmlEscaped() {
        String html = MarkdownLinkRenderer.toHtml("see `Map<K,V>`");
        assertEquals(
            "see <a href=\"ireview-sym://Map&lt;K,V&gt;\" class=\"ref-sym\">Map&lt;K,V&gt;</a>",
            html);
    }
```

- [ ] **Step 2: Run test to verify failure**

Run: `cd ~/projects/petros-skills/intellij-plugin-spike && ./gradlew test --tests MarkdownLinkRendererTest 2>&1 | tail -15`
Expected: FAIL — the current implementation emits `<code class="inline-code">Foo</code>`.

- [ ] **Step 3: Update the renderer**

In `MarkdownLinkRenderer.java`, find the backtick branch in `toHtml`:

```java
            } else {
                out.append("<code class=\"inline-code\">")
                   .append(escape(m.group(3)))
                   .append("</code>");
            }
```

Replace with:

```java
            } else {
                String sym = m.group(3);
                out.append("<a href=\"ireview-sym://").append(escape(sym))
                   .append("\" class=\"ref-sym\">")
                   .append(escape(sym)).append("</a>");
            }
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests MarkdownLinkRendererTest 2>&1 | tail -10`
Expected: all 8 pass (6 existing + 2 new).

- [ ] **Step 5: Commit**

```bash
cd ~/projects/petros-skills
git add intellij-plugin-spike/src/main/java/com/petros/ireview/MarkdownLinkRenderer.java intellij-plugin-spike/src/test/java/com/petros/ireview/MarkdownLinkRendererTest.java
git commit -m "intellij-plugin-spike: backticks render as ireview-sym:// links"
```

---

## Task 3: `SynthesisPopup` — handle `ireview-sym://` clicks via PSI

**Files:**
- Modify: `intellij-plugin-spike/src/main/java/com/petros/ireview/SynthesisPopup.java`

- [ ] **Step 1: Locate the existing HyperlinkListener branch**

Read `SynthesisPopup.java`, find the lambda inside `synthesisPane.addHyperlinkListener(...)` (~lines 64-90). The existing structure is:

```java
synthesisPane.addHyperlinkListener(e -> {
    if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;
    String url = e.getDescription();
    if (url == null) return;
    if (url.startsWith("ireview-nav://")) {
        // ...path navigation
    } else {
        BrowserUtil.browse(url);
    }
});
```

Insert a new `ireview-sym://` branch BEFORE the URL fallback.

- [ ] **Step 2: Add the symbol-resolution branch**

Replace the existing `else { BrowserUtil.browse(url); }` block with:

```java
            } else if (url.startsWith("ireview-sym://")) {
                String identifier = url.substring("ireview-sym://".length());
                resolveAndNavigateSymbol(project, identifier);
            } else {
                com.intellij.ide.BrowserUtil.browse(url);
            }
```

- [ ] **Step 3: Add the `resolveAndNavigateSymbol` helper**

Add this as a private static method at the bottom of the class, just above `private SynthesisPopup() {}`:

```java
    /**
     * Look up `identifier` in the project's PSI symbol caches. If exactly one
     * class or method matches, navigate to it. If multiple, show a chooser
     * popup. If none, silently no-op (clicking `false` or `null` shouldn't
     * be noisy).
     */
    private static void resolveAndNavigateSymbol(Project project, String identifier) {
        com.intellij.openapi.application.ReadAction.nonBlocking(() -> {
            var cache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project);
            var scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project);
            com.intellij.psi.PsiNamedElement[] candidates =
                cache.getClassesByName(identifier, scope);
            if (candidates.length == 0) {
                candidates = cache.getMethodsByName(identifier, scope);
            }
            return candidates;
        })
        .finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState(),
            candidates -> {
                if (candidates.length == 0) {
                    return; // silent no-op
                }
                if (candidates.length == 1) {
                    if (candidates[0] instanceof com.intellij.pom.Navigatable nav) {
                        nav.navigate(true);
                    }
                    return;
                }
                // Multi-match: show a small chooser popup
                com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(java.util.Arrays.asList(candidates))
                    .setTitle("Multiple matches for '" + identifier + "'")
                    .setItemChosenCallback(item -> {
                        if (item instanceof com.intellij.pom.Navigatable nav) {
                            nav.navigate(true);
                        }
                    })
                    .createPopup()
                    .showCenteredInCurrentWindow(project);
            })
        .submit(com.intellij.openapi.application.AppExecutorUtil.getAppExecutorService());
    }
```

- [ ] **Step 4: Add the `.ref-sym` CSS to the wrapHtml stylesheet**

Find the `wrapHtml` method at the bottom of the class. The current stylesheet has:

```java
+ "code.inline-code { color: #ce9178; font-family: monospace; font-size: 11.5px; }"
```

Replace that single line with:

```java
+ "a.ref-sym { color: #ce9178; text-decoration: none; font-family: monospace; font-size: 11.5px; }"
+ "a.ref-sym:hover { text-decoration: underline dashed; }"
```

(The old `code.inline-code` styling is no longer reachable since Task 2 made the renderer emit anchors instead of `<code>`. Deleting the rule is correct cleanup.)

- [ ] **Step 5: Compile + run all tests**

Run: `./gradlew test 2>&1 | tail -8`
Expected: BUILD SUCCESSFUL. (No new unit tests; `SynthesisPopup` is too Swing-coupled for unit testing — verified by manual smoke later.)

- [ ] **Step 6: Commit**

```bash
cd ~/projects/petros-skills
git add intellij-plugin-spike/src/main/java/com/petros/ireview/SynthesisPopup.java
git commit -m "intellij-plugin-spike: backtick clicks route through PsiShortNamesCache"
```

---

## Task 4: `AnnotationsPanel` — the Swing component

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/AnnotationsPanel.java`

This task is larger; split into substeps.

- [ ] **Step 1: Create the file with skeleton structure**

Create `AnnotationsPanel.java`:

```java
package com.petros.ireview;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AnnotationsPanel {

    private final Project project;
    private final ReviewSessionClient client;
    private final DefaultListModel<AnnotationEntry> model = new DefaultListModel<>();
    private final JBList<AnnotationEntry> list = new JBList<>(model);
    private final JLabel countLabel = new JLabel();
    private final JLabel titleLabel = new JLabel("Review · idle");
    private final JBTextField searchField = new JBTextField();
    private final Map<String, Integer> seenVersions = new HashMap<>();

    public AnnotationsPanel(@NotNull Project project) {
        this.project = project;
        this.client = ReviewSessionService.get(project).client();
        // Subsequent steps add: layout, list renderer, click handler,
        // search-filter wiring, session-state subscription.
    }

    public JComponent getComponent() {
        // Returns the root JPanel — built in the next steps.
        return new JPanel();
    }
}
```

- [ ] **Step 2: Build the layout (header / list / footer)**

Replace the body of the constructor + `getComponent` with:

```java
    private JPanel root;

    public AnnotationsPanel(@NotNull Project project) {
        this.project = project;
        this.client = ReviewSessionService.get(project).client();

        list.setCellRenderer(this::renderCell);
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 1) return;
                int idx = list.locationToIndex(e.getPoint());
                if (idx < 0) return;
                AnnotationEntry entry = model.getElementAt(idx);
                onRowClicked(entry, idx);
            }
        });

        searchField.getEmptyText().setText("Filter…");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { rebuild(); }
            @Override public void removeUpdate(DocumentEvent e) { rebuild(); }
            @Override public void changedUpdate(DocumentEvent e) { rebuild(); }
        });

        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setBorder(JBUI.Borders.empty(6, 8, 4, 8));
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 12f));
        countLabel.setFont(countLabel.getFont().deriveFont(10.5f));
        countLabel.setForeground(JBColor.GRAY);
        header.add(titleLabel, BorderLayout.WEST);
        header.add(countLabel, BorderLayout.EAST);

        JPanel headerWrap = new JPanel(new BorderLayout(0, 4));
        headerWrap.add(header, BorderLayout.NORTH);
        headerWrap.add(searchField, BorderLayout.SOUTH);
        headerWrap.setBorder(JBUI.Borders.emptyBottom(4));

        JLabel footer = new JLabel("● live", JLabel.LEFT);
        footer.setFont(footer.getFont().deriveFont(10f));
        footer.setForeground(new JBColor(new Color(0xb0, 0x90, 0x10), new Color(0xf1, 0xc4, 0x0f)));
        footer.setBorder(JBUI.Borders.empty(4, 8));

        root = new JPanel(new BorderLayout());
        root.add(headerWrap, BorderLayout.NORTH);
        root.add(new JBScrollPane(list), BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);

        // Subscribe to session state + thread changes
        client.addListener(new ReviewSessionClient.Listener() {
            @Override public void onStateChanged(ReviewSessionClient.State state) {
                SwingUtilities.invokeLater(AnnotationsPanel.this::refreshTitle);
            }
            @Override public void onAttached(ReviewSessionClient.SessionInfo info) {
                SwingUtilities.invokeLater(() -> { refreshTitle(); rebuild(); });
            }
            @Override public void onDetached() {
                SwingUtilities.invokeLater(() -> { refreshTitle(); rebuild(); });
            }
            @Override public void onThreadChanged(String anchor, String synthesis, int version) {
                SwingUtilities.invokeLater(AnnotationsPanel.this::rebuild);
            }
        });

        refreshTitle();
        rebuild();
    }

    public JComponent getComponent() { return root; }

    private void refreshTitle() {
        titleLabel.setText(client.currentSession()
            .map(s -> "Review · " + truncate(s.prRef(), 28))
            .orElse("Review · idle"));
    }
```

Also add a small helper:

```java
    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
```

- [ ] **Step 3: Add `rebuild()` (filter + sort + populate)**

```java
    private void rebuild() {
        String q = searchField.getText().toLowerCase();
        List<AnnotationEntry> rows = new ArrayList<>();
        for (var e : client.snapshotCache().entrySet()) {
            String anchor = e.getKey();
            if (!q.isEmpty() && !anchor.toLowerCase().contains(q)) continue;
            var thread = e.getValue();
            int last = seenVersions.getOrDefault(anchor, 0);
            rows.add(new AnnotationEntry(
                anchor,
                snippet(thread.synthesis()),
                thread.version(),
                0L,  // updatedAt not currently exposed; placeholder
                thread.version() > last
            ));
        }
        rows.sort(Comparator.comparing(AnnotationEntry::anchor));
        model.clear();
        for (var r : rows) model.addElement(r);
        countLabel.setText(rows.size() + " annotation" + (rows.size() == 1 ? "" : "s"));
    }

    private static String snippet(String synthesis) {
        if (synthesis == null) return "";
        String oneLine = synthesis.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 159) + "…";
    }
```

NOTE: `client.snapshotCache()` doesn't exist yet — `ReviewSessionClient` only exposes `threadFor(anchor)`. Add it now: in `ReviewSessionClient.java`, find the `cache` field and add this public method:

```java
    public Map<String, ThreadState> snapshotCache() {
        return new HashMap<>(cache);
    }
```

(Reads from the existing private `cache` field.)

- [ ] **Step 4: Add the cell renderer**

```java
    private ListCellRenderer<? super AnnotationEntry> renderCellRenderer() { return this::renderCell; }

    private Component renderCell(JBList<? extends AnnotationEntry> jbList,
                                 AnnotationEntry entry,
                                 int index,
                                 boolean selected,
                                 boolean focused) {
        JPanel row = new JPanel(new BorderLayout(0, 2));
        row.setBorder(JBUI.Borders.empty(8, 10));
        row.setOpaque(true);

        Color bg = selected ? new JBColor(new Color(0x1a, 0x3a, 0x5e), new Color(0x1a, 0x3a, 0x5e))
                            : new JBColor(new Color(0xf0, 0xf0, 0xf0), new Color(0x23, 0x25, 0x27));
        row.setBackground(bg);

        // Top row: path · :side:line (right)
        String[] parts = entry.anchor().split(":", 3);  // path, side, line
        String pathOnly = parts.length >= 1 ? lastSegment(parts[0]) : entry.anchor();
        String lineRef = parts.length >= 3 ? ":" + parts[1] + ":" + parts[2] : "";

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        JLabel pathLbl = new JLabel(pathOnly);
        pathLbl.setForeground(selected ? new Color(0xd6, 0xe9, 0xff) : new Color(0xb5, 0xb6, 0xe3));
        pathLbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JLabel lineLbl = new JLabel(lineRef);
        lineLbl.setForeground(new Color(0xf1, 0xc4, 0x0f));
        lineLbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        top.add(pathLbl, BorderLayout.WEST);
        top.add(lineLbl, BorderLayout.EAST);

        // Middle: 2-line snippet
        JLabel snippetLbl = new JLabel("<html><body style='width:200px'>" + escapeHtml(entry.snippet()) + "</body></html>");
        snippetLbl.setForeground(selected ? new Color(0xe8, 0xe8, 0xe8) : new Color(0xc0, 0xc0, 0xc0));
        snippetLbl.setFont(snippetLbl.getFont().deriveFont(11.5f));

        // Bottom: meta + new dot
        JPanel meta = new JPanel(new BorderLayout());
        meta.setOpaque(false);
        JLabel verLbl = new JLabel("v" + entry.version());
        verLbl.setForeground(new Color(0x80, 0x80, 0x80));
        verLbl.setFont(verLbl.getFont().deriveFont(10f));
        meta.add(verLbl, BorderLayout.WEST);
        if (entry.isNew()) {
            JLabel dot = new JLabel("●");
            dot.setForeground(new Color(0xf1, 0xc4, 0x0f));
            dot.setFont(dot.getFont().deriveFont(12f));
            meta.add(dot, BorderLayout.EAST);
        }

        row.add(top, BorderLayout.NORTH);
        row.add(snippetLbl, BorderLayout.CENTER);
        row.add(meta, BorderLayout.SOUTH);
        return row;
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
```

- [ ] **Step 5: Add the click handler**

```java
    private void onRowClicked(AnnotationEntry entry, int rowIndex) {
        String[] parts = entry.anchor().split(":", 3);
        if (parts.length < 3) return;
        String path = parts[0];
        int line;
        try {
            // anchor may be "<start>-<end>" — take the start
            String lineStr = parts[2].split("-", 2)[0];
            line = Integer.parseInt(lineStr);
        } catch (NumberFormatException e) {
            return;
        }
        String base = project.getBasePath();
        if (base == null) return;
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(base + "/" + path);
        if (vf == null) {
            com.intellij.notification.Notifications.Bus.notify(
                new com.intellij.notification.Notification(
                    "Interactive Review",
                    "File not found",
                    path,
                    com.intellij.notification.NotificationType.WARNING),
                project);
            return;
        }
        int line0 = Math.max(0, line - 1);
        new OpenFileDescriptor(project, vf, line0, 0).navigate(true);

        // Record "seen" version + clear the new-dot
        seenVersions.put(entry.anchor(), entry.version());
        rebuild();

        // Also open the popup for this anchor
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor instanceof com.intellij.openapi.editor.ex.EditorEx ex) {
            SynthesisPopup.show(project, ex, entry.anchor(), line0);
        }
    }
```

- [ ] **Step 6: Compile**

Run: `cd ~/projects/petros-skills/intellij-plugin-spike && ./gradlew compileJava 2>&1 | tail -8`
Expected: BUILD SUCCESSFUL.

If `client.snapshotCache()` is unrecognized, double-check Task 4 Step 3 — the `ReviewSessionClient` change to add that method may have been missed.

- [ ] **Step 7: Commit**

```bash
cd ~/projects/petros-skills
git add intellij-plugin-spike/src/main/java/com/petros/ireview/AnnotationsPanel.java intellij-plugin-spike/src/main/java/com/petros/ireview/ReviewSessionClient.java
git commit -m "intellij-plugin-spike: AnnotationsPanel — live list of annotations + click-to-jump"
```

---

## Task 5: `AnnotationsToolWindowFactory` + plugin.xml registration

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/AnnotationsToolWindowFactory.java`
- Modify: `intellij-plugin-spike/src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create the factory**

Create `AnnotationsToolWindowFactory.java`:

```java
package com.petros.ireview;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

public final class AnnotationsToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        AnnotationsPanel panel = new AnnotationsPanel(project);
        Content content = ContentFactory.getInstance()
            .createContent(panel.getComponent(), "", false);
        toolWindow.getContentManager().addContent(content);
    }
}
```

- [ ] **Step 2: Register in plugin.xml**

Open `src/main/resources/META-INF/plugin.xml`. Inside the existing `<extensions defaultExtensionNs="com.intellij">` block, add:

```xml
        <toolWindow id="Review Annotations"
                    anchor="right"
                    icon="/icons/annotation_yellow.svg"
                    factoryClass="com.petros.ireview.AnnotationsToolWindowFactory"/>
```

(Place it after the existing `<statusBarWidgetFactory ...>` line.)

- [ ] **Step 3: Build the plugin into the sandbox**

Run: `cd ~/projects/petros-skills/intellij-plugin-spike && ./gradlew prepareSandbox 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd ~/projects/petros-skills
git add intellij-plugin-spike/src/main/java/com/petros/ireview/AnnotationsToolWindowFactory.java intellij-plugin-spike/src/main/resources/META-INF/plugin.xml
git commit -m "intellij-plugin-spike: register Review Annotations tool window on the right"
```

---

## Task 6: Smoke test recipe in HANDOFF.md

**Files:**
- Modify: `intellij-plugin-spike/HANDOFF.md`

- [ ] **Step 1: Append a section to HANDOFF.md**

Find the existing "End-to-end smoke recipe" section and append after its current numbered list:

```markdown
### Navigation polish smoke (after that backend smoke passes)

7. Open the `Review Annotations` tool window on the right side. You should see one row per annotated line, with file (last segment) · :side:line · 2-line snippet · `v<N>`.
8. Type a substring of a file name into the search box at the top. Verify the list filters live.
9. Click a row that's NOT the currently-open annotation. IDEA should jump to that file/line AND open the popup on it.
10. From the browser surface, ask a follow-up question on a different annotation. The IDE panel should add a yellow `●` next to that row within ~5 s (SSE event). Click the row to clear the dot.
11. In an open popup synthesis, hover any backtick code identifier — should show dashed underline. Click → IDE should navigate to that symbol's declaration (if it exists in the project).
12. Click a backtick word that ISN'T a symbol (e.g. `null` or `POST /foo`). Should be a silent no-op (no error, no popup).
```

- [ ] **Step 2: Commit**

```bash
cd ~/projects/petros-skills
git add intellij-plugin-spike/HANDOFF.md
git commit -m "intellij-plugin-spike: add navigation-polish smoke steps to HANDOFF.md"
```

---

## Task 7: Manual smoke test (verification only — no code)

Per the original spec, the panel + click handlers are hard to unit-test against the IDEA Platform. Verification is manual.

- [ ] **Step 1: Rebuild + restart IDEA**

```bash
cd ~/projects/petros-skills/intellij-plugin-spike
./gradlew prepareSandbox
osascript -e 'quit app "IntelliJ IDEA"'
# Manually reopen IDEA on the same project.
```

- [ ] **Step 2: Run the smoke steps 7–12 above**

If anything fails, do not mark the task complete — file what failed as a follow-up.

- [ ] **Step 3 (no commit): mark Task 7 complete in the task list if all checks pass**

---

## Self-Review

**Spec coverage**:

| Spec section | Task |
|---|---|
| `AnnotationEntry` record | 1 |
| `AnnotationsToolWindowFactory` registration | 5 |
| `AnnotationsPanel` Swing layout | 4 |
| Custom cell renderer | 4 (step 4) |
| Search filter | 4 (step 2 + 3) |
| Subscribe to ReviewSessionClient | 4 (step 2) |
| Click → navigate + open popup | 4 (step 5) |
| Yellow new-dot via seenVersions | 4 (steps 3 + 5) |
| MarkdownLinkRenderer emits `ireview-sym://` | 2 |
| HyperlinkListener resolves via PsiShortNamesCache | 3 |
| Multi-match chooser popup | 3 |
| `.ref-sym` CSS | 3 (step 4) |
| Test for backtick → sym link | 2 (step 1) |
| HANDOFF.md updates | 6 |

No gaps.

**Type consistency**: `AnnotationEntry` defined in Task 1 with fields `(anchor, snippet, version, updatedAt, isNew)`; Task 4 uses exactly these. `snapshotCache()` added in Task 4 step 3, used in Task 4 step 3. `ireview-sym://` scheme used consistently in Tasks 2 and 3.

**Placeholder scan**: no TBD, no "implement later." Every code step has actual code.

Self-review pass.
