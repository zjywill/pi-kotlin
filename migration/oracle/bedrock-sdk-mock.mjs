const source = String.raw`
const state = globalThis.__piBedrockMock;

export class BedrockRuntimeServiceException extends Error {}

export class BedrockRuntimeClient {
	constructor(config) {
		this.config = config;
		this.registrations = [];
		state.configs.push(config);
		this.middlewareStack = {
			add: (handler, options) => {
				this.registrations.push({ handler, options });
			},
		};
	}

	async send(command) {
		const args = { request: { headers: {} } };
		for (const registration of this.registrations) {
			await registration.handler(async (value) => value)(args);
		}
		state.requests.push({ input: command.input, headers: args.request.headers });
		const events = structuredClone(state.events);
		return {
			$metadata: { httpStatusCode: 200, requestId: "bedrock-request-1" },
			stream: (async function* () {
				for (const event of events) yield event;
			})(),
		};
	}
}

export class ConverseStreamCommand {
	constructor(input) {
		this.input = input;
	}
}

export const StopReason = {
	END_TURN: "end_turn",
	STOP_SEQUENCE: "stop_sequence",
	MAX_TOKENS: "max_tokens",
	MODEL_CONTEXT_WINDOW_EXCEEDED: "model_context_window_exceeded",
	TOOL_USE: "tool_use",
};
export const CachePointType = { DEFAULT: "default" };
export const CacheTTL = { FIVE_MINUTES: "5m", ONE_HOUR: "1h" };
export const ConversationRole = { ASSISTANT: "assistant", USER: "user" };
export const ImageFormat = { JPEG: "jpeg", PNG: "png", GIF: "gif", WEBP: "webp" };
export const ToolResultStatus = { ERROR: "error", SUCCESS: "success" };
`;

export async function resolve(specifier, context, nextResolve) {
	if (specifier === "@aws-sdk/client-bedrock-runtime") {
		return { url: "pi-bedrock-mock:client", shortCircuit: true };
	}
	return nextResolve(specifier, context);
}

export async function load(url, context, nextLoad) {
	if (url === "pi-bedrock-mock:client") {
		return { format: "module", source, shortCircuit: true };
	}
	return nextLoad(url, context);
}
