// ============================================================
// CLI Prefix Section Builder
// ============================================================

import type { ContextSection } from "../types.js";
import { estimateTokens } from "../utils.js";
import { resolveSystemPromptTemplates, type SystemPromptTemplates } from "../system-prompts.js";

/** 产品身份前缀。文案来自用户可编辑的 cli-prefix.md。 */
export function buildCliPrefixSection(
  templates: SystemPromptTemplates = resolveSystemPromptTemplates(undefined),
): ContextSection {
  const content = templates.cliPrefix.trim();

  return {
    name: "CLI Prefix",
    source: "cli_prefix",
    injectionTarget: "system",
    cacheHint: "stable",
    chars: content.length,
    tokens: estimateTokens(content),
    content,
    preview: content.slice(0, 100),
  };
}
