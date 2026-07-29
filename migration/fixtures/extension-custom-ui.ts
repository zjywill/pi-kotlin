import { Editor, Key, matchesKey } from "@earendil-works/pi-tui";

export default function extensionCustomUi(pi) {
	let widgetTui;
	const disposed = [];

	pi.on("session_start", (_event, ctx) => {
		ctx.ui.setStatus("phase", "ready");
		ctx.ui.setWidget("array", ["array-widget"]);
		ctx.ui.setWidget(
			"factory",
			(tui) => {
				widgetTui = tui;
				return {
					render(width) {
						return [`factory-widget:${width}`];
					},
					dispose() {
						disposed.push("widget");
					},
				};
			},
			{ placement: "belowEditor" },
		);
		ctx.ui.setHeader(() => ({
			render(width) {
				return [`custom-header:${width}`];
			},
			dispose() {
				disposed.push("header");
			},
		}));
		ctx.ui.setFooter((_tui, _theme, footerData) => ({
			render(width) {
				const statuses = [...footerData.getExtensionStatuses()]
					.map(([key, value]) => `${key}=${value}`)
					.join(",");
				return [`custom-footer:${width}:${statuses}`];
			},
			dispose() {
				disposed.push("footer");
			},
		}));
	});

	pi.registerCommand("refresh-clear", {
		handler: async (_args, ctx) => {
			widgetTui.requestRender();
			ctx.ui.setWidget("array", undefined);
			ctx.ui.setWidget("factory", undefined);
			ctx.ui.setHeader(undefined);
			ctx.ui.setFooter(undefined);
			ctx.ui.notify(`disposed:${disposed.join(",")}`);
		},
	});

	pi.registerCommand("choose", {
		handler: async (_args, ctx) => {
			let customDisposed = false;
			const result = await ctx.ui.custom((_tui, _theme, _keybindings, done) => {
				let selected = 0;
				return {
					render(width) {
						return [`custom-choice:${width}:${selected === 0 ? "alpha" : "beta"}`];
					},
					handleInput(input) {
						if (matchesKey(input, Key.down)) selected = 1;
						if (matchesKey(input, Key.enter)) done(selected === 0 ? "alpha" : "beta");
					},
					dispose() {
						customDisposed = true;
					},
				};
			});
			ctx.ui.notify(`custom-result:${result}:${customDisposed}`);
		},
	});

	pi.registerCommand("edit", {
		handler: async (_args, ctx) => {
			const result = await ctx.ui.custom((tui, _theme, keybindings, done) => {
				const editor = new Editor(tui, {}, keybindings);
				editor.onSubmit = done;
				return {
					render(width) {
						return [`custom-editor:${width}:${editor.getText()}`];
					},
					handleInput(input) {
						editor.handleInput(input);
					},
				};
			});
			ctx.ui.notify(`editor-result:${result}`);
		},
	});
}
