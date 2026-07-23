import { join } from "node:path";
import { pathToFileURL } from "node:url";

const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const apiNames = [
	"openai-completions",
	"openai-responses",
	"azure-openai-responses",
	"anthropic-messages",
	"google-generative-ai",
	"mistral-conversations",
] as const;

const modules = new Map(
	await Promise.all(
		apiNames.map(async (api) => {
			const source = pathToFileURL(join(sourceRoot, "packages", "ai", "src", "api", `${api}.ts`)).href;
			return [api, await import(source)] as const;
		}),
	),
);

const context = {
	systemPrompt: "system",
	messages: [{ role: "user", content: "hello", timestamp: 1 }],
	tools: [
		{
			name: "echo",
			description: "Echo",
			parameters: {
				type: "object",
				properties: { value: { type: "string" } },
				required: ["value"],
			},
		},
	],
};

const payloads: Record<string, unknown> = {};
for (const api of apiNames) {
	const model = {
		id: "fixture",
		name: "Fixture",
		api,
		provider:
			api === "google-generative-ai"
				? "google"
				: api === "azure-openai-responses"
					? "azure-openai-responses"
					: api === "mistral-conversations"
						? "mistral"
					: "fixture",
		baseUrl: api === "azure-openai-responses" ? "" : "https://fixture.invalid/v1",
		reasoning: false,
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 128000,
		maxTokens: 16384,
	};
	payloads[api] = await capturePayload(api, model, {
		apiKey: "test",
		cacheRetention: "none",
		maxTokens: 123,
		temperature: 0.25,
		...(api === "azure-openai-responses"
			? {
					azureBaseUrl: "https://fixture.invalid/v1",
					azureDeploymentName: "fixture-deployment",
					sessionId: "x".repeat(67),
				}
			: {}),
	});
}

payloads["openai-responses-reasoning"] = await capturePayload(
	"openai-responses",
	{
		...fixtureModel("openai-responses"),
		reasoning: true,
		thinkingLevelMap: { off: "none", high: "high" },
	},
	{ apiKey: "test", cacheRetention: "none", maxTokens: 123, reasoning: "high" },
	true,
);
payloads["azure-openai-responses-reasoning"] = await capturePayload(
	"azure-openai-responses",
	{
		...fixtureModel("azure-openai-responses", "azure-openai-responses"),
		baseUrl: "",
		reasoning: true,
		thinkingLevelMap: { off: "none", high: "high" },
	},
	{
		apiKey: "test",
		cacheRetention: "none",
		maxTokens: 123,
		reasoningEffort: "high",
		reasoningSummary: "detailed",
		azureBaseUrl: "https://fixture.invalid/v1",
		azureDeploymentName: "reasoning-deployment",
	},
);
payloads["anthropic-messages-reasoning"] = await capturePayload(
	"anthropic-messages",
	{
		...fixtureModel("anthropic-messages"),
		reasoning: true,
		compat: { forceAdaptiveThinking: true },
	},
	{ apiKey: "test", cacheRetention: "none", maxTokens: 123, reasoning: "high" },
	true,
);
payloads["google-generative-ai-reasoning"] = await capturePayload(
	"google-generative-ai",
	{
		...fixtureModel("google-generative-ai", "google"),
		id: "gemini-3.1-pro-preview",
		reasoning: true,
	},
	{ apiKey: "test", cacheRetention: "none", maxTokens: 123, reasoning: "medium" },
	true,
);
payloads["openai-completions-reasoning"] = await capturePayload(
	"openai-completions",
	{
		...fixtureModel("openai-completions", "deepseek"),
		baseUrl: "https://api.deepseek.com",
		reasoning: true,
		compat: {
			supportsStore: false,
			supportsDeveloperRole: false,
			requiresReasoningContentOnAssistantMessages: true,
			thinkingFormat: "deepseek",
		},
	},
	{ apiKey: "test", cacheRetention: "none", maxTokens: 123, reasoning: "high" },
	true,
);
payloads["mistral-conversations-reasoning-effort"] = await capturePayload(
	"mistral-conversations",
	{
		...fixtureModel("mistral-conversations", "mistral"),
		id: "mistral-small-2603",
		reasoning: true,
	},
	{
		apiKey: "test",
		cacheRetention: "short",
		sessionId: "session-123",
		maxTokens: 123,
		reasoning: "medium",
	},
	true,
);
payloads["mistral-conversations-prompt-mode"] = await capturePayload(
	"mistral-conversations",
	{
		...fixtureModel("mistral-conversations", "mistral"),
		id: "magistral-medium-latest",
		reasoning: true,
	},
	{
		apiKey: "test",
		cacheRetention: "none",
		maxTokens: 123,
		reasoning: "medium",
	},
	true,
);

console.log(JSON.stringify(payloads));

async function capturePayload(
	api: (typeof apiNames)[number],
	model: Record<string, unknown>,
	options: Record<string, unknown>,
	simple = false,
): Promise<unknown> {
	let payload: unknown;
	const module = modules.get(api)!;
	const stream = (simple ? module.streamSimple : module.stream)(model, context, {
		...options,
		onPayload(value: unknown) {
			payload = value;
			throw new Error("payload captured");
		},
	});
	await stream.result();
	if (payload === undefined) {
		throw new Error(`No payload captured for ${api}`);
	}
	if (api === "google-generative-ai") return normalizeGooglePayload(payload);
	if (api === "mistral-conversations") return normalizeMistralPayload(payload);
	return payload;
}

function fixtureModel(api: string, provider = "fixture"): Record<string, unknown> {
	return {
		id: "fixture",
		name: "Fixture",
		api,
		provider,
		baseUrl: "https://fixture.invalid/v1",
		reasoning: false,
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 128000,
		maxTokens: 16384,
	};
}

function normalizeGooglePayload(value: unknown): unknown {
	const payload = value as {
		contents: unknown;
		config: {
			temperature?: number;
			maxOutputTokens?: number;
			systemInstruction?: string;
			tools?: unknown;
			thinkingConfig?: unknown;
		};
	};
	const generationConfig: Record<string, unknown> = {};
	if (payload.config.temperature !== undefined) {
		generationConfig.temperature = payload.config.temperature;
	}
	if (payload.config.maxOutputTokens !== undefined) {
		generationConfig.maxOutputTokens = payload.config.maxOutputTokens;
	}
	if (payload.config.thinkingConfig !== undefined) {
		generationConfig.thinkingConfig = payload.config.thinkingConfig;
	}
	return {
		contents: payload.contents,
		generationConfig,
		...(payload.config.systemInstruction
			? { systemInstruction: { parts: [{ text: payload.config.systemInstruction }] } }
			: {}),
		...(payload.config.tools ? { tools: payload.config.tools } : {}),
	};
}

function normalizeMistralPayload(value: unknown): unknown {
	const keyMap: Record<string, string> = {
		maxTokens: "max_tokens",
		toolChoice: "tool_choice",
		reasoningEffort: "reasoning_effort",
		promptMode: "prompt_mode",
		promptCacheKey: "prompt_cache_key",
		toolCalls: "tool_calls",
		toolCallId: "tool_call_id",
		imageUrl: "image_url",
	};
	const normalize = (entry: unknown): unknown => {
		if (Array.isArray(entry)) return entry.map(normalize);
		if (entry && typeof entry === "object") {
			return Object.fromEntries(
				Object.entries(entry).map(([key, child]) => [keyMap[key] ?? key, normalize(child)]),
			);
		}
		return entry;
	};
	return normalize(value);
}
