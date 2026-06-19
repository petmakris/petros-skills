// Parsers for the interactive_review server's JSON responses.
//
// The server always emits well-formed JSON (Python json.dumps), so we parse
// with the built-in JSON.parse rather than regex. Each parser is defensive
// about shape (returns null/[] on anything unexpected) so a malformed or
// unreachable server can never throw into the extension's event handlers.

export interface ParsedSession {
    sid: string;
    prRef: string;
    title: string;
    stateDir: string;
}

export interface ParsedThread {
    anchor: string;
    synthesis: string;
    version: number;
}

function asString(v: unknown): string {
    return typeof v === "string" ? v : "";
}

function asNumber(v: unknown): number {
    return typeof v === "number" ? v : 0;
}

function tryParse(body: string): unknown {
    try {
        return JSON.parse(body);
    } catch {
        return undefined;
    }
}

/**
 * First session from `GET /api/sessions?cwd=…`, which returns a JSON array of
 * `{sid, pr_ref, title, state_dir}` sorted newest-first. Returns null if the
 * list is empty, unparseable, or the first entry has no sid.
 */
export function parseFirstSession(body: string): ParsedSession | null {
    const data = tryParse(body);
    const first = Array.isArray(data) ? data[0] : undefined;
    if (!first || typeof first !== "object") return null;
    const o = first as Record<string, unknown>;
    if (typeof o.sid !== "string" || o.sid === "") return null;
    return {
        sid: o.sid,
        prRef: asString(o.pr_ref),
        title: asString(o.title),
        stateDir: asString(o.state_dir),
    };
}

/**
 * Parse `GET /s/<sid>/threads.json`, shaped as
 *   `{ "<anchor>": { "latest_synthesis": "...", "version": N, ... }, ... }`.
 */
export function parseThreadsBulk(body: string): ParsedThread[] {
    const data = tryParse(body);
    if (!data || typeof data !== "object" || Array.isArray(data)) return [];
    const out: ParsedThread[] = [];
    for (const [anchor, raw] of Object.entries(data as Record<string, unknown>)) {
        if (!raw || typeof raw !== "object") continue;
        const o = raw as Record<string, unknown>;
        out.push({
            anchor,
            synthesis: asString(o.latest_synthesis),
            version: asNumber(o.version),
        });
    }
    return out;
}

/**
 * Parse a `thread-changed` SSE data payload:
 *   `{ "anchor": "...", "latest_synthesis": "...", "version": N }`.
 * Returns null without a valid anchor.
 */
export function parseThreadEvent(data: string): ParsedThread | null {
    const o = tryParse(data);
    if (!o || typeof o !== "object") return null;
    const r = o as Record<string, unknown>;
    if (typeof r.anchor !== "string") return null;
    return {
        anchor: r.anchor,
        synthesis: asString(r.latest_synthesis),
        version: asNumber(r.version),
    };
}

/** Parse a `thread-deleted` SSE data payload (`{ "anchor": "..." }`). */
export function parseDeletedAnchor(data: string): string | null {
    const o = tryParse(data);
    if (o && typeof o === "object" && typeof (o as Record<string, unknown>).anchor === "string") {
        return (o as Record<string, unknown>).anchor as string;
    }
    return null;
}
