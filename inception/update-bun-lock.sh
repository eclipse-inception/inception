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
set -e

MVN=${MVN:-'mvn'}
# Do not change the order without a good reason - modules that are imported by other modules must come before them!
TS_MODULES="
./inception-support-bootstrap/src/main/ts
./inception-js-api/src/main/ts
./inception-diam/src/main/ts
./inception-io-tei/src/main/ts
./inception-io-html/src/main/ts
./inception-io-xml/src/main/ts
./inception-brat-editor/src/main/ts
./inception-diam-editor/src/main/ts
./inception-external-editor/src/main/ts
./inception-html-apache-annotator-editor/src/main/ts
./inception-pdf-editor2/src/main/ts
./inception-project-export/src/main/ts
./inception-recommendation/src/main/ts
./inception-ui-dashboard-activity/src/main/ts
./inception-ui-kb/src/main/ts
./inception-ui-search/src/main/ts
./inception-ui-scheduling/src/main/ts
./inception-layer-docmetadata/src/main/ts
./inception-assistant-ui/src/main/ts
./inception-workload/src/main/ts
"

# Lock update mode:
#   --keep  keep the existing bun.lock: bun re-resolves only the packages whose range in
#           package.json no longer matches, everything else stays at its locked version.
#   --full  delete bun.lock and resolve everything anew, which moves every package (including
#           transitive ones) to the newest version its ranges allow.
# Without an option, the mode follows the branch: full on main, keep everywhere else, so that a
# maintenance branch never floats by accident.
MODE=""
case "$1" in
  --keep) MODE=keep; shift ;;
  --full) MODE=full; shift ;;
esac
if [ -z "$MODE" ]; then
  if [ "$(git rev-parse --abbrev-ref HEAD 2>/dev/null)" = "main" ]; then
    MODE=full
  else
    MODE=keep
  fi
fi
echo "ℹ️ Lock update mode: $MODE"

TARGET_MODULE="$1"

if [ -n "$TARGET_MODULE" ]; then
  echo "Running only for module: $TARGET_MODULE"
fi

MATCHED=0

# Prints the value of a Maven expression for the project in the current directory. The value is
# read through a file because -DforceStdout prints nothing under mvnd, which only forwards its
# own warnings to stdout.
evaluate() {
  EVAL_FILE=$(mktemp)
  ${MVN} help:evaluate -Dexpression="$1" -q -Doutput="$EVAL_FILE" >/dev/null 2>&1 || true
  EVAL_VALUE=$(cat "$EVAL_FILE")
  rm -f "$EVAL_FILE"
  case "$EVAL_VALUE" in
    ''|*[[:space:]]*|*'['*|*'$'*)
      echo "ERROR: could not evaluate $1 in $(pwd), got: '$EVAL_VALUE'" >&2
      exit 3
      ;;
  esac
  echo "$EVAL_VALUE"
}

for module in $TS_MODULES ; do
  if [ -n "$TARGET_MODULE" ]; then
    if ! echo "$module" | grep -q "$TARGET_MODULE"; then
      continue
    fi
    MATCHED=1
  fi

  pushd "$module" >/dev/null 2>&1
  # A module without a lock is always resolved in full
  if [ "$MODE" = full ] || [ ! -f ../ts_template/bun.lock ]; then
    echo "🧹 Cleaning up generated files in $module"
    rm -f ../ts_template/bun.lock
    rm -f bun.lock
    rm -f package-lock.json
    rm -f package.json
    rm -Rf node_modules
  else
    # The build copies the template lock into place, and bun starts from it
    echo "📌 Keeping the existing lock in $module"
  fi

  rm -f .eslintrc.yml
  cp ../../../../inception-build/src/main/resources/inception/eslint.config.mjs eslint.config.mjs
  cp ../../../../inception-build/src/main/resources/inception/vite.config.js vite.config.js
  cp ../../../../inception-build/src/main/resources/inception/tsconfig.json tsconfig.json
  popd >/dev/null 2>&1

  echo "🔄 Building module $module to update bun.lock"
  pushd "$module/../../.." >/dev/null 2>&1
  ${MVN} clean generate-resources -q -Dnpm-install-command=install -Dts-link-phase=generate-resources

  ORIG_VERSION=$(evaluate project.version)
  SEMVER="$(echo "$ORIG_VERSION" | cut -d'-' -f1).0-SNAPSHOT"
  echo "ℹ️ Setting version $ORIG_VERSION -> $SEMVER"

  ARTIFACT_ID=$(evaluate project.artifactId)
  popd >/dev/null 2>&1

  echo "🔎 Auditing module $module"
  pushd "$module" >/dev/null 2>&1
  if command -v osv-scanner >/dev/null 2>&1; then
    osv-scanner scan source --lockfile=bun.lock --config=../../../../osv-scanner.toml || AUDIT_FAILED=1
  else
    echo "⚠️  osv-scanner not found on PATH — skipping audit. Install via 'brew install osv-scanner' or see https://google.github.io/osv-scanner/installation/"
  fi
  popd >/dev/null 2>&1

  echo "📦 Updating template bun.lock for module $module"
  pushd "$module" >/dev/null 2>&1
  cp bun.lock ../ts_template/bun.lock
  sed -i '' "s/${SEMVER}/\${semver}/g" ../ts_template/bun.lock
  sed -i '' "s/${ARTIFACT_ID}/\${project.artifactId}/g" ../ts_template/bun.lock
  popd >/dev/null 2>&1
done

if [ -n "$TARGET_MODULE" ] && [ "$MATCHED" -eq 0 ]; then
  echo "ERROR: module '$TARGET_MODULE' not found in TS_MODULES" >&2
  exit 2
fi

if [ -n "$AUDIT_FAILED" ]; then
  echo ""
  echo "⚠️  osv-scanner reported vulnerabilities in one or more modules."
  echo "    Address each finding by either:"
  echo "    - upgrading the affected dependency in package.json (and re-running this script), or"
  echo "    - adding an [[IgnoredVulns]] entry to inception/osv-scanner.toml with a reason"
  echo "      and an ignoreUntil date so the decision will be re-evaluated."
  exit 1
fi
