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

Requirements: a JDK 17 or newer (the one the NetBeans extension uses, setting `netbeans.jdkhome`, or
`JAVA_HOME`), and NetBeans' Java support enabled (`netbeans.javaSupport.enabled`, the default).

## Review refactorings

A refactoring writes its files itself, apart from the agent's own edits. After each one a
notification names it, with **Review** (a diff of all files it changed) and **Undo** (puts the files
back, unless one was changed since). The setting `dukehoffMcp.refactoringReview` chooses:

- `notify` (default): the notification,
- `openDiff`: open the diff right away,
- `off`: nothing; the changes are in Source Control as usual.

Outside VS Code (Copilot CLI, Claude Code, Codex, Cursor) use the standalone server from the
[releases](https://github.com/dukehoff-gmbh/dukehoff-mcp/releases).

## License

Apache License 2.0. Dukehoff MCP for Java is not part of, nor endorsed by, the Apache NetBeans project.
Source: https://github.com/dukehoff-gmbh/dukehoff-mcp
