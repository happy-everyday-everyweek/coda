import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
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
    if (!existsSync(file)) writeFileSync(file, `${DEFAULT_SYSTEM_PROMPT_TEMPLATES[key]}\n`, "utf8");
  }
  return dir;
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