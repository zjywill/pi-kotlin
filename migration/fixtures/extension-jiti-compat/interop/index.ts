import legacyDefault, { named } from "./legacy.cjs";

const required = require("./required.cjs");

export default function (pi: any) {
	pi.registerCommand("jiti-interop", {
		description: `${legacyDefault()}:${named}:${required.value}`,
		handler() {},
	});
}
