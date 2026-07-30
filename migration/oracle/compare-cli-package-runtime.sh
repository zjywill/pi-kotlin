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

TS_CLI="$TS_ROOT/packages/coding-agent/dist/cli.js"
KT_CLI="$ROOT/pi-coding-agent/build/install/pi/bin/pi"
PACKAGE_FIXTURE="$ROOT/migration/fixtures/package-resources"
PROVIDER_FIXTURE="$ROOT/migration/fixtures/rpc-runtime/native-provider.ts"

if [[ ! -f "$TS_CLI" ]]; then
  printf 'TypeScript coding-agent distribution is missing: %s\n' "$TS_CLI" >&2
  printf 'Build the pinned source checkout before running this oracle.\n' >&2
  exit 2
fi
if ! command -v expect >/dev/null 2>&1; then
  printf 'CLI/package runtime grader requires expect.\n' >&2
  exit 2
fi
if ! command -v jq >/dev/null 2>&1; then
  printf 'CLI/package runtime grader requires jq.\n' >&2
  exit 2
fi

cd "$ROOT"
./gradlew -q :pi-coding-agent:installDist

runtime_command() {
  local runtime="$1"
  local agent_dir="$2"
  local home_dir="$3"
  local project_dir="$4"
  shift 4
  if [[ "$runtime" == "typescript" ]]; then
    (
      cd "$project_dir"
      env \
        HOME="$home_dir" \
        PI_CODING_AGENT_DIR="$agent_dir" \
        NODE_NO_WARNINGS=1 \
        TERM=dumb \
        "$NODE_BIN" "$TS_CLI" "$@"
    )
  else
    (
      cd "$project_dir"
      env \
        HOME="$home_dir" \
        PI_CODING_AGENT_DIR="$agent_dir" \
        NODE_NO_WARNINGS=1 \
        TERM=dumb \
        "$KT_CLI" "$@"
    )
  fi
}

compare_command_case() {
  local label="$1"
  shift
  local case_dir="$TMP_DIR/cases/$label"
  mkdir -p \
    "$case_dir/typescript-agent" \
    "$case_dir/typescript-home" \
    "$case_dir/typescript-project" \
    "$case_dir/kotlin-agent" \
    "$case_dir/kotlin-home" \
    "$case_dir/kotlin-project"

  set +e
  runtime_command \
    typescript \
    "$case_dir/typescript-agent" \
    "$case_dir/typescript-home" \
    "$case_dir/typescript-project" \
    "$@" >"$case_dir/typescript.stdout" 2>"$case_dir/typescript.stderr"
  local typescript_status=$?
  runtime_command \
    kotlin \
    "$case_dir/kotlin-agent" \
    "$case_dir/kotlin-home" \
    "$case_dir/kotlin-project" \
    "$@" >"$case_dir/kotlin.stdout" 2>"$case_dir/kotlin.stderr"
  local kotlin_status=$?
  set -e

  if [[ "$typescript_status" != "$kotlin_status" ]]; then
    printf '%s exit mismatch: TypeScript=%s Kotlin=%s\n' \
      "$label" "$typescript_status" "$kotlin_status" >&2
    return 1
  fi
  diff -u "$case_dir/typescript.stdout" "$case_dir/kotlin.stdout"
  diff -u "$case_dir/typescript.stderr" "$case_dir/kotlin.stderr"
}

for command in install remove update list config; do
  compare_command_case "help-$command" "$command" --help
done

compare_command_case "install-missing" install
compare_command_case "remove-missing" remove
compare_command_case "install-unknown" install --bogus
compare_command_case "remove-unknown" remove --bogus
compare_command_case "update-unknown" update --bogus
compare_command_case "list-unknown" list --bogus
compare_command_case "config-unknown" config --bogus
compare_command_case "list-one-positional" list ignored
compare_command_case "list-two-positionals" list ignored extra
compare_command_case "update-missing-extension" update --extension
compare_command_case "update-all-self" update --all --self
compare_command_case "update-all-models" update --all --models
compare_command_case "update-models-self" update --models --self
compare_command_case "update-models-positional" update --models extra
compare_command_case "update-models-missing-extension" update --models --extension
compare_command_case "update-models-two-positionals" update --models one two
compare_command_case "config-positional" config extra
compare_command_case \
  "offline-print" \
  --offline \
  --extension "$PROVIDER_FIXTURE" \
  --provider rpc-fixture \
  --model model-a \
  --no-session \
  -p oracle

run_package_lifecycle() {
  local runtime="$1"
  local runtime_dir="$2"
  local output="$3"
  mkdir -p "$runtime_dir/agent" "$runtime_dir/home" "$runtime_dir/project"
  {
    runtime_command \
      "$runtime" \
      "$runtime_dir/agent" \
      "$runtime_dir/home" \
      "$runtime_dir/project" \
      install "$PACKAGE_FIXTURE" --no-approve
    runtime_command \
      "$runtime" \
      "$runtime_dir/agent" \
      "$runtime_dir/home" \
      "$runtime_dir/project" \
      list --no-approve
    jq --sort-keys . "$runtime_dir/agent/settings.json"
    runtime_command \
      "$runtime" \
      "$runtime_dir/agent" \
      "$runtime_dir/home" \
      "$runtime_dir/project" \
      remove "$PACKAGE_FIXTURE" --no-approve
    jq --sort-keys . "$runtime_dir/agent/settings.json"
  } >"$output"
}

run_package_lifecycle \
  typescript \
  "$TMP_DIR/package-lifecycle/typescript" \
  "$TMP_DIR/package-lifecycle-typescript.txt"
run_package_lifecycle \
  kotlin \
  "$TMP_DIR/package-lifecycle/kotlin" \
  "$TMP_DIR/package-lifecycle-kotlin.txt"
diff -u \
  "$TMP_DIR/package-lifecycle-typescript.txt" \
  "$TMP_DIR/package-lifecycle-kotlin.txt"

run_login_pty() {
  local runtime="$1"
  local runtime_dir="$2"
  mkdir -p "$runtime_dir/agent" "$runtime_dir/home" "$runtime_dir/project"
  export LOGIN_RUNTIME="$runtime"
  export LOGIN_AGENT="$runtime_dir/agent"
  export LOGIN_HOME="$runtime_dir/home"
  export LOGIN_PROJECT="$runtime_dir/project"
  export LOGIN_TRANSCRIPT="$runtime_dir/transcript.log"
  export LOGIN_NODE_BIN="$NODE_BIN"
  export LOGIN_TS_CLI="$TS_CLI"
  export LOGIN_KT_CLI="$KT_CLI"
  export LOGIN_FIXTURE="$PROVIDER_FIXTURE"

  if ! expect <<'EOF' >"$LOGIN_TRANSCRIPT" 2>&1
set timeout 40
set stty_init "rows 24 columns 80"
cd $env(LOGIN_PROJECT)
if {$env(LOGIN_RUNTIME) eq "typescript"} {
  spawn -noecho env HOME=$env(LOGIN_HOME) PI_CODING_AGENT_DIR=$env(LOGIN_AGENT) NODE_NO_WARNINGS=1 TERM=xterm-256color $env(LOGIN_NODE_BIN) $env(LOGIN_TS_CLI) --extension $env(LOGIN_FIXTURE) --provider rpc-fixture --model model-a --no-session --offline --approve
  expect {
    "native-provider.ts" {}
    timeout { puts stderr "TypeScript login startup timed out"; exit 2 }
    eof { puts stderr "TypeScript login exited during startup"; exit 3 }
  }
  after 800
} else {
  spawn -noecho env HOME=$env(LOGIN_HOME) PI_CODING_AGENT_DIR=$env(LOGIN_AGENT) NODE_NO_WARNINGS=1 TERM=xterm-256color $env(LOGIN_KT_CLI) --extension $env(LOGIN_FIXTURE) --provider rpc-fixture --model model-a --no-session --offline --approve
  expect {
    "Type /help for commands. Ctrl-D or /exit quits." {}
    timeout { puts stderr "Kotlin login startup timed out"; exit 2 }
    eof { puts stderr "Kotlin login exited during startup"; exit 3 }
  }
  expect {
    -re {> .*} {}
    timeout { puts stderr "Kotlin login editor did not become ready"; exit 4 }
  }
}
send -- "/login rpc-fixture\r"
expect {
  "RPC fixture key" {}
  timeout { puts stderr "API-key prompt timed out"; exit 5 }
  eof { puts stderr "Login exited before the API-key prompt"; exit 6 }
}
send -- "rpc-secret\r"
if {$env(LOGIN_RUNTIME) eq "typescript"} {
  expect {
    "Saved API key for RPC Fixture" {}
    timeout { puts stderr "TypeScript API-key login timed out"; exit 7 }
    eof { puts stderr "TypeScript login exited before completion"; exit 8 }
  }
} else {
  expect {
    "Logged in to RPC Fixture." {}
    timeout { puts stderr "Kotlin API-key login timed out"; exit 7 }
    eof { puts stderr "Kotlin login exited before completion"; exit 8 }
  }
}
after 1000
if {$env(LOGIN_RUNTIME) eq "typescript"} {
  after 1000
  send -- "/quit\r"
} else {
  expect {
    -exact "> " {}
    timeout { puts stderr "Kotlin login editor did not become ready for exit"; exit 9 }
    eof { puts stderr "Kotlin login exited before the final editor prompt"; exit 9 }
  }
  after 100
  send -- "/exit\r"
}
expect {
  eof {}
  timeout { puts stderr "Login PTY did not exit after the explicit exit command"; exit 10 }
}
catch wait result
exit [lindex $result 3]
EOF
  then
    printf '%s API-key PTY failed:\n' "$runtime" >&2
    tail -80 "$LOGIN_TRANSCRIPT" >&2
    return 1
  fi

  if rg -q 'rpc-secret' "$LOGIN_TRANSCRIPT"; then
    printf '%s API-key PTY leaked the secret into its transcript.\n' "$runtime" >&2
    return 1
  fi
  if [[ "$(stat -f '%Lp' "$runtime_dir/agent/auth.json")" != "600" ]]; then
    printf '%s auth.json is not owner-only.\n' "$runtime" >&2
    return 1
  fi
  jq --sort-keys . "$runtime_dir/agent/auth.json" >"$runtime_dir/auth.json"
}

run_login_pty typescript "$TMP_DIR/login/typescript"
run_login_pty kotlin "$TMP_DIR/login/kotlin"
diff -u "$TMP_DIR/login/typescript/auth.json" "$TMP_DIR/login/kotlin/auth.json"

run_config_pty() {
  local runtime="$1"
  local runtime_dir="$2"
  mkdir -p "$runtime_dir/agent/extensions" "$runtime_dir/home" "$runtime_dir/project"
  cp "$PROVIDER_FIXTURE" "$runtime_dir/agent/extensions/config-smoke.ts"
  export CONFIG_RUNTIME="$runtime"
  export CONFIG_AGENT="$runtime_dir/agent"
  export CONFIG_HOME="$runtime_dir/home"
  export CONFIG_PROJECT="$runtime_dir/project"
  export CONFIG_NODE_BIN="$NODE_BIN"
  export CONFIG_TS_CLI="$TS_CLI"
  export CONFIG_KT_CLI="$KT_CLI"

  if ! expect <<'EOF' >"$runtime_dir/transcript.log" 2>&1
set timeout 30
set stty_init "rows 24 columns 100"
cd $env(CONFIG_PROJECT)
if {$env(CONFIG_RUNTIME) eq "typescript"} {
  spawn -noecho env HOME=$env(CONFIG_HOME) PI_CODING_AGENT_DIR=$env(CONFIG_AGENT) NODE_NO_WARNINGS=1 TERM=xterm-256color $env(CONFIG_NODE_BIN) $env(CONFIG_TS_CLI) config --no-approve
} else {
  spawn -noecho env HOME=$env(CONFIG_HOME) PI_CODING_AGENT_DIR=$env(CONFIG_AGENT) NODE_NO_WARNINGS=1 TERM=xterm-256color $env(CONFIG_KT_CLI) config --no-approve
}
expect {
  "config-smoke.ts" {}
  timeout { puts stderr "Resource config selector did not render"; exit 2 }
  eof { puts stderr "Resource config selector exited before rendering"; exit 3 }
}
after 300
send -- " "
after 500
send -- "\033"
expect {
  eof {}
  timeout { puts stderr "Resource config selector did not exit"; exit 4 }
}
catch wait result
exit [lindex $result 3]
EOF
  then
    printf '%s config selector PTY failed:\n' "$runtime" >&2
    tail -80 "$runtime_dir/transcript.log" >&2
    return 1
  fi

  jq --sort-keys . "$runtime_dir/agent/settings.json" >"$runtime_dir/settings.json"
  jq -e \
    '.extensions == ["-extensions/config-smoke.ts"] and (keys == ["extensions"])' \
    "$runtime_dir/settings.json" >/dev/null
}

run_config_pty typescript "$TMP_DIR/config/typescript"
run_config_pty kotlin "$TMP_DIR/config/kotlin"
diff -u "$TMP_DIR/config/typescript/settings.json" "$TMP_DIR/config/kotlin/settings.json"

printf 'CLI/package runtime parity passed.\n'
