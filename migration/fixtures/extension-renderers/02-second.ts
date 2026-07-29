export default function (pi) {
	pi.registerMessageRenderer("oracle-message", () => ({
		render() {
			return ["second-message"];
		},
	}));
	pi.registerEntryRenderer("oracle-entry", () => ({
		render() {
			return ["second-entry"];
		},
	}));
}
