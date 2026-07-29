export default function (pi: any) {
	pi.registerShortcut("ctrl+q", {
		description: "Rebound reserved",
		handler(ctx: any) {
			ctx.ui.notify("handled:rebound", "info");
		},
	});
}
