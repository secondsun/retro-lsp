#!/bin/bash
# Bundles the JLink runtime into vscode/server and packages the VS Code extension (.vsix).

set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

# Allow target override via argument or detect host target
TARGET="$1"

if [ -z "$TARGET" ]; then
    OS="$(uname -s)"
    ARCH="$(uname -m)"

    case "$OS" in
        Linux*)
            case "$ARCH" in
                x86_64)  TARGET="linux-x64" ;;
                aarch64) TARGET="linux-arm64" ;;
                *)       TARGET="linux-x64" ;;
            esac
            ;;
        Darwin*)
            case "$ARCH" in
                arm64)   TARGET="darwin-arm64" ;;
                x86_64)  TARGET="darwin-x64" ;;
                *)       TARGET="darwin-arm64" ;;
            esac
            ;;
        CYGWIN*|MINGW*|MSYS*)
            case "$ARCH" in
                x86_64)  TARGET="win32-x64" ;;
                aarch64) TARGET="win32-arm64" ;;
                *)       TARGET="win32-x64" ;;
            esac
            ;;
        *)
            TARGET="linux-x64"
            ;;
    esac
fi

# Determine source dist directory
case "$TARGET" in
    linux*)   DIST_SUBDIR="linux" ;;
    darwin*)  DIST_SUBDIR="mac" ;;
    win32*)   DIST_SUBDIR="windows" ;;
    *)        DIST_SUBDIR="linux" ;;
esac

SOURCE_DIST="$DIR/dist/$DIST_SUBDIR"

if [ ! -d "$SOURCE_DIST" ]; then
    echo "Source runtime '$SOURCE_DIST' not found. Attempting to build..."
    case "$DIST_SUBDIR" in
        linux)   ./scripts/link_linux.sh ;;
        mac)     ./scripts/link_mac.sh ;;
        windows) ./scripts/link_windows.sh ;;
    esac
fi

if [ ! -d "$SOURCE_DIST" ]; then
    echo "Error: Source runtime '$SOURCE_DIST' could not be found or built."
    exit 1
fi

echo "=== Bundling $DIST_SUBDIR runtime for target '$TARGET' into vscode/server ==="
rm -rf "$DIR/vscode/server"
mkdir -p "$DIR/vscode/server"
cp -a "$SOURCE_DIST"/* "$DIR/vscode/server/"

# Ensure executable permissions on Unix binaries
if [ -d "$DIR/vscode/server/bin" ]; then
    chmod +x "$DIR/vscode/server/bin/"* 2>/dev/null || true
fi

echo "=== Packaging VS Code extension (.vsix) for target '$TARGET' ==="
cd "$DIR/vscode"
npm run compile

if [ "$TARGET" = "universal" ] || [ "$TARGET" = "none" ]; then
    npx @vscode/vsce package --no-dependencies
else
    npx @vscode/vsce package --no-dependencies --target "$TARGET"
fi

echo "=== Extension packaged successfully for $TARGET ==="
