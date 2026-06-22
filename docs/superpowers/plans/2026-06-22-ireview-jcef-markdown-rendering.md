# JCEF Markdown Rendering in Synthesis Popup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render Claude's synthesis as real HTML (tables, lists, code, headings) in the `SynthesisPopup` by parsing markdown with commonmark-java and displaying it in an embedded JCEF browser, with a graceful fallback to the existing renderer.

**Architecture:** A pure `SynthesisHtmlRenderer` converts markdown → themed HTML, preserving the two custom link behaviors (`ireview-nav://` path jumps, `ireview-sym://` symbol jumps). `SynthesisBrowser` wraps `JBCefBrowser` + a `JBCefJSQuery` click bridge. `SynthesisLinkRouter` (extracted from the popup) routes clicked hrefs to IDE navigation and is shared by both the JCEF and fallback paths. `SynthesisPopup` picks JCEF when `JBCefApp.isSupported()`, else uses the unchanged `JEditorPane` + `MarkdownLinkRenderer`.

**Tech Stack:** Java 25, IntelliJ Platform Gradle plugin 2.x, JCEF (bundled), commonmark-java 0.24.0 + commonmark-ext-gfm-tables, JUnit 5.

## Global Constraints

- Java toolchain: `JavaLanguageVersion.of(25)` (unchanged).
- New runtime deps must be `implementation` so the IntelliJ Platform plugin bundles them into the plugin jar.
- commonmark version pinned to `0.24.0` for both `commonmark` and `commonmark-ext-gfm-tables`.
- Custom link schemes are exactly `ireview-nav://` and `ireview-sym://` (must match `MarkdownLinkRenderer.parseNavTarget` and existing behavior).
- Nothing in `MarkdownLinkRenderer` or `AnnotationsPanel` is deleted; the fallback path must keep working.
- Tests run with `./gradlew test` from `intellij-plugin-spike/`. Manual UI checks run with `./gradlew runIde`.
- All new sources live in `intellij-plugin-spike/src/main/java/com/petros/ireview/`; tests in `intellij-plugin-spike/src/test/java/com/petros/ireview/`.

---

### Task 1: Add commonmark dependencies

**Files:**
- Modify: `intellij-plugin-spike/build.gradle.kts` (the second `dependencies { }` block, currently holding the JUnit deps)

**Interfaces:**
- Consumes: nothing.
- Produces: `org.commonmark.parser.Parser`, `org.commonmark.renderer.html.HtmlRenderer`, `org.commonmark.ext.gfm.tables.TablesExtension` available on the main + test classpath.

- [ ] **Step 1: Add the dependencies**

In `intellij-plugin-spike/build.gradle.kts`, find the second dependencies block:

```kotlin
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
}
```

Replace it with:

```kotlin
dependencies {
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
}
```

- [ ] **Step 2: Verify the dependencies resolve**

Run: `cd intellij-plugin-spike && ./gradlew dependencies --configuration runtimeClasspath` (or `./gradlew compileJava`)
Expected: build succeeds; `org.commonmark:commonmark:0.24.0` and `commonmark-ext-gfm-tables:0.24.0` appear in the resolved classpath.

- [ ] **Step 3: Commit**

```bash
git add intellij-plugin-spike/build.gradle.kts
git commit -m "build(ireview): add commonmark-java + gfm-tables for markdown rendering"
```

---

### Task 2: SynthesisHtmlRenderer (pure markdown → HTML)

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/SynthesisHtmlRenderer.java`
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/SynthesisHtmlRendererTest.java`

**Interfaces:**
- Consumes: commonmark-java (Task 1).
- Produces:
  - `String SynthesisHtmlRenderer.toBodyHtml(String markdown)` — inner `<body>` HTML; pure.
  - `String SynthesisHtmlRenderer.toDocument(String markdown, SynthesisHtmlRenderer.Theme theme, String navScriptOrNull)` — full `<html>` doc; pure.
  - `record SynthesisHtmlRenderer.Theme(String background, String foreground, String fontFamily, int fontSize, String accent, String codeBackground, String border)`.

- [ ] **Step 1: Write the failing tests**

Create `intellij-plugin-spike/src/test/java/com/petros/ireview/SynthesisHtmlRendererTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SynthesisHtmlRendererTest {

    @Test
    void gfmTableRendersAsTableElement() {
        String md = "| a | b |\n|---|---|\n| 1 | 2 |\n";
        String html = SynthesisHtmlRenderer.toBodyHtml(md);
        assertTrue(html.contains("<table"), html);
        assertTrue(html.contains("<th"), html);
        assertTrue(html.contains("<td"), html);
    }

    @Test
    void pathLinkIsRewrittenToIreviewNavScheme() {
        String html = SynthesisHtmlRenderer.toBodyHtml("[Foo](src/Foo.java:18)");
        assertTrue(html.contains("href=\"ireview-nav://src/Foo.java:18\""), html);
    }

    @Test
    void httpLinkStaysExternal() {
        String html = SynthesisHtmlRenderer.toBodyHtml("[t](https://example.com/x)");
        assertTrue(html.contains("href=\"https://example.com/x\""), html);
        assertFalse(html.contains("ireview-nav://"), html);
    }

    @Test
    void inlineCodeBecomesSymbolLink() {
        String html = SynthesisHtmlRenderer.toBodyHtml("call `Foo` now");
        assertTrue(html.contains("href=\"ireview-sym://Foo\""), html);
        assertTrue(html.contains("<code>Foo</code>"), html);
    }

    @Test
    void inlineCodeWithAngleBracketsIsEscaped() {
        String html = SynthesisHtmlRenderer.toBodyHtml("see `Map<K,V>`");
        assertTrue(html.contains("ireview-sym://Map&lt;K,V&gt;"), html);
        assertTrue(html.contains("<code>Map&lt;K,V&gt;</code>"), html);
    }

    @Test
    void inlineCodeWithDoubleQuoteIsEscapedInAttribute() {
        String html = SynthesisHtmlRenderer.toBodyHtml("via `f(\"x\")`");
        assertTrue(html.contains("ireview-sym://f(&quot;x&quot;)"), html);
    }

    @Test
    void fencedCodeBlockIsPreAndNotASymbolLink() {
        String html = SynthesisHtmlRenderer.toBodyHtml("```\nint x = 1;\n```\n");
        assertTrue(html.contains("<pre>"), html);
        assertFalse(html.contains("ireview-sym://"), html);
    }

    @Test
    void unorderedListRenders() {
        String html = SynthesisHtmlRenderer.toBodyHtml("- one\n- two\n");
        assertTrue(html.contains("<ul>"), html);
        assertTrue(html.contains("<li>one</li>"), html);
    }

    @Test
    void headingRenders() {
        String html = SynthesisHtmlRenderer.toBodyHtml("# Title\n");
        assertTrue(html.contains("<h1>Title</h1>"), html);
    }

    @Test
    void emptyMarkdownIsEmptyBody() {
        assertEquals("", SynthesisHtmlRenderer.toBodyHtml(""));
        assertEquals("", SynthesisHtmlRenderer.toBodyHtml(null));
    }

    @Test
    void documentWrapsBodyWithThemeAndScript() {
        SynthesisHtmlRenderer.Theme theme = new SynthesisHtmlRenderer.Theme(
            "#1e1f22", "#d8d8d8", "monospace", 13, "#3b72e8", "#2b2d30", "#393b40");
        String doc = SynthesisHtmlRenderer.toDocument("# Hi\n", theme, "console.log(1);");
        assertTrue(doc.contains("<html>"), doc);
        assertTrue(doc.contains("<h1>Hi</h1>"), doc);
        assertTrue(doc.contains("#1e1f22"), doc);
        assertTrue(doc.contains("font-size: 13px"), doc);
        assertTrue(doc.contains("<script>console.log(1);</script>"), doc);
    }

    @Test
    void documentOmitsScriptTagWhenNull() {
        SynthesisHtmlRenderer.Theme theme = new SynthesisHtmlRenderer.Theme(
            "#000", "#fff", "monospace", 12, "#00f", "#111", "#222");
        String doc = SynthesisHtmlRenderer.toDocument("hi", theme, null);
        assertFalse(doc.contains("<script>"), doc);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.SynthesisHtmlRendererTest"`
Expected: FAIL — compilation error, `SynthesisHtmlRenderer` does not exist.

- [ ] **Step 3: Write the implementation**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/SynthesisHtmlRenderer.java`:

```java
package com.petros.ireview;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Code;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts synthesis markdown into HTML for the JCEF popup.
 *
 * Two custom rules preserve the popup's existing link behavior:
 *  - a markdown link whose destination is NOT http(s) is rewritten to the
 *    ireview-nav:// scheme (file:line navigation);
 *  - inline `code` is wrapped in an ireview-sym:// anchor (symbol lookup).
 *
 * Everything else (tables, lists, headings, fenced blocks, emphasis) comes
 * from commonmark-java's standard renderers. Pure: no IntelliJ dependencies,
 * so it is fully unit-testable.
 */
public final class SynthesisHtmlRenderer {

    /** Theme colors/font sourced from the IDE editor scheme by the caller. */
    public record Theme(String background, String foreground, String fontFamily,
                        int fontSize, String accent, String codeBackground,
                        String border) {}

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());

    private static final Parser PARSER = Parser.builder()
            .extensions(EXTENSIONS)
            .build();

    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .attributeProviderFactory(ctx -> new NavLinkAttributeProvider())
            .nodeRendererFactory(SymbolCodeRenderer::new)
            .build();

    public static String toBodyHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        Node doc = PARSER.parse(markdown);
        return RENDERER.render(doc);
    }

    public static String toDocument(String markdown, Theme theme, String navScript) {
        String body = toBodyHtml(markdown);
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><style>");
        sb.append(css(theme));
        sb.append("</style></head><body>");
        sb.append(body);
        if (navScript != null && !navScript.isEmpty()) {
            sb.append("<script>").append(navScript).append("</script>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String css(Theme t) {
        return "html,body{margin:0;padding:0;}"
             + "body{background:" + t.background() + ";color:" + t.foreground() + ";"
             +   "font-family:" + t.fontFamily() + ",monospace;font-size: " + t.fontSize() + "px;"
             +   "line-height:1.45;padding:6px 8px;word-wrap:break-word;}"
             + "a{color:" + t.accent() + ";text-decoration:none;}"
             + "a:hover{text-decoration:underline;}"
             + "code{background:" + t.codeBackground() + ";border-radius:3px;padding:0 3px;}"
             + "pre{background:" + t.codeBackground() + ";border:1px solid " + t.border() + ";"
             +   "border-radius:4px;padding:8px 10px;overflow:auto;}"
             + "pre code{background:transparent;padding:0;}"
             + "table{border-collapse:collapse;margin:6px 0;}"
             + "th,td{border:1px solid " + t.border() + ";padding:4px 8px;text-align:left;}"
             + "th{background:" + t.codeBackground() + ";}"
             + "h1,h2,h3{margin:8px 0 4px;}"
             + "ul,ol{margin:4px 0;padding-left:22px;}"
             + "blockquote{margin:6px 0;padding-left:10px;border-left:3px solid " + t.border() + ";color:" + t.foreground() + ";}";
    }

    /** Rewrites non-http link destinations to the ireview-nav:// scheme. */
    private static final class NavLinkAttributeProvider implements AttributeProvider {
        @Override
        public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
            if (!(node instanceof Link)) return;
            String href = attributes.get("href");
            if (href == null) return;
            if (href.startsWith("http://") || href.startsWith("https://")) return;
            attributes.put("href", "ireview-nav://" + href);
        }
    }

    /** Renders inline code as a clickable symbol-lookup link. */
    private static final class SymbolCodeRenderer implements NodeRenderer {
        private final HtmlWriter html;

        SymbolCodeRenderer(HtmlNodeRendererContext context) {
            this.html = context.getWriter();
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Code.class);
        }

        @Override
        public void render(Node node) {
            String literal = ((Code) node).getLiteral();
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("href", "ireview-sym://" + literal);
            html.tag("a", attrs);
            html.tag("code");
            html.text(literal);
            html.tag("/code");
            html.tag("/a");
        }
    }

    private SynthesisHtmlRenderer() {}
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.SynthesisHtmlRendererTest"`
Expected: PASS (all 12 tests).

Note on escaping: commonmark's `HtmlWriter.tag(name, attrs)` HTML-escapes attribute values (`&`, `<`, `>`, `"`), and `html.text(...)` escapes element text — this is why the `<`/`"` tests pass without manual escaping.

- [ ] **Step 5: Commit**

```bash
git add intellij-plugin-spike/src/main/java/com/petros/ireview/SynthesisHtmlRenderer.java intellij-plugin-spike/src/test/java/com/petros/ireview/SynthesisHtmlRendererTest.java
git commit -m "feat(ireview): SynthesisHtmlRenderer — commonmark markdown to themed HTML"
```

---

### Task 3: SynthesisLinkRouter (extract + share routing)

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/SynthesisLinkRouter.java`
- Test: `intellij-plugin-spike/src/test/java/com/petros/ireview/SynthesisLinkRouterTest.java`

**Interfaces:**
- Consumes: `MarkdownLinkRenderer.parseNavTarget` (existing); IntelliJ navigation APIs.
- Produces:
  - `enum SynthesisLinkRouter.Kind { NAV, SYM, EXTERNAL, NONE }`
  - `record SynthesisLinkRouter.LinkAction(Kind kind, String payload)`
  - `LinkAction SynthesisLinkRouter.classify(String href)` — pure.
  - `void SynthesisLinkRouter.route(Project project, String href)` — performs IDE navigation.

- [ ] **Step 1: Write the failing test (pure classify)**

Create `intellij-plugin-spike/src/test/java/com/petros/ireview/SynthesisLinkRouterTest.java`:

```java
package com.petros.ireview;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SynthesisLinkRouterTest {

    @Test
    void navScheme() {
        var a = SynthesisLinkRouter.classify("ireview-nav://src/Foo.java:18");
        assertEquals(SynthesisLinkRouter.Kind.NAV, a.kind());
        assertEquals("src/Foo.java:18", a.payload());
    }

    @Test
    void symScheme() {
        var a = SynthesisLinkRouter.classify("ireview-sym://Foo");
        assertEquals(SynthesisLinkRouter.Kind.SYM, a.kind());
        assertEquals("Foo", a.payload());
    }

    @Test
    void httpIsExternal() {
        var a = SynthesisLinkRouter.classify("https://example.com/x");
        assertEquals(SynthesisLinkRouter.Kind.EXTERNAL, a.kind());
        assertEquals("https://example.com/x", a.payload());
    }

    @Test
    void unknownIsNone() {
        assertEquals(SynthesisLinkRouter.Kind.NONE, SynthesisLinkRouter.classify("mailto:x@y.z").kind());
        assertEquals(SynthesisLinkRouter.Kind.NONE, SynthesisLinkRouter.classify(null).kind());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.SynthesisLinkRouterTest"`
Expected: FAIL — `SynthesisLinkRouter` does not exist.

- [ ] **Step 3: Write the implementation**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/SynthesisLinkRouter.java`. The `resolveAndNavigateSymbol` body is moved verbatim from `SynthesisPopup` (lines 320-357 of the current file):

```java
package com.petros.ireview;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Routes a clicked link href (from the JCEF page or the fallback JEditorPane)
 * to the right IDE action. Shared so the two render paths cannot drift.
 */
public final class SynthesisLinkRouter {

    public enum Kind { NAV, SYM, EXTERNAL, NONE }

    public record LinkAction(Kind kind, String payload) {}

    private static final String NAV = "ireview-nav://";
    private static final String SYM = "ireview-sym://";

    public static LinkAction classify(String href) {
        if (href == null) return new LinkAction(Kind.NONE, "");
        if (href.startsWith(NAV)) return new LinkAction(Kind.NAV, href.substring(NAV.length()));
        if (href.startsWith(SYM)) return new LinkAction(Kind.SYM, href.substring(SYM.length()));
        if (href.startsWith("http://") || href.startsWith("https://")) return new LinkAction(Kind.EXTERNAL, href);
        return new LinkAction(Kind.NONE, href);
    }

    public static void route(@NotNull Project project, String href) {
        LinkAction action = classify(href);
        switch (action.kind()) {
            case NAV -> navigate(project, action.payload());
            case SYM -> resolveAndNavigateSymbol(project, action.payload());
            case EXTERNAL -> BrowserUtil.browse(action.payload());
            case NONE -> { }
        }
    }

    private static void navigate(Project project, String payload) {
        MarkdownLinkRenderer.NavTarget t = MarkdownLinkRenderer.parseNavTarget(NAV + payload);
        String base = project.getBasePath();
        if (base == null) return;
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(base + "/" + t.path());
        if (vf == null) return;
        int line0 = Math.max(0, t.line() - 1);
        new OpenFileDescriptor(project, vf, line0, 0).navigate(true);
    }

    /**
     * Look up `identifier` in the project's PSI symbol caches. If exactly one
     * class or method matches, navigate to it. If multiple, show a chooser
     * popup. If none, silent no-op.
     */
    private static void resolveAndNavigateSymbol(Project project, String identifier) {
        ReadAction.nonBlocking(() -> {
            var cache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project);
            var scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project);
            com.intellij.psi.PsiNamedElement[] candidates = cache.getClassesByName(identifier, scope);
            if (candidates.length == 0) {
                candidates = cache.getMethodsByName(identifier, scope);
            }
            return candidates;
        })
        .finishOnUiThread(
            com.intellij.openapi.application.ModalityState.defaultModalityState(),
            candidates -> {
                if (candidates.length == 0) {
                    return;
                }
                if (candidates.length == 1) {
                    if (candidates[0] instanceof com.intellij.pom.Navigatable nav) {
                        nav.navigate(true);
                    }
                    return;
                }
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
        .submit(AppExecutorUtil.getAppExecutorService());
    }

    private SynthesisLinkRouter() {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd intellij-plugin-spike && ./gradlew test --tests "com.petros.ireview.SynthesisLinkRouterTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add intellij-plugin-spike/src/main/java/com/petros/ireview/SynthesisLinkRouter.java intellij-plugin-spike/src/test/java/com/petros/ireview/SynthesisLinkRouterTest.java
git commit -m "feat(ireview): SynthesisLinkRouter — shared href routing with pure classify()"
```

---

### Task 4: SynthesisBrowser (JCEF wrapper + click bridge)

**Files:**
- Create: `intellij-plugin-spike/src/main/java/com/petros/ireview/SynthesisBrowser.java`

**Interfaces:**
- Consumes: `SynthesisHtmlRenderer.toDocument` (Task 2); `SynthesisLinkRouter.route` (Task 3); JCEF platform APIs.
- Produces:
  - `new SynthesisBrowser(Project)` — constructs the browser + click bridge.
  - `JComponent SynthesisBrowser.getComponent()`
  - `void SynthesisBrowser.render(String markdown)`
  - implements `com.intellij.openapi.Disposable` (its browser is a registered child, disposed with it).

This task has no unit test: JCEF cannot start in a headless unit-test JVM. It is verified by compilation here and by the manual run in Task 6. Keep the code minimal and exactly as below.

- [ ] **Step 1: Write the implementation**

Create `intellij-plugin-spike/src/main/java/com/petros/ireview/SynthesisBrowser.java`:

```java
package com.petros.ireview;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Color;

/**
 * Embedded Chromium (JCEF) view of the synthesis markdown. Renders via
 * {@link SynthesisHtmlRenderer} and intercepts every <a> click through a
 * {@link JBCefJSQuery} bridge, routing the href via {@link SynthesisLinkRouter}.
 *
 * Only constructed when {@code JBCefApp.isSupported()}; the popup falls back to
 * the JEditorPane renderer otherwise.
 */
public final class SynthesisBrowser implements Disposable {

    private final JBCefBrowser browser;
    private final String navScript;

    public SynthesisBrowser(@NotNull Project project) {
        this.browser = new JBCefBrowser();
        Disposer.register(this, browser);

        JBCefJSQuery linkQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);
        linkQuery.addHandler(href -> {
            SwingUtilities.invokeLater(() -> SynthesisLinkRouter.route(project, href));
            return new JBCefJSQuery.Response(null);
        });

        // Intercept all link clicks; getAttribute('href') returns the raw,
        // un-resolved scheme value (e.g. ireview-sym://Foo), not a percent-
        // encoded absolute URL.
        this.navScript =
            "document.addEventListener('click',function(e){"
          + "var a=e.target.closest('a');if(!a)return;"
          + "e.preventDefault();"
          + "var href=a.getAttribute('href');"
          + linkQuery.inject("href")
          + "},true);";
    }

    public JComponent getComponent() {
        return browser.getComponent();
    }

    public void render(@NotNull String markdown) {
        browser.loadHTML(SynthesisHtmlRenderer.toDocument(markdown, currentTheme(), navScript));
    }

    @Override
    public void dispose() {
        // browser (and the JS query created from it) are disposed via Disposer.
    }

    private static SynthesisHtmlRenderer.Theme currentTheme() {
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        String bg = hex(scheme.getDefaultBackground());
        String fg = hex(scheme.getDefaultForeground());
        String font = scheme.getEditorFontName();
        int size = Math.max(11, scheme.getEditorFontSize());
        String codeBg = hex(shift(scheme.getDefaultBackground(), 12));
        String border = hex(shift(scheme.getDefaultBackground(), 36));
        return new SynthesisHtmlRenderer.Theme(bg, fg, "'" + font + "'", size, "#4f83ed", codeBg, border);
    }

    /** Nudge a color lighter (dark themes) or darker (light themes) by delta. */
    private static Color shift(Color c, int delta) {
        boolean dark = (c.getRed() + c.getGreen() + c.getBlue()) / 3 < 128;
        int d = dark ? delta : -delta;
        return new Color(clamp(c.getRed() + d), clamp(c.getGreen() + d), clamp(c.getBlue() + d));
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd intellij-plugin-spike && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add intellij-plugin-spike/src/main/java/com/petros/ireview/SynthesisBrowser.java
git commit -m "feat(ireview): SynthesisBrowser — JCEF view with JS link-click bridge"
```

---

### Task 5: Wire JCEF into SynthesisPopup (with fallback)

**Files:**
- Modify: `intellij-plugin-spike/src/main/java/com/petros/ireview/SynthesisPopup.java`

**Interfaces:**
- Consumes: `SynthesisBrowser` (Task 4); `SynthesisLinkRouter.route` (Task 3).
- Produces: no new public API; `SynthesisPopup.show(...)` now renders via JCEF when supported.

This task is verified by compilation here and the manual run in Task 6 (JCEF wiring is not unit-testable).

- [ ] **Step 1: Add imports**

At the top of `SynthesisPopup.java`, with the other `com.intellij` imports, add:

```java
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefApp;
```

- [ ] **Step 2: Create the browser (or null) and choose the synthesis component**

Find this block (currently lines 164-169):

```java
        // CardLayout swap between synthesis content and thinking spinner.
        java.awt.CardLayout cards = new java.awt.CardLayout();
        JPanel centerCards = new JPanel(cards);
        centerCards.add(synthesisScroll, "synthesis");
        centerCards.add(thinkingCard, "thinking");
        centerCards.setPreferredSize(new Dimension(520, 130));
```

Replace it with:

```java
        // CardLayout swap between synthesis content and thinking spinner.
        // Prefer JCEF (real browser) for the synthesis card; fall back to the
        // JEditorPane (synthesisScroll) when JCEF is unavailable.
        final SynthesisBrowser browser = JBCefApp.isSupported() ? new SynthesisBrowser(project) : null;
        JComponent synthesisCard = browser != null ? (JComponent) browser.getComponent() : synthesisScroll;
        java.awt.CardLayout cards = new java.awt.CardLayout();
        JPanel centerCards = new JPanel(cards);
        centerCards.add(synthesisCard, "synthesis");
        centerCards.add(thinkingCard, "thinking");
        centerCards.setPreferredSize(new Dimension(520, 130));
```

- [ ] **Step 3: Branch renderCurrent on the browser**

Find this block (currently lines 177-192):

```java
        // renderCurrent captures synthesisPane and thinking; called on EDT.
        Runnable renderCurrent = () -> {
            if (thinking.get()) {
                cards.show(centerCards, "thinking");
                return;
            }
            cards.show(centerCards, "synthesis");
            var cached = client.threadFor(anchor);
            if (cached.isEmpty()) {
                synthesisPane.setText(wrapHtml("<i style='color:#7a7e85'>No annotation yet. Ask a question to start.</i>"));
            } else {
                synthesisPane.setText(wrapHtml(MarkdownLinkRenderer.toHtml(cached.get().synthesis())));
            }
            synthesisPane.setCaretPosition(0);
        };
        renderCurrent.run();
```

Replace it with:

```java
        // renderCurrent captures synthesisPane/browser and thinking; called on EDT.
        Runnable renderCurrent = () -> {
            if (thinking.get()) {
                cards.show(centerCards, "thinking");
                return;
            }
            cards.show(centerCards, "synthesis");
            var cached = client.threadFor(anchor);
            if (browser != null) {
                browser.render(cached.isEmpty()
                    ? "*No annotation yet. Ask a question to start.*"
                    : cached.get().synthesis());
                return;
            }
            if (cached.isEmpty()) {
                synthesisPane.setText(wrapHtml("<i style='color:#7a7e85'>No annotation yet. Ask a question to start.</i>"));
            } else {
                synthesisPane.setText(wrapHtml(MarkdownLinkRenderer.toHtml(cached.get().synthesis())));
            }
            synthesisPane.setCaretPosition(0);
        };
        renderCurrent.run();
```

- [ ] **Step 4: Branch the submit error path on the browser**

Find this block (currently lines 202-211):

```java
            client.postComment(anchor, q).whenComplete((v, t) -> SwingUtilities.invokeLater(() -> {
                if (t != null) {
                    thinking.set(false);
                    cards.show(centerCards, "synthesis");
                    synthesisPane.setText(wrapHtml(
                        "<span style='color:#cc6666'>Failed to submit — retry?</span>"));
                }
                // On success, do nothing; the SSE thread-changed event will call
                // onThreadChanged, which sets thinking=false and re-renders.
            }));
```

Replace it with:

```java
            client.postComment(anchor, q).whenComplete((v, t) -> SwingUtilities.invokeLater(() -> {
                if (t != null) {
                    thinking.set(false);
                    cards.show(centerCards, "synthesis");
                    if (browser != null) {
                        browser.render("**Failed to submit — retry?**");
                    } else {
                        synthesisPane.setText(wrapHtml(
                            "<span style='color:#cc6666'>Failed to submit — retry?</span>"));
                    }
                }
                // On success, do nothing; the SSE thread-changed event will call
                // onThreadChanged, which sets thinking=false and re-renders.
            }));
```

- [ ] **Step 5: Simplify the fallback hyperlink listener to use the shared router**

Find this block (currently lines 123-144):

```java
        synthesisPane.addHyperlinkListener(e -> {
            if (e.getEventType() != javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) return;
            String url = e.getDescription();
            if (url == null) return;
            if (url.startsWith("ireview-nav://")) {
                MarkdownLinkRenderer.NavTarget t = MarkdownLinkRenderer.parseNavTarget(url);
                String base = project.getBasePath();
                if (base == null) return;
                com.intellij.openapi.vfs.VirtualFile vf =
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                        .findFileByPath(base + "/" + t.path());
                if (vf != null) {
                    int line0 = Math.max(0, t.line() - 1);
                    new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, line0, 0).navigate(true);
                }
            } else if (url.startsWith("ireview-sym://")) {
                String identifier = url.substring("ireview-sym://".length());
                resolveAndNavigateSymbol(project, identifier);
            } else {
                com.intellij.ide.BrowserUtil.browse(url);
            }
        });
```

Replace it with:

```java
        synthesisPane.addHyperlinkListener(e -> {
            if (e.getEventType() != javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) return;
            SynthesisLinkRouter.route(project, e.getDescription());
        });
```

- [ ] **Step 6: Register the browser for disposal on popup close**

Find this block (currently lines 279-280):

```java
        popupRef.set(popup);
        OPEN_POPUPS.put(anchor, popup);
```

Replace it with:

```java
        popupRef.set(popup);
        if (browser != null) Disposer.register(popup, browser);
        OPEN_POPUPS.put(anchor, popup);
```

- [ ] **Step 7: Delete the now-unused resolveAndNavigateSymbol from SynthesisPopup**

Delete the entire `resolveAndNavigateSymbol` method (currently lines 315-357, the Javadoc comment through the closing brace). It now lives in `SynthesisLinkRouter`. Leave `wrapHtml`, `makeAccentButton`, and `pluginVersionLabel` in place — they are still used by the fallback path and header.

- [ ] **Step 8: Verify it compiles and all unit tests pass**

Run: `cd intellij-plugin-spike && ./gradlew compileJava test`
Expected: BUILD SUCCESSFUL; all tests pass; no "unused method"/missing-symbol errors. If the compiler reports unused imports in `SynthesisPopup` (e.g. `ReadAction`, `AppExecutorUtil` were never imported there — they were fully-qualified), ignore — none were top-level imports.

- [ ] **Step 9: Commit**

```bash
git add intellij-plugin-spike/src/main/java/com/petros/ireview/SynthesisPopup.java
git commit -m "feat(ireview): render synthesis popup via JCEF, fallback to JEditorPane"
```

---

### Task 6: Manual verification in a running IDE

**Files:** none (verification only).

This confirms the JCEF rendering, the link bridge, and the fallback path — the parts no unit test can cover.

- [ ] **Step 1: Launch the sandbox IDE**

Run: `cd intellij-plugin-spike && ./gradlew runIde`
Expected: a sandbox IntelliJ starts with the plugin loaded.

- [ ] **Step 2: Open a synthesis popup with a table**

Start/attach an interactive_review session, open the PR diff, click the ask-+ gutter icon on a line, and submit a question whose answer includes a GFM table, a fenced code block, a `[label](path:line)` link, a backtick `Symbol`, and an `[x](https://…)` link. (Or temporarily seed a thread synthesis containing the exact table from the bug report.)
Expected: the table renders as a real bordered table (not raw `|` pipes); the code block is a styled box; lists/headings render.

- [ ] **Step 3: Exercise the link bridge**

Click the path link → the file opens at the line. Click the backtick symbol → it navigates to the class/method (or shows the chooser for multiple matches). Click the external link → it opens in the system browser.
Expected: all three navigate exactly as before.

- [ ] **Step 4: Verify the fallback path**

Stop the IDE. Relaunch with JCEF disabled: `./gradlew runIde -Dide.browser.jcef.enabled=false`
Open a synthesis popup again.
Expected: the popup still renders (via the JEditorPane fallback) with today's behavior — no crash, links still work through `SynthesisLinkRouter`.

- [ ] **Step 5: Final full build**

Run: `cd intellij-plugin-spike && ./gradlew clean build`
Expected: BUILD SUCCESSFUL (compiles, tests pass, plugin verifier/packaging OK).

---

## Self-Review

**Spec coverage:**
- JCEF + commonmark rendering → Tasks 1, 2, 4, 5. ✓
- Preserve `ireview-nav://` path links → Task 2 (`NavLinkAttributeProvider`), Task 3 (`navigate`). ✓
- Preserve `ireview-sym://` inline-code symbol links → Task 2 (`SymbolCodeRenderer`), Task 3 (`resolveAndNavigateSymbol`). ✓
- GFM tables (the reported bug) → Task 1 (extension), Task 2 (test), Task 6 (manual). ✓
- Theme-aware CSS from editor scheme → Task 2 (`css`), Task 4 (`currentTheme`). ✓
- Click bridge via JBCefJSQuery → Task 4. ✓
- Fallback when JCEF unsupported → Task 5 (`JBCefApp.isSupported()` branch), Task 6 step 4. ✓
- Dispose browser on popup close → Task 5 step 6. ✓
- commonmark bundled as `implementation` → Task 1 + Global Constraints. ✓
- `MarkdownLinkRenderer` / `AnnotationsPanel` untouched as renderers → preserved (fallback only). ✓
- HTML escaping incl. `"` → Task 2 tests rely on commonmark `HtmlWriter` escaping. ✓
- Tests for renderer + router → Tasks 2, 3. ✓

**Placeholder scan:** No TBD/TODO; every code step shows complete code; commands have expected output. ✓

**Type consistency:** `toBodyHtml`/`toDocument`/`Theme` used consistently across Tasks 2/4; `SynthesisLinkRouter.route`/`classify`/`Kind`/`LinkAction` consistent across Tasks 3/4/5; `SynthesisBrowser(Project)`/`getComponent()`/`render(String)`/`dispose()` consistent across Tasks 4/5. ✓
