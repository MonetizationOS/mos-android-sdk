#!/bin/sh
# Runs the Kotlin core tests. Uses the Homebrew JDK 21 when no JAVA_HOME is set.
set -e
cd "$(dirname "$0")/.."
if [ -z "$JAVA_HOME" ] && [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
exec ./gradlew :mos-core:test "$@"
