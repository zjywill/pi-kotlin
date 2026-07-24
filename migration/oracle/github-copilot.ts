import { pathToFileURL } from "node:url";

const tsRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const { githubCopilotOAuth } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/auth/oauth/github-copilot.ts`).href
);
const { buildCopilotDynamicHeaders } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/api/github-copilot-headers.ts`).href
);
const { githubCopilotProvider } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/providers/github-copilot.ts`).href
);

type RequestRecord = {
	url: string;
	method: string;
	headers: Record<string, string>;
	body: string;
};

const requests: RequestRecord[] = [];
const token = "tid=test;proxy-ep=proxy.enterprise.githubcopilot.com;exp=900";

function jsonResponse(body: unknown, status = 200): Response {
	return new Response(JSON.stringify(body), {
		status,
		headers: { "Content-Type": "application/json" },
	});
}

globalThis.fetch = async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
	const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
	const method = init?.method ?? "GET";
	const headers = Object.fromEntries(new Headers(init?.headers).entries());
	const body = String(init?.body ?? "");
	requests.push({ url, method, headers, body });

	if (url.endsWith("/login/device/code")) {
		return jsonResponse({
			device_code: "device-code",
			user_code: "ABCD-EFGH",
			verification_uri: "https://company.ghe.com/login/device",
			interval: 1,
			expires_in: 60,
		});
	}
	if (url.endsWith("/login/oauth/access_token")) {
		return jsonResponse({ access_token: "ghu-enterprise" });
	}
	if (url.endsWith("/copilot_internal/v2/token")) {
		return jsonResponse({
			token,
			expires_at: 900,
		});
	}
	if (url.endsWith("/models")) {
		return jsonResponse({
			data: [
				{
					id: "gpt-4.1",
					model_picker_enabled: true,
					capabilities: { supports: { tool_calls: true } },
				},
				{
					id: "claude-opus-4.7",
					model_picker_enabled: true,
					policy: { state: "disabled" },
					capabilities: { supports: { tool_calls: true } },
				},
				{
					id: "gpt-5.4-nano",
					model_picker_enabled: false,
					capabilities: { supports: { tool_calls: true } },
				},
			],
		});
	}
	if (url.includes("/models/") && url.endsWith("/policy")) {
		return new Response("", { status: 200 });
	}
	throw new Error(`Unexpected GitHub Copilot request: ${url}`);
};

let promptProjection: unknown;
let deviceProjection: unknown;
const progress: string[] = [];
const credential = await githubCopilotOAuth.login({
	prompt: async (prompt) => {
		if (prompt.type !== "text") throw new Error(`Unexpected prompt: ${prompt.type}`);
		promptProjection = {
			message: prompt.message,
			placeholder: prompt.placeholder,
		};
		return "https://company.ghe.com/some/path";
	},
	notify: (event) => {
		if (event.type === "device_code") {
			deviceProjection = {
				userCode: event.userCode,
				verificationUri: event.verificationUri,
				intervalSeconds: event.intervalSeconds,
				expiresInSeconds: event.expiresInSeconds,
			};
		}
		if (event.type === "progress") progress.push(event.message);
	},
});
const auth = await githubCopilotOAuth.toAuth(credential);
const provider = githubCopilotProvider();
const filtered = provider.filterModels?.(provider.getModels(), credential) ?? [];
const policyRequests = requests
	.filter((request) => request.url.endsWith("/policy"))
	.map((request) => decodeURIComponent(request.url.match(/\/models\/(.+)\/policy$/)?.[1] ?? ""))
	.sort();

function requestAt(suffix: string): RequestRecord {
	const request = requests.find((entry) => entry.url.endsWith(suffix));
	if (!request) throw new Error(`Missing request ending in ${suffix}`);
	return request;
}

function form(body: string): Record<string, string> {
	return Object.fromEntries(new URLSearchParams(body).entries());
}

function header(request: RequestRecord, name: string): string | undefined {
	return request.headers[name.toLowerCase()];
}

const allModels = provider.getModels();
const countByApi = Object.fromEntries(
	["anthropic-messages", "openai-completions", "openai-responses"].map((api) => [
		api,
		allModels.filter((model: { api: string }) => model.api === api).length,
	]),
);

console.log(
	JSON.stringify(
		{
			login: {
				prompt: promptProjection,
				device: deviceProjection,
				progress,
				credential: {
					type: credential.type,
					access: credential.access,
					refresh: credential.refresh,
					expires: credential.expires,
					enterpriseUrl: credential.enterpriseUrl,
					availableModelIds: credential.availableModelIds,
				},
			},
			requests: {
				device: {
					url: requestAt("/login/device/code").url,
					method: requestAt("/login/device/code").method,
					form: form(requestAt("/login/device/code").body),
				},
				access: {
					url: requestAt("/login/oauth/access_token").url,
					method: requestAt("/login/oauth/access_token").method,
					form: form(requestAt("/login/oauth/access_token").body),
				},
				token: {
					url: requestAt("/copilot_internal/v2/token").url,
					method: requestAt("/copilot_internal/v2/token").method,
					authorization: header(requestAt("/copilot_internal/v2/token"), "authorization"),
				},
				policy: {
					count: policyRequests.length,
					models: policyRequests,
				},
				models: {
					url: requestAt("/models").url,
					method: requestAt("/models").method,
					authorization: header(requestAt("/models"), "authorization"),
				},
			},
			auth,
			catalog: {
				total: allModels.length,
				countByApi,
				filtered: filtered.map((model: { id: string }) => model.id),
			},
			dynamicHeaders: {
				empty: buildCopilotDynamicHeaders({ messages: [], hasImages: false }),
				agent: buildCopilotDynamicHeaders({
					messages: [
						{
							role: "assistant",
							content: [],
							api: "openai-responses",
							provider: "github-copilot",
							model: "gpt-5.4",
							usage: {
								input: 0,
								output: 0,
								cacheRead: 0,
								cacheWrite: 0,
								totalTokens: 0,
								cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
							},
							stopReason: "stop",
							timestamp: 0,
						},
					],
					hasImages: false,
				}),
				vision: buildCopilotDynamicHeaders({
					messages: [{ role: "user", content: "inspect", timestamp: 0 }],
					hasImages: true,
				}),
			},
		},
		null,
		2,
	),
);
