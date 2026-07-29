import { createAssistantMessageEventStream } from "@earendil-works/pi-ai";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

type FixtureModel = {
	id: string;
	name: string;
	api: string;
	provider: string;
	baseUrl: string;
	reasoning: boolean;
	input: readonly ["text"];
	cost: {
		input: number;
		output: number;
		cacheRead: number;
		cacheWrite: number;
	};
	contextWindow: number;
	maxTokens: number;
	thinkingLevelMap: {
		off: null;
		minimal: string;
		low: string;
		medium: string;
		high: string;
		xhigh: string;
		max: null;
	};
};

const models: FixtureModel[] = [
	{
		id: "model-a",
		name: "RPC Fixture A",
		api: "rpc-fixture-api",
		provider: "rpc-fixture",
		baseUrl: "https://rpc-fixture.invalid/v1",
		reasoning: true,
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 262144,
		maxTokens: 4096,
		thinkingLevelMap: {
			off: null,
			minimal: "minimal",
			low: "low",
			medium: "medium",
			high: "high",
			xhigh: "xhigh",
			max: null,
		},
	},
	{
		id: "model-b",
		name: "RPC Fixture B",
		api: "rpc-fixture-api",
		provider: "rpc-fixture",
		baseUrl: "https://rpc-fixture.invalid/v1",
		reasoning: true,
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 262144,
		maxTokens: 4096,
		thinkingLevelMap: {
			off: null,
			minimal: "minimal",
			low: "low",
			medium: "medium",
			high: "high",
			xhigh: "xhigh",
			max: null,
		},
	},
];

const retryAttempts = new Map<string, number>();
let compactionAttempts = 0;

function contentText(content: unknown): string {
	if (typeof content === "string") return content;
	if (!Array.isArray(content)) return "";
	return content
		.filter((part): part is { type: "text"; text: string } => {
			return typeof part === "object" && part !== null && part.type === "text" && typeof part.text === "string";
		})
		.map((part) => part.text)
		.join("");
}

function latestUserText(context: { messages?: Array<{ role?: string; content?: unknown }> }): string {
	for (let index = (context.messages?.length ?? 0) - 1; index >= 0; index--) {
		const message = context.messages?.[index];
		if (message?.role === "user") return contentText(message.content);
	}
	return "";
}

function usage(input = 11, output = 3) {
	return {
		input,
		output,
		cacheRead: 0,
		cacheWrite: 0,
		totalTokens: input + output,
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
	};
}

function assistantMessage(
	model: FixtureModel,
	text: string,
	stopReason: "pending" | "stop" | "error" | "aborted",
	errorMessage?: string,
) {
	return {
		role: "assistant" as const,
		content: text ? [{ type: "text" as const, text }] : [],
		api: model.api,
		provider: model.provider,
		model: model.id,
		usage: usage(),
		stopReason,
		timestamp: 123,
		...(errorMessage ? { errorMessage } : {}),
	};
}

function createStream(
	model: FixtureModel,
	context: { systemPrompt?: string; messages?: Array<{ role?: string; content?: unknown }> },
	options?: { signal?: AbortSignal },
) {
	const stream = createAssistantMessageEventStream();
	const prompt = latestUserText(context);
	const isCompaction = context.systemPrompt?.startsWith("You are a context summarization assistant.") === true;
	const retryKey = `${model.id}:${prompt}`;

	let terminal = false;
	let started = false;
	const begin = () => {
		if (terminal || started) return;
		started = true;
		stream.push({
			type: "start",
			partial: assistantMessage(model, "", "pending"),
		});
	};
	const finishError = (reason: "error" | "aborted", message: string) => {
		if (terminal) return;
		terminal = true;
		stream.push({
			type: "error",
			reason,
			error: assistantMessage(model, "", reason, message),
		});
	};

	const finishSuccess = (text: string) => {
		if (terminal) return;
		terminal = true;
		const partial = assistantMessage(model, text, "pending");
		if (!started) stream.push({ type: "start", partial });
		stream.push({ type: "text_start", contentIndex: 0, partial });
		stream.push({ type: "text_delta", contentIndex: 0, delta: text, partial });
		stream.push({ type: "text_end", contentIndex: 0, content: text, partial });
		stream.push({
			type: "done",
			reason: "stop",
			message: assistantMessage(model, text, "stop"),
		});
	};

	const run = () => {
		if (options?.signal?.aborted) {
			finishError("aborted", "Operation aborted");
			return;
		}
		if (isCompaction) {
			compactionAttempts += 1;
			if (compactionAttempts === 1) {
				finishError("error", "503 service unavailable");
			} else {
				finishSuccess("rpc-summary");
			}
			return;
		}
		if (prompt === "rpc:retry") {
			const attempt = (retryAttempts.get(retryKey) ?? 0) + 1;
			retryAttempts.set(retryKey, attempt);
			if (attempt === 1) {
				finishError("error", "503 service unavailable");
				return;
			}
		}
		const projectedPrompt = prompt.length > 200 ? `long:${prompt.length}` : prompt;
		finishSuccess(`rpc-response:${model.id}:${projectedPrompt}`);
	};

	const delayMs = prompt === "rpc:slow" ? 3000 : prompt === "rpc:abort" ? 5000 : 0;
	if (delayMs > 0) begin();
	const timer = setTimeout(run, delayMs);
	options?.signal?.addEventListener(
		"abort",
		() => {
			clearTimeout(timer);
			finishError("aborted", "Operation aborted");
		},
		{ once: true },
	);
	return stream;
}

export default function rpcRuntimeFixture(pi: ExtensionAPI) {
	pi.registerProvider({
		id: "rpc-fixture",
		name: "RPC Fixture",
		baseUrl: "https://rpc-fixture.invalid/v1",
		auth: {
			apiKey: {
				name: "RPC fixture key",
				async login(interaction) {
					return {
						type: "api_key",
						key: await interaction.prompt({ type: "secret", message: "RPC fixture key" }),
					};
				},
				async check() {
					return { type: "api_key", source: "RPC fixture" };
				},
				async resolve() {
					return {
						auth: { apiKey: "rpc-key" },
						source: "RPC fixture",
					};
				},
			},
		},
		getModels() {
			return models;
		},
		filterModels(available) {
			return available;
		},
		stream(model, context, options) {
			return createStream(model as FixtureModel, context, options);
		},
		streamSimple(model, context, options) {
			return createStream(model as FixtureModel, context, options);
		},
	});

	pi.registerCommand("rpc-events", {
		description: "Emit deterministic RPC extension events",
		handler(args, ctx) {
			pi.appendEntry("rpc-checkpoint", { args });
			pi.setSessionName("RPC Extension Session");
			ctx.ui.notify(`rpc-notify:${args}`, "info");
			ctx.ui.setStatus("rpc", "ready");
			ctx.ui.setWidget("rpc", ["widget-line"], { placement: "aboveEditor" });
			ctx.ui.setTitle("RPC Fixture");
			ctx.ui.setEditorText("rpc-editor");
		},
	});

	pi.registerCommand("rpc-dialogs", {
		description: "Exercise RPC extension dialogs",
		async handler(_args, ctx) {
			const choice = await ctx.ui.select("RPC select", ["alpha", "beta"]);
			const confirmed = await ctx.ui.confirm("RPC confirm", "Continue?");
			const input = await ctx.ui.input("RPC input", "name");
			const edited = await ctx.ui.editor("RPC editor", "draft");
			pi.appendEntry("rpc-dialogs", { choice, confirmed, input, edited });
		},
	});
}
