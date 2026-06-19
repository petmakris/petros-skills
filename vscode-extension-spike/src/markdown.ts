// Render synthesis text into a vscode.MarkdownString.
//
// The original Java side hand-rolled HTML so it could use a custom URL scheme
// + HyperlinkListener. VS Code's MarkdownString already understands markdown
// natively, so we mostly just have to rewrite link targets:
//
//   [label](https://...)      → external link (no rewrite needed)
//   [label](path[:line])      → command:ireview.navigate?<args>
//   `Identifier`              → command:ireview.gotoSymbol?<args>
//   **bold** / ```fenced```   → already valid markdown, leave alone
//
// Identifier rewriting follows the same rule as MarkdownLinkRenderer.java:
// any non-empty backtick run becomes a symbol-lookup link.
//
// The pure string rewriter (`rewriteLinks`, `parseNavTarget`) is exported
// without importing `vscode`, so it can be unit-tested in plain node.

export interface NavTarget {
    path: string;
    line: number; // -1 if absent
}

/** Parse "path:42" or "path"; returns line=-1 if no line suffix. */
export function parseNavTarget(target: string): NavTarget {
    const m = /^(.+?):(\d+)$/.exec(target);
    if (m) return { path: m[1], line: Number.parseInt(m[2], 10) };
    return { path: target, line: -1 };
}

/**
 * Build a MarkdownString suitable for the Comments API. Trust must be on so
 * `command:` URIs activate. Imports `vscode` lazily so this module remains
 * pure when consumed by unit tests.
 */
export function renderSynthesis(text: string): import("vscode").MarkdownString {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const vscode = require("vscode") as typeof import("vscode");
    const out = rewriteLinks(text);
    const md = new vscode.MarkdownString(out, true);
    md.isTrusted = true;
    md.supportHtml = false;
    return md;
}

/**
 * Rewrite [label](target) when target is not a URL, and `Ident` runs.
 *
 * Skip code fences entirely so embedded backticks don't get rewritten.
 */
export function rewriteLinks(text: string): string {
    // Split on fenced code blocks; only rewrite the non-fence segments.
    const parts = text.split(/(```[\s\S]*?```)/g);
    return parts
        .map((segment, idx) => {
            const isFence = idx % 2 === 1;
            if (isFence) return segment;
            return rewriteNonFence(segment);
        })
        .join("");
}

function rewriteNonFence(text: string): string {
    // First rewrite links so we don't double-process labels containing backticks.
    let out = text.replace(/\[([^\]]+)\]\(([^)]+)\)/g, (_m, label, target) => {
        if (/^(https?:|mailto:|command:)/.test(target)) {
            return `[${label}](${target})`;
        }
        const cmd = encodeCommandUri("ireview.navigate", [target]);
        return `[${label}](${cmd})`;
    });
    // Then inline-code identifiers. Skip if the run contains a space or
    // colon — those aren't useful symbol lookups.
    out = out.replace(/`([^`\n]+)`/g, (_m, sym) => {
        if (/[\s:()<>]/.test(sym)) {
            return "`" + sym + "`";
        }
        const cmd = encodeCommandUri("ireview.gotoSymbol", [sym]);
        return `[\`${sym}\`](${cmd})`;
    });
    return out;
}

function encodeCommandUri(command: string, args: unknown[]): string {
    return `command:${command}?${encodeURIComponent(JSON.stringify(args))}`;
}
