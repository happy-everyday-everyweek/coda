import type { ContextBuilderConfig, ContextSection } from "./types.js";
import { estimateTokens } from "./utils.js";
import { resolveSystemPromptTemplates, type SystemPromptTemplates } from "./system-prompts.js";

/**
 * 会话能力指导段。
 *
 * 只有确实存在可调用技能时才注入；文案来自 session-guidance.md。
 */
export function buildSessionGuidanceSection(
  toolNames: readonly string[],
  hasSkills = false,
  templates: SystemPromptTemplates = resolveSystemPromptTemplates(undefined),
): ContextSection | null {
  const tools = new Set(toolNames);
  if (!(tools.has("Skill") && hasSkills)) {
    return null;
  }
  // 只有存在实际 session guidance 时才输出本段，避免向 simple branch 注入空标题。
  return createDynamicSection(
    "Session-specific guidance",
    "session_guidance",
    templates.sessionGuidance.trim(),
  );
}

/** 动态行为段，文案来自 dynamic-behavior.md。 */
export function buildDynamicBehaviorSection(
  templates: SystemPromptTemplates = resolveSystemPromptTemplates(undefined),
): ContextSection {
  return createDynamicSection(
    "Dynamic Behavior",
    "dynamic_behavior",
    templates.dynamicBehavior.trim(),
  );
}

/** Output Style 由用户在设置里选择，标题与正文仍然来自该选择本身。 */
export function buildOutputStyleSection(
  style: ContextBuilderConfig["outputStyle"],
): ContextSection | null {
  if (!style || style.prompt.trim().length === 0) return null;
  return createDynamicSection(
    "Output Style",
    "output_style",
    [`# Output Style: ${style.name}`, style.prompt.trim()].join("\n"),
  );
}

/** 长上下文管理段，文案来自 context-management.md。 */
export function buildContextManagementSection(
  templates: SystemPromptTemplates = resolveSystemPromptTemplates(undefined),
): ContextSection {
  return createDynamicSection(
    "Context Management",
    "context_management",
    templates.contextManagement.trim(),
  );
}

function createDynamicSection(
  name: string,
  source: ContextSection["source"],
  content: string,
): ContextSection {
  return {
    name,
    source,
    injectionTarget: "system",
    cacheHint: "dynamic",
    chars: content.length,
    tokens: estimateTokens(content),
    content,
    preview: content.slice(0, 100),
  };
}
