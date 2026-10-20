<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
# Contributing to Dukehoff MCP for Java

Thanks for your interest. Bug reports with a reproduction (a small project, the tool call, what you
expected) help most; pull requests are welcome too.

## Contributor license agreement

Before we can merge your first pull request, you sign a contributor license agreement (CLA) with
Dukehoff GmbH, based on the standard [Harmony agreements](https://www.harmonyagreements.org/):

- **You keep the copyright** of your contribution and may use it as you like. You grant Dukehoff GmbH
  a non-exclusive license to distribute it, also under other licenses than the Apache License 2.0;
  in return, your contribution always stays available under the Apache License 2.0.
- **One signature covers all projects** of Dukehoff GmbH.
- **Signing:** on your first pull request a bot comments with the link to the agreement. Read it, then
  post the sentence the bot names as a comment; `recheck` checks again.
- **Work done for an employer:** your employer signs the entity version first (an authorized
  signatory, by email to the address in the agreement); we then add you.
- **Your data:** we keep your GitHub login and id, the pull request and the time of signing, in a
  private repository of Dukehoff GmbH, as the record of the agreement, for as long as we distribute
  your contribution.

The agreement is being finalized. Until it is published here, we cannot merge outside pull requests;
they stay open, and we credit you if we make the change ourselves in the meantime.

## Building and testing

You need a JDK 17 or newer and Maven.

```sh
mvn clean package                                       # the NetBeans module, the runtime, the VS Code extension, unit tests
tests/smoke/smoke.sh runtime/target/dukehoffmcp/bin/dukehoff-mcp    # every tool end to end; exit 0 = pass
python tests/smoke/smoke.py runtime/target/dukehoffmcp/bin/dukehoff-mcp.jar   # the same on any OS (Windows)
```

The runtime is a headless Apache NetBeans built from the NetBeans clusters on Maven Central; the
first build downloads them. Further end-to-end tests are in `tests/smoke/` (Lombok, project types,
two clients on one workspace, tool groups). `runtime/target/dukehoffmcp/bin/dukehoff-mcp --stop <workspace>`
ends a persistent server a test left running.

The VS Code extension (`vscode/`, out: `vscode/target/dukehoff-mcp-java-<version>.vsix`) is built with
npm; the build downloads Node.js itself. It runs our module inside the NetBeans of the Apache NetBeans
extension; `tests/smoke/embedded.py` checks that without VS Code, against the unpacked Apache NetBeans
VSIX (the version CI uses is `NETBEANS_VSIX_VERSION` in `.github/workflows/ci.yml`):

```sh
python3 tests/smoke/embedded.py <unpacked VSIX>/extension runtime/target/dukehoffmcp/extra launcher/target/dukehoff-mcp.jar
```

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

## Releasing (maintainers)

A tag `v<version>` on `main` (e.g. `git tag -s v0.1.0 && git push origin v0.1.0`) starts the Release
workflow: it builds that version (the poms stay at their `-SNAPSHOT` version in the repository),
runs the smoke test and publishes a GitHub release with `dukehoff-mcp-<version>.tar.gz`, `.zip` and
`SHA256SUMS`. Versions with a suffix (`0.2.0-rc1`) become pre-releases. Actions > Release > Run
workflow is a dry run of the same steps, without a release.

A tag also puts the NetBeans plugin on Maven Central (`com.dukehoff.mcp:dukehoff-mcp-netbeans`, with
the parent pom), where the NetBeans Plugin Portal takes it from. That needs the repository secrets
`CENTRAL_USERNAME` and `CENTRAL_PASSWORD` (a user token of the Central Portal account that owns the
namespace `com.dukehoff`) and `MAVEN_GPG_PRIVATE_KEY` and `MAVEN_GPG_PASSPHRASE` (the release signing
key); without them the step is skipped. The upload waits in the Central Portal for **Publish** unless
the repository variable `CENTRAL_AUTO_PUBLISH` is `true`. To check the bundle locally without
uploading: `mvn -P central -pl server -am deploy -DskipTests -Dcentral.skipPublishing=true` (the bundle
is in `target/central-publishing`; it is built only for a release version, not a `-SNAPSHOT`).

## Reporting security issues

Please do not open a public issue for a vulnerability; see [SECURITY.md](SECURITY.md).
