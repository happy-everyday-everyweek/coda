/** CORE-111：管道终端、有界回放、detach/重连、resize 明确降级。 */
import test from "node:test";
import assert from "node:assert/strict";

import { TerminalService } from "../../core/terminal.mjs";
import { waitFor } from "../../fixtures/core/helpers.mjs";

const NODE = process.execPath;

test("管道终端可写 stdin、回放输出，detach 不杀进程", async () => {
  const terminal = new TerminalService({ clock: () => new Date(), maxBytes: 64 * 1024 });
  try {
    const view = terminal.open({
      cwd: process.cwd(),
      command: NODE,
      args: [
        "-e",
        "process.stdin.setEncoding('utf8');process.stdin.on('data',(d)=>{process.stdout.write('echo:'+d.trim()+'\\n')})",
      ],
    });
    assert.equal(view.pty, false);
    assert.equal(view.status, "running");

    terminal.write(view.id, "hello\n");
    const { matched, text } = await terminal.waitForOutput(view.id, { pattern: "echo:hello", timeoutMs: 5_000 });
    assert.equal(matched, true, `terminal output: ${JSON.stringify(text)}`);

    const replay = terminal.read(view.id, { fromOffset: 0 });
    assert.ok(replay.nextOffset > 0);
    assert.equal(replay.chunks.map((c) => c.data).join("").includes("echo:hello"), true);

    const detached = terminal.detach(view.id);
    assert.equal(detached.attached, false);
    assert.equal(detached.status, "running");
    assert.equal(terminal.list().length, 1);

    const attached = terminal.attach(view.id, { fromOffset: 0 });
    assert.equal(attached.attached, true);
    assert.ok(attached.replay.nextOffset > 0);

    terminal.close(view.id);
    await waitFor(() => terminal.list()[0]?.status === "exited", { label: "terminal exit", timeoutMs: 5_000 });
    assert.equal(terminal.list()[0].status, "exited");
  } finally {
    await terminal.closeAll();
  }
});

test("resize 明确返回 unsupported，不假装有 PTY", () => {
  const terminal = new TerminalService({ clock: () => new Date() });
  const view = terminal.open({ cwd: process.cwd(), command: NODE, args: ["-e", "setTimeout(()=>{},300)"] });
  try {
    const result = terminal.resize(view.id, 120, 40);
    assert.equal(result.supported, false);
    assert.equal(result.applied, false);
    assert.equal(result.mode, "pipes");
    assert.match(result.reason, /PTY unavailable/i);
    const capabilities = terminal.capabilities();
    assert.equal(capabilities.pty, false);
    assert.equal(capabilities.resize, "unsupported");
    assert.equal(capabilities.replay, true);
  } finally {
    terminal.dispose(view.id);
  }
});

test("环形缓冲容量上限：丢弃最旧输出并报告缺口", async () => {
  const terminal = new TerminalService({ clock: () => new Date(), maxBytes: 16 });
  const view = terminal.open({
    cwd: process.cwd(),
    command: NODE,
    args: ["-e", "process.stdout.write('x'.repeat(100))"],
  });
  try {
    await waitFor(() => terminal.list()[0]?.status === "exited", { label: "terminal exit", timeoutMs: 5_000 });
    const replay = terminal.read(view.id, { fromOffset: 0 });
    assert.equal(replay.truncated, true);
    assert.ok(replay.droppedBytes > 0);
    assert.equal(replay.totalBytes, 100);

    // 从当前末尾继续读不应再出现缺口。
    const tail = terminal.read(view.id, { fromOffset: replay.nextOffset });
    assert.equal(tail.truncated, false);
  } finally {
    await terminal.closeAll();
  }
});
