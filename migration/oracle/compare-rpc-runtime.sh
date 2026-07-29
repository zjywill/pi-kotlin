#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TS_ROOT="${PI_TYPESCRIPT_ROOT:-/Users/junyizhang/Git/pi}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

DEFAULT_CODEX_NODE="/Users/junyizhang/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node"
NODE_BIN="${PI_NODE_BIN:-}"
if [[ -z "$NODE_BIN" ]]; then
  if node -e 'const [major, minor] = process.versions.node.split(".").map(Number); process.exit(major > 22 || (major === 22 && minor >= 19) ? 0 : 1)'; then
    NODE_BIN="$(command -v node)"
  elif [[ -x "$DEFAULT_CODEX_NODE" ]]; then
    NODE_BIN="$DEFAULT_CODEX_NODE"
  else
    printf 'RPC runtime grader requires Node.js 22.19 or newer.\n' >&2
    exit 1
  fi
fi
export PATH="$(dirname "$NODE_BIN"):$PATH"

if [[ ! -f "$TS_ROOT/packages/coding-agent/dist/cli.js" ]]; then
  MODEL_DATA_ROOT="$TS_ROOT/packages/ai/src/providers/data"
  MODEL_DATA_SCHEMA="$(
    "$NODE_BIN" -e '
      const fs = require("node:fs");
      try {
        process.stdout.write(String(JSON.parse(fs.readFileSync(process.argv[1], "utf8")).schemaVersion ?? ""));
      } catch {
        process.stdout.write("");
      }
    ' "$MODEL_DATA_ROOT/.manifest.json"
  )"
  if [[ "$MODEL_DATA_SCHEMA" != "3" ]]; then
    HYDRATED_ROOT="$TMP_DIR/typescript-source"
    mkdir -p "$HYDRATED_ROOT"
    git -C "$TS_ROOT" archive HEAD | tar -x -C "$HYDRATED_ROOT"
    if [[ -d "$TS_ROOT/node_modules" ]]; then
      ln -s "$TS_ROOT/node_modules" "$HYDRATED_ROOT/node_modules"
    fi
    (
      cd "$HYDRATED_ROOT"
      NODE_OPTIONS="${NODE_OPTIONS:+$NODE_OPTIONS }--experimental-strip-types" \
        npm run hydrate:model-data >/dev/null
    )
    MODEL_DATA_ROOT="$HYDRATED_ROOT/packages/ai/src/providers/data"
  fi

  npm --prefix "$TS_ROOT/packages/tui" run build
  AI_BUILD_ROOT="$TS_ROOT/packages/ai"
  if [[ -n "${HYDRATED_ROOT:-}" ]]; then
    AI_BUILD_ROOT="$HYDRATED_ROOT/packages/ai"
  fi
  (
    cd "$AI_BUILD_ROOT"
    "$TS_ROOT/node_modules/.bin/tsgo" -p tsconfig.build.json
    rm -rf dist/providers/data
    mkdir -p dist/providers
    cp -R "$MODEL_DATA_ROOT" dist/providers/data
  )
  if [[ "$AI_BUILD_ROOT" != "$TS_ROOT/packages/ai" ]]; then
    rm -rf "$TS_ROOT/packages/ai/dist"
    cp -R "$AI_BUILD_ROOT/dist" "$TS_ROOT/packages/ai/dist"
  fi
  npm --prefix "$TS_ROOT/packages/agent" run build
  npm --prefix "$TS_ROOT/packages/storage/sqlite-node" run build
  npm --prefix "$TS_ROOT/packages/coding-agent" run build
fi

cd "$ROOT"
./gradlew -q :pi-coding-agent:installDist

PI_TYPESCRIPT_ROOT="$TS_ROOT" NODE_NO_WARNINGS=1 \
  "$NODE_BIN" migration/oracle/rpc-runtime.mjs typescript \
  | jq --sort-keys . > "$TMP_DIR/typescript.json"
PI_TYPESCRIPT_ROOT="$TS_ROOT" NODE_NO_WARNINGS=1 \
  "$NODE_BIN" migration/oracle/rpc-runtime.mjs kotlin \
  | jq --sort-keys . > "$TMP_DIR/kotlin.json"

diff -u "$TMP_DIR/typescript.json" "$TMP_DIR/kotlin.json"
