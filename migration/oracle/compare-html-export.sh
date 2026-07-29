#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TS_ROOT="${PI_TYPESCRIPT_ROOT:-/Users/junyizhang/Git/pi}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

cd "$ROOT"
PI_TYPESCRIPT_ROOT="$TS_ROOT" NODE_NO_WARNINGS=1 \
  node --experimental-strip-types migration/oracle/html-export.ts \
  > "$TMP_DIR/typescript.html"
./gradlew -q :pi-coding-agent:htmlExportOracle > "$TMP_DIR/kotlin.html"

cmp "$TMP_DIR/typescript.html" "$TMP_DIR/kotlin.html"
shasum -a 256 "$TMP_DIR/typescript.html"
