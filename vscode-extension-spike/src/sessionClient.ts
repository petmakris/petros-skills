// HTTP + SSE client for the interactive_review server.
//
// Mirrors com.petros.ireview.ReviewSessionClient. No external dependencies —
// uses Node's built-in http module so the extension stays installable as a
// plain VSIX without a bundle step.

import * as http from "node:http";
import * as https from "node:https";
import { URL } from "node:url";
import {
    jsonEscape,
    parseFirstSession,
    parseThreadsBulk,
    jsonField,
} from "./json";
import { LineBuffer, SseParser } from "./sse";
import type {
    ClientState,
    SessionInfo,
    SessionListener,
    ThreadState,
} from "./types";

export class ReviewSessionClient {
    private readonly listeners: SessionListener[] = [];
    private readonly cache = new Map<string, ThreadState>();
    /** Anchors with an in-flight Claude reply (post-submit, pre-SSE-confirmation). */
    private readonly pending = new Set<string>();
    private state: ClientState = "DORMANT";
    private current: SessionInfo | null = null;
    private discoverTimer: NodeJS.Timeout | null = null;
    private sseRequest: http.ClientRequest | null = null;
    private sseReconnectTimer: NodeJS.Timeout | null = null;
    private stopped = false;

    constructor(
        private readonly baseUrl: string,
        private readonly projectCwd: string,
        private readonly pollIntervalMs: number
    ) {}

    start(): void {
        if (this.stopped) return;
        // Fire immediately, then on interval.
        this.pollDiscover();
        this.discoverTimer = setInterval(
            () => this.pollDiscover(),
            this.pollIntervalMs
        );
    }

    stop(): void {
        this.stopped = true;
        if (this.discoverTimer) {
            clearInterval(this.discoverTimer);
            this.discoverTimer = null;
        }
        if (this.sseReconnectTimer) {
            clearTimeout(this.sseReconnectTimer);
            this.sseReconnectTimer = null;
        }
        if (this.sseRequest) {
            this.sseRequest.destroy();
            this.sseRequest = null;
        }
        this.setState("DORMANT");
    }

    addListener(l: SessionListener): void {
        this.listeners.push(l);
    }

    currentSession(): SessionInfo | null {
        return this.current;
    }

    threadFor(anchor: string): ThreadState | undefined {
        return this.cache.get(anchor);
    }

    snapshotCache(): Map<string, ThreadState> {
        return new Map(this.cache);
    }

    isPending(anchor: string): boolean {
        return this.pending.has(anchor);
    }

    /** POST a comment event to /s/<sid>/api/submit. */
    async postComment(anchor: string, text: string): Promise<void> {
        const s = this.current;
        if (!s) throw new Error("no session");
        this.markPending(anchor, true);
        const body =
            '{"anchor":' +
            jsonEscape(anchor) +
            ',"type":"comment","text":' +
            jsonEscape(text) +
            "}";
        try {
            await this.postJson(`/s/${s.sid}/api/submit`, body);
        } catch (err) {
            // Clear pending so the side-panel × button reappears.
            this.markPending(anchor, false);
            throw err;
        }
    }

    /** POST a delete request. */
    async deleteThread(anchor: string): Promise<void> {
        const s = this.current;
        if (!s) throw new Error("no session");
        const body = '{"anchor":' + jsonEscape(anchor) + "}";
        await this.postJson(`/s/${s.sid}/api/threads/delete`, body);
    }

    // --- internal ---

    private markPending(anchor: string, isPending: boolean): void {
        const changed = isPending
            ? !this.pending.has(anchor) && (this.pending.add(anchor), true)
            : this.pending.delete(anchor);
        if (!changed) return;
        for (const l of this.listeners) l.onPendingChanged?.(anchor, isPending);
    }

    private async pollDiscover(): Promise<void> {
        try {
            const url = `${this.baseUrl}/api/sessions?cwd=${encodeURIComponent(
                this.projectCwd
            )}`;
            const resp = await this.getText(url);
            if (resp.status !== 200) {
                this.handleNoSession();
                return;
            }
            const found = parseFirstSession(resp.body);
            if (!found) {
                this.handleNoSession();
                return;
            }
            if (!this.current || this.current.sid !== found.sid) {
                this.attach(found);
            }
        } catch {
            this.handleNoSession();
        }
    }

    private handleNoSession(): void {
        if (this.current) {
            this.current = null;
            this.cache.clear();
            this.pending.clear();
            if (this.sseRequest) {
                this.sseRequest.destroy();
                this.sseRequest = null;
            }
            for (const l of this.listeners) l.onDetached?.();
        }
        this.setState("DORMANT");
    }

    private attach(s: SessionInfo): void {
        this.current = s;
        // Switching sessions: drop cached state from the previous SID so old
        // dead threads don't linger in the side panel.
        this.cache.clear();
        this.pending.clear();
        this.setState("CONNECTING");
        // seedCache fires onThreadChanged for each seeded entry; do that
        // BEFORE notifying onAttached so listeners can render a full snapshot
        // in one pass.
        this.seedCache(s.sid).finally(() => {
            for (const l of this.listeners) l.onAttached?.(s);
            this.openSse(s.sid);
        });
    }

    private async seedCache(sid: string): Promise<void> {
        try {
            const resp = await this.getText(
                `${this.baseUrl}/s/${sid}/threads.json`
            );
            if (resp.status !== 200) return;
            const threads = parseThreadsBulk(resp.body);
            for (const t of threads) {
                this.cache.set(t.anchor, {
                    synthesis: t.synthesis,
                    version: t.version,
                });
                for (const l of this.listeners) {
                    l.onThreadChanged?.(t.anchor, t.synthesis, t.version);
                }
            }
        } catch {
            // swallow — caller already set CONNECTING.
        }
    }

    private openSse(sid: string): void {
        if (this.stopped) return;
        const url = new URL(`${this.baseUrl}/s/${sid}/stream`);
        const lib: typeof http = url.protocol === "https:" ? (https as unknown as typeof http) : http;
        const lineBuf = new LineBuffer((line) => parser.feed(line));
        const parser = new SseParser((e) => this.handleSseEvent(e));
        const req = lib.request(
            {
                method: "GET",
                protocol: url.protocol,
                hostname: url.hostname,
                port: url.port,
                path: url.pathname + url.search,
                headers: { Accept: "text/event-stream" },
            },
            (res) => {
                if (res.statusCode !== 200) {
                    res.resume();
                    this.scheduleReconnect(sid);
                    return;
                }
                res.setEncoding("utf8");
                res.on("data", (chunk: string) => lineBuf.push(chunk));
                res.on("end", () => {
                    lineBuf.flush();
                    this.scheduleReconnect(sid);
                });
                res.on("error", () => this.scheduleReconnect(sid));
            }
        );
        req.on("error", () => this.scheduleReconnect(sid));
        req.end();
        this.sseRequest = req;
        this.setState("ACTIVE");
    }

    private scheduleReconnect(sid: string): void {
        if (this.stopped) return;
        // Only reconnect if this is still the current sid; otherwise the
        // session has switched or disappeared and pollDiscover will sort it.
        if (this.current?.sid !== sid) return;
        this.setState("DISCONNECTED");
        if (this.sseReconnectTimer) clearTimeout(this.sseReconnectTimer);
        this.sseReconnectTimer = setTimeout(() => {
            this.sseReconnectTimer = null;
            if (this.current?.sid === sid) this.openSse(sid);
        }, 2000);
    }

    private handleSseEvent(e: { name: string; data: string }): void {
        if (e.name === "thread-deleted") {
            const anchor = jsonField(e.data, "anchor");
            if (!anchor) return;
            this.cache.delete(anchor);
            this.markPending(anchor, false);
            for (const l of this.listeners) l.onThreadDeleted?.(anchor);
            return;
        }
        if (e.name !== "thread-changed") return;
        const anchor = jsonField(e.data, "anchor");
        const synthesis = jsonField(e.data, "latest_synthesis");
        const versionStr = jsonField(e.data, "version");
        const version = versionStr ? Number.parseInt(versionStr, 10) : 0;

        // Server fires thread-changed on EVERY thread mutation, including the
        // user's own appended question (version bumps but synthesis unchanged).
        // Filter those out so the popup's "thinking…" spinner stays up until
        // Claude actually replies.
        const existing = this.cache.get(anchor);
        if (
            existing &&
            existing.synthesis === synthesis &&
            existing.version === version
        ) {
            return;
        }
        if (existing && existing.synthesis === synthesis) {
            // Bump cache silently; no user-visible change.
            this.cache.set(anchor, { synthesis, version });
            return;
        }
        this.cache.set(anchor, { synthesis, version });
        // New synthesis text → Claude has replied. Clear pending.
        this.markPending(anchor, false);
        for (const l of this.listeners) l.onThreadChanged?.(anchor, synthesis, version);
    }

    private setState(s: ClientState): void {
        if (this.state === s) return;
        this.state = s;
        for (const l of this.listeners) l.onStateChanged?.(s);
    }

    private getText(url: string): Promise<{ status: number; body: string }> {
        return new Promise((resolve, reject) => {
            const parsed = new URL(url);
            const lib: typeof http = parsed.protocol === "https:" ? (https as unknown as typeof http) : http;
            const req = lib.request(
                {
                    method: "GET",
                    protocol: parsed.protocol,
                    hostname: parsed.hostname,
                    port: parsed.port,
                    path: parsed.pathname + parsed.search,
                },
                (res) => {
                    res.setEncoding("utf8");
                    let body = "";
                    res.on("data", (c: string) => (body += c));
                    res.on("end", () => resolve({ status: res.statusCode ?? 0, body }));
                    res.on("error", reject);
                }
            );
            req.on("error", reject);
            req.setTimeout(4000, () => req.destroy(new Error("timeout")));
            req.end();
        });
    }

    private postJson(pathSegment: string, body: string): Promise<void> {
        return new Promise((resolve, reject) => {
            const parsed = new URL(this.baseUrl + pathSegment);
            const lib: typeof http = parsed.protocol === "https:" ? (https as unknown as typeof http) : http;
            const req = lib.request(
                {
                    method: "POST",
                    protocol: parsed.protocol,
                    hostname: parsed.hostname,
                    port: parsed.port,
                    path: parsed.pathname + parsed.search,
                    headers: {
                        "Content-Type": "application/json",
                        "Content-Length": Buffer.byteLength(body),
                    },
                },
                (res) => {
                    res.resume();
                    res.on("end", () => {
                        const sc = res.statusCode ?? 0;
                        if (Math.trunc(sc / 100) === 2) resolve();
                        else reject(new Error(`HTTP ${sc}`));
                    });
                    res.on("error", reject);
                }
            );
            req.on("error", reject);
            req.write(body);
            req.end();
        });
    }
}
