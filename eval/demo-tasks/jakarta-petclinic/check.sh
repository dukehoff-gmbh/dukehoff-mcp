#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the workspace (Spring PetClinic at 9ecdc11, the last commit before Spring Boot 3); exit 0 = correct
# expectation: no javax.persistence, javax.validation or javax.xml.bind left under src/ (javax.cache keeps its
# namespace under Spring Boot 3), and only Java files changed
left=$(grep -rnE 'javax\.(persistence|validation|xml\.bind)' src)
[ -z "$left" ] || { echo "javax left:"; echo "$left"; exit 1; }
other=$(git status --porcelain | grep -v '\.java$')
[ -z "$other" ] || { echo "non-Java files changed: $other"; exit 1; }
[ -n "$(git status --porcelain)" ] || { echo "nothing changed"; exit 1; }
echo "correct"
