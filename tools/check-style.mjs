#!/usr/bin/env node
/**
 * 变更文件的确定性风格检查。
 *
 * 只查「不需要争论」的项：CRLF、制表符、行尾空白、JSON 可解析。
 * 之所以只查变更文件：存量代码还有历史格式问题（例如大量文件缺末尾换行），
 * 全量门禁会立刻变红；门禁一旦长期红着就等于没有门禁。detekt 仍是完整规则集，
 * 等基线建立后再接入阻塞。
 *
 * 用法：node tools/check-style.mjs [file ...]
 * 不传文件时，自动取 origin/dev...HEAD 与工作区改动。
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const TEXT_EXT = new Set([".kt", ".kts", ".mjs", ".js", ".json", ".yml", ".yaml", ".md", ".xml", ".gradle", ".properties"]);

function git(args) {
  try {
    return execFileSync("git", args, { encoding: "utf8" }).trim();
  } catch {
    return "";
  }
}

function candidates() {
  const fromArgs = process.argv.slice(2).filter(Boolean);
  if (fromArgs.length > 0) return fromArgs;
  const base = git(["merge-base", "origin/dev", "HEAD"]) || "HEAD~1";
  const committed = git(["diff", "--name-only", "--diff-filter=ACM", `${base}...HEAD`]).split("\n");
  const working = git(["diff", "--name-only", "--diff-filter=ACM", "HEAD"]).split("\n");
  return [...committed, ...working].filter(Boolean);
}

const problems = [];
for (const file of new Set(candidates())) {
  if (!fs.existsSync(file)) continue;
  if (!TEXT_EXT.has(path.extname(file))) continue;
  const text = fs.readFileSync(file, "utf8");
  if (text.includes("\r\n")) problems.push(`${file}: 使用了 CRLF 换行`);
  const lines = text.split("\n");
  lines.forEach((line, index) => {
    if (line.includes("\t")) problems.push(`${file}:${index + 1}: 含制表符`);
    if (/[ \t]+$/.test(line)) problems.push(`${file}:${index + 1}: 行尾有空白`);
  });
  if (path.extname(file) === ".json") {
    try {
      JSON.parse(text);
    } catch (error) {
      problems.push(`${file}: JSON 解析失败（${error.message}）`);
    }
  }
}

if (problems.length > 0) {
  for (const problem of problems) console.error(problem);
  console.error(`\n共 ${problems.length} 处风格问题`);
  process.exit(1);
}
console.log("风格检查通过");