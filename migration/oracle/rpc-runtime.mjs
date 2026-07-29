import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, relative, resolve, sep } from "node:path";
import readline from "node:readline";

const targetRoot = resolve(import.meta.dirname, "../..");
const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const implementation = process.argv[2];
if (implementation !== "typescript" && implementation !== "kotlin") {
	throw new Error("Usage: node migration/oracle/rpc-runtime.mjs <typescript|kotlin>");
}

const fixturePath = resolve(targetRoot, "migration/fixtures/rpc-runtime/native-provider.ts");
const executable =
	implementation === "typescript"
		? {
				command: process.execPath,
				args: [resolve(sourceRoot, "packages/coding-agent/dist/cli.js")],
			}
		: {
				command: resolve(targetRoot, "pi-coding-agent/build/install/pi/bin/pi"),
				args: [],
			};

const runRoot = await mkdtemp(join(tmpdir(), `pi-rpc-${implementation}-`));
const cwd = join(runRoot, "workspace");
const agentDir = join(runRoot, "agent");
const sessionDir = join(runRoot, "sessions");
const exportPath = join(runRoot, "rpc-export.html");
await Promise.all([mkdir(cwd, { recursive: true }), mkdir(agentDir, { recursive: true }), mkdir(sessionDir, { recursive: true })]);
await writeFile(
	join(agentDir, "settings.json"),
	JSON.stringify(
		{
			defaultProjectTrust: true,
			defaultProvider: "rpc-fixture",
			defaultModel: "model-a",
			defaultThinkingLevel: "low",
			steeringMode: "one-at-a-time",
			followUpMode: "one-at-a-time",
			compaction: {
				enabled: false,
				reserveTokens: 1024,
				keepRecentTokens: 32,
			},
			retry: {
				enabled: true,
				maxRetries: 2,
				baseDelayMs: 1,
			},
		},
		null,
		2,
	),
);

const child = spawn(
	executable.command,
	[
		...executable.args,
		"--mode",
		"rpc",
		"--provider",
		"rpc-fixture",
		"--model",
		"model-a",
		"--models",
		"rpc-fixture/model-a:low,rpc-fixture/model-b:high",
		"--api-key",
		"rpc-key",
		"--session-dir",
		sessionDir,
		"--extension",
		fixturePath,
		"--no-extensions",
		"--no-skills",
		"--no-prompt-templates",
		"--no-context-files",
		"--no-builtin-tools",
		"--approve",
		"--offline",
	],
	{
		cwd,
		env: {
			...process.env,
			HOME: join(runRoot, "home"),
			PI_CODING_AGENT_DIR: agentDir,
			PI_OFFLINE: "1",
			NODE_NO_WARNINGS: "1",
			TERM: "dumb",
		},
		stdio: ["pipe", "pipe", "pipe"],
	},
);

const records = [];
const stderr = [];
let exit;
const exitPromise = new Promise((resolveExit) => {
	child.once("exit", (code, signal) => {
		exit = { code, signal };
		resolveExit(exit);
	});
});
process.once("exit", () => {
	if (!exit) child.kill("SIGTERM");
});

child.stderr.setEncoding("utf8");
child.stderr.on("data", (chunk) => stderr.push(chunk));

const output = readline.createInterface({ input: child.stdout });
output.on("line", (line) => {
	if (!line.trim()) return;
	let value;
	try {
		value = JSON.parse(line);
	} catch (error) {
		value = { type: "invalid_json_output", line, error: String(error) };
	}
	records.push(value);
	if (value.type === "extension_ui_request") {
		let response;
		switch (value.method) {
			case "select":
				response = { type: "extension_ui_response", id: value.id, value: "beta" };
				break;
			case "confirm":
				response = { type: "extension_ui_response", id: value.id, confirmed: true };
				break;
			case "input":
				response = { type: "extension_ui_response", id: value.id, value: "Ada" };
				break;
			case "editor":
				response = { type: "extension_ui_response", id: value.id, value: "edited" };
				break;
		}
		if (response) child.stdin.write(`${JSON.stringify(response)}\n`);
	}
});

function sleep(ms) {
	return new Promise((resolveSleep) => setTimeout(resolveSleep, ms));
}

async function waitFor(predicate, startIndex, description, timeoutMs = 15000) {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		for (let index = startIndex; index < records.length; index++) {
			if (predicate(records[index], index)) return records[index];
		}
		if (exit) {
			throw new Error(
				`RPC process exited while waiting for ${description}: ${JSON.stringify(exit)}\n${stderr.join("")}`,
			);
		}
		await sleep(5);
	}
	throw new Error(
		`Timed out waiting for ${description}\nRecent output:\n${records
			.slice(-20)
			.map((record) => JSON.stringify(record))
			.join("\n")}\nStderr:\n${stderr.join("")}`,
	);
}

function writeRaw(line) {
	child.stdin.write(`${line}\n`);
}

async function send(command, options = {}) {
	const startIndex = records.length;
	writeRaw(JSON.stringify(command));
	const response = await waitFor(
		(record) => record.type === "response" && record.id === command.id,
		startIndex,
		`${command.type} response`,
		options.timeoutMs,
	);
	if (options.settled) {
		await waitFor(
			(record) => record.type === "agent_settled",
			startIndex,
			`${command.type} agent_settled`,
			options.timeoutMs,
		);
	}
	return { response, startIndex };
}

const phases = [];

async function phase(name, action) {
	const startIndex = records.length;
	const result = await action(startIndex);
	await sleep(15);
	phases.push({
		phase: name,
		records: records.slice(startIndex),
	});
	return result;
}

let requestCounter = 0;
function request(type, fields = {}) {
	requestCounter += 1;
	return { id: `rpc-${String(requestCounter).padStart(2, "0")}`, type, ...fields };
}

await phase("parse_error", async (startIndex) => {
	writeRaw("{not-json");
	await waitFor(
		(record) => record.type === "response" && record.command === "parse",
		startIndex,
		"parse error response",
	);
});
await phase("unknown_command", () => send(request("unknown_rpc_command")));

await phase("startup_state", () => send(request("get_state")));
await phase("available_models", () => send(request("get_available_models")));
await phase("available_thinking", () => send(request("get_available_thinking_levels")));
await phase("commands", () => send(request("get_commands")));

await phase("set_session_name", () => send(request("set_session_name", { name: "  RPC Session  " })));
await phase("set_thinking", () => send(request("set_thinking_level", { level: "high" })));
await phase("cycle_thinking", () => send(request("cycle_thinking_level")));
await phase("set_model", () => send(request("set_model", { provider: "rpc-fixture", modelId: "model-b" })));
await phase("cycle_model", () => send(request("cycle_model")));
await phase("set_modes", async () => {
	await send(request("set_steering_mode", { mode: "all" }));
	await send(request("set_follow_up_mode", { mode: "all" }));
	await send(request("set_auto_compaction", { enabled: false }));
	await send(request("set_auto_retry", { enabled: true }));
	await send(request("abort_retry"));
	await send(request("get_state"));
});

await phase("prompt", () => send(request("prompt", { message: "rpc:hello" }), { settled: true }));
await phase("message_queries", async () => {
	await send(request("get_last_assistant_text"));
	await send(request("get_messages"));
	await send(request("get_session_stats"));
	await send(request("get_fork_messages"));
});

await phase("extension_events", () => send(request("prompt", { message: "/rpc-events checkpoint" })));
await phase("extension_dialogs", () => send(request("prompt", { message: "/rpc-dialogs" })));

await phase("bash", () => send(request("bash", { command: "printf 'rpc-bash'" })));
await phase("abort_bash", async (startIndex) => {
	const bash = request("bash", { command: "printf 'rpc-start'; sleep 5; printf 'rpc-end'" });
	writeRaw(JSON.stringify(bash));
	await waitFor(
		(record) => record.type === "bash_execution_update" && record.id === bash.id,
		startIndex,
		"bash output before abort",
	);
	const abort = request("abort_bash");
	await send(abort);
	await waitFor(
		(record) => record.type === "response" && record.id === bash.id,
		startIndex,
		"cancelled bash response",
	);
});

await phase("queued_prompts", async (startIndex) => {
	const initial = request("prompt", { message: "rpc:slow" });
	writeRaw(JSON.stringify(initial));
	await waitFor(
		(record) => record.type === "response" && record.id === initial.id,
		startIndex,
		"slow prompt response",
	);
	await waitFor(
		(record) => record.type === "message_start" && record.message?.role === "assistant",
		startIndex,
		"slow assistant start",
	);
	const steer = request("steer", { message: "rpc:steer" });
	const followUp = request("follow_up", { message: "rpc:follow" });
	child.stdin.write(`${JSON.stringify(steer)}\n${JSON.stringify(followUp)}\n`);
	await waitFor(
		(record) => record.type === "response" && record.id === steer.id,
		startIndex,
		"steer response",
	);
	await waitFor(
		(record) => record.type === "response" && record.id === followUp.id,
		startIndex,
		"follow-up response",
	);
	await waitFor(
		(record) => record.type === "agent_settled",
		startIndex,
		"queued prompt settlement",
		30000,
	);
});

await phase("abort_prompt", async (startIndex) => {
	const prompt = request("prompt", { message: "rpc:abort" });
	writeRaw(JSON.stringify(prompt));
	await waitFor(
		(record) => record.type === "response" && record.id === prompt.id,
		startIndex,
		"abortable prompt response",
	);
	await waitFor(
		(record) => record.type === "message_start" && record.message?.role === "assistant",
		startIndex,
		"abortable assistant start",
	);
	await send(request("abort"));
	await waitFor(
		(record) => record.type === "agent_settled",
		startIndex,
		"aborted prompt settlement",
	);
});

await phase("auto_retry", () => send(request("prompt", { message: "rpc:retry" }), { settled: true }));

await phase("compact_seed", () =>
	send(request("prompt", { message: `rpc:long:${"x".repeat(1024)}` }), { settled: true }),
);
await phase("compact", () => send(request("compact", { customInstructions: "Keep RPC facts" }), { timeoutMs: 30000 }));

let originalSessionFile;
let forkEntryId;
await phase("entries_and_tree", async () => {
	const state = await send(request("get_state"));
	originalSessionFile = state.response.data.sessionFile;
	const entries = await send(request("get_entries"));
	const userEntry = entries.response.data.entries.find(
		(entry) => entry.type === "message" && entry.message?.role === "user",
	);
	if (!userEntry) throw new Error("No user entry is available for the fork scenario");
	forkEntryId = userEntry.id;
	await send(request("get_entries", { since: forkEntryId }));
	await send(request("get_tree"));
});

await phase("session_errors", async () => {
	await send(request("set_session_name", { name: "   " }));
	await send(request("set_model", { provider: "rpc-fixture", modelId: "missing" }));
	await send(request("get_entries", { since: "missing-entry" }));
	await send(request("fork", { entryId: "missing-entry" }));
});

await phase("fork", async () => {
	await send(request("fork", { entryId: forkEntryId }));
	await send(request("get_state"));
});
await phase("switch_session", async () => {
	await send(request("switch_session", { sessionPath: originalSessionFile }));
	await send(request("get_state"));
});
await phase("clone", async () => {
	await send(request("clone"));
	await send(request("get_state"));
});
await phase("switch_after_clone", () => send(request("switch_session", { sessionPath: originalSessionFile })));
await phase("export_html", async () => {
	await send(request("export_html", { outputPath: exportPath }), { timeoutMs: 30000 });
	const html = await readFile(exportPath, "utf8");
	if (!html.includes("<!DOCTYPE html>")) throw new Error("RPC export did not produce HTML");
});
await phase("new_session", async () => {
	await send(request("new_session", { parentSession: originalSessionFile }));
	await send(request("get_state"));
});

child.stdin.end();
await Promise.race([
	exitPromise,
	sleep(10000).then(() => {
		child.kill("SIGTERM");
		throw new Error(`RPC process did not exit after stdin EOF\n${stderr.join("")}`);
	}),
]);
if (exit.code !== 0) {
	throw new Error(`RPC process exited with ${JSON.stringify(exit)}\n${stderr.join("")}`);
}

const idMap = new Map();
let nextId = 0;
const sessionPathMap = new Map();
let nextSessionPath = 0;

function canonicalId(value) {
	if (typeof value !== "string") return value;
	if (/^rpc-\d+$/.test(value)) return value;
	if (!/^(?:[0-9a-f]{8}|[0-9a-f]{8}-[0-9a-f-]{27})$/i.test(value)) return value;
	if (idMap.has(value)) return idMap.get(value);
	nextId += 1;
	const canonical = `<id:${nextId}>`;
	idMap.set(value, canonical);
	return canonical;
}

function normalizePath(value) {
	const normalized = value.split(sep).join("/");
	const roots = [
		[runRoot, "<RUN_ROOT>"],
		[targetRoot, "<TARGET_ROOT>"],
		[sourceRoot, "<SOURCE_ROOT>"],
	];
	for (const [root, marker] of roots) {
		const normalizedRoot = root.split(sep).join("/");
		if (normalized === normalizedRoot) return marker;
		if (normalized.startsWith(`${normalizedRoot}/`)) {
			if (root === runRoot && normalized.startsWith(`${normalizedRoot}/sessions/`)) {
				if (!sessionPathMap.has(normalized)) {
					nextSessionPath += 1;
					sessionPathMap.set(normalized, `<RUN_ROOT>/sessions/<session:${nextSessionPath}>`);
				}
				return sessionPathMap.get(normalized);
			}
			return `${marker}/${relative(root, value).split(sep).join("/")}`;
		}
	}
	return value;
}

function normalizeString(value, key) {
	if (value.length > 4096) {
		return `<long:${value.length}:${createHash("sha256").update(value).digest("hex")}>`;
	}
	if (
		key === "path" ||
		key === "baseDir" ||
		key === "extensionPath" ||
		key === "sessionFile" ||
		key === "parentSession"
	) {
		return normalizePath(value);
	}
	if (key === "timestamp" || key === "labelTimestamp") return "<timestamp>";
	if (
		key === "entryId" ||
		key === "parentId" ||
		key === "leafId" ||
		key === "firstKeptEntryId" ||
		key === "targetId" ||
		key === "fromId" ||
		key === "sessionId"
	) {
		return canonicalId(value);
	}
	return idMap.get(value) ?? value;
}

function normalizeValue(value, key) {
	if (Array.isArray(value)) return value.map((item) => normalizeValue(item));
	if (typeof value === "string") return normalizeString(value, key);
	if ((key === "timestamp" || key === "labelTimestamp") && typeof value === "number") return "<timestamp>";
	if (value === null || typeof value !== "object") return value;

	const result = {};
	for (const childKey of Object.keys(value).sort()) {
		let childValue = value[childKey];
		if (childKey === "id" && typeof childValue === "string") {
			childValue = canonicalId(childValue);
		}
		result[childKey] = normalizeValue(childValue, childKey);
	}
	return result;
}

function projectRecord(record) {
	const projected = structuredClone(record);
	if (projected.type === "response" && projected.command === "get_available_models" && projected.data?.models) {
		projected.data.models = projected.data.models.filter((model) => model.provider === "rpc-fixture");
	}
	if (projected.type === "response" && projected.command === "get_commands" && projected.data?.commands) {
		projected.data.commands = projected.data.commands.filter((command) => command.name.startsWith("rpc-"));
	}
	if (projected.type === "response" && projected.command === "parse") {
		projected.error = "Failed to parse command: <parser-error>";
	}
	return projected;
}

function coalesceBashUpdates(phaseRecords) {
	const result = [];
	for (const record of phaseRecords) {
		const previous = result.at(-1);
		if (
			record.type === "bash_execution_update" &&
			previous?.type === "bash_execution_update" &&
			record.id === previous.id
		) {
			previous.delta += record.delta;
		} else {
			result.push(record);
		}
	}
	return result;
}

const normalized = phases.map((item) => ({
	phase: item.phase,
	records: normalizeValue(coalesceBashUpdates(item.records.map(projectRecord))),
}));
process.stdout.write(`${JSON.stringify(normalized, null, 2)}\n`);

await rm(runRoot, { recursive: true, force: true });
