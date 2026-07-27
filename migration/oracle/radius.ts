import { createHash } from "node:crypto";
import { createServer } from "node:http";
import { pathToFileURL } from "node:url";

const tsRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const { createRadiusOAuth } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/auth/oauth/radius.ts`).href
);
const { InMemoryCredentialStore } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/auth/credential-store.ts`).href
);
const { createModels } = await import(pathToFileURL(`${tsRoot}/packages/ai/src/models.ts`).href);
const { InMemoryModelsStore } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/models-store.ts`).href
);
const { radiusProvider } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/providers/radius.ts`).href
);

await new Promise<void>((resolve) => setImmediate(resolve));

type RequestRecord = {
	url: string;
	method: string;
	headers: Record<string, string>;
	body: string;
};

type PromptProjection = {
	message: string;
	options: { id: string; label: string }[];
};

const originalFetch = globalThis.fetch;
const originalSetTimeout = globalThis.setTimeout;
const originalDateNow = Date.now;
const oauthRequests: RequestRecord[] = [];
const browserEvents: Record<string, unknown>[] = [];
const deviceEvents: Record<string, unknown>[] = [];
const sleeps: number[] = [];
const pollTimes: number[] = [];
let browserPrompt: PromptProjection | undefined;
let devicePrompt: PromptProjection | undefined;
let devicePoll = 0;
let currentTime = 1_000_000;

Date.now = () => currentTime;
globalThis.fetch = async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
	const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
	if (url.startsWith("http://127.0.0.1:1456/oauth/callback")) {
		return originalFetch(input, init);
	}
	const request = {
		url,
		method: init?.method ?? "GET",
		headers: Object.fromEntries(new Headers(init?.headers).entries()),
		body: String(init?.body ?? ""),
	};
	oauthRequests.push(request);
	if (url.endsWith("/v1/oauth")) return jsonResponse(oauthDiscovery());
	if (url.endsWith("/device")) {
		return jsonResponse({
			device_code: "device-code",
			user_code: "ABCD-1234",
			verification_uri: "https://verify.example/device",
			verification_uri_complete: "https://verify.example/device?code=ABCD-1234",
			expires_in: 120,
			interval: 0.25,
		});
	}
	if (url.endsWith("/token")) {
		const fields = form(request.body);
		if (fields.grant_type === "authorization_code") {
			return jsonResponse({
				access_token: "browser-access",
				refresh_token: "browser-refresh",
				expires_in: 3600,
				scope: "openid profile",
			});
		}
		if (fields.grant_type === "refresh_token") {
			return jsonResponse({
				access_token: "refreshed-access",
				refresh_token: "refreshed-refresh",
				expires_in: 600,
				scope: "scope-2",
			});
		}
		pollTimes.push(Date.now());
		if (devicePoll++ === 0) return jsonResponse({ error: "authorization_pending" }, 400);
		if (devicePoll === 2) return jsonResponse({ error: "slow_down" }, 400);
		return jsonResponse({
			access_token: "device-access",
			refresh_token: "device-refresh",
			expires_in: 3600,
			scope: "openid profile",
		});
	}
	throw new Error(`Unexpected Radius OAuth request: ${url}`);
};

const browserOAuth = createRadiusOAuth({ name: "Radius", gateway: "radius.example/" });
const browserCredential = await browserOAuth.login({
	prompt: async (prompt) => {
		if (prompt.type !== "select") throw new Error(`Unexpected browser prompt: ${prompt.type}`);
		browserPrompt = {
			message: prompt.message,
			options: prompt.options.map(({ id, label }) => ({ id, label })),
		};
		return "browser";
	},
	notify: (event) => {
		browserEvents.push(eventProjection(event));
		if (event.type === "auth_url") {
			const state = new URL(event.url).searchParams.get("state");
			queueMicrotask(() => {
				void originalFetch(
					`http://127.0.0.1:1456/oauth/callback?code=browser-code&state=${encodeURIComponent(state ?? "")}`,
				);
			});
		}
	},
});

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

const deviceOAuth = createRadiusOAuth({ name: "Radius", gateway: "radius.example/" });
const deviceCredential = await deviceOAuth.login({
	prompt: async (prompt) => {
		if (prompt.type !== "select") throw new Error(`Unexpected device prompt: ${prompt.type}`);
		devicePrompt = {
			message: prompt.message,
			options: prompt.options.map(({ id, label }) => ({ id, label })),
		};
		return "device-code";
	},
	notify: (event) => deviceEvents.push(eventProjection(event)),
});
const refreshedCredential = await deviceOAuth.refresh(deviceCredential);
const requestAuth = await deviceOAuth.toAuth(refreshedCredential);

globalThis.fetch = originalFetch;
globalThis.setTimeout = originalSetTimeout;
Date.now = originalDateNow;

const providerRequests: {
	path: string;
	headers: Record<string, string | string[] | undefined>;
	body?: Record<string, unknown>;
}[] = [];
const fixture = createServer(async (request, response) => {
	const chunks: Buffer[] = [];
	for await (const chunk of request) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
	const body = Buffer.concat(chunks).toString("utf8");
	providerRequests.push({
		path: request.url ?? "",
		headers: request.headers,
		body: body ? (JSON.parse(body) as Record<string, unknown>) : undefined,
	});
	const origin = `http://127.0.0.1:${(fixture.address() as { port: number }).port}`;
	if (request.url === "/v1/config") {
		response.writeHead(200, { "content-type": "application/json" });
		response.end(
			JSON.stringify({
				baseUrl: `${origin}/v1`,
				models: [radiusModel()],
			}),
		);
		return;
	}
	if (request.url === "/v1/messages?debug=1") {
		response.writeHead(200, {
			"content-type": "text/event-stream",
			"x-pi-gateway-upstream-provider": "anthropic",
		});
		response.end(piMessagesSse());
		return;
	}
	response.writeHead(404);
	response.end();
});

let providerOutput: Record<string, unknown>;
try {
	await new Promise<void>((resolve) => fixture.listen(0, "127.0.0.1", resolve));
	const address = fixture.address();
	if (!address || typeof address === "string") throw new Error("Radius fixture did not bind");
	const gateway = `http://127.0.0.1:${address.port}`;
	const credentials = new InMemoryCredentialStore();
	await credentials.modify("radius", async () => ({
		...refreshedCredential,
		expires: Date.now() + 60_000,
	}));
	const store = new InMemoryModelsStore();
	const models = createModels({ credentials, modelsStore: store });
	const provider = radiusProvider({ gateway });
	models.setProvider(provider);
	const refresh = await models.refresh({ allowNetwork: true });
	const model = models.getModel("radius", "auto");
	if (!model) throw new Error("Radius model was not refreshed");
	let responseProvider: string | undefined;
	const eventStream = models.stream(
		model,
		{
			systemPrompt: "Project instructions",
			messages: [{ role: "user", content: "hi", timestamp: 1 }],
			tools: [{ name: "echo", description: "Echo", parameters: { type: "object" } }],
		},
		{
			debug: true,
			maxTokens: 64,
			reasoning: "high",
			sessionId: "session-1",
			toolChoice: "auto",
			headers: { "x-custom": "1" },
			onResponse: (response) => {
				responseProvider = response.headers["x-pi-gateway-upstream-provider"];
			},
		},
	);
	const events: Record<string, unknown>[] = [];
	for await (const event of eventStream) events.push(streamEventProjection(event));
	const result = await eventStream.result();

	const legacyCredentials = new InMemoryCredentialStore();
	await legacyCredentials.modify("radius", async () => ({
		type: "oauth",
		access: "legacy-access",
		refresh: "legacy-refresh",
		expires: Date.now() + 60_000,
		gatewayConfig: {
			baseUrl: "https://legacy.example/v1",
			models: [radiusModel()],
		},
	}));
	const legacyModels = createModels({
		credentials: legacyCredentials,
		modelsStore: new InMemoryModelsStore(),
	});
	legacyModels.setProvider(radiusProvider({ gateway: "http://127.0.0.1:1" }));
	await legacyModels.refresh({ allowNetwork: false });
	const legacyModel = legacyModels.getModel("radius", "auto");
	if (!legacyModel) throw new Error("Legacy Radius model was not restored");

	const configRequest = providerRequests.find((request) => request.path === "/v1/config");
	const messageRequest = providerRequests.find((request) => request.path === "/v1/messages?debug=1");
	if (!configRequest || !messageRequest?.body) throw new Error("Radius provider requests were not captured");
	providerOutput = {
		refreshErrors: [...refresh.errors.keys()],
		model: modelProjection(model),
		storedModels: (await store.read("radius"))?.models.map(modelProjection),
		legacyModel: modelProjection(legacyModel),
		configRequest: {
			path: configRequest.path,
			authorization: configRequest.headers.authorization,
			accept: configRequest.headers.accept,
		},
		messageRequest: {
			path: messageRequest.path,
			authorization: messageRequest.headers.authorization,
			accept: messageRequest.headers.accept,
			contentType: messageRequest.headers["content-type"],
			custom: messageRequest.headers["x-custom"],
			body: messageRequest.body,
		},
		responseProvider,
		events,
		result: resultProjection(result),
	};
} finally {
	await new Promise<void>((resolve) => fixture.close(() => resolve()));
}

const browserTokenRequest = requiredRequest(
	(request) => form(request.body).grant_type === "authorization_code",
);
const browserAuthUrl = String(
	browserEvents.find((event) => event.type === "auth_url")?.url ?? "",
);
const browserQuery = Object.fromEntries(new URL(browserAuthUrl).searchParams.entries());
const deviceRequest = requiredRequest((request) => request.url.endsWith("/device"));
const pollRequests = oauthRequests.filter(
	(request) =>
		form(request.body).grant_type ===
		"urn:ietf:params:oauth:grant-type:device_code",
);
const refreshRequest = requiredRequest(
	(request) => form(request.body).grant_type === "refresh_token",
);
const browserForm = form(browserTokenRequest.body);

console.log(
	JSON.stringify(
		{
			browser: {
				prompt: browserPrompt,
				events: browserEvents.map(normalizeBrowserEvent),
				authorization: {
					responseType: browserQuery.response_type,
					clientId: browserQuery.client_id,
					redirectUri: browserQuery.redirect_uri,
					scope: browserQuery.scope,
					challengeMethod: browserQuery.code_challenge_method,
					handoff: browserQuery.handoff,
					hasState: Boolean(browserQuery.state),
					challengeMatchesVerifier:
						browserQuery.code_challenge === sha256Base64Url(browserForm.code_verifier ?? ""),
				},
				request: browserRequestProjection(browserTokenRequest),
				credential: credentialProjection(browserCredential),
			},
			device: {
				prompt: devicePrompt,
				events: deviceEvents,
				sleeps,
				pollTimes,
				request: requestProjection(deviceRequest),
				poll: {
					count: pollRequests.length,
					request: requestProjection(pollRequests[0]),
				},
				credential: credentialProjection(deviceCredential),
			},
			refresh: {
				request: requestProjection(refreshRequest),
				credential: credentialProjection(refreshedCredential),
				auth: requestAuth,
			},
			provider: providerOutput,
		},
		null,
		2,
	),
);

function oauthDiscovery(): Record<string, unknown> {
	return {
		authorizationEndpoint: "https://oauth.example/authorize",
	};
}

function radiusModel(): Record<string, unknown> {
	return {
		id: "auto",
		name: "Radius Auto",
		reasoning: true,
		thinkingLevelMap: { off: null, high: "high" },
		input: ["text"],
		cost: { input: 1, output: 2, cacheRead: 0.1, cacheWrite: 0.2 },
		contextWindow: 128000,
		maxTokens: 16384,
	};
}

function piMessagesSse(): string {
	const usage = {
		input: 10,
		output: 5,
		cacheRead: 0,
		cacheWrite: 0,
		totalTokens: 15,
		cost: { input: 0.1, output: 0.2, cacheRead: 0, cacheWrite: 0, total: 0.3 },
	};
	return [
		'data: {"type":"start"}',
		'data: {"type":"text_start","contentIndex":0}',
		'data: {"type":"text_delta","contentIndex":0,"delta":"hello"}',
		'data: {"type":"text_end","contentIndex":0,"content":"hello","contentSignature":"text-sig"}',
		'data: {"type":"toolcall_start","contentIndex":1,"id":"call-1","toolName":"echo"}',
		'data: {"type":"toolcall_delta","contentIndex":1,"delta":"{\\"value\\":"}',
		'data: {"type":"toolcall_delta","contentIndex":1,"delta":"\\"ok\\"}"}',
		'data: {"type":"toolcall_end","contentIndex":1,"toolCall":{"type":"toolCall","id":"call-1","name":"echo","arguments":{"value":"ok"}}}',
		`data: ${JSON.stringify({
			type: "done",
			reason: "toolUse",
			usage,
			responseId: "response-1",
			rewrite: {
				policyId: "policy-1",
				policyVersion: 2,
				changed: true,
				tokenCountChange: -3,
				messageCountChange: 0,
				systemPromptChanged: false,
			},
		})}`,
		"",
	].join("\n\n");
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
	if (!request) throw new Error("Missing Radius OAuth request");
	return request;
}

function requestProjection(request: RequestRecord | undefined): Record<string, unknown> {
	if (!request) throw new Error("Missing Radius OAuth request projection");
	return {
		url: request.url,
		method: request.method,
		accept: request.headers.accept,
		contentType: request.headers["content-type"],
		form: form(request.body),
	};
}

function browserRequestProjection(request: RequestRecord): Record<string, unknown> {
	const fields = form(request.body);
	if (fields.code_verifier) fields.code_verifier = "<pkce>";
	return {
		url: request.url,
		method: request.method,
		accept: request.headers.accept,
		contentType: request.headers["content-type"],
		form: fields,
	};
}

function eventProjection(event: any): Record<string, unknown> {
	if (event.type === "auth_url") return { type: event.type, url: event.url, instructions: event.instructions };
	if (event.type === "device_code") return { ...event };
	return { type: event.type, message: event.message };
}

function normalizeBrowserEvent(event: Record<string, unknown>): Record<string, unknown> {
	if (event.type !== "auth_url") return event;
	return {
		type: "auth_url",
		instructions: event.instructions,
		url: "https://oauth.example/authorize",
	};
}

function credentialProjection(credential: any): Record<string, unknown> {
	return {
		type: "oauth",
		access: credential.access,
		refresh: credential.refresh,
		expires: credential.expires,
		scope: credential.scope,
	};
}

function modelProjection(model: any): Record<string, unknown> {
	return {
		id: model.id,
		name: model.name,
		api: model.api,
		provider: model.provider,
		baseUrl: String(model.baseUrl).replace(/http:\/\/127\.0\.0\.1:\d+/u, "http://127.0.0.1:<port>"),
		reasoning: model.reasoning,
		thinkingLevelMap: model.thinkingLevelMap,
		input: model.input,
		cost: model.cost,
		contextWindow: model.contextWindow,
		maxTokens: model.maxTokens,
	};
}

function streamEventProjection(event: any): Record<string, unknown> {
	const output: Record<string, unknown> = { type: event.type };
	if ("contentIndex" in event) output.contentIndex = event.contentIndex;
	if ("delta" in event) output.delta = event.delta;
	if ("content" in event) output.content = event.content;
	if ("toolCall" in event) output.toolCall = event.toolCall;
	if (event.type === "done") output.reason = event.reason;
	if (event.type === "error") output.reason = event.reason;
	return output;
}

function resultProjection(message: any): Record<string, unknown> {
	return {
		stopReason: message.stopReason,
		responseId: message.responseId,
		content: message.content,
		usage: message.usage,
		diagnostics: message.diagnostics?.map((diagnostic: any) => ({
			type: diagnostic.type,
			details: diagnostic.details,
		})),
	};
}

function sha256Base64Url(value: string): string {
	return createHash("sha256").update(value).digest("base64url");
}
