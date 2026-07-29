export default function (pi: any) {
	pi.registerShortcut("ctrl+p", {
		description: "Freed reserved default",
		handler(ctx: any) {
			ctx.ui.notify("handled:freed", "info");
		},
	});
}
