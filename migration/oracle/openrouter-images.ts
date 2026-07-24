import { createHash } from "node:crypto";
import { createServer } from "node:http";
import { pathToFileURL } from "node:url";

const tsRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const { IMAGE_MODELS } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/image-models.generated.ts`).href
);
const { generateImages } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/api/openrouter-images.ts`).href
);
const { createImagesModels } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/images-models.ts`).href
);
const { InMemoryCredentialStore } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/auth/credential-store.ts`).href
);
const { openrouterImagesProvider } = await import(
	pathToFileURL(`${tsRoot}/packages/ai/src/providers/openrouter-images.ts`).href
);

type CapturedRequest = {
	path: string;
	authorization?: string;
	modelHeader?: string;
	removedHeader?: string;
	requestHeader?: string;
	body: Record<string, unknown>;
};

const requests: CapturedRequest[] = [];
let retryAttempts = 0;
const fixture = createServer(async (request, response) => {
	const chunks: Buffer[] = [];
	for await (const chunk of request) {
		chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
	}
	const captured = {
		path: request.url ?? "",
		authorization: request.headers.authorization,
		modelHeader: headerValue(request.headers["x-model"]),
		removedHeader: headerValue(request.headers["x-remove"]),
		requestHeader: headerValue(request.headers["x-request"]),
		body: JSON.parse(Buffer.concat(chunks).toString("utf8")) as Record<string, unknown>,
	} satisfies CapturedRequest;
	requests.push(captured);

	const oracleCase = headerValue(request.headers["x-oracle-case"]);
	if (oracleCase === "error") {
		response.writeHead(403, { "content-type": "application/json" });
		response.end(JSON.stringify({ error: { message: "blocked by image fixture" } }));
		return;
	}
	if (oracleCase === "retry" && retryAttempts++ === 0) {
		response.writeHead(429, {
			"content-type": "application/json",
			"retry-after-ms": "1",
		});
		response.end(JSON.stringify({ error: { message: "retry image request" } }));
		return;
	}

	response.writeHead(200, {
		"content-type": "application/json",
		"x-fixture": "openrouter-images",
	});
	response.end(
		JSON.stringify({
			id: "img-response-1",
			usage: {
				prompt_tokens: 20,
				completion_tokens: 7,
				prompt_tokens_details: {
					cached_tokens: 8,
					cache_write_tokens: 3,
				},
			},
			choices: [
				{
					message: {
						content: "Rendered image",
						images: [
							{ image_url: "data:image/png;base64,cG5n" },
							{ image_url: { url: "data:image/jpeg;base64,anBlZw==" } },
							{ image_url: "https://example.test/not-data.png" },
							{ image_url: "data:image/webp;not-base64,d2VicA==" },
						],
					},
				},
			],
		}),
	);
});
await new Promise<void>((resolve) => fixture.listen(0, "127.0.0.1", resolve));
const address = fixture.address();
if (!address || typeof address === "string") throw new Error("Image fixture did not bind");
const baseUrl = `http://127.0.0.1:${address.port}/v1`;

const model = {
	id: "oracle/image-model",
	name: "Oracle Image Model",
	api: "openrouter-images" as const,
	provider: "openrouter",
	baseUrl,
	input: ["text", "image"] as const,
	output: ["image", "text"] as const,
	cost: {
		input: 1,
		output: 2,
		cacheRead: 3,
		cacheWrite: 4,
	},
	headers: {
		"X-Model": "model-value",
		"X-Remove": "remove-me",
	},
};
const context = {
	input: [
		{ type: "text" as const, text: `Generate ${String.fromCharCode(0xd83d)} image` },
		{ type: "image" as const, mimeType: "image/png", data: "aW5wdXQ=" },
	],
};

let payloadCallbackModel = "";
let responseCallback:
	| {
			status: number;
			fixtureHeader?: string;
			model: string;
	  }
	| undefined;
const direct = await generateImages(model, context, {
	apiKey: "direct-key",
	headers: {
		"X-Model": "request-value",
		"X-Remove": null,
		"X-Request": "request-only",
		"X-Oracle-Case": "retry",
	},
	maxRetries: 1,
	onPayload: (payload, callbackModel) => {
		payloadCallbackModel = callbackModel.id;
		return { ...(payload as Record<string, unknown>), payload_tag: "replaced" };
	},
	onResponse: (response, callbackModel) => {
		responseCallback = {
			status: response.status,
			fixtureHeader: response.headers["x-fixture"],
			model: callbackModel.id,
		};
	},
});
const directRequest = requests.at(-1);
if (!directRequest || !responseCallback) throw new Error("Direct image request was not captured");

const errorResult = await generateImages(
	{ ...model, headers: { "X-Oracle-Case": "error" } },
	context,
	{ apiKey: "error-key", maxRetries: 0 },
);
const missingKeyResult = await generateImages(model, context);

const credentials = new InMemoryCredentialStore();
await credentials.modify("openrouter", async () => ({
	type: "oauth",
	access: "stored-openrouter-key",
	refresh: "",
	expires: Number.MAX_SAFE_INTEGER,
}));
const imagesModels = createImagesModels({ credentials });
imagesModels.setProvider(openrouterImagesProvider());
const storedAuth = await imagesModels.getAuth("openrouter");
const oauthResult = await imagesModels.generateImages(
	{ ...model, headers: { "X-Oracle-Case": "oauth" } },
	context,
);
const oauthRequest = requests.at(-1);
const explicitResult = await imagesModels.generateImages(
	{ ...model, headers: { "X-Oracle-Case": "explicit" } },
	context,
	{ apiKey: "explicit-key" },
);
const explicitRequest = requests.at(-1);
await new Promise<void>((resolve) => fixture.close(() => resolve()));
if (!oauthRequest || !explicitRequest) throw new Error("Authenticated image requests were not captured");

const catalog = Object.values(IMAGE_MODELS.openrouter);
const catalogCanonical = catalog
	.map((entry: any) =>
		[
			entry.id,
			entry.name,
			entry.api,
			entry.provider,
			entry.baseUrl,
			entry.input.join(","),
			entry.output.join(","),
			String(entry.cost.input),
			String(entry.cost.output),
			String(entry.cost.cacheRead),
			String(entry.cost.cacheWrite),
		].join("\t"),
	)
	.join("\n");

console.log(
	JSON.stringify(
		{
			catalog: {
				providers: Object.keys(IMAGE_MODELS),
				count: catalog.length,
				hash: createHash("sha256").update(catalogCanonical).digest("hex"),
				first: catalog[0],
				last: catalog.at(-1),
			},
			direct: {
				request: directRequest,
				retryAttempts,
				payloadCallbackModel,
				responseCallback,
				result: projectResult(direct),
			},
			errors: {
				http: projectResult(errorResult),
				missingKey: projectResult(missingKeyResult),
			},
			auth: {
				stored: storedAuth,
				oauthAuthorization: oauthRequest.authorization,
				oauthResult: projectResult(oauthResult),
				explicitAuthorization: explicitRequest.authorization,
				explicitResult: projectResult(explicitResult),
			},
		},
		null,
		2,
	),
);

function headerValue(value: string | string[] | undefined): string | undefined {
	return Array.isArray(value) ? value.join(",") : value;
}

function projectResult(result: any) {
	return {
		api: result.api,
		provider: result.provider,
		model: result.model,
		output: result.output,
		responseId: result.responseId,
		usage: result.usage,
		stopReason: result.stopReason,
		errorMessage: result.errorMessage,
		timestampPositive: result.timestamp > 0,
	};
}
