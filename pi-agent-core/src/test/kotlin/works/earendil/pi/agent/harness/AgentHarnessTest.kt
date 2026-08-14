package works.earendil.pi.agent.harness

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import works.earendil.pi.agent.AgentThinkingLevel
import works.earendil.pi.agent.AgentToolResult
import works.earendil.pi.agent.QueueMode
import works.earendil.pi.agent.harness.session.BasicDurableSessionCreateOptions
import works.earendil.pi.agent.harness.session.InMemoryDurableSessionRepository
import works.earendil.pi.agent.harness.session.NewDurableRecord
import works.earendil.pi.agent.harness.session.OperationIntent
import works.earendil.pi.agent.harness.session.RecordPayload
import works.earendil.pi.ai.FauxProvider
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.Usage

class AgentHarnessTest {
    @Test
    fun `opens only record free sessions before restore is implemented`() =
        runTest {
            val fixture = fixture()
            val creation =
                AgentHarness.create(
                    AgentHarnessOptions(
                        session = fixture.session,
                        models = fixture.models,
                        model = fixture.model,
                    ),
                )

            assertEquals(emptyList(), creation.suspended)
            assertEquals("main", creation.harness.name)
            assertSame(fixture.session, creation.harness.session)
            assertEquals(null, creation.harness.getLeafId())

            val recorded =
                fixture.repository.create(
                    BasicDurableSessionCreateOptions(id = "recorded"),
                )
            recorded.appendRecord(
                NewDurableRecord(
                    id = "run",
                    lane = "main",
                    payload =
                        RecordPayload.OperationStarted(
                            sourceLeafId = null,
                            intent = OperationIntent.Run(emptyList(), emptyList()),
                        ),
                ),
            )

            val error =
                assertFailsWith<HarnessNotImplemented> {
                    AgentHarness.create(
                        AgentHarnessOptions(
                            session = recorded,
                            models = fixture.models,
                            model = fixture.model,
                        ),
                    )
                }
            assertEquals("create.restore", error.operation)
        }

    @Test
    fun `configuration getters and setters isolate mutable collections`() =
        runTest {
            val fixture = fixture()
            val harness =
                AgentHarness
                    .create(
                        AgentHarnessOptions(
                            session = fixture.session,
                            models = fixture.models,
                            model = fixture.model,
                        ),
                    ).harness

            harness.setThinkingLevel(AgentThinkingLevel.HIGH)
            assertEquals(AgentThinkingLevel.HIGH, harness.getThinkingLevel())

            val activeTools = mutableListOf("one")
            harness.setActiveTools(activeTools)
            activeTools += "mutated"
            assertEquals(listOf("one"), harness.getActiveTools())

            val tool = TestHarnessTool("tool")
            val tools = mutableListOf<HarnessTool>(tool)
            harness.setTools(tools)
            tools += TestHarnessTool("mutated")
            assertEquals(listOf("tool"), harness.getTools().map(HarnessTool::name))

            val skills =
                mutableListOf(
                    HarnessSkill(
                        name = "skill",
                        description = "description",
                        content = "content",
                        filePath = "/tmp/SKILL.md",
                    ),
                )
            harness.setResources(HarnessResources(skills = skills))
            skills +=
                HarnessSkill(
                    name = "mutated",
                    description = "description",
                    content = "content",
                    filePath = "/tmp/OTHER.md",
                )
            assertEquals(listOf("skill"), harness.getResources().skills?.map(HarnessSkill::name))

            harness.setStreamOptions(SimpleStreamOptions())
            assertEquals(SimpleStreamOptions(), harness.getStreamOptions())
            harness.setRetryPolicy(HarnessRetryPolicy(enabled = true, maxRetries = 2, baseDelayMs = 10))
            assertEquals(
                HarnessRetryPolicy(enabled = true, maxRetries = 2, baseDelayMs = 10),
                harness.getRetryPolicy(),
            )
            harness.setCompactionSettings(
                HarnessCompactionSettings(enabled = false, reserveTokens = 1, keepRecentTokens = 2),
            )
            assertEquals(
                HarnessCompactionSettings(enabled = false, reserveTokens = 1, keepRecentTokens = 2),
                harness.getCompactionSettings(),
            )
            harness.setSteeringMode(QueueMode.ALL)
            harness.setFollowUpMode(QueueMode.ALL)
            assertEquals(QueueMode.ALL, harness.getSteeringMode())
            assertEquals(QueueMode.ALL, harness.getFollowUpMode())
        }

    @Test
    fun `unfinished operations fail explicitly and callbacks do not run`() =
        runTest {
            val harness = fixture().harness()
            var callbackCalled = false

            val failures =
                listOf<suspend () -> Nothing>(
                    { harness.prompt("hello") },
                    { harness.skill("skill") },
                    { harness.promptFromTemplate("template") },
                    { harness.compact() },
                    { harness.navigateTree(null) },
                    { harness.resume() },
                    { harness.abort() },
                    { harness.steer("hello") },
                    { harness.followUp("hello") },
                    { harness.nextRun("hello") },
                    { harness.cancelQueued("queued") },
                    { harness.recordUsage(Usage()) },
                    {
                        harness.runWhenIdle {
                            callbackCalled = true
                        }
                    },
                    { harness.peekAction() },
                    { harness.executeAction() },
                    { harness.runToCompletion() },
                    { harness.watch() },
                    { harness.lane("main") },
                    { harness.createLane("thread", null) },
                    { harness.lanes() },
                    { harness.watchSession() },
                )
            failures.forEach { operation ->
                assertIs<HarnessNotImplemented>(assertFailsWith { operation() })
            }
            assertFalse(callbackCalled)
            assertFailsWith<HarnessNotImplemented> {
                harness.hooks.on("before_run", { Unit })
            }
            assertFailsWith<IllegalArgumentException> {
                harness.events.on("event", HarnessEventListener { })
            }
        }

    @Test
    fun `closed harness reports the closed error for unfinished work`() =
        runTest {
            val harness = fixture().harness()
            harness.close()

            assertFailsWith<HarnessClosed> { harness.prompt("hello") }
            assertFailsWith<HarnessClosed> { harness.waitForIdle() }
            assertFailsWith<HarnessClosed> {
                harness.hooks.on("before_run", { Unit })
            }
            assertFailsWith<HarnessClosed> {
                harness.events.on("run_start", HarnessEventListener { })
            }
        }

    private suspend fun fixture(): Fixture {
        val repository =
            InMemoryDurableSessionRepository(
                idGenerator = { "generated" },
            )
        val session =
            repository.create(
                BasicDurableSessionCreateOptions(id = "session"),
            )
        val provider = FauxProvider()
        return Fixture(
            repository = repository,
            session = session,
            models = Models(listOf(provider)),
            model = requireNotNull(provider.getModel()),
        )
    }

    private data class Fixture(
        val repository: InMemoryDurableSessionRepository,
        val session: works.earendil.pi.agent.harness.session.DurableSession<*>,
        val models: Models,
        val model: works.earendil.pi.ai.Model,
    ) {
        suspend fun harness(): AgentHarness =
            AgentHarness
                .create(
                    AgentHarnessOptions(
                        session = session,
                        models = models,
                        model = model,
                    ),
                ).harness
    }

    private class TestHarnessTool(
        override val name: String,
    ) : HarnessTool {
        override val label: String = name
        override val description: String = name
        override val parameters: JsonObject = JsonObject(emptyMap())

        override suspend fun execute(
            toolCallId: String,
            params: JsonObject,
            onUpdate: works.earendil.pi.agent.AgentToolUpdateCallback?,
        ): AgentToolResult =
            AgentToolResult(
                content = listOf(TextContent(toolCallId + params.size + onUpdate.hashCode())),
            )
    }
}
