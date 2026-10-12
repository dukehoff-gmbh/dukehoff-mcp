#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# Checks the debug tasks themselves: the bugged copy fails the check (and only the business test
# fails), the reference fix passes, a fix in the configuration does not.
# Usage: validate.sh <scratch directory>
set -u
here=$(cd "$(dirname "$0")" && pwd)
dir=$1/validate-$$
"$here/setup-bug.sh" "$dir" > /dev/null || exit 1
cd "$dir"
out=$(bash "$here/tasks/06-billing/check.sh") && { echo "FAIL: the bugged copy passes"; exit 1; }
echo "bugged: $out"
mvn -q -o test > "$1/validate-test.log" 2>&1
grep -E 'Tests run:|FAIL' "$1/validate-test.log" | grep -v 'Failures: 0, Errors: 0' | head -5
# a configuration "fix" (zero-padded priorities)
sed -i '' 's/volume:9,loyalty:12/volume:09,loyalty:12/' src/main/resources/tariffs-business.properties
out=$(bash "$here/tasks/06-billing/check.sh") && { echo "FAIL: the configuration fix passes"; exit 1; }
echo "config fix: $out"
git checkout -q -- .
# the reference fix
sed -i '' 's#return priority.compareTo(other.priority);#return Integer.compare(Integer.parseInt(priority), Integer.parseInt(other.priority));#' \
    src/main/java/com/kestrel/billing/config/RuleSpec.java
out=$(bash "$here/tasks/07-billing-hint/check.sh") || { echo "FAIL: the reference fix fails: $out"; exit 1; }
echo "reference fix: $out"
rm -rf "$dir"
echo "valid"
