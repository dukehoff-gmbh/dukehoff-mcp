#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# Checks the platform debug tasks themselves: the bugged copy fails the check (only BillingRunTest
# fails, only for c-1187, with the totals from the prompt), "corrections always win" fails, the
# reference fix passes.
# Usage: validate-platform.sh <scratch directory>
set -u
here=$(cd "$(dirname "$0")" && pwd)
dir=$1/validate-platform-$$
"$here/setup-platform-bug.sh" "$dir" > /dev/null || exit 1
cd "$dir"
merger=src/main/java/com/kestrel/billing/usage/CorrectionMerger.java
out=$(bash "$here/tasks/08-platform/check.sh") && { echo "FAIL: the bugged copy passes"; exit 1; }
echo "bugged: $out"
mvn -q -o test > "$1/validate-platform-test.log" 2>&1
grep -E 'Tests run:.*Fail|c-1[0-9]{3}: expected' "$1/validate-platform-test.log" | grep -v 'Failures: 0, Errors: 0' | head -5
# "corrections always win": right for the data, wrong for two corrections
sed -i '' 's#(a, b) -> WRITTEN.compare(b, a) > 0 ? b : a#(a, b) -> b.correction() \&\& !a.correction() ? b : a.correction() \&\& !b.correction() ? a : WRITTEN.compare(b, a) > 0 ? b : a#' "$merger"
out=$(bash "$here/tasks/08-platform/check.sh") && { echo "FAIL: corrections-always-win passes"; exit 1; }
echo "corrections always win: $out"
git checkout -q -- .
# the reference fix
sed -i '' 's#Comparator.comparing(MeterReading::recordedAt);#Comparator.comparing(MeterReading::recordedInstant);#' "$merger"
out=$(bash "$here/tasks/09-platform-hint/check.sh") || { echo "FAIL: the reference fix fails: $out"; exit 1; }
echo "reference fix: $out"
rm -rf "$dir"
echo "valid"
