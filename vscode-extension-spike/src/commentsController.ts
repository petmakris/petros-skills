// CommentsController glue.
//
// VS Code's Comments API gives us per-line gutter affordance, threaded
// comments, and a built-in reply input. We map one CommentThread per anchor:
//
//   - Adding a thread to an editor by anchor → gutter icon + bubble.
//   - The thread's body is one synthesized "comment" (Claude's response).
//     Each thread-changed event replaces it, so the bubble always shows the
//     latest synthesis. We don't preserve history visually — the IntelliJ
//     popup didn't either; it always showed `latest_synthesis`.
//   - User submits a CommentReply → postComment(anchor, text). The thread
//     stays in "Pending" state until the next thread-changed lands.

import * as vscode from "vscode";
import { ReviewSessionClient } from "./sessionClient";
import { renderSynthesis } from "./markdown";
import { parseAnchor } from "./types";

const CONTROLLER_ID = "ireview.comments";

interface ThreadEntry {
    anchor: string;
    thread: vscode.CommentThread;
}

export class IReviewCommentsController implements vscode.Disposable {
    private readonly controller: vscode.CommentController;
    private readonly threadsByAnchor = new Map<string, ThreadEntry>();
    private readonly disposables: vscode.Disposable[] = [];

    constructor(
        private readonly client: ReviewSessionClient,
        private readonly workspaceRoot: string
    ) {
        this.controller = vscode.comments.createCommentController(
            CONTROLLER_ID,
            "Interactive Review"
        );
        // Allow user to comment on any line in any file. Server validates
        // the anchor on submit; if the file isn't part of the PR diff, the
        // submit will fail and the pending mark will clear.
        this.controller.commentingRangeProvider = {
            provideCommentingRanges: (document) => {
                const lineCount = document.lineCount;
                return [new vscode.Range(0, 0, Math.max(0, lineCount - 1), 0)];
            },
        };
        this.controller.options = {
            prompt: "Ask Claude about this line",
            placeHolder: "What does this do? Is the null check needed? …",
        };

        // Wire up listeners.
        this.client.addListener({
            onAttached: () => this.refreshFromCache(),
            onDetached: () => this.disposeAllThreads(),
            onThreadChanged: (anchor, synthesis, version) =>
                this.upsertThread(anchor, synthesis, version),
            onThreadDeleted: (anchor) => this.removeThread(anchor),
            onPendingChanged: (anchor, pending) =>
                this.updatePendingLabel(anchor, pending),
        });

        // Re-attach threads when a file is opened (new editor for a path
        // we have anchors on).
        this.disposables.push(
            vscode.window.onDidChangeVisibleTextEditors(() => this.refreshFromCache())
        );
    }

    private refreshFromCache(): void {
        const snap = this.client.snapshotCache();
        for (const [anchor, t] of snap) {
            this.upsertThread(anchor, t.synthesis, t.version);
        }
    }

    private upsertThread(anchor: string, synthesis: string, version: number): void {
        const parsed = parseAnchor(anchor);
        if (!parsed) return;
        const uri = vscode.Uri.file(`${this.workspaceRoot}/${parsed.path}`);
        const line0 = Math.max(0, parsed.line - 1);
        const endLine0 = Math.max(line0, parsed.endLine - 1);
        const range = new vscode.Range(line0, 0, endLine0, 0);

        const comment: vscode.Comment = {
            body: renderSynthesis(synthesis),
            mode: vscode.CommentMode.Preview,
            author: { name: `Claude · v${version}` },
            contextValue: "ireview-synthesis",
        };

        let entry = this.threadsByAnchor.get(anchor);
        if (entry) {
            entry.thread.range = range;
            entry.thread.comments = [comment];
            entry.thread.label = `v${version}`;
        } else {
            const thread = this.controller.createCommentThread(uri, range, [comment]);
            thread.label = `v${version}`;
            thread.canReply = true;
            thread.collapsibleState = vscode.CommentThreadCollapsibleState.Collapsed;
            thread.contextValue = anchor; // exposed as `commentThread` in when-clauses
            entry = { anchor, thread };
            this.threadsByAnchor.set(anchor, entry);
        }
    }

    private removeThread(anchor: string): void {
        const entry = this.threadsByAnchor.get(anchor);
        if (!entry) return;
        entry.thread.dispose();
        this.threadsByAnchor.delete(anchor);
    }

    private updatePendingLabel(anchor: string, pending: boolean): void {
        const entry = this.threadsByAnchor.get(anchor);
        if (!entry) return;
        // Stash the version part of the label, append/remove a marker.
        const base = entry.thread.label?.replace(/ · thinking…$/, "") ?? "";
        entry.thread.label = pending ? `${base} · thinking…` : base;
    }

    private disposeAllThreads(): void {
        for (const e of this.threadsByAnchor.values()) e.thread.dispose();
        this.threadsByAnchor.clear();
    }

    /** Handler for the registered ireview.reply command (wired in extension.ts). */
    async handleReply(reply: vscode.CommentReply): Promise<void> {
        const anchor = reply.thread.contextValue;
        if (!anchor) return;
        const text = reply.text.trim();
        if (!text) return;
        try {
            await this.client.postComment(anchor, text);
        } catch (err) {
            vscode.window.showErrorMessage(
                `interactive-review: submit failed — ${(err as Error).message}`
            );
        }
    }

    /**
     * Programmatic open: focus or create the thread for an anchor.
     * Used by the side-panel row-click handler.
     */
    async revealAnchor(anchor: string): Promise<void> {
        const parsed = parseAnchor(anchor);
        if (!parsed) return;
        const uri = vscode.Uri.file(`${this.workspaceRoot}/${parsed.path}`);
        const line0 = Math.max(0, parsed.line - 1);
        const doc = await vscode.workspace.openTextDocument(uri);
        const editor = await vscode.window.showTextDocument(doc, {
            selection: new vscode.Range(line0, 0, line0, 0),
            preserveFocus: false,
        });
        editor.revealRange(
            new vscode.Range(line0, 0, line0, 0),
            vscode.TextEditorRevealType.InCenter
        );
        // If the thread already exists, expand it.
        const entry = this.threadsByAnchor.get(anchor);
        if (entry) {
            entry.thread.collapsibleState =
                vscode.CommentThreadCollapsibleState.Expanded;
        }
    }

    dispose(): void {
        this.disposeAllThreads();
        for (const d of this.disposables) d.dispose();
        this.controller.dispose();
    }
}
