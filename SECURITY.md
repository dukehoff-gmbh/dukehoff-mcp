<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
# Security

Please report vulnerabilities privately through GitHub's "Report a vulnerability" button on the
repository's Security tab, not in a public issue. We confirm receipt and keep you informed until a
fix is released.

What to keep in mind when you run dukehoff-mcp:

- It reads and, with its refactoring tools, changes the source files of the workspace it is started
  on, like the coding agent that calls it.
- The `debug` tool runs your project's tests (through Maven, Gradle or Ant), so it executes the
  project's code and build scripts. Gradle projects' build scripts are also run when the workspace
  is opened. Start dukehoff-mcp only on code you would build yourself.
- The persistent server listens on a Unix domain socket in its data directory, readable and writable
  by your user only; it accepts no network connections.
