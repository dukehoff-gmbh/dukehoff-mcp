/*
 * Copyright 2026 Dukehoff GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
// withSection of the compiled extension, in plain Node: the vscode module exists only inside VS Code,
// so it is replaced by an empty stub (withSection does not use it).
const Module = require('module');
const assert = require('assert');
const path = require('path');

const load = Module._load;
Module._load = (request, ...rest) => request === 'vscode' ? {} : load(request, ...rest);
const hints = require(path.join(__dirname, '..', 'out', 'agentHints.js'));

const S = hints.SECTION;
assert.strictEqual(hints.withSection(undefined), S, 'a new file has only the section');
assert.strictEqual(hints.withSection('  \n'), S, 'an empty file gets only the section');
const own = '# Project\n\nBuild with mvn.\n';
const added = hints.withSection(own);
assert.strictEqual(added, '# Project\n\nBuild with mvn.\n\n' + S, 'appended after a blank line');
assert.strictEqual(hints.withSection(added), added, 'running again changes nothing');
assert.strictEqual(hints.withSection(added.replace('find_usages', 'find_callers')), added,
    'an older section is replaced in place');
const middle = '# A\n\n' + S + '\n# B\n';
assert.strictEqual(hints.withSection(middle.replace('outline', 'x')), middle,
    'replaced where it stands, the text after it kept');
const crlf = hints.withSection('# Project\r\n\r\nBuild.\r\n');
assert.ok(!/[^\r]\n/.test(crlf), 'a CRLF file stays CRLF');
assert.strictEqual(hints.withSection(crlf), crlf, 'CRLF: running again changes nothing');
console.log('agentHints: all checks passed');
