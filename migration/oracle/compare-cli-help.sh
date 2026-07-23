#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE_ROOT="${PI_TYPESCRIPT_ROOT:-/Users/junyizhang/Git/pi}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

if [[ ! -d "$SOURCE_ROOT/node_modules" ]]; then
  echo "TypeScript dependencies are missing. Run npm ci --ignore-scripts in $SOURCE_ROOT." >&2
  exit 2
fi

(
  cd "$SOURCE_ROOT"
  ./pi-test.sh --help
) >"$TMP_DIR/typescript.txt"

(
  cd "$ROOT"
  ./gradlew -q :pi-coding-agent:run --args="--help"
) >"$TMP_DIR/kotlin.txt"

diff -u "$TMP_DIR/typescript.txt" "$TMP_DIR/kotlin.txt"
