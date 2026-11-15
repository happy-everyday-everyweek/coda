#!/usr/bin/env node
/**
 * 三端冒烟：CLI（TUI）、Web UI、桌面共享核心。
 *
 * 目的不是测业务，而是确认「打包后还能起来」：命令能执行、Web 能监听并响应、
 * 核心模块能导入并暴露 createCore。CI 在打包前跑这一遍，避免发出装不上的包。
 */
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..");

function run(cmd, args, { timeoutMs = 60000, env = process.env } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { cwd: root, env });
    let out = "";
    let err = "";
    child.stdout.on("data", (chunk) => {
      out += chunk;
    });
    child.stderr.on("data", (chunk) => {
      err += chunk;
    });
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      reject(new Error(`${cmd} ${args.join(" ")} 超时`));
    }, timeoutMs);
    child.on("error", (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      resolve({ code, out, err });
    });
  });
}

async function checkCli() {
  const help = await run(process.execPath, ["clients/tui/main.mjs", "--help"]);
  assert.equal(help.code, 0, `CLI --help 退出码 ${help.code}: ${help.err}`);
  assert.match(help.out, /Coda TUI/, "CLI --help 没有输出用法");
  const run0 = await run(process.execPath, [
    "clients/tui/main.mjs",
    "--mock",
    "--no-color",
    "--script",
    '[{"action":"projection"}]',
  ]);
  assert.equal(run0.code, 0, `CLI mock 回合退出码 ${run0.code}: ${run0.err}`);
  assert.match(run0.out, /"type":"projection"/, "CLI mock 回合没有输出 projection");
  console.log("CLI 冒烟通过：--help 与 mock 回合");
}

function get(url) {
  return new Promise((resolve, reject) => {
    const req = http.get(url, (res) => {
      let body = "";
      res.setEncoding("utf8");
      res.on("data", (chunk) => {
        body += chunk;
      });
      res.on("end", () => resolve({ status: res.statusCode, body }));
    });
    req.on("error", reject);
    req.setTimeout(15000, () => req.destroy(new Error("HTTP 超时")));
  });
}

async function checkWeb() {
  const child = spawn(process.execPath, ["clients/web/server.mjs", "--mock", "--host", "127.0.0.1", "--port", "0"], {
    cwd: root,
    env: process.env,
  });
  let out = "";
  let err = "";
  child.stdout.on("data", (chunk) => {
    out += chunk;
  });
  child.stderr.on("data", (chunk) => {
    err += chunk;
  });
  try {
    const port = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`Web 启动超时: ${out}${err}`)), 30000);
      const onData = () => {
        const match = out.match(/LISTENING (\d+)/);
        if (match) {
          clearTimeout(timer);
          resolve(Number(match[1]));
        }
      };
      child.stdout.on("data", onData);
      child.on("close", (code) => {
        clearTimeout(timer);
        reject(new Error(`Web 进程提前退出（${code}）: ${err}`));
      });
    });
    const res = await get(`http://127.0.0.1:${port}/`);
    assert.equal(res.status, 200, `Web 首页状态码 ${res.status}`);
    assert.ok(res.body.length > 0, "Web 首页是空的");
    console.log(`Web UI 冒烟通过：127.0.0.1:${port} 返回 200`);
  } finally {
    child.kill("SIGKILL");
  }
}

async function checkCore() {
  const core = await import(path.join(root, "core/index.mjs"));
  assert.equal(typeof core.createCore, "function", "core/index.mjs 没导出 createCore");
  const rpc = await import(path.join(root, "contracts/rpc.mjs"));
  assert.ok(rpc.PROTOCOL_VERSION >= 1, "契约版本号不合法");
  console.log("桌面共享核心冒烟通过：createCore 与契约版本可用");
}

for (const check of [checkCore, checkCli, checkWeb]) {
  await check();
}
console.log("三端冒烟全部通过");
