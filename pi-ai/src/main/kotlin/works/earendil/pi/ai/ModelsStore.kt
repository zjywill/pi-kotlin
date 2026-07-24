package works.earendil.pi.ai

import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
data class ModelsStoreEntry(
    val models: List<Model>,
    val lastModified: Long? = null,
    val checkedAt: Long? = null,
)

interface ModelsStore {
    suspend fun read(providerId: String): ModelsStoreEntry?

    suspend fun write(
        providerId: String,
        entry: ModelsStoreEntry,
    )

    suspend fun delete(providerId: String)

    suspend fun readAll(providerIds: Collection<String>): Map<String, ModelsStoreEntry> =
        providerIds
            .mapNotNull { providerId ->
                read(providerId)?.let { providerId to it }
            }.toMap()

    suspend fun applyChanges(
        writes: Map<String, ModelsStoreEntry>,
        deletes: Set<String>,
    ) {
        deletes.forEach { providerId -> delete(providerId) }
        writes.forEach { (providerId, entry) -> write(providerId, entry) }
    }
}

interface ProviderModelsStore {
    suspend fun read(): ModelsStoreEntry?

    suspend fun write(entry: ModelsStoreEntry)

    suspend fun delete()
}

class InMemoryModelsStore : ModelsStore {
    private val entries = ConcurrentHashMap<String, ModelsStoreEntry>()

    override suspend fun read(providerId: String): ModelsStoreEntry? = entries[providerId]?.copyForRead()

    override suspend fun write(
        providerId: String,
        entry: ModelsStoreEntry,
    ) {
        entries[providerId] = entry.copyForRead()
    }

    override suspend fun delete(providerId: String) {
        entries.remove(providerId)
    }

    override suspend fun readAll(providerIds: Collection<String>): Map<String, ModelsStoreEntry> =
        providerIds
            .mapNotNull { providerId ->
                entries[providerId]?.copyForRead()?.let { providerId to it }
            }.toMap()

    override suspend fun applyChanges(
        writes: Map<String, ModelsStoreEntry>,
        deletes: Set<String>,
    ) {
        deletes.forEach(entries::remove)
        writes.forEach { (providerId, entry) ->
            entries[providerId] = entry.copyForRead()
        }
    }
}

class JsonFileModelsStore(
    path: Path,
) : ModelsStore {
    private val path = path.toAbsolutePath()
    private val mutex = Mutex()

    override suspend fun read(providerId: String): ModelsStoreEntry? =
        locked {
            readEntries()[providerId]?.copyForRead()
        }

    override suspend fun write(
        providerId: String,
        entry: ModelsStoreEntry,
    ) {
        locked {
            val entries = readEntries().toMutableMap()
            entries[providerId] = entry.copyForRead()
            writeEntries(entries)
        }
    }

    override suspend fun delete(providerId: String) {
        locked {
            val entries = readEntries().toMutableMap()
            entries.remove(providerId)
            writeEntries(entries)
        }
    }

    override suspend fun readAll(providerIds: Collection<String>): Map<String, ModelsStoreEntry> =
        locked {
            val requested = providerIds.toSet()
            readEntries()
                .filterKeys(requested::contains)
                .mapValues { (_, entry) -> entry.copyForRead() }
        }

    override suspend fun applyChanges(
        writes: Map<String, ModelsStoreEntry>,
        deletes: Set<String>,
    ) {
        if (writes.isEmpty() && deletes.isEmpty()) {
            return
        }
        locked {
            val entries = readEntries().toMutableMap()
            deletes.forEach(entries::remove)
            writes.forEach { (providerId, entry) ->
                entries[providerId] = entry.copyForRead()
            }
            writeEntries(entries)
        }
    }

    private suspend fun <T> locked(block: () -> T): T =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val absolutePath = path.toAbsolutePath()
                val parent = absolutePath.parent
                Files.createDirectories(parent)
                val lockPath = parent.resolve("${absolutePath.fileName}.lock")
                FileChannel
                    .open(
                        lockPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                    ).use { channel ->
                        channel.lock().use {
                            block()
                        }
                    }
            }
        }

    private fun readEntries(): Map<String, ModelsStoreEntry> {
        if (!Files.exists(path)) {
            return emptyMap()
        }
        val content = Files.readString(path, StandardCharsets.UTF_8)
        if (content.isBlank()) {
            return emptyMap()
        }
        return storeJson.decodeFromString(STORED_MODELS_SERIALIZER, content)
    }

    private fun writeEntries(entries: Map<String, ModelsStoreEntry>) {
        val absolutePath = path.toAbsolutePath()
        val parent = absolutePath.parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${absolutePath.fileName}.", ".tmp")
        try {
            Files.writeString(
                temporary,
                storeJson.encodeToString(STORED_MODELS_SERIALIZER, entries),
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            try {
                Files.move(
                    temporary,
                    absolutePath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, absolutePath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        val STORED_MODELS_SERIALIZER = MapSerializer(String.serializer(), ModelsStoreEntry.serializer())
        val storeJson =
            Json {
                ignoreUnknownKeys = true
                prettyPrint = true
                explicitNulls = false
            }
    }
}

private fun ModelsStoreEntry.copyForRead(): ModelsStoreEntry = copy(models = models.toList())
