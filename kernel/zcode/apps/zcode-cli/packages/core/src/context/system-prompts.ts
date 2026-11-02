// ============================================================
// 系统提示词模板
// ============================================================
//
// 系统提示词里曾经写死在代码中的段落，全部移到 ./prompts/*.md。
// 运行时优先读取用户目录 `HOME/.zcode/system-prompts/<name>.md`，文件缺失时
// 用这里随包分发的默认模板；默认模板只作为种子与兜底，用户改文件即可生效。

import cliPrefix from "./prompts/cli-prefix.md";
import identityIntro from "./prompts/identity-intro.md";
import identityIntroOutputStyle from "./prompts/identity-intro-output-style.md";
import securityNotice from "./prompts/security-notice.md";
import harness from "./prompts/harness.md";
import dynamicBehavior from "./prompts/dynamic-behavior.md";
import sessionGuidance from "./prompts/session-guidance.md";
import contextManagement from "./prompts/context-management.md";
import memory from "./prompts/memory.md";
import desktopContext from "./prompts/desktop-context.md";
import subagentNotes from "./prompts/subagent-notes.md";
import subagentEnvironment from "./prompts/subagent-environment.md";
import workflowActorOpening from "./prompts/workflow-actor-opening.md";
import workflowActorContract from "./prompts/workflow-actor-contract.md";

export interface SystemPromptTemplates {
  /** 最前面的产品身份行。 */
  cliPrefix: string;
  /** 没有 Output Style 时的身份开场句。 */
  identityIntro: string;
  /** 有 Output Style 时的身份开场句。 */
  identityIntroOutputStyle: string;
  /** 安全边界 IMPORTANT 行。 */
  securityNotice: string;
  /** `# Harness` 稳定运行约束。 */
  harness: string;
  /** 动态行为段：沟通方式与不可逆操作边界。 */
  dynamicBehavior: string;
  /** 会话能力指导段（有可调用技能时才注入）。 */
  sessionGuidance: string;
  /** 长上下文管理段。 */
  contextManagement: string;
  /** Memory 段说明，支持 `{{memoryRoot}}` 占位。 */
  memory: string;
  /** 桌面形态渲染协议段。 */
  desktopContext: string;
  /** 子代理通用注意事项段。 */
  subagentNotes: string;
  /** 子代理环境说明段，支持 {{cwd}} 等占位。 */
  subagentEnvironment: string;
  /** 工作流子代理开场身份句，支持 `{{nameClause}}`。 */
  workflowActorOpening: string;
  /** 工作流子代理工作契约段。 */
  workflowActorContract: string;
}

export type SystemPromptTemplateKey = keyof SystemPromptTemplates;

/** 每段模板对应的可编辑文件名。 */
export const SYSTEM_PROMPT_FILES: Record<SystemPromptTemplateKey, string> = {
  cliPrefix: "cli-prefix.md",
  identityIntro: "identity-intro.md",
  identityIntroOutputStyle: "identity-intro-output-style.md",
  securityNotice: "security-notice.md",
  harness: "harness.md",
  dynamicBehavior: "dynamic-behavior.md",
  sessionGuidance: "session-guidance.md",
  contextManagement: "context-management.md",
  memory: "memory.md",
  desktopContext: "desktop-context.md",
  subagentNotes: "subagent-notes.md",
  subagentEnvironment: "subagent-environment.md",
  workflowActorOpening: "workflow-actor-opening.md",
  workflowActorContract: "workflow-actor-contract.md",
};

/** 用户可编辑的系统提示词目录，相对 HOME。 */
export const SYSTEM_PROMPT_DIR = ".zcode/system-prompts";

/** 随包分发的默认模板。 */
export const DEFAULT_SYSTEM_PROMPT_TEMPLATES: SystemPromptTemplates = {
  cliPrefix,
  identityIntro,
  identityIntroOutputStyle,
  securityNotice,
  harness,
  dynamicBehavior,
  sessionGuidance,
  contextManagement,
  memory,
  desktopContext,
  subagentNotes,
  subagentEnvironment,
  workflowActorOpening,
  workflowActorContract,
};

/** 文件缺失时回落到默认模板；空文件表示用户主动清空该段。 */
export function resolveSystemPromptTemplates(
  overrides: Partial<SystemPromptTemplates> | undefined,
): SystemPromptTemplates {
  const resolved = { ...DEFAULT_SYSTEM_PROMPT_TEMPLATES };
  if (!overrides) return resolved;
  for (const key of Object.keys(resolved) as SystemPromptTemplateKey[]) {
    const value = overrides[key];
    if (typeof value === "string" && value.trim().length > 0) resolved[key] = value;
  }
  return resolved;
}

/** 替换 `{{name}}` 占位；缺变量时保留原样，方便用户看出自己写错了。 */
export function renderPromptTemplate(template: string, vars: Record<string, string>): string {
  return template.replace(/\{\{(\w+)\}\}/g, (match, name: string) => vars[name] ?? match);
}
