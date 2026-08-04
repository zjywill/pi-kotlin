import { join } from "node:path";
import { pathToFileURL } from "node:url";

const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const apiNames = [
	"openai-completions",
	"openai-responses",
	"azure-openai-responses",
	"anthropic-messages",
	"google-generative-ai",
	"google-vertex",
	"mistral-conversations",
	"bedrock-converse-stream",
	"openai-codex-responses",
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
				: api === "google-vertex"
					? "google-vertex"
				: api === "azure-openai-responses"
					? "azure-openai-responses"
					: api === "mistral-conversations"
						? "mistral"
						: api === "bedrock-converse-stream"
							? "amazon-bedrock"
							: api === "openai-codex-responses"
								? "openai-codex"
					: "fixture",
		baseUrl: api === "azure-openai-responses" ? "" : "https://fixture.invalid/v1",
		reasoning: false,
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 128000,
		maxTokens: 16384,
	};
	payloads[api] = await capturePayload(api, model, {
		apiKey: api === "openai-codex-responses" ? codexToken("fixture-account") : "test",
		cacheRetention: "none",
		maxTokens: 123,
		temperature: 0.25,
		...(api === "openai-codex-responses" ? { transport: "sse" } : {}),
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
payloads["openai-completions-sampling"] = await capturePayload(
	"openai-completions",
	{
		...fixtureModel("openai-completions"),
		samplingParams: { top_p: 0.95, min_p: 0.05 },
	},
	{
		apiKey: "test",
		temperature: 0,
		samplingParams: { temperature: 1, top_p: 0.5, top_k: 0 },
	},
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
payloads["google-vertex-reasoning"] = await capturePayload(
	"google-vertex",
	{
		...fixtureModel("google-vertex", "google-vertex"),
		id: "gemini-3.1-pro-preview",
		reasoning: true,
		thinkingLevelMap: { off: null, minimal: null, low: "LOW", medium: null, high: "HIGH" },
	},
	{ apiKey: "test", cacheRetention: "none", maxTokens: 123, reasoning: "high" },
	true,
);
payloads["google-vertex-thinking-disabled"] = await capturePayload(
	"google-vertex",
	{
		...fixtureModel("google-vertex", "google-vertex"),
		id: "gemini-3-flash-preview",
		reasoning: true,
		thinkingLevelMap: { off: null },
	},
	{ apiKey: "test", cacheRetention: "none", maxTokens: 123 },
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
payloads["openai-completions-qwen-reasoning-effort"] = await capturePayload(
	"openai-completions",
	{
		...fixtureModel("openai-completions", "qwen-token-plan"),
		reasoning: true,
		thinkingLevelMap: {
			minimal: null,
			low: null,
			medium: null,
			high: "high",
			xhigh: null,
			max: "max",
		},
		compat: {
			thinkingFormat: "qwen",
			supportsDeveloperRole: false,
			supportsStore: false,
			supportsReasoningEffort: true,
		},
	},
	{ apiKey: "test", cacheRetention: "none", maxTokens: 123, reasoning: "high" },
	true,
);
payloads["openai-completions-qwen-thinking-only"] = await capturePayload(
	"openai-completions",
	{
		...fixtureModel("openai-completions", "qwen-token-plan"),
		id: "qwen3.7-plus",
		reasoning: true,
		compat: {
			thinkingFormat: "qwen",
			supportsDeveloperRole: false,
			supportsStore: false,
			supportsReasoningEffort: false,
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
payloads["bedrock-converse-stream-adaptive-thinking"] = await capturePayload(
	"bedrock-converse-stream",
	{
		...fixtureModel("bedrock-converse-stream", "amazon-bedrock"),
		id: "global.anthropic.claude-opus-4-8-v1",
		name: "Claude Opus 4.8",
		reasoning: true,
		thinkingLevelMap: { xhigh: "xhigh", max: "max" },
	},
	{
		apiKey: "test",
		cacheRetention: "none",
		maxTokens: 123,
		reasoning: "xhigh",
	},
);
payloads["bedrock-converse-stream-fixed-thinking"] = await capturePayload(
	"bedrock-converse-stream",
	{
		...fixtureModel("bedrock-converse-stream", "amazon-bedrock"),
		id: "us.anthropic.claude-sonnet-4-5-20250929-v1:0",
		name: "Claude Sonnet 4.5",
		reasoning: true,
	},
	{
		apiKey: "test",
		cacheRetention: "none",
		maxTokens: 123,
		reasoning: "medium",
	},
);
payloads["bedrock-converse-stream-simple-fixed-thinking"] = await capturePayload(
	"bedrock-converse-stream",
	{
		...fixtureModel("bedrock-converse-stream", "amazon-bedrock"),
		id: "us.anthropic.claude-sonnet-4-5-20250929-v1:0",
		name: "Claude Sonnet 4.5",
		reasoning: true,
	},
	{
		apiKey: "test",
		cacheRetention: "none",
		maxTokens: 123,
		reasoning: "medium",
		thinkingBudgets: { medium: 4096 },
	},
	true,
);
payloads["openai-codex-responses-reasoning"] = await capturePayload(
	"openai-codex-responses",
	{
		...fixtureModel("openai-codex-responses", "openai-codex"),
		id: "gpt-5.5",
		name: "GPT-5.5",
		reasoning: true,
		thinkingLevelMap: { minimal: "low", xhigh: "xhigh" },
	},
	{
		apiKey: codexToken("fixture-account"),
		cacheRetention: "short",
		sessionId: "session-123",
		transport: "sse",
		reasoningEffort: "xhigh",
		reasoningSummary: "detailed",
		serviceTier: "priority",
		textVerbosity: "high",
		toolChoice: "required",
	},
);
payloads["openai-codex-responses-simple-minimal"] = await capturePayload(
	"openai-codex-responses",
	{
		...fixtureModel("openai-codex-responses", "openai-codex"),
		id: "gpt-5.5",
		name: "GPT-5.5",
		reasoning: true,
		thinkingLevelMap: { minimal: "low", xhigh: "xhigh" },
	},
	{
		apiKey: codexToken("fixture-account"),
		cacheRetention: "none",
		transport: "sse",
		reasoning: "minimal",
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

function codexToken(accountId: string): string {
	const payload = Buffer.from(
		JSON.stringify({ "https://api.openai.com/auth": { chatgpt_account_id: accountId } }),
	).toString("base64url");
	return `aaa.${payload}.bbb`;
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
