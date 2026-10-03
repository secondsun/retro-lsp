#!/bin/bash
# Builds the Java backend (clean package), creates the native jlink image for current platform,
# and packages the VS Code extension bundled with the language server.

set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

echo "=== 1. Building Java Language Server ==="
./mvnw clean package -DskipTests

echo "=== 2. Creating jlink runtime ==="
OS="$(uname -s)"
case "$OS" in
    Linux*)     ./scripts/link_linux.sh ;;
    Darwin*)    ./scripts/link_mac.sh ;;
    CYGWIN*|MINGW*|MSYS*) ./scripts/link_windows.sh ;;
    *)          echo "Unknown OS $OS, skipping jlink" ;;
esac

echo "=== 3. Bundling and Packaging VS Code Extension ==="
./scripts/bundle_extension.sh "$1"

echo "=== Build complete! ==="
