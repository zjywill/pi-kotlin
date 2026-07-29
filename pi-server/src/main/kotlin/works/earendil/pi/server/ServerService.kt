package works.earendil.pi.server

import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class ServerService(
    private val supervisor: ServerSupervisor,
) {
    suspend fun handle(request: JsonObject): JsonObject =
        try {
            when (request.string("type")) {
                "spawn" -> {
                    val cwd = request.string("cwd")?.let(Path::of) ?: Path.of("").toAbsolutePath()
                    val instance =
                        supervisor.spawnInstance(
                            cwd = cwd,
                            label = request.string("label"),
                            provider = request.string("provider"),
                            model = request.string("model"),
                        )
                    buildJsonObject {
                        put("type", "spawn_result")
                        put("ok", true)
                        put("instance", instanceSummary(instance))
                    }
                }

                "list" -> listResponse(supervisor.listInstances())
                "status" -> {
                    val id = request.instanceId()
                    val instance = supervisor.getInstance(id)
                        ?: return errorResponse("Unknown instance: $id")
                    buildJsonObject {
                        put("type", "status_result")
                        put("ok", true)
                        put("instance", instanceSummary(instance))
                    }
                }

                "stop" -> {
                    val id = request.instanceId()
                    supervisor.stopInstance(id)
                        ?: return errorResponse("Unknown instance: $id")
                    buildJsonObject {
                        put("type", "stop_result")
                        put("ok", true)
                        put("instanceId", id)
                    }
                }

                "rpc" -> {
                    val id = request.instanceId()
                    val command = request["command"]?.jsonObject ?: error("command is required")
                    val response =
                        supervisor.handleRpc(id, command)
                            ?: return errorResponse("Unknown instance: $id")
                    buildJsonObject {
                        put("type", "rpc_result")
                        put("ok", true)
                        put("response", response)
                    }
                }

                "rpc_stream" -> {
                    val id = request.instanceId()
                    val instance = supervisor.getInstance(id)
                        ?: return errorResponse("Unknown instance: $id")
                    buildJsonObject {
                        put("type", "rpc_ready")
                        put("ok", true)
                        put("instance", instanceSummary(instance))
                    }
                }

                else -> errorResponse("Unknown request type: ${request.string("type")}")
            }
        } catch (error: Exception) {
            errorResponse(error.message ?: error::class.simpleName.orEmpty())
        }

    fun subscribe(
        instanceId: String,
        listener: (JsonObject) -> Unit,
    ): (() -> Unit)? = supervisor.subscribe(instanceId, listener)

    suspend fun handleStreamCommand(
        instanceId: String,
        command: JsonObject,
    ): JsonObject? =
        if (command.string("type") == "extension_ui_response") {
            supervisor.handleUiResponse(instanceId, command)
            null
        } else {
            supervisor.handleRpc(instanceId, command)
        }
}
