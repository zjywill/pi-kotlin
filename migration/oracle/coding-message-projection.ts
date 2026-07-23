import { readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const messagesModuleUrl = pathToFileURL(
	join(sourceRoot, "packages", "coding-agent", "src", "core", "messages.ts"),
).href;
const { convertToLlm }: { convertToLlm: (messages: Record<string, unknown>[]) => Record<string, unknown>[] } =
	await import(messagesModuleUrl);
const messages = JSON.parse(
	readFileSync(join(import.meta.dirname, "coding-messages.json"), "utf8"),
) as Record<string, unknown>[];

console.log(JSON.stringify(convertToLlm(messages)));
