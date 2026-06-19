// Status-bar item. Mirrors com.petros.ireview.ReviewStatusBarWidget:
//   DORMANT       → "Review · idle"
//   CONNECTING    → "Review · connecting…"
//   ACTIVE        → "Review · <pr_ref>"
//   DISCONNECTED  → "Review · reconnecting…"
//
// Click action copies "/interactive-review " to the clipboard so the user
// can paste it into a Claude Code session.

import * as vscode from "vscode";
import { ReviewSessionClient } from "./sessionClient";
import type { ClientState } from "./types";

export class IReviewStatusBar implements vscode.Disposable {
    private readonly item: vscode.StatusBarItem;

    constructor(private readonly client: ReviewSessionClient) {
        this.item = vscode.window.createStatusBarItem(
            vscode.StatusBarAlignment.Left,
            100
        );
        this.item.command = "ireview.copySlashCommand";
        this.item.tooltip =
            "Click to copy `/interactive-review` to the clipboard";
        this.update("DORMANT");
        this.item.show();

        client.addListener({
            onStateChanged: (state) => this.update(state),
            onAttached: () => this.update("ACTIVE"),
            onDetached: () => this.update("DORMANT"),
        });
    }

    private update(state: ClientState): void {
        const session = this.client.currentSession();
        switch (state) {
            case "DORMANT":
                this.item.text = "$(comment-discussion) Review · idle";
                break;
            case "CONNECTING":
                this.item.text = "$(sync~spin) Review · connecting…";
                break;
            case "ACTIVE":
                this.item.text = session
                    ? `$(check) Review · ${truncate(session.prRef, 28)}`
                    : "$(check) Review · active";
                break;
            case "DISCONNECTED":
                this.item.text = "$(sync~spin) Review · reconnecting…";
                break;
        }
    }

    dispose(): void {
        this.item.dispose();
    }
}

function truncate(s: string, max: number): string {
    return s.length <= max ? s : s.slice(0, max - 1) + "…";
}
