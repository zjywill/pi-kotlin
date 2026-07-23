import { readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const fixtureDir = join(import.meta.dirname, "session-fixtures");
const sessionModuleUrl = pathToFileURL(
	join(sourceRoot, "packages", "coding-agent", "src", "core", "session-manager.ts"),
).href;
const {
	buildSessionContext,
	migrateSessionEntries,
	parseSessionEntries,
}: {
	buildSessionContext: (
		entries: Record<string, unknown>[],
		leafId?: string | null,
	) => Record<string, unknown>;
	migrateSessionEntries: (entries: Record<string, unknown>[]) => void;
	parseSessionEntries: (content: string) => Record<string, unknown>[];
} = await import(sessionModuleUrl);

const currentEntries = readEntries("current.jsonl");
const sessionEntries = currentEntries.filter((entry) => entry.type !== "session");
const v1Entries = readEntries("v1.jsonl");
const v2Entries = readEntries("v2.jsonl");
migrateSessionEntries(v1Entries);
migrateSessionEntries(v2Entries);

const output = {
	parsedTypes: currentEntries.map((entry) => entry.type),
	roundTrip: currentEntries,
	contexts: {
		defaultLeaf: buildSessionContext(sessionEntries),
		mainLeaf: buildSessionContext(sessionEntries, "info"),
		beforeCompaction: buildSessionContext(sessionEntries, "a2"),
		explicitEmptyLeaf: buildSessionContext(sessionEntries, null),
		missingLeafFallsBack: buildSessionContext(sessionEntries, "missing"),
	},
	migrations: {
		v1: normalizeGeneratedIds(v1Entries),
		v2: v2Entries,
	},
};

console.log(JSON.stringify(output));

function readEntries(name: string): Record<string, unknown>[] {
	return parseSessionEntries(readFileSync(join(fixtureDir, name), "utf8"));
}

function normalizeGeneratedIds(entries: Record<string, unknown>[]): {
	idLengths: number[];
	entries: Record<string, unknown>[];
} {
	const generated = entries.filter((entry) => entry.type !== "session");
	const idMap = new Map(
		generated.map((entry, index) => [String(entry.id), `entry-${index + 1}`]),
	);
	return {
		idLengths: generated.map((entry) => String(entry.id).length),
		entries: entries.map((entry) => {
			const normalized = { ...entry };
			if (typeof entry.id === "string" && idMap.has(entry.id)) {
				normalized.id = idMap.get(entry.id);
			}
			if (typeof entry.parentId === "string" && idMap.has(entry.parentId)) {
				normalized.parentId = idMap.get(entry.parentId);
			}
			if (typeof entry.firstKeptEntryId === "string" && idMap.has(entry.firstKeptEntryId)) {
				normalized.firstKeptEntryId = idMap.get(entry.firstKeptEntryId);
			}
			return normalized;
		}),
	};
}
