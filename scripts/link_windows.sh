#!/bin/bash
# Create self-contained copy of java in dist/windows

set -e

# Set env variables to build with linux toolchain but windows target
REAL_JAVA_HOME=$JAVA_HOME
JAVA_HOME="./jdks/windows/jdk-15.0.1"

# Build in dist/windows
rm -rf dist/windows
$REAL_JAVA_HOME/bin/jlink \
  --module-path "$JAVA_HOME/jmods:target/classes:target/dependency" \
  --add-modules ALL-MODULE-PATH \
  --launcher launcher=dev.secondsun.tm4e4lsp/dev.secondsun.tm4e4lsp.Main \
  --output dist/windows \
  --vm=server \
  --compress 2 \
  --strip-debug



