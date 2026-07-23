package works.earendil.pi.server

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ServerStorage(
    private val config: ServerConfig,
) {
    @Synchronized
    fun loadInstances(): List<InstanceRecord> {
        if (!Files.exists(config.instancesPath)) {
            return emptyList()
        }
        return runCatching {
            serverJson
                .parseToJsonElement(Files.readString(config.instancesPath))
                .jsonArray
                .mapNotNull(::decodeInstance)
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun saveInstances(instances: List<InstanceRecord>) {
        Files.createDirectories(config.serverDir)
        val temporary = config.instancesPath.resolveSibling("${config.instancesPath.fileName}.tmp")
        Files.writeString(
            temporary,
            JsonArray(instances.map(InstanceRecord::toJson)).toString(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        Files.move(
            temporary,
            config.instancesPath,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    @Synchronized
    fun getInstance(id: String): InstanceRecord? = loadInstances().firstOrNull { it.id == id }

    @Synchronized
    fun upsertInstance(record: InstanceRecord) {
        val instances = loadInstances().toMutableList()
        val index = instances.indexOfFirst { it.id == record.id }
        if (index < 0) {
            instances += record
        } else {
            instances[index] = record
        }
        saveInstances(instances)
    }

    @Synchronized
    fun removeInstance(id: String) {
        saveInstances(loadInstances().filterNot { it.id == id })
    }

    private fun decodeInstance(element: kotlinx.serialization.json.JsonElement): InstanceRecord? {
        val value = element as? JsonObject ?: return null
        val id = value.string("id") ?: return null
        val status =
            InstanceStatus.entries.firstOrNull { it.wireValue == value.string("status") }
                ?: return null
        val cwd = value.string("cwd") ?: return null
        val createdAt = value.string("createdAt") ?: return null
        return InstanceRecord(
            id = id,
            status = status,
            cwd = cwd,
            createdAt = createdAt,
            lastSeenAt = value.string("lastSeenAt"),
            label = value.string("label"),
            sessionId = value.string("sessionId"),
            sessionFile = value.string("sessionFile"),
        )
    }
}
