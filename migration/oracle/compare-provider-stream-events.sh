#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TS_ROOT="${PI_TYPESCRIPT_ROOT:-/Users/junyizhang/Git/pi}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

normalize_json() {
  node -e '
    const fs = require("node:fs");
    const sort = (value) => {
      if (Array.isArray(value)) return value.map(sort);
      if (value && typeof value === "object") {
        return Object.fromEntries(Object.keys(value).sort().map((key) => [key, sort(value[key])]));
      }
      return value;
    };
    process.stdout.write(JSON.stringify(sort(JSON.parse(fs.readFileSync(0, "utf8"))), null, 2) + "\n");
  '
}

cd "$ROOT"
PI_TYPESCRIPT_ROOT="$TS_ROOT" NODE_NO_WARNINGS=1 \
  node --experimental-strip-types migration/oracle/provider-stream-events.ts \
  | normalize_json > "$TMP_DIR/typescript.json"
./gradlew -q :pi-ai:providerStreamEventOracle \
  | normalize_json > "$TMP_DIR/kotlin.json"

diff -u "$TMP_DIR/typescript.json" "$TMP_DIR/kotlin.json"
