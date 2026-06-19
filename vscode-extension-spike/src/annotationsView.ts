// Activity-bar side panel ("Annotations") backed by a TreeDataProvider.
//
// One row per anchor. Row content:
//   label   = filename + ":<side>:<line>"
//   desc    = "v<version>" plus optional "new" dot
//   tooltip = snippet of synthesis
//
// Inline trash icon delete (via menus → view/item/context, group "inline").
// While an anchor is pending (post-submit, pre-SSE-confirmation), the row
// shows a "thinking…" suffix and the inline delete is hidden by gating the
// contextValue (the menu when-clause checks viewItem === "annotation"; we
// switch to "annotation-pending" to suppress it).

import * as vscode from "vscode";
import { ReviewSessionClient } from "./sessionClient";
import type { ThreadState } from "./types";

interface Row {
    anchor: string;
    snippet: string;
    version: number;
    isNew: boolean;
    pending: boolean;
}

export class AnnotationsTreeProvider
    implements vscode.TreeDataProvider<Row>, vscode.Disposable
{
    private readonly _onDidChangeTreeData = new vscode.EventEmitter<void>();
    readonly onDidChangeTreeData = this._onDidChangeTreeData.event;
    private readonly seenVersions = new Map<string, number>();
    private readonly disposables: vscode.Disposable[] = [];

    constructor(private readonly client: ReviewSessionClient) {
        this.client.addListener({
            onAttached: () => this.refresh(),
            onDetached: () => this.refresh(),
            onThreadChanged: () => this.refresh(),
            onThreadDeleted: () => this.refresh(),
            onPendingChanged: () => this.refresh(),
            onStateChanged: () => this.refresh(),
        });
    }

    refresh(): void {
        this._onDidChangeTreeData.fire();
    }

    /** Called when the user clicks a row to "see" the annotation. */
    markSeen(anchor: string, version: number): void {
        this.seenVersions.set(anchor, version);
        this.refresh();
    }

    getTreeItem(row: Row): vscode.TreeItem {
        const parts = row.anchor.split(":");
        const lineSide = parts.length >= 3 ? `${parts[parts.length - 2]}:${parts[parts.length - 1]}` : "";
        const pathPart = parts.slice(0, parts.length - 2).join(":");
        const fileName = pathPart.split("/").pop() ?? pathPart;

        const item = new vscode.TreeItem(fileName, vscode.TreeItemCollapsibleState.None);
        item.description = `${lineSide}  v${row.version}${row.isNew ? " ●" : ""}${row.pending ? "  thinking…" : ""}`;
        item.tooltip = new vscode.MarkdownString(
            `**${row.anchor}**\n\n${row.snippet}`
        );
        // contextValue gates the inline trash icon (see package.json menus).
        item.contextValue = row.pending ? "annotation-pending" : "annotation";
        item.command = {
            command: "ireview.openAnchor",
            title: "Open Annotation",
            arguments: [row.anchor, row.version],
        };
        if (row.isNew) {
            item.iconPath = new vscode.ThemeIcon(
                "circle-filled",
                new vscode.ThemeColor("editorWarning.foreground")
            );
        } else {
            item.iconPath = new vscode.ThemeIcon("comment");
        }
        // Stash the anchor on the item so command callbacks can recover it
        // when invoked from the inline icon (the menu passes the TreeItem).
        (item as vscode.TreeItem & { anchor?: string }).anchor = row.anchor;
        return item;
    }

    getChildren(): Row[] {
        const cache = this.client.snapshotCache();
        const rows: Row[] = [];
        for (const [anchor, t] of cache) {
            const last = this.seenVersions.get(anchor) ?? 0;
            rows.push({
                anchor,
                snippet: snippet(t.synthesis),
                version: t.version,
                isNew: t.version > last,
                pending: this.client.isPending(anchor),
            });
        }
        rows.sort((a, b) => a.anchor.localeCompare(b.anchor));
        return rows;
    }

    dispose(): void {
        this._onDidChangeTreeData.dispose();
        for (const d of this.disposables) d.dispose();
    }
}

function snippet(synthesis: string): string {
    if (!synthesis) return "";
    const oneLine = synthesis.replace(/\n/g, " ").replace(/\s+/g, " ").trim();
    return oneLine.length <= 160 ? oneLine : oneLine.slice(0, 159) + "…";
}

// Augment TreeItem with an `anchor` field so the inline trash menu callback
// (which receives the item) can recover the anchor without re-walking state.
declare module "vscode" {
    interface TreeItem {
        anchor?: string;
    }
}

// Helper for code that just wants the cache snapshot in a flat shape.
export function snapshotEntries(
    client: ReviewSessionClient
): { anchor: string; thread: ThreadState }[] {
    const out: { anchor: string; thread: ThreadState }[] = [];
    for (const [anchor, thread] of client.snapshotCache()) {
        out.push({ anchor, thread });
    }
    return out;
}
