// ============================================================
// Identity Section Builder
// ============================================================
//
// 身份段的文案来自用户可编辑的模板文件（identity-intro.md、security-notice.md、
// harness.md），代码只负责拼接顺序，不再持有固定文案。

import type { ContextSection } from "../types.js";
import type { OutputStylePromptConfig } from "../types.js";
import { estimateTokens } from "../utils.js";
import { resolveSystemPromptTemplates, type SystemPromptTemplates } from "../system-prompts.js";

/** 安全 IMPORTANT 行：交互式身份与工作流子代理身份共用同一份模板。 */
export function buildSecurityNotice(
  templates: SystemPromptTemplates = resolveSystemPromptTemplates(undefined),
): string {
  return templates.securityNotice.trim();
}

/**
 * `# Harness` 块：稳定运行时约束，不属于 output style 可替换的 coding instructions，
 * 工作流子代理身份也复用同一份模板。
 */
export function buildHarnessBlock(
  templates: SystemPromptTemplates = resolveSystemPromptTemplates(undefined),
): string {
  return templates.harness.trim();
}

function buildIdentityPrompt(
  outputStyle: OutputStylePromptConfig | undefined,
  templates: SystemPromptTemplates,
): string {
  const intro = (outputStyle ? templates.identityIntroOutputStyle : templates.identityIntro).trim();

  const identityLines = ["", intro, "", templates.securityNotice.trim()].join("\n");

  return [identityLines, "", templates.harness.trim()].join("\n");
}

export function buildIdentitySection(
  outputStyle?: OutputStylePromptConfig,
  templates: SystemPromptTemplates = resolveSystemPromptTemplates(undefined),
): ContextSection {
  const content = buildIdentityPrompt(outputStyle, templates);

  return {
    name: "Agent Identity",
    source: "identity",
    injectionTarget: "system",
    cacheHint: "stable",
    chars: content.length,
    tokens: estimateTokens(content),
    content,
    preview: content.slice(0, 100),
  };
}
