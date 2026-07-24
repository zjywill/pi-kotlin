import { createServer } from "node:http";
import { pathToFileURL } from "node:url";

const tsRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const { openRouterOAuth } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/auth/oauth/openrouter.ts`).href
);
const { stream } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/api/openai-completions.ts`).href
);

const tokenUrl = "https://openrouter.ai/api/v1/auth/keys";
const originalFetch = globalThis.fetch;
let tokenRequest:
	| {
			url: string;
			method: string;
			headers: Record<string, string>;
			body: Record<string, unknown>;
	  }
	| undefined;

globalThis.fetch = async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
	const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
	if (url !== tokenUrl) return originalFetch(input, init);
	tokenRequest = {
		url,
		method: init?.method ?? "GET",
		headers: Object.fromEntries(new Headers(init?.headers).entries()),
		body: JSON.parse(String(init?.body ?? "{}")) as Record<string, unknown>,
	};
	return new Response(JSON.stringify({ key: "openrouter-oauth-key" }), {
		status: 200,
		headers: { "content-type": "application/json" },
	});
};

let progressMessage = "";
let authorizationUrl = "";
let authorizationInstructions: string | undefined;
let callbackResponsePromise:
	| Promise<{ status: number; contentType: string | null; cacheControl: string | null; success: boolean }>
	| undefined;
let credential;
try {
	credential = await openRouterOAuth.login({
		prompt: async (prompt) => {
			throw new Error(`Unexpected OpenRouter prompt: ${prompt.type}`);
		},
		notify: (event) => {
			if (event.type === "progress") progressMessage = event.message;
			if (event.type === "auth_url") {
				authorizationUrl = event.url;
				authorizationInstructions = event.instructions;
				const callbackUrl = new URL(event.url).searchParams.get("callback_url");
				if (!callbackUrl) throw new Error("OpenRouter callback URL missing");
				callbackResponsePromise = originalFetch(`${callbackUrl}?code=oracle-code`).then(async (response) => ({
					status: response.status,
					contentType: response.headers.get("content-type"),
					cacheControl: response.headers.get("cache-control"),
					success: (await response.text()).includes("Signed in to OpenRouter"),
				}));
			}
		},
	});
} finally {
	globalThis.fetch = originalFetch;
}
if (!tokenRequest || !callbackResponsePromise) throw new Error("OpenRouter OAuth request was not captured");
const callbackResponse = await callbackResponsePromise;
const refreshed = await openRouterOAuth.refresh(credential);
const auth = await openRouterOAuth.toAuth(credential);

type ProviderRequest = {
	path: string;
	headers: Record<string, string | string[] | undefined>;
	body: Record<string, unknown>;
};

let providerRequest: ProviderRequest | undefined;
const fixture = createServer(async (request, response) => {
	const chunks: Buffer[] = [];
	for await (const chunk of request) {
		chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
	}
	providerRequest = {
		path: request.url ?? "",
		headers: request.headers,
		body: JSON.parse(Buffer.concat(chunks).toString("utf8")) as Record<string, unknown>,
	};
	const sse = [
		'data: {"choices":[{"delta":{"content":"hello "}}]}',
		'data: {"choices":[{"delta":{"content":"world"}}]}',
		'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"echo","arguments":"{\\"value\\":\\"ok\\"}"}}]},"finish_reason":"tool_calls"}]}',
		'data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4,"prompt_tokens_details":{"cached_tokens":2}}}',
		"data: [DONE]",
		"",
	].join("\n\n");
	response.writeHead(200, { "content-type": "text/event-stream" });
	response.end(sse);
});
await new Promise<void>((resolve) => fixture.listen(0, "127.0.0.1", resolve));
const fixtureAddress = fixture.address();
if (!fixtureAddress || typeof fixtureAddress === "string") throw new Error("Provider fixture did not bind");

const model = {
	id: "openrouter-test",
	name: "OpenRouter Test",
	api: "openai-completions" as const,
	provider: "openrouter",
	baseUrl: `http://127.0.0.1:${fixtureAddress.port}`,
	reasoning: false,
	input: ["text"] as const,
	cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
	contextWindow: 128000,
	maxTokens: 16384,
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
let terminal: any;
for await (const event of stream(model, context, {
	apiKey: credential.access,
	cacheRetention: "none",
	maxRetries: 0,
})) {
	if (event.type === "done" || event.type === "error") terminal = event;
}
await new Promise<void>((resolve) => fixture.close(() => resolve()));
if (!providerRequest) throw new Error("Provider request was not captured");

const authorization = new URL(authorizationUrl);
const callback = new URL(authorization.searchParams.get("callback_url")!);
const verifier = String(tokenRequest.body.code_verifier);
const challenge = Buffer.from(
	await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)),
).toString("base64url");
const content = terminal?.message?.content ?? [];

console.log(
	JSON.stringify(
		{
			login: {
				name: openRouterOAuth.name,
				loginLabel: openRouterOAuth.loginLabel,
				events: {
					progressMatchesCallback: progressMessage.endsWith(callback.href),
					instructions: authorizationInstructions,
				},
				authorization: {
					protocol: authorization.protocol,
					host: authorization.host,
					path: authorization.pathname,
					callbackHost: callback.hostname,
					callbackPortPositive: Number(callback.port) > 0,
					callbackPathPrefix: callback.pathname.startsWith("/oauth/callback/"),
					callbackIdLength: callback.pathname.split("/").at(-1)?.length,
					codeChallengeMethod: authorization.searchParams.get("code_challenge_method"),
					challengeLength: authorization.searchParams.get("code_challenge")?.length,
					challengeMatches: challenge === authorization.searchParams.get("code_challenge"),
				},
				request: {
					url: tokenRequest.url,
					method: tokenRequest.method,
					accept: tokenRequest.headers.accept,
					contentType: tokenRequest.headers["content-type"],
					code: tokenRequest.body.code,
					codeChallengeMethod: tokenRequest.body.code_challenge_method,
					verifierLength: verifier.length,
				},
				callback: callbackResponse,
				credential,
				refreshUnchanged: JSON.stringify(refreshed) === JSON.stringify(credential),
				auth,
			},
			provider: {
				path: providerRequest.path,
				authorization: providerRequest.headers.authorization,
				body: providerRequest.body,
				result: {
					stopReason: terminal?.reason,
					text: content
						.filter((block: any) => block.type === "text")
						.map((block: any) => block.text)
						.join(""),
					toolName: content.find((block: any) => block.type === "toolCall")?.name,
				},
			},
		},
		null,
		2,
	),
);
