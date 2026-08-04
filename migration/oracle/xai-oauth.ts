import { createServer } from "node:http";
import { pathToFileURL } from "node:url";

const tsRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const { xaiOAuth } = await import(pathToFileURL(`${tsRoot}/packages/ai/src/auth/oauth/xai.ts`).href);
const { InMemoryCredentialStore } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/auth/credential-store.ts`).href
);
const { createModels } = await import(pathToFileURL(`${tsRoot}/packages/ai/src/models.ts`).href);
const { xaiProvider } = await import(pathToFileURL(`${tsRoot}/packages/ai/src/providers/xai.ts`).href);
const oauthSignal = new AbortController().signal;

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

	if (url.endsWith("/device/code")) {
		return jsonResponse({
			device_code: "device-code",
			user_code: "ABCD-1234",
			verification_uri: "https://accounts.x.ai/oauth2/device",
			verification_uri_complete: "https://accounts.x.ai/oauth2/device?user_code=ABCD-1234",
			expires_in: 60,
			interval: 1,
		});
	}
	if (url.endsWith("/token")) {
		const grantType = form(request.body).grant_type;
		if (grantType === "refresh_token") {
			return jsonResponse({
				access_token: "refreshed-access",
			});
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
			expires_in: 21_600,
		});
	}
	throw new Error(`Unexpected xAI OAuth request: ${url}`);
};

let deviceEvent: Record<string, unknown> | undefined;
let loginCredential;
let refreshedCredential;
let auth;
try {
	loginCredential = await xaiOAuth.login({
		signal: oauthSignal,
		prompt: async (prompt) => {
			throw new Error(`Unexpected xAI prompt: ${prompt.type}`);
		},
		notify: (event) => {
			if (event.type === "device_code") {
				const { type: _type, ...projection } = event;
				deviceEvent = projection;
			}
		},
	});
	refreshedCredential = await xaiOAuth.refresh(loginCredential, oauthSignal);
	auth = await xaiOAuth.toAuth(refreshedCredential);
} finally {
	globalThis.fetch = originalFetch;
	globalThis.setTimeout = originalSetTimeout;
}

if (!deviceEvent || !loginCredential || !refreshedCredential || !auth) {
	throw new Error("xAI OAuth oracle did not complete");
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
	const sse = request.url?.endsWith("/chat/completions") ? chatSse() : responsesSse();
	response.writeHead(200, { "content-type": "text/event-stream" });
	response.end(sse);
});

try {
	await new Promise<void>((resolve) => fixture.listen(0, "127.0.0.1", resolve));
	const address = fixture.address();
	if (!address || typeof address === "string") throw new Error("xAI provider fixture did not bind");
	const baseUrl = `http://127.0.0.1:${address.port}`;
	const credentials = new InMemoryCredentialStore();
	await credentials.modify("xai", async () => refreshedCredential);
	const models = createModels({ credentials });
	const provider = xaiProvider();
	models.setProvider(provider);
	const chatModel = {
		...requiredModel(provider.getModels(), "grok-4.3"),
		baseUrl,
	};
	const responsesModel = {
		...requiredModel(provider.getModels(), "grok-4.5"),
		baseUrl,
	};
	const context = {
		messages: [{ role: "user" as const, content: "hi", timestamp: 1 }],
		tools: [
			{
				name: "echo",
				description: "Echo",
				parameters: { type: "object" },
			},
		],
	};
	const streamOptions = {
		cacheRetention: "none" as const,
		maxRetries: 0,
		maxTokens: 64,
	};
	const chatResult = await models.complete(chatModel, context, streamOptions);
	const responsesResult = await models.complete(responsesModel, context, streamOptions);
	const deviceRequest = oauthRequests.find((request) => request.url.endsWith("/device/code"));
	const pollRequests = oauthRequests.filter(
		(request) => form(request.body).grant_type === "urn:ietf:params:oauth:grant-type:device_code",
	);
	const refreshRequest = oauthRequests.find((request) => form(request.body).grant_type === "refresh_token");
	if (!deviceRequest || pollRequests.length === 0 || !refreshRequest) {
		throw new Error("xAI OAuth requests were not captured");
	}

	console.log(
		JSON.stringify(
			{
				login: {
					name: xaiOAuth.name,
					loginLabel: xaiOAuth.loginLabel,
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
					refresh: requestProjection(refreshRequest),
				},
				refresh: {
					credential: refreshedCredential,
					auth,
				},
				provider: providerRequests.map((request, index) => ({
					api: index === 0 ? "openai-completions" : "openai-responses",
					path: request.path,
					authorization: request.headers.authorization,
					body: request.body,
					result: resultProjection(index === 0 ? chatResult : responsesResult),
				})),
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
	if (!model) throw new Error(`Missing xAI model: ${id}`);
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

function chatSse(): string {
	return [
		'data: {"choices":[{"delta":{"content":"hello "}}]}',
		'data: {"choices":[{"delta":{"content":"world"}}]}',
		'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"echo","arguments":"{\\"value\\":\\"ok\\"}"}}]},"finish_reason":"tool_calls"}]}',
		'data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4,"prompt_tokens_details":{"cached_tokens":2}}}',
		"data: [DONE]",
		"",
	].join("\n\n");
}

function responsesSse(): string {
	return [
		'data: {"type":"response.created","response":{"id":"resp-1"}}',
		'data: {"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"msg-1","content":[]}}',
		'data: {"type":"response.output_text.delta","output_index":0,"delta":"responses"}',
		'data: {"type":"response.output_item.done","output_index":0,"item":{"type":"message","id":"msg-1","content":[{"type":"output_text","text":"responses"}]}}',
		'data: {"type":"response.completed","response":{"id":"resp-1","status":"completed","usage":{"input_tokens":12,"output_tokens":3,"total_tokens":15,"input_tokens_details":{"cached_tokens":2},"output_tokens_details":{"reasoning_tokens":1}},"output":[]}}',
		"",
	].join("\n\n");
}
