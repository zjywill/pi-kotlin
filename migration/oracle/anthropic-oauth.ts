import { createServer } from "node:http";
import { pathToFileURL } from "node:url";

const tsRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const { anthropicOAuth } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/auth/oauth/anthropic.ts`).href
);
const { stream } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/api/anthropic-messages.ts`).href
);
const { createModels } = await import(pathToFileURL(`${tsRoot}/packages/ai/src/models.ts`).href);
const { anthropicProvider } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/providers/anthropic.ts`).href
);

type OAuthRequest = {
	url: string;
	method: string;
	headers: Record<string, string>;
	body: Record<string, unknown>;
};

const oauthRequests: OAuthRequest[] = [];
const originalFetch = globalThis.fetch;
globalThis.fetch = async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
	const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
	const body = JSON.parse(String(init?.body ?? "{}")) as Record<string, unknown>;
	oauthRequests.push({
		url,
		method: init?.method ?? "GET",
		headers: Object.fromEntries(new Headers(init?.headers).entries()),
		body,
	});
	const refresh = body.grant_type === "refresh_token";
	return new Response(
		JSON.stringify({
			access_token: refresh ? "sk-ant-oat-refreshed" : "sk-ant-oat-login",
			refresh_token: refresh ? "refresh-rotated" : "refresh-login",
			expires_in: refresh ? 7200 : 3600,
		}),
		{ status: 200, headers: { "content-type": "application/json" } },
	);
};

let authorizationUrl = "";
const loginEvents: unknown[] = [];
const loginStart = Date.now();
const loginCredential = await anthropicOAuth.login({
	notify: (event) => {
		if (event.type === "auth_url") authorizationUrl = event.url;
		if (event.type === "progress") loginEvents.push({ type: event.type, message: event.message });
	},
	prompt: async (prompt) => {
		if (prompt.type !== "manual_code") throw new Error(`Unexpected prompt: ${prompt.type}`);
		const url = new URL(authorizationUrl);
		return `${url.searchParams.get("redirect_uri")}?code=manual-code&state=${url.searchParams.get("state")}`;
	},
});
const refreshStart = Date.now();
const refreshCredential = await anthropicOAuth.refresh(loginCredential);
globalThis.fetch = originalFetch;

type CapturedProviderRequest = {
	path: string;
	headers: Record<string, string | string[] | undefined>;
	body: Record<string, unknown>;
};

const providerRequests: CapturedProviderRequest[] = [];
const fixture = createServer(async (request, response) => {
	const chunks: Buffer[] = [];
	for await (const chunk of request) {
		chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
	}
	providerRequests.push({
		path: request.url ?? "",
		headers: request.headers,
		body: JSON.parse(Buffer.concat(chunks).toString("utf8")) as Record<string, unknown>,
	});
	const sse = [
		'event: message_start\ndata: {"type":"message_start","message":{"id":"msg-1","usage":{"input_tokens":1,"output_tokens":0}}}',
		'event: content_block_start\ndata: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool-1","name":"Read","input":{}}}',
		'event: content_block_delta\ndata: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":\\"README.md\\"}"}}',
		'event: content_block_stop\ndata: {"type":"content_block_stop","index":0}',
		'event: message_delta\ndata: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":1}}',
		'event: message_stop\ndata: {"type":"message_stop"}',
		"",
	].join("\n\n");
	response.writeHead(200, { "content-type": "text/event-stream" });
	response.end(sse);
});
await new Promise<void>((resolve) => fixture.listen(0, "127.0.0.1", resolve));
const address = fixture.address();
if (!address || typeof address === "string") throw new Error("Fixture server did not bind");

const model = {
	id: "claude-test",
	name: "Claude Test",
	api: "anthropic-messages" as const,
	provider: "anthropic",
	baseUrl: `http://127.0.0.1:${address.port}`,
	reasoning: false,
	input: ["text"] as const,
	cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
	contextWindow: 128000,
	maxTokens: 16384,
};
const context = {
	systemPrompt: "Project instructions",
	messages: [
		{ role: "user" as const, content: "Run the tool", timestamp: 1 },
		{
			role: "assistant" as const,
			content: [{ type: "toolCall" as const, id: "prior-call", name: "bash", arguments: {} }],
			api: "anthropic-messages" as const,
			provider: "anthropic",
			model: "claude-test",
			usage: {
				input: 0,
				output: 0,
				cacheRead: 0,
				cacheWrite: 0,
				totalTokens: 0,
				cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
			},
			stopReason: "stop" as const,
			timestamp: 2,
		},
		{
			role: "toolResult" as const,
			toolCallId: "prior-call",
			toolName: "bash",
			content: [{ type: "text" as const, text: "done" }],
			isError: false,
			timestamp: 3,
		},
		{ role: "user" as const, content: "Read the file", timestamp: 4 },
	],
	tools: [
		{
			name: "read",
			description: "Read a file",
			parameters: { type: "object", properties: { path: { type: "string" } } },
		},
		{
			name: "echo",
			description: "Echo",
			parameters: { type: "object" },
		},
	],
};
let terminal: any;
for await (const event of stream(model, context, {
	apiKey: "prefix-sk-ant-oat-session-token",
	cacheRetention: "none",
})) {
	if (event.type === "done" || event.type === "error") terminal = event;
}
const models = createModels({
	authContext: {
		env: async (name) => (name === "ANTHROPIC_AUTH_TOKEN" ? "gateway-token" : undefined),
		fileExists: async () => false,
	},
});
models.setProvider({ ...anthropicProvider(), getModels: () => [model] });
const bearerResult = await models.completeSimple(model, context, { cacheRetention: "none" });
await new Promise<void>((resolve) => fixture.close(() => resolve()));
const providerRequest = providerRequests[0];
const bearerRequest = providerRequests[1];
if (!providerRequest || !bearerRequest) throw new Error("Provider requests were not captured");

const auth = new URL(authorizationUrl);
const verifier = auth.searchParams.get("state") ?? "";
const challenge = Buffer.from(
	await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)),
).toString("base64url");
const exchange = oauthRequests.find((request) => request.body.grant_type === "authorization_code")!;
const refresh = oauthRequests.find((request) => request.body.grant_type === "refresh_token")!;
const headers = providerRequest.headers;

function credentialProjection(
	credential: { access: string; refresh: string; expires: number },
	start: number,
) {
	return {
		access: credential.access,
		refresh: credential.refresh,
		expiresInSeconds: Math.round((credential.expires - start) / 1000),
	};
}

console.log(
	JSON.stringify(
		{
			login: {
				authorization: {
					code: auth.searchParams.get("code"),
					client_id: auth.searchParams.get("client_id"),
					response_type: auth.searchParams.get("response_type"),
					redirect_uri: auth.searchParams.get("redirect_uri"),
					scope: auth.searchParams.get("scope"),
					code_challenge_method: auth.searchParams.get("code_challenge_method"),
					stateLength: verifier.length,
					challengeLength: auth.searchParams.get("code_challenge")?.length,
					challengeMatches: challenge === auth.searchParams.get("code_challenge"),
				},
				events: loginEvents,
				request: {
					url: exchange.url,
					method: exchange.method,
					contentType: exchange.headers["content-type"],
					accept: exchange.headers.accept,
					grant_type: exchange.body.grant_type,
					client_id: exchange.body.client_id,
					code: exchange.body.code,
					redirect_uri: exchange.body.redirect_uri,
					stateMatchesVerifier: exchange.body.state === exchange.body.code_verifier,
					verifierLength: String(exchange.body.code_verifier).length,
				},
				credential: credentialProjection(loginCredential, loginStart),
			},
			refresh: {
				request: {
					grant_type: refresh.body.grant_type,
					client_id: refresh.body.client_id,
					refresh_token: refresh.body.refresh_token,
					hasScope: Object.hasOwn(refresh.body, "scope"),
				},
				credential: credentialProjection(refreshCredential, refreshStart),
			},
			provider: {
				path: providerRequest.path,
				headers: {
					authorization: headers.authorization,
					xApiKey: headers["x-api-key"] ?? null,
					accept: headers.accept,
					contentType: headers["content-type"],
					anthropicVersion: headers["anthropic-version"],
					anthropicBeta: headers["anthropic-beta"],
					userAgent: headers["user-agent"],
					xApp: headers["x-app"],
					dangerous: headers["anthropic-dangerous-direct-browser-access"],
				},
				body: providerRequest.body,
				result: {
					stopReason: terminal?.reason,
					toolName: terminal?.message?.content?.find((block: any) => block.type === "toolCall")?.name,
				},
			},
			bearer: {
				headers: {
					authorization: bearerRequest.headers.authorization,
					xApiKey: bearerRequest.headers["x-api-key"] ?? null,
					anthropicBeta: bearerRequest.headers["anthropic-beta"],
					xApp: bearerRequest.headers["x-app"] ?? null,
				},
				body: bearerRequest.body,
				result: {
					stopReason: bearerResult.stopReason,
					toolName: bearerResult.content.find((block: any) => block.type === "toolCall")?.name,
				},
			},
		},
		null,
		2,
	),
);
