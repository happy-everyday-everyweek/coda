/** 端到端：TUI 通过 stdio 连真实核心（mock 适配器），建会话→发送→收到回复。 */
import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const platformDir = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const tui = join(platformDir, "clients", "tui", "main.mjs");
const core = join(platformDir, "core", "index.mjs");

test("TUI ↔ 真实核心：create → send → assistantReply", { timeout: 120000 }, () => {
  const dataDir = mkdtempSync(join(tmpdir(), "coda-e2e-"));
  try {
    const script = JSON.stringify([
      { action: "create", title: "E2E 任务" },
      { action: "send", text: "你好" },
      { action: "wait", until: { assistantReply: true }, timeoutMs: 20000 },
      { action: "render", view: "status" },
    ]);
    const result = spawnSync(
      process.execPath,
      [tui, "--core", `stdio:${process.execPath} ${core} --data-dir ${dataDir}`, "--script", script],
      { cwd: platformDir, encoding: "utf8", timeout: 90000, env: { ...process.env, NO_COLOR: "1" } },
    );
    const stdout = result.stdout ?? "";
    const lines = stdout.split("\n").filter((l) => l.trim().startsWith("{"));
    const records = lines.map((l) => { try { return JSON.parse(l); } catch { return null; } }).filter(Boolean);
    assert.equal(result.status, 0, `TUI 退出码 ${result.status}\nstdout=${stdout.slice(-1500)}\nstderr=${(result.stderr ?? "").slice(-1500)}`);

    const created = records.find((r) => r.type === "create" && r.ok);
    assert.ok(created, "没有建会话记录");
    assert.ok(created.sessionId, "create 没有返回 sessionId");

    const sent = records.find((r) => r.type === "send" && r.ok);
    assert.ok(sent, "没有发送成功记录");

    const waited = records.find((r) => r.type === "wait");
    assert.equal(waited?.ok, true, "没有等到 assistantReply");

    const rendered = records.find((r) => r.type === "render");
    assert.match(rendered?.text ?? "", /E2E 任务/);
    assert.match(rendered?.text ?? "", /会话 1/);
  } finally {
    rmSync(dataDir, { recursive: true, force: true });
  }
});
