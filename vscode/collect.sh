#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# Copies the built parts into the extension (after mvn package): the launcher (the proxy) to bin/,
# our module as a NetBeans cluster to nbcode/dukehoffmcp/ (the Apache NetBeans extension loads every
# cluster in other extensions' nbcode/), and the license files.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo=$(dirname "$here")
cluster=$repo/runtime/target/dukehoffmcp/extra
rm -rf "$here/bin" "$here/nbcode"
mkdir -p "$here/bin" "$here/nbcode/dukehoffmcp/config/Modules" "$here/nbcode/dukehoffmcp/modules/ext" \
    "$here/nbcode/dukehoffmcp/update_tracking"
cp "$repo/launcher/target/dukehoff-mcp.jar" "$here/bin/"
cp "$cluster/config/Modules/com-dukehoff-mcp.xml" "$here/nbcode/dukehoffmcp/config/Modules/"
cp "$cluster/modules/com-dukehoff-mcp.jar" "$here/nbcode/dukehoffmcp/modules/"
cp -R "$cluster/modules/ext/dukehoff-mcp" "$here/nbcode/dukehoffmcp/modules/ext/"
cp "$cluster/update_tracking/com-dukehoff-mcp.xml" "$here/nbcode/dukehoffmcp/update_tracking/"
cp "$repo/LICENSE" "$repo/NOTICE" "$here/"
cp "$repo/runtime/src/main/licenses/licenses/MIT-lombok" "$here/nbcode/dukehoffmcp/modules/ext/dukehoff-mcp/LICENSE-lombok"
echo "collected: bin/dukehoff-mcp.jar, nbcode/dukehoffmcp ($(find "$here/nbcode" -type f | wc -l | tr -d ' ') files)"
