#!/bin/bash
# Links everything into a self-contained executable using jlink.

set -e

# Build using jlink
rm -rf dist/linux
$JAVA_HOME/bin/jlink \
  --module-path "$JAVA_HOME/jmods:target/classes:target/dependency" \
  --add-modules ALL-MODULE-PATH \
  --launcher launcher=dev.secondsun.retrolsp/dev.secondsun.retrolsp.Main \
  --output dist/linux \
  --vm=server \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress zip-6
