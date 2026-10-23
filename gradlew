#!/usr/bin/env sh
set -eu
GRADLE_VERSION="8.9"
GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}/manual-dists/gradle-$GRADLE_VERSION"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  mkdir -p "$(dirname "$GRADLE_HOME")"
  ZIP="$(dirname "$GRADLE_HOME")/gradle-$GRADLE_VERSION-bin.zip"
  if [ ! -f "$ZIP" ]; then
    curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
  fi
  unzip -q -o "$ZIP" -d "$(dirname "$GRADLE_HOME")"
fi
exec "$GRADLE_HOME/bin/gradle" "$@"
