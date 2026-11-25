#!/usr/bin/env bash
# Copy the two canonical bootstrap halves from the pinned js_widget_runtime
# (.conformance/, filled by scripts/fetch-conformance.sh) into the Android
# asset paths, with a provenance first line. Overwrites only when the content
# differs. CI runs: scripts/fetch-conformance.sh && scripts/sync-bootstrap.sh && git diff --exit-code
set -euo pipefail

cd "$(dirname "$0")/.."
src_dir=".conformance/js_widget_runtime/src"
dest_dir="app/src/main/assets/jsr"
sha="$(git -C .conformance/js_widget_runtime rev-parse --short=12 HEAD)"

for file in bootstrap-runtime.js bootstrap-widget.js; do
  [ -f "$src_dir/$file" ] || { echo "missing $src_dir/$file — run scripts/fetch-conformance.sh" >&2; exit 1; }
  dest="$dest_dir/$file"
  tmp="$(mktemp)"
  {
    echo "// Generated from IstiN/js_widget_runtime@$sha:src/$file by scripts/sync-bootstrap.sh — do not edit"
    cat "$src_dir/$file"
  } > "$tmp"
  if [ ! -e "$dest" ] || ! cmp -s "$tmp" "$dest"; then
    mkdir -p "$dest_dir"
    mv "$tmp" "$dest"
    echo "synced $dest"
  else
    rm -f "$tmp"
  fi
done
