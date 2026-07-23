import { join } from "node:path";
import { pathToFileURL } from "node:url";

const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const apiNames = [
	"openai-completions",
	"openai-responses",
	"anthropic-messages",
	"google-generative-ai",
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
		provider: api === "google-generative-ai" ? "google" : "fixture",
		baseUrl: "https://fixture.invalid/v1",
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
	return api === "google-generative-ai" ? normalizeGooglePayload(payload) : payload;
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
