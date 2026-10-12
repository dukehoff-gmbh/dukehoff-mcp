#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# Makes the workspace for tasks 10/11 (NetBeans issue #9399, open and unfixed upstream as of
# 2026-10-07): an APFS clone of a built NetBeans checkout on a new branch whose only commit adds the
# reproducing tests (the test part of fix.patch). The fix itself (errors/Utilities.java) stays out.
# Run the sessions with --repo <dir> --workspace java/java.hints and a full NetBeans 31 with the NBM
# (the product runtime has no apisupport, so NetBeans modules are no projects there).
#
# Usage: setup.sh <built NetBeans checkout> <new directory>
set -eu
here=$(cd "$(dirname "$0")" && pwd)
src=$1
dst=$2
[ ! -e "$dst" ] || { echo "$dst exists" >&2; exit 2; }
[ -d "$src/nbbuild/netbeans" ] || { echo "$src is not built (no nbbuild/netbeans)" >&2; exit 2; }
cp -Rc "$src" "$dst"
cd "$dst"
[ -z "$(git status --porcelain -- java/java.hints)" ] || { echo "java/java.hints has changes in $src" >&2; exit 2; }
git checkout -q -b task-9399
git apply --include='*UnusedAssignmentOrBranchTest.java' "$here/fix.patch"
git add java/java.hints
git -c user.name=eval -c user.email=eval@example.invalid -c commit.gpgsign=false commit -qm "Tests for #9399"
echo "workspace: $dst/java/java.hints"
