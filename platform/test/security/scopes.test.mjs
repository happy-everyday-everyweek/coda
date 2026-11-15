/** SEC-103 / SEC-104 / SEC-003 / SEC-004：权限范围、撤销、ambient 凭据清理与多账号隔离。 */
import test from "node:test";
import assert from "node:assert/strict";

import {
  SCOPES,
  buildCleanEnv,
  createAccountInstance,
  createAccountRegistry,
  createAuditLog,
  createScopeRegistry,
  detectAmbientCredentials,
  normalizeScopes,
} from "../../security/scopes.mjs";

test("scope：只允许已知 scope；wildcard 仅 ui；授权/过期/会话范围/扩权", () => {
  assert.deepEqual(normalizeScopes([SCOPES.sessionRead, SCOPES.sessionRead]), [SCOPES.sessionRead]);
  assert.throws(() => normalizeScopes(["files.everything"]), (err) => err.code === "UNKNOWN_SCOPE");
  assert.throws(() => normalizeScopes(["*"], { kind: "external-agent" }), (err) => err.code === "WILDCARD_NOT_ALLOWED");

  let now = 1_000;
  const registry = createScopeRegistry({ clock: () => now });
  const grant = registry.grant({
    principalId: "remote-1",
    kind: "remote-client",
    scopes: [SCOPES.sessionRead, SCOPES.interactionAnswer],
    deviceId: "dev_remote",
    sessionId: "ses_1",
  });
  assert.equal(grant.scopes.length, 2);
  assert.equal(registry.authorize("remote-1", SCOPES.sessionRead, { sessionId: "ses_1" }).ok, true);
  assert.equal(registry.authorize("remote-1", SCOPES.sessionRead, { sessionId: "ses_2" }).code, "SESSION_SCOPE_MISMATCH");
  assert.equal(registry.authorize("remote-1", SCOPES.filesWrite).code, "SCOPE_NOT_GRANTED");
  assert.equal(registry.authorize("missing", SCOPES.sessionRead).code, "UNKNOWN_PRINCIPAL");
  assert.throws(
    () => registry.requireScope("remote-1", SCOPES.terminalControl),
    (err) => err.code === "SCOPE_NOT_GRANTED",
  );
  const extended = registry.extend("remote-1", [SCOPES.terminalControl]);
  assert.ok(extended.scopes.includes(SCOPES.terminalControl));
  assert.equal(registry.authorize("remote-1", SCOPES.terminalControl).ok, true);

  now += 10_000;
  const expiring = registry.grant({
    principalId: "remote-2",
    kind: "remote-client",
    scopes: [SCOPES.sessionRead],
    expiresAt: new Date(now - 1).toISOString(),
  });
  assert.ok(expiring.expiresAt);
  assert.equal(registry.authorize("remote-2", SCOPES.sessionRead).code, "GRANT_EXPIRED");
});

test("撤销：立即拒绝新请求；进行中任务标 unknown，不伪报已停止", () => {
  const registry = createScopeRegistry();
  registry.grant({
    principalId: "remote-device",
    kind: "device",
    scopes: [SCOPES.sessionRead, SCOPES.sessionControl],
    deviceId: "dev_1",
  });
  const revoked = registry.revoke("remote-device", {
    reason: "user_revoked",
    inFlight: [{ taskId: "turn_1", status: "running" }],
  });
  assert.equal(revoked.ok, true);
  assert.equal(revoked.affectedInFlight[0].status, "unknown");
  assert.equal(revoked.affectedInFlight[0].fakedStopped, false);
  assert.match(revoked.note, /not reported as stopped/);
  assert.equal(registry.authorize("remote-device", SCOPES.sessionRead).code, "GRANT_REVOKED");
  assert.equal(registry.projection("remote-device").active, false);
  assert.deepEqual(registry.projection("remote-device").scopes, []);

  registry.grant({ principalId: "web-1", kind: "remote-client", scopes: [SCOPES.sessionRead], deviceId: "dev_1" });
  const all = registry.revokeAllForDevice("dev_1", { reason: "device_revoked" });
  assert.ok(all.length >= 1);
  assert.equal(registry.authorize("web-1", SCOPES.sessionRead).code, "GRANT_REVOKED");
});

test("ambient credentials：白名单环境、剥离凭据、显式 env 单独传入", () => {
  const ambient = {
    PATH: "/usr/bin",
    HOME: "/home/user",
    LANG: "zh_CN.UTF-8",
    ANTHROPIC_API_KEY: "sk-ant-secret",
    OPENAI_API_KEY: "sk-secret",
    GITHUB_TOKEN: "ghp_secret",
    AWS_SECRET_ACCESS_KEY: "aws-secret",
    SSH_AUTH_SOCK: "/tmp/ssh.sock",
    NODE_OPTIONS: "--require evil",
    RANDOM_UNKNOWN: "x",
  };
  const detected = detectAmbientCredentials(ambient).map((entry) => entry.key);
  assert.ok(detected.includes("ANTHROPIC_API_KEY"));
  assert.ok(!JSON.stringify(detectAmbientCredentials(ambient)).includes("sk-ant-secret"));

  const env = buildCleanEnv({ ambient, explicit: { TERM: "xterm-256color" } });
  assert.equal(env.PATH, "/usr/bin");
  assert.equal(env.HOME, "/home/user");
  assert.equal(env.LANG, "zh_CN.UTF-8");
  assert.equal(env.TERM, "xterm-256color");
  for (const key of ["ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GITHUB_TOKEN", "AWS_SECRET_ACCESS_KEY", "SSH_AUTH_SOCK", "NODE_OPTIONS", "RANDOM_UNKNOWN"]) {
    assert.equal(Object.hasOwn(env, key), false, `${key} 不得继承`);
  }
  assert.throws(() => buildCleanEnv({ ambient, explicit: { NODE_OPTIONS: "--require evil" } }), /not allowed/);
});

test("多账号隔离：凭据不能跨账号静默共用；会话归属明确", () => {
  const ambient = { PATH: "/usr/bin", OPENAI_API_KEY: "ambient-should-not-leak" };
  const accountA = createAccountInstance({
    accountId: "acct_a",
    provider: "openai",
    ambient,
    explicitEnv: { TZ: "UTC" },
    credentials: { apiKey: "key-a" },
  });
  const accountB = createAccountInstance({
    accountId: "acct_b",
    provider: "openai",
    ambient,
    credentials: { apiKey: "key-b" },
  });
  assert.ok(accountA.isolation.ambientCredentialsRemoved.includes("OPENAI_API_KEY"));
  assert.equal(Object.hasOwn(accountA.env, "OPENAI_API_KEY"), false);
  assert.equal(accountA.env.TZ, "UTC");

  const registry = createAccountRegistry();
  registry.register(accountA);
  registry.register(accountB);
  registry.bindCredential("acct_a", { credentialId: "cred-shared", provider: "openai" });
  assert.throws(
    () => registry.bindCredential("acct_b", { credentialId: "cred-shared", provider: "openai" }),
    (err) => err.code === "CREDENTIAL_SHARED_ACROSS_ACCOUNTS",
  );
  assert.equal(registry.credentialOwner("cred-shared"), "acct_a");

  registry.openSession("acct_a", "ses_1");
  assert.throws(() => registry.openSession("acct_b", "ses_1"), (err) => err.code === "SESSION_ACCOUNT_CONFLICT");
  assert.equal(registry.assertIsolation("acct_a", "acct_b"), true);

  // 两个账号若复用同一凭据值，视为可能共用计费身份，必须拒绝。
  const sharedA = createAccountInstance({ accountId: "shared_a", provider: "p", ambient: {}, credentials: { key: "same-secret" } });
  const sharedB = createAccountInstance({ accountId: "shared_b", provider: "p", ambient: {}, credentials: { key: "same-secret" } });
  const sharedRegistry = createAccountRegistry();
  sharedRegistry.register(sharedA);
  sharedRegistry.register(sharedB);
  assert.throws(
    () => sharedRegistry.assertIsolation("shared_a", "shared_b"),
    (err) => err.code === "CREDENTIAL_SHARED_ACROSS_ACCOUNTS",
  );
});

test("SEC-005：PA 自动决定与用户决定在审计中可区分", () => {
  const audit = createAuditLog({ clock: () => 1_000 });
  const userEntry = audit.record({ actor: "user", action: "approval.allow", target: { interactionId: "int_1" } });
  const paEntry = audit.record({
    actor: "pa",
    principalId: "pa_local",
    action: "approval.allow",
    target: { interactionId: "int_2" },
    evidence: { policy: "auto-approve-readonly" },
  });
  assert.notEqual(userEntry.auditId, paEntry.auditId);
  assert.equal(audit.list({ actor: "pa" }).length, 1);
  assert.equal(audit.countByActor().user, 1);
  assert.equal(audit.countByActor().pa, 1);
  assert.throws(() => audit.record({ actor: "pa", action: "x" }), /principalId/);
  assert.throws(() => audit.record({ actor: "robot", action: "x" }), /actor must be/);
});
