/**
 * 权限范围、撤销与多账号隔离（SEC-103 / SEC-104 / SEC-003 / SEC-004 / SEC-005）。
 *
 * 关键语义：
 * - 设备配对、Web 客户端、PA、外部 Agent 是不同 principal，各自持有可撤销 grant。
 * - 撤销立即影响新请求（authorize 每次检查），但**不伪造**已运行任务已停止：
 *   撤销返回的 in-flight 列表状态一律标记为 unknown，由执行节点核对。
 * - 未配置账号实例不继承宿主 ambient credentials；凭据不得跨账号静默共用。
 */
import { createHash, randomUUID } from "node:crypto";

export const SCOPES = Object.freeze({
  sessionRead: "session.read",
  sessionControl: "session.control",
  interactionAnswer: "interaction.answer",
  terminalControl: "terminal.control",
  filesRead: "files.read",
  filesWrite: "files.write",
  artifactRead: "artifact.read",
  deviceManage: "device.manage",
  coreMaintain: "core.maintain",
  diagnosticsRead: "diagnostics.read",
});

export const ALL_SCOPES = Object.freeze(Object.values(SCOPES));
export const PRINCIPAL_KINDS = Object.freeze(["ui", "remote-client", "external-agent", "device", "pa"]);

const SCOPE_SET = new Set(ALL_SCOPES);

export class ScopeError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.name = "ScopeError";
    this.code = code;
    this.details = details;
  }
}

/** 归一化 scope 列表；未知 scope 直接拒绝（不能靠拼写错误获得权限）。 */
export function normalizeScopes(scopes, { kind = "ui" } = {}) {
  if (!Array.isArray(scopes) || scopes.length === 0) {
    throw new ScopeError("SCOPES_REQUIRED", "at least one scope is required");
  }
  if (!PRINCIPAL_KINDS.includes(kind)) throw new ScopeError("UNKNOWN_PRINCIPAL_KIND", `unknown kind ${kind}`);
  const out = new Set();
  for (const scope of scopes) {
    if (scope === "*") {
      if (kind !== "ui") {
        throw new ScopeError("WILDCARD_NOT_ALLOWED", `wildcard scope is not allowed for ${kind}`);
      }
      for (const s of ALL_SCOPES) out.add(s);
      continue;
    }
    if (!SCOPE_SET.has(scope)) throw new ScopeError("UNKNOWN_SCOPE", `unknown scope: ${scope}`);
    out.add(scope);
  }
  return [...out];
}

function isoAt(clock) {
  return new Date(clock()).toISOString();
}

/**
 * 权限注册表。grant 记录可撤销/可过期；authorize 每请求校验。
 */
export function createScopeRegistry({ clock = Date.now } = {}) {
  /** @type {Map<string, object>} */
  const grants = new Map();

  const findGrant = (principalId) => grants.get(principalId) ?? null;

  return {
    grant({
      principalId = `principal_${randomUUID()}`,
      kind = "ui",
      scopes,
      deviceId = null,
      sessionId = null,
      expiresAt = null,
      grantedBy = "user",
      label = null,
    } = {}) {
      const normalized = normalizeScopes(scopes, { kind });
      if (expiresAt !== null && Number.isNaN(Date.parse(expiresAt))) {
        throw new ScopeError("INVALID_EXPIRY", `invalid expiresAt: ${expiresAt}`);
      }
      const grant = {
        principalId,
        kind,
        scopes: normalized,
        deviceId,
        sessionId,
        grantedBy,
        label,
        grantedAt: isoAt(clock),
        expiresAt,
        revokedAt: null,
        revokedReason: null,
        version: 1,
      };
      grants.set(principalId, grant);
      return { ...grant, scopes: [...grant.scopes] };
    },

    /** 扩权必须显式再授权，Socket 已认证不会自动获得新 scope。 */
    extend(principalId, scopes) {
      const grant = findGrant(principalId);
      if (!grant) throw new ScopeError("UNKNOWN_PRINCIPAL", `unknown principal ${principalId}`);
      if (grant.revokedAt) throw new ScopeError("GRANT_REVOKED", `principal ${principalId} is revoked`);
      const added = normalizeScopes(scopes, { kind: grant.kind }).filter((s) => !grant.scopes.includes(s));
      grant.scopes = [...new Set([...grant.scopes, ...added])];
      grant.version += 1;
      return { principalId, scopes: [...grant.scopes], added };
    },

    authorize(principalId, scope, { at = clock(), sessionId = null } = {}) {
      const grant = findGrant(principalId);
      if (!grant) return { ok: false, code: "UNKNOWN_PRINCIPAL", principalId, scope };
      if (grant.revokedAt) {
        return { ok: false, code: "GRANT_REVOKED", principalId, scope, revokedAt: grant.revokedAt };
      }
      if (grant.expiresAt && Date.parse(grant.expiresAt) <= at) {
        return { ok: false, code: "GRANT_EXPIRED", principalId, scope, expiresAt: grant.expiresAt };
      }
      if (!SCOPE_SET.has(scope)) return { ok: false, code: "UNKNOWN_SCOPE", principalId, scope };
      if (!grant.scopes.includes(scope)) return { ok: false, code: "SCOPE_NOT_GRANTED", principalId, scope };
      if (grant.sessionId && sessionId && grant.sessionId !== sessionId) {
        return { ok: false, code: "SESSION_SCOPE_MISMATCH", principalId, scope, expected: grant.sessionId };
      }
      return { ok: true, principalId, scope, kind: grant.kind, deviceId: grant.deviceId };
    },

    requireScope(principalId, scope, opts = {}) {
      const result = this.authorize(principalId, scope, opts);
      if (!result.ok) {
        throw new ScopeError(result.code, `principal ${principalId} lacks ${scope} (${result.code})`, result);
      }
      return result;
    },

    /**
     * 撤销授权。立即拒绝新请求；进行中任务不伪报停止，统一标记 unknown。
     */
    revoke(principalId, { reason = "user_revoked", at = clock(), inFlight = [] } = {}) {
      const grant = findGrant(principalId);
      if (!grant) return { ok: false, code: "UNKNOWN_PRINCIPAL", principalId };
      grant.revokedAt = new Date(at).toISOString();
      grant.revokedReason = reason;
      grant.version += 1;
      return {
        ok: true,
        principalId,
        revokedAt: grant.revokedAt,
        reason,
        affectedInFlight: inFlight.map((task) => ({ ...task, status: "unknown", fakedStopped: false })),
        note: "revocation blocks new requests; running tasks are not reported as stopped",
      };
    },

    revokeAllForDevice(deviceId, opts = {}) {
      const revoked = [];
      for (const grant of grants.values()) {
        if (grant.deviceId === deviceId && !grant.revokedAt) {
          revoked.push(this.revoke(grant.principalId, opts));
        }
      }
      return revoked;
    },

    get(principalId) {
      const grant = findGrant(principalId);
      if (!grant) return null;
      return { ...grant, scopes: [...grant.scopes] };
    },

    list() {
      return [...grants.values()].map((g) => ({ ...g, scopes: [...g.scopes] }));
    },

    /** 某 principal 对外的投影：不含内部版本细节之外的敏感字段。 */
    projection(principalId, { at = clock() } = {}) {
      const grant = findGrant(principalId);
      if (!grant) return null;
      const active = !grant.revokedAt && (!grant.expiresAt || Date.parse(grant.expiresAt) > at);
      return {
        principalId,
        kind: grant.kind,
        deviceId: grant.deviceId,
        active,
        scopes: active ? [...grant.scopes] : [],
        revokedAt: grant.revokedAt,
      };
    },
  };
}

export const AMBIENT_ENV_ALLOWLIST = Object.freeze([
  "PATH",
  "HOME",
  "USER",
  "LOGNAME",
  "LANG",
  "LC_ALL",
  "LC_CTYPE",
  "TZ",
  "TERM",
  "SHELL",
  "PWD",
  "TMPDIR",
  "TEMP",
  "TMP",
  "XDG_RUNTIME_DIR",
  "DISPLAY",
  "SystemRoot",
  "ComSpec",
  "PATHEXT",
  "WINDIR",
]);

const ENV_CREDENTIAL_PATTERN =
  /(?:API[_-]?KEY|ACCESS[_-]?KEY|SECRET|TOKEN|PASSWORD|PASSWD|CREDENTIAL|AUTH|PRIVATE[_-]?KEY|SESSION[_-]?KEY|SSH_AUTH_SOCK|AWS_|AZURE_|GOOGLE_|GITHUB_|GITLAB_|OPENAI_|ANTHROPIC_|COHERE_|MISTRAL_|HF_|HUGGINGFACE_|DASHSCOPE_|MOONSHOT_|DEEPSEEK_|Z_AI_|OLLAMA_|VERTEX_|ASSISTANT_|CLAUDE_|CODEX_)/i;

const ENV_FORBIDDEN_ALWAYS = new Set([
  "NODE_OPTIONS",
  "NODE_PATH",
  "PYTHONSTARTUP",
  "PYTHONPATH",
  "BASH_ENV",
  "ENV",
  "LD_PRELOAD",
  "LD_LIBRARY_PATH",
  "DYLD_INSERT_LIBRARIES",
]);

/** 仅返回键名，绝不返回环境变量值。 */
export function detectAmbientCredentials(env = process.env) {
  const found = [];
  for (const key of Object.keys(env ?? {})) {
    if (ENV_CREDENTIAL_PATTERN.test(key)) found.push({ key, name: key.toLowerCase() });
  }
  return found;
}

/**
 * 构造干净的子进程/Agent 环境：
 * - 只保留 allowlist 中的非凭据变量；
 * - 强制剔除环境中的凭据与注入类变量；
 * - 账号凭据只能通过显式 credentials/env 传入（account-scoped）。
 */
export function buildCleanEnv({ ambient = process.env, explicit = {}, allow = [] } = {}) {
  const allowSet = new Set([...AMBIENT_ENV_ALLOWLIST, ...allow]);
  const out = {};
  for (const [key, value] of Object.entries(ambient ?? {})) {
    if (typeof value !== "string") continue;
    if (ENV_FORBIDDEN_ALWAYS.has(key)) continue;
    if (ENV_CREDENTIAL_PATTERN.test(key)) continue;
    if (!allowSet.has(key)) continue;
    out[key] = value;
  }
  for (const [key, value] of Object.entries(explicit ?? {})) {
    if (typeof value !== "string") continue;
    if (ENV_FORBIDDEN_ALWAYS.has(key)) {
      throw new ScopeError("FORBIDDEN_ENV", `explicit env key ${key} is not allowed`);
    }
    out[key] = value;
  }
  return out;
}

function envFingerprint(env) {
  const keys = Object.keys(env).sort();
  return createHash("sha256").update(keys.join(",")).digest("hex").slice(0, 16);
}

/**
 * 账号实例隔离（SEC-003）。env 中永远不含宿主凭据；凭据单独保存并绑定 owner。
 */
export function createAccountInstance({
  accountId = `account_${randomUUID()}`,
  provider,
  ambient = process.env,
  explicitEnv = {},
  credentials = {},
  clock = Date.now,
} = {}) {
  if (!provider) throw new ScopeError("PROVIDER_REQUIRED", "account provider required");
  const removed = detectAmbientCredentials(ambient).map((c) => c.key);
  const env = buildCleanEnv({ ambient, explicit: explicitEnv });
  const credentialEntries = Object.entries(credentials);
  for (const [key] of credentialEntries) {
    if (!/^[A-Za-z0-9_.:-]{1,128}$/.test(key)) {
      throw new ScopeError("INVALID_CREDENTIAL_KEY", `invalid credential key ${key}`);
    }
  }
  return {
    accountId,
    provider,
    env,
    credentials: Object.fromEntries(credentialEntries),
    createdAt: new Date(clock()).toISOString(),
    isolation: {
      ambientCredentialsRemoved: removed,
      envFingerprint: envFingerprint(env),
      credentialKeys: credentialEntries.map(([key]) => key),
    },
  };
}

/**
 * 多账号注册表：凭据与计费身份不能静默共用。
 */
export function createAccountRegistry({ clock = Date.now } = {}) {
  const accounts = new Map();
  const credentialOwners = new Map(); // credentialId → accountId
  const sessionOwners = new Map(); // sessionId → accountId

  return {
    register(account) {
      if (!account?.accountId) throw new ScopeError("INVALID_ACCOUNT", "accountId required");
      if (accounts.has(account.accountId)) {
        throw new ScopeError("ACCOUNT_EXISTS", `account ${account.accountId} already registered`);
      }
      accounts.set(account.accountId, account);
      return account;
    },
    get(accountId) {
      return accounts.get(accountId) ?? null;
    },
    list() {
      return [...accounts.values()];
    },
    /**
     * 绑定凭据。同一 credentialId 被第二个账号绑定视为静默共用计费/会话，必须拒绝。
     */
    bindCredential(accountId, { credentialId, provider, kind = "api-key" }) {
      const account = accounts.get(accountId);
      if (!account) throw new ScopeError("UNKNOWN_ACCOUNT", `unknown account ${accountId}`);
      if (!credentialId) throw new ScopeError("CREDENTIAL_REQUIRED", "credentialId required");
      const owner = credentialOwners.get(credentialId);
      if (owner && owner !== accountId) {
        throw new ScopeError(
          "CREDENTIAL_SHARED_ACROSS_ACCOUNTS",
          `credential ${credentialId} is already bound to account ${owner}; refusing silent sharing`,
          { credentialId, owner, requestedBy: accountId },
        );
      }
      if (provider && account.provider && provider !== account.provider) {
        throw new ScopeError(
          "PROVIDER_MISMATCH",
          `credential provider ${provider} does not match account provider ${account.provider}`,
        );
      }
      credentialOwners.set(credentialId, accountId);
      return { accountId, credentialId, provider, kind, boundAt: new Date(clock()).toISOString() };
    },
    credentialOwner(credentialId) {
      return credentialOwners.get(credentialId) ?? null;
    },
    openSession(accountId, sessionId) {
      const account = accounts.get(accountId);
      if (!account) throw new ScopeError("UNKNOWN_ACCOUNT", `unknown account ${accountId}`);
      const owner = sessionOwners.get(sessionId);
      if (owner && owner !== accountId) {
        throw new ScopeError("SESSION_ACCOUNT_CONFLICT", `session ${sessionId} already belongs to ${owner}`);
      }
      sessionOwners.set(sessionId, accountId);
      return { accountId, sessionId };
    },
    sessionOwner(sessionId) {
      return sessionOwners.get(sessionId) ?? null;
    },
    /** 两个账号实例不得共享凭据值，也不得把凭据类环境变量带进实例环境。 */
    assertIsolation(accountIdA, accountIdB) {
      const a = accounts.get(accountIdA);
      const b = accounts.get(accountIdB);
      if (!a || !b) throw new ScopeError("UNKNOWN_ACCOUNT", "both accounts must be registered");
      if (a.accountId === b.accountId) throw new ScopeError("SAME_ACCOUNT", "accounts must differ");
      const valuesA = new Map(
        Object.entries(a.credentials ?? {}).map(([key, value]) => [JSON.stringify(value), key]),
      );
      const sharedCredentials = Object.entries(b.credentials ?? {})
        .filter(([, value]) => valuesA.has(JSON.stringify(value)))
        .map(([key]) => key);
      if (sharedCredentials.length) {
        throw new ScopeError("CREDENTIAL_SHARED_ACROSS_ACCOUNTS", "accounts share credential values", {
          sharedCredentials,
        });
      }
      const envCredA = Object.keys(a.env ?? {}).filter((k) => ENV_CREDENTIAL_PATTERN.test(k));
      const envCredB = Object.keys(b.env ?? {}).filter((k) => ENV_CREDENTIAL_PATTERN.test(k));
      if (envCredA.length || envCredB.length) {
        throw new ScopeError("AMBIENT_CREDENTIALS_PRESENT", "account env must not contain ambient credentials", {
          accountA: envCredA,
          accountB: envCredB,
        });
      }
      return true;
    },
  };
}

/**
 * 审计日志（SEC-005）：PA 自动决定与用户决定必须可区分。
 * 每条记录独立，不合并；actor 只能是 user/pa/system。
 */
export function createAuditLog({ clock = Date.now } = {}) {
  const entries = [];
  return {
    record({
      actor,
      principalId = null,
      action,
      target = null,
      decision = null,
      evidence = null,
      grantId = null,
    }) {
      if (!["user", "pa", "system"].includes(actor)) {
        throw new ScopeError("INVALID_ACTOR", `actor must be user|pa|system, got ${actor}`);
      }
      if (actor === "pa" && !principalId) {
        throw new ScopeError("PA_AUDIT_NEEDS_PRINCIPAL", "PA audit entries need a principalId for attribution");
      }
      const entry = {
        auditId: `audit_${randomUUID()}`,
        at: new Date(clock()).toISOString(),
        actor,
        principalId,
        action,
        target,
        decision,
        grantId,
        evidence,
      };
      entries.push(entry);
      return entry;
    },
    list({ actor = null, action = null, since = null } = {}) {
      return entries.filter(
        (e) =>
          (!actor || e.actor === actor) &&
          (!action || e.action === action) &&
          (!since || Date.parse(e.at) >= Date.parse(since)),
      );
    },
    countByActor() {
      return entries.reduce((acc, e) => {
        acc[e.actor] = (acc[e.actor] ?? 0) + 1;
        return acc;
      }, {});
    },
    clear() {
      entries.length = 0;
    },
  };
}
