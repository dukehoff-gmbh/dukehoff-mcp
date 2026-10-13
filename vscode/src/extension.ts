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
 * (java -jar bin/dukehoff-mcp.jar --connect <socket> <folder>); the socket comes from the LSP command
 * nbls.dukehoff-mcp.connection, which starts the server inside NetBeans on its first call.
 */

const NETBEANS = 'asf.apache-netbeans-java';
const CONNECTION = 'nbls.dukehoff-mcp.connection';
const CONNECT_TIMEOUT_MS = 180_000;

export function activate(context: vscode.ExtensionContext): void {
    const log = vscode.window.createOutputChannel('Dukehoff MCP for Java');
    const changed = new vscode.EventEmitter<void>();
    context.subscriptions.push(log, changed, vscode.workspace.onDidChangeWorkspaceFolders(() => changed.fire()));
    const jar = path.join(context.extensionPath, 'bin', 'dukehoff-mcp.jar');
    const version: string = context.extension.packageJSON.version;

    context.subscriptions.push(vscode.lm.registerMcpServerDefinitionProvider('dukehoff-mcp', {
        onDidChangeMcpServerDefinitions: changed.event,

        provideMcpServerDefinitions: () => {
            const folders = vscode.workspace.workspaceFolders ?? [];
            return folders.map(folder => new vscode.McpStdioServerDefinition(
                folders.length > 1 ? `Java (${folder.name})` : 'Java',
                javaCommand(),
                // the socket (index 3) is filled in when the server starts
                ['-jar', jar, '--connect', '', folder.uri.fsPath],
                {},
                version));
        },

        resolveMcpServerDefinition: async (server: vscode.McpServerDefinition) => {
            if (!(server instanceof vscode.McpStdioServerDefinition)) {
                return server;
            }
            if (vscode.workspace.getConfiguration('netbeans').get<boolean>('javaSupport.enabled') === false) {
                throw new Error('Dukehoff MCP for Java needs NetBeans\' Java support: enable the setting netbeans.javaSupport.enabled');
            }
            const socket = await connection(log);
            server.args[3] = socket;
            log.appendLine(`starting ${server.label}: ${server.command} ${server.args.join(' ')}`);
            return server;
        }
    }));
}

export function deactivate(): void {
    // the server lives inside NetBeans and ends with it
}

/** The socket of the server inside NetBeans: starts the Apache extension and waits for its LSP server. */
async function connection(log: vscode.OutputChannel): Promise<string> {
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
                log.appendLine(`server inside NetBeans: ${answer.socket} (version ${answer.version})`);
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
