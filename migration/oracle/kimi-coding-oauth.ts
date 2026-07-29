import { createServer } from "node:http";
import { pathToFileURL } from "node:url";

const tsRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const { kimiCodingOAuth } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/auth/oauth/kimi-coding.ts`).href
);
const { InMemoryCredentialStore } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/auth/credential-store.ts`).href
);
const { createModels } = await import(pathToFileURL(`${tsRoot}/packages/ai/src/models.ts`).href);
const { kimiCodingProvider } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/providers/kimi-coding.ts`).href
);

type RequestRecord = {
	url: string;
	method: string;
	headers: Record<string, string>;
	body: string;
};

type ProviderRequest = {
	path: string;
	headers: Record<string, string | string[] | undefined>;
	body: Record<string, unknown>;
};

const originalFetch = globalThis.fetch;
const originalSetTimeout = globalThis.setTimeout;
const originalDateNow = Date.now;
let currentTime = 1_000_000;
const sleeps: number[] = [];
const pollTimes: number[] = [];
const oauthRequests: RequestRecord[] = [];
let devicePoll = 0;
let refreshCall = 0;

Date.now = () => currentTime;
globalThis.setTimeout = ((
	callback: TimerHandler,
	delay?: number,
	...args: unknown[]
): ReturnType<typeof setTimeout> => {
	const milliseconds = Number(delay ?? 0);
	sleeps.push(milliseconds);
	currentTime += milliseconds;
	queueMicrotask(() => {
		if (typeof callback === "function") callback(...args);
	});
	return 1 as unknown as ReturnType<typeof setTimeout>;
}) as typeof setTimeout;

globalThis.fetch = async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
	const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
	const request = {
		url,
		method: init?.method ?? "GET",
		headers: Object.fromEntries(new Headers(init?.headers).entries()),
		body: String(init?.body ?? ""),
	};
	oauthRequests.push(request);

	if (url.endsWith("/device_authorization")) {
		return jsonResponse({
			user_code: "ABCD-1234",
			device_code: "device-code-123",
			verification_uri: "https://www.kimi.com/code",
			verification_uri_complete: "https://www.kimi.com/code?user_code=ABCD-1234",
			interval: 1,
			expires_in: 60,
		});
	}
	if (url.endsWith("/token")) {
		const grantType = form(request.body).grant_type;
		if (grantType === "refresh_token") {
			refreshCall += 1;
			if (refreshCall === 1) return jsonResponse({ error: "temporarily_unavailable" }, 429);
			if (refreshCall === 2) return jsonResponse({ error: "server_error" }, 500);
			if (refreshCall === 3) {
					return jsonResponse({
						access_token: "refreshed-access",
						refresh_token: "refreshed-refresh",
						expires_in: 600,
					});
			}
			return jsonResponse({ error: "invalid_grant", error_description: "session revoked" }, 400);
		}
		pollTimes.push(Date.now());
		if (devicePoll++ === 0) {
			return jsonResponse({ error: "authorization_pending" }, 400);
		}
		if (devicePoll === 2) {
			return jsonResponse({ error: "slow_down", interval: 2 }, 400);
		}
		return jsonResponse({
			access_token: "access-token",
			refresh_token: "refresh-token",
			expires_in: 3600,
		});
	}
	throw new Error(`Unexpected Kimi OAuth request: ${url}`);
};

let deviceEvent: Record<string, unknown> | undefined;
let loginCredential;
let refreshedCredential;
let auth;
let unauthorizedMessage = "";
try {
	loginCredential = await kimiCodingOAuth.login({
		prompt: async (prompt) => {
			throw new Error(`Unexpected Kimi prompt: ${prompt.type}`);
		},
		notify: (event) => {
			if (event.type === "device_code") {
				const { type: _type, ...projection } = event;
				deviceEvent = projection;
			}
		},
	});
	refreshedCredential = await kimiCodingOAuth.refresh(loginCredential);
	auth = await kimiCodingOAuth.toAuth(refreshedCredential);
	try {
		await kimiCodingOAuth.refresh(refreshedCredential);
	} catch (error) {
		unauthorizedMessage = error instanceof Error ? error.message : String(error);
	}
} finally {
	globalThis.fetch = originalFetch;
	globalThis.setTimeout = originalSetTimeout;
}

if (!deviceEvent || !loginCredential || !refreshedCredential || !auth) {
	throw new Error("Kimi OAuth oracle did not complete");
}

const providerRequests: ProviderRequest[] = [];
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
	response.writeHead(200, { "content-type": "text/event-stream" });
	response.end(anthropicSse());
});

try {
	await new Promise<void>((resolve) => fixture.listen(0, "127.0.0.1", resolve));
	const address = fixture.address();
	if (!address || typeof address === "string") throw new Error("Kimi provider fixture did not bind");
	const credentials = new InMemoryCredentialStore();
	await credentials.modify("kimi-coding", async () => refreshedCredential);
	const models = createModels({ credentials });
	const provider = kimiCodingProvider();
	models.setProvider(provider);
	const model = {
		...requiredModel(provider.getModels(), "kimi-for-coding"),
		baseUrl: `http://127.0.0.1:${address.port}`,
		reasoning: false,
	};
	const result = await models.complete(
		model,
		{
			systemPrompt: "Project instructions",
			messages: [{ role: "user" as const, content: "hi", timestamp: 1 }],
			tools: [
				{
					name: "echo",
					description: "Echo",
					parameters: { type: "object" },
				},
			],
		},
		{
			cacheRetention: "none",
			maxRetries: 0,
			maxTokens: 64,
		},
	);
	const deviceRequest = requiredRequest((request) => request.url.endsWith("/device_authorization"));
	const pollRequests = oauthRequests.filter(
		(request) => form(request.body).grant_type === "urn:ietf:params:oauth:grant-type:device_code",
	);
	const refreshRequests = oauthRequests.filter((request) => form(request.body).grant_type === "refresh_token");
	const providerRequest = providerRequests[0];
	if (!providerRequest) throw new Error("Kimi provider request was not captured");

	console.log(
		JSON.stringify(
			{
				login: {
					name: kimiCodingOAuth.name,
					loginLabel: kimiCodingOAuth.loginLabel,
					device: deviceEvent,
					sleeps,
					pollTimes,
					credential: loginCredential,
				},
				requests: {
					device: requestProjection(deviceRequest),
					poll: {
						count: pollRequests.length,
						request: requestProjection(pollRequests[0]),
					},
					refresh: {
						count: refreshRequests.length - 1,
						request: requestProjection(refreshRequests[0]),
					},
					unauthorized: requestProjection(refreshRequests.at(-1)!),
				},
				refresh: {
					credential: refreshedCredential,
					auth,
					unauthorizedMessage,
				},
				provider: {
					path: providerRequest.path,
					authorization: providerRequest.headers.authorization,
					xApiKey: providerRequest.headers["x-api-key"],
					userAgent: providerRequest.headers["user-agent"],
					anthropicBeta: providerRequest.headers["anthropic-beta"],
					body: providerRequest.body,
					result: resultProjection(result),
				},
			},
			null,
			2,
		),
	);
} finally {
	await new Promise<void>((resolve) => fixture.close(() => resolve()));
	Date.now = originalDateNow;
}

function jsonResponse(body: unknown, status = 200): Response {
	return new Response(JSON.stringify(body), {
		status,
		headers: { "content-type": "application/json" },
	});
}

function form(body: string): Record<string, string> {
	return Object.fromEntries(new URLSearchParams(body).entries());
}

function requiredRequest(predicate: (request: RequestRecord) => boolean): RequestRecord {
	const request = oauthRequests.find(predicate);
	if (!request) throw new Error("Missing Kimi OAuth request");
	return request;
}

function requestProjection(request: RequestRecord): Record<string, unknown> {
	return {
		url: request.url,
		method: request.method,
		accept: request.headers.accept,
		contentType: request.headers["content-type"],
		form: form(request.body),
	};
}

function requiredModel(models: readonly { id: string }[], id: string): any {
	const model = models.find((entry) => entry.id === id);
	if (!model) throw new Error(`Missing Kimi model: ${id}`);
	return model;
}

function resultProjection(message: any): Record<string, unknown> {
	const content = message.content ?? [];
	return {
		stopReason: message.stopReason,
		text: content
			.filter((block: any) => block.type === "text")
			.map((block: any) => block.text)
			.join(""),
		toolName: content.find((block: any) => block.type === "toolCall")?.name,
	};
}

function anthropicSse(): string {
	return [
		"event: message_start",
		'data: {"type":"message_start","message":{"id":"msg-1","usage":{"input_tokens":7,"output_tokens":0}}}',
		"",
		"event: content_block_start",
		'data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}',
		"",
		"event: content_block_delta",
		'data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}',
		"",
		"event: content_block_stop",
		'data: {"type":"content_block_stop","index":0}',
		"",
		"event: content_block_start",
		'data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tool-1","name":"echo","input":{}}}',
		"",
		"event: content_block_delta",
		'data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"value\\":\\"ok\\"}"}}',
		"",
		"event: content_block_stop",
		'data: {"type":"content_block_stop","index":1}',
		"",
		"event: message_delta",
		'data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}',
		"",
		"event: message_stop",
		'data: {"type":"message_stop"}',
		"",
	].join("\n");
}
