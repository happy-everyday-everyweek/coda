// ============================================================
// Memory Section Builder
// ============================================================
//
// 说明文案来自用户可编辑的 memory.md，`{{memoryRoot}}` 在渲染时替换成真实目录。

import type { ContextSection } from "../types.js";
import { estimateTokens } from "../utils.js";
import {
  renderPromptTemplate,
  resolveSystemPromptTemplates,
  type SystemPromptTemplates,
} from "../system-prompts.js";

export function buildMemorySection(
  memoryRoot: string | undefined,
  templates: SystemPromptTemplates = resolveSystemPromptTemplates(undefined),
): ContextSection | null {
  if (!memoryRoot) return null;

  const content = renderPromptTemplate(templates.memory, { memoryRoot }).trim();

  return {
    name: "Memory",
    source: "memory",
    injectionTarget: "system",
    cacheHint: "dynamic",
    chars: content.length,
    tokens: estimateTokens(content),
    content,
    preview: content.slice(0, 100),
  };
}
