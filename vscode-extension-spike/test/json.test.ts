// JSON helper tests — string unescape (including \uXXXX), field extraction,
// and the bulk threads.json shape.

import { test } from "node:test";
import * as assert from "node:assert/strict";
import {
    jsonEscape,
    jsonField,
    parseFirstSession,
    parseThreadsBulk,
    unescapeJsonString,
} from "../src/json";

test("unescapes standard escape sequences", () => {
    assert.equal(unescapeJsonString("a\\nb"), "a\nb");
    assert.equal(unescapeJsonString("a\\tb"), "a\tb");
    assert.equal(unescapeJsonString('a\\"b'), 'a"b');
    assert.equal(unescapeJsonString("a\\\\b"), "a\\b");
    assert.equal(unescapeJsonString("a\\/b"), "a/b");
});

test("unescapes \\uXXXX form", () => {
    assert.equal(unescapeJsonString("\\u0041"), "A");
    assert.equal(unescapeJsonString("prefix\\u00e9end"), "prefixéend");
});

test("jsonEscape round-trips through unescape", () => {
    const cases = [
        "hello",
        'with "quotes"',
        "with\nnewline",
        "back\\slash",
    ];
    for (const c of cases) {
        // jsonEscape returns "..." — strip the outer quotes.
        const escaped = jsonEscape(c).slice(1, -1);
        assert.equal(unescapeJsonString(escaped), c);
    }
});

test("jsonField extracts string and number fields", () => {
    const json = '{"a":"hello","b":42,"c":"esc\\nape"}';
    assert.equal(jsonField(json, "a"), "hello");
    assert.equal(jsonField(json, "b"), "42");
    assert.equal(jsonField(json, "c"), "esc\nape");
    assert.equal(jsonField(json, "missing"), "");
});

test("parseFirstSession reads sid/pr_ref/title/state_dir", () => {
    const json =
        '[{"sid":"abc123","pr_ref":"owner/repo#42","title":"Fix bug","state_dir":"/tmp/x"}]';
    const s = parseFirstSession(json);
    assert.ok(s);
    assert.equal(s.sid, "abc123");
    assert.equal(s.prRef, "owner/repo#42");
    assert.equal(s.title, "Fix bug");
    assert.equal(s.stateDir, "/tmp/x");
});

test("parseFirstSession returns null for empty list", () => {
    assert.equal(parseFirstSession("[]"), null);
    assert.equal(parseFirstSession(""), null);
});

test("parseThreadsBulk extracts each thread entry", () => {
    const json = `{
        "src/a.ts:R:10": {"latest_synthesis": "first synth", "version": 3, "updated_at": 0},
        "src/b.ts:L:5": {"latest_synthesis": "with \\"quotes\\" inside", "version": 1, "updated_at": 0}
    }`;
    const rows = parseThreadsBulk(json);
    assert.equal(rows.length, 2);
    const a = rows.find((r) => r.anchor === "src/a.ts:R:10");
    const b = rows.find((r) => r.anchor === "src/b.ts:L:5");
    assert.ok(a && b);
    assert.equal(a.synthesis, "first synth");
    assert.equal(a.version, 3);
    assert.equal(b.synthesis, 'with "quotes" inside');
    assert.equal(b.version, 1);
});
