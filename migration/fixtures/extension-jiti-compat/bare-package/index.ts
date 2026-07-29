import { packageName, packageValue } from "pi-jiti-fixture";

export default function (pi: any) {
	pi.registerCommand("jiti-bare-package", {
		description: `${packageName}:${packageValue}`,
		handler() {},
	});
}
