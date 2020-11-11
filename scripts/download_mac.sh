#!/bin/bash
# Download a copy of mac JDK in jdks/mac

set -e

# Download mac jdk
mkdir -p jdks/mac
cd jdks/mac
curl https://download.java.net/java/GA/jdk15.0.1/51f4f36ad4ef43e39d0dfdbaf6549e32/9/GPL/openjdk-15.0.1_osx-x64_bin.tar.gz > mac.tar.gz
gunzip -c mac.tar.gz | tar xopf -
rm mac.tar.gz
cd ../..