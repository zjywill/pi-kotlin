export default function (pi: any) {
	pi.registerShortcut("CTRL+SHIFT+X", {
		description: "Second extension",
		handler(ctx: any) {
			ctx.ui.notify("handled:second", "info");
		},
	});
}
