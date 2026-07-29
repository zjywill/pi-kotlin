export default function (pi: any) {
	pi.registerMessageRenderer("pty-message", (message: any) => ({
		render(width: number) {
			return [`renderer-message:${width}:${message.content}`];
		},
	}));
	pi.registerEntryRenderer("pty-entry", (entry: any) => ({
		render(width: number) {
			return [`renderer-entry:${width}:${entry.data.value}`];
		},
	}));
	pi.on("session_start", () => {
		pi.sendMessage({
			customType: "pty-message",
			content: "visible",
			display: true,
		});
		pi.sendMessage({
			customType: "pty-message",
			content: "secret",
			display: false,
		});
		pi.appendEntry("pty-entry", { value: "durable" });
	});
}
