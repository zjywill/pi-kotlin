#!/usr/bin/env node

import { readFileSync } from "node:fs";

const [path, rawRows, rawColumns, marker, rawRow, rawColumn] = process.argv.slice(2);
if (!path || !rawRows || !rawColumns || marker === undefined || rawRow === undefined || rawColumn === undefined) {
	console.error(
		"Usage: verify-terminal-screen.mjs <transcript> <rows> <columns> <marker> <row> <column>",
	);
	process.exit(2);
}

const rows = Number(rawRows);
const columns = Number(rawColumns);
const expectedRow = Number(rawRow);
const expectedColumn = Number(rawColumn);
const input = readFileSync(path, "utf8");
let screen = Array.from({ length: rows }, () => Array(columns).fill(" "));
let row = 0;
let column = 0;
let savedRow = 0;
let savedColumn = 0;
let matched = false;
const observedPositions = new Set();

function clampCursor() {
	row = Math.max(0, Math.min(rows - 1, row));
	column = Math.max(0, Math.min(columns, column));
}

function scrollIfNeeded() {
	while (row >= rows) {
		screen.shift();
		screen.push(Array(columns).fill(" "));
		row--;
	}
}

function checkMarker() {
	if (matched) return;
	for (let currentRow = 0; currentRow < rows; currentRow++) {
		const line = screen[currentRow].join("");
		let start = 0;
		while (start < columns) {
			const found = line.indexOf(marker, start);
			if (found < 0) break;
			observedPositions.add(`${currentRow}:${found}`);
			if (currentRow === expectedRow && found === expectedColumn) {
				matched = true;
				return;
			}
			start = found + 1;
		}
	}
}

function eraseDisplay(mode) {
	if (mode === 2 || mode === 3) {
		screen = Array.from({ length: rows }, () => Array(columns).fill(" "));
		return;
	}
	if (mode === 0) {
		for (let current = row; current < rows; current++) {
			const start = current === row ? column : 0;
			screen[current].fill(" ", start);
		}
		return;
	}
	if (mode === 1) {
		for (let current = 0; current <= row; current++) {
			const end = current === row ? Math.min(columns, column + 1) : columns;
			screen[current].fill(" ", 0, end);
		}
	}
}

function eraseLine(mode) {
	if (mode === 2) {
		screen[row].fill(" ");
	} else if (mode === 1) {
		screen[row].fill(" ", 0, Math.min(columns, column + 1));
	} else {
		screen[row].fill(" ", Math.min(columns, column));
	}
}

function parameter(values, index, fallback) {
	const value = Number(values[index]);
	return Number.isFinite(value) && value !== 0 ? value : fallback;
}

function handleCsi(parameters, final) {
	const normalized = parameters.replace(/^[?>!]/, "");
	const values = normalized.length === 0 ? [] : normalized.split(";");
	switch (final) {
		case "A":
			row -= parameter(values, 0, 1);
			break;
		case "B":
			row += parameter(values, 0, 1);
			break;
		case "C":
			column += parameter(values, 0, 1);
			break;
		case "D":
			column -= parameter(values, 0, 1);
			break;
		case "E":
			row += parameter(values, 0, 1);
			column = 0;
			break;
		case "F":
			row -= parameter(values, 0, 1);
			column = 0;
			break;
		case "G":
			column = parameter(values, 0, 1) - 1;
			break;
		case "H":
		case "f":
			row = parameter(values, 0, 1) - 1;
			column = parameter(values, 1, 1) - 1;
			break;
		case "J":
			eraseDisplay(parameter(values, 0, 0));
			break;
		case "K":
			eraseLine(parameter(values, 0, 0));
			break;
		case "s":
			savedRow = row;
			savedColumn = column;
			break;
		case "u":
			row = savedRow;
			column = savedColumn;
			break;
	}
	clampCursor();
	checkMarker();
}

function writeCharacter(character) {
	if (column >= columns) {
		column = 0;
		row++;
		scrollIfNeeded();
	}
	if (column < columns) {
		screen[row][column] = character;
		column++;
	}
	checkMarker();
}

for (let index = 0; index < input.length; index++) {
	const character = input[index];
	if (character === "\u001b") {
		const next = input[index + 1];
		if (next === "[") {
			let end = index + 2;
			while (end < input.length && !/[@-~]/.test(input[end])) end++;
			if (end < input.length) {
				handleCsi(input.slice(index + 2, end), input[end]);
				index = end;
			}
			continue;
		}
		if (next === "]") {
			let end = index + 2;
			while (end < input.length) {
				if (input[end] === "\u0007") break;
				if (input[end] === "\u001b" && input[end + 1] === "\\") {
					end++;
					break;
				}
				end++;
			}
			index = end;
			continue;
		}
		if (next !== undefined) index++;
		continue;
	}
	if (character === "\r") {
		column = 0;
	} else if (character === "\n") {
		row++;
		scrollIfNeeded();
	} else if (character === "\b") {
		column = Math.max(0, column - 1);
	} else if (character === "\t") {
		column = Math.min(columns, column + (8 - (column % 8)));
	} else if (character >= " " && character !== "\u007f") {
		writeCharacter(character);
	}
}

if (!matched) {
	const observed = [...observedPositions].join(", ") || "none";
	console.error(
		`Terminal transcript never placed ${JSON.stringify(marker)} at row ${expectedRow}, column ${expectedColumn}; observed: ${observed}.`,
	);
	process.exit(1);
}

console.log(
	`Terminal placement passed for ${JSON.stringify(marker)} at row ${expectedRow}, column ${expectedColumn}.`,
);
