/**
 * security 模块入口（CONTRACT.md §8）：
 * `loadSecrets(path)`、`redact(text)`、`normalizeWorkspacePath(p)` 为契约必需导出。
 */
export {
  SECRET_FILE_MODE,
  SECRET_DIR_MODE,
  SECRET_STORE_SCHEMA_VERSION,
  SecretError,
  ensureSecureDir,
  assertSecretPermissions,
  modeOf,
  writeSecretFile,
  readSecretFile,
  loadSecrets,
  createSecretStore,
  maskSecret,
  scanSecrets,
  assertNoSecrets,
  exportDiagnostics,
  safeError,
} from "./secrets.mjs";

export {
  REDACTED,
  redact,
  redactValue,
  sanitize,
  redactUrl,
  containsSecret,
  findSecretKinds,
  isSensitiveKey,
} from "./redact.mjs";

export {
  PATH_ERRORS,
  PathSecurityError,
  normalizeWorkspacePath,
  isPathInside,
  realpathOfNearestExisting,
  resolveWithinRoot,
  isWithinWorkspace,
  assertWithinWorkspace,
  checkRemoteFileRequest,
  canAccessWorkspacePath,
} from "./paths.mjs";

export {
  SCOPES,
  ALL_SCOPES,
  PRINCIPAL_KINDS,
  ScopeError,
  normalizeScopes,
  createScopeRegistry,
  AMBIENT_ENV_ALLOWLIST,
  detectAmbientCredentials,
  buildCleanEnv,
  createAccountInstance,
  createAccountRegistry,
  createAuditLog,
} from "./scopes.mjs";
