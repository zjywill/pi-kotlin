import { pathToFileURL } from "node:url";

const root = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const protocol = await import(pathToFileURL(`${root}/packages/protocol/dist/index.js`).href);

const hex = (bytes) => Buffer.from(bytes).toString("hex");
const rejects = (operation) => {
	try {
		operation();
		return false;
	} catch {
		return true;
	}
};
const clientHello = {
	type: "hello",
	version: protocol.PROTOCOL_VERSION,
};
const request = {
	type: "request",
	id: "request-1",
	request: { command: "list" },
};
const serverHello = {
	type: "hello",
	version: protocol.PROTOCOL_VERSION,
	connectionId: "connection-1",
	snapshot: {
		serverId: "server-1",
		protocolVersion: protocol.PROTOCOL_VERSION,
		revision: 0,
		sessions: [],
		models: [],
	},
};

const wire = Buffer.concat([
	Buffer.from(protocol.encodeClientMessage(clientHello)),
	Buffer.from(protocol.encodeClientMessage(request)),
]);
const decoder = new protocol.ClientMessageDecoder();
const decoded = [];
for (const byte of wire) {
	decoded.push(...decoder.push(Uint8Array.of(byte)));
}
decoder.end();

const cyclic = {};
cyclic.self = cyclic;

const byteRoundTrip = protocol.decodeCbor(protocol.encodeCbor(Uint8Array.of(0, 1, 255)));

console.log(
	JSON.stringify({
		version: protocol.PROTOCOL_VERSION,
		cbor: {
			null: hex(protocol.encodeCbor(null)),
			false: hex(protocol.encodeCbor(false)),
			true: hex(protocol.encodeCbor(true)),
			integers: [-9007199254740991, -24, -1, 0, 23, 24, 255, 256, 9007199254740991].map((value) =>
				hex(protocol.encodeCbor(value)),
			),
			floats: [-0, 1.5, Number.MIN_VALUE].map((value) => hex(protocol.encodeCbor(value))),
			text: hex(protocol.encodeCbor("hello \u4e16\u754c")),
			bytes: hex(protocol.encodeCbor(Uint8Array.of(0, 1, 255))),
			array: hex(protocol.encodeCbor([1, "two", true, null])),
			object: hex(protocol.encodeCbor({ alpha: 1, beta: ["two", false] })),
			byteRoundTrip: [...byteRoundTrip],
		},
		protocol: {
			clientHello: hex(protocol.encodeClientMessage(clientHello)),
			request: hex(protocol.encodeClientMessage(request)),
			serverHello: hex(protocol.encodeServerMessage(serverHello)),
			incrementalDecoded: decoded,
			supportedVersions: [1, 2, 2.5, Number.NaN].map((value) =>
				protocol.isSupportedProtocolVersion(value),
			),
		},
		rejections: {
			cycle: rejects(() => protocol.encodeCbor(cyclic)),
			trailingCbor: rejects(() => protocol.decodeCbor(Uint8Array.of(0xf6, 0xf6))),
			credentialField: rejects(() => protocol.parseClientMessage({ ...clientHello, token: "secret" })),
			extraHelloField: rejects(() => protocol.parseClientMessage({ ...clientHello, extra: true })),
			shortFrameLimit: rejects(() => protocol.encodeClientMessage(clientHello, { maxFrameLength: 8 })),
		},
	}),
);
