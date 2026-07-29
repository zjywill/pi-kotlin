import { Type } from "typebox";

export default function (pi: any) {
	pi.registerTool({
		name: "html_probe",
		label: "HTML probe",
		description: "Exercise HTML export tool rendering",
		parameters: Type.Object({ text: Type.String() }),
		async execute(_toolCallId: string, params: { text: string }) {
			return {
				content: [{ type: "text", text: `result:${params.text}` }],
				details: { source: "html-probe" },
			};
		},
		renderCall(args: { text: string }, theme: any, context: any) {
			return {
				render(width: number) {
					return ["", theme.fg("accent", `call:${args.text}:${width}:${context.argsComplete}`)];
				},
			};
		},
		renderResult(result: any, options: any, theme: any, context: any) {
			return {
				render(width: number) {
					const text = result.content.find((item: any) => item.type === "text")?.text ?? "";
					return [
						"",
						theme.fg(
							options.expanded ? "success" : "warning",
							`${options.expanded ? "expanded" : "collapsed"}:${text}:${width}:${context.isError}`,
						),
						"",
					];
				},
			};
		},
	});
}
