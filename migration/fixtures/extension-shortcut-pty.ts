export default function (pi: any) {
	pi.registerShortcut("ctrl+y", {
		description: "Ask for a name",
		async handler(ctx: any) {
			const name = await ctx.ui.input("Shortcut name", "name");
			ctx.ui.notify(`shortcut:${name}`, "info");
		},
	});
}
