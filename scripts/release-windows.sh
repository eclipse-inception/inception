#!/usr/bin/env bash
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

# Builds the Windows MSI on GitHub Actions and downloads it.
#
# The MSI cannot be built locally, so this dispatches the dist-win workflow on
# the release tag, waits for it, and unpacks the artifact where the other
# release steps expect it - so `release.sh status` and `release.sh draft` then
# treat it like a locally built artifact.
#
# Re-runnable: if a successful run for the tag already exists, it downloads from
# that instead of building again. Use --rebuild to force a new run.
#
# Usage: release-windows.sh [--rebuild] [--dry-run]

set -euo pipefail

# shellcheck source=release-functions.shlib
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/release-functions.shlib"

WORKFLOW="dist-win.yml"
ARTIFACT="inception-msi-x86_64"

rebuild=0
dry_run=0
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help|help)
      info "Usage: release-windows.sh [--rebuild] [--dry-run]"; exit 0 ;;
    --rebuild) rebuild=1 ;;
    --dry-run) dry_run=1 ;;
    *) die "Unexpected argument '$1'." ;;
  esac
  shift
done

# Like macos and docker, the version comes from the checkout release:perform left
# behind rather than from an argument - there is one release in flight at a time.
version="$(version_from_checkout)" || die "\
No target/checkout - run the Maven release first (release.sh maven).
The MSI is built from the release tag recorded there."

tag="$(tag_for "$version")"
# The MSI carries the base version, dropping any beta suffix.
msi="eclipse-inception-incubation-$(base_version_for "$version")-x86_64.msi"
dest="$WIN_TARGET/jpackage-x86_64"

# Latest run of the workflow for this tag, whatever its state.
latest_run() {
  gh run list --workflow "$WORKFLOW" --repo "$REPO" --branch "$tag" \
    --limit 1 --json databaseId,status,conclusion 2>/dev/null
}

info "Windows MSI for ${C_BOLD}$version${C_RESET} ($tag)"
info "  workflow: $WORKFLOW on $tag"
info "  artifact: $ARTIFACT -> ${dest#"$PROJECT_ROOT"/}/$msi"
info ""

if [ "$dry_run" -eq 1 ]; then
  info "${C_DIM}dry run - nothing executed${C_RESET}"
  exit 0
fi

require_tool gh
require_tool jq
warn_if_checkout_published "$version"

if [ -f "$dest/$msi" ] && [ "$rebuild" -eq 0 ]; then
  info "Already present - nothing to do (use --rebuild to force a new run)."
  exit 0
fi

run_json="$(latest_run)"
run_id="$(jq -r '.[0].databaseId // empty' <<<"$run_json")"
conclusion="$(jq -r '.[0].conclusion // empty' <<<"$run_json")"

if [ "$rebuild" -eq 1 ] || [ -z "$run_id" ] || [ "$conclusion" = "failure" ]; then
  git ls-remote --tags origin "refs/tags/$tag" 2>/dev/null | grep -q . \
    || die "Tag $tag is not on origin - the workflow can only build a pushed tag."

  info "Dispatching $WORKFLOW on $tag ..."
  gh workflow run "$WORKFLOW" --repo "$REPO" --ref "$tag" -f ref="$tag"

  # `gh workflow run` does not report the run id, so wait for a *new* run to
  # appear rather than picking up the previous one for this tag.
  prev_run_id="$run_id"
  for _ in $(seq 1 30); do
    sleep 2
    run_json="$(latest_run)"
    run_id="$(jq -r '.[0].databaseId // empty' <<<"$run_json")"
    if [ -n "$run_id" ] && [ "$run_id" != "$prev_run_id" ]; then
      break
    fi
    run_id=''
  done
  [ -n "$run_id" ] || die "Dispatched, but no new run appeared. Check: gh run list --workflow $WORKFLOW"
fi

info "Run: https://github.com/$REPO/actions/runs/$run_id"
gh run watch "$run_id" --repo "$REPO" --exit-status \
  || die "The workflow run failed. See the log above."

mkdir -p "$dest"
info ""
info "Downloading $ARTIFACT ..."
gh run download "$run_id" --repo "$REPO" --name "$ARTIFACT" --dir "$dest"

[ -f "$dest/$msi" ] || die "\
Downloaded, but $msi is not there. Contents:
$(ls -1 "$dest" 2>/dev/null)"

info ""
info "$msi ($(file_size "$dest/$msi"))"
info "Now check: release.sh status $version"
