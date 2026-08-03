import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const temp = mkdtempSync(resolve(tmpdir(), "pi-server-recovery-"));
const serverDir = resolve(temp, "server");
const bundlePath = resolve(temp, "supervisor.mjs");
const stubPath = resolve(temp, "coding-agent-stub.mjs");
const oldTimestamp = "2026-01-01T00:00:00.000Z";

const records = ["starting", "online", "stopping", "stopped", "error"].map((status) => ({
	id: status,
	status,
	cwd: `/fixture/${status}`,
	createdAt: oldTimestamp,
	lastSeenAt: oldTimestamp,
	label: `label-${status}`,
	sessionId: `session-${status}`,
	sessionFile: `/sessions/${status}.jsonl`,
}));

try {
	process.env.PI_SERVER_DIR = serverDir;
	writeFileSync(stubPath, "export function readStoredCredential() { return undefined; }\n");
	const esbuild = await import(pathToFileURL(resolve(sourceRoot, "node_modules/esbuild/lib/main.js")).href);
	await esbuild.build({
		entryPoints: [resolve(sourceRoot, "packages/server/src/legacy/supervisor.ts")],
		outfile: bundlePath,
		bundle: true,
		format: "esm",
		platform: "node",
		target: "node22",
		logLevel: "silent",
		alias: {
			"@earendil-works/pi-ai": stubPath,
			"@earendil-works/pi-coding-agent": stubPath,
		},
	});
	mkdirSync(serverDir, { recursive: true });
	writeFileSync(resolve(serverDir, "instances.json"), JSON.stringify(records, null, 2));
	const { ServerSupervisor } = await import(`${pathToFileURL(bundlePath).href}?t=${Date.now()}`);
	await new ServerSupervisor().recoverAfterRestart();
	const recovered = JSON.parse(readFileSync(resolve(serverDir, "instances.json"), "utf8")) as typeof records;
	console.log(
		JSON.stringify({
			records: recovered.map((record) => ({
				id: record.id,
				status: record.status,
				cwd: record.cwd,
				createdAt: record.createdAt,
				lastSeenUpdated: record.lastSeenAt !== oldTimestamp,
				label: record.label,
				sessionId: record.sessionId,
				sessionFile: record.sessionFile,
			})),
		}),
	);
} finally {
	rmSync(temp, { recursive: true, force: true });
}
