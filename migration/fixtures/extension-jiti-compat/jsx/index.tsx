const React = {
	createElement(_type: string, _props: unknown, value: string) {
		return { value };
	},
};

const element = <span>jsx</span>;

export default function (pi: any) {
	pi.registerCommand("jiti-jsx", {
		description: element.value,
		handler() {},
	});
}
