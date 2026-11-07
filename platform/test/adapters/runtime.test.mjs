/** AGT-102：适配器进程运行时的隔离环境、超时、终止、输出截断。 */
import test from "node:test";
import assert from "node:assert/strict";

import {
  buildIsolatedEnv,
  resolveExecutable,
  runProcess,
  spawnProcess,
  terminateProcess,
  truncateText,
} from "../../adapters/runtime.mjs";

test("隔离环境：凭据变量默认不继承，显式 allow 才放行", () => {
  const base = { ...process.env, GITHUB_TOKEN: "ghp_secret", MY_FLAG: "1" };
  const { env } = buildIsolatedEnv({ base, allow: ["MY_FLAG"] });
  assert.equal(env.GITHUB_TOKEN, undefined);
  assert.equal(env.MY_FLAG, "1");
  assert.ok(env.PATH);

  const explicit = buildIsolatedEnv({ base, allow: ["GITHUB_TOKEN"] });
  assert.equal(explicit.env.GITHUB_TOKEN, "ghp_secret");

  const overridden = buildIsolatedEnv({ base, override: { GITHUB_TOKEN: "x" } });
  assert.equal(overridden.env.GITHUB_TOKEN, "x");
});

test("PATH 白名单替换 PATH，不影响其它变量", () => {
  const { env, pathEntries } = buildIsolatedEnv({ base: process.env, pathAllowlist: ["/usr/bin", "/bin"] });
  const delimiter = process.platform === "win32" ? ";" : ":";
  assert.equal(env.PATH, ["/usr/bin", "/bin"].join(delimiter));
  assert.deepEqual(pathEntries, ["/usr/bin", "/bin"]);
});

test("resolveExecutable：绝对路径可直接用，找不到返回 null", () => {
  assert.equal(resolveExecutable(process.execPath), process.execPath);
  assert.equal(resolveExecutable("coda-definitely-not-real-binary-xyz"), null);
});

test("runProcess：捕获 stdout、非零退出码不抛错", async () => {
  const ok = await runProcess({ command: process.execPath, args: ["-e", "process.stdout.write('hello')"] });
  assert.equal(ok.ok, true);
  assert.equal(ok.stdout, "hello");
  assert.equal(ok.code, 0);
  assert.equal(ok.timedOut, false);

  const failed = await runProcess({ command: process.execPath, args: ["-e", "process.exit(3)"] });
  assert.equal(failed.ok, false);
  assert.equal(failed.code, 3);
});

test("runProcess：超时会杀掉进程并如实标记 timedOut", async () => {
  const started = Date.now();
  const result = await runProcess({
    command: process.execPath,
    args: ["-e", "setTimeout(() => {}, 10000)"],
    timeoutMs: 200,
  });
  assert.equal(result.timedOut, true);
  assert.equal(result.ok, false);
  assert.ok(Date.now() - started < 5000, "must not wait for the full child lifetime");
});

test("runProcess：输出有界截断并保留总字节数", async () => {
  const result = await runProcess({
    command: process.execPath,
    args: ["-e", "process.stdout.write('x'.repeat(100000))"],
    maxOutputBytes: 1024,
  });
  assert.equal(result.stdoutTruncated, true);
  assert.equal(result.stdout.length, 1024);
  assert.ok(result.stdoutBytes >= 100000, `counted ${result.stdoutBytes} bytes`);
  assert.ok(result.stdout.startsWith("xxx"));
});

test("runProcess：PATH 白名单里找不到可执行文件时报 NOT_INSTALLED", async () => {
  await assert.rejects(
    runProcess({
      command: "coda-definitely-not-real-binary-xyz",
      pathAllowlist: ["/usr/bin", "/bin"],
    }),
    (err) => err.code === "NOT_INSTALLED",
  );
});

test("runProcess：stdin 输入可传递", async () => {
  const result = await runProcess({
    command: process.execPath,
    args: [
      "-e",
      "let d='';process.stdin.on('data',(c)=>{d+=c}).on('end',()=>process.stdout.write(d.toUpperCase()))",
    ],
    stdin: "abc",
  });
  assert.equal(result.stdout, "ABC");
});

test("terminateProcess：长驻进程可被终止", async () => {
  const child = spawnProcess({ command: process.execPath, args: ["-e", "setInterval(() => {}, 1000)"] });
  const result = await terminateProcess(child, { graceMs: 200 });
  assert.equal(result.killed, true);
  assert.ok(child.exitCode !== null || child.signalCode !== null);
});

test("truncateText 有界", () => {
  assert.deepEqual(truncateText("abc", 10), { text: "abc", truncated: false });
  const big = truncateText("x".repeat(100), 10);
  assert.equal(big.truncated, true);
  assert.equal(big.text.length, 10);
});
