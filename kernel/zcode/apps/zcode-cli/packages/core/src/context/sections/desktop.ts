import type { ContextSection } from "../types.js";
import { estimateTokens } from "../utils.js";
import { resolveSystemPromptTemplates, type SystemPromptTemplates } from "../system-prompts.js";

/** 桌面形态渲染协议段，文案来自用户可编辑的 desktop-context.md。 */
export function buildDesktopContextSection(
  templates: SystemPromptTemplates = resolveSystemPromptTemplates(undefined),
): ContextSection {
  const content = templates.desktopContext.trim();

  return {
    name: "Coda Desktop Context",
    source: "desktop_context",
    injectionTarget: "system",
    cacheHint: "stable",
    chars: content.length,
    tokens: estimateTokens(content),
    content,
    preview: content.slice(0, 100),
  };
}
