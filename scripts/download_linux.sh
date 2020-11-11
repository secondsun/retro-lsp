#!/bin/bash
# Download a copy of linux JDK in jdks/linux

set -e

# Download linux jdk
mkdir -p jdks/linux
cd jdks/linux
curl https://download.java.net/java/GA/jdk15.0.1/51f4f36ad4ef43e39d0dfdbaf6549e32/9/GPL/openjdk-15.0.1_linux-x64_bin.tar.gz > linux.tar.gz
gunzip -c linux.tar.gz | tar xopf -
rm linux.tar.gz
cd ../..