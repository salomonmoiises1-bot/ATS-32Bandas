#!/usr/bin/env sh
# Lightweight Gradle launcher for this repository. Downloads the configured Gradle
# distribution when it is not already installed; no gradle-wrapper.jar is required.
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPS="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"
URL=$(sed -n 's/^distributionUrl=//p' "$PROPS" | sed 's/\\:/\:/g' | sed 's/\\=/=/g')
if [ -z "$URL" ]; then echo "Missing distributionUrl in $PROPS" >&2; exit 1; fi
VERSION=$(printf '%s' "$URL" | sed -n 's#.*gradle-\([0-9][0-9.]*\)-bin\.zip.*#\1#p')
if [ -z "$VERSION" ]; then echo "Cannot determine Gradle version from $URL" >&2; exit 1; fi
GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$HOME/.gradle"}
DIST="$GRADLE_USER_HOME/manual-wrapper/gradle-$VERSION"
BIN="$DIST/bin/gradle"
if [ ! -x "$BIN" ]; then
  mkdir -p "$GRADLE_USER_HOME/manual-wrapper" "$GRADLE_USER_HOME/manual-wrapper/downloads"
  ZIP="$GRADLE_USER_HOME/manual-wrapper/downloads/gradle-$VERSION-bin.zip"
  if [ ! -f "$ZIP" ]; then
    echo "Downloading Gradle $VERSION from $URL"
    if command -v curl >/dev/null 2>&1; then curl -fL --retry 2 "$URL" -o "$ZIP"
    elif command -v wget >/dev/null 2>&1; then wget -O "$ZIP" "$URL"
    else echo "Install curl or wget to bootstrap Gradle." >&2; exit 1; fi
  fi
  command -v unzip >/dev/null 2>&1 || { echo "Install unzip to bootstrap Gradle." >&2; exit 1; }
  TMP="$GRADLE_USER_HOME/manual-wrapper/.extract-$$"
  mkdir -p "$TMP"
  unzip -q "$ZIP" -d "$TMP"
  rm -rf "$DIST"
  mv "$TMP/gradle-$VERSION" "$DIST"
  rmdir "$TMP" 2>/dev/null || true
fi
exec "$BIN" -p "$APP_HOME" "$@"
