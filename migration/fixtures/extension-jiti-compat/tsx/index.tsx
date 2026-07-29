import { fixtureValue } from "./dependency";

export default function (pi: any) {
	pi.registerCommand("jiti-tsx", {
		description: fixtureValue.value,
		handler() {},
	});
}
