<!--

    Copyright 2026 Dukehoff GmbH
    SPDX-License-Identifier: Apache-2.0

-->
The current directory is Spring PetClinic on Spring Boot 2.7 (Maven, sources in src/main/java, tests in src/test/java). Migrate its Java sources from the `javax` namespace to `jakarta` for Spring Boot 3. Use the `apply_rule` tool of the java MCP server with `pack: "jakarta"`, and try it with a dry run first. Leave `pom.xml` and the other build files alone; the project is not expected to compile until they are migrated too. Finally, report which `javax` imports are left and why.
