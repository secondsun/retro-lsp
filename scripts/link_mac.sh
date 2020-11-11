#!/bin/bash
# Links everything into a self-contained executable using jlink.

set -e

# Set env variables to build with linux toolchain but windows target
REAL_JAVA_HOME=$JAVA_HOME
JAVA_HOME="./jdks/mac/jdk-15.0.1.jdk"

# Build in dist/windows
rm -rf dist/mac
$REAL_JAVA_HOME/bin/jlink \
  --module-path "$JAVA_HOME/jmods:target/classes:target/dependency" \
  --add-modules ALL-MODULE-PATH \
  --launcher launcher=dev.secondsun.tm4e4lsp/dev.secondsun.tm4e4lsp.Main \
  --output dist/mac \
  --vm=server \
  --compress 2 \
  --strip-debug

 strip -p --strip-unneeded dist/mac/lib/server/libjvm.so 