#!/bin/bash
# Links everything into a self-contained executable using jlink.

set -e

# Needed if you have a java version other than 11 as default
JAVA_HOME=/etc/alternatives/java_sdk

# Compile sources
mvn compile

# Patch gson
if [ ! -e modules/gson.jar ]; then
  ./scripts/patch_gson.sh
fi

# Build using jlink
rm -rf dist/mac
$JAVA_HOME/bin/jlink \
  --module-path target/classes \
  --add-modules gson,javacs,wla_server \
  --launcher launcher=wla_server/net.saga.snes.dev.wlalanguageserver.Main \
  --output dist/mac \
  --compress 2 