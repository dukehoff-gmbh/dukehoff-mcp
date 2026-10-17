/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';

/**
 * A short section in the project's AGENTS.md that tells agents when to use the Java tools. Agents pick
 * tools by what they read; project instructions are the strongest hint they get (Copilot, Codex, Cursor
 * and others read AGENTS.md). The section is only written after the user saw and confirmed it, and it
 * sits between markers, so running the command again updates it instead of adding a second one.
 */

export const COMMAND = 'dukehoffMcp.addAgentHints';
const START = '<!-- dukehoff-mcp:start -->';
const END = '<!-- dukehoff-mcp:end -->';
const PROPOSED = 'dukehoff-mcp-proposed';
const NOT_AGAIN = 'agentHints.notAgain';
const ASKED = 'agentHints.asked';

export const SECTION = `${START}
## Java code

To rename, move or delete Java code or change a method's signature, use the Java MCP tools
(\`rename\`, \`move\`, \`change_signature\`, \`safe_delete\`) instead of editing files: one call updates
every reference, also in other modules, and checks that everything still compiles.
To find who calls or uses a method, field or class, use \`find_usages\` (\`group_by: "caller"\` names the
calling methods).
For the same kind of change at many places (migrations, modernizations, replacing an API), use
\`apply_rule\`: structural rewrite rules that check types and match whole statements, not text.
For JUnit 4 to 5, javax to jakarta or a Java modernization, start with \`apply_rule\` and
\`pack: "junit5"\`, \`"jakarta"\` or \`"modernize"\` (a dry run first), then finish what the result lists by hand.
Before calling a method of the JDK or a dependency whose exact signature you are unsure of, look it
up with \`outline\`.
${END}
`;

/** The text with the section added at the end, or replacing the one between the markers. */
export function withSection(text: string | undefined, section: string = SECTION): string {
    if (text === undefined || text.trim() === '') {
        return section;
    }
    if (text.includes('\r\n')) {
        section = section.replace(/\r?\n/g, '\r\n');
    }
    const start = text.indexOf(START);
    const end = text.indexOf(END);
    if (start >= 0 && end > start) {
        let after = end + END.length;
        if (text.startsWith('\r\n', after)) {
            after += 2;
        } else if (text.startsWith('\n', after)) {
            after += 1;
        }
        return text.substring(0, start) + section + text.substring(after);
    }
    const eol = text.includes('\r\n') ? '\r\n' : '\n';
    return text.replace(/\s*$/, eol + eol) + section;
}

/** Proposed contents, shown in the diff before anything is written. */
class Proposals implements vscode.TextDocumentContentProvider {

    readonly texts = new Map<string, string>();

    provideTextDocumentContent(uri: vscode.Uri): string {
        return this.texts.get(uri.path) ?? '';
    }
}

export function register(context: vscode.ExtensionContext, log: vscode.OutputChannel): void {
    const proposals = new Proposals();
    context.subscriptions.push(
        vscode.workspace.registerTextDocumentContentProvider(PROPOSED, proposals),
        vscode.commands.registerCommand(COMMAND, () => addHints(proposals, log)));
}

/** Shows the AGENTS.md of a workspace folder with the section against the file now; writes it on confirmation. */
async function addHints(proposals: Proposals, log: vscode.OutputChannel): Promise<void> {
    const folder = await pickFolder();
    if (!folder) {
        return;
    }
    const file = path.join(folder.uri.fsPath, 'AGENTS.md');
    const now = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : undefined;
    const proposed = withSection(now);
    if (now === proposed) {
        void vscode.window.showInformationMessage('Dukehoff MCP: AGENTS.md already has the current Java tool hints.');
        return;
    }
    const proposedUri = vscode.Uri.file(file).with({ scheme: PROPOSED });
    proposals.texts.set(proposedUri.path, proposed);
    const nowUri = now === undefined ? vscode.Uri.file(file).with({ scheme: PROPOSED, query: 'empty' }) : vscode.Uri.file(file);
    await vscode.commands.executeCommand('vscode.diff', nowUri, proposedUri,
        `AGENTS.md: ${now === undefined ? 'new' : 'now'} ↔ with Java tool hints`, { preview: true });
    const choice = await vscode.window.showInformationMessage(
        `Write the Java tool hints to ${vscode.workspace.asRelativePath(file)}?`,
        { modal: true, detail: 'The diff shows what changes. Agents read AGENTS.md as project instructions.' },
        'Write');
    if (choice !== 'Write') {
        return;
    }
    fs.writeFileSync(file, proposed, 'utf8');
    log.appendLine(`Java tool hints written to ${file}`);
    await vscode.window.showTextDocument(vscode.Uri.file(file));
}

async function pickFolder(): Promise<vscode.WorkspaceFolder | undefined> {
    const folders = vscode.workspace.workspaceFolders ?? [];
    if (folders.length <= 1) {
        if (folders.length === 0) {
            void vscode.window.showWarningMessage('Dukehoff MCP: open a folder first.');
        }
        return folders[0];
    }
    return vscode.window.showWorkspaceFolderPick({ placeHolder: 'The folder whose AGENTS.md gets the Java tool hints' });
}

/**
 * Once per workspace, when no folder's AGENTS.md has the section: offers the command. "Don't ask again"
 * turns this off everywhere.
 */
export async function offerOnce(context: vscode.ExtensionContext): Promise<void> {
    if (context.globalState.get<boolean>(NOT_AGAIN) || context.workspaceState.get<boolean>(ASKED)) {
        return;
    }
    const folders = vscode.workspace.workspaceFolders ?? [];
    const hasSection = folders.some(f => {
        const file = path.join(f.uri.fsPath, 'AGENTS.md');
        return fs.existsSync(file) && fs.readFileSync(file, 'utf8').includes(START);
    });
    if (hasSection) {
        return;
    }
    await context.workspaceState.update(ASKED, true);
    const choice = await vscode.window.showInformationMessage(
        'Dukehoff MCP: agents use the Java tools (rename, find usages, ...) more reliably with a short hint in AGENTS.md. Add it?',
        'Show me', 'Not now', 'Don\'t ask again');
    if (choice === 'Show me') {
        await vscode.commands.executeCommand(COMMAND);
    } else if (choice === 'Don\'t ask again') {
        await context.globalState.update(NOT_AGAIN, true);
    }
}
