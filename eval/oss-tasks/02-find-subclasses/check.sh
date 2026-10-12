#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the workspace (Apache Commons Lang at rel/commons-lang-3.17.0); exit 0 = correct
# traps: indirect subclasses (via CodePointTranslator, UnicodeEscaper) and nested classes in
# another package (StringEscapeUtils.CsvEscaper, CsvUnescaper); expectation from the class files
expected='org.apache.commons.lang3.StringEscapeUtils.CsvEscaper
org.apache.commons.lang3.StringEscapeUtils.CsvUnescaper
org.apache.commons.lang3.text.translate.AggregateTranslator
org.apache.commons.lang3.text.translate.CodePointTranslator
org.apache.commons.lang3.text.translate.JavaUnicodeEscaper
org.apache.commons.lang3.text.translate.LookupTranslator
org.apache.commons.lang3.text.translate.NumericEntityEscaper
org.apache.commons.lang3.text.translate.NumericEntityUnescaper
org.apache.commons.lang3.text.translate.OctalUnescaper
org.apache.commons.lang3.text.translate.UnicodeEscaper
org.apache.commons.lang3.text.translate.UnicodeUnescaper
org.apache.commons.lang3.text.translate.UnicodeUnpairedSurrogateRemover'
[ -f answer.txt ] || { echo "no answer.txt"; exit 1; }
got=$(sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' answer.txt | grep -v '^$' | sort -u)
if [ "$got" != "$expected" ]; then
    echo "wrong classes: $(diff <(echo "$expected") <(echo "$got") | grep -c '^<') missing, $(diff <(echo "$expected") <(echo "$got") | grep -c '^>') wrong"
    exit 1
fi
changed=$(git status --porcelain -- . ':!answer.txt')
[ -z "$changed" ] || { echo "files changed: $changed"; exit 1; }
echo "correct"
