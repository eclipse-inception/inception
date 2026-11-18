#!/bin/sh
#
# Licensed to the Technische Universität Darmstadt under one
#  or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The Technische Universität Darmstadt
# licenses this file to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Builds a per-arch macOS DMG via jpackage. If no JDK is configured for the
# target architecture, native-arch builds fall back to JAVA_HOME and
# cross-arch builds skip with a warning rather than failing the reactor.
#
# Args (positional):
#   1: target arch (aarch64 | x86_64)
#   2: JDK home for target arch (may be empty -> resolve from JAVA_HOME or skip)
#   3: signing identity
#   4: input dir (jpackage-input)
#   5: destination dir
#   6: main jar
#   7: main class
#   8: app version
#   9: icon path
#  10: entitlements path

set -eu

TARGET_ARCH="$1"
JDK_HOME="$2"
SIGNING_IDENTITY="$3"
INPUT_DIR="$4"
DEST_DIR="$5"
MAIN_JAR="$6"
MAIN_CLASS="$7"
APP_VERSION="$8"
ICON="$9"
ENTITLEMENTS="${10}"

# Maven leaves unresolved property references as the literal "${name}" string;
# treat those as unset.
case "$JDK_HOME" in
  '${'*'}') JDK_HOME='' ;;
esac
case "$SIGNING_IDENTITY" in
  '${'*'}') SIGNING_IDENTITY='' ;;
esac

if [ -z "$SIGNING_IDENTITY" ]; then
  echo "ERROR: No valid 'mac.signing.identity' configured. Pass it via -Dmac.signing.identity=\"Developer ID Application: …\" or set it in ~/.m2/settings.xml."
  exit 1
fi

# Normalize host arch reported by uname -m to the values jpackage uses.
HOST_ARCH=$(uname -m)
case "$HOST_ARCH" in
  arm64|aarch64) HOST_ARCH=aarch64 ;;
  x86_64|amd64)  HOST_ARCH=x86_64 ;;
esac

if [ -z "$JDK_HOME" ]; then
  if [ "$TARGET_ARCH" = "$HOST_ARCH" ]; then
    if [ -n "${JAVA_HOME:-}" ]; then
      echo "No 'jdk.${TARGET_ARCH}.home' configured; falling back to JAVA_HOME=$JAVA_HOME (native arch)."
      JDK_HOME="$JAVA_HOME"
    else
      echo "ERROR: No 'jdk.${TARGET_ARCH}.home' configured and JAVA_HOME is unset."
      exit 1
    fi
  else
    echo "WARNING: No 'jdk.${TARGET_ARCH}.home' configured. Skipping ${TARGET_ARCH} DMG build (cross-arch)."
    exit 0
  fi
fi

JPACKAGE="$JDK_HOME/bin/jpackage"
if [ ! -x "$JPACKAGE" ]; then
  echo "ERROR: jpackage not found or not executable at $JPACKAGE"
  exit 1
fi

echo "Building ${TARGET_ARCH} DMG via $JPACKAGE..."

"$JPACKAGE" \
  --type dmg \
  --name INCEpTION \
  --app-version "$APP_VERSION" \
  --input "$INPUT_DIR" \
  --dest "$DEST_DIR" \
  --main-jar "$MAIN_JAR" \
  --main-class "$MAIN_CLASS" \
  --java-options -Xmx4g \
  --java-options -Dinception.app-bundle=true \
  --java-options "--add-modules jdk.incubator.vector" \
  --java-options "--enable-native-access=ALL-UNNAMED" \
  --java-options -XX:-UseBASE64Intrinsics \
  --icon "$ICON" \
  --mac-sign \
  --mac-signing-key-user-name "$SIGNING_IDENTITY" \
  --mac-entitlements "$ENTITLEMENTS"

# Tag the produced DMG with the target arch so multiple builds can coexist,
# then codesign the DMG itself. jpackage signs the .app inside the DMG but
# leaves the DMG container unsigned, which Gatekeeper/notarization requires.
for f in "$DEST_DIR"/*.dmg; do
  [ -e "$f" ] || continue
  base=$(basename "$f" .dmg)
  case "$base" in
    *-"$TARGET_ARCH") tagged="$f" ;;
    *) tagged="$DEST_DIR/$base-$TARGET_ARCH.dmg"; mv "$f" "$tagged" ;;
  esac
  echo "Signing DMG: $tagged"
  codesign --sign "$SIGNING_IDENTITY" --timestamp "$tagged"
  echo "Wrote $tagged"
done
