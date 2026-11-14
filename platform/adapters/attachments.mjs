/**
 * 附件与成果归属（AGT-110 / ADP-015 / ADP-016 / SPEC 9.9）。
 *
 * 三条不能破的线：
 *   1. 附件/成果/Diff 都属于「节点 + 执行环境 + 工作区」，不同环境同名路径不得互认。
 *   2. 提示词里出现路径 ≠ 已授权读取；本模块只做引用与降级标注，不读文件。
 *   3. 大内容不进事件日志，只留 Artifact 引用与元数据（含 SHA-256）。
 */
import { AdapterError } from "./base.mjs";
import { makeId, nowIso } from "../contracts/ids.mjs";

export const ATTACHMENT_KINDS = ["image", "text", "binary"];

export const ARTIFACT_SOURCES = ["upstream", "git", "session"];

export const DEFAULT_ATTACHMENT_LIMITS = Object.freeze({
  maxNativeBytes: 20 * 1024 * 1024,
  maxInlineTextBytes: 256 * 1024,
  maxDiffBytes: 512 * 1024,
});

const ENVIRONMENT_PATTERN = /^(host|wsl:[A-Za-z0-9._-]+|remote:[A-Za-z0-9._-]+|android:[A-Za-z0-9._-]+|fixture)$/;

export function normalizeEnvironment(environment) {
  if (typeof environment !== "string" || !ENVIRONMENT_PATTERN.test(environment)) {
    throw new AdapterError("WORKSPACE_INVALID", `illegal execution environment: ${JSON.stringify(environment)}`, {
      details: { expected: "host | wsl:<distro> | remote:<nodeId> | android:<id> | fixture" },
    });
  }
  return environment;
}

export function assertSameEnvironment(left, right, { what = "artifact" } = {}) {
  if (left !== right) {
    throw new AdapterError(
      "WORKSPACE_INVALID",
      `${what} belongs to environment ${JSON.stringify(right)} but the session runs in ${JSON.stringify(left)}; refusing cross-environment access`,
      { details: { left, right } },
    );
  }
  return true;
}

function capsOf(capabilities) {
  if (!capabilities) return null;
  if (capabilities.capabilities) return capabilities.capabilities;
  return capabilities;
}

/** 按 MIME/扩展名粗分类；不打开文件。 */
export function classifyAttachment({ name = "", mimeType = null, size = 0 } = {}) {
  const mime = (mimeType ?? "").toLowerCase();
  if (mime.startsWith("image/")) return "image";
  if (mime.startsWith("text/") || mime === "application/json" || mime === "application/x-ndjson") return "text";
  const ext = String(name).toLowerCase().split(".").pop() ?? "";
  if (["png", "jpg", "jpeg", "gif", "webp", "bmp", "svg"].includes(ext)) return "image";
  if (["txt", "md", "json", "yaml", "yml", "toml", "js", "mjs", "ts", "tsx", "py", "rs", "go", "java", "sql", "sh"].includes(ext)) {
    return "text";
  }
  return "binary";
}

/**
 * 附件如何交给上游：
 *   native        —— 上游声明支持该类型输入
 *   text-reference—— 降级为文本引用（明确“路径不是访问授权”）
 *   unsupported   —— 超限或上游完全不支持
 */
export function attachmentPlan({ attachment = {}, capabilities = null, limits = DEFAULT_ATTACHMENT_LIMITS } = {}) {
  const caps = capsOf(capabilities)?.attachments ?? null;
  const kind = classifyAttachment(attachment);
  const size = Number(attachment.size ?? 0);
  const inputCaps = caps?.input;
  const nativeAllowed = inputCaps === true || inputCaps === "experimental";

  if (size > limits.maxNativeBytes) {
    return {
      mode: "unsupported",
      kind,
      nativeInput: false,
      reason: `attachment exceeds native limit (${limits.maxNativeBytes} bytes)`,
      note: "ask the user to shrink the file or provide a workspace path the agent may read itself",
    };
  }
  if (kind === "image" && nativeAllowed) {
    return { mode: "native", kind, nativeInput: true, reason: null, note: "sent as upstream native image input" };
  }
  return {
    mode: "text-reference",
    kind,
    nativeInput: false,
    reason: nativeAllowed ? "no native block for this kind" : "upstream did not declare native attachment input",
    note: "a path in the prompt is not an access grant; the agent must read the file itself",
  };
}

/**
 * 成果归属：必须记录执行环境与工作区，跨环境直接拒绝。
 */
export function attributeArtifact({
  artifactId = null,
  sessionId,
  sessionEnvironment,
  sessionWorkspace = null,
  artifactEnvironment,
  artifactWorkspace = null,
  name = null,
  kind = "binary",
  size = 0,
  sha256 = null,
  source = "upstream",
  createdBy = null,
} = {}) {
  if (!sessionId) throw new AdapterError("PROTOCOL_ERROR", "artifact attribution requires sessionId");
  normalizeEnvironment(sessionEnvironment);
  normalizeEnvironment(artifactEnvironment);
  assertSameEnvironment(sessionEnvironment, artifactEnvironment, { what: "artifact" });
  if (!ARTIFACT_SOURCES.includes(source)) {
    throw new AdapterError("PROTOCOL_ERROR", `illegal artifact source: ${source}`);
  }
  if (sessionWorkspace && artifactWorkspace && sessionWorkspace !== artifactWorkspace) {
    throw new AdapterError(
      "WORKSPACE_INVALID",
      `artifact workspace ${artifactWorkspace} does not match session workspace ${sessionWorkspace}`,
    );
  }
  return {
    artifactId: artifactId ?? makeId("art"),
    sessionId,
    environment: artifactEnvironment,
    workspace: artifactWorkspace ?? sessionWorkspace ?? null,
    name,
    kind,
    size: Number(size) || 0,
    sha256,
    source,
    createdBy,
    ownedBySession: true,
    attributedAt: nowIso(),
  };
}

/** 大内容只保留引用，正文不进事件日志。 */
export function artifactRef({ content = null, name = null, mimeType = null, limits = DEFAULT_ATTACHMENT_LIMITS } = {}) {
  const text = content === null || content === undefined ? null : String(content);
  const size = text === null ? 0 : Buffer.byteLength(text, "utf8");
  const omitted = size > limits.maxInlineTextBytes;
  return {
    name,
    mimeType,
    size,
    content: omitted ? null : text,
    contentOmitted: omitted,
    note: omitted ? "content exceeds inline limit; fetch by artifact reference instead" : null,
  };
}

/**
 * Diff 归属：优先上游原生 diff，否则在**该会话所属环境**的 Git 工作区计算。
 */
export function diffAttribution({
  sessionId,
  sessionEnvironment,
  workspacePath,
  diffEnvironment = null,
  workspace = null,
  source = "git",
  files = null,
  bytes = 0,
  limits = DEFAULT_ATTACHMENT_LIMITS,
} = {}) {
  if (!sessionId) throw new AdapterError("PROTOCOL_ERROR", "diff attribution requires sessionId");
  if (!ARTIFACT_SOURCES.includes(source)) throw new AdapterError("PROTOCOL_ERROR", `illegal diff source: ${source}`);
  if (!workspacePath) {
    throw new AdapterError("WORKSPACE_INVALID", "diff requires the owning workspace path; refusing to guess one");
  }
  if (diffEnvironment) assertSameEnvironment(sessionEnvironment, diffEnvironment, { what: "diff" });
  if (workspace && workspace !== workspacePath) {
    throw new AdapterError("WORKSPACE_INVALID", `diff workspace ${workspace} does not match ${workspacePath}`);
  }
  const truncated = Number(bytes) > limits.maxDiffBytes;
  return {
    sessionId,
    environment: sessionEnvironment,
    workspacePath,
    source,
    computedIn: sessionEnvironment,
    files: Array.isArray(files) ? files.slice(0, 500) : null,
    bytes: Number(bytes) || 0,
    truncated,
    note: truncated ? "diff exceeds inline budget; store as artifact" : null,
  };
}

/** 附件摘要（进事件 payload 用，不含正文）。 */
export function summarizeAttachments(attachments = []) {
  return attachments.map((a) => ({
    name: a.name ?? null,
    mimeType: a.mimeType ?? null,
    size: Number(a.size ?? 0),
    kind: classifyAttachment(a),
    sha256: a.sha256 ?? null,
  }));
}
