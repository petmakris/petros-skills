// End-to-end "ride" for ReviewSessionClient against a live local server that
// mimics the interactive_review endpoints (sessions list, threads.json, SSE
// stream, submit, delete). Exercises the real HTTP + SSE networking code — not
// mocks — through a full scenario and asserts the listener callbacks fire.
//
// Run: node --import=tsx test/integration.ride.ts

import * as http from "node:http";
import * as assert from "node:assert/strict";
import { ReviewSessionClient } from "../src/sessionClient";
import type { SessionInfo } from "../src/types";

const SID = "260619-120000-abc";
const SEED_ANCHOR = "src/app.ts:R:10";

type Thread = { latest_synthesis: string; version: number; updated_at: number };
const threads = new Map<string, Thread>([
    [SEED_ANCHOR, { latest_synthesis: "Seeded synthesis.", version: 1, updated_at: 0 }],
]);
const openStreams = new Set<http.ServerResponse>();

function sse(res: http.ServerResponse, event: string, data: unknown): void {
    res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
}

function broadcast(event: string, data: unknown): void {
    for (const res of openStreams) sse(res, event, data);
}

function readBody(req: http.IncomingMessage): Promise<string> {
    return new Promise((resolve) => {
        let b = "";
        req.on("data", (c) => (b += c));
        req.on("end", () => resolve(b));
    });
}

const server = http.createServer(async (req, res) => {
    const url = new URL(req.url ?? "/", "http://localhost");
    const p = url.pathname;

    if (p === "/api/sessions") {
        res.setHeader("Content-Type", "application/json");
        res.end(JSON.stringify([
            { sid: SID, pr_ref: "owner/repo#7", title: "Demo PR", state_dir: "/tmp/s" },
        ]));
        return;
    }
    if (p === `/s/${SID}/threads.json`) {
        res.setHeader("Content-Type", "application/json");
        res.end(JSON.stringify(Object.fromEntries(threads)));
        return;
    }
    if (p === `/s/${SID}/stream`) {
        res.writeHead(200, {
            "Content-Type": "text/event-stream",
            "Cache-Control": "no-cache",
            Connection: "keep-alive",
        });
        sse(res, "connected", {});
        for (const [anchor, info] of threads) sse(res, "thread-changed", { anchor, ...info });
        openStreams.add(res);
        req.on("close", () => openStreams.delete(res));
        return;
    }
    if (p === `/s/${SID}/api/submit`) {
        const body = JSON.parse(await readBody(req)) as { anchor: string; text: string };
        res.end("{}");
        // Simulate Claude replying a moment later: bump version + new synthesis.
        setTimeout(() => {
            const prev = threads.get(body.anchor);
            const next: Thread = {
                latest_synthesis: `Reply to: ${body.text}`,
                version: (prev?.version ?? 0) + 1,
                updated_at: 1,
            };
            threads.set(body.anchor, next);
            broadcast("thread-changed", { anchor: body.anchor, ...next });
        }, 60);
        return;
    }
    if (p === `/s/${SID}/api/threads/delete`) {
        const body = JSON.parse(await readBody(req)) as { anchor: string };
        res.end("{}");
        setTimeout(() => {
            threads.delete(body.anchor);
            broadcast("thread-deleted", { anchor: body.anchor });
        }, 60);
        return;
    }
    res.writeHead(404);
    res.end("not found");
});

function waitFor(cond: () => boolean, label: string, ms = 4000): Promise<void> {
    return new Promise((resolve, reject) => {
        const start = Date.now();
        const t = setInterval(() => {
            if (cond()) {
                clearInterval(t);
                resolve();
            } else if (Date.now() - start > ms) {
                clearInterval(t);
                reject(new Error(`timeout waiting for: ${label}`));
            }
        }, 15);
    });
}

async function main(): Promise<void> {
    await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
    const port = (server.address() as { port: number }).port;
    const baseUrl = `http://127.0.0.1:${port}`;
    console.log(`fake interactive_review server on ${baseUrl}`);

    // Record every callback the client emits.
    const attached: SessionInfo[] = [];
    const changed: Array<{ anchor: string; synthesis: string; version: number }> = [];
    const deleted: string[] = [];
    const pending: Array<{ anchor: string; pending: boolean }> = [];
    const states: string[] = [];

    const client = new ReviewSessionClient(baseUrl, "/some/workspace", 200);
    client.addListener({
        onAttached: (i) => attached.push(i),
        onThreadChanged: (anchor, synthesis, version) => changed.push({ anchor, synthesis, version }),
        onThreadDeleted: (anchor) => deleted.push(anchor),
        onPendingChanged: (anchor, p) => pending.push({ anchor, pending: p }),
        onStateChanged: (s) => states.push(s),
    });

    client.start();

    // 1. Discover + attach + seed.
    await waitFor(() => attached.length === 1, "attach");
    await waitFor(() => states.includes("ACTIVE"), "ACTIVE state");
    await waitFor(() => changed.some((c) => c.anchor === SEED_ANCHOR), "seeded thread");
    assert.equal(attached[0].sid, SID);
    assert.equal(client.threadFor(SEED_ANCHOR)?.synthesis, "Seeded synthesis.");
    console.log("✔ attach + seed + ACTIVE");

    // 2. Post a comment → optimistic pending, then Claude reply via SSE.
    const before = changed.length;
    await client.postComment(SEED_ANCHOR, "why this line?");
    assert.ok(client.isPending(SEED_ANCHOR), "pending set immediately after postComment");
    await waitFor(
        () => changed.length > before && changed[changed.length - 1].synthesis.startsWith("Reply to:"),
        "Claude reply via SSE"
    );
    await waitFor(() => !client.isPending(SEED_ANCHOR), "pending cleared after reply");
    assert.equal(client.threadFor(SEED_ANCHOR)?.version, 2);
    assert.ok(pending.some((p) => p.pending === true), "saw pending=true");
    assert.ok(pending.some((p) => p.pending === false), "saw pending=false");
    console.log("✔ comment → pending → SSE reply → pending cleared");

    // 3. A brand-new thread pushed over SSE.
    const newAnchor = "src/other.ts:R:3";
    threads.set(newAnchor, { latest_synthesis: "Fresh.", version: 1, updated_at: 2 });
    broadcast("thread-changed", { anchor: newAnchor, latest_synthesis: "Fresh.", version: 1 });
    await waitFor(() => changed.some((c) => c.anchor === newAnchor), "new thread via SSE");
    console.log("✔ new thread over SSE");

    // 4. Delete a thread → SSE thread-deleted.
    await client.deleteThread(SEED_ANCHOR);
    await waitFor(() => deleted.includes(SEED_ANCHOR), "thread-deleted");
    assert.equal(client.threadFor(SEED_ANCHOR), undefined);
    console.log("✔ delete → SSE thread-deleted → cache evicted");

    client.stop();
    await new Promise<void>((r) => server.close(() => r()));
    console.log("\nALL INTEGRATION CHECKS PASSED ✅");
}

main().catch((err) => {
    console.error("\nINTEGRATION RIDE FAILED ❌\n", err);
    try {
        server.close();
    } catch {
        /* ignore */
    }
    process.exit(1);
});
