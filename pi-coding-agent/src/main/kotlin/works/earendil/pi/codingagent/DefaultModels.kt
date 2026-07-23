package works.earendil.pi.codingagent

internal fun defaultModelId(provider: String): String? = DEFAULT_MODEL_IDS[provider]

private val DEFAULT_MODEL_IDS =
    mapOf(
        "ant-ling" to "Ring-2.6-1T",
        "anthropic" to "claude-opus-4-8",
        "cerebras" to "zai-glm-4.7",
        "deepseek" to "deepseek-v4-pro",
        "fireworks" to "accounts/fireworks/models/kimi-k2p6",
        "google" to "gemini-3.1-pro-preview",
        "groq" to "openai/gpt-oss-120b",
        "huggingface" to "moonshotai/Kimi-K2.6",
        "kimi-coding" to "kimi-for-coding",
        "minimax" to "MiniMax-M2.7",
        "minimax-cn" to "MiniMax-M2.7",
        "moonshotai" to "kimi-k2.6",
        "moonshotai-cn" to "kimi-k2.6",
        "nvidia" to "nvidia/nemotron-3-super-120b-a12b",
        "openai" to "gpt-5.5",
        "opencode" to "kimi-k2.6",
        "opencode-go" to "kimi-k2.6",
        "openrouter" to "moonshotai/kimi-k2.6",
        "qwen-token-plan" to "qwen3.7-max",
        "qwen-token-plan-cn" to "qwen3.7-max",
        "together" to "moonshotai/Kimi-K2.6",
        "vercel-ai-gateway" to "zai/glm-5.1",
        "xai" to "grok-4.5",
        "xiaomi" to "mimo-v2.5-pro",
        "xiaomi-token-plan-ams" to "mimo-v2.5-pro",
        "xiaomi-token-plan-cn" to "mimo-v2.5-pro",
        "xiaomi-token-plan-sgp" to "mimo-v2.5-pro",
        "zai" to "glm-5.1",
        "zai-coding-cn" to "glm-5.1",
    )
