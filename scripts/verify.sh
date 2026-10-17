#!/bin/bash
# Comprehensive verification script for retro-lsp.
# Validates both the Java backend, the native JLink runtime,
# the headless LSP stdio communication, and the VS Code extension.

set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

echo "=== [1/4] Running Java Tests & Formatting Check ==="
./mvnw clean spotless:check test

echo "=== [2/4] Building JLink Native Image ==="
OS="$(uname -s)"
case "$OS" in
    Linux*)     ./scripts/link_linux.sh ;;
    Darwin*)    ./scripts/link_mac.sh ;;
    CYGWIN*|MINGW*|MSYS*) ./scripts/link_windows.sh ;;
    *)          echo "Unknown OS $OS, skipping jlink" ;;
esac

echo "=== [3/4] Running Headless LSP Handshake Test ==="
node ./scripts/verify_lsp_handshake.mjs

echo "=== [4/4] Compiling and Linting VS Code Extension ==="
cd "$DIR/vscode"
npm run compile
npm run lint

echo "============================================="
echo "  ✓ ALL VERIFICATION CHECKS PASSED!"
echo "============================================="
