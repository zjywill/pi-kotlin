#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE="$ROOT/migration/sync-state.tsv"
SYNC="$ROOT/migration/upstream-sync.tsv"
INVENTORY="$ROOT/migration/inventory.tsv"
MODE="${1:-full}"

value() {
  awk -F '\t' -v key="$1" '$1 == key { print $2 }' "$STATE"
}

SOURCE_REPO="${PI_SOURCE_REPO:-$(value source_repo)}"
BASELINE="$(value initial_baseline)"
SOURCE_HEAD="$(value source_head)"
ACTUAL_HEAD="$(git -C "$SOURCE_REPO" rev-parse HEAD)"

if [[ "$ACTUAL_HEAD" != "$SOURCE_HEAD" ]]; then
  printf 'Source HEAD drifted: recorded=%s actual=%s\n' "$SOURCE_HEAD" "$ACTUAL_HEAD" >&2
  exit 1
fi

expected="$(mktemp)"
recorded="$(mktemp)"
trap 'rm -f "$expected" "$recorded"' EXIT

git -C "$SOURCE_REPO" log \
  --reverse \
  --format='%H' \
  "$BASELINE..$SOURCE_HEAD" \
  -- \
  packages/ai \
  packages/agent \
  packages/tui \
  packages/storage/sqlite-node \
  packages/coding-agent \
  packages/protocol \
  packages/client \
  packages/server >"$expected"
tail -n +2 "$SYNC" | cut -f1 >"$recorded"

if [[ "$(wc -l <"$recorded")" -ne "$(sort -u "$recorded" | wc -l)" ]]; then
  printf 'Upstream sync manifest contains duplicate commit IDs.\n' >&2
  exit 1
fi

sort -o "$expected" "$expected"
sort -o "$recorded" "$recorded"
if ! diff -u "$expected" "$recorded"; then
  printf 'Upstream sync manifest commit set does not match the recorded source range.\n' >&2
  exit 1
fi

pending_sync="$(awk -F '\t' 'NR > 1 && $2 !~ /^(ported|already-covered|not-applicable|covered-by-known-gap)$/ { print }' "$SYNC")"
if [[ -n "$pending_sync" ]]; then
  printf 'Unreviewed upstream changes:\n%s\n' "$pending_sync" >&2
  exit 1
fi

if [[ "$MODE" == "sync" ]]; then
  printf 'Upstream sync audit passed through %s.\n' "$SOURCE_HEAD"
  exit 0
fi
if [[ "$MODE" != "full" ]]; then
  printf 'Usage: %s [sync|full]\n' "$0" >&2
  exit 2
fi

known_gaps="$(awk -F '\t' 'NR > 1 && $2 == "covered-by-known-gap" { print }' "$SYNC")"
if [[ -n "$known_gaps" ]]; then
  printf 'Migration still has known upstream gaps:\n%s\n' "$known_gaps" >&2
  exit 1
fi

incomplete="$(awk -F '\t' 'NR > 1 && $4 != "complete" && $4 != "not-applicable" { print }' "$INVENTORY")"
if [[ -n "$incomplete" ]]; then
  printf 'Migration is incomplete:\n%s\n' "$incomplete" >&2
  exit 1
fi

printf 'Full migration inventory is complete through %s.\n' "$SOURCE_HEAD"
