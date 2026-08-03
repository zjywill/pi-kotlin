import { pathToFileURL } from "node:url";

const root = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const terminalImage = await import(pathToFileURL(`${root}/packages/tui/dist/terminal-image.js`).href);

const metadata = {
	imageId: 42,
	columns: 3,
	rows: 3,
	widthPx: 100,
	heightPx: 100,
};
terminalImage.registerKittyImageMetadata(metadata);
const kitty = terminalImage.encodeKitty("QUFB", {
	columns: metadata.columns,
	rows: metadata.rows,
	imageId: metadata.imageId,
	moveCursor: false,
});
const cropped = terminalImage.cropKittyImageLine(kitty, 2, 1);
const placement = terminalImage.getKittyImagePlacement(`left ${cropped} right`);
if (!placement) throw new Error("Expected Kitty placement");
const size = terminalImage.calculateImageCellSize({ widthPx: 100, heightPx: 50 }, 20, 10);

console.log(
	JSON.stringify({
		size,
		fallback: terminalImage.imageFallback("image/png", { widthPx: 100, heightPx: 50 }),
		kitty,
		cropped,
		placement: placement.sequence,
		replacement: placement.replacementLine,
		iterm2: terminalImage.encodeITerm2("QUFB", { width: 20 }),
		deleteImage: terminalImage.deleteKittyImage(42),
		deleteAll: terminalImage.deleteAllKittyImages(),
		deletePlacements: terminalImage.deleteAllKittyPlacements(),
	}),
);
