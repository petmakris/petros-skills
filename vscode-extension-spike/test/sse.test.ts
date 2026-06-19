// SSE parser tests. Mirrors src/test/java/com/petros/ireview/SseClientTest.java.

import { test } from "node:test";
import * as assert from "node:assert/strict";
import { LineBuffer, SseParser } from "../src/sse";

test("parses a single event", () => {
    const events: { name: string; data: string }[] = [];
    const p = new SseParser((e) => events.push(e));
    p.feed("event: thread-changed");
    p.feed('data: {"anchor":"foo:R:1"}');
    p.feed("");
    assert.equal(events.length, 1);
    assert.equal(events[0].name, "thread-changed");
    assert.equal(events[0].data, '{"anchor":"foo:R:1"}');
});

test("parses multiple events separated by blank lines", () => {
    const events: { name: string; data: string }[] = [];
    const p = new SseParser((e) => events.push(e));
    for (const line of [
        "event: a",
        "data: 1",
        "",
        "event: b",
        "data: 2",
        "",
    ]) {
        p.feed(line);
    }
    assert.equal(events.length, 2);
    assert.equal(events[0].name, "a");
    assert.equal(events[0].data, "1");
    assert.equal(events[1].name, "b");
    assert.equal(events[1].data, "2");
});

test("multiline data is joined with newlines", () => {
    const events: { name: string; data: string }[] = [];
    const p = new SseParser((e) => events.push(e));
    p.feed("event: x");
    p.feed("data: line1");
    p.feed("data: line2");
    p.feed("");
    assert.equal(events.length, 1);
    assert.equal(events[0].data, "line1\nline2");
});

test("ignores comment lines starting with colon", () => {
    const events: { name: string; data: string }[] = [];
    const p = new SseParser((e) => events.push(e));
    p.feed(": this is a comment");
    p.feed("event: x");
    p.feed("data: y");
    p.feed("");
    assert.equal(events.length, 1);
});

test("LineBuffer splits across chunk boundaries", () => {
    const lines: string[] = [];
    const buf = new LineBuffer((l) => lines.push(l));
    buf.push("event: a\nda");
    buf.push("ta: 1\n\nevent: ");
    buf.push("b\ndata: 2\n\n");
    assert.deepEqual(lines, [
        "event: a",
        "data: 1",
        "",
        "event: b",
        "data: 2",
        "",
    ]);
});

test("LineBuffer handles CRLF", () => {
    const lines: string[] = [];
    const buf = new LineBuffer((l) => lines.push(l));
    buf.push("event: a\r\ndata: 1\r\n\r\n");
    assert.deepEqual(lines, ["event: a", "data: 1", ""]);
});
