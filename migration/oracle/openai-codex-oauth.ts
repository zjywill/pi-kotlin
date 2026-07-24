import { pathToFileURL } from "node:url";

const tsRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const { openaiCodexOAuth } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/auth/oauth/openai-codex.ts`).href
);

type RequestRecord = {
	url: string;
	headers: Record<string, string>;
	body: string;
};

const requests: RequestRecord[] = [];

function accessToken(accountId: string): string {
	const payload = Buffer.from(
		JSON.stringify({
			"https://api.openai.com/auth": {
				chatgpt_account_id: accountId,
			},
		}),
	).toString("base64url");
	return `header.${payload}.signature`;
}

function jsonResponse(body: unknown, status = 200): Response {
	return new Response(JSON.stringify(body), {
		status,
		headers: { "Content-Type": "application/json" },
	});
}

globalThis.fetch = async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
	const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
	const headers = Object.fromEntries(new Headers(init?.headers).entries());
	const body = String(init?.body ?? "");
	requests.push({ url, headers, body });

	if (url.endsWith("/api/accounts/deviceauth/usercode")) {
		return jsonResponse({
			device_auth_id: "device-auth-id",
			user_code: "ABCD-1234",
			interval: "5",
		});
	}
	if (url.endsWith("/api/accounts/deviceauth/token")) {
		return jsonResponse({
			authorization_code: "device-code",
			code_verifier: "device-verifier",
		});
	}
	if (url.endsWith("/oauth/token")) {
		const form = new URLSearchParams(body);
		const grant = form.get("grant_type");
		if (grant === "refresh_token") {
			return jsonResponse({
				access_token: accessToken("refresh-account"),
				refresh_token: "rotated-refresh",
				expires_in: 60,
			});
		}
		const code = form.get("code");
		if (code === "browser-code") {
			return jsonResponse({
				access_token: accessToken("browser-account"),
				refresh_token: "browser-refresh",
				expires_in: 3600,
			});
		}
		if (code === "device-code") {
			return jsonResponse({
				access_token: accessToken("device-account"),
				refresh_token: "device-refresh",
				expires_in: 3600,
			});
		}
	}
	throw new Error(`Unexpected OAuth request: ${url}`);
};

let browserUrl = "";
let browserPrompt: unknown;
const browserStart = Date.now();
const browserCredential = await openaiCodexOAuth.login({
	prompt: async (prompt) => {
		if (prompt.type === "select") {
			browserPrompt = {
				message: prompt.message,
				options: prompt.options.map(({ id, label }) => ({ id, label })),
			};
			return "browser";
		}
		if (prompt.type === "manual_code") {
			const state = new URL(browserUrl).searchParams.get("state");
			return `http://localhost:1455/auth/callback?code=browser-code&state=${state}`;
		}
		throw new Error(`Unexpected browser prompt: ${prompt.type}`);
	},
	notify: (event) => {
		if (event.type === "auth_url") browserUrl = event.url;
	},
});

let devicePrompt: unknown;
let deviceEvent: unknown;
const deviceStart = Date.now();
const deviceCredential = await openaiCodexOAuth.login({
	prompt: async (prompt) => {
		if (prompt.type !== "select") throw new Error(`Unexpected device prompt: ${prompt.type}`);
		devicePrompt = {
			message: prompt.message,
			options: prompt.options.map(({ id, label }) => ({ id, label })),
		};
		return "device_code";
	},
	notify: (event) => {
		if (event.type === "device_code") {
			deviceEvent = {
				userCode: event.userCode,
				verificationUri: event.verificationUri,
				intervalSeconds: event.intervalSeconds,
				expiresInSeconds: event.expiresInSeconds,
			};
		}
	},
});

const refreshStart = Date.now();
const refreshCredential = await openaiCodexOAuth.refresh({
	type: "oauth",
	access: "old-access",
	refresh: "old-refresh",
	expires: 0,
});

const authorization = new URL(browserUrl);
const browserExchange = requests.find((request) => new URLSearchParams(request.body).get("code") === "browser-code")!;
const browserForm = new URLSearchParams(browserExchange.body);
const verifier = browserForm.get("code_verifier")!;
const challenge = Buffer.from(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))).toString(
	"base64url",
);
const userCodeRequest = requests.find((request) => request.url.endsWith("/deviceauth/usercode"))!;
const pollRequest = requests.find((request) => request.url.endsWith("/deviceauth/token"))!;
const deviceExchange = requests.find((request) => new URLSearchParams(request.body).get("code") === "device-code")!;
const refreshRequest = requests.find(
	(request) => new URLSearchParams(request.body).get("grant_type") === "refresh_token",
)!;

function formProjection(request: RequestRecord): Record<string, string> {
	return Object.fromEntries(new URLSearchParams(request.body).entries());
}

function credentialProjection(
	credential: {
		type: string;
		access: string;
		refresh: string;
		expires: number;
		accountId?: unknown;
	},
	start: number,
) {
	return {
		type: credential.type,
		access: credential.access,
		refresh: credential.refresh,
		expiresInSeconds: Math.round((credential.expires - start) / 1000),
		accountId: credential.accountId,
	};
}

process.stdout.write(
	JSON.stringify(
		{
			browser: {
				prompt: browserPrompt,
				authorization: {
					response_type: authorization.searchParams.get("response_type"),
					client_id: authorization.searchParams.get("client_id"),
					redirect_uri: authorization.searchParams.get("redirect_uri"),
					scope: authorization.searchParams.get("scope"),
					code_challenge_method: authorization.searchParams.get("code_challenge_method"),
					stateLength: authorization.searchParams.get("state")?.length,
					challengeLength: authorization.searchParams.get("code_challenge")?.length,
					id_token_add_organizations: authorization.searchParams.get("id_token_add_organizations"),
					codex_cli_simplified_flow: authorization.searchParams.get("codex_cli_simplified_flow"),
					originator: authorization.searchParams.get("originator"),
				},
				exchange: {
					grant_type: browserForm.get("grant_type"),
					client_id: browserForm.get("client_id"),
					code: browserForm.get("code"),
					redirect_uri: browserForm.get("redirect_uri"),
					verifierLength: verifier.length,
					challengeMatches: challenge === authorization.searchParams.get("code_challenge"),
				},
				credential: credentialProjection(browserCredential, browserStart),
			},
			device: {
				prompt: devicePrompt,
				event: deviceEvent,
				userCodeRequest: JSON.parse(userCodeRequest.body),
				pollRequest: JSON.parse(pollRequest.body),
				exchange: formProjection(deviceExchange),
				credential: credentialProjection(deviceCredential, deviceStart),
			},
			refresh: {
				request: formProjection(refreshRequest),
				credential: credentialProjection(refreshCredential, refreshStart),
				auth: await openaiCodexOAuth.toAuth(refreshCredential),
			},
		},
		null,
		2,
	) + "\n",
);
