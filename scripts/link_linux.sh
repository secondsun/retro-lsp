#!/bin/bash
# Links everything into a self-contained executable using jlink.

set -e

# Needed if you have a java version other than 11 as default
#JAVA_HOME=/usr/lib/jvm/java

# Compile sources
#mvn compile

# Build using jlink
rm -rf dist/linux
$JAVA_HOME/bin/jlink \
  --module-path target/classes:target/dependency \
  --add-modules ALL-MODULE-PATH \
  --launcher launcher=dev.secondsun.tm4e4lsp/dev.secondsun.tm4e4lsp.Main \
  --output dist/linux \
  --vm=server \
  --compress 2 
#  --strip-debug \

# strip -p --strip-unneeded dist/linux/lib/server/libjvm.so 

sed -i 's/JLINK_VM_OPTIONS=.*/JLINK_VM_OPTIONS=--enable-preview/' dist/linux/bin/launcher

