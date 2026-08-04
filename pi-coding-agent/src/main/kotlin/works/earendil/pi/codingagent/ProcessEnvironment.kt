package works.earendil.pi.codingagent

internal fun ProcessBuilder.withPiAgentEnvironment(): ProcessBuilder =
    apply {
        environment()["PI_CODING_AGENT"] = "true"
        environment()["AI_AGENT"] = "pi"
    }
