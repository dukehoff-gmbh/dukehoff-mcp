#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# the same check as 06-billing (only the prompt differs)
exec bash "$(dirname "$0")/../06-billing/check.sh"
