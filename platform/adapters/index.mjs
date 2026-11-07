/**
 * 适配器模块入口（CONTRACT §8）。
 *
 *   adapters/index.mjs → createMockAdapter(opts)、createAcpAdapter(opts)、buildCapabilityMatrix(instances)
 *
 * 这里只做导出聚合，不含业务逻辑。
 */
export {
  AdapterError,
  ADAPTER_ERROR_CODES,
  BaseAdapter,
  CAPABILITY_VALUES,
  ProviderAdapter,
  classifyUpstreamError,
  isValidCapabilityValue,
  probeResult,
  rpcCodeFor,
  sideEffectFreeProbe,
} from "./base.mjs";

export {
  CAPABILITY_STATES,
  CapabilityMatrixError,
  EVIDENCE_KINDS,
  MATRIX_VERSION,
  RELEASE_STATES,
  buildCapabilityMatrix,
  capabilityState,
  checkCapabilityMatrix,
  computeSupportStates,
  deriveRelease,
  flattenCapabilityStates,
  instanceFromAdapter,
  isInstanceUsable,
  supportsCapability,
  validateCapabilityMatrix,
} from "./capability-matrix.mjs";

export {
  AcpAdapter,
  ACP_METHODS,
  ACP_PROTOCOL_VERSION,
  ACP_RPC_ERRORS,
  JsonRpcConnection,
  acpCapabilityDeclaration,
  createAcpAdapter,
} from "./acp.mjs";

export { MockAdapter, createMockAdapter, mockCapabilityDeclaration } from "./mock.mjs";

export {
  FixtureError,
  FIXTURE_SOURCES,
  FIXTURE_VERSION,
  REDACTION_RULE_IDS,
  ReplayAdapter,
  auditFixture,
  buildFixtureIndex,
  createFixture,
  createRecorder,
  fixtureCapabilities,
  loadFixture,
  parseFixture,
  redact,
  redactText,
  redactValue,
  replayFixture,
  validateFixture,
} from "./fixtures.mjs";

export {
  DEFAULT_ENV_WHITELIST,
  DEFAULT_MAX_OUTPUT_BYTES,
  DEFAULT_TIMEOUT_MS,
  SECRET_ENV_PATTERN,
  buildIsolatedEnv,
  resolveExecutable,
  runProcess,
  spawnProcess,
  terminateProcess,
  truncateText,
} from "./runtime.mjs";

export {
  SessionLifecycle,
  STEER_STRATEGIES,
  STOP_TRUST,
  createSessionLifecycle,
} from "./lifecycle.mjs";

export {
  INTERACTION_KINDS,
  INTERACTION_ORIGINS,
  InteractionError,
  InteractionRegistry,
  isInteractionExpired,
  interactionEventPayload,
  normalizePermissionRequest,
  normalizeQuestion,
} from "./interactions.mjs";

export {
  MODEL_SENTINELS,
  OPTION_STATES,
  applyOptions,
  assertRealModel,
  discoverOptions,
  isModelSentinel,
  reconcileAfterResume,
  resolveModelRequest,
  resolvePermissionMode,
  resolveReasoningEffort,
} from "./options.mjs";

export {
  ARTIFACT_SOURCES,
  ATTACHMENT_KINDS,
  DEFAULT_ATTACHMENT_LIMITS,
  artifactRef,
  assertSameEnvironment,
  attachmentPlan,
  attributeArtifact,
  classifyAttachment,
  diffAttribution,
  normalizeEnvironment,
  summarizeAttachments,
} from "./attachments.mjs";

export {
  ALLOWED_PROBE_ARGS,
  PROBE_POLICY,
  assertProbePolicy,
  createDiscoveryCache,
  discoverAgent,
  parseVersion,
} from "./discovery.mjs";

export {
  CLAUDE_CODE_BLOCKED_EVIDENCE,
  CLAUDE_CODE_PROTOCOL,
  CLAUDE_CODE_TASK_ID,
  ClaudeCodeAdapter,
  claudeCodeCapabilityDeclaration,
  createClaudeCodeAdapter,
} from "./claude-code.mjs";

export {
  CODEX_BLOCKED_EVIDENCE,
  CODEX_PROTOCOL,
  CODEX_TASK_ID,
  CodexAdapter,
  codexCapabilityDeclaration,
  createCodexAdapter,
} from "./codex.mjs";

/** 便捷：把已 probe 的适配器列表直接编译成矩阵。 */
export async function buildMatrixFromAdapters(adapters = [], { generatedAt = undefined } = {}) {
  const { buildCapabilityMatrix: build } = await import("./capability-matrix.mjs");
  const { instanceFromAdapter } = await import("./capability-matrix.mjs");
  return build(
    adapters.map((adapter) => instanceFromAdapter(adapter)),
    generatedAt ? { generatedAt } : {},
  );
}

export {
  DEEPSEEK_HARNESS_TASK_ID,
  DSH_HEADLESS_PROTOCOL,
  DeepSeekHarnessAdapter,
  createDeepSeekHarnessAdapter,
  deepseekHarnessCapabilityDeclaration,
} from "./deepseek-harness.mjs";

export {
  CODEX_APP_SERVER_TASK_ID,
  CODEX_APP_SERVER_PROTOCOL,
  CodexAppServerAdapter,
  codexAppServerCapabilityDeclaration,
  createCodexAppServerAdapter,
} from "./codex-app-server.mjs";

export {
  KNOWN_FLAGS,
  PROBES_DIR,
  PROBE_TASK_IDS,
  ProbeEvidenceError,
  listProbeEvidence,
  listRecordings,
  loadProbeEvidence,
  probeEvidenceForMatrix,
  validateProbeEvidence,
} from "./probe-evidence.mjs";

export {
  CODEX_EXEC_TASK_ID,
  CODEX_EXEC_PROTOCOL,
  CodexExecAdapter,
  codexExecCapabilityDeclaration,
  createCodexExecAdapter,
} from "./codex-exec.mjs";

export {
  CLAUDE_STREAM_TASK_ID,
  CLAUDE_STREAM_PROTOCOL,
  ClaudeStreamJsonAdapter,
  claudeStreamCapabilityDeclaration,
  createClaudeStreamJsonAdapter,
  mapClaudeFrame,
} from "./claude-stream-json.mjs";

export {
  ACP_PROFILES,
  AcpProfileError,
  agentCapabilitiesFromFixture,
  capabilitiesFromAcpHandshake,
  createAcpProfileAdapter,
  getAcpProfile,
} from "./acp-profiles.mjs";

export {
  INSTALL_CATALOG,
  INSTALL_CATALOG_VERSION,
  installPlanFor,
  listInstallCatalog,
  normalizePlatform,
} from "./install-catalog.mjs";

export {
  HEADLESS_PROFILES,
  createHeadlessProfileAdapter,
  getHeadlessProfile,
} from "./headless-profiles.mjs";
