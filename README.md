# pi-kotlin

Kotlin/JVM migration of the [Pi Agent Harness](https://github.com/earendil-works/pi).

The first migration pass is pinned to upstream commit
`9b3a2059171bcc74ad9d2cadeea6d186776cf2db` (July 22, 2026), and the reviewed
incremental sync currently reaches
`24bace27cf308c89707cf8005b4795d873e23f17` (July 23, 2026). The target is
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

## Build

```bash
./gradlew build
./gradlew :pi-coding-agent:run --args="--help"
```

See [migration/MIGRATION.md](migration/MIGRATION.md) for migration rules,
acceptance gates, current status, and the executable completeness audit.
