#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TS_ROOT="${PI_TYPESCRIPT_ROOT:-/Users/junyizhang/Git/pi}"
TMP_DIR="$(mktemp -d)"
ORACLE_TS_ROOT="$TS_ROOT"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

MODEL_DATA_MANIFEST="$TS_ROOT/packages/ai/src/providers/data/.manifest.json"
MODEL_DATA_SCHEMA="$(
  node -e '
    const fs = require("node:fs");
    const path = process.argv[1];
    try {
      process.stdout.write(String(JSON.parse(fs.readFileSync(path, "utf8")).schemaVersion ?? ""));
    } catch {
      process.stdout.write("");
    }
  ' "$MODEL_DATA_MANIFEST"
)"
if [[ "$MODEL_DATA_SCHEMA" != "3" ]]; then
  ORACLE_TS_ROOT="$TMP_DIR/typescript"
  mkdir -p "$ORACLE_TS_ROOT"
  git -C "$TS_ROOT" archive HEAD | tar -x -C "$ORACLE_TS_ROOT"
  if [[ -d "$TS_ROOT/node_modules" ]]; then
    ln -s "$TS_ROOT/node_modules" "$ORACLE_TS_ROOT/node_modules"
  fi
  (
    cd "$ORACLE_TS_ROOT"
    NODE_OPTIONS="${NODE_OPTIONS:+$NODE_OPTIONS }--experimental-strip-types" \
      npm run hydrate:model-data >/dev/null
  )
fi

cd "$ROOT"
PI_TYPESCRIPT_ROOT="$ORACLE_TS_ROOT" NODE_NO_WARNINGS=1 \
  node --experimental-strip-types migration/oracle/github-copilot.ts \
  | jq --sort-keys . > "$TMP_DIR/typescript.json"
./gradlew -q :pi-ai:githubCopilotOracle \
  | jq --sort-keys . > "$TMP_DIR/kotlin.json"

diff -u "$TMP_DIR/typescript.json" "$TMP_DIR/kotlin.json"
