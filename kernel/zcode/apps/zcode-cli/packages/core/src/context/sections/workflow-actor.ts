// ============================================================
// Workflow Actor Identity Section Builder
// ============================================================
//
// 动态工作流子代理（workflow child）的身份段。它替换的是交互式的 Agent Identity，
// 不替换基座其他段：安全行与 `# Harness` 块与主 Agent 共用同一份模板。
//
// 开场句、工作契约、安全行、Harness 全部来自用户可编辑的模板文件，代码只负责拼接顺序。

import type { ContextSection, WorkflowActorContext } from "../types.js";
import { estimateTokens } from "../utils.js";
import {
  renderPromptTemplate,
  resolveSystemPromptTemplates,
  type SystemPromptTemplates,
} from "../system-prompts.js";

function buildWorkflowActorIdentityPrompt(
  actor: WorkflowActorContext,
  templates: SystemPromptTemplates,
): string {
  const name = actor.name?.trim();
  const opening = renderPromptTemplate(templates.workflowActorOpening, {
    nameClause: name ? `, named "${name}"` : "",
  }).trim();
  const persona = actor.persona?.trim();
  // 不再有 CLI prefix 走在前面，所以这一段就是 system 的第一行，不再以空行起头。
  const parts = [
    opening,
    ...(persona ? ["", persona] : []),
    "",
    templates.securityNotice.trim(),
    "",
    templates.harness.trim(),
    "",
    templates.workflowActorContract.trim(),
  ];
  return parts.join("\n");
}

export function buildWorkflowActorIdentitySection(
  actor: WorkflowActorContext,
  templates: SystemPromptTemplates = resolveSystemPromptTemplates(undefined),
): ContextSection {
  const content = buildWorkflowActorIdentityPrompt(actor, templates);
  return {
    name: "Workflow Actor Identity",
    source: "workflow_actor_identity",
    injectionTarget: "system",
    cacheHint: "stable",
    chars: content.length,
    tokens: estimateTokens(content),
    content,
    preview: content.slice(0, 100),
  };
}
