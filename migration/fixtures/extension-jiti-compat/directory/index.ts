import directoryValue from "./helper";

export default function (pi: any) {
	pi.registerCommand("jiti-directory", {
		description: directoryValue,
		handler() {},
	});
}
