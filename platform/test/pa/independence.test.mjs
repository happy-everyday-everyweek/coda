import test from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { createPaRunner } from "../../pa/runner.mjs";
import { fakeClock, fakeCore, fakeDirectAgent, makeTempDir, scriptedLlm } from "./helpers.mjs";

test("PA-106: 没有 Coding Agent / 共享核心时 PA 仍可对话", async (t) => {
  const runner = createPaRunner({
    dataDir: makeTempDir(t),
    nodeId: "dev_phone",
    llm: scriptedLlm([{ text: "手机本机也能聊" }]),
    clock: fakeClock(),
  });
  const result = await runner.sendUserMessage("在吗");
  assert.equal(result.status, "replied");
  assert.equal(result.reply.content, "手机本机也能聊");
  assert.equal(runner.diagnostics().hasCore, false);
  assert.equal(runner.diagnostics().hasCommandLayer, false);
  assert.equal(runner.history().length, 2);

  const query = await runner.tools.listSessions();
  assert.equal(query.ok, false);
  assert.equal(query.state, "unavailable");
  runner.close();
});

test("PA-004 / PA-106: PA 启动和停止不影响直接使用 Agent", async (t) => {
  const agent = fakeDirectAgent();
  agent.send("before");
  const runner = createPaRunner({
    dataDir: makeTempDir(t),
    nodeId: "dev_a",
    llm: scriptedLlm([{ text: "PA 回复" }]),
    clock: fakeClock(),
  });
  await runner.sendUserMessage("PA 工作");
  agent.send("during");
  runner.close();
  agent.send("after");
  assert.equal(agent.running, true);
  assert.deepEqual(agent.log, ["before", "during", "after"]);
});

test("PA-004: PA 不可用时直接 Agent 入口仍可用", async (t) => {
  const runner = createPaRunner({ dataDir: makeTempDir(t), nodeId: "dev_a", clock: fakeClock() });
  const blocked = await runner.tools.createSession({ agentInstanceId: "ai_demo" });
  assert.equal(blocked.ok, false);
  assert.equal(blocked.state, "unavailable");

  const agent = fakeDirectAgent();
  const direct = agent.send("直接操作 Agent，不经过 PA");
  assert.equal(direct.ok, true);
  assert.equal(agent.log.length, 1);

  const chat = await runner.sendUserMessage("PA 没有配置 LLM");
  assert.equal(chat.status, "no_llm");
  assert.equal(agent.log.length, 1);
  runner.close();
});

test("PA-106: PA 模块不 import adapters/core/clients，只依赖 node 内置与 contracts", () => {
  const paDir = join(import.meta.dirname, "../../pa");
  const files = readdirSync(paDir).filter((file) => file.endsWith(".mjs"));
  assert.ok(files.length >= 7);
  for (const file of files) {
    const source = readFileSync(join(paDir, file), "utf8");
    const specifiers = [
      ...source.matchAll(/from\s+["']([^"']+)["']/g),
      ...source.matchAll(/import\s*\(\s*["']([^"']+)["']/g),
    ].map((match) => match[1]);
    for (const specifier of specifiers) {
      assert.ok(
        specifier.startsWith("node:") || specifier.startsWith("../contracts/") || specifier.startsWith("./"),
        `${file} imports forbidden module: ${specifier}`,
      );
      assert.equal(/(^|\/)(adapters|core|clients)\//.test(specifier), false, `${file} imports ${specifier}`);
    }
  }
});

test("PA-001: PA 资料与消息存储在独立目录，不触碰 Agent 自己的状态", async (t) => {
  const agentDir = makeTempDir(t);
  const agentStatePath = join(agentDir, "agent-state.json");
  const agentState = JSON.stringify({ persona: "coding-agent", messages: ["keep me"] });
  writeFileSync(agentStatePath, agentState);

  const paDir = makeTempDir(t);
  const runner = createPaRunner({
    dataDir: paDir,
    nodeId: "dev_a",
    llm: scriptedLlm([{ text: "PA 回复" }]),
    clock: fakeClock(),
  });
  runner.updateProfile({ name: "只属于 PA 的名字", persona: "只属于 PA 的人设" });
  await runner.sendUserMessage("改 PA 不能改 Agent");

  assert.equal(readFileSync(agentStatePath, "utf8"), agentState);
  assert.deepEqual(readdirSync(agentDir), ["agent-state.json"]);
  assert.ok(existsSync(join(paDir, "pa.sqlite")));
  assert.match(runner.profile.id, /^pa_/);
  runner.close();
});

test("PA-106: 关闭 PA 不向核心派发任何命令", async (t) => {
  const core = fakeCore();
  const runner = createPaRunner({ dataDir: makeTempDir(t), nodeId: "dev_a", core, clock: fakeClock() });
  runner.close();
  assert.equal(core.rpcCalls.length, 0);
  assert.equal(core.executions, 0);
  assert.deepEqual(core.received, []);
});

test("PA-106: 模块入口导出 createPaRunner 且无重复导出", async () => {
  const module = await import("../../pa/index.mjs");
  assert.equal(typeof module.createPaRunner, "function");
  assert.equal(typeof module.createPaStore, "function");
  assert.equal(typeof module.mergePaMessages, "function");
  assert.equal(typeof module.importLegacyMigration, "function");
  assert.equal(typeof module.AUTONOMY_NOTICE, "string");
  assert.equal(typeof module.createAuditLog, "function");
});

test("PA-004: 直接 Agent 与 PA 可以并行工作，互不阻塞", async (t) => {
  const agent = fakeDirectAgent();
  const runner = createPaRunner({
    dataDir: makeTempDir(t),
    nodeId: "dev_a",
    llm: scriptedLlm([{ text: "PA 并行回复" }]),
    clock: fakeClock(),
  });
  const [paResult] = await Promise.all([
    runner.sendUserMessage("PA 并行消息"),
    Promise.resolve(agent.send("Agent 并行消息")),
  ]);
  assert.equal(paResult.status, "replied");
  assert.equal(agent.log.length, 1);
  assert.equal(agent.running, true);
  assert.equal(runner.history().length, 2);
  runner.close();
});
