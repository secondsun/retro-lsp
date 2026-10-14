#!/bin/bash
# Links everything into a self-contained executable using jlink.

set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

# Build runtime image using jlink
rm -rf dist/linux
$JAVA_HOME/bin/jlink \
  --module-path "$JAVA_HOME/jmods" \
  --add-modules java.se,jdk.zipfs,jdk.httpserver,jdk.unsupported \
  --output dist/linux \
  --vm=server \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress zip-6

mkdir -p dist/linux/app
cp target/retro-lsp-*.jar dist/linux/app/
rm -f dist/linux/app/*-sources.jar dist/linux/app/*-javadoc.jar dist/linux/app/*-tests.jar
cp target/dependency/*.jar dist/linux/app/

cat << 'EOF' > dist/linux/bin/launcher
#!/bin/sh
DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec "$DIR/bin/java" -p "$DIR/app" --add-modules ALL-MODULE-PATH -m dev.secondsun.retrolsp/dev.secondsun.retrolsp.Main "$@"
EOF
chmod +x dist/linux/bin/launcher
