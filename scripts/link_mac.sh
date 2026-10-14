#!/bin/bash
# Links everything into a self-contained executable using jlink on macOS.

set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

rm -rf dist/mac
$JAVA_HOME/bin/jlink \
  --module-path "$JAVA_HOME/jmods" \
  --add-modules java.se,jdk.zipfs,jdk.httpserver,jdk.unsupported \
  --output dist/mac \
  --vm=server \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress zip-6

mkdir -p dist/mac/app
cp target/retro-lsp-*.jar dist/mac/app/
rm -f dist/mac/app/*-sources.jar dist/mac/app/*-javadoc.jar dist/mac/app/*-tests.jar
cp target/dependency/*.jar dist/mac/app/

cat << 'EOF' > dist/mac/bin/launcher
#!/bin/sh
DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec "$DIR/bin/java" -p "$DIR/app" --add-modules ALL-MODULE-PATH -m dev.secondsun.retrolsp/dev.secondsun.retrolsp.Main "$@"
EOF
chmod +x dist/mac/bin/launcher