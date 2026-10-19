/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
// serverConfig and claudeExecutable of the compiled extension, in plain Node (vscode stubbed, as in
// agentHints.test.js).
const Module = require('module');
const assert = require('assert');
const path = require('path');

const load = Module._load;
Module._load = (request, ...rest) => request === 'vscode' ? {} : load(request, ...rest);
const claudeCode = require(path.join(__dirname, '..', 'out', 'claudeCode.js'));

assert.deepStrictEqual(
    claudeCode.serverConfig('/jdk/bin/java', '/storage/dukehoff-mcp.jar', ['--connect-file', '/ws/connection', '/project']),
    { type: 'stdio', command: '/jdk/bin/java', args: ['-jar', '/storage/dukehoff-mcp.jar', '--connect-file', '/ws/connection', '/project'] },
    'the proxy with its arguments, as a stdio server');

const ext = '/ext/anthropic.claude-code-2.1.301';
const bundled = path.join(ext, 'resources', 'native-binary', 'claude');
assert.strictEqual(claudeCode.claudeExecutable(ext, 'darwin', f => f === bundled), bundled,
    'the program bundled with Claude Code\'s extension');
assert.strictEqual(claudeCode.claudeExecutable(ext, 'win32', f => f === bundled + '.exe'), bundled + '.exe',
    'claude.exe on Windows');
assert.strictEqual(claudeCode.claudeExecutable(ext, 'darwin', () => false), 'claude',
    'claude on the PATH when the extension has none');
assert.strictEqual(claudeCode.claudeExecutable(undefined, 'linux', () => true), 'claude',
    'claude on the PATH without the extension');
const entry = { type: 'stdio', command: 'java', args: [] };
const config = { projects: { '/project': { mcpServers: { java: entry, other: {} } }, '/elsewhere': { mcpServers: {} } } };
assert.deepStrictEqual(claudeCode.localEntry(config, '/project'), entry, 'the folder\'s "java" server');
assert.strictEqual(claudeCode.localEntry(config, '/elsewhere'), undefined, 'none in another folder');
assert.strictEqual(claudeCode.localEntry({}, '/project'), undefined, 'no projects at all');
assert.strictEqual(claudeCode.localEntry(null, '/project'), undefined, 'no configuration');
console.log('claudeCode: all checks passed');
