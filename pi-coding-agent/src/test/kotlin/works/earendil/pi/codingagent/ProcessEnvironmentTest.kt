package works.earendil.pi.codingagent

import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessEnvironmentTest {
    @Test
    fun `child processes inherit pi agent markers`() {
        val process = ProcessBuilder("ignored").withPiAgentEnvironment()

        assertEquals("true", process.environment()["PI_CODING_AGENT"])
        assertEquals("pi", process.environment()["AI_AGENT"])
    }
}
