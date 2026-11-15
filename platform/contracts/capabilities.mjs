/** 运行期能力声明（SPEC 8.1/8.2）：产品名不是能力。 */
export const CAPABILITY_KEYS = [
  "session", "thread", "turn", "streaming", "tools", "approvals",
  "plan", "subagent", "context", "checkpoint", "identity", "attachments",
];

/** 每类能力要么是布尔/状态字符串，要么是带细节的对象。 */
export function makeCapabilityDeclaration({ provider, version, environment = "host", capabilities = {} }) {
  if (!provider || typeof provider !== "string") throw new Error("provider required");
  if (!version || typeof version !== "string") throw new Error("version required");
  const missing = CAPABILITY_KEYS.filter((k) => !Object.hasOwn(capabilities, k));
  if (missing.length) throw new Error(`capabilities missing keys: ${missing.join(", ")}`);
  return { provider, version, environment, capabilities, declaredAt: new Date().toISOString() };
}

/** 支持 = 明确 true 或 experimental；unknown/unsupported 都不算。 */
export function supports(declaration, key, sub = null) {
  let v = declaration?.capabilities?.[key];
  if (v && typeof v === "object" && sub) v = v[sub];
  return v === true || v === "experimental";
}

export function supportState(declaration, key, sub = null) {
  let v = declaration?.capabilities?.[key];
  if (v && typeof v === "object" && sub) v = v[sub];
  if (v === true) return "supported";
  if (v === "experimental") return "experimental";
  if (v === false || v === "unsupported") return "unsupported";
  return "unknown";
}
