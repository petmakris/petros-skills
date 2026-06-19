// Extension entry point.
//
// Lifecycle:
//   activate()
//     1. Read ~/.claude/interactive-review/server.json for base URL.
//     2. Take the first workspace folder as the project cwd.
//     3. Create a singleton ReviewSessionClient.
//     4. Wire up: status bar, comments controller, annotations side panel.
//     5. Register commands.
//   deactivate()
//     - dispose everything; client.stop() cancels timers + SSE.

import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import * as vscode from "vscode";
import { ReviewSessionClient } from "./sessionClient";
import { IReviewStatusBar } from "./statusBar";
import { IReviewCommentsController } from "./commentsController";
import { AnnotationsTreeProvider } from "./annotationsView";
import { parseNavTarget } from "./markdown";

let disposables: vscode.Disposable[] = [];

export function activate(context: vscode.ExtensionContext): void {
    const folders = vscode.workspace.workspaceFolders;
    const workspaceRoot = folders && folders.length > 0 ? folders[0].uri.fsPath : os.homedir();

    const baseUrl = resolveServerUrl() ?? "http://127.0.0.1:54620";
    const client = new ReviewSessionClient(baseUrl, workspaceRoot, 5000);

    const statusBar = new IReviewStatusBar(client);
    const comments = new IReviewCommentsController(client, workspaceRoot);
    const tree = new AnnotationsTreeProvider(client);

    const treeView = vscode.window.createTreeView("ireview.annotations", {
        treeDataProvider: tree,
        showCollapseAll: false,
    });

    // Comments reply command — wired into the controller's reply action.
    // VS Code routes the input box's submit to whatever command we register
    // as the controller's reply handler; the cleanest pattern is a global
    // command that delegates to the controller.
    const replyCmd = vscode.commands.registerCommand(
        "ireview.reply",
        (reply: vscode.CommentReply) => comments.handleReply(reply)
    );

    const openAnchorCmd = vscode.commands.registerCommand(
        "ireview.openAnchor",
        async (anchor: string, version: number) => {
            tree.markSeen(anchor, version);
            await comments.revealAnchor(anchor);
        }
    );

    const deleteCmd = vscode.commands.registerCommand(
        "ireview.deleteThread",
        async (itemOrAnchor: vscode.TreeItem | string) => {
            const anchor =
                typeof itemOrAnchor === "string"
                    ? itemOrAnchor
                    : itemOrAnchor.anchor;
            if (!anchor) return;
            try {
                await client.deleteThread(anchor);
                // Optimistic: the SSE thread-deleted will refresh the tree;
                // we don't need to do anything else.
            } catch (err) {
                vscode.window.showErrorMessage(
                    `interactive-review: delete failed — ${(err as Error).message}`
                );
            }
        }
    );

    const refreshCmd = vscode.commands.registerCommand(
        "ireview.refresh",
        () => tree.refresh()
    );

    const navigateCmd = vscode.commands.registerCommand(
        "ireview.navigate",
        async (target: string) => {
            const nav = parseNavTarget(target);
            const uri = vscode.Uri.file(path.join(workspaceRoot, nav.path));
            try {
                const doc = await vscode.workspace.openTextDocument(uri);
                const line0 = nav.line > 0 ? nav.line - 1 : 0;
                await vscode.window.showTextDocument(doc, {
                    selection: new vscode.Range(line0, 0, line0, 0),
                });
            } catch (err) {
                vscode.window.showWarningMessage(
                    `interactive-review: cannot open ${nav.path}`
                );
            }
        }
    );

    const gotoSymbolCmd = vscode.commands.registerCommand(
        "ireview.gotoSymbol",
        async (symbol: string) => {
            // Use the workspace symbol provider — the LSP equivalent of the
            // IntelliJ PsiShortNamesCache lookup.
            const results = (await vscode.commands.executeCommand<vscode.SymbolInformation[]>(
                "vscode.executeWorkspaceSymbolProvider",
                symbol
            )) ?? [];
            const exact = results.filter((r) => r.name === symbol);
            const pick = exact.length > 0 ? exact : results;
            if (pick.length === 0) {
                vscode.window.showInformationMessage(
                    `interactive-review: no symbol named '${symbol}'`
                );
                return;
            }
            const chosen =
                pick.length === 1
                    ? pick[0]
                    : await vscode.window.showQuickPick(
                          pick.map((s) => ({
                              label: s.name,
                              description: `${vscode.SymbolKind[s.kind]} · ${vscode.workspace.asRelativePath(
                                  s.location.uri
                              )}`,
                              symbol: s,
                          })),
                          { placeHolder: `Multiple matches for '${symbol}'` }
                      ).then((p) => p?.symbol);
            if (!chosen) return;
            const doc = await vscode.workspace.openTextDocument(chosen.location.uri);
            await vscode.window.showTextDocument(doc, {
                selection: chosen.location.range,
            });
        }
    );

    const copyCmd = vscode.commands.registerCommand(
        "ireview.copySlashCommand",
        async () => {
            await vscode.env.clipboard.writeText("/interactive-review ");
            vscode.window.setStatusBarMessage(
                "Copied `/interactive-review ` to clipboard",
                3000
            );
        }
    );

    client.start();

    disposables = [
        statusBar,
        comments,
        tree,
        treeView,
        replyCmd,
        openAnchorCmd,
        deleteCmd,
        refreshCmd,
        navigateCmd,
        gotoSymbolCmd,
        copyCmd,
        // ReviewSessionClient exposes stop() rather than dispose().
        { dispose: () => client.stop() },
    ] as unknown as vscode.Disposable[];

    for (const d of disposables) context.subscriptions.push(d);
}

export function deactivate(): void {
    for (const d of disposables) {
        try {
            (d as { dispose?: () => void }).dispose?.();
        } catch {
            // ignore
        }
    }
    disposables = [];
}

/**
 * Read the interactive_review server URL from
 * ~/.claude/interactive-review/server.json. The skill writes it on server
 * start. Returns null if missing/unreadable — caller falls back to a default.
 */
function resolveServerUrl(): string | null {
    const file = path.join(
        os.homedir(),
        ".claude",
        "interactive-review",
        "server.json"
    );
    try {
        const data = JSON.parse(fs.readFileSync(file, "utf8")) as { url?: unknown };
        return typeof data.url === "string" ? data.url : null;
    } catch {
        return null;
    }
}
