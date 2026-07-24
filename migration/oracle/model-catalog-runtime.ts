import { join } from "node:path";
import { pathToFileURL } from "node:url";

const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const { withRemoteCatalog } = await import(
	pathToFileURL(join(sourceRoot, "packages", "coding-agent", "src", "core", "remote-catalog-provider.ts")).href
);

const bundledAt = Date.parse("2026-07-23T10:00:00Z");
const model = (id: string) => ({
	id,
	name: id,
	api: "openai-completions",
	provider: "test-provider",
	baseUrl: "https://example.test/v1",
	reasoning: false,
	input: ["text"],
	cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
	contextWindow: 1000,
	maxTokens: 100,
});
const staticProvider = () => ({
	id: "test-provider",
	name: "Test Provider",
	auth: { apiKey: { name: "Test", resolve: async () => ({ auth: {} }) } },
	getModels: () => [model("static")],
	stream: () => {
		throw new Error("not used");
	},
	streamSimple: () => {
		throw new Error("not used");
	},
});
const store = (initial?: Record<string, unknown>) => {
	let entry = initial;
	return {
		scoped: {
			read: async () => entry,
			write: async (next: Record<string, unknown>) => {
				entry = structuredClone(next);
			},
			delete: async () => {
				entry = undefined;
			},
		},
		read: () => entry,
	};
};
const context = (modelsStore: ReturnType<typeof store>, allowNetwork: boolean, force = false) => ({
	credential: { type: "api_key", key: "test" },
	store: modelsStore.scoped,
	allowNetwork,
	force,
});

const responses = [
	new Response(JSON.stringify({ old: model("old") }), {
		headers: { "last-modified": new Date(bundledAt - 60_000).toUTCString() },
	}),
	new Response(JSON.stringify({ newer: model("newer") }), {
		headers: { "last-modified": new Date(bundledAt + 60_000).toUTCString() },
	}),
	new Response("not implemented", { status: 501 }),
];
globalThis.fetch = async () => responses.shift() as Response;

const selectionStore = store();
const selectionProvider = withRemoteCatalog(staticProvider(), "https://pi.dev", bundledAt);
await selectionProvider.refreshModels(context(selectionStore, true));
const older = selectionProvider.getModels().map((entry: { id: string }) => entry.id);
await selectionProvider.refreshModels(context(selectionStore, true, true));
const newer = selectionProvider.getModels().map((entry: { id: string }) => entry.id);

const offlineStore = store({
	models: [model("cached")],
	lastModified: bundledAt + 1,
	checkedAt: 100,
});
const offlineProvider = withRemoteCatalog(staticProvider(), "https://pi.dev", bundledAt);
await offlineProvider.refreshModels(context(offlineStore, false));

const unavailableStore = store();
const unavailableProvider = withRemoteCatalog(staticProvider(), "https://pi.dev", bundledAt);
await unavailableProvider.refreshModels(context(unavailableStore, true));
const unavailableEntry = unavailableStore.read() as { checkedAt?: number; lastModified?: number };

console.log(
	JSON.stringify({
		older,
		newer,
		offline: offlineProvider.getModels().map((entry: { id: string }) => entry.id),
		unimplemented: {
			models: unavailableProvider.getModels().map((entry: { id: string }) => entry.id),
			lastModified: unavailableEntry.lastModified,
			hasCheckedAt: typeof unavailableEntry.checkedAt === "number",
		},
	}),
);
