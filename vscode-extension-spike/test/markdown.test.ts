// Markdown rewriting tests. These exercise only `rewriteLinks` — the
// MarkdownString wrapper itself is just `new vscode.MarkdownString(...)` and
// requires the vscode module at runtime.

import { test } from "node:test";
import * as assert from "node:assert/strict";
import { parseNavTarget, rewriteLinks } from "../src/markdown";

test("parseNavTarget reads path:line", () => {
    assert.deepEqual(parseNavTarget("src/foo.ts:42"), {
        path: "src/foo.ts",
        line: 42,
    });
});

test("parseNavTarget without line", () => {
    assert.deepEqual(parseNavTarget("src/foo.ts"), {
        path: "src/foo.ts",
        line: -1,
    });
});

test("rewriteLinks leaves URL targets alone", () => {
    const out = rewriteLinks("see [docs](https://example.com)");
    assert.ok(out.includes("(https://example.com)"));
});

test("rewriteLinks rewrites path:line targets to command URIs", () => {
    const out = rewriteLinks("see [Foo](src/Foo.ts:12)");
    assert.match(out, /\[Foo\]\(command:ireview\.navigate\?/);
    // Args are URL-encoded JSON
    assert.match(out, /%22src%2FFoo\.ts%3A12%22/);
});

test("rewriteLinks rewrites backtick identifiers to gotoSymbol commands", () => {
    const out = rewriteLinks("call `MyClass` to do the thing");
    assert.match(out, /\[`MyClass`\]\(command:ireview\.gotoSymbol\?/);
});

test("rewriteLinks skips backtick runs with whitespace or colons", () => {
    const out = rewriteLinks("see `Foo bar` and `path:42`");
    // Should remain bare inline code, no command: URI wrapping.
    assert.ok(out.includes("`Foo bar`"));
    assert.ok(out.includes("`path:42`"));
    assert.ok(!out.includes("command:ireview.gotoSymbol"));
});

test("rewriteLinks preserves fenced code blocks verbatim", () => {
    const input = "before\n```ts\ncall `Foo` here\n```\nafter";
    const out = rewriteLinks(input);
    // The fenced segment must be untouched
    assert.ok(out.includes("```ts\ncall `Foo` here\n```"));
    // The non-fenced surroundings are intact
    assert.ok(out.startsWith("before\n"));
    assert.ok(out.endsWith("after"));
});
