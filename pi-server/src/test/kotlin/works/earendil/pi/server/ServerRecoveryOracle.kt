package works.earendil.pi.server

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val OLD_TIMESTAMP = "2026-01-01T00:00:00.000Z"

fun main() =
    runBlocking {
        val root = Files.createTempDirectory("pi-server-recovery-oracle")
        val storage = ServerStorage(ServerConfig(root.resolve("server")))
        storage.saveInstances(
            InstanceStatus.entries.map { status ->
                val value = status.wireValue
                InstanceRecord(
                    id = value,
                    status = status,
                    cwd = "/fixture/$value",
                    createdAt = OLD_TIMESTAMP,
                    lastSeenAt = OLD_TIMESTAMP,
                    label = "label-$value",
                    sessionId = "session-$value",
                    sessionFile = "/sessions/$value.jsonl",
                )
            },
        )
        val supervisor =
            ServerSupervisor(
                storage,
                RpcProcessFactory { _, _, _ -> error("Recovery must not start RPC processes") },
            )
        supervisor.recoverAfterRestart()
        val output =
            buildJsonObject {
                put(
                    "records",
                    JsonArray(
                        storage.loadInstances().map { record ->
                            buildJsonObject {
                                put("id", record.id)
                                put("status", record.status.wireValue)
                                put("cwd", record.cwd)
                                put("createdAt", record.createdAt)
                                put("lastSeenUpdated", record.lastSeenAt != OLD_TIMESTAMP)
                                record.label?.let { put("label", it) }
                                record.sessionId?.let { put("sessionId", it) }
                                record.sessionFile?.let { put("sessionFile", it) }
                            }
                        },
                    ),
                )
            }
        println(serverJson.encodeToString(output))
    }
