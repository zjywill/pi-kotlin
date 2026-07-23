import { createServer } from "node:http";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const fixtureDir = join(import.meta.dirname, "provider-stream-fixtures");
const apiNames = [
	"openai-completions",
	"anthropic-messages",
	"openai-responses",
	"azure-openai-responses",
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

const output: Record<string, unknown> = {};
for (const api of apiNames) {
	const capture = await captureEvents(api);
	output[api] = capture.events;
	if (api === "azure-openai-responses") {
		output["azure-openai-responses-request"] = capture.request;
	}
}
console.log(JSON.stringify(output));

async function captureEvents(
	api: (typeof apiNames)[number],
): Promise<{ events: unknown[]; request: FixtureRequest }> {
	const fixture = api === "azure-openai-responses" ? "openai-responses" : api;
	const response = readFileSync(join(fixtureDir, `${fixture}.sse`), "utf8") + "\n";
	return withFixtureServer(response, async (baseUrl) => {
		const module = modules.get(api)!;
		const model = fixtureModel(api, baseUrl);
		const stream = module.stream(model, fixtureContext(), {
			apiKey: "test",
			cacheRetention: "none",
			maxRetries: 0,
			...(api === "azure-openai-responses"
				? {
						azureBaseUrl: `${baseUrl}/proxy?tenant=one`,
						azureApiVersion: "2026-07-01-preview",
						azureDeploymentName: "fixture-deployment",
					}
				: {}),
		});
		const events: unknown[] = [];
		for await (const event of stream) {
			events.push(canonicalEvent(event as Record<string, unknown>));
		}
		await stream.result();
		return events;
	});
}

interface FixtureRequest {
	url: string;
	apiKey: string | null;
	hasAuthorization: boolean;
}

function canonicalEvent(event: Record<string, unknown>): Record<string, unknown> {
	const result: Record<string, unknown> = { type: event.type };
	if (typeof event.contentIndex === "number") {
		result.contentIndex = event.contentIndex;
	}
	if (typeof event.delta === "string") {
		result.delta = event.delta;
	}
	if (typeof event.content === "string") {
		result.content = event.content;
	}
	if (event.toolCall) {
		result.toolCall = normalizeDynamicValues(stripStreamingScratch(structuredClone(event.toolCall)));
	}
	if (typeof event.reason === "string") {
		result.reason = event.reason;
	}
	if (event.message) {
		result.message = normalizeDynamicValues(stripStreamingScratch(structuredClone(event.message)));
	}
	if (event.error) {
		result.error = normalizeDynamicValues(stripStreamingScratch(structuredClone(event.error)));
	}
	return result;
}

function stripStreamingScratch(value: unknown): unknown {
	if (Array.isArray(value)) {
		return value.map(stripStreamingScratch);
	}
	if (value && typeof value === "object") {
		return Object.fromEntries(
			Object.entries(value)
				.filter(([key]) => !["index", "partialArgs", "partialJson", "streamIndex"].includes(key))
				.map(([key, entryValue]) => [key, stripStreamingScratch(entryValue)]),
		);
	}
	return value;
}

function fixtureModel(api: string, baseUrl: string): Record<string, unknown> {
	return {
		id: "fixture",
		name: "Fixture",
		api,
		provider: api === "azure-openai-responses" ? "azure-openai-responses" : "fixture",
		baseUrl: api === "azure-openai-responses" ? "" : baseUrl,
		reasoning: false,
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 128000,
		maxTokens: 16384,
	};
}

function fixtureContext(): Record<string, unknown> {
	return {
		messages: [{ role: "user", content: "hi", timestamp: 1 }],
		tools: [
			{
				name: "echo",
				description: "Echo",
				parameters: { type: "object" },
			},
		],
	};
}

function normalizeDynamicValues(value: unknown, key?: string): unknown {
	if (key === "timestamp" && typeof value === "number") {
		return 0;
	}
	if (Array.isArray(value)) {
		return value.map((entry) => normalizeDynamicValues(entry));
	}
	if (value && typeof value === "object") {
		return Object.fromEntries(
			Object.entries(value).map(([entryKey, entryValue]) => [
				entryKey,
				normalizeDynamicValues(entryValue, entryKey),
			]),
		);
	}
	return value;
}

async function withFixtureServer<T>(
	response: string,
	run: (baseUrl: string) => Promise<T>,
): Promise<{ events: T; request: FixtureRequest }> {
	let capturedRequest: FixtureRequest | undefined;
	const server = createServer((request, reply) => {
		capturedRequest = {
			url: request.url ?? "",
			apiKey: typeof request.headers["api-key"] === "string" ? request.headers["api-key"] : null,
			hasAuthorization: typeof request.headers.authorization === "string",
		};
		request.resume();
		reply.writeHead(200, {
			"content-type": "text/event-stream",
			"cache-control": "no-cache",
		});
		reply.end(response);
	});
	await new Promise<void>((resolve, reject) => {
		server.once("error", reject);
		server.listen(0, "127.0.0.1", () => resolve());
	});
	try {
		const address = server.address();
		if (!address || typeof address === "string") {
			throw new Error("Fixture server did not expose a TCP port");
		}
		const events = await run(`http://127.0.0.1:${address.port}`);
		if (!capturedRequest) {
			throw new Error("Fixture server did not receive a request");
		}
		return { events, request: capturedRequest };
	} finally {
		await new Promise<void>((resolve, reject) => {
			server.close((error) => (error ? reject(error) : resolve()));
		});
	}
}
