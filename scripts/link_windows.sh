#!/bin/bash
# Create self-contained copy of java in dist/windows

set -e

# Set env variables to build with mac toolchain but windows target
REAL_JAVA_HOME=$JAVA_HOME
JAVA_HOME="./jdks/windows/jdk-13"

# Build in dist/windows
rm -rf dist/windows
$JAVA_HOME/bin/jlink \
  --module-path "$JAVA_HOME/jmods;modules/gson.jar;target/classes;target/dependency" \
  --add-modules ALL-MODULE-PATH \
  --launcher launcher=wla_server/net.saga.snes.dev.wlalanguageserver.Main \
  --output dist/windows \
  --compress 2 
sed -i 's/JLINK_VM_OPTIONS=.*/JLINK_VM_OPTIONS=--enable-preview/' dist/windows/bin/launcher
sed -i 's/set JLINK_VM_OPTIONS=.*/set JLINK_VM_OPTIONS=--enable-preview/' dist/windows/bin/launcher.bat

