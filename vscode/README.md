<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
# Dukehoff MCP for Java

Gives GitHub Copilot (and every agent in VS Code that uses MCP) semantic access to your Java code:
the compiler's index and the refactoring engine of Apache NetBeans, through the
[Apache NetBeans Language Server](https://marketplace.visualstudio.com/items?itemName=ASF.apache-netbeans-java)
extension, which is installed with it.

Your agent can then:

- **find usages and implementations** exactly (overloads, overrides and same-named methods told apart),
- **refactor in one step**: rename, change a method's signature, move a class, safe delete, inline,
  with every implementation and caller updated and a compile check afterwards,
- **list compile errors** without running a build,
- search and replace by Java structure, and stop in a test under the debugger.

## Use

Install the extension and open a Maven, Gradle or Ant project. The tools appear in Copilot's agent
mode under the server **Java**; VS Code asks once before starting it. Indexing a new project takes a
moment; the tools wait for it.

**Claude Code in VS Code** has its own panel and its own MCP configuration, so it does not see the
servers of VS Code's chat. The command **Dukehoff MCP: Use the Java tools in Claude Code** registers
the same server with Claude Code for the folder, in its local scope (only for you, not in the
repository); it shows the entry first and writes it only when you confirm. The extension suggests
it once when Claude Code's extension is installed. Start a new Claude Code session afterwards; the
tools work while the VS Code window is open.

Requirements: VS Code 1.101 or newer (its MCP support; older versions refuse to install the
extension), a JDK 17 or newer (the one the NetBeans extension uses, setting `netbeans.jdkhome`, or
`JAVA_HOME`), and NetBeans' Java support enabled (`netbeans.javaSupport.enabled`, the default).

## Review refactorings

A refactoring writes its files itself, apart from the agent's own edits. After each one a
notification names it, with **Review** (a diff of all files it changed) and **Undo** (puts the files
back, unless one was changed since). The setting `dukehoffMcp.refactoringReview` chooses:

- `notify` (default): the notification,
- `openDiff`: open the diff right away,
- `off`: nothing; the changes are in Source Control as usual.

## Changes outside the workspace

Refactorings change only files inside the workspace folder. If one would also change a file outside
(say, a shared source folder next to it), it changes nothing and names the files; the agent asks you
and passes the ones you allow in its next call. Folders that may always be changed go into the
setting `dukehoffMcp.allowedWritePaths` (relative paths are relative to the workspace folder).

## Help agents pick the tools

Agents choose tools by what they read. A short section in the project's `AGENTS.md` (read by Copilot,
Codex, Cursor and others) tells them to rename, move or change signatures with the tools, to look
up callers with `find_usages` and to do migrations with `apply_rule` (for JUnit 4 to 5 and Java
modernization with its tested rule packs). In our evaluation such a
section made agents pick `apply_rule` for a modernization on their own (3 of 3 sessions, none
without it), with a third fewer turns. The command **Dukehoff MCP: Add Java tool hints to AGENTS.md** shows
the section as a diff and writes it only when you confirm; running it again updates the section
instead of adding another. The extension suggests it once per workspace.

## Troubleshooting

- **No Java tools in Copilot:** the tools come from the server **Java** (Copilot's tool list in agent
  mode; "MCP: List Servers" shows its state and output). The workspace must contain a Maven, Gradle
  or Ant project, and NetBeans' Java support must be enabled (`netbeans.javaSupport.enabled`).
- **No Java tools in Claude Code:** run **Dukehoff MCP: Use the Java tools in Claude Code** and start a
  new session; `claude mcp list` in the folder shows the server `java`. Claude Code started in a
  terminal outside VS Code reaches the tools only while the folder is open in a VS Code window; for
  Claude Code on its own, use the standalone server.
- **The output channel "Dukehoff MCP for Java"** (View, Output) says what happened: the module version
  inside NetBeans, the server's socket, or why it did not start.
- **JDK:** NetBeans needs a JDK 17 or newer: the setting `netbeans.jdkhome`, else `JAVA_HOME`.
- **After an update**, run "Developer: Reload Window" (or restart VS Code), so that NetBeans starts with
  the new module; the output channel's module version should match the extension's.
- **The first start of a large project** indexes it; the tools wait up to two and a half minutes, then
  answer "not ready, call again" while indexing goes on.
- **Results look incomplete:** build the project once (so its dependencies are downloaded) and check
  the compile errors (`diagnostics`): files that do not compile are named in the results.

Outside VS Code (Copilot CLI, Claude Code in a terminal, Codex, Cursor) use the standalone server from the
[releases](https://github.com/dukehoff-gmbh/dukehoff-mcp/releases).

## License

Apache License 2.0. Dukehoff MCP for Java is not part of, nor endorsed by, the Apache NetBeans project.
Source: https://github.com/dukehoff-gmbh/dukehoff-mcp
