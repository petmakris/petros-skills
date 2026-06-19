// Pure SSE line-to-event state machine. No HTTP, no VS Code APIs.
// Mirrors com.petros.ireview.SseClient.Parser so the same tests pass.

export interface SseEvent {
    name: string;
    data: string;
}

export class SseParser {
    private eventName = "message";
    private dataBuf: string[] = [];
    private hasData = false;
    constructor(private readonly sink: (e: SseEvent) => void) {}

    /** Feed a single line (without trailing newline). Blank line = dispatch. */
    feed(line: string): void {
        if (line.length === 0) {
            if (this.hasData) {
                this.sink({ name: this.eventName, data: this.dataBuf.join("\n") });
            }
            this.eventName = "message";
            this.dataBuf = [];
            this.hasData = false;
            return;
        }
        if (line.startsWith(":")) return; // comment line
        const colon = line.indexOf(":");
        let field: string;
        let value: string;
        if (colon < 0) {
            field = line;
            value = "";
        } else {
            field = line.substring(0, colon);
            value = line.substring(colon + 1);
        }
        if (value.startsWith(" ")) value = value.substring(1);
        switch (field) {
            case "event":
                this.eventName = value;
                break;
            case "data":
                this.dataBuf.push(value);
                this.hasData = true;
                break;
            default:
                // ignore id, retry, etc.
                break;
        }
    }
}

/**
 * Buffer a chunked byte/text stream into newline-separated lines and feed
 * the parser. Handles CRLF, LF, and split-mid-line chunks.
 */
export class LineBuffer {
    private buf = "";
    constructor(private readonly onLine: (line: string) => void) {}

    push(chunk: string): void {
        this.buf += chunk;
        let idx: number;
        // Match either \n or \r\n; \r alone is unlikely from a real HTTP stream.
        while ((idx = this.buf.indexOf("\n")) >= 0) {
            let line = this.buf.substring(0, idx);
            if (line.endsWith("\r")) line = line.substring(0, line.length - 1);
            this.buf = this.buf.substring(idx + 1);
            this.onLine(line);
        }
    }

    /** Flush any trailing data (no newline) as a final line. */
    flush(): void {
        if (this.buf.length > 0) {
            this.onLine(this.buf);
            this.buf = "";
        }
    }
}
