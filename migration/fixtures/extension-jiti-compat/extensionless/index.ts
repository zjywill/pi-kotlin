import { FixtureKind, typedValue } from "./dependency";

export default function (pi: any) {
	pi.registerCommand("jiti-extensionless", {
		description: `${FixtureKind.Extensionless}:${typedValue}`,
		handler() {},
	});
}
