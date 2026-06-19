// Anchor parsing tests.

import { test } from "node:test";
import * as assert from "node:assert/strict";
import { parseAnchor } from "../src/types";

test("parses a single-line anchor", () => {
    const a = parseAnchor("src/foo/Bar.java:R:42");
    assert.deepEqual(a, { path: "src/foo/Bar.java", side: "R", line: 42, endLine: 42 });
});

test("parses a range anchor", () => {
    const a = parseAnchor("path/to/file.ts:L:10-15");
    assert.deepEqual(a, { path: "path/to/file.ts", side: "L", line: 10, endLine: 15 });
});

test("rejects malformed anchors", () => {
    assert.equal(parseAnchor("just-a-string"), null);
    assert.equal(parseAnchor("path:R:nope"), null);
    assert.equal(parseAnchor("path:X:1"), null);
});

test("keeps colons in path", () => {
    // Anchors are POSIX, but defensive: a Windows-like prefix should not
    // confuse the parser — anchor is "split off the last two parts".
    const a = parseAnchor("C:/foo/bar.ts:R:5");
    assert.equal(a?.path, "C:/foo/bar.ts");
    assert.equal(a?.line, 5);
});
