export default function (pi: any) {
	pi.registerShortcut("ctrl+y", {
		description: "Non-reserved",
		handler(ctx: any) {
			ctx.ui.notify("handled:non-reserved", "info");
		},
	});
}
