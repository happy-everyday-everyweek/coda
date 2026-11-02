#!/usr/bin/env node
/**
 * tsc 不会把 .md 模板复制到 dist，而 CLI 的 esbuild 从 dist 读取 @zcode/core，
 * 所以构建后需要补一次拷贝。缺了它，打包时会报找不到 prompts/*.md。
 */
import { cp, mkdir } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const source = resolve(packageRoot, "src/context/prompts");
const target = resolve(packageRoot, "dist/context/prompts");

await mkdir(target, { recursive: true });
await cp(source, target, { recursive: true });
