#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the project directory; exit 0 = correct
here=$(cd "$(dirname "$0")/.." && pwd)
python3 "$here/check-modernize.py" "$here/modernize-expected.json"
