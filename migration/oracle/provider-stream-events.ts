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
	"google-vertex",
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
const modelsModule = await import(pathToFileURL(join(sourceRoot, "packages", "ai", "src", "models.ts")).href);
const cloudflareGatewayModule = await import(
	pathToFileURL(join(sourceRoot, "packages", "ai", "src", "providers", "cloudflare-ai-gateway.ts")).href
);
const cloudflareWorkersModule = await import(
	pathToFileURL(join(sourceRoot, "packages", "ai", "src", "providers", "cloudflare-workers-ai.ts")).href
);

const output: Record<string, unknown> = {};
for (const api of apiNames) {
	const capture = await captureEvents(api);
	output[api] = capture.events;
	if (api === "azure-openai-responses") {
		output["azure-openai-responses-request"] = baseRequestProjection(capture.request);
	}
	if (api === "mistral-conversations") {
		output["mistral-conversations-request"] = baseRequestProjection(capture.request);
	}
	if (api === "google-vertex") {
		output["google-vertex-request"] = {
			url: capture.request.url,
			authorization: capture.request.authorization,
			xGoogApiKey: capture.request.xGoogApiKey,
			body: capture.request.body,
		};
	}
}
output["cloudflare-auth-resolution"] = await captureCloudflareAuthResolution();
for (const fixture of [
	{ name: "cloudflare-workers-ai", provider: "workers" as const, api: "openai-completions" as const },
	{ name: "cloudflare-ai-gateway-chat", provider: "gateway" as const, api: "openai-completions" as const },
	{ name: "cloudflare-ai-gateway-responses", provider: "gateway" as const, api: "openai-responses" as const },
	{ name: "cloudflare-ai-gateway-anthropic", provider: "gateway" as const, api: "anthropic-messages" as const },
]) {
	output[`${fixture.name}-request`] = await captureCloudflareRequest(fixture.provider, fixture.api);
}
console.log(JSON.stringify(output));

async function captureEvents(
	api: (typeof apiNames)[number],
): Promise<{ events: unknown[]; request: FixtureRequest }> {
	const fixture =
		api === "azure-openai-responses"
			? "openai-responses"
			: api === "google-vertex"
				? "google-generative-ai"
				: api;
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
			...(api === "mistral-conversations" ? { sessionId: "session-123" } : {}),
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
	authorization: string | null;
	xAffinity: string | null;
	cfAigAuthorization: string | null;
	xApiKey: string | null;
	sessionId: string | null;
	xClientRequestId: string | null;
	xSessionAffinity: string | null;
	xGoogApiKey: string | null;
	body: unknown;
}

function baseRequestProjection(request: FixtureRequest): Record<string, unknown> {
	return {
		url: request.url,
		apiKey: request.apiKey,
		hasAuthorization: request.hasAuthorization,
		authorization: request.authorization,
		xAffinity: request.xAffinity,
	};
}

function cloudflareRequestProjection(request: FixtureRequest): Record<string, unknown> {
	return {
		url: request.url,
		authorization: request.authorization,
		cfAigAuthorization: request.cfAigAuthorization,
		xApiKey: request.xApiKey,
		sessionId: request.sessionId,
		xClientRequestId: request.xClientRequestId,
		xSessionAffinity: request.xSessionAffinity,
	};
}

async function captureCloudflareAuthResolution(): Promise<Record<string, unknown>> {
	const ambient = {
		CLOUDFLARE_API_KEY: "ambient-key",
		CLOUDFLARE_ACCOUNT_ID: "ambient-account",
		CLOUDFLARE_GATEWAY_ID: "ambient-gateway",
	};
	const context = {
		env: async (name: string) => ambient[name as keyof typeof ambient],
		fileExists: async () => false,
	};
	const gatewayAuth = cloudflareGatewayModule.cloudflareAIGatewayProvider().auth.apiKey;
	const workersAuth = cloudflareWorkersModule.cloudflareWorkersAIProvider().auth.apiKey;
	const gateway = await gatewayAuth.resolve({
		ctx: context,
		credential: {
			type: "api_key",
			key: "stored-key",
			env: { CLOUDFLARE_ACCOUNT_ID: "stored-account" },
		},
	});
	const workers = await workersAuth.resolve({
		ctx: context,
		credential: {
			type: "api_key",
			key: "stored-key",
			env: { CLOUDFLARE_ACCOUNT_ID: "stored-account" },
		},
	});
	const missingGateway = await gatewayAuth.resolve({
		ctx: {
			env: async (name: string) =>
				({ CLOUDFLARE_API_KEY: "key", CLOUDFLARE_ACCOUNT_ID: "account" })[
					name as "CLOUDFLARE_API_KEY" | "CLOUDFLARE_ACCOUNT_ID"
				],
			fileExists: async () => false,
		},
	});
	return {
		gateway: {
			apiKey: gateway?.auth.apiKey ?? null,
			headers: gateway?.auth.headers ?? null,
			env: gateway?.env ?? null,
		},
		workers: {
			apiKey: workers?.auth.apiKey ?? null,
			headers: workers?.auth.headers ?? null,
			env: workers?.env ?? null,
		},
		missingGatewayConfigured: missingGateway !== undefined,
	};
}

async function captureCloudflareRequest(
	providerKind: "workers" | "gateway",
	api: "openai-completions" | "openai-responses" | "anthropic-messages",
): Promise<Record<string, unknown>> {
	const response = readFileSync(join(fixtureDir, `${api}.sse`), "utf8") + "\n";
	const capture = await withFixtureServer(response, async (baseUrl) => {
		const provider =
			providerKind === "workers"
				? cloudflareWorkersModule.cloudflareWorkersAIProvider()
				: cloudflareGatewayModule.cloudflareAIGatewayProvider();
		const providerId = providerKind === "workers" ? "cloudflare-workers-ai" : "cloudflare-ai-gateway";
		const basePath =
			providerKind === "workers"
				? `${baseUrl}/client/v4/accounts/{CLOUDFLARE_ACCOUNT_ID}/ai/v1`
				: `${baseUrl}/v1/{CLOUDFLARE_ACCOUNT_ID}/{CLOUDFLARE_GATEWAY_ID}/${
						api === "openai-completions" ? "compat" : api === "openai-responses" ? "openai" : "anthropic"
					}`;
		const model = {
			...fixtureModel(api, basePath),
			provider: providerId,
			baseUrl: basePath,
			...(api === "openai-completions" ? { compat: { sendSessionAffinityHeaders: true } } : {}),
		};
		const env = {
			CLOUDFLARE_API_KEY: "cf-token",
			CLOUDFLARE_ACCOUNT_ID: "account",
			CLOUDFLARE_GATEWAY_ID: "gateway",
		};
		const models = modelsModule.createModels({
			authContext: {
				env: async (name: string) => env[name as keyof typeof env],
				fileExists: async () => false,
			},
		});
		models.setProvider(provider);
		const stream = models.stream(model, fixtureContext(), {
			maxRetries: 0,
			...(api === "openai-completions" ? { sessionId: "session-123" } : {}),
			...(providerKind === "gateway" && api === "openai-responses"
				? { headers: { Authorization: "Bearer upstream-token" } }
				: {}),
		});
		await stream.result();
		return null;
	});
	return cloudflareRequestProjection(capture.request);
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
		provider:
			api === "azure-openai-responses"
				? "azure-openai-responses"
				: api === "mistral-conversations"
					? "mistral"
					: api === "google-vertex"
						? "google-vertex"
					: "fixture",
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
	const server = createServer(async (request, reply) => {
		const chunks: Buffer[] = [];
		for await (const chunk of request) {
			chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
		}
		const bodyText = Buffer.concat(chunks).toString("utf8");
		capturedRequest = {
			url: request.url ?? "",
			apiKey: typeof request.headers["api-key"] === "string" ? request.headers["api-key"] : null,
			hasAuthorization: typeof request.headers.authorization === "string",
			authorization: typeof request.headers.authorization === "string" ? request.headers.authorization : null,
			xAffinity: typeof request.headers["x-affinity"] === "string" ? request.headers["x-affinity"] : null,
			cfAigAuthorization:
				typeof request.headers["cf-aig-authorization"] === "string"
					? request.headers["cf-aig-authorization"]
					: null,
			xApiKey: typeof request.headers["x-api-key"] === "string" ? request.headers["x-api-key"] : null,
			sessionId: typeof request.headers.session_id === "string" ? request.headers.session_id : null,
			xClientRequestId:
				typeof request.headers["x-client-request-id"] === "string"
					? request.headers["x-client-request-id"]
					: null,
			xSessionAffinity:
				typeof request.headers["x-session-affinity"] === "string" ? request.headers["x-session-affinity"] : null,
			xGoogApiKey: typeof request.headers["x-goog-api-key"] === "string" ? request.headers["x-goog-api-key"] : null,
			body: bodyText ? JSON.parse(bodyText) : null,
		};
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
