import type { EnvInfo, Model } from "@zcode/contracts";
import { isEnvInfoGitRepository } from "../context/sections/env-info.js";
import {
  renderPromptTemplate,
  resolveSystemPromptTemplates,
  type SystemPromptTemplates,
} from "../context/system-prompts.js";

export interface SubagentEnvironmentContextOptions {
  agentPrompt: string;
  envInfo: EnvInfo;
  model?: Model;
}

/** 子代理通用注意事项，文案来自 subagent-notes.md。 */
export function buildSubagentCommonNotes(
  templates: SystemPromptTemplates = resolveSystemPromptTemplates(undefined),
): string {
  return templates.subagentNotes.trim();
}

/** 子代理环境说明，文案来自 subagent-environment.md，变量在渲染时替换。 */
export function buildSubagentEnvironmentContext(
  options: SubagentEnvironmentContextOptions,
  templates: SystemPromptTemplates = resolveSystemPromptTemplates(undefined),
): string {
  const { envInfo, model } = options;
  const modelLine = model
    ? `You are powered by the model named ${model.providerId}/${model.modelId}.`
    : "";

  return renderPromptTemplate(templates.subagentEnvironment, {
    cwd: envInfo.cwd,
    isGitRepo: isEnvInfoGitRepository(envInfo) ? "Yes" : "No",
    platform: envInfo.platform,
    shell: envInfo.shell,
    osVersion: envInfo.osVersion,
    modelLine,
  }).trim();
}
