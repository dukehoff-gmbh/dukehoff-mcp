<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
# Contributing to Dukehoff MCP for Java

Thanks for your interest. Bug reports with a reproduction (a small project, the tool call, what you
expected) help most; pull requests are welcome too.

## Contributor license agreement

Before we can merge your first pull request, you (and, for work done for an employer, your employer)
sign a contributor license agreement modeled on the Apache Software Foundation's ICLA/CCLA. You keep
the copyright of your contribution; the agreement grants Dukehoff GmbH the rights to distribute it
under the Apache License 2.0 and in other products. You will be asked to sign it on your first pull
request.

## Building and testing

You need a JDK 17 or newer and Maven.

```sh
mvn clean package                                       # the NetBeans module, the runtime, unit tests
tests/smoke/smoke.sh runtime/target/dukehoffmcp/bin/dukehoff-mcp    # every tool end to end; exit 0 = pass
python tests/smoke/smoke.py runtime/target/dukehoffmcp/bin/dukehoff-mcp.jar   # the same on any OS (Windows)
```

The runtime is a headless Apache NetBeans built from the NetBeans clusters on Maven Central; the
first build downloads them. Further end-to-end tests are in `tests/smoke/` (Lombok, project types,
two clients on one workspace, tool groups). `runtime/target/dukehoffmcp/bin/dukehoff-mcp --stop <workspace>`
ends a persistent server a test left running.

The evaluation in `eval/` runs real coding-agent sessions (Claude Code) on your own login, so every
run costs tokens; it is not needed for most changes.

## Rules for code

- Java 17, no compiler warnings (`-Xlint:all` minus serial and processing); match the existing style.
- Only public NetBeans APIs (no friend or implementation dependencies), from Maven Central at the
  release set in `pom.xml`.
- Every file carries the license header (`Copyright 2026 Dukehoff GmbH`,
  `SPDX-License-Identifier: Apache-2.0`). The build checks it (`license-maven-plugin`, in `validate`);
  `mvn -N license:format` adds it to new files in the right comment style.
- Tools follow these design rules: any agent and model (tools only, stdio, plain text), few tools, symbol-addressed inputs, compact plain-text
  output, errors as results with a hint. A new tool needs evidence that agents gain from it
  (tokens, time or correctness); the evaluation in `eval/` is where that is measured.
- Small, focused commits with a message that says why.

## Reporting security issues

Please do not open a public issue for a vulnerability; see [SECURITY.md](SECURITY.md).
