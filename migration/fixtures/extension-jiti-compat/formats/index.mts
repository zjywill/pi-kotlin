import ctsDefault, { ctsValue } from "./dependency.cts";
import { mjsValue } from "./helper.mjs";

export default function (pi: any) {
	pi.registerCommand("jiti-formats", {
		description: `${mjsValue}:${ctsDefault}:${ctsValue}`,
		handler() {},
	});
}
