/**
 * 短码配对（SYNC-101 / SYNC-002）。
 *
 * 安全性质：
 * - 8 位 Crockford Base32（40 bit）+ 5 次尝试 + 5 分钟有效期；
 * - 常量时间比较（比较 SHA-256，而非原始字符串）；
 * - 一次性：成功即消费；超限/过期后即使输入正确也拒绝；
 * - 短码只存在于内存与用户屏幕，绝不出现在日志/诊断/同步消息中。
 *
 * 短码不是唯一信任来源：配对仍需设备指纹确认（identity.acceptPairing 强制）。
 */
import { createHash, randomInt, timingSafeEqual } from "node:crypto";

export const PAIRING_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"; // Crockford：无 I L O U
export const PAIRING_CODE_LENGTH = 8;
export const PAIRING_CODE_TTL_MS = 5 * 60_000;
export const PAIRING_MAX_ATTEMPTS = 5;

export class PairingError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.name = "PairingError";
    this.code = code;
    this.details = details;
  }
}

/** 用户输入归一化：去分隔符、大写、纠正 Crockford 易混字符。 */
export function normalizePairingCode(input) {
  if (typeof input !== "string") throw new PairingError("INVALID_CODE", "pairing code must be a string");
  const cleaned = input.toUpperCase().replace(/[^0-9A-Z]/g, "");
  if (cleaned.length === 0) throw new PairingError("INVALID_CODE", "pairing code is empty");
  if (cleaned.length > 64) throw new PairingError("INVALID_CODE", "pairing code is too long");
  return cleaned
    .replaceAll("I", "1")
    .replaceAll("L", "1")
    .replaceAll("O", "0")
    .replaceAll("U", "V");
}

export function isValidPairingCodeFormat(code) {
  if (typeof code !== "string" || code.length !== PAIRING_CODE_LENGTH) return false;
  return [...code].every((ch) => PAIRING_ALPHABET.includes(ch));
}

/** 展示形式：XXXX-XXXX。 */
export function formatPairingCode(code) {
  const normalized = normalizePairingCode(code);
  return normalized.length > 4 ? `${normalized.slice(0, 4)}-${normalized.slice(4)}` : normalized;
}

function randomChar(random) {
  if (typeof random === "function") {
    const value = random(PAIRING_ALPHABET.length);
    if (!Number.isInteger(value) || value < 0 || value >= PAIRING_ALPHABET.length) {
      throw new PairingError("INVALID_RANDOM", "random() must return an integer in [0, alphabet.length)");
    }
    return PAIRING_ALPHABET[value];
  }
  return PAIRING_ALPHABET[randomInt(PAIRING_ALPHABET.length)];
}

/**
 * 生成短码记录。`random` 可注入以做确定性测试。
 * 记录本身包含明文 code，调用方必须只保存在内存中。
 */
export function createPairingCode({
  length = PAIRING_CODE_LENGTH,
  ttlMs = PAIRING_CODE_TTL_MS,
  maxAttempts = PAIRING_MAX_ATTEMPTS,
  now = Date.now,
  random,
} = {}) {
  if (!Number.isInteger(length) || length < 6 || length > 32) {
    throw new PairingError("INVALID_LENGTH", "pairing code length must be 6..32");
  }
  if (!Number.isInteger(ttlMs) || ttlMs <= 0) throw new PairingError("INVALID_TTL", "ttlMs must be positive");
  if (!Number.isInteger(maxAttempts) || maxAttempts < 1) {
    throw new PairingError("INVALID_ATTEMPTS", "maxAttempts must be >= 1");
  }
  let code = "";
  for (let i = 0; i < length; i += 1) code += randomChar(random);
  const createdAt = now();
  return {
    code,
    normalized: normalizePairingCode(code),
    display: formatPairingCode(code),
    createdAt: new Date(createdAt).toISOString(),
    expiresAt: new Date(createdAt + ttlMs).toISOString(),
    maxAttempts,
    attempts: 0,
    usedAt: null,
    lockedAt: null,
  };
}

export function isPairingCodeExpired(record, at = Date.now()) {
  return Date.parse(record.expiresAt) <= at;
}

export function remainingPairingAttempts(record) {
  return Math.max(0, record.maxAttempts - record.attempts);
}

function sameCode(a, b) {
  const ha = createHash("sha256").update(String(a)).digest();
  const hb = createHash("sha256").update(String(b)).digest();
  return timingSafeEqual(ha, hb);
}

/**
 * 校验短码（不消费）。会消耗一次尝试次数。
 * @returns {{ok:boolean, code?:string, reason?:string, remainingAttempts:number, locked:boolean, expired:boolean}}
 */
export function verifyPairingCode(record, input, { at = Date.now() } = {}) {
  if (!record) throw new PairingError("UNKNOWN_OFFER", "pairing record required");
  const expired = isPairingCodeExpired(record, at);
  const locked = !!record.lockedAt || record.attempts >= record.maxAttempts;
  if (record.usedAt) {
    return { ok: false, reason: "PAIRING_USED", remainingAttempts: 0, locked, expired };
  }
  if (expired) return { ok: false, reason: "PAIRING_EXPIRED", remainingAttempts: remainingPairingAttempts(record), locked, expired };
  if (locked) return { ok: false, reason: "PAIRING_LOCKED", remainingAttempts: 0, locked, expired };

  let normalized;
  try {
    normalized = normalizePairingCode(input);
  } catch {
    normalized = null;
  }
  const validFormat = normalized !== null && isValidPairingCodeFormat(normalized);
  record.attempts += 1;
  const after = remainingPairingAttempts(record);
  if (!validFormat || !sameCode(normalized, record.normalized)) {
    if (after <= 0) record.lockedAt = new Date(at).toISOString();
    return {
      ok: false,
      reason: after <= 0 ? "PAIRING_LOCKED" : "PAIRING_CODE_INVALID",
      remainingAttempts: after,
      locked: after <= 0,
      expired,
    };
  }
  if (after < 0) {
    return { ok: false, reason: "PAIRING_LOCKED", remainingAttempts: 0, locked: true, expired };
  }
  return { ok: true, remainingAttempts: after, locked: false, expired };
}

/** 常量时间判断输入是否等于记录中的码；不消耗尝试次数（用于按码定位 offer）。 */
export function pairingCodesMatch(record, input) {
  if (!record) return false;
  let normalized;
  try {
    normalized = normalizePairingCode(input);
  } catch {
    return false;
  }
  if (!isValidPairingCodeFormat(normalized)) return false;
  return sameCode(normalized, record.normalized);
}

/** 校验成功则一次性消费。 */
export function consumePairingCode(record, input, { at = Date.now() } = {}) {
  const result = verifyPairingCode(record, input, { at });
  if (result.ok) record.usedAt = new Date(at).toISOString();
  return result;
}

/** 清理过期记录；返回仍有效的记录。 */
export function activePairingCodes(records, { at = Date.now() } = {}) {
  return records.filter((r) => !r.usedAt && !isPairingCodeExpired(r, at));
}
