import { Type } from "typebox";
import { defineTool } from "@earendil-works/pi-coding-agent";

const tool = defineTool({
	name: "virtual-tool",
	description: "Virtual import fixture",
	parameters: Type.Object({ value: Type.String() }),
	async execute() {
		return { content: [], details: {} };
	},
});

export default function (pi: any) {
	pi.registerCommand("jiti-virtual", {
		description: `${tool.name}:${tool.parameters.type}`,
		handler() {},
	});
}
