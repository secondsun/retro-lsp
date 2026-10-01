#!/bin/bash
# Links everything into a self-contained executable using jlink on macOS.

set -e

rm -rf dist/mac
$JAVA_HOME/bin/jlink \
  --module-path "$JAVA_HOME/jmods:target/classes:target/dependency" \
  --add-modules ALL-MODULE-PATH \
  --launcher launcher=dev.secondsun.retrolsp/dev.secondsun.retrolsp.Main \
  --output dist/mac \
  --vm=server