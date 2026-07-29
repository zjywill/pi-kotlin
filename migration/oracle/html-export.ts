import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const targetRoot = resolve(import.meta.dirname, "../..");
const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const root = mkdtempSync(join(tmpdir(), "pi-html-export-oracle-"));
const agentDir = join(root, "agent");
const outputPath = join(root, "session.html");
const sessionPath = join(targetRoot, "migration/fixtures/html-export/session.jsonl");
const themePath = join(targetRoot, "migration/fixtures/html-export/oracle-theme.json");

try {
	mkdirSync(join(agentDir, "themes"), { recursive: true });
	copyFileSync(themePath, join(agentDir, "themes", "oracle-export.json"));
	process.env.PI_CODING_AGENT_DIR = agentDir;

	const { exportFromFile } = await import(
		pathToFileURL(join(sourceRoot, "packages/coding-agent/src/core/export-html/index.ts")).href
	);
	await exportFromFile(sessionPath, {
		outputPath,
		themeName: "oracle-export",
	});
	process.stdout.write(readFileSync(outputPath, "utf8"));
} finally {
	rmSync(root, { recursive: true, force: true });
}
