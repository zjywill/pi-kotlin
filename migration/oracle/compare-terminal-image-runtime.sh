#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TS_ROOT="${PI_TYPESCRIPT_ROOT:-/Users/junyizhang/Git/pi}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

npm --prefix "$TS_ROOT/packages/tui" run build >/dev/null

cd "$ROOT"
PI_TYPESCRIPT_ROOT="$TS_ROOT" NODE_NO_WARNINGS=1 \
  node migration/oracle/terminal-image-runtime.mjs \
  | jq --sort-keys . > "$TMP_DIR/typescript.json"
./gradlew -q :pi-tui:terminalImageOracle \
  | jq --sort-keys . > "$TMP_DIR/kotlin.json"

diff -u "$TMP_DIR/typescript.json" "$TMP_DIR/kotlin.json"
printf 'Terminal image runtime parity passed.\n'
