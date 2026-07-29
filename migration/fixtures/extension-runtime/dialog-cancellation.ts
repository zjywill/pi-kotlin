export default function (pi: any) {
	pi.registerCommand("cancel-dialogs", {
		async handler(_args: string, ctx: any) {
			const timed = await ctx.ui.input("Timed input", undefined, { timeout: 100 });
			const controller = new AbortController();
			setTimeout(() => controller.abort(), 100);
			const aborted = await ctx.ui.select(
				"Aborted select",
				["one", "two"],
				{ signal: controller.signal },
			);
			ctx.ui.notify(
				`dialog-cancelled:${timed ?? "timeout"}|${aborted ?? "aborted"}`,
				"info",
			);
		},
	});
}
