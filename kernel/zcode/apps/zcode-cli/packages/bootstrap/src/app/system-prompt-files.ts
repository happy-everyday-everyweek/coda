import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import {
  DEFAULT_SYSTEM_PROMPT_TEMPLATES,
  SYSTEM_PROMPT_DIR,
  SYSTEM_PROMPT_FILES,
  type SystemPromptTemplateKey,
  type SystemPromptTemplates,
} from "@zcode/core";

/**
 * 系统提示词文件。
 *
 * 内核的系统提示词段落不再写死：首次启动把默认模板写到 `HOME/.zcode/system-prompts/`，
 * 之后每次启动都从这些文件读取。用户改文件即可改变系统提示词，不需要重新编译内核。
 */
export function ensureSystemPromptFiles(homeDir: string): string {
  const dir = join(homeDir, SYSTEM_PROMPT_DIR);
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
  for (const key of Object.keys(SYSTEM_PROMPT_FILES) as SystemPromptTemplateKey[]) {
    const file = join(dir, SYSTEM_PROMPT_FILES[key]);
    // 只补缺失文件：已存在的文件可能正是用户改过的内容，不能覆盖。
    if (!existsSync(file)) writeFileSync(file, templateBody(key), "utf8");
  }
  writeTemplateBaselines(dir);
  return dir;
}

/**
 * 模板的落盘正文。
 *
 * 释放与摘要必须用同一个函数产出：两边各自拼 `${template}\n` 时，
 * 任何一侧改了行尾或做了 trim，摘要就会静默失配，所有没动过的模板都显示「已修改」。
 */
function templateBody(key: SystemPromptTemplateKey): string {
  return `${DEFAULT_SYSTEM_PROMPT_TEMPLATES[key]}\n`;
}

/**
 * 默认模板的摘要清单文件名。
 *
 * 客户端要区分「内核释放的默认内容」与「用户改过的内容」。此前客户端用
 * 「第一次见到的内容即默认」做基线，任何在设置页打开之前发生的修改
 * （内核侧编辑、备份恢复、从无基线版本升级）都会被记成默认，真实改动被永久隐藏。
 * 由**释放模板的一方**写出权威摘要，客户端只做比对，不再猜。
 */
export const SYSTEM_PROMPT_BASELINE_FILE = ".template-baselines.json";

/** 计算每份默认模板的 sha256（按落盘内容，含结尾换行）。 */
export function templateBaselineDigests(): Record<string, string> {
  const out: Record<string, string> = {};
  for (const key of Object.keys(SYSTEM_PROMPT_FILES) as SystemPromptTemplateKey[]) {
    out[SYSTEM_PROMPT_FILES[key]] = createHash("sha256").update(templateBody(key), "utf8").digest("hex");
  }
  return out;
}

/**
 * 写出默认模板摘要清单。
 *
 * 清单形式是「文件名 → 该文件历次默认版本的摘要数组」。必须保留历史：
 * 释放只补缺失文件，已存在的文件永不覆盖，所以内核升级后磁盘上仍可能是
 * **旧版本**释放的默认内容。只记当前版本摘要的话，那些从未被用户碰过的文件
 * 会因为摘要失配而被报成「已修改」——正是 #742 要消除的误报，只是方向相反。
 *
 * 合并规则：读旧清单，把当前版本的摘要并进各自的历史集合（去重、保序）。
 * 内容没变就不写盘：客户端会在后台线程读它，无谓的重写会制造读到半截内容的窗口。
 * 真要写时走临时文件 + rename，与客户端 AgentPrompt.writeAtomic 同一套原子模式。
 */
function writeTemplateBaselines(dir: string): void {
  const file = join(dir, SYSTEM_PROMPT_BASELINE_FILE);
  try {
    const current = templateBaselineDigests();
    const previous = readBaselineHistory(file);
    const merged: Record<string, string[]> = {};
    let changed = false;
    for (const [name, digest] of Object.entries(current)) {
      const history = previous[name] ? [...previous[name]] : [];
      if (!history.includes(digest)) {
        history.push(digest);
        changed = true;
      }
      merged[name] = history;
    }
    // 旧清单里有、当前版本已不再释放的文件：保留其历史，避免升级后状态跳变。
    for (const [name, history] of Object.entries(previous)) {
      if (!(name in merged)) {
        merged[name] = history;
        changed = true;
      }
    }
    const body = JSON.stringify(merged);
    if (!changed && existsSync(file) && readFileSync(file, "utf8") === body) return;
    const tmp = `${file}.${process.pid}.tmp`;
    writeFileSync(tmp, body, "utf8");
    renameSync(tmp, file);
  } catch (err) {
    // 清单只服务于状态显示，写不进去时客户端回落到「未知」而不是误判默认。
    // 但不能完全静默：只读目录、ENOSPC 这类持续故障要能查。
    console.warn(`[system-prompts] 写入模板基线清单失败：${String(err)}`);
  }
}

/** 读历史清单；兼容早期的「文件名 → 单个摘要」形式。 */
function readBaselineHistory(file: string): Record<string, string[]> {
  try {
    if (!existsSync(file)) return {};
    const parsed = JSON.parse(readFileSync(file, "utf8")) as Record<string, unknown>;
    const out: Record<string, string[]> = {};
    for (const [name, value] of Object.entries(parsed)) {
      if (typeof value === "string") out[name] = [value];
      else if (Array.isArray(value)) out[name] = value.filter((v): v is string => typeof v === "string");
    }
    return out;
  } catch {
    return {};
  }
}

/** 读取用户目录下的模板；缺失的段落返回 undefined，由调用方回落到默认模板。 */
export function loadSystemPromptTemplates(homeDir: string | undefined): SystemPromptTemplates | undefined {
  if (!homeDir) return undefined;
  let dir: string;
  try {
    dir = ensureSystemPromptFiles(homeDir);
  } catch {
    return undefined;
  }
  const resolved = {} as SystemPromptTemplates;
  let any = false;
  for (const key of Object.keys(SYSTEM_PROMPT_FILES) as SystemPromptTemplateKey[]) {
    try {
      const text = readFileSync(join(dir, SYSTEM_PROMPT_FILES[key]), "utf8");
      // 空文件视为用户主动清空该段，不回落到默认文案。
      resolved[key] = text.trimEnd();
      any = true;
    } catch {
      // 读不到就交给默认模板。
    }
  }
  return any ? resolved : undefined;
}