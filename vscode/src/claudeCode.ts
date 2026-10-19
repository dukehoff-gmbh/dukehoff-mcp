/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
import { execFile } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as vscode from 'vscode';

/**
 * The Java tools for Claude Code. Its VS Code extension runs its own agent with its own MCP configuration
 * and does not see the servers VS Code's chat gets from this extension. The command registers the same
 * server, the proxy to the server inside this window's NetBeans, with Claude Code for one folder, in its
 * local scope (`claude mcp add-json --scope local`): private to the user and that folder, not in the
 * repository, and no approval prompt. It shows the entry first and writes it only on confirmation.
 *
 * The entry names the proxy at a fixed path in this extension's global storage, copied there at every
 * start, so it stays valid when the extension is updated (the extension's own folder carries the version).
 */

export const COMMAND = 'dukehoffMcp.addToClaudeCode';
export const NAME = 'java';
const CLAUDE_EXTENSION = 'anthropic.claude-code';
const NOT_AGAIN = 'claudeCode.notAgain';
const ASKED = 'claudeCode.asked';

/** Claude Code's entry for the server: the proxy, run with the given java, for one folder. */
export function serverConfig(java: string, jar: string, args: string[]): object {
    return { type: 'stdio', command: java, args: ['-jar', jar, ...args] };
}

/** The claude program of Claude Code's VS Code extension, if it has one, else claude on the PATH. */
export function claudeExecutable(extensionPath: string | undefined, platform: string,
        exists: (file: string) => boolean): string {
    if (extensionPath) {
        const file = path.join(extensionPath, 'resources', 'native-binary', platform === 'win32' ? 'claude.exe' : 'claude');
        if (exists(file)) {
            return file;
        }
    }
    return 'claude';
}

/** Copies the proxy to the extension's global storage (a path that survives updates); returns that path. */
export function installJar(context: vscode.ExtensionContext, jar: string, version: string,
        log: vscode.OutputChannel): string {
    const dir = context.globalStorageUri.fsPath;
    const target = path.join(dir, 'dukehoff-mcp.jar');
    const marker = target + '.version';
    try {
        if (!fs.existsSync(target) || read(marker) !== version) {
            fs.mkdirSync(dir, { recursive: true });
            fs.copyFileSync(jar, target);
            fs.writeFileSync(marker, version, 'utf8');
        }
    } catch (e) {
        log.appendLine(`could not copy the proxy for Claude Code to ${target}: ${e instanceof Error ? e.message : String(e)}`);
    }
    return target;
}

function read(file: string): string | undefined {
    try {
        return fs.readFileSync(file, 'utf8');
    } catch {
        return undefined;
    }
}

/** What the command needs from the extension: the java, the proxy's fixed path and the proxy's arguments. */
export interface Server {
    java(): string;
    jar: string;
    args(folder: vscode.WorkspaceFolder): string[];
}

export function register(context: vscode.ExtensionContext, log: vscode.OutputChannel, server: Server): void {
    context.subscriptions.push(vscode.commands.registerCommand(COMMAND, () => add(log, server)));
}

/**
 * The server named NAME in Claude Code's local scope for a folder, from its configuration (projects by
 * folder). Read, not asked: `claude mcp get` starts the server to check the connection.
 */
export function localEntry(config: unknown, folder: string): object | undefined {
    const projects = (config as { projects?: Record<string, { mcpServers?: Record<string, object> }> })?.projects;
    return projects?.[folder]?.mcpServers?.[NAME];
}

function readLocalEntry(folder: string): object | undefined {
    const dir = process.env.CLAUDE_CONFIG_DIR;
    const file = dir ? path.join(dir, '.claude.json') : path.join(os.homedir(), '.claude.json');
    try {
        return localEntry(JSON.parse(fs.readFileSync(file, 'utf8')), folder);
    } catch {
        return undefined;
    }
}

function claude(): string {
    return claudeExecutable(vscode.extensions.getExtension(CLAUDE_EXTENSION)?.extensionPath, process.platform, fs.existsSync);
}

/** Runs a claude command; missing: the program itself was not found. */
function run(program: string, args: string[], cwd: string): Promise<{ ok: boolean, missing: boolean, out: string }> {
    return new Promise(resolve => execFile(program, args, { cwd, timeout: 30_000 }, (error, stdout, stderr) =>
        resolve({
            ok: !error,
            missing: (error as NodeJS.ErrnoException | null)?.code === 'ENOENT',
            // without its colors
            out: ((stdout + stderr).trim() || (error ? error.message : '')).replace(/\x1b\[[0-9;]*m/g, '')
        })));
}

/** Shows the entry for a folder and registers it with Claude Code on confirmation. */
async function add(log: vscode.OutputChannel, server: Server): Promise<void> {
    const folder = await pickFolder();
    if (!folder) {
        return;
    }
    const cwd = folder.uri.fsPath;
    const json = JSON.stringify(serverConfig(server.java(), server.jar, server.args(folder)));
    const program = claude();
    if ((await run(program, ['--version'], cwd)).missing) {
        // no Claude Code program to call: the user can run the command in a terminal in that folder
        const command = `claude mcp add-json --scope local ${NAME} '${json}'`;
        const choice = await vscode.window.showWarningMessage(
            'Dukehoff MCP: Claude Code was not found (neither its VS Code extension nor claude on the PATH). '
            + `Run this in a terminal in ${folder.name}:`, { modal: true, detail: command }, 'Copy command');
        if (choice === 'Copy command') {
            await vscode.env.clipboard.writeText(command);
        }
        return;
    }
    const existing = readLocalEntry(cwd);
    const replaces = existing && !JSON.stringify(existing).includes(JSON.stringify(server.jar))
        ? `\n\nThis replaces the server "${NAME}" Claude Code has for this folder now:\n${JSON.stringify(existing)}` : '';
    const choice = await vscode.window.showInformationMessage(
        `Use the Java tools in Claude Code for ${folder.name}?`,
        {
            modal: true,
            detail: `Registers the server "${NAME}" for this folder in Claude Code's local scope (only for you, not `
                + `in the repository), as the proxy to the Java server of this VS Code window:\n\n${json}`
                + `${replaces}\n\nStart a new Claude Code session afterwards. The tools work while this window is open.`
        },
        'Add');
    if (choice !== 'Add') {
        return;
    }
    if (existing) {
        await run(program, ['mcp', 'remove', '--scope', 'local', NAME], cwd);
    }
    const added = await run(program, ['mcp', 'add-json', '--scope', 'local', NAME, json], cwd);
    log.appendLine(`claude mcp add-json in ${cwd}: ${added.out}`);
    if (added.ok) {
        void vscode.window.showInformationMessage(
            `Dukehoff MCP: Claude Code has the Java tools for ${folder.name} (server "${NAME}"). Start a new Claude Code session to use them.`);
    } else {
        void vscode.window.showErrorMessage(`Dukehoff MCP: Claude Code did not add the server: ${added.out}`);
    }
}

async function pickFolder(): Promise<vscode.WorkspaceFolder | undefined> {
    const folders = vscode.workspace.workspaceFolders ?? [];
    if (folders.length <= 1) {
        if (folders.length === 0) {
            void vscode.window.showWarningMessage('Dukehoff MCP: open a folder first.');
        }
        return folders[0];
    }
    return vscode.window.showWorkspaceFolderPick({ placeHolder: 'The folder Claude Code gets the Java tools for' });
}

/**
 * Once per workspace, when Claude Code's extension is installed: offers the command. "Don't ask again"
 * turns this off everywhere.
 */
export async function offerOnce(context: vscode.ExtensionContext): Promise<void> {
    if (!vscode.extensions.getExtension(CLAUDE_EXTENSION)
            || context.globalState.get<boolean>(NOT_AGAIN) || context.workspaceState.get<boolean>(ASKED)) {
        return;
    }
    await context.workspaceState.update(ASKED, true);
    const choice = await vscode.window.showInformationMessage(
        'Dukehoff MCP: Claude Code does not see the Java tools of VS Code\'s chat. Add them to Claude Code for this folder?',
        'Show me', 'Not now', 'Don\'t ask again');
    if (choice === 'Show me') {
        await vscode.commands.executeCommand(COMMAND);
    } else if (choice === 'Don\'t ask again') {
        await context.globalState.update(NOT_AGAIN, true);
    }
}
