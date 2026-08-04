import { Type } from "typebox";
import { createAssistantMessageEventStream } from "@earendil-works/pi-ai";
import { defineTool, type ExtensionAPI } from "@earendil-works/pi-coding-agent";

export default function extensionRuntimeFixture(pi: ExtensionAPI) {
	pi.registerFlag("loud", {
		type: "boolean",
		description: "Use loud extension output",
		default: false,
	});

	pi.registerProvider("fixture-provider", {
		name: "Fixture Provider",
		baseUrl: "https://fixture.invalid/v1",
		apiKey: "$FIXTURE_API_KEY",
		api: "openai-responses",
		models: [
			{
				id: "fixture-model",
				name: "Fixture Model",
				reasoning: false,
				input: ["text"],
				cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
				contextWindow: 8192,
				maxTokens: 1024,
			},
		],
	});

	const callbackModel = {
		id: "callback-model",
		name: "Callback Model",
		reasoning: true,
		input: ["text"] as const,
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 8192,
		maxTokens: 1024,
	};
	pi.registerProvider("callback-provider", {
		name: "Callback Provider",
		baseUrl: "https://callback.invalid/v1",
		apiKey: "callback-key",
		api: "callback-api",
		models: [callbackModel],
		streamSimple(model, context, options) {
			const stream = createAssistantMessageEventStream();
			const prompt = context.messages.at(-1)?.content ?? "";
			const text = `callback:${prompt}:${options?.apiKey}:${options?.reasoning}`;
			const usage = {
				input: 1,
				output: 1,
				cacheRead: 0,
				cacheWrite: 0,
				totalTokens: 2,
				cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
			};
			const partial = {
				role: "assistant" as const,
				content: [{ type: "text" as const, text }],
				api: model.api,
				provider: model.provider,
				model: model.id,
				usage,
				stopReason: "pending" as const,
				timestamp: 123,
			};
			stream.push({ type: "start", partial });
			stream.push({ type: "text_start", contentIndex: 0, partial });
			stream.push({ type: "text_delta", contentIndex: 0, delta: text, partial });
			stream.push({ type: "text_end", contentIndex: 0, content: text, partial });
			stream.push({
				type: "done",
				reason: "stop",
				message: { ...partial, stopReason: "stop" },
			});
			return stream;
		},
		oauth: {
			name: "Callback Subscription",
			async login(callbacks) {
				callbacks.onAuth({
					url: "https://auth.invalid/start",
					instructions: "Open the browser",
				});
				callbacks.onDeviceCode({
					userCode: "ABCD",
					verificationUri: "https://auth.invalid/device",
					intervalSeconds: 2,
					expiresInSeconds: 60,
				});
				callbacks.onProgress?.("Waiting");
				const account = await callbacks.onPrompt({ message: "Account", placeholder: "name" });
				const code = await callbacks.onManualCodeInput?.();
				const tenant = await callbacks.onSelect({
					message: "Workspace",
					options: [
						{ id: "team", label: "Team" },
						{ id: "personal", label: "Personal" },
					],
				});
				return {
					access: `${account}-${code}`,
					refresh: "refresh-1",
					expires: 0,
					tenant,
				};
			},
			async refreshToken(credentials) {
				return {
					...credentials,
					access: `${credentials.access}-refreshed`,
					refresh: "refresh-2",
					expires: 1000000,
				};
			},
			getApiKey(credentials) {
				return `${credentials.access}:${credentials.tenant}`;
			},
			modifyModels(models, credentials) {
				return [
					...models,
					{
						...callbackModel,
						id: `tenant-${credentials.tenant}`,
						name: `Tenant ${credentials.tenant}`,
					},
				];
			},
		},
	});

	const nativeModel = {
		id: "native-initial",
		name: "Native Initial",
		api: "native-api",
		provider: "native-provider",
		baseUrl: "https://native.invalid/v1",
		reasoning: false,
		input: ["text"] as const,
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 8192,
		maxTokens: 1024,
	};
	let nativeModels = [nativeModel];
	const nativeStream = (kind: string, model: typeof nativeModel, options?: { apiKey?: string }) => {
		const stream = createAssistantMessageEventStream();
		const text = `${kind}:${model.id}:${options?.apiKey}`;
		const usage = {
			input: 1,
			output: 1,
			cacheRead: 0,
			cacheWrite: 0,
			totalTokens: 2,
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
		};
		const partial = {
			role: "assistant" as const,
			content: [{ type: "text" as const, text }],
			api: model.api,
			provider: model.provider,
			model: model.id,
			usage,
			stopReason: "pending" as const,
			timestamp: 123,
		};
		stream.push({ type: "start", partial });
		stream.push({ type: "text_start", contentIndex: 0, partial });
		stream.push({ type: "text_delta", contentIndex: 0, delta: text, partial });
		stream.push({ type: "text_end", contentIndex: 0, content: text, partial });
		stream.push({
			type: "done",
			reason: "stop",
			message: { ...partial, stopReason: "stop" },
		});
		return stream;
	};
	pi.registerProvider({
		id: "native-provider",
		name: "Native Provider",
		baseUrl: "https://native.invalid/v1",
		headers: { "X-Native": "metadata" },
		auth: {
			apiKey: {
				name: "Native setup",
				async login(interaction) {
					return {
						type: "api_key",
						key: await interaction.prompt({ type: "secret", message: "Native API key" }),
					};
				},
				async check({ ctx, credential }) {
					const key = credential?.key ?? await ctx.env("NATIVE_API_KEY");
					return key
						? { type: "api_key", source: "native stored key" }
						: undefined;
				},
				async resolve({ ctx, credential }) {
					const key = credential?.key ?? await ctx.env("NATIVE_API_KEY");
					const account = credential?.env?.NATIVE_ACCOUNT ?? await ctx.env("NATIVE_ACCOUNT");
					if (!key || !account || !await ctx.fileExists(".")) return undefined;
					return {
						auth: {
							apiKey: key,
							baseUrl: `https://native.invalid/${account}`,
						},
						env: { NATIVE_ACCOUNT: account },
						source: "native resolve",
					};
				},
			},
		},
		getModels() {
			return nativeModels;
		},
		async refreshModels(context) {
			const refreshed = [{
				...nativeModel,
				id: `native-${context.stored?.models?.[0]?.id ?? "empty"}`,
			}];
			await context.publish({
				persist: { models: refreshed, checkedAt: 321 },
				update: () => {
					nativeModels = refreshed;
				},
			});
		},
		filterModels(models) {
			return models;
		},
		stream(model, _context, options) {
			return nativeStream("stream", model as typeof nativeModel, options);
		},
		streamSimple(model, _context, options) {
			return nativeStream("simple", model as typeof nativeModel, options);
		},
	});

	pi.registerProvider("dynamic-provider", {
		name: "Dynamic Provider",
		baseUrl: "https://dynamic.invalid/v1",
		apiKey: "dynamic-key",
		api: "openai-completions",
		async refreshModels(context) {
			return [{
				id: `dynamic-${context.stored?.models?.[0]?.id ?? "empty"}`,
				name: "Dynamic model",
				reasoning: false,
				input: ["text"],
				cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
				contextWindow: 8192,
				maxTokens: 1024,
			}];
		},
	});

	pi.registerTool(
		defineTool({
			name: "extension_echo",
			label: "Extension echo",
			description: "Echo text through the extension runtime",
			parameters: Type.Object({
				text: Type.String({ description: "Text to echo" }),
				suffix: Type.Optional(Type.String()),
			}),
			executionMode: "sequential",
			async execute(_toolCallId, params, _signal, onUpdate) {
				onUpdate?.({
					content: [{ type: "text", text: "working" }],
					details: { phase: "update" },
				});
				const suffix = params.suffix ?? "";
				return {
					content: [{ type: "text", text: `${params.text}${suffix}` }],
					details: { loud: pi.getFlag("loud") },
				};
			},
		}),
	);

	pi.registerCommand("record", {
		description: "Record an extension command",
		async handler(args, ctx) {
			pi.appendEntry("fixture-command", { args });
			ctx.ui.notify(`recorded:${args}`, "info");
			pi.registerTool(
				defineTool({
					name: "dynamic_echo",
					label: "Dynamic echo",
					description: "Registered from a command",
					parameters: Type.Object({ text: Type.String() }),
					async execute(_toolCallId, params) {
						return {
							content: [{ type: "text", text: `dynamic:${params.text}` }],
							details: {},
						};
					},
				}),
			);
			pi.registerCommand("dynamic-record", {
				description: "Dynamically registered command",
				handler() {},
			});
			pi.registerFlag("dynamic-flag", {
				type: "boolean",
				description: "Dynamically registered flag",
				default: true,
			});
		},
	});

	pi.registerCommand("dialogs", {
		description: "Exercise awaited extension dialogs",
		async handler(_args, ctx) {
			const choice = await ctx.ui.select("Choose", ["alpha", "beta"]);
			const confirmed = await ctx.ui.confirm("Confirm", "Continue?");
			const name = await ctx.ui.input("Name", "enter name");
			const edited = await ctx.ui.editor("Edit", "draft");
			pi.appendEntry("fixture-dialogs", { choice, confirmed, name, edited });
		},
	});

	pi.registerCommand("schedule-background", {
		description: "Register extension features after the command returns",
		handler() {
			setTimeout(() => {
				pi.registerTool(
					defineTool({
						name: "background_echo",
						label: "Background echo",
						description: "Registered outside an extension invocation",
						parameters: Type.Object({ text: Type.String() }),
						async execute(_toolCallId, params) {
							return {
								content: [{ type: "text", text: `background:${params.text}` }],
								details: {},
							};
						},
					}),
				);
				pi.registerCommand("background-command", {
					description: "Registered outside an extension invocation",
					handler(_args, ctx) {
						ctx.ui.notify("background-command", "info");
					},
				});
				pi.registerFlag("background-flag", {
					type: "boolean",
					description: "Registered outside an extension invocation",
					default: true,
				});
				pi.registerProvider("background-provider", {
					name: "Background Provider",
					baseUrl: "https://background.invalid/v1",
					apiKey: "background-key",
					api: "openai-completions",
					models: [{
						id: "background-model",
						name: "Background Model",
						reasoning: false,
						input: ["text"],
						cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
						contextWindow: 8192,
						maxTokens: 1024,
					}],
				});
			}, 0);
		},
	});

	pi.on("session_start", (_event, ctx) => {
		ctx.ui.setStatus("fixture", "started");
	});

	pi.on("project_trust", () => ({
		trusted: "yes",
		remember: true,
	}));

	pi.on("before_agent_start", (event) => ({
		systemPrompt: `${event.systemPrompt}\nextension fixture`,
	}));

	pi.on("tool_call", (event) => {
		if (event.input.block === true) {
			return { block: true, reason: "blocked by fixture" };
		}
	});

	pi.on("tool_result", (event) => ({
		content: [...event.content, { type: "text", text: "postprocessed" }],
		details: { fixture: true },
	}));

	pi.on("resources_discover", () => ({
		skillPaths: ["fixture-skill.md"],
		promptPaths: ["fixture-prompt.md"],
		themePaths: ["fixture-theme.json"],
	}));

	pi.on("user_bash", () => ({
		operations: {
			async exec(command, cwd, { onData }) {
				onData(Buffer.from(`remote:${command}:${cwd}\n`));
				onData(Buffer.from("finished"));
				return { exitCode: 7 };
			},
		},
	}));
}
