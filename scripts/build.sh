#!/bin/bash

set -e

# Needed if you have a java version other than 11 as default
JAVA_HOME=/c/Users/secon/.sdkman/candidates/java/current/bin/java

# Build fat jar
./scripts/link_mac.sh
./scripts/link_windows.sh

# Build vsix
vsce package -o build.vsix

code --install-extension build.vsix

echo 'Reload VSCode to update extension'
