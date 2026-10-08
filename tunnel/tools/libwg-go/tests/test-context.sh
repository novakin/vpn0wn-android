#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to the host JDK for JNI context tests}"
output="${BUILDDIR:-$PWD/build}/jni-test"
mkdir -p "$output"
case "$(uname -s)" in
  Linux) include=linux; shared=-shared;;
  Darwin) include=darwin; shared=-dynamiclib;;
  *) echo "Unsupported JNI test host" >&2; exit 1;;
esac
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror -Wno-unused-parameter -fPIC "$shared" -pthread \
  -I. -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/$include" \
  jni_context.c tests/context_test.c -o "$output/context-test.so"
"$JAVA_HOME/bin/javac" -d "$output" tests/WgContextTest.java
"$JAVA_HOME/bin/java" -Xcheck:jni -cp "$output" WgContextTest "$(cd "$output" && pwd)/context-test.so"
