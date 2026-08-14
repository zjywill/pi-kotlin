# pi-kotlin

Kotlin/JVM migration of the [Pi Agent Harness](https://github.com/earendil-works/pi).

The first migration pass is pinned to upstream commit
`9b3a2059171bcc74ad9d2cadeea6d186776cf2db` (July 22, 2026), and the reviewed
incremental sync currently reaches
`b1efcf7d7c5d7394fbb12ede0174e04d39ee7004` (August 14, 2026). The target is
behavioral compatibility at the CLI, session, provider, agent-loop, tool, and
terminal boundaries. JVM-native implementations replace Node/Bun-specific
packaging and process internals.

## Modules

| Kotlin module | Upstream package |
| --- | --- |
| `pi-ai` | `@earendil-works/pi-ai` |
| `pi-agent-core` | `@earendil-works/pi-agent-core` |
| `pi-tui` | `@earendil-works/pi-tui` |
| `pi-storage-sqlite` | `@earendil-works/pi-storage-sqlite-node` |
| `pi-coding-agent` | `@earendil-works/pi-coding-agent` |
| `pi-server` | `@earendil-works/pi-server` |

`pi-server` is a library module. Server lifecycle and RPC behavior are exposed
through the installed `pi --mode rpc` runtime; Gradle does not produce a
separate `pi-server` executable distribution.

## Build

```bash
./gradlew build
./gradlew :pi-coding-agent:run --args="--help"
```

## OAuth login

Start the installed `pi` application in interactive mode, then authenticate
with Anthropic Claude Pro/Max, OpenRouter, OpenAI Codex, GitHub Copilot, Kimi
Code, Radius, or xAI:

```text
/login anthropic
/login openrouter
/login openai-codex
/login github-copilot
/login kimi-coding
/login radius
/login xai
```

Credentials are stored in `~/.pi/agent/auth.json` by default, or under
`PI_CODING_AGENT_DIR` when configured. Writes are atomic and the credential
file is owner-readable and owner-writable on POSIX filesystems. Use
`/logout anthropic`, `/logout openrouter`, `/logout openai-codex`,
`/logout github-copilot`, `/logout kimi-coding`, `/logout radius`, or
`/logout xai` to remove only that stored credential; environment variables are
unchanged.

The `pi-ai` module also exposes the upstream OpenRouter image-generation API,
including its 45-model catalog and shared OpenRouter API-key/OAuth
authentication. This remains a library-level API because the upstream coding
agent and server do not currently expose a separate image-generation command.

See [migration/MIGRATION.md](migration/MIGRATION.md) for migration rules,
acceptance gates, current status, and the executable completeness audit.
