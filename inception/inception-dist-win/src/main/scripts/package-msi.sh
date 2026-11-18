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

# Builds a per-arch Windows MSI via jpackage. Falls back to JAVA_HOME when
# the native-arch JDK home is not configured; skips with a warning when
# building cross-arch without a configured JDK.
#
# Args (positional):
#   1: target arch (x86_64 | aarch64)
#   2: JDK home for target arch (may be empty -> resolve from JAVA_HOME or skip)
#   3: input dir (jpackage-input)
#   4: destination dir
#   5: main jar
#   6: main class
#   7: app version
#   8: icon path

set -eu

TARGET_ARCH="$1"
JDK_HOME="$2"
INPUT_DIR="$3"
DEST_DIR="$4"
MAIN_JAR="$5"
MAIN_CLASS="$6"
APP_VERSION="$7"
ICON="$8"

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

echo "Building ${TARGET_ARCH} MSI via $JPACKAGE..."

"$JPACKAGE" \
  --type msi \
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
