#!/bin/bash
# Create self-contained copy of java in dist/windows

set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

# Build in dist/windows
rm -rf dist/windows
$JAVA_HOME/bin/jlink \
  --module-path "$JAVA_HOME/jmods" \
  --add-modules java.se,jdk.zipfs,jdk.httpserver,jdk.unsupported \
  --output dist/windows \
  --vm=server \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress zip-6

mkdir -p dist/windows/app
cp target/retro-lsp-*.jar dist/windows/app/
rm -f dist/windows/app/*-sources.jar dist/windows/app/*-javadoc.jar dist/windows/app/*-tests.jar
cp target/dependency/*.jar dist/windows/app/

cat << 'EOF' > dist/windows/bin/launcher.bat
@echo off
set "DIR=%~dp0.."
"%DIR%\bin\java.exe" -p "%DIR%\app" --add-modules ALL-MODULE-PATH -m dev.secondsun.retrolsp/dev.secondsun.retrolsp.Main %*
EOF

cat << 'EOF' > dist/windows/bin/launcher
#!/bin/sh
DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec "$DIR/bin/java.exe" -p "$DIR/app" --add-modules ALL-MODULE-PATH -m dev.secondsun.retrolsp/dev.secondsun.retrolsp.Main "$@"
EOF
chmod +x dist/windows/bin/launcher
