import { realpathSync } from "node:fs";
import { basename, dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const targetRoot = resolve(import.meta.dirname, "../..");
const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const fixtureRoot = realpathSync(resolve(targetRoot, "migration/fixtures/extension-jiti-compat"));
const cases = [
	["extensionless", "index.ts"],
	["directory", "index.ts"],
	["interop", "index.ts"],
	["formats", "index.mts"],
	["tsx", "index.tsx"],
	["bare-package", "index.ts"],
	["virtual", "index.ts"],
	["commonjs", "index.cjs"],
	["jsx", "index.tsx"],
] as const;
const paths = cases.map(([name, entry]) => realpathSync(resolve(fixtureRoot, name, entry)));
const loader = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/extensions/loader.ts")).href
);
const loaded = await loader.loadExtensions(paths, fixtureRoot);
const loadedByCase = new Map(
	loaded.extensions.map(extension => [basename(dirname(extension.path)), extension]),
);

const result = Object.fromEntries(
	cases.map(([name]) => {
		const extension = loadedByCase.get(name);
		const command = extension ? [...extension.commands.values()][0] : undefined;
		return [
			name,
			{
				loaded: extension !== undefined,
				value: command?.description ?? null,
			},
		];
	}),
);

console.log(JSON.stringify(result));
