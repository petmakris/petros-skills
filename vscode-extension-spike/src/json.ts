// Lightweight JSON helpers that mirror the regex-based extractors used in the
// IntelliJ plugin. We don't use a full JSON parser because the SSE payloads
// are small, well-formed snippets emitted by the same server we already trust;
// the regex approach is faster and matches the Java side bit-for-bit.

/**
 * Decode a JSON string literal body (everything between the outer quotes).
 * Handles standard escapes plus \uXXXX. Matches the Java unescapeJsonString.
 */
export function unescapeJsonString(s: string): string {
    let out = "";
    for (let i = 0; i < s.length; i++) {
        const c = s.charAt(i);
        if (c !== "\\" || i + 1 >= s.length) {
            out += c;
            continue;
        }
        const next = s.charAt(++i);
        switch (next) {
            case "n":
                out += "\n";
                break;
            case "r":
                out += "\r";
                break;
            case "t":
                out += "\t";
                break;
            case "b":
                out += "\b";
                break;
            case "f":
                out += "\f";
                break;
            case '"':
                out += '"';
                break;
            case "\\":
                out += "\\";
                break;
            case "/":
                out += "/";
                break;
            case "u":
                if (i + 4 < s.length) {
                    out += String.fromCharCode(
                        Number.parseInt(s.substring(i + 1, i + 5), 16)
                    );
                    i += 4;
                } else {
                    out += "\\" + next;
                }
                break;
            default:
                out += "\\" + next;
                break;
        }
    }
    return out;
}

export function jsonEscape(s: string): string {
    return (
        '"' +
        s
            .replace(/\\/g, "\\\\")
            .replace(/"/g, '\\"')
            .replace(/\n/g, "\\n")
            .replace(/\r/g, "\\r")
            .replace(/\t/g, "\\t") +
        '"'
    );
}

/** Extract a single string-or-number field from a flat JSON blob. */
export function jsonField(json: string, key: string): string {
    const escapedKey = key.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const re = new RegExp(
        '"' + escapedKey + '"\\s*:\\s*(?:"((?:[^"\\\\]|\\\\.)*)"|(\\d+))'
    );
    const m = re.exec(json);
    if (!m) return "";
    return m[1] !== undefined ? unescapeJsonString(m[1]) : m[2];
}

export interface ParsedSession {
    sid: string;
    prRef: string;
    title: string;
    stateDir: string;
}

export function parseFirstSession(json: string): ParsedSession | null {
    if (!json || !json.includes('"sid"')) return null;
    return {
        sid: jsonField(json, "sid"),
        prRef: jsonField(json, "pr_ref"),
        title: jsonField(json, "title"),
        stateDir: jsonField(json, "state_dir"),
    };
}

export interface ParsedThread {
    anchor: string;
    synthesis: string;
    version: number;
}

/**
 * Parse the bulk threads.json shape:
 *   { "<anchor>": { "latest_synthesis": "...", "version": N, ... }, ... }
 * Matches the Java parseThreadsBulk regex form.
 */
export function parseThreadsBulk(json: string): ParsedThread[] {
    const out: ParsedThread[] = [];
    const re =
        /"([^"]+)"\s*:\s*\{[^}]*"latest_synthesis"\s*:\s*"((?:[^"\\]|\\.)*)"[^}]*"version"\s*:\s*(\d+)/g;
    let m: RegExpExecArray | null;
    while ((m = re.exec(json)) !== null) {
        out.push({
            anchor: m[1],
            synthesis: unescapeJsonString(m[2]),
            version: Number.parseInt(m[3], 10),
        });
    }
    return out;
}
