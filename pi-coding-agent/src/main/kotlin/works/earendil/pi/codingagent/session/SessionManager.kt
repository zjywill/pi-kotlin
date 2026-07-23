package works.earendil.pi.codingagent.session

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.BranchSummaryMessage
import works.earendil.pi.ai.CompactionSummaryMessage
import works.earendil.pi.ai.CustomMessage
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.contentText
import works.earendil.pi.ai.uuidv7

class SessionManager private constructor(
    cwd: Path,
    sessionDir: Path?,
    private var sessionFile: Path?,
    private val persist: Boolean,
    options: NewSessionOptions?,
    loaded: LoadedSession? = null,
) {
    private var cwd: Path = cwd.toAbsolutePath().normalize()
    private val sessionDir: Path? = sessionDir?.toAbsolutePath()?.normalize()
    private var sessionId = ""
    private var fileEntries = mutableListOf<FileEntry>()
    private val byId = linkedMapOf<String, SessionEntry>()
    private val labelsById = mutableMapOf<String, String>()
    private val labelTimestampsById = mutableMapOf<String, String>()
    private var leafId: String? = null
    private var flushed = false

    init {
        if (persist && this.sessionDir != null) {
            Files.createDirectories(this.sessionDir)
        }
        if (loaded != null && loaded.entries.isNotEmpty()) {
            fileEntries = loaded.entries
            val header = fileEntries.filterIsInstance<SessionHeader>().first()
            sessionId = header.id
            this.cwd = header.cwd.takeIf(String::isNotEmpty)?.let(Path::of)?.toAbsolutePath()?.normalize() ?: this.cwd
            buildIndex()
            flushed = sessionFile?.let(Files::exists) == true
            if (loaded.migrated) {
                rewriteFile()
            }
        } else {
            newSession(options)
        }
    }

    fun newSession(options: NewSessionOptions? = null): Path? {
        options?.id?.let(::assertValidSessionId)
        sessionId = options?.id ?: uuidv7()
        val timestamp = Instant.now().toString()
        fileEntries =
            mutableListOf(
                SessionHeader(
                    id = sessionId,
                    timestamp = timestamp,
                    cwd = cwd.toString(),
                    parentSession = options?.parentSession,
                ),
            )
        byId.clear()
        labelsById.clear()
        labelTimestampsById.clear()
        leafId = null
        flushed = false
        if (persist && sessionFile == null) {
            val filename = "${timestamp.replace(':', '-').replace('.', '-')}_${sessionId}.jsonl"
            sessionFile = requireNotNull(sessionDir).resolve(filename)
        }
        return sessionFile
    }

    fun isPersisted(): Boolean = persist

    fun getCwd(): Path = cwd

    fun getSessionDir(): Path? = sessionDir

    fun getSessionId(): String = sessionId

    fun getSessionFile(): Path? = sessionFile

    fun getHeader(): SessionHeader? = fileEntries.filterIsInstance<SessionHeader>().firstOrNull()

    fun getEntries(): List<SessionEntry> = fileEntries.filterIsInstance<SessionEntry>()

    fun getLeafId(): String? = leafId

    fun getLeafEntry(): SessionEntry? = leafId?.let(byId::get)

    fun getEntry(id: String): SessionEntry? = byId[id]

    fun getChildren(parentId: String): List<SessionEntry> = byId.values.filter { it.parentId == parentId }

    fun appendMessage(message: Message): String =
        appendEntry { id, parent, timestamp ->
            SessionMessageEntry(id, parent, timestamp, message)
        }

    fun appendThinkingLevelChange(thinkingLevel: String): String =
        appendEntry { id, parent, timestamp ->
            ThinkingLevelChangeEntry(id, parent, timestamp, thinkingLevel)
        }

    fun appendModelChange(
        provider: String,
        modelId: String,
    ): String =
        appendEntry { id, parent, timestamp ->
            ModelChangeEntry(id, parent, timestamp, provider, modelId)
        }

    fun appendCompaction(
        summary: String,
        firstKeptEntryId: String,
        tokensBefore: Int,
        details: JsonElement? = null,
        fromHook: Boolean? = null,
        usage: Usage? = null,
    ): String =
        appendEntry { id, parent, timestamp ->
            CompactionEntry(
                id,
                parent,
                timestamp,
                summary,
                firstKeptEntryId,
                tokensBefore,
                details,
                usage,
                fromHook,
            )
        }

    fun appendCustomEntry(
        customType: String,
        data: JsonElement? = null,
    ): String =
        appendEntry { id, parent, timestamp ->
            CustomEntry(id, parent, timestamp, customType, data)
        }

    fun appendCustomMessageEntry(
        customType: String,
        content: MessageContent,
        display: Boolean,
        details: JsonElement? = null,
    ): String =
        appendEntry { id, parent, timestamp ->
            CustomMessageEntry(id, parent, timestamp, customType, content, details, display)
        }

    fun appendSessionInfo(name: String): String {
        val sanitized = name.replace(Regex("[\\r\\n]+"), " ").trim()
        return appendEntry { id, parent, timestamp ->
            SessionInfoEntry(id, parent, timestamp, sanitized)
        }
    }

    fun getSessionName(): String? =
        getEntries()
            .asReversed()
            .filterIsInstance<SessionInfoEntry>()
            .firstOrNull()
            ?.name
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    fun appendLabelChange(
        targetId: String,
        label: String?,
    ): String {
        require(byId.containsKey(targetId)) { "Entry $targetId not found" }
        val normalized = label?.takeIf(String::isNotEmpty)
        val id =
            appendEntry { entryId, parent, timestamp ->
                LabelEntry(entryId, parent, timestamp, targetId, normalized)
            }
        val entry = requireNotNull(byId[id]) as LabelEntry
        if (normalized == null) {
            labelsById.remove(targetId)
            labelTimestampsById.remove(targetId)
        } else {
            labelsById[targetId] = normalized
            labelTimestampsById[targetId] = entry.timestamp
        }
        return id
    }

    fun getLabel(id: String): String? = labelsById[id]

    fun getBranch(fromId: String? = leafId): List<SessionEntry> {
        var current = fromId?.let(byId::get)
        val path = mutableListOf<SessionEntry>()
        while (current != null) {
            path += current
            current = current.parentId?.let(byId::get)
        }
        path.reverse()
        return path
    }

    fun buildContextEntries(): List<SessionEntry> = buildContextEntries(getEntries(), leafId, byId)

    fun buildSessionContext(): SessionContext = buildSessionContext(getEntries(), leafId, byId)

    fun getTree(): List<SessionTreeNode> {
        val nodes =
            getEntries().associate { entry ->
                entry.id to
                    SessionTreeNode(
                        entry = entry,
                        label = labelsById[entry.id],
                        labelTimestamp = labelTimestampsById[entry.id],
                    )
            }
        val roots = mutableListOf<SessionTreeNode>()
        for (entry in getEntries()) {
            val node = requireNotNull(nodes[entry.id])
            val parent = entry.parentId?.let(nodes::get)
            if (parent == null || entry.parentId == entry.id) {
                roots += node
            } else {
                parent.children += node
            }
        }
        val stack = ArrayDeque(roots)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            node.children.sortBy { it.entry.timestamp }
            stack.addAll(node.children)
        }
        return roots
    }

    fun branch(branchFromId: String) {
        require(byId.containsKey(branchFromId)) { "Entry $branchFromId not found" }
        leafId = branchFromId
    }

    fun resetLeaf() {
        leafId = null
    }

    fun branchWithSummary(
        branchFromId: String?,
        summary: String,
        details: JsonElement? = null,
        fromHook: Boolean? = null,
        usage: Usage? = null,
    ): String {
        require(branchFromId == null || byId.containsKey(branchFromId)) {
            "Entry $branchFromId not found"
        }
        leafId = branchFromId
        return appendEntry { id, parent, timestamp ->
            BranchSummaryEntry(
                id,
                parent,
                timestamp,
                branchFromId ?: "root",
                summary,
                details,
                usage,
                fromHook,
            )
        }
    }

    private fun appendEntry(create: (String, String?, String) -> SessionEntry): String {
        val entry = create(generateId(), leafId, Instant.now().toString())
        fileEntries += entry
        byId[entry.id] = entry
        leafId = entry.id
        persistEntry(entry)
        return entry.id
    }

    private fun persistEntry(entry: SessionEntry) {
        if (!persist || sessionFile == null) {
            return
        }
        val hasAssistant =
            fileEntries
                .filterIsInstance<SessionMessageEntry>()
                .any { it.message is AssistantMessage }
        if (!hasAssistant) {
            return
        }
        if (!flushed) {
            val content = fileEntries.joinToString(separator = "") { encodeLine(it) }
            Files.writeString(
                sessionFile,
                content,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            flushed = true
        } else {
            Files.writeString(
                sessionFile,
                encodeLine(entry),
                StandardOpenOption.APPEND,
            )
        }
    }

    private fun rewriteFile() {
        val file = sessionFile ?: return
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            fileEntries.joinToString(separator = "") { encodeLine(it) },
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        flushed = true
    }

    private fun buildIndex() {
        byId.clear()
        labelsById.clear()
        labelTimestampsById.clear()
        leafId = null
        for (entry in getEntries()) {
            byId[entry.id] = entry
            leafId = entry.id
            if (entry is LabelEntry) {
                if (entry.label == null) {
                    labelsById.remove(entry.targetId)
                    labelTimestampsById.remove(entry.targetId)
                } else {
                    labelsById[entry.targetId] = entry.label
                    labelTimestampsById[entry.targetId] = entry.timestamp
                }
            }
        }
    }

    private fun generateId(): String {
        repeat(100) {
            val id = UUID.randomUUID().toString().take(8)
            if (!byId.containsKey(id)) {
                return id
            }
        }
        return UUID.randomUUID().toString()
    }

    companion object {
        fun inMemory(
            cwd: Path = Path.of("").toAbsolutePath(),
            options: NewSessionOptions? = null,
        ): SessionManager = SessionManager(cwd, null, null, persist = false, options = options)

        fun create(
            cwd: Path,
            sessionDir: Path? = null,
            options: NewSessionOptions? = null,
        ): SessionManager {
            val directory = sessionDir ?: getDefaultSessionDir(cwd)
            return SessionManager(cwd, directory, null, persist = true, options = options)
        }

        fun open(
            path: Path,
            sessionDir: Path? = null,
            cwdOverride: Path? = null,
        ): SessionManager {
            val resolved = path.toAbsolutePath().normalize()
            val loaded = loadSession(resolved)
            if (Files.exists(resolved) && Files.size(resolved) > 0 && loaded.entries.isEmpty()) {
                error("Session file is not a valid pi session: $resolved")
            }
            val header = loaded.entries.filterIsInstance<SessionHeader>().firstOrNull()
            val cwd = cwdOverride ?: header?.cwd?.let(Path::of) ?: Path.of("").toAbsolutePath()
            val directory = sessionDir ?: resolved.parent
            val manager =
                SessionManager(
                    cwd,
                    directory,
                    resolved,
                    persist = true,
                    options = null,
                    loaded = loaded.takeIf { it.entries.isNotEmpty() },
                )
            if (Files.exists(resolved) && Files.size(resolved) == 0L) {
                manager.rewriteFile()
            }
            return manager
        }

        fun continueRecent(
            cwd: Path,
            sessionDir: Path? = null,
        ): SessionManager {
            val resolvedCwd = cwd.toAbsolutePath().normalize()
            val directory = (sessionDir ?: getDefaultSessionDir(resolvedCwd)).toAbsolutePath().normalize()
            val shouldFilterCwd = sessionDir != null && directory != getDefaultSessionDirPath(resolvedCwd)
            val mostRecent =
                sessionFiles(directory)
                    .filter { path ->
                        if (!shouldFilterCwd) {
                            true
                        } else {
                            loadSession(path)
                                .entries
                                .filterIsInstance<SessionHeader>()
                                .firstOrNull()
                                ?.cwd
                                ?.takeIf(String::isNotEmpty)
                                ?.let(Path::of)
                                ?.toAbsolutePath()
                                ?.normalize() == resolvedCwd
                        }
                    }.maxByOrNull { path ->
                        runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(Long.MIN_VALUE)
                    }
            return if (mostRecent == null) {
                create(resolvedCwd, directory)
            } else {
                open(mostRecent, directory)
            }
        }

        fun forkFrom(
            sourcePath: Path,
            targetCwd: Path,
            sessionDir: Path? = null,
            options: NewSessionOptions? = null,
        ): SessionManager {
            options?.id?.let(::assertValidSessionId)
            val resolvedSource = sourcePath.toAbsolutePath().normalize()
            val source = loadSession(resolvedSource)
            val sourceHeader = source.entries.filterIsInstance<SessionHeader>().firstOrNull()
                ?: error("Cannot fork: source session file is empty or invalid: $resolvedSource")
            val resolvedCwd = targetCwd.toAbsolutePath().normalize()
            val directory = (sessionDir ?: getDefaultSessionDir(resolvedCwd)).toAbsolutePath().normalize()
            Files.createDirectories(directory)
            val sessionId = options?.id ?: uuidv7()
            val timestamp = Instant.now()
            val newHeader =
                SessionHeader(
                    id = sessionId,
                    timestamp = timestamp.toString(),
                    cwd = resolvedCwd.toString(),
                    parentSession = resolvedSource.toString(),
                )
            val filename = "${timestamp.toString().replace(':', '-').replace('.', '-')}_${sessionId}.jsonl"
            val target = directory.resolve(filename)
            val entries =
                buildList<FileEntry> {
                    add(newHeader)
                    addAll(source.entries.filterNot { it === sourceHeader })
                }.toMutableList()
            Files.writeString(
                target,
                entries.joinToString(separator = "") { encodeLine(it) },
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            return SessionManager(
                resolvedCwd,
                directory,
                target,
                persist = true,
                options = null,
                loaded = LoadedSession(entries, source.migrated),
            )
        }

        fun list(
            cwd: Path,
            sessionDir: Path? = null,
        ): List<SessionInfo> {
            val resolvedCwd = cwd.toAbsolutePath().normalize()
            val directory = (sessionDir ?: getDefaultSessionDir(resolvedCwd)).toAbsolutePath().normalize()
            val shouldFilterCwd = sessionDir != null && directory != getDefaultSessionDirPath(resolvedCwd)
            return listFromDirectory(directory)
                .filter { info -> !shouldFilterCwd || info.cwd == resolvedCwd }
                .sortedByDescending(SessionInfo::modified)
        }

        fun listAll(sessionDir: Path? = null): List<SessionInfo> {
            if (sessionDir != null) {
                return listFromDirectory(sessionDir.toAbsolutePath().normalize())
                    .sortedByDescending(SessionInfo::modified)
            }
            val root = getSessionsRoot()
            if (!Files.isDirectory(root)) {
                return emptyList()
            }
            return runCatching {
                Files.list(root).use { paths ->
                    paths
                        .filter(Files::isDirectory)
                        .flatMap { directory -> listFromDirectory(directory).stream() }
                        .toList()
                        .sortedByDescending(SessionInfo::modified)
                }
            }.getOrDefault(emptyList())
        }

        fun getDefaultSessionDir(cwd: Path): Path {
            val directory = getDefaultSessionDirPath(cwd)
            Files.createDirectories(directory)
            return directory
        }

        private fun getDefaultSessionDirPath(cwd: Path): Path {
            val resolved = cwd.toAbsolutePath().normalize().toString()
            val safePath = "--${resolved.trimStart('/', '\\').replace(Regex("[/\\\\:]"), "-")}--"
            return getSessionsRoot().resolve(safePath).toAbsolutePath().normalize()
        }

        private fun getSessionsRoot(): Path =
            Path
                .of(System.getProperty("user.home"), ".pi", "agent", "sessions")
                .toAbsolutePath()
                .normalize()

        private fun listFromDirectory(directory: Path): List<SessionInfo> =
            sessionFiles(directory).mapNotNull(::buildSessionInfo)

        private fun sessionFiles(directory: Path): List<Path> {
            if (!Files.isDirectory(directory)) {
                return emptyList()
            }
            return runCatching {
                Files.list(directory).use { paths ->
                    paths
                        .filter(Files::isRegularFile)
                        .filter { it.fileName.toString().endsWith(".jsonl") }
                        .toList()
                }
            }.getOrDefault(emptyList())
        }

        private fun buildSessionInfo(path: Path): SessionInfo? =
            runCatching {
                val loaded = loadSession(path)
                val header = loaded.entries.filterIsInstance<SessionHeader>().firstOrNull() ?: return null
                val messages = loaded.entries.filterIsInstance<SessionMessageEntry>()
                val activity =
                    messages
                        .mapNotNull { entry ->
                            entry.message.timestamp
                                .takeIf { it > 0 }
                                ?.let(Instant::ofEpochMilli)
                                ?: runCatching { Instant.parse(entry.timestamp) }.getOrNull()
                        }.maxOrNull()
                val created =
                    runCatching { Instant.parse(header.timestamp) }.getOrElse {
                        Files.getLastModifiedTime(path).toInstant()
                    }
                val userAndAssistantText =
                    messages.mapNotNull { entry ->
                        when (val message = entry.message) {
                            is works.earendil.pi.ai.UserMessage -> contentText(message.content)
                            is AssistantMessage -> contentText(message.content)
                            else -> null
                        }?.takeIf(String::isNotEmpty)
                    }
                val firstUser =
                    messages.firstNotNullOfOrNull { entry ->
                        (entry.message as? works.earendil.pi.ai.UserMessage)
                            ?.let { contentText(it.content) }
                            ?.takeIf(String::isNotEmpty)
                    }
                SessionInfo(
                    path = path.toAbsolutePath().normalize(),
                    id = header.id,
                    cwd =
                        header.cwd
                            .takeIf(String::isNotEmpty)
                            ?.let(Path::of)
                            ?.toAbsolutePath()
                            ?.normalize(),
                    name =
                        loaded.entries
                            .filterIsInstance<SessionInfoEntry>()
                            .lastOrNull()
                            ?.name
                            ?.trim()
                            ?.takeIf(String::isNotEmpty),
                    parentSessionPath =
                        header.parentSession
                            ?.let(Path::of)
                            ?.toAbsolutePath()
                            ?.normalize(),
                    created = created,
                    modified = activity ?: created,
                    messageCount = messages.size,
                    firstMessage = firstUser ?: "(no messages)",
                    allMessagesText = userAndAssistantText.joinToString(" "),
                )
            }.getOrNull()
    }
}

fun assertValidSessionId(id: String) {
    require(Regex("^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$").matches(id)) {
        "Session id must be non-empty, contain only alphanumeric characters, '-', '_', and '.', " +
            "and start and end with an alphanumeric character"
    }
}

fun buildContextEntries(
    entries: List<SessionEntry>,
): List<SessionEntry> = buildContextEntries(entries, entries.lastOrNull()?.id)

fun buildContextEntries(
    entries: List<SessionEntry>,
    leafId: String?,
    byId: Map<String, SessionEntry>? = null,
): List<SessionEntry> {
    val path = buildSessionPath(entries, leafId, byId)
    val compaction = path.filterIsInstance<CompactionEntry>().lastOrNull() ?: return path
    val compactionIndex = path.indexOfFirst { it.id == compaction.id }
    if (compactionIndex < 0) {
        return path
    }

    val result = mutableListOf<SessionEntry>(compaction)
    var foundFirstKept = false
    for (index in 0 until compactionIndex) {
        val entry = path[index]
        if (entry.id == compaction.firstKeptEntryId) {
            foundFirstKept = true
        }
        if (foundFirstKept) {
            result += entry
        }
    }
    result += path.drop(compactionIndex + 1)
    return result
}

fun buildSessionContext(
    entries: List<SessionEntry>,
): SessionContext = buildSessionContext(entries, entries.lastOrNull()?.id)

fun buildSessionContext(
    entries: List<SessionEntry>,
    leafId: String?,
    byId: Map<String, SessionEntry>? = null,
): SessionContext {
    val path = buildSessionPath(entries, leafId, byId)
    var thinkingLevel = "off"
    var model: SessionModel? = null
    for (entry in path) {
        when (entry) {
            is ThinkingLevelChangeEntry -> thinkingLevel = entry.thinkingLevel
            is ModelChangeEntry -> model = SessionModel(entry.provider, entry.modelId)
            is SessionMessageEntry -> {
                val message = entry.message
                if (message is AssistantMessage) {
                    model = SessionModel(message.provider, message.model)
                }
            }

            else -> Unit
        }
    }

    val messages =
        buildContextEntries(entries, leafId, byId).flatMap { entry ->
            when (entry) {
                is SessionMessageEntry -> listOf(entry.message)
                is CustomMessageEntry ->
                    listOf(
                        CustomMessage(
                            customType = entry.customType,
                            content = entry.content,
                            display = entry.display,
                            details = entry.details,
                            timestamp = Instant.parse(entry.timestamp).toEpochMilli(),
                        ),
                    )

                is CompactionEntry ->
                    listOf(
                        CompactionSummaryMessage(
                            summary = entry.summary,
                            tokensBefore = entry.tokensBefore,
                            timestamp = Instant.parse(entry.timestamp).toEpochMilli(),
                        ),
                    )

                is BranchSummaryEntry ->
                    listOf(
                        BranchSummaryMessage(
                            summary = entry.summary,
                            fromId = entry.fromId,
                            timestamp = Instant.parse(entry.timestamp).toEpochMilli(),
                        ),
                    )

                else -> emptyList()
            }
        }
    return SessionContext(messages, thinkingLevel, model)
}

private fun buildSessionPath(
    entries: List<SessionEntry>,
    leafId: String?,
    suppliedIndex: Map<String, SessionEntry>?,
): List<SessionEntry> {
    val index = suppliedIndex ?: entries.associateBy(SessionEntry::id)
    if (leafId == null) {
        return emptyList()
    }
    var current =
        index[leafId] ?: entries.lastOrNull()
    val path = mutableListOf<SessionEntry>()
    while (current != null) {
        path += current
        current = current.parentId?.let(index::get)
    }
    path.reverse()
    return path
}
