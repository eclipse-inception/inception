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

# Builds a per-arch macOS DMG via jpackage, in two steps so that the legal
# documentation can be generated from the finished application in between:
#
#   app-image: builds the app in the destination dir, unsigned, for the
#              legal documentation to be generated from
#   dmg:       builds and signs the app again from the same input and packs it
#              into the DMG, with the legal documentation from
#              <destination dir>/legal next to it in the DMG window and inside
#              the app (Contents/legal), so that it stays with the app once it
#              has been copied to the Applications folder
#
# Both steps choose the JDK the same way: if no JDK is configured for the
# target architecture, native-arch builds fall back to JAVA_HOME and
# cross-arch builds skip with a warning rather than failing the reactor.
#
# Args (positional):
#   1: step (app-image | dmg)
#   2: target arch (aarch64 | x86_64)
#   3: JDK home for target arch (may be empty -> resolve from JAVA_HOME or skip)
#   4: signing identity
#   5: input dir (jpackage-input)
#   6: destination dir
#   7: main jar
#   8: main class
#   9: app version
#  10: icon path
#  11: entitlements path

set -eu

STEP="$1"
TARGET_ARCH="$2"
JDK_HOME="$3"
SIGNING_IDENTITY="$4"
INPUT_DIR="$5"
DEST_DIR="$6"
MAIN_JAR="$7"
MAIN_CLASS="$8"
APP_VERSION="$9"
ICON="${10}"
ENTITLEMENTS="${11}"

# The name shown in the Applications folder, the Dock and the menu bar. The DMG
# itself gets a download name without spaces.
APP_NAME="Eclipse INCEpTION"
# Same wording as the About page; the end year is the year of the build.
COPYRIGHT="Copyright (c) 2012, $(date +%Y) Contributors to the Eclipse Foundation"

APP_IMAGE="$DEST_DIR/$APP_NAME.app"
DMG="$DEST_DIR/eclipse-inception-incubation-$APP_VERSION-$TARGET_ARCH.dmg"
LEGAL_DIR="$DEST_DIR/legal"

# Background and layout of the window that opens when the DMG is mounted. jpackage
# picks them up from its resource dir under names that start with the app name.
DMG_SOURCES="$(cd "$(dirname "$0")/../dmg" && pwd)"
RESOURCE_DIR="$DEST_DIR/dmg-resources"

# A new application image, or none if this architecture is skipped below: an
# image left from an earlier build must not be analysed as if it were current.
if [ "$STEP" = "app-image" ]; then
  rm -rf "$APP_IMAGE" "$LEGAL_DIR"
fi

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

# jpackage's default jlink options, plus the JDK's implementor and its version.
# jlink otherwise keeps only the Java version in the runtime's release file, and
# the legal documentation generated from the application image names the
# runtime by its implementor and version from there. Only these two lines are
# carried over: the JDK's whole release file would also claim IMAGE_TYPE="JDK".
# jpackage honours double quotes in --jlink-options, which keeps a path with
# spaces together.
mkdir -p "$DEST_DIR"
grep -E '^IMPLEMENTOR(_VERSION)?=' "$JDK_HOME/release" > "$DEST_DIR/jdk-release" || true
JLINK_OPTIONS="--strip-native-commands --strip-debug --no-man-pages --no-header-files"
JLINK_OPTIONS="$JLINK_OPTIONS --release-info \"$DEST_DIR/jdk-release\""

# Both steps build the application from the same JDK, input and options, so
# the image analysed in the app-image step is what the dmg step packs.
# The bundle identifier is set explicitly: jpackage would otherwise use the
# package of the main class, which a package rename would change. Releases up
# to 42.x carry de.tudarmstadt.ukp.inception.
jpackage_app() {
  "$JPACKAGE" "$@" \
    --name "$APP_NAME" \
    --copyright "$COPYRIGHT" \
    --mac-package-identifier org.eclipse.inception \
    --app-version "$APP_VERSION" \
    --input "$INPUT_DIR" \
    --main-jar "$MAIN_JAR" \
    --main-class "$MAIN_CLASS" \
    --java-options -Xmx4g \
    --java-options -Dinception.app-bundle=true \
    --java-options "--add-modules jdk.incubator.vector" \
    --java-options "--enable-native-access=ALL-UNNAMED" \
    --java-options -XX:-UseBASE64Intrinsics \
    --jlink-options "$JLINK_OPTIONS" \
    --icon "$ICON"
}

if [ "$STEP" = "app-image" ]; then
  echo "Building ${TARGET_ARCH} application image via $JPACKAGE..."

  jpackage_app --type app-image --dest "$DEST_DIR"
  exit 0
fi

if [ "$STEP" != "dmg" ]; then
  echo "ERROR: Unknown step '$STEP', expected 'app-image' or 'dmg'."
  exit 1
fi

if [ ! -d "$LEGAL_DIR" ]; then
  echo "ERROR: No legal documentation in $LEGAL_DIR - it is generated from $APP_IMAGE."
  exit 1
fi

echo "Building ${TARGET_ARCH} DMG via $JPACKAGE..."

# A DMG left from an earlier build would be signed a second time below
rm -f "$DEST_DIR"/*.dmg

rm -rf "$RESOURCE_DIR"
mkdir -p "$RESOURCE_DIR"
cp "$DMG_SOURCES/background.tiff" "$RESOURCE_DIR/$APP_NAME-background.tiff"
cp "$DMG_SOURCES/dmg-setup.scpt" "$RESOURCE_DIR/$APP_NAME-dmg-setup.scpt"

# Built again rather than from the application image of the first step: an
# application image that is signed before it is packed (jpackage --app-image
# with an unsigned image leaves it ad-hoc signed only) lacks app/.package, the
# launcher's per-user configuration support, which jpackage adds only to an app
# it builds itself.
jpackage_app \
  --type dmg \
  --dest "$DEST_DIR" \
  --resource-dir "$RESOURCE_DIR" \
  --mac-dmg-content "$LEGAL_DIR" \
  --app-content "$LEGAL_DIR" \
  --mac-sign \
  --mac-signing-key-user-name "$SIGNING_IDENTITY" \
  --mac-entitlements "$ENTITLEMENTS"

# jpackage names the DMG after the application and the version only; the
# download name adds the incubation status and the architecture. The DMGs were
# deleted above, so the one found here is the one just built. Then codesign the
# DMG itself: jpackage signs the .app inside the DMG but leaves the DMG
# container unsigned, which Gatekeeper/notarization requires.
for f in "$DEST_DIR"/*.dmg; do
  mv "$f" "$DMG"
done
echo "Signing DMG: $DMG"
codesign --sign "$SIGNING_IDENTITY" --timestamp "$DMG"
echo "Wrote $DMG"
