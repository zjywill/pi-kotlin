import { CustomEditor } from "@earendil-works/pi-coding-agent";
import { Key, matchesKey } from "@earendil-works/pi-tui";

const AUTOCOMPLETE_LABEL = "PTY_AC_ITEM";
const AUTOCOMPLETE_COMMAND = "/base-probe";
const BASE_LABEL = "PTY_BASE_OK";
const BASE_COMMAND = "/base-probe";

class ProbeEditor extends CustomEditor {
	private readonly onRestore: (text: string) => void;

	constructor(tui: unknown, theme: unknown, keybindings: unknown, onRestore: (text: string) => void) {
		super(tui, theme, keybindings);
		this.onRestore = onRestore;
	}

	handleInput(data: string): void {
		if (data === "|") {
			this.onRestore(this.getText());
			return;
		}
		super.handleInput(data);
	}

	render(width: number): string[] {
		return [`PTY_ED:${width}:${this.getText()}`];
	}
}

export default function interactiveTuiPty(pi: any) {
	pi.on("session_start", (_event: unknown, ctx: any) => {
		ctx.ui.setStatus("pty", "ready");
		ctx.ui.setHeader(() => ({
			render(width: number) {
				return [`PTY_HDR:${width}`];
			},
		}));
		ctx.ui.setWidget(
			"above",
			() => ({
				render(width: number) {
					return [`PTY_WA:${width}`];
				},
			}),
			{ placement: "aboveEditor" },
		);
		ctx.ui.setWidget(
			"below",
			() => ({
				render(width: number) {
					return [`PTY_WB:${width}`];
				},
			}),
			{ placement: "belowEditor" },
		);
		ctx.ui.setFooter((_tui: unknown, _theme: unknown, data: any) => ({
			render(width: number) {
				const status = [...data.getExtensionStatuses()]
					.map(([key, value]) => `${key}=${value}`)
					.join(",");
				return [`PTY_FTR:${width}:${status}`];
			},
		}));

		if (process.env.PI_TUI_PTY_SCENARIO === "core") {
			let removeRawInput = () => {};
			let rawProbeState = 0;
			removeRawInput = ctx.ui.onTerminalInput((data: string) => {
				if (rawProbeState === 0 && data === "~") {
					rawProbeState = 1;
					return { consume: true };
				}
				if (rawProbeState === 1 && data === "^") {
					rawProbeState = 2;
					return { data: "$" };
				}
				if (rawProbeState === 2 && data === "p") {
					rawProbeState = 3;
					setTimeout(() => {
						removeRawInput();
						ctx.ui.notify("PTY_RAW_OFF");
					}, 0);
					return undefined;
				}
				rawProbeState = 0;
				return undefined;
			});
		}

		ctx.ui.addAutocompleteProvider((current: any) => ({
			triggerCharacters: [...new Set([...(current.triggerCharacters ?? []), "$"])],
			async getSuggestions(
				lines: string[],
				cursorLine: number,
				cursorColumn: number,
				options: unknown,
			) {
				const line = lines[cursorLine] ?? "";
				const beforeCursor = line.slice(0, cursorColumn);
				if (beforeCursor.endsWith("#b")) {
					const base = await current.getSuggestions(["/hot"], 0, 4, { force: false });
					const delegated = base?.items?.some((item: { value?: string }) => item.value === "hotkeys");
					return {
						prefix: "#b",
						items: [
							{
								value: BASE_COMMAND,
								label: delegated ? BASE_LABEL : "PTY_BASE_BAD",
								description: "delegated built-in completion",
							},
						],
					};
				}
				const match = beforeCursor.match(/(?:^|[ \t])(\$([a-z]*))$/);
				if (!match || match[2].length === 0) {
					return current.getSuggestions(lines, cursorLine, cursorColumn, options);
				}
				return {
					prefix: match[1],
					items: [
						{
							value: AUTOCOMPLETE_COMMAND,
							label: AUTOCOMPLETE_LABEL,
							description: "interactive PTY completion",
						},
					],
				};
			},
			applyCompletion(
				lines: string[],
				cursorLine: number,
				cursorColumn: number,
				item: { label?: string },
				prefix: string,
			) {
				const command =
					item.label === AUTOCOMPLETE_LABEL
						? AUTOCOMPLETE_COMMAND
						: item.label === BASE_LABEL
							? BASE_COMMAND
							: undefined;
				if (command === undefined) {
					return current.applyCompletion(lines, cursorLine, cursorColumn, item, prefix);
				}
				const updated = [...lines];
				const line = updated[cursorLine] ?? "";
				const beforePrefix = line.slice(0, Math.max(0, cursorColumn - prefix.length));
				const afterCursor = line.slice(cursorColumn);
				updated[cursorLine] = beforePrefix + command + afterCursor;
				return {
					lines: updated,
					cursorLine,
					cursorColumn: beforePrefix.length + command.length,
				};
			},
			shouldTriggerFileCompletion(lines: string[], cursorLine: number, cursorColumn: number) {
				return current.shouldTriggerFileCompletion?.(lines, cursorLine, cursorColumn) ?? true;
			},
		}));
	});

	pi.registerCommand("base-probe", {
		handler: async (_args: string, ctx: any) => {
			ctx.ui.notify("PTY_BASE_DONE");
		},
	});

	pi.registerCommand("editor-on", {
		handler: async (_args: string, ctx: any) => {
			ctx.ui.setEditorComponent(
				(tui: unknown, theme: unknown, keybindings: unknown) =>
					new ProbeEditor(tui, theme, keybindings, (text) => {
						ctx.ui.setEditorComponent(undefined);
						ctx.ui.setEditorText("");
						ctx.ui.notify(`PTY_ED_OFF:${text}`);
					}),
			);
			ctx.ui.setEditorText("seed");
			ctx.ui.pasteToEditor("-paste");
			ctx.ui.notify("PTY_ED_ON");
		},
	});

	pi.registerCommand("overlay-probe", {
		handler: async (_args: string, ctx: any) => {
			let handle: any;
			let state = "boot";
			const result = await ctx.ui.custom(
				(tui: any, _theme: unknown, _keybindings: unknown, done: (value: string) => void) => ({
					render(width: number) {
						return [`PTY_OVR:${width}:${state}`.padEnd(width, ".")];
					},
					handleInput(data: string) {
						if (data === "u") {
							state = "unf";
							handle.unfocus({ target: null });
							setTimeout(() => handle.focus(), 120);
						} else if (data === "t") {
							state = "hide";
							handle.setHidden(true);
							setTimeout(() => {
								handle.setHidden(false);
								handle.focus();
							}, 120);
						} else if (data === "r") {
							state = "ready";
						} else if (matchesKey(data, Key.enter)) {
							done("ok");
						}
						tui.requestRender();
					},
				}),
				{
					overlay: true,
					overlayOptions: {
						anchor: "top-right",
						width: "50%",
						minWidth: 20,
						maxHeight: 3,
						offsetX: -1,
						margin: { top: 1, right: 2, bottom: 1, left: 1 },
					},
					onHandle(overlayHandle: any) {
						handle = overlayHandle;
						handle.setHidden(true);
						setTimeout(() => {
							handle.setHidden(false);
							handle.focus();
						}, 120);
					},
				},
			);
			ctx.ui.notify(`PTY_OVR_RESULT:${result}`);
		},
	});
}
