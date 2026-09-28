#!/usr/bin/env node
/**
 * After a coding turn ends, ask (once) whether to open a Canvas analysis.
 * afterFileEdit sets a pending flag; stop consumes it.
 */
const fs = require("fs");
const path = require("path");

const STATE_DIR = path.join(__dirname, "state");
const PENDING = path.join(STATE_DIR, "pending-canvas-ask");
const MODE = process.argv[2] || "stop";

function readStdin() {
	try {
		return fs.readFileSync(0, "utf8");
	} catch {
		return "";
	}
}

function parseJson(raw) {
	try {
		return JSON.parse(raw || "{}");
	} catch {
		return {};
	}
}

function emit(obj) {
	process.stdout.write(JSON.stringify(obj) + "\n");
}

function isIgnorableEdit(filePath) {
	const normalized = String(filePath || "").replace(/\\/g, "/").toLowerCase();
	if (!normalized) {
		return true;
	}
	if (normalized.endsWith(".canvas.tsx")) {
		return true;
	}
	if (normalized.includes("/.cursor/hooks/")) {
		return true;
	}
	return false;
}

function markPending() {
	fs.mkdirSync(STATE_DIR, { recursive: true });
	fs.writeFileSync(PENDING, String(Date.now()), "utf8");
}

function consumePending() {
	if (!fs.existsSync(PENDING)) {
		return false;
	}
	try {
		fs.unlinkSync(PENDING);
	} catch {
		// still treat as pending so we do not get stuck
	}
	return true;
}

const payload = parseJson(readStdin());

if (MODE === "afterFileEdit") {
	if (!isIgnorableEdit(payload.file_path)) {
		markPending();
	}
	emit({});
	process.exit(0);
}

if (payload.status !== "completed" || Number(payload.loop_count || 0) > 0) {
	emit({});
	process.exit(0);
}

if (!consumePending()) {
	emit({});
	process.exit(0);
}

emit({
	followup_message:
		"这一轮已经改完代码。请用简体中文、一两句话询问用户：要不要用 Canvas 给出这次改动的详情分析（含饼图）？" +
		"用户没同意之前不要生成 Canvas。不要重复提问，也不要额外解释 hook。",
});
process.exit(0);
