export default function (pi: any) {
	pi.registerShortcut("ctrl+c", {
		description: "Reserved",
		handler(ctx: any) {
			ctx.ui.notify("handled:reserved", "info");
		},
	});
}
