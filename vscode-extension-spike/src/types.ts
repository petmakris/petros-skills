// Shared types between the session client and the UI components.

export interface SessionInfo {
    sid: string;
    prRef: string;
    title: string;
    stateDir: string;
}

export interface ThreadState {
    synthesis: string;
    version: number;
}

export type ClientState = "DORMANT" | "CONNECTING" | "ACTIVE" | "DISCONNECTED";

export interface SessionListener {
    onAttached?(info: SessionInfo): void;
    onDetached?(): void;
    onThreadChanged?(anchor: string, synthesis: string, version: number): void;
    onThreadDeleted?(anchor: string): void;
    onPendingChanged?(anchor: string, pending: boolean): void;
    onStateChanged?(state: ClientState): void;
}

/**
 * Parse an anchor like "path/to/file.ts:R:42" or
 * "path/to/file.ts:R:10-15". Returns null if the input doesn't match.
 */
export function parseAnchor(
    anchor: string
): { path: string; side: "L" | "R"; line: number; endLine: number } | null {
    const parts = anchor.split(":");
    if (parts.length < 3) return null;
    // path may itself contain colons on Windows; rejoin everything except the
    // last two segments back into the path. Server-side anchors are POSIX,
    // but be defensive.
    const lineRange = parts[parts.length - 1];
    const side = parts[parts.length - 2];
    const path = parts.slice(0, parts.length - 2).join(":");
    if (side !== "L" && side !== "R") return null;
    const [startStr, endStr] = lineRange.split("-", 2);
    const start = Number.parseInt(startStr, 10);
    if (!Number.isFinite(start)) return null;
    const end = endStr ? Number.parseInt(endStr, 10) : start;
    return { path, side, line: start, endLine: Number.isFinite(end) ? end : start };
}
