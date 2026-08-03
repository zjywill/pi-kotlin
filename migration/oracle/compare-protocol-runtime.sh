#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TS_ROOT="${PI_TYPESCRIPT_ROOT:-/Users/junyizhang/Git/pi}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

DEFAULT_CODEX_NODE="/Users/junyizhang/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node"
NODE_BIN="${PI_NODE_BIN:-}"
if [[ -z "$NODE_BIN" ]]; then
  if [[ -x "$DEFAULT_CODEX_NODE" ]]; then
    NODE_BIN="$DEFAULT_CODEX_NODE"
  else
    NODE_BIN="$(command -v node)"
  fi
fi
export PATH="$(dirname "$NODE_BIN"):$PATH"

npm --prefix "$TS_ROOT/packages/protocol" run build >/dev/null

cd "$ROOT"
PI_TYPESCRIPT_ROOT="$TS_ROOT" NODE_NO_WARNINGS=1 \
  "$NODE_BIN" migration/oracle/protocol-runtime.mjs \
  | jq --sort-keys . > "$TMP_DIR/typescript.json"
./gradlew -q :pi-protocol:protocolRuntimeOracle \
  | jq --sort-keys . > "$TMP_DIR/kotlin.json"

diff -u "$TMP_DIR/typescript.json" "$TMP_DIR/kotlin.json"
