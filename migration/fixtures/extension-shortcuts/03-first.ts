export default function (pi: any) {
	pi.registerShortcut("ctrl+shift+x", {
		description: "First extension",
		handler(ctx: any) {
			ctx.ui.notify("handled:first", "info");
		},
	});
}
