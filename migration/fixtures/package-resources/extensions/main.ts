export default function packageSmokeExtension(pi) {
	pi.registerFlag("package-smoke-flag", {
		type: "boolean",
		description: "Verify package extension flags",
		default: false,
	});
	pi.registerCommand("package-extension-smoke", {
		description: "Verify package-sourced extension loading",
		async handler(_args, ctx) {
			ctx.ui.notify("package extension command executed", "info");
		},
	});
}
