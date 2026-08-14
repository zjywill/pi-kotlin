package works.earendil.pi.agent.harness.session

import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import works.earendil.pi.ai.uuidv7

class JsonlDurableSessionMetadata(
    override val id: String,
    override val createdAt: Long,
    override val parentSessionId: String? = null,
    val cwd: String,
    val path: Path,
    val modifiedAt: Long,
    val sourceFormat: Int = 4,
    val legacyParentSessionPath: String? = null,
    val metadata: JsonObject? = null,
) : DurableSessionMetadata(id, createdAt, parentSessionId)

data class JsonlDurableSessionCreateOptions(
    override val id: String? = null,
    override val parentSessionId: String? = null,
    val cwd: Path,
    val metadata: JsonObject? = null,
) : DurableSessionCreateOptions

data class JsonlDurableSessionListOptions(
    val cwd: Path? = null,
)

class JsonlDurableSessionStorage private constructor(
    private val metadata: JsonlDurableSessionMetadata,
    private val currentTimeMillis: () -> Long,
    private val state: DurableSessionState,
) : DurableSessionStorage<JsonlDurableSessionMetadata> {
    private val mutex = Mutex()

    override suspend fun getMetadata(): JsonlDurableSessionMetadata =
        JsonlDurableSessionMetadata(
            id = metadata.id,
            createdAt = metadata.createdAt,
            parentSessionId = metadata.parentSessionId,
            cwd = metadata.cwd,
            path = metadata.path,
            modifiedAt = metadata.modifiedAt,
            sourceFormat = metadata.sourceFormat,
            legacyParentSessionPath = metadata.legacyParentSessionPath,
            metadata = metadata.metadata,
        )

    override suspend fun getLanes(): List<LanePointer> = mutex.withLock { state.getLanes() }

    override suspend fun createLane(
        lane: String,
        at: String?,
    ) {
        mutex.withLock {
            state.validateNewLane(lane)
            state.validateTarget(at)
            appendAndApply(DurableMutation.Lane(state.nextSequence, lane, at))
        }
    }

    override suspend fun moveLane(
        lane: String,
        to: String?,
    ) {
        mutex.withLock {
            state.requireLane(lane)
            state.validateTarget(to)
            appendAndApply(DurableMutation.Lane(state.nextSequence, lane, to))
        }
    }

    override suspend fun appendEntry(
        entry: ProvisionedEntry,
        lane: String,
    ): DurableEntry =
        mutex.withLock {
            val validated = entry.deepCopy()
            val parentId = state.requireLane(lane)
            state.validateUnusedId(validated.id)
            val materialized =
                DurableEntry(
                    id = validated.id,
                    seq = state.nextSequence,
                    parentId = parentId,
                    timestamp = currentTimeMillis(),
                    payload = validated.payload,
                )
            appendAndApply(DurableMutation.Entry(lane, materialized))
            materialized.deepCopy()
        }

    override suspend fun appendRecord(record: NewDurableRecord): DurableRecord =
        mutex.withLock {
            val validated =
                durableSessionJson.decodeFromString(
                    NewDurableRecord.serializer(),
                    durableSessionJson.encodeToString(NewDurableRecord.serializer(), record),
                )
            state.requireLane(validated.lane)
            state.validateUnusedId(validated.id)
            if (
                validated.payload is RecordPayload.OperationStarted &&
                state.findOpenOperations(validated.lane, 1).isNotEmpty()
            ) {
                val operation = state.findOpenOperations(validated.lane, 1).single()
                throw DurableSessionException(
                    DurableSessionErrorCode.STORAGE,
                    "Lane ${validated.lane} already has an open operation ${operation.id}",
                )
            }
            val materialized =
                DurableRecord(
                    id = validated.id,
                    seq = state.nextSequence,
                    lane = validated.lane,
                    timestamp = currentTimeMillis(),
                    payload = validated.payload,
                )
            appendAndApply(DurableMutation.Record(materialized))
            materialized.deepCopy()
        }

    override suspend fun getEntry(id: String): DurableEntry? = mutex.withLock { state.getEntry(id) }

    override suspend fun findEntries(query: EntryQuery): List<DurableEntry> =
        mutex.withLock { state.findEntries(query) }

    override suspend fun findEntriesOnBranch(
        query: EntryQuery,
        bounds: BranchBounds,
        start: String,
    ): List<DurableEntry> =
        mutex.withLock {
            state.findEntriesOnBranch(query, bounds, start)
        }

    override suspend fun findRecords(query: RecordQuery): List<DurableRecord> =
        mutex.withLock { state.findRecords(query) }

    override suspend fun findOpenOperations(
        lane: String,
        limit: Int?,
    ): List<DurableRecord> =
        mutex.withLock {
            state.findOpenOperations(lane, limit)
        }

    override suspend fun getLog(options: LogOptions): List<DurableLogItem> =
        mutex.withLock { state.getLog(options) }

    override suspend fun getName(): String? = mutex.withLock { state.getName() }

    override suspend fun setName(name: String?) {
        mutex.withLock {
            appendAndApply(DurableMutation.Name(state.nextSequence, name))
        }
    }

    override suspend fun getLabel(id: String): String? = mutex.withLock { state.getLabel(id) }

    override suspend fun setLabel(
        id: String,
        label: String?,
    ) {
        mutex.withLock {
            state.validateTarget(id)
            appendAndApply(DurableMutation.Label(state.nextSequence, id, label))
        }
    }

    override suspend fun getStats(): DurableSessionStats = mutex.withLock { state.getStats() }

    internal suspend fun appendCopiedEntry(source: DurableEntry): DurableEntry =
        mutex.withLock {
            state.validateUnusedId(source.id)
            state.validateTarget(source.parentId)
            val entry = source.deepCopy().copy(seq = state.nextSequence)
            appendAndApply(DurableMutation.Entry(null, entry))
            entry.deepCopy()
        }

    internal suspend fun appendForkLane(
        lane: String,
        leafId: String?,
    ) {
        mutex.withLock {
            state.validateTarget(leafId)
            appendAndApply(DurableMutation.Lane(state.nextSequence, lane, leafId))
        }
    }

    private fun appendAndApply(mutation: DurableMutation) {
        Files.writeString(
            metadata.path,
            encodeJsonlMutation(mutation),
            StandardCharsets.UTF_8,
            StandardOpenOption.APPEND,
        )
        state.applyMutation(mutation)
    }

    companion object {
        fun empty(
            metadata: JsonlDurableSessionMetadata,
            currentTimeMillis: () -> Long = System::currentTimeMillis,
        ): JsonlDurableSessionStorage =
            JsonlDurableSessionStorage(
                metadata = metadata,
                currentTimeMillis = currentTimeMillis,
                state = DurableSessionState(),
            )

        fun load(
            path: Path,
            currentTimeMillis: () -> Long = System::currentTimeMillis,
        ): JsonlDurableSessionStorage {
            val content =
                try {
                    Files.readString(path)
                } catch (error: Throwable) {
                    throw DurableSessionException(
                        DurableSessionErrorCode.STORAGE,
                        "Failed to read session $path",
                        error,
                    )
                }
            val lines = content.lineSequence().toMutableList()
            if (lines.isEmpty() || lines.first().isEmpty()) {
                throw JsonlInvalidFileException(path, 1, "is missing a header")
            }
            val header = parseJsonlHeader(lines.first(), path)
            val state = DurableSessionState()
            for (index in 1 until lines.size) {
                val lineNumber = index + 1
                val mutation =
                    try {
                        parseJsonlMutation(lines[index], path, lineNumber)
                    } catch (error: JsonlInvalidFileException) {
                        val isFinalLine = index == lines.lastIndex
                        if (!isFinalLine || error.cause == null) {
                            throw error
                        }
                        val prefix = lines.take(index).joinToString("\n", postfix = "\n")
                        Files.writeString(
                            path,
                            prefix,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.TRUNCATE_EXISTING,
                        )
                        return fromHeader(path, header, state, currentTimeMillis)
                    }
                try {
                    state.applyMutation(mutation) { message ->
                        throw JsonlInvalidFileException(path, lineNumber, message)
                    }
                } catch (error: JsonlInvalidFileException) {
                    throw error
                } catch (error: DurableSessionException) {
                    throw JsonlInvalidFileException(
                        path,
                        lineNumber,
                        error.message ?: "contains an invalid mutation",
                    )
                }
            }
            if (!content.endsWith("\n")) {
                Files.writeString(
                    path,
                    "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND,
                )
            }
            return fromHeader(path, header, state, currentTimeMillis)
        }

        private fun fromHeader(
            path: Path,
            header: JsonlV4Header,
            state: DurableSessionState,
            currentTimeMillis: () -> Long,
        ): JsonlDurableSessionStorage =
            JsonlDurableSessionStorage(
                metadata =
                    JsonlDurableSessionMetadata(
                        id = header.id,
                        createdAt = header.createdAt,
                        parentSessionId = header.parentSessionId,
                        cwd = header.cwd,
                        path = path,
                        modifiedAt = Files.getLastModifiedTime(path).toMillis(),
                        legacyParentSessionPath = header.legacyParentSessionPath,
                        metadata = header.metadata,
                    ),
                currentTimeMillis = currentTimeMillis,
                state = state,
            )
    }
}

class JsonlDurableSessionRepository(
    sessionsRoot: Path,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = ::uuidv7,
) : DurableSessionRepository<
        JsonlDurableSessionMetadata,
        JsonlDurableSessionCreateOptions,
        JsonlDurableSessionListOptions,
    > {
    private val root = sessionsRoot.toAbsolutePath().normalize()

    override suspend fun create(
        options: JsonlDurableSessionCreateOptions,
    ): DurableSession<JsonlDurableSessionMetadata> = createDirect(options).first

    override suspend fun open(
        metadata: JsonlDurableSessionMetadata,
    ): DurableSession<JsonlDurableSessionMetadata> {
        if (!Files.exists(metadata.path)) {
            throw DurableSessionException(
                DurableSessionErrorCode.NOT_FOUND,
                "Session not found: ${metadata.id}",
            )
        }
        val storage = JsonlDurableSessionStorage.load(metadata.path, currentTimeMillis)
        val loaded = storage.getMetadata()
        if (loaded.id != metadata.id) {
            throw DurableSessionException(
                DurableSessionErrorCode.INVALID_ENTRY,
                "Session id does not match header: ${metadata.id}",
            )
        }
        return DurableSession(storage, idGenerator)
    }

    override suspend fun list(options: JsonlDurableSessionListOptions?): List<JsonlDurableSessionMetadata> {
        val directories = sessionDirectories(options?.cwd)
        return directories
            .flatMap { directory ->
                listChildren(directory)
                    .filter { path -> !path.isDirectory() && path.fileName.toString().endsWith(".jsonl") }
                    .map { path ->
                        val firstLine = Files.newBufferedReader(path).use { it.readLine() }
                            ?: throw JsonlInvalidFileException(path, 1, "is missing a header")
                        metadataFromHeader(
                            parseJsonlHeader(firstLine, path),
                            path,
                        )
                    }
            }.sortedByDescending(JsonlDurableSessionMetadata::modifiedAt)
    }

    override suspend fun delete(metadata: JsonlDurableSessionMetadata) {
        Files.deleteIfExists(metadata.path)
    }

    override suspend fun fork(
        source: JsonlDurableSessionMetadata,
        options: DurableForkOptions,
    ): DurableSession<JsonlDurableSessionMetadata> {
        val sourceSession = open(source)
        val copiedEntries: List<DurableEntry>
        val forkLanes: List<LanePointer>
        when (options) {
            is DurableForkOptions.Tree -> {
                copiedEntries = sourceSession.findEntries(EntryQuery(order = EntryOrder.OLDEST_FIRST))
                forkLanes = sourceSession.getLanes()
            }

            is DurableForkOptions.Branch -> {
                val selectedEntryId = options.entryId ?: sourceSession.getLeafId()
                val targetId =
                    if (selectedEntryId == null) {
                        null
                    } else {
                        val entry = sourceSession.getEntry(selectedEntryId)
                        if (entry?.payload !is EntryPayload.MessageValue) {
                            throw DurableSessionException(
                                DurableSessionErrorCode.INVALID_FORK_TARGET,
                                "Fork target is not a message entry: $selectedEntryId",
                            )
                        }
                        val position =
                            options.position
                                ?: if (options.entryId == null) {
                                    DurableForkOptions.Branch.Position.AT
                                } else {
                                    DurableForkOptions.Branch.Position.BEFORE
                                }
                        if (position == DurableForkOptions.Branch.Position.AT) {
                            entry.id
                        } else {
                            entry.parentId
                        }
                    }
                copiedEntries =
                    if (targetId == null) {
                        emptyList()
                    } else {
                        sourceSession.findEntriesOnBranch(
                            query = EntryQuery(order = EntryOrder.OLDEST_FIRST),
                            bounds = BranchBounds(start = targetId),
                        )
                    }
                forkLanes = listOf(LanePointer("main", targetId))
            }
        }

        val (target, storage) =
            createDirect(
                JsonlDurableSessionCreateOptions(
                    id = options.id,
                    parentSessionId = options.parentSessionId ?: source.id,
                    cwd = Path.of(source.cwd),
                    metadata = source.metadata,
                ),
            )
        copiedEntries.forEach { storage.appendCopiedEntry(it) }
        forkLanes.forEach { storage.appendForkLane(it.lane, it.leafId) }
        sourceSession.getName()?.let { target.setName(it) }
        copiedEntries.forEach { entry ->
            sourceSession.getLabel(entry.id)?.let { target.setLabel(entry.id, it) }
        }
        return target
    }

    private fun createDirect(
        options: JsonlDurableSessionCreateOptions,
    ): Pair<DurableSession<JsonlDurableSessionMetadata>, JsonlDurableSessionStorage> {
        val id = options.id ?: idGenerator()
        validateSessionId(id)
        if (options.id != null && sessionIdExists(id)) {
            throw DurableSessionException(
                DurableSessionErrorCode.ALREADY_EXISTS,
                "Session already exists: $id",
            )
        }
        val cwd = options.cwd.toAbsolutePath().normalize()
        val createdAt = currentTimeMillis()
        val directory = sessionDirectory(cwd)
        val path = directory.resolve(sessionFileName(createdAt, id))
        Files.createDirectories(directory)
        val header =
            JsonlV4Header(
                id = id,
                createdAt = createdAt,
                cwd = cwd.toString(),
                parentSessionId = options.parentSessionId,
                metadata = options.metadata,
            )
        try {
            Files.writeString(
                path,
                encodeJsonlHeader(header),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
            )
        } catch (error: FileAlreadyExistsException) {
            throw DurableSessionException(
                DurableSessionErrorCode.ALREADY_EXISTS,
                "Session already exists: $id",
                error,
            )
        }
        val metadata = metadataFromHeader(header, path)
        val storage = JsonlDurableSessionStorage.empty(metadata, currentTimeMillis)
        return DurableSession(storage, idGenerator) to storage
    }

    private fun metadataFromHeader(
        header: JsonlV4Header,
        path: Path,
    ): JsonlDurableSessionMetadata =
        JsonlDurableSessionMetadata(
            id = header.id,
            createdAt = header.createdAt,
            parentSessionId = header.parentSessionId,
            cwd = header.cwd,
            path = path,
            modifiedAt = Files.getLastModifiedTime(path).toMillis(),
            legacyParentSessionPath = header.legacyParentSessionPath,
            metadata = header.metadata,
        )

    private fun sessionIdExists(id: String): Boolean {
        val suffix = "_$id.jsonl"
        return sessionDirectories(null).any { directory ->
            listChildren(directory).any { path ->
                !path.isDirectory() && path.fileName.toString().endsWith(suffix)
            }
        }
    }

    private fun sessionDirectories(cwd: Path?): List<Path> {
        if (cwd != null) {
            val directory = sessionDirectory(cwd.toAbsolutePath().normalize())
            return if (Files.exists(directory)) listOf(directory) else emptyList()
        }
        if (!Files.exists(root)) {
            return emptyList()
        }
        return listChildren(root).filter { it.isDirectory() || it.isSymbolicLink() }
    }

    private fun sessionDirectory(cwd: Path): Path = root.resolve(sessionDirectoryName(cwd.toString()))

    private fun listChildren(directory: Path): List<Path> =
        Files.list(directory).use { stream -> stream.toList() }
}

private val SESSION_ID_PATTERN =
    Regex("^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$")

private val SESSION_TIMESTAMP_FORMATTER =
    DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH-mm-ss-SSS'Z'")
        .withZone(ZoneOffset.UTC)

private fun validateSessionId(id: String) {
    if (!SESSION_ID_PATTERN.matches(id)) {
        throw DurableSessionException(
            DurableSessionErrorCode.INVALID_PAYLOAD,
            "Session id must be non-empty, contain only alphanumeric characters, '-', '_', and '.', " +
                "and start and end with an alphanumeric character",
        )
    }
}

private fun sessionDirectoryName(cwd: String): String =
    "--" +
        cwd
            .replace(Regex("^[/\\\\]"), "")
            .replace(Regex("[/\\\\:]"), "-") +
        "--"

private fun sessionFileName(
    createdAt: Long,
    id: String,
): String = "${SESSION_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(createdAt))}_$id.jsonl"
