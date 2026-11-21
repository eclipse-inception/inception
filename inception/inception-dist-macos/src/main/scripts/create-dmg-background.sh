#!/bin/bash
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


# Renders the background of the DMG window from its vector source:
#
#   src/main/dmg/background.svg -> src/main/dmg/background.tiff
#
# The TIFF holds a 1x and a 2x image, so that Finder shows it sharp on
# high-resolution screens. It is committed, so that the build does not need an
# SVG renderer; run this script after changing the SVG. Needs rsvg-convert
# (Homebrew: librsvg) and tiffutil (part of macOS).

set -eu

DMG_DIR="$(cd "$(dirname "$0")/../dmg" && pwd)"
SVG="$DMG_DIR/background.svg"
TIFF="$DMG_DIR/background.tiff"

# Must match the window size in dmg-setup.scpt
WIDTH=640
HEIGHT=530

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

rsvg-convert -w "$WIDTH" -h "$HEIGHT" "$SVG" -o "$TMP/background.png"
rsvg-convert -w $((WIDTH * 2)) -h $((HEIGHT * 2)) "$SVG" -o "$TMP/background@2x.png"
tiffutil -cathidpicheck "$TMP/background.png" "$TMP/background@2x.png" -out "$TIFF"

echo "Wrote $TIFF"
