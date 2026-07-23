package works.earendil.pi.storage.sqlite

import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import works.earendil.pi.agent.session.SessionMetadata

data class SqliteSessionMetadata(
    override val id: String,
    override val createdAt: String,
    val cwd: Path,
    val path: Path,
    val parentSessionId: String? = null,
    val metadata: JsonObject? = null,
) : SessionMetadata(id, createdAt)

data class SqliteSessionCreateOptions(
    val cwd: Path,
    val id: String? = null,
    val parentSessionId: String? = null,
    val metadata: JsonObject? = null,
)

data class SqliteSessionListOptions(
    val cwd: Path? = null,
)
