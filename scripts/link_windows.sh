#!/bin/bash
# Create self-contained copy of java in dist/windows

set -e

# Build in dist/windows
rm -rf dist/windows
$JAVA_HOME/bin/jlink \
  --module-path "$JAVA_HOME/jmods;target/classes;target/dependency" \
  --add-modules ALL-MODULE-PATH \
  --launcher launcher=dev.secondsun.tm4e4lsp/dev.secondsun.tm4e4lsp.Main \
  --output dist/windows \
  --vm=server #\
  #--compress 2 \
  #--strip-debug



