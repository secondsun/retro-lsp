#!/bin/bash
# Patch Gson with module-info.java
# When Gson releases a new version, we will no longer need to do this 
# https://github.com/google/gson/issues/1315

#set -e

# Needed if you have a java version other than 11 as default
JAVA_HOME=/c/Program\ Files/OpenJDK/jdk-12.0.1/

# Download Gson jar
cd modules
curl https://repo1.maven.org/maven2/com/google/code/gson/gson/2.8.5/gson-2.8.5.jar > gson.jar

# Unpack jar into modules/classes
echo "making dirs"
mkdir classes
cd classes
jar xf ../gson.jar
cd ..

# Compile module-info.java to module-info.class
echo "java"
javac -p com.google.gson -d classes module-info.java
echo "jar"
# Update gson.jar with module-info.class
jar uf gson.jar -C classes module-info.class
echo "cleanup"
# Clean up
rm -rf classes
cd ..
