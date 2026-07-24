package works.earendil.pi.codingagent

import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import works.earendil.pi.ai.Credential
import works.earendil.pi.ai.CredentialInfo
import works.earendil.pi.ai.CredentialStore
import works.earendil.pi.ai.typeName

class JsonFileCredentialStore(
    path: Path,
) : CredentialStore {
    private val path = path.toAbsolutePath().normalize()
    private val processMutex = mutexes.computeIfAbsent(this.path) { Mutex() }

    override suspend fun read(providerId: String): Credential? =
        locked {
            readCredentials()[providerId]
        }

    override suspend fun list(): List<CredentialInfo> =
        locked {
            readCredentials()
                .map { (providerId, credential) ->
                    CredentialInfo(providerId, credential.typeName())
                }.sortedBy(CredentialInfo::providerId)
        }

    override suspend fun modify(
        providerId: String,
        transform: suspend (Credential?) -> Credential?,
    ): Credential? =
        locked {
            val credentials = readCredentials().toMutableMap()
            val current = credentials[providerId]
            val next = transform(current)
            if (next == null) {
                current
            } else {
                credentials[providerId] = next
                writeCredentials(credentials)
                next
            }
        }

    override suspend fun delete(providerId: String) {
        locked {
            val credentials = readCredentials().toMutableMap()
            if (credentials.remove(providerId) != null) {
                writeCredentials(credentials)
            }
        }
    }

    private suspend fun <T> locked(block: suspend () -> T): T =
        processMutex.withLock {
            withContext(Dispatchers.IO) {
                val parent = requireNotNull(path.parent) { "Credential path must have a parent: $path" }
                ensurePrivateDirectory(parent)
                val lockPath = parent.resolve(".${path.fileName}.kotlin.lock")
                FileChannel
                    .open(
                        lockPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                    ).use { channel ->
                        setOwnerOnlyFilePermissions(lockPath)
                        channel.lock().use {
                            block()
                        }
                    }
            }
        }

    private fun readCredentials(): Map<String, Credential> {
        if (!Files.exists(path)) {
            return emptyMap()
        }
        val content = Files.readString(path, StandardCharsets.UTF_8)
        return credentialJson.decodeFromString(CREDENTIALS_SERIALIZER, content)
    }

    private fun writeCredentials(credentials: Map<String, Credential>) {
        val parent = requireNotNull(path.parent)
        val temporary = Files.createTempFile(parent, ".${path.fileName}.", ".tmp")
        try {
            Files.writeString(
                temporary,
                credentialJson.encodeToString(CREDENTIALS_SERIALIZER, credentials),
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            setOwnerOnlyFilePermissions(temporary)
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            setOwnerOnlyFilePermissions(path)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        val mutexes = ConcurrentHashMap<Path, Mutex>()
        val CREDENTIALS_SERIALIZER = MapSerializer(String.serializer(), Credential.serializer())
        val credentialJson =
            Json {
                ignoreUnknownKeys = true
                prettyPrint = true
                explicitNulls = false
            }
    }
}

private fun ensurePrivateDirectory(path: Path) {
    if (!Files.exists(path)) {
        Files.createDirectories(path)
        runCatching {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        }
    }
}

private fun setOwnerOnlyFilePermissions(path: Path) {
    runCatching {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    }
}
