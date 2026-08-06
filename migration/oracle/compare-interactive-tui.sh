#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TS_ROOT="${PI_TYPESCRIPT_ROOT:-/Users/junyizhang/Git/pi}"
TMP_DIR="$(mktemp -d)"
KEEP_TMP="${PI_TUI_PTY_KEEP_TMP:-0}"
cleanup() {
  if [[ "$KEEP_TMP" == "1" ]]; then
    printf 'Interactive TUI PTY artifacts kept at %s\n' "$TMP_DIR" >&2
  else
    rm -rf "$TMP_DIR"
  fi
}
trap cleanup EXIT

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
UI_FIXTURE="$ROOT/migration/fixtures/interactive-tui-pty.ts"
PROVIDER_FIXTURE="$ROOT/migration/fixtures/rpc-runtime/native-provider.ts"
ROWS=24

if [[ ! -f "$TS_CLI" ]]; then
  printf 'TypeScript coding-agent distribution is missing: %s\n' "$TS_CLI" >&2
  printf 'Build the pinned source checkout before running this oracle.\n' >&2
  exit 2
fi
if ! command -v expect >/dev/null 2>&1; then
  printf 'Interactive TUI grader requires expect.\n' >&2
  exit 2
fi

cd "$ROOT"
./gradlew -q :pi-coding-agent:installDist

run_pty() {
  local runtime="$1"
  local width="$2"
  local scenario="$3"
  local ui_mode="$4"
  local case_dir="$TMP_DIR/$runtime-$width-$scenario-$ui_mode"
  local overlay_width=$((width / 2))
  local overlay_column=$((width - overlay_width - 3))
  mkdir -p "$case_dir/agent" "$case_dir/home" "$case_dir/project"

  export PTY_RUNTIME="$runtime"
  export PTY_WIDTH="$width"
  export PTY_ROWS="$ROWS"
  export PTY_SCENARIO="$scenario"
  export PTY_UI_MODE="$ui_mode"
  export PTY_AGENT="$case_dir/agent"
  export PTY_HOME="$case_dir/home"
  export PTY_PROJECT="$case_dir/project"
  export PTY_NODE_BIN="$NODE_BIN"
  export PTY_TS_CLI="$TS_CLI"
  export PTY_KT_CLI="$KT_CLI"
  export PTY_UI_FIXTURE="$UI_FIXTURE"
  export PTY_PROVIDER_FIXTURE="$PROVIDER_FIXTURE"

  if ! expect <<'EOF' >"$case_dir/expect.log" 2>&1
set timeout 45
set stty_init "rows $env(PTY_ROWS) columns $env(PTY_WIDTH)"
log_user 1
cd $env(PTY_PROJECT)
if {$env(PTY_RUNTIME) eq "typescript"} {
  spawn -noecho env HOME=$env(PTY_HOME) PI_CODING_AGENT_DIR=$env(PTY_AGENT) PI_TUI_PTY_SCENARIO=$env(PTY_SCENARIO) NODE_NO_WARNINGS=1 TERM=xterm-256color $env(PTY_NODE_BIN) $env(PTY_TS_CLI) --tui-mode $env(PTY_UI_MODE) --extension $env(PTY_UI_FIXTURE) --extension $env(PTY_PROVIDER_FIXTURE) --provider rpc-fixture --model model-a --no-session --offline --approve
} else {
  spawn -noecho env HOME=$env(PTY_HOME) PI_CODING_AGENT_DIR=$env(PTY_AGENT) PI_TUI_PTY_SCENARIO=$env(PTY_SCENARIO) NODE_NO_WARNINGS=1 TERM=xterm-256color $env(PTY_KT_CLI) --tui-mode $env(PTY_UI_MODE) --extension $env(PTY_UI_FIXTURE) --extension $env(PTY_PROVIDER_FIXTURE) --provider rpc-fixture --model model-a --no-session --offline --approve
}

proc wait_for {value label} {
  expect {
    -exact $value {}
    timeout { puts stderr "$label timed out"; exit 2 }
    eof { puts stderr "$label exited early"; exit 3 }
  }
}

proc submit {value} {
  send -- "\033\[200~${value}\033\[201~"
  wait_for $value "pasted command $value"
  after 100
  send -- "\r"
}

proc wait_runtime_ready {} {
  global env
  if {$env(PTY_RUNTIME) eq "kotlin"} {
    wait_for "> " "Kotlin editor readiness"
    after 100
  } else {
    after 300
  }
}

proc wait_autocomplete {value label} {
  global env
  set previousTimeout $::timeout
  if {$env(PTY_RUNTIME) eq "typescript"} {
    set ::timeout 1
  } else {
    set ::timeout 10
  }
  expect {
    -exact $value {
      set ::timeout $previousTimeout
    }
    timeout {
      set ::timeout $previousTimeout
      send -- "\t"
      wait_for $value $label
    }
    eof {
      puts stderr "$label exited early"
      exit 3
    }
  }
}

proc wait_for_both {first second label} {
  set firstSeen 0
  set secondSeen 0
  while {!$firstSeen || !$secondSeen} {
    expect {
      -exact $first {
        set firstSeen 1
      }
      -exact $second {
        set secondSeen 1
      }
      timeout {
        puts stderr "$label timed out"
        exit 2
      }
      eof {
        puts stderr "$label exited early"
        exit 3
      }
    }
  }
}

wait_for "PTY_HDR:$env(PTY_WIDTH)" "header"
wait_for "PTY_WA:$env(PTY_WIDTH)" "above-editor widget"
wait_for "PTY_WB:$env(PTY_WIDTH)" "below-editor widget"
wait_for "PTY_FTR:$env(PTY_WIDTH):pty=ready" "footer"
if {$env(PTY_RUNTIME) eq "kotlin"} {
  wait_for "Type /help for commands." "Kotlin startup"
  wait_for "> " "Kotlin editor readiness"
}
after 500

if {$env(PTY_SCENARIO) eq "core"} {
  if {$env(PTY_RUNTIME) eq "kotlin"} {
    submit "/help"
    wait_for "!<command>" "Kotlin /help"
    wait_runtime_ready
  }
  send -- "#b"
  wait_autocomplete "PTY_BASE_OK" "built-in autocomplete delegation"
  send -- "\t"
  after 100
  send -- "\r"
  wait_for "PTY_BASE_DONE" "delegated autocomplete completion"
  wait_runtime_ready

  send -- "~^p"
  wait_for_both "PTY_AC_ITEM" "PTY_RAW_OFF" "raw terminal input and extension autocomplete"
  after 100
  send -- "\177\177"
  after 200
} elseif {$env(PTY_SCENARIO) eq "editor"} {
  submit "/editor-on"
  wait_for_both \
    "PTY_ED_ON" \
    "PTY_ED:$env(PTY_WIDTH):seed-paste" \
    "custom editor install and initial text"
  send -- "-typed"
  wait_for "PTY_ED:$env(PTY_WIDTH):seed-paste-typed" "custom editor input"
  send -- "|"
  wait_for "PTY_ED_OFF:seed-paste-typed" "custom editor restore"
  wait_runtime_ready
} elseif {$env(PTY_SCENARIO) eq "overlay"} {
  submit "/overlay-probe"
  wait_for "PTY_OVR:[expr {$env(PTY_WIDTH) / 2}]:boot" "overlay mount"
  after 250
  send -- "u"
  wait_for "PTY_OVR:[expr {$env(PTY_WIDTH) / 2}]:unf" "overlay unfocus"
  after 250
  send -- "r"
  wait_for "PTY_OVR:[expr {$env(PTY_WIDTH) / 2}]:ready" "overlay refocus"
  send -- "t"
  wait_for "PTY_OVR:[expr {$env(PTY_WIDTH) / 2}]:hide" "overlay hide and show"
  after 250
  send -- "r"
  wait_for "PTY_OVR:[expr {$env(PTY_WIDTH) / 2}]:ready" "overlay input after show"
  send -- "\r"
  wait_for "PTY_OVR_RESULT:ok" "overlay close"
  wait_runtime_ready
} else {
  puts stderr "Unknown PTY scenario: $env(PTY_SCENARIO)"
  exit 5
}

if {$env(PTY_SCENARIO) ne "core"} {
  send -- "\004"
} elseif {$env(PTY_RUNTIME) eq "typescript"} {
  submit "/quit"
} else {
  submit "/exit"
}
expect {
  eof {}
  timeout { puts stderr "clean exit timed out"; exit 4 }
}
catch wait result
exit [lindex $result 3]
EOF
  then
    printf '%s %s-column %s %s interactive TUI PTY failed:\n' "$runtime" "$width" "$scenario" "$ui_mode" >&2
    tail -120 "$case_dir/expect.log" >&2
    return 1
  fi

  if [[ "$scenario" == "overlay" ]]; then
    "$NODE_BIN" \
      "$ROOT/migration/oracle/verify-terminal-screen.mjs" \
      "$case_dir/expect.log" \
      "$ROWS" \
      "$width" \
      "PTY_OVR:$overlay_width:boot" \
      1 \
      "$overlay_column" >/dev/null
  fi

  printf '%s %s-column %s %s interactive TUI PTY passed.\n' "$runtime" "$width" "$scenario" "$ui_mode"
}

read -r -a widths <<<"${PI_TUI_PTY_WIDTHS:-40 80 120}"
read -r -a runtimes <<<"${PI_TUI_PTY_RUNTIMES:-typescript kotlin}"
read -r -a scenarios <<<"${PI_TUI_PTY_SCENARIOS:-core editor overlay}"
read -r -a ui_modes <<<"${PI_TUI_PTY_UI_MODES:-regular fullscreen}"

for width in "${widths[@]}"; do
  for runtime in "${runtimes[@]}"; do
    for scenario in "${scenarios[@]}"; do
      for ui_mode in "${ui_modes[@]}"; do
        run_pty "$runtime" "$width" "$scenario" "$ui_mode"
      done
    done
  done
done

printf 'Interactive TUI parity passed for widths [%s], runtimes [%s], scenarios [%s], and TUI modes [%s].\n' \
  "${widths[*]}" \
  "${runtimes[*]}" \
  "${scenarios[*]}" \
  "${ui_modes[*]}"
