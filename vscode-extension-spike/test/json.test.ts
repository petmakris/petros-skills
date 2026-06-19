// JSON parsing tests. The interactive_review server always emits well-formed
// JSON (Python json.dumps), so the client parses with JSON.parse. These tests
// cover the response shapes plus the cases the previous regex parser got wrong
// (nested objects, reordered fields).

import { test } from "node:test";
import * as assert from "node:assert/strict";
import {
    parseFirstSession,
    parseThreadsBulk,
    parseThreadEvent,
    parseDeletedAnchor,
} from "../src/json";

test("parseFirstSession reads sid/pr_ref/title/state_dir from the array", () => {
    const json =
        '[{"sid":"abc123","pr_ref":"owner/repo#42","title":"Fix bug","state_dir":"/tmp/x"}]';
    const s = parseFirstSession(json);
    assert.ok(s);
    assert.equal(s.sid, "abc123");
    assert.equal(s.prRef, "owner/repo#42");
    assert.equal(s.title, "Fix bug");
    assert.equal(s.stateDir, "/tmp/x");
});

test("parseFirstSession picks the first session when several are present", () => {
    const json =
        '[{"sid":"newest","pr_ref":"a#1","title":"","state_dir":"/a"},' +
        '{"sid":"older","pr_ref":"b#2","title":"","state_dir":"/b"}]';
    assert.equal(parseFirstSession(json)?.sid, "newest");
});

test("parseFirstSession tolerates missing optional fields", () => {
    const s = parseFirstSession('[{"sid":"only-sid"}]');
    assert.ok(s);
    assert.equal(s.sid, "only-sid");
    assert.equal(s.prRef, "");
    assert.equal(s.title, "");
    assert.equal(s.stateDir, "");
});

test("parseFirstSession returns null for empty list, bad JSON, or no sid", () => {
    assert.equal(parseFirstSession("[]"), null);
    assert.equal(parseFirstSession(""), null);
    assert.equal(parseFirstSession("not json"), null);
    assert.equal(parseFirstSession('[{"pr_ref":"x"}]'), null); // no sid
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

test("parseThreadsBulk handles a nested object inside a thread entry", () => {
    // The old regex used [^}]* and broke on any nested {...} before "version".
    const json =
        '{"src/a.ts:R:1": {"latest_synthesis": "hi", "meta": {"k": "v"}, "version": 2}}';
    const rows = parseThreadsBulk(json);
    assert.equal(rows.length, 1);
    assert.equal(rows[0].synthesis, "hi");
    assert.equal(rows[0].version, 2);
});

test("parseThreadsBulk handles fields in any order", () => {
    // The old regex required latest_synthesis before version.
    const json =
        '{"src/a.ts:R:1": {"version": 7, "updated_at": 0, "latest_synthesis": "late"}}';
    const rows = parseThreadsBulk(json);
    assert.equal(rows[0].version, 7);
    assert.equal(rows[0].synthesis, "late");
});

test("parseThreadsBulk returns [] for empty object or bad JSON", () => {
    assert.deepEqual(parseThreadsBulk("{}"), []);
    assert.deepEqual(parseThreadsBulk("nope"), []);
    assert.deepEqual(parseThreadsBulk("[]"), []);
});

test("parseThreadEvent reads anchor/synthesis/version", () => {
    const e = parseThreadEvent(
        '{"anchor":"src/a.ts:R:1","latest_synthesis":"done","version":4,"updated_at":0}'
    );
    assert.ok(e);
    assert.equal(e.anchor, "src/a.ts:R:1");
    assert.equal(e.synthesis, "done");
    assert.equal(e.version, 4);
});

test("parseThreadEvent returns null without an anchor or on bad JSON", () => {
    assert.equal(parseThreadEvent('{"latest_synthesis":"x"}'), null);
    assert.equal(parseThreadEvent("{"), null);
});

test("parseDeletedAnchor reads the anchor", () => {
    assert.equal(parseDeletedAnchor('{"anchor":"src/a.ts:R:1"}'), "src/a.ts:R:1");
    assert.equal(parseDeletedAnchor("{}"), null);
    assert.equal(parseDeletedAnchor("bad"), null);
});
