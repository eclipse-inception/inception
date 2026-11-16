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

# Builds a per-arch Windows MSI via jpackage, in two steps so that the legal
# documentation can be generated from the application in between:
#
#   app-image: builds the application image INCEpTION in the destination dir,
#              for the legal documentation to be generated from
#   msi:       adds the legal documentation from <destination dir>/legal to
#              that application image and packs it into the MSI, so that it
#              lands in the installation folder
#
# Both steps choose the JDK the same way: falls back to JAVA_HOME when the
# native-arch JDK home is not configured; skips with a warning when building
# cross-arch without a configured JDK.
#
# Args (positional):
#   1: step (app-image | msi)
#   2: target arch (x86_64 | aarch64)
#   3: JDK home for target arch (may be empty -> resolve from JAVA_HOME or skip)
#   4: input dir (jpackage-input)
#   5: destination dir
#   6: main jar
#   7: main class
#   8: app version
#   9: icon path

set -eu

STEP="$1"
TARGET_ARCH="$2"
JDK_HOME="$3"
INPUT_DIR="$4"
DEST_DIR="$5"
MAIN_JAR="$6"
MAIN_CLASS="$7"
APP_VERSION="$8"
ICON="$9"

APP_IMAGE="$DEST_DIR/INCEpTION"
LEGAL_DIR="$DEST_DIR/legal"

# A new application image, or none if this architecture is skipped below: an
# image left from an earlier build must not be analysed as if it were current.
if [ "$STEP" = "app-image" ]; then
  rm -rf "$APP_IMAGE" "$LEGAL_DIR"
fi

# Maven leaves unresolved property references as the literal "${name}" string;
# treat that as unset.
case "$JDK_HOME" in
  '${'*'}') JDK_HOME='' ;;
esac

# Normalize host arch reported by uname/wmic to a canonical value.
HOST_ARCH=$(uname -m 2>/dev/null || echo "")
case "$HOST_ARCH" in
  arm64|aarch64) HOST_ARCH=aarch64 ;;
  x86_64|amd64)  HOST_ARCH=x86_64 ;;
  *)
    # On native cmd.exe (no uname), fall back to env var.
    case "${PROCESSOR_ARCHITECTURE:-}" in
      AMD64) HOST_ARCH=x86_64 ;;
      ARM64) HOST_ARCH=aarch64 ;;
    esac
    ;;
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
    echo "WARNING: No 'jdk.${TARGET_ARCH}.home' configured. Skipping ${TARGET_ARCH} MSI build (cross-arch)."
    exit 0
  fi
fi

# jpackage on Windows is jpackage.exe; under Git Bash plain "jpackage" works too.
JPACKAGE="$JDK_HOME/bin/jpackage"
if [ ! -x "$JPACKAGE" ] && [ ! -x "$JPACKAGE.exe" ]; then
  echo "ERROR: jpackage not found at $JPACKAGE(.exe)"
  exit 1
fi

mkdir -p "$DEST_DIR"

# jpackage's default jlink options, plus the JDK's implementor and its version.
# jlink otherwise keeps only the Java version in the runtime's release file, and
# the legal documentation generated from the application image names the
# runtime by its implementor and version from there. Only these two lines are
# carried over: the JDK's whole release file would also claim IMAGE_TYPE="JDK".
# jpackage honours double quotes in --jlink-options, which keeps a path with
# spaces together.
grep -E '^IMPLEMENTOR(_VERSION)?=' "$JDK_HOME/release" > "$DEST_DIR/jdk-release" || true
JLINK_OPTIONS="--strip-native-commands --strip-debug --no-man-pages --no-header-files"
JLINK_OPTIONS="$JLINK_OPTIONS --release-info \"$DEST_DIR/jdk-release\""

jpackage_app() {
  "$JPACKAGE" "$@" \
    --name INCEpTION \
    --app-version "$APP_VERSION" \
    --input "$INPUT_DIR" \
    --main-jar "$MAIN_JAR" \
    --main-class "$MAIN_CLASS" \
    --java-options -Xmx4g \
    --java-options -Dinception.app-bundle=true \
    --java-options "--add-modules jdk.incubator.vector" \
    --java-options "--enable-native-access=ALL-UNNAMED" \
    --jlink-options "$JLINK_OPTIONS" \
    --icon "$ICON"
}

if [ "$STEP" = "app-image" ]; then
  echo "Building ${TARGET_ARCH} application image via $JPACKAGE..."

  jpackage_app --type app-image --dest "$DEST_DIR"
  exit 0
fi

if [ "$STEP" != "msi" ]; then
  echo "ERROR: Unknown step '$STEP', expected 'app-image' or 'msi'."
  exit 1
fi

if [ ! -d "$LEGAL_DIR" ]; then
  echo "ERROR: No legal documentation in $LEGAL_DIR - it is generated from $APP_IMAGE."
  exit 1
fi

echo "Building ${TARGET_ARCH} MSI via $JPACKAGE..."

rm -f "$DEST_DIR"/*.msi

# Packs exactly the application image that was analysed. Unlike on macOS, where
# the DMG step has to build and sign the app again, nothing on Windows changes
# the image after the analysis.
rm -rf "$APP_IMAGE/legal"
cp -R "$LEGAL_DIR" "$APP_IMAGE/legal"

"$JPACKAGE" \
  --type msi \
  --name INCEpTION \
  --app-version "$APP_VERSION" \
  --app-image "$APP_IMAGE" \
  --dest "$DEST_DIR" \
  --win-dir-chooser \
  --win-menu \
  --win-shortcut

# Tag the produced MSI with the target arch so multiple builds can coexist.
for f in "$DEST_DIR"/*.msi; do
  [ -e "$f" ] || continue
  base=$(basename "$f" .msi)
  case "$base" in
    *-"$TARGET_ARCH") tagged="$f" ;;
    *) tagged="$DEST_DIR/$base-$TARGET_ARCH.msi"; mv "$f" "$tagged" ;;
  esac
  echo "Wrote $tagged"
done
