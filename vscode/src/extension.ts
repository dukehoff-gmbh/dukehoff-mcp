/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';

/**
 * Registers the Java MCP server for Copilot (and any agent in VS Code). The server runs inside the
 * NetBeans of the Apache NetBeans extension, which loads our module from nbcode/ of this extension.
 * Each workspace folder gets a stdio server definition: the launcher's proxy
 * (java -jar bin/dukehoff-mcp.jar --connect-file <file> <folder>). This extension writes the server's
 * socket into that file once NetBeans runs (LSP command nbls.dukehoff-mcp.connection, which starts the
 * server inside NetBeans on its first call); the proxy waits for it. Not through
 * resolveMcpServerDefinition: Copilot's agent host starts servers without calling it.
 */

const NETBEANS = 'asf.apache-netbeans-java';
const CONNECTION = 'nbls.dukehoff-mcp.connection';
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

    // only for Java workspaces: activating the NetBeans extension starts NetBeans
    vscode.workspace.findFiles(JAVA_PROJECT, '**/node_modules/**', 1).then(found => {
        if (found.length > 0) {
            writeConnection(connectionFile, log);
        } else {
            log.appendLine('no Java project in this workspace; NetBeans is not started for the MCP server');
        }
    });
}

export function deactivate(): void {
    // the server lives inside NetBeans and ends with it
}

/** Writes the socket of the server inside NetBeans (or "error: ...") for the proxy to read. */
async function writeConnection(file: string, log: vscode.OutputChannel): Promise<void> {
    let content: string;
    try {
        content = await connection(log);
        log.appendLine(`server inside NetBeans: ${content}`);
    } catch (e) {
        content = `error: ${e instanceof Error ? e.message : String(e)}`;
        log.appendLine(content);
    }
    fs.writeFileSync(file, content + '\n', 'utf8');
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
