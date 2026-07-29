import { Box, Text, TruncatedText } from "@earendil-works/pi-tui";

export default function (pi) {
	pi.registerMessageRenderer("oracle-message", (message, { expanded, outputPad }, theme) => {
		const box = new Box(outputPad, 1, (text) => theme.bg("customMessageBg", text));
		box.addChild(
			new Text(
				`${theme.fg("accent", "first-message")}|${message.role}|${message.customType}|${message.content}|${message.display}|${message.details.source}|${message.timestamp}|${expanded}|${outputPad} wrap these words cleanly`,
				0,
				0,
			),
		);
		box.addChild(new TruncatedText("truncate this renderer line at the component width", 0, 0));
		return box;
	});
	pi.registerMessageRenderer("undefined-message", () => undefined);
	pi.registerMessageRenderer("throw-message", () => {
		throw new Error("message exploded");
	});

	pi.registerEntryRenderer("oracle-entry", (entry, { expanded }, theme) => {
		const box = new Box(1, 1, (text) => theme.bg("customMessageBg", text));
		box.addChild(
			new Text(
				`${theme.fg("accent", "first-entry")}|${entry.type}|${entry.id}|${entry.parentId}|${entry.timestamp}|${entry.customType}|${entry.data.value}|${expanded}`,
				0,
				0,
			),
		);
		return box;
	});
	pi.registerEntryRenderer("undefined-entry", () => undefined);
	pi.registerEntryRenderer("throw-entry", () => {
		throw new Error("entry exploded");
	});
}
