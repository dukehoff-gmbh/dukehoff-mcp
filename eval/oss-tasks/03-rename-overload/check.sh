#!/usr/bin/env bash
#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

# run in the workspace (Apache Commons Lang at rel/commons-lang-3.17.0); exit 0 = correct
# traps: two other ClassUtils overloads of the same name, and ToStringStyle.getShortClassName(Class)
# (same signature, overridden in AnnotationUtils) which calls ClassUtils.getShortClassName itself.
# Compiling proves every call was updated (a remaining call with a Class argument has no overload
# left); javap proves which methods carry which name. Needs the dependencies in ~/.m2 (mvn -o).
mvn -q -o -Dmaven.compiler.showWarnings=false test-compile > /tmp/dukehoffmcp-oss-03-compile.log 2>&1 \
    || { echo "does not compile: $(grep -m1 ERROR /tmp/dukehoffmcp-oss-03-compile.log)"; exit 1; }
methods() { javap -p -cp target/classes "$1" | grep -E " $2\(" | sed 's/^ *//'; }
cu=org.apache.commons.lang3.ClassUtils
[ "$(methods $cu shortClassName)" = "public static java.lang.String shortClassName(java.lang.Class<?>);" ] \
    || { echo "ClassUtils.shortClassName is not exactly the (Class) method: $(methods $cu shortClassName)"; exit 1; }
[ "$(methods $cu getShortClassName | sort)" = "public static java.lang.String getShortClassName(java.lang.Object, java.lang.String);
public static java.lang.String getShortClassName(java.lang.String);" ] \
    || { echo "ClassUtils.getShortClassName overloads wrong: $(methods $cu getShortClassName)"; exit 1; }
for c in org.apache.commons.lang3.builder.ToStringStyle 'org.apache.commons.lang3.AnnotationUtils$1'; do
    [ -n "$(methods "$c" getShortClassName)" ] || { echo "$c.getShortClassName was renamed"; exit 1; }
done
changed=$(git status --porcelain -- . ':!*.java' ':!target')
[ -z "$changed" ] || { echo "non-Java files changed: $changed"; exit 1; }
echo "correct"
