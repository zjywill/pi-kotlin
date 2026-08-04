package works.earendil.pi.codingagent.session

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.ModelCost
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionManagerTest {
    @Test
    fun `append creates parent chain and branch tree`() {
        val session = SessionManager.inMemory()
        val first = session.appendMessage(UserMessage("first", 1))
        val second = session.appendMessage(assistant("second", 2))
        val third = session.appendMessage(UserMessage("third", 3))

        session.branch(second)
        val branch = session.appendMessage(UserMessage("branch", 4))

        assertEquals(listOf(first, second, branch), session.getBranch().map(SessionEntry::id))
        val secondNode = session.getTree().single().children.single()
        assertEquals(setOf(third, branch), secondNode.children.map { it.entry.id }.toSet())
    }

    @Test
    fun `build context follows compaction and tracks settings`() {
        val session = SessionManager.inMemory()
        session.appendMessage(UserMessage("first", 1))
        session.appendThinkingLevelChange("high")
        session.appendMessage(assistant("response1", 2))
        val kept = session.appendMessage(UserMessage("second", 3))
        session.appendMessage(assistant("response2", 4))
        session.appendCompaction("summary", kept, 1000)
        session.appendMessage(UserMessage("third", 5))

        val context = session.buildSessionContext()

        assertEquals("high", context.thinkingLevel)
        assertEquals(4, context.messages.size)
        assertEquals("summary", (context.messages.first() as works.earendil.pi.ai.CompactionSummaryMessage).summary)
        assertEquals("anthropic", context.model?.provider)
    }

    @Test
    fun `persisted session is created after first assistant and opens again`() {
        val directory = Files.createTempDirectory("pi-kotlin-session")
        val session =
            SessionManager.create(
                cwd = directory,
                sessionDir = directory,
                options = NewSessionOptions(id = "created-session-id"),
            )
        val file = assertNotNull(session.getSessionFile())
        val userEntryId = session.appendMessage(UserMessage("hello", 1))
        assertEquals(userEntryId, session.getLeafId())
        assertFalse(Files.exists(file))
        session.appendMessage(assistant("hi", 2))
        assertEquals(userEntryId, session.getEntries()[1].parentId)
        assertTrue(Files.exists(file))

        val reopened = SessionManager.open(file, directory)

        assertEquals("created-session-id", reopened.getSessionId())
        assertEquals(2, reopened.buildSessionContext().messages.size)
        assertEquals("hello", (reopened.buildSessionContext().messages.first() as UserMessage).content
            .let { works.earendil.pi.ai.contentText(it) })
    }

    @Test
    fun `custom entries stay in tree but not model context`() {
        val session = SessionManager.inMemory()
        val message = session.appendMessage(UserMessage("hello"))
        val custom = session.appendCustomEntry("state", JsonPrimitive("value"))
        session.appendMessage(assistant("hi", 2))

        assertEquals(message, session.getEntry(custom)?.parentId)
        assertEquals(2, session.buildSessionContext().messages.size)
    }

    @Test
    fun `reset leaf produces an empty context while omitted leaf uses latest entry`() {
        val session = SessionManager.inMemory()
        session.appendMessage(UserMessage("hello", 1))
        session.appendMessage(assistant("hi", 2))
        val entries = session.getEntries()

        assertEquals(2, buildSessionContext(entries).messages.size)

        session.resetLeaf()

        assertTrue(session.buildSessionContext().messages.isEmpty())
        assertEquals("off", session.buildSessionContext().thinkingLevel)
        assertEquals(null, session.buildSessionContext().model)
    }

    @Test
    fun `list and continue recent scope a flat directory by cwd`() {
        val directory = Files.createTempDirectory("pi-kotlin-session-list")
        val projectA = Files.createDirectories(directory.resolve("project-a"))
        val projectB = Files.createDirectories(directory.resolve("project-b"))
        val sessionA =
            SessionManager.create(projectA, directory, NewSessionOptions(id = "session-a")).also {
                it.appendMessage(UserMessage("from A", 1))
                it.appendMessage(assistant("reply A", 2))
            }
        val sessionB =
            SessionManager.create(projectB, directory, NewSessionOptions(id = "session-b")).also {
                it.appendMessage(UserMessage("from B", 3))
                it.appendMessage(assistant("reply B", 4))
            }
        Files.setLastModifiedTime(
            assertNotNull(sessionA.getSessionFile()),
            FileTime.from(Instant.parse("2026-01-01T00:00:00Z")),
        )
        Files.setLastModifiedTime(
            assertNotNull(sessionB.getSessionFile()),
            FileTime.from(Instant.parse("2026-01-02T00:00:00Z")),
        )

        val currentA = SessionManager.list(projectA, directory)
        val all = SessionManager.listAll(directory)
        val continuedA = SessionManager.continueRecent(projectA, directory)

        assertEquals(listOf("session-a"), currentA.map(SessionInfo::id))
        assertEquals(setOf("session-a", "session-b"), all.map(SessionInfo::id).toSet())
        assertEquals(sessionA.getSessionFile(), continuedA.getSessionFile())
    }

    @Test
    fun `list all discovers sessions through a symlinked directory`() {
        val root = Files.createTempDirectory("pi-kotlin-session-symlink")
        val originalHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", root.resolve("home").toString())
            val target = Files.createDirectories(root.resolve("linked-sessions"))
            val project = Files.createDirectories(root.resolve("project"))
            val session =
                SessionManager.create(project, target, NewSessionOptions(id = "linked")).also {
                    it.appendMessage(UserMessage("linked", 1))
                    it.appendMessage(assistant("found", 2))
                }
            val sessionsRoot =
                Files.createDirectories(
                    Path.of(System.getProperty("user.home"), ".pi", "agent", "sessions"),
                )
            val alias = sessionsRoot.resolve("--linked--")
            Files.createSymbolicLink(alias, target)

            val discovered = SessionManager.listAll()

            assertEquals(listOf("linked"), discovered.map(SessionInfo::id))
            assertEquals(alias.resolve(assertNotNull(session.getSessionFile()).fileName), discovered.single().path)
        } finally {
            System.setProperty("user.home", originalHome)
        }
    }

    @Test
    fun `fork copies history and records source parent`() {
        val directory = Files.createTempDirectory("pi-kotlin-session-fork")
        val sourceCwd = Files.createDirectories(directory.resolve("source-project"))
        val targetCwd = Files.createDirectories(directory.resolve("target-project"))
        val source =
            SessionManager.create(sourceCwd, directory, NewSessionOptions(id = "source-id")).also {
                it.appendMessage(UserMessage("hello", 1))
                it.appendMessage(assistant("hi", 2))
            }
        val sourceFile = assertNotNull(source.getSessionFile())

        val forked =
            SessionManager.forkFrom(
                sourceFile,
                targetCwd,
                directory,
                NewSessionOptions(id = "forked-id"),
            )

        assertEquals("forked-id", forked.getSessionId())
        assertEquals(targetCwd.toAbsolutePath().normalize(), forked.getCwd())
        assertEquals(sourceFile.toAbsolutePath().normalize().toString(), forked.getHeader()?.parentSession)
        assertEquals(2, forked.buildSessionContext().messages.size)
        assertTrue(Files.exists(assertNotNull(forked.getSessionFile())))
    }

    private fun assistant(
        text: String,
        timestamp: Long,
    ): AssistantMessage =
        AssistantMessage(
            content = listOf(TextContent(text)),
            api = "anthropic-messages",
            provider = "anthropic",
            model = "claude-test",
            stopReason = StopReason.STOP,
            timestamp = timestamp,
        )
}
