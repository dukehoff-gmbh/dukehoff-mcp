/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import * as agentHints from './agentHints';

/**
 * Registers the Java MCP server for Copilot (and any agent in VS Code). The server runs inside the
 * NetBeans of the Apache NetBeans extension, which loads our module from nbcode/ of this extension.
 * Each workspace folder gets a stdio server definition: the launcher's proxy
 * (java -jar bin/dukehoff-mcp.jar --connect-file <file> <folder>). This extension writes the server's
 * socket into that file once NetBeans runs (LSP command nbls.dukehoff-mcp.connection, which starts the
 * server inside NetBeans on its first call); the proxy waits for it. Not through
 * resolveMcpServerDefinition: Copilot's agent host starts servers without calling it.
 *
 * Refactorings write to disk on their own, outside the agent's edits. This extension asks NetBeans for
 * them (LSP command nbls.dukehoff-mcp.changes, a long poll) and, per the setting
 * dukehoffMcp.refactoringReview, offers each one as a multi-file diff and to undo it.
 */

const NETBEANS = 'asf.apache-netbeans-java';
const CONNECTION = 'nbls.dukehoff-mcp.connection';
const CHANGES = 'nbls.dukehoff-mcp.changes';
const BEFORE = 'dukehoff-mcp-before';
const KEPT = 20;
const CONNECT_TIMEOUT_MS = 180_000;
const JAVA_PROJECT = '{**/pom.xml,**/build.gradle,**/build.gradle.kts,**/build.xml,**/*.java}';

export function activate(context: vscode.ExtensionContext): void {
    const log = vscode.window.createOutputChannel('Dukehoff MCP for Java');
    const changed = new vscode.EventEmitter<void>();
    context.subscriptions.push(log, changed, vscode.workspace.onDidChangeWorkspaceFolders(() => changed.fire()));
    const jar = path.join(context.extensionPath, 'bin', 'dukehoff-mcp.jar');
    const version: string = context.extension.packageJSON.version;
    const storage = (context.storageUri ?? context.globalStorageUri).fsPath;
    const connectionFile = path.join(storage, 'connection');
    fs.mkdirSync(storage, { recursive: true });
    fs.rmSync(connectionFile, { force: true }); // an earlier NetBeans' socket

    context.subscriptions.push(vscode.lm.registerMcpServerDefinitionProvider('dukehoff-mcp', {
        onDidChangeMcpServerDefinitions: changed.event,
        provideMcpServerDefinitions: () => {
            const folders = vscode.workspace.workspaceFolders ?? [];
            return folders.map(folder => new vscode.McpStdioServerDefinition(
                folders.length > 1 ? `Java (${folder.name})` : 'Java',
                javaCommand(),
                ['-jar', jar, '--connect-file', connectionFile, folder.uri.fsPath],
                {},
                version));
        }
    }));

    agentHints.register(context, log);
    const changes = new Changes(log);
    context.subscriptions.push(changes, vscode.workspace.registerTextDocumentContentProvider(BEFORE, changes));

    // only for Java workspaces: activating the NetBeans extension starts NetBeans
    vscode.workspace.findFiles(JAVA_PROJECT, '**/node_modules/**', 1).then(async found => {
        if (found.length > 0) {
            if (await writeConnection(connectionFile, log)) {
                void changes.watch();
                void agentHints.offerOnce(context);
            }
        } else {
            log.appendLine('no Java project in this workspace; NetBeans is not started for the MCP server');
        }
    });
}

export function deactivate(): void {
    // the server lives inside NetBeans and ends with it
}

/** Writes the socket of the server inside NetBeans (or "error: ...") for the proxy to read; whether it runs. */
async function writeConnection(file: string, log: vscode.OutputChannel): Promise<boolean> {
    let content: string;
    let running = false;
    try {
        content = await connection(log);
        running = true;
        log.appendLine(`server inside NetBeans: ${content}`);
    } catch (e) {
        content = `error: ${e instanceof Error ? e.message : String(e)}`;
        log.appendLine(content);
    }
    fs.writeFileSync(file, content + '\n', 'utf8');
    return running;
}

/** A file a tool changed: oldPath is null for a created file, newPath for a deleted one. */
interface FileChange {
    oldPath: string | null;
    newPath: string | null;
    before: string | null;
    after: string | null;
}

/** One tool call's changes, as the server's ChangeLog reports them. */
interface Change {
    seq: number;
    summary: string;
    files: FileChange[];
}

type Review = 'off' | 'notify' | 'openDiff';

/**
 * The refactorings of this window's server: asks for them, shows them per the setting, and serves the
 * content of files before a refactoring (URIs dukehoff-mcp-before:<path>?<seq>-<index>) to the diff.
 */
class Changes implements vscode.TextDocumentContentProvider, vscode.Disposable {

    private readonly kept = new Map<number, Change>();
    private active = true;

    constructor(private readonly log: vscode.OutputChannel) {
    }

    dispose(): void {
        this.active = false;
    }

    provideTextDocumentContent(uri: vscode.Uri): string {
        const [seq, index] = uri.query.split('-').map(Number);
        return this.kept.get(seq)?.files[index]?.before ?? '';
    }

    /** Asks the server for new changes until disposed; it answers when there are some, or empty after a while. */
    async watch(): Promise<void> {
        let after = 0;
        while (this.active) {
            try {
                const entries = await vscode.commands.executeCommand<Change[]>(CHANGES, after) ?? [];
                for (const change of entries) {
                    after = Math.max(after, change.seq);
                    this.keep(change);
                    void this.show(change);
                }
            } catch (e) {
                const message = e instanceof Error ? e.message : String(e);
                if (message.includes('not found')) {
                    // NetBeans runs a module older than this extension (installed while it ran)
                    this.log.appendLine(`refactoring review is off: NetBeans does not know ${CHANGES} (${message}); `
                        + 'reload the window to start NetBeans with this extension\'s module');
                    return;
                }
                this.log.appendLine(`asking for refactoring changes failed: ${message}`);
                await new Promise(resolve => setTimeout(resolve, 5000));
            }
        }
    }

    private keep(change: Change): void {
        this.kept.set(change.seq, change);
        for (const seq of this.kept.keys()) {
            if (this.kept.size <= KEPT) {
                break;
            }
            this.kept.delete(seq);
        }
    }

    private async show(change: Change): Promise<void> {
        const mode = vscode.workspace.getConfiguration('dukehoffMcp').get<Review>('refactoringReview', 'notify');
        this.log.appendLine(`${change.summary}: ${change.files.map(f => f.newPath ?? f.oldPath).join(', ')}`);
        if (mode === 'off') {
            return;
        }
        if (mode === 'openDiff') {
            await this.review(change);
        }
        const n = change.files.length;
        const actions = mode === 'openDiff' ? ['Undo'] : ['Review', 'Undo'];
        const choice = await vscode.window.showInformationMessage(
            `Dukehoff MCP: ${change.summary} (${n} ${n === 1 ? 'file' : 'files'})`, ...actions);
        if (choice === 'Review') {
            await this.review(change);
        } else if (choice === 'Undo') {
            await this.undo(change);
        }
    }

    /** The multi-file diff: each file before the refactoring against the file on disk now. */
    private async review(change: Change): Promise<void> {
        const resources = change.files.map((f, i) => {
            const shown = (f.newPath ?? f.oldPath)!;
            const before = vscode.Uri.file((f.oldPath ?? shown)).with({ scheme: BEFORE, query: `${change.seq}-${i}` });
            // a deleted file against an empty text
            const now = f.newPath ? vscode.Uri.file(f.newPath) : vscode.Uri.file(shown).with({ scheme: BEFORE, query: 'deleted' });
            return [vscode.Uri.file(shown), before, now];
        });
        await vscode.commands.executeCommand('vscode.changes', `Dukehoff MCP: ${change.summary}`, resources);
    }

    /** Writes the files back as they were, unless one was changed since the refactoring. */
    private async undo(change: Change): Promise<void> {
        const changedSince = change.files.filter(f => f.newPath
            ? read(f.newPath) !== f.after
            : f.oldPath !== null && fs.existsSync(f.oldPath));
        if (changedSince.length > 0) {
            const names = changedSince.map(f => path.basename((f.newPath ?? f.oldPath)!)).join(', ');
            const choice = await vscode.window.showWarningMessage(
                `Not undone, changed since the refactoring: ${names}. Undo it with git or by hand.`, 'Review');
            if (choice === 'Review') {
                await this.review(change);
            }
            return;
        }
        for (const f of change.files) {
            if (f.newPath && f.newPath !== f.oldPath) {
                fs.rmSync(f.newPath, { force: true });
            }
            if (f.oldPath && f.before !== null) {
                fs.mkdirSync(path.dirname(f.oldPath), { recursive: true });
                fs.writeFileSync(f.oldPath, f.before, 'utf8');
            }
        }
        this.kept.delete(change.seq);
        this.log.appendLine(`undone: ${change.summary}`);
        void vscode.window.showInformationMessage(`Dukehoff MCP: undone: ${change.summary}`);
    }
}

/** A file's text, or null if it does not exist. */
function read(file: string): string | null {
    try {
        return fs.readFileSync(file, 'utf8');
    } catch {
        return null;
    }
}

async function connection(log: vscode.OutputChannel): Promise<string> {
    if (vscode.workspace.getConfiguration('netbeans').get<boolean>('javaSupport.enabled') === false) {
        throw new Error('Dukehoff MCP for Java needs NetBeans\' Java support: enable the setting netbeans.javaSupport.enabled');
    }
    const netbeans = vscode.extensions.getExtension(NETBEANS);
    if (!netbeans) {
        throw new Error(`the extension ${NETBEANS} (Apache NetBeans Language Server) is not installed`);
    }
    await netbeans.activate();
    const deadline = Date.now() + CONNECT_TIMEOUT_MS;
    let last: unknown;
    while (Date.now() < deadline) {
        try {
            // registered by the language client once NetBeans' LSP server runs
            const answer = await vscode.commands.executeCommand<{ socket?: string; version?: string }>(CONNECTION);
            if (answer?.socket) {
                log.appendLine(`NetBeans module version ${answer.version}`);
                return answer.socket;
            }
            last = answer;
        } catch (e) {
            last = e;
            if (e instanceof Error && e.message.includes('javaSupport')) {
                throw e;
            }
        }
        await new Promise(resolve => setTimeout(resolve, 2000));
    }
    throw new Error(`NetBeans did not provide the Java MCP server within ${CONNECT_TIMEOUT_MS / 1000} s: ${String(last)}`);
}

/** The java to run the proxy with: NetBeans' configured JDK, JAVA_HOME, or java on the PATH. */
function javaCommand(): string {
    const exe = process.platform === 'win32' ? 'java.exe' : 'java';
    const homes = [vscode.workspace.getConfiguration('netbeans').get<string>('jdkhome'), process.env.JAVA_HOME];
    for (const home of homes) {
        if (home && fs.existsSync(path.join(home, 'bin', exe))) {
            return path.join(home, 'bin', exe);
        }
    }
    return exe;
}
